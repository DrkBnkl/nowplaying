package de.nowplaying.tablet;

import android.app.admin.DeviceAdminReceiver;

/** Only needed so the app is allowed to turn the display off via lockNow(). */
public class AdminReceiver extends DeviceAdminReceiver {
}
