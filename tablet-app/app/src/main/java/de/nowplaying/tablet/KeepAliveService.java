package de.nowplaying.tablet;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Haelt den Prozess der Anzeige am Leben und die Anzeige vorne.
 *
 * - Beendet Android den Prozess (Speichermangel), startet das System diesen Dienst neu
 *   (START_STICKY), und er holt die Anzeige zurueck.
 * - Solange das Display an ist, prueft der Dienst alle paar Sekunden, ob die Anzeige vorne ist.
 *   Ist laenger als FRONT_MS etwas anderes vorne (andere App, Startbildschirm) oder gibt es die
 *   Anzeige gar nicht mehr (aus der Liste der letzten Apps gewischt, vom System beendet), holt
 *   er sie nach vorn. Diese Wache sitzt im Dienst und nicht in der Anzeige selbst, weil sie
 *   auch dann laufen muss, wenn es die Anzeige nicht mehr gibt.
 */
public class KeepAliveService extends Service {
    static final long FRONT_MS = 60 * 1000;   // etwas anderes vorne: so lange dulden (Android-Einstellungen)
    private static final long CHECK_MS = 3000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private PowerManager pm;
    private long awaySince = 0;
    private boolean watching = false;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            try {
                guard();
            } catch (RuntimeException e) {
                Log.w("NowPlaying", "Wache: " + e);
            }
            ui.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!MainActivity.running) bringToFront("Anzeige laeuft nicht: starten");
        if (!watching) {
            watching = true;
            ui.postDelayed(check, CHECK_MS);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void guard() {
        // Display aus: nichts zu tun; die Anzeige schaltet es selbst wieder ein, wenn Musik laeuft
        if (!pm.isScreenOn() || MainActivity.front) {
            awaySince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (awaySince == 0) {
            awaySince = now;
        } else if (now - awaySince >= FRONT_MS) {
            awaySince = now;
            bringToFront(MainActivity.running ? "Etwas anderes vorne: Anzeige zurueck nach vorn"
                    : "Anzeige beendet: neu starten");
        }
    }

    private void bringToFront(String why) {
        Log.i("NowPlaying", why);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacks(check);
        watching = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
