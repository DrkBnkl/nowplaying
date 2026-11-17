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
 * Keeps the display's process alive and the display in the foreground.
 *
 * - If Android kills the process (low memory), the system restarts this service
 *   (START_STICKY) and it brings the display back.
 * - While the screen is on, the service checks every few seconds whether the display is in front.
 *   If something else has been in front for longer than FRONT_MS (another app, the home screen),
 *   or the display no longer exists (swiped away from recent apps, killed by the system), it
 *   brings it back to the front. This watchdog lives in the service rather than in the display
 *   itself because it has to keep running even when the display is gone.
 */
public class KeepAliveService extends Service {
    static final long FRONT_MS = 60 * 1000;   // tolerate something else in front this long (e.g. Android settings)
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
                Log.w("NowPlaying", "Watchdog: " + e);
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
        if (!MainActivity.running) bringToFront("Display not running: starting it");
        if (!watching) {
            watching = true;
            ui.postDelayed(check, CHECK_MS);
        }
        return START_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void guard() {
        // Screen off: nothing to do; the display turns it back on itself when music plays
        if (!pm.isScreenOn() || MainActivity.front) {
            awaySince = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (awaySince == 0) {
            awaySince = now;
        } else if (now - awaySince >= FRONT_MS) {
            awaySince = now;
            bringToFront(MainActivity.running ? "Something else in front: bringing display back"
                    : "Display was terminated: restarting");
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
