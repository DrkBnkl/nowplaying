package de.nowplaying.tablet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Vollbild-Anzeige der Now-Playing-Seite vom Raspberry.
 *
 * Die App fragt alle 3 Sekunden /api/now ab. Meldet der Server
 * "display":"off" (keine Musik), wird das Display
 * ausgeschaltet, bei "on" wieder eingeschaltet. Ist der Server laenger
 * als 2 Minuten nicht erreichbar, geht das Display ebenfalls aus.
 *
 * Wird das Display ohne Musik von Hand geweckt, geht es von selbst wieder
 * aus: nach 1 Minute, solange die Anzeige vorne ist (Einstellungsfenster
 * zu), nach 5 Minuten, wenn eine andere App offen ist.
 *
 * Ausschalten: mit Geraeteadministrator-Recht echt per lockNow(),
 * sonst Helligkeit auf 0 (Rueckfall, wie bei anderen Kiosk-Apps).
 */
public class MainActivity extends Activity {

    private static final String PREFS = "nowplaying";
    private static final String KEY_URL = "url";
    private static final long POLL_MS = 3000;
    private static final long RELOCK_MS = 60 * 1000;        // Anzeige vorne
    private static final long RELOCK_AWAY_MS = 5 * 60 * 1000; // andere App vorne
    private static final long UNREACHABLE_OFF_MS = 2 * 60 * 1000; // Server weg: Display aus

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean alive = true;
    private volatile String baseUrl;
    private String display = "on";
    private long wokenSince = 0;     // seit wann das Display trotz "off" an ist
    private boolean resumed = false;
    private AlertDialog settingsDialog;

    private DevicePolicyManager dpm;
    private ComponentName admin;
    private PowerManager pm;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

        // CPU und WLAN wach halten, damit die App auch bei dunklem Display
        // merkt, wenn Musik startet. Das Tablet haengt ohnehin am Strom.
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nowplaying:cpu");
        cpuLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nowplaying:wifi");
            wifiLock.acquire();
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) retryLoadLater();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                retryLoadLater();
            }
        });
        // Lange druecken (ca. 1 s) oeffnet die Einstellungen
        web.setLongClickable(true);
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showSettings();
                return true;
            }
        });
        setContentView(web);

        baseUrl = prefs.getString(KEY_URL, null);
        if (baseUrl == null) showSettings();
        else loadPage();
        startPoller();
    }

    // ---------------------------------------------------------------- Seite
    private void loadPage() {
        if (baseUrl != null) web.loadUrl(baseUrl + "/");
    }

    private void retryLoadLater() {
        ui.removeCallbacksAndMessages("retry");
        ui.postAtTime(new Runnable() {
            @Override
            public void run() { loadPage(); }
        }, "retry", android.os.SystemClock.uptimeMillis() + 10000);
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        // Zurueck-Taste ignorieren, die Anzeige soll stehen bleiben
    }

    // -------------------------------------------------------- Display an/aus
    private void startPoller() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                long failingSince = 0;
                while (alive) {
                    String url = baseUrl;
                    if (url != null) {
                        String state = null;
                        try {
                            state = new JSONObject(fetch(url + "/api/now")).optString("display", "on");
                            failingSince = 0;
                        } catch (Exception ignored) {
                            // Server nicht erreichbar: Zustand zunaechst beibehalten, nach
                            // UNREACHABLE_OFF_MS das Display ausschalten (kein Standbild fuer Stunden)
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (failingSince == 0) failingSince = now;
                            else if (now - failingSince >= UNREACHABLE_OFF_MS) state = "off";
                        }
                        if (state != null) {
                            final String d = state;
                            ui.post(new Runnable() {
                                @Override
                                public void run() { applyDisplay(d); }
                            });
                        }
                    }
                    try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
                }
            }
        }, "nowplaying-poller");
        t.setDaemon(true);
        t.start();
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private void applyDisplay(String d) {
        if (d.equals(display)) {
            if ("off".equals(d)) relockIfWoken();
            return;
        }
        display = d;
        wokenSince = 0;
        if ("off".equals(d)) turnOff();
        else turnOn();
    }

    /** Display wurde ohne Musik von Hand geweckt: nach einer Weile wieder aus. */
    @SuppressWarnings("deprecation")
    private void relockIfWoken() {
        if (!dpm.isAdminActive(admin) || !pm.isScreenOn()) {
            wokenSince = 0;
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (wokenSince == 0) wokenSince = now;
        boolean front = resumed && (settingsDialog == null || !settingsDialog.isShowing());
        if (now - wokenSince >= (front ? RELOCK_MS : RELOCK_AWAY_MS)) {
            wokenSince = 0;
            turnOff();
        }
    }

    private void turnOff() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web.onPause();
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            setBrightness(0f);
        }
    }

    private void turnOn() {
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        @SuppressWarnings("deprecation")
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "nowplaying:wake");
        wake.acquire(5000);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        web.onResume();
        hideSystemUi();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    // ---------------------------------------------------------- Einstellungen
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText("Adresse des Now-Playing-Servers auf dem Raspberry:");
        box.addView(hint);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(baseUrl != null ? baseUrl : "http://192.168.178.68:8080");
        box.addView(input);

        TextView status = new TextView(this);
        status.setPadding(0, pad / 2, 0, 0);
        status.setText(dpm.isAdminActive(admin)
                ? "Display-Steuerung: aktiv (Display geht ohne Musik wirklich aus)"
                : "Display-Steuerung: nicht aktiv (ohne Musik nur abgedunkelt)");
        box.addView(status);

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("Now Playing")
                .setView(box)
                .setPositiveButton("Speichern", (dialog, which) -> {
                    String u = input.getText().toString().trim();
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    if (!u.startsWith("http")) u = "http://" + u;
                    baseUrl = u;
                    prefs.edit().putString(KEY_URL, u).apply();
                    loadPage();
                })
                .setNeutralButton("Display-Steuerung aktivieren", (dialog, which) -> {
                    Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                    i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Damit Now Playing das Display ausschalten kann, wenn keine Musik läuft.");
                    startActivity(i);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        web.destroy();
        super.onDestroy();
    }
}
