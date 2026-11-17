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
 * LED bar (spectrum) drawn on its own surface on top of the web page.
 *
 * Inside the WebView the bar costs a whole CPU core on the old tablet, because
 * the entire page is recomposited on every frame. Here it lives in its own
 * Surface, so the page does not need to be redrawn for it.
 *
 * Via NowPlayingApp.bar(...) the page reports whether the bar is allowed, the
 * address of the spectrum service and the three cover colours. The bar fetches
 * the data itself (Server-Sent Events from the Pi). Look and motion match the
 * bar in index.html (40 columns, 10 rows).
 *
 * Drawn sparingly: the dark grid and the fully lit bar are prepared as bitmaps.
 * Each frame only redraws the strip of changed columns: copy the grid, copy the
 * lit part of each column, draw the peak.
 */
class SpectrumBar extends SurfaceView implements SurfaceHolder.Callback {

    private static final int N = 40, M = 10;
    private static final long FRAME_MS = 33;        // 30 fps = rate of the data from the Pi
    private static final long SIGNAL_MS = 4000;     // keep showing this long without signal
    // Bar motion per frame (30 fps): fraction of the way to the new value, and peak fall
    private static final float RISE = 1f, FALL = .6f, PEAK_DROP = .04f;
    private static final int[][] BANDS = {{0, 5}, {5, 8}, {8, 10}};  // rows [from, to) per colour

    private volatile float[] target = new float[N];
    private volatile long lastData = 0, lastSignal = 0;
    private volatile boolean allowed = false, dialogOpen = false, displayOff = false;
    private volatile String url = "";
    private volatile int[] colors = {0xFF3E8C9C, 0xFFE9A24F, 0xFFF2E6D0};  // low, mid, high
    private volatile boolean alive = true;
    private volatile Thread drawThread;

    // Used by the drawing thread only
    private final int[] colX0 = new int[N], colX1 = new int[N], rowY0 = new int[M], rowY1 = new int[M];
    private Bitmap gridBmp, litBmp;     // dark grid / all cells lit
    private int[] bmpColors;
    private final Paint copy = new Paint(), fill = new Paint();
    private final Rect dirty = new Rect(), part = new Rect();

    SpectrumBar(Context context) {
        super(context);
        setZOrderOnTop(true);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        copy.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));  // replace instead of blend
        Thread net = new Thread(new Runnable() {
            @Override
            public void run() { receive(); }
        }, "nowplaying-spectrum");
        net.setDaemon(true);
        net.start();
    }

    /** From the page: bar allowed?, address of the spectrum service, colours as "#rrggbb". */
    void configure(boolean on, String spectrumUrl, String low, String mid, String high) {
        int[] c = colors.clone();
        String[] in = {low, mid, high};
        for (int i = 0; i < 3; i++) {
            try { c[i] = Color.parseColor(in[i]); } catch (Exception ignored) { /* keep old colour */ }
        }
        colors = c;
        url = spectrumUrl == null ? "" : spectrumUrl;
        allowed = on && !url.isEmpty();
    }

    void setDialogOpen(boolean open) { dialogOpen = open; }

    void setDisplayOff(boolean off) { displayOff = off; }

    void shutdown() { alive = false; }

    private boolean wanted() { return alive && allowed && !dialogOpen && !displayOff; }

    // ------------------------------------------------------------- Data
    private void receive() {
        while (alive) {
            String u = url;
            if (wanted() && !u.isEmpty()) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(10000);  // the Pi sends at least twice per second
                    c.setRequestProperty("Accept", "text/event-stream");
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while (wanted() && u.equals(url) && (line = r.readLine()) != null) {
                        if (line.startsWith("data:")) onData(line.substring(5).trim());
                    }
                } catch (Exception ignored) {
                    // service unreachable: try again shortly
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

    // ------------------------------------------------------------ Drawing
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
        boolean full = true;   // draw everything on the next frame
        gridBmp = null;        // new surface: rebuild bitmaps
        while (alive && drawThread == Thread.currentThread()) {
            long t0 = SystemClock.elapsedRealtime();
            // Only show the bar while the Pi actually delivers a signal (music played directly on the WiiM: none)
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
            int from = N, to = -1;   // changed columns [from, to]
            for (int i = 0; i < N; i++) {
                // Rise immediately (no delay), fall within a few frames, peaks drop in just over 0.8 s
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
                else full = true;   // not drawn: catch up on everything next time
            }
            pause(FRAME_MS - (SystemClock.elapsedRealtime() - t0));
        }
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    /** Clear the surface (transparent). */
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

    /** Build the grid and fully lit bar as bitmaps. Returns false if there was nothing to do. */
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
                    fill.setColor((col[b] & 0x00FFFFFF) | 0x14000000);   // dark cell (8 %)
                    g.drawRect(colX0[i], rowY0[r], colX1[i], rowY1[r], fill);
                    fill.setColor(col[b]);
                    l.drawRect(colX0[i], rowY0[r], colX1[i], rowY1[r], fill);
                }
            }
        }
        bmpColors = col;
        return true;
    }

    /** Redraws columns [from, to] (everything on a new size or new colours). */
    private boolean paint(int[] lit, int[] peak, int[] col, int from, int to) {
        SurfaceHolder holder = getHolder();
        Rect frame = holder.getSurfaceFrame();
        int w = frame.width(), h = frame.height();
        if (w <= 1 || h <= 1) return false;
        Canvas c = null;
        try {
            if (prepare(w, h, col)) { from = 0; to = N - 1; }
            dirty.set(colX0[from], 0, colX1[to], h);
            c = holder.lockCanvas(dirty);   // the system may enlarge the area
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
