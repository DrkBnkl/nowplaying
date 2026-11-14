package de.nowplaying.tablet;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Haelt den Prozess der Anzeige am Leben. Beendet Android ihn trotzdem (Speichermangel),
 * startet das System diesen Dienst neu (START_STICKY), und er holt die Anzeige zurueck.
 */
public class KeepAliveService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!MainActivity.running) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
