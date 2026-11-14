package de.nowplaying.tablet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet die Anzeige automatisch nach dem Einschalten des Tablets. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent start = new Intent(context, MainActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(start);
        }
    }
}
