package de.nowplaying.tablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * LED-Leiste (Spektrum) als eigene Zeichenflaeche ueber der Webseite.
 *
 * In der Webansicht kostet die Leiste auf dem alten Tablet einen ganzen
 * Prozessorkern, weil bei jedem Bild die komplette Seite neu zusammengesetzt
 * wird. Hier liegt sie in einer eigenen Surface: die Seite wird dafuer nicht
 * neu gezeichnet.
 *
 * Die Seite meldet ueber NowPlayingApp.bar(...), ob die Leiste erlaubt ist,
 * die Adresse des Spektrum-Dienstes und die drei Farben des Covers. Die Daten
 * holt die Leiste selbst (Server-Sent Events vom Pi). Aussehen und Bewegung
 * entsprechen der Leiste in index.html (40 Spalten, 10 Zeilen).
 */
class SpectrumBar extends SurfaceView implements SurfaceHolder.Callback {

    private static final int N = 40, M = 10;
    private static final long FRAME_MS = 33;        // 30 Bilder/s = Takt der Daten vom Pi
    private static final long SIGNAL_MS = 4000;     // ohne Signal so lange noch zeigen
    private static final int[][] BANDS = {{0, 5}, {5, 8}, {8, 10}};  // Zeilen [von, bis) je Farbe

    private volatile float[] target = new float[N];
    private volatile long lastData = 0, lastSignal = 0;
    private volatile boolean allowed = false, dialogOpen = false, displayOff = false;
    private volatile String url = "";
    private volatile int[] colors = {0xFF3E8C9C, 0xFFE9A24F, 0xFFF2E6D0};  // tief, mittel, hoch
    private volatile boolean alive = true;
    private volatile Thread drawThread;

    SpectrumBar(Context context) {
        super(context);
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        Thread net = new Thread(new Runnable() {
            @Override
            public void run() { receive(); }
        }, "nowplaying-spectrum");
        net.setDaemon(true);
        net.start();
    }

    /** Von der Seite: Leiste erlaubt?, Adresse des Spektrum-Dienstes, Farben als "#rrggbb". */
    void configure(boolean on, String spectrumUrl, String low, String mid, String high) {
        int[] c = colors.clone();
        String[] in = {low, mid, high};
        for (int i = 0; i < 3; i++) {
            try { c[i] = Color.parseColor(in[i]); } catch (Exception ignored) { /* alte Farbe behalten */ }
        }
        colors = c;
        url = spectrumUrl == null ? "" : spectrumUrl;
        allowed = on && !url.isEmpty();
    }

    void setDialogOpen(boolean open) { dialogOpen = open; }

    void setDisplayOff(boolean off) { displayOff = off; }

    void shutdown() { alive = false; }

    private boolean wanted() { return alive && allowed && !dialogOpen && !displayOff; }

    // ------------------------------------------------------------ Daten
    private void receive() {
        while (alive) {
            String u = url;
            if (wanted() && !u.isEmpty()) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(10000);  // der Pi sendet mindestens zweimal pro Sekunde
                    c.setRequestProperty("Accept", "text/event-stream");
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while (wanted() && u.equals(url) && (line = r.readLine()) != null) {
                        if (line.startsWith("data:")) onData(line.substring(5).trim());
                    }
                } catch (Exception ignored) {
                    // Dienst nicht erreichbar: gleich noch einmal versuchen
                } finally {
                    if (c != null) c.disconnect();
                }
            }
            try { Thread.sleep(wanted() ? 2000 : 500); } catch (InterruptedException e) { return; }
        }
    }

    private void onData(String data) {
        String[] arr = data.split(";");
        if (arr.length == 0) return;
        float[] t = new float[N];
        float sum = 0;
        for (int i = 0; i < N; i++) {
            int v = 0;
            try { v = Integer.parseInt(arr[i * arr.length / N].trim()); } catch (NumberFormatException ignored) { }
            t[i] = Math.max(0f, Math.min(1f, v / 1000f));
            sum += t[i];
        }
        target = t;
        long now = SystemClock.elapsedRealtime();
        lastData = now;
        if (sum > .2f) lastSignal = now;
    }

    // --------------------------------------------------------- Zeichnen
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { drawLoop(); }
        }, "nowplaying-bar");
        t.setDaemon(true);
        drawThread = t;
        t.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Thread t = drawThread;
        drawThread = null;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) { }
        }
    }

    private void drawLoop() {
        float[] lv = new float[N], pk = new float[N];
        int[] lit = new int[N], peak = new int[N], shown = new int[N];
        int[] drawn = null;
        boolean visible = false, first = true;
        Paint paint = new Paint();
        while (alive && drawThread == Thread.currentThread()) {
            long t0 = SystemClock.elapsedRealtime();
            // Leiste nur zeigen, wenn der Pi gerade wirklich Signal liefert (Musik direkt am WiiM: keins)
            boolean show = wanted() && t0 - lastSignal <= SIGNAL_MS;
            if (!show) {
                if (visible || first) {
                    paint(null, null, null, paint);
                    visible = false;
                    first = false;
                    for (int i = 0; i < N; i++) { lv[i] = 0; pk[i] = 0; shown[i] = -1; }
                }
                pause(200);
                continue;
            }
            float[] tg = t0 - lastData > 1000 ? new float[N] : target;
            int[] col = colors;
            boolean dirty = !visible || col != drawn;
            for (int i = 0; i < N; i++) {
                lv[i] += (tg[i] - lv[i]) * (tg[i] > lv[i] ? .91f : .44f);
                pk[i] = lv[i] >= pk[i] ? lv[i] : Math.max(lv[i], pk[i] - .024f);
                lit[i] = Math.round(lv[i] * M);
                peak[i] = Math.max(lit[i], Math.round(pk[i] * M)) - 1;
                int key = lit[i] * 16 + peak[i];
                if (shown[i] != key) { shown[i] = key; dirty = true; }
            }
            if (dirty && paint(lit, peak, col, paint)) {
                visible = true;
                drawn = col;
            }
            pause(FRAME_MS - (SystemClock.elapsedRealtime() - t0));
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Zeichnet die Leiste; mit lit == null wird die Flaeche nur geleert (durchsichtig). */
    private boolean paint(int[] lit, int[] peak, int[] col, Paint paint) {
        SurfaceHolder holder = getHolder();
        Canvas c = null;
        try {
            c = holder.lockCanvas();
            if (c == null) return false;
            c.drawColor(0, PorterDuff.Mode.CLEAR);
            if (lit == null) return true;
            float w = c.getWidth(), h = c.getHeight();
            float gx = w * .006f, gy = h * .03f;
            float bw = (w - gx * (N - 1)) / N, sh = (h - gy * (M - 1)) / M;
            for (int b = 0; b < BANDS.length; b++) {
                for (int r = BANDS[b][0]; r < BANDS[b][1]; r++) {
                    float y = h - (r + 1) * sh - r * gy;
                    for (int i = 0; i < N; i++) {
                        float x = i * (bw + gx);
                        if (peak[i] == r && peak[i] >= lit[i]) paint.setColor(col[2]);          // Spitze
                        else if (r < lit[i]) paint.setColor(col[b]);                            // leuchtet
                        else paint.setColor((col[b] & 0x00FFFFFF) | 0x14000000);                // dunkle Zelle (8 %)
                        c.drawRect(x, y, x + bw, y + sh, paint);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
        }
    }
}
