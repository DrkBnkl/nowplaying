package de.nowplaying.tablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
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
 *
 * Sparsam gezeichnet: das dunkle Raster und die voll leuchtende Leiste liegen
 * als Bilder vor. Pro Bild wird nur der Streifen der geaenderten Spalten
 * erneuert: Raster kopieren, je Spalte den leuchtenden Teil kopieren, Spitze.
 */
class SpectrumBar extends SurfaceView implements SurfaceHolder.Callback {

    private static final int N = 40, M = 10;
    private static final long FRAME_MS = 33;        // 30 Bilder/s = Takt der Daten vom Pi
    private static final long SIGNAL_MS = 4000;     // ohne Signal so lange noch zeigen
    // Bewegung der Balken je Bild (30 Bilder/s): Anteil des Wegs zum neuen Wert und Fall der Spitzen
    private static final float RISE = 1f, FALL = .6f, PEAK_DROP = .04f;
    private static final int[][] BANDS = {{0, 5}, {5, 8}, {8, 10}};  // Zeilen [von, bis) je Farbe

    private volatile float[] target = new float[N];
    private volatile long lastData = 0, lastSignal = 0;
    private volatile boolean allowed = false, dialogOpen = false, displayOff = false;
    private volatile String url = "";
    private volatile int[] colors = {0xFF3E8C9C, 0xFFE9A24F, 0xFFF2E6D0};  // tief, mittel, hoch
    private volatile boolean alive = true;
    private volatile Thread drawThread;

    // Nur vom Zeichen-Thread benutzt
    private final int[] colX0 = new int[N], colX1 = new int[N], rowY0 = new int[M], rowY1 = new int[M];
    private Bitmap gridBmp, litBmp;     // dunkles Raster / alle Zellen leuchtend
    private int[] bmpColors;
    private final Paint copy = new Paint(), fill = new Paint();
    private final Rect dirty = new Rect(), part = new Rect();

    SpectrumBar(Context context) {
        super(context);
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));  // ersetzen statt mischen
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
        boolean visible = false, first = true;
        boolean full = true;   // beim naechsten Bild alles zeichnen
        gridBmp = null;        // neue Flaeche: Bilder neu aufbauen
        while (alive && drawThread == Thread.currentThread()) {
            long t0 = SystemClock.elapsedRealtime();
            // Leiste nur zeigen, wenn der Pi gerade wirklich Signal liefert (Musik direkt am WiiM: keins)
            boolean show = wanted() && t0 - lastSignal <= SIGNAL_MS;
            if (!show) {
                if (visible || first) {
                    clear();
                    visible = false;
                    first = false;
                    full = true;
                    for (int i = 0; i < N; i++) { lv[i] = 0; pk[i] = 0; shown[i] = -1; }
                }
                pause(200);
                continue;
            }
            float[] tg = t0 - lastData > 1000 ? new float[N] : target;
            int[] col = colors;
            int from = N, to = -1;   // geaenderte Spalten [from, to]
            for (int i = 0; i < N; i++) {
                // Anstieg sofort (keine Verzoegerung), Abfall in wenigen Bildern, Spitzen fallen in gut 0,8 s
                lv[i] += (tg[i] - lv[i]) * (tg[i] > lv[i] ? RISE : FALL);
                pk[i] = lv[i] >= pk[i] ? lv[i] : Math.max(lv[i], pk[i] - PEAK_DROP);
                lit[i] = Math.round(lv[i] * M);
                peak[i] = Math.max(lit[i], Math.round(pk[i] * M)) - 1;
                int key = lit[i] * 16 + peak[i];
                if (shown[i] != key) {
                    shown[i] = key;
                    if (i < from) from = i;
                    to = i;
                }
            }
            if (full || col != bmpColors) { from = 0; to = N - 1; }
            if (to >= 0) {
                if (paint(lit, peak, col, from, to)) { visible = true; full = false; }
                else full = true;   // nicht gezeichnet: beim naechsten Mal alles nachholen
            }
            pause(FRAME_MS - (SystemClock.elapsedRealtime() - t0));
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Flaeche leeren (durchsichtig). */
    private void clear() {
        SurfaceHolder holder = getHolder();
        Canvas c = null;
        try {
            c = holder.lockCanvas();
            if (c != null) c.drawColor(0, PorterDuff.Mode.CLEAR);
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try { holder.unlockCanvasAndPost(c); } catch (Exception ignored) { }
            }
        }
    }

    /** Raster und voll leuchtende Leiste als Bilder aufbauen. Liefert false, wenn nichts zu tun war. */
    private boolean prepare(int w, int h, int[] col) {
        if (gridBmp != null && gridBmp.getWidth() == w && gridBmp.getHeight() == h && col == bmpColors) return false;
        float gx = w * .006f, gy = h * .03f;
        float bw = (w - gx * (N - 1)) / N, sh = (h - gy * (M - 1)) / M;
        for (int i = 0; i < N; i++) {
            colX0[i] = Math.round(i * (bw + gx));
            colX1[i] = Math.round(i * (bw + gx) + bw);
        }
        for (int r = 0; r < M; r++) {
            float y = h - (r + 1) * sh - r * gy;
            rowY0[r] = Math.round(y);
            rowY1[r] = Math.round(y + sh);
        }
        if (gridBmp == null || gridBmp.getWidth() != w || gridBmp.getHeight() != h) {
            gridBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            litBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        gridBmp.eraseColor(0);
        litBmp.eraseColor(0);
        Canvas g = new Canvas(gridBmp), l = new Canvas(litBmp);
        for (int b = 0; b < BANDS.length; b++) {
            for (int r = BANDS[b][0]; r < BANDS[b][1]; r++) {
                for (int i = 0; i < N; i++) {
                    fill.setColor((col[b] & 0x00FFFFFF) | 0x14000000);   // dunkle Zelle (8 %)
                    g.drawRect(colX0[i], rowY0[r], colX1[i], rowY1[r], fill);
                    fill.setColor(col[b]);
                    l.drawRect(colX0[i], rowY0[r], colX1[i], rowY1[r], fill);
                }
            }
        }
        bmpColors = col;
        return true;
    }

    /** Zeichnet die Spalten [from, to] neu (bei neuer Groesse oder neuen Farben alles). */
    private boolean paint(int[] lit, int[] peak, int[] col, int from, int to) {
        SurfaceHolder holder = getHolder();
        Rect frame = holder.getSurfaceFrame();
        int w = frame.width(), h = frame.height();
        if (w <= 1 || h <= 1) return false;
        Canvas c = null;
        try {
            if (prepare(w, h, col)) { from = 0; to = N - 1; }
            dirty.set(colX0[from], 0, colX1[to], h);
            c = holder.lockCanvas(dirty);   // das System darf den Bereich vergroessern
            if (c == null || c.getWidth() != w || c.getHeight() != h) return false;
            c.drawBitmap(gridBmp, dirty, dirty, copy);
            fill.setColor(col[2]);
            for (int i = 0; i < N; i++) {
                if (colX1[i] <= dirty.left || colX0[i] >= dirty.right) continue;
                if (lit[i] > 0) {
                    part.set(colX0[i], rowY0[lit[i] - 1], colX1[i], h);
                    c.drawBitmap(litBmp, part, part, null);
                }
                if (peak[i] >= lit[i] && peak[i] >= 0)
                    c.drawRect(colX0[i], rowY0[peak[i]], colX1[i], rowY1[peak[i]], fill);
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
