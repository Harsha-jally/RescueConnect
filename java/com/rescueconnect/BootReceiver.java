package com.rescueconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver
 * ─────────────────────────────────────────────────────────────────────────────
 * Automatically restarts EmergencyDetectionService whenever the device boots
 * (or quick-boots / restarts).  Without this, the foreground service would
 * stop after a reboot and the user would lose background protection until
 * they manually opened the app again.
 *
 * Required AndroidManifest.xml entries (inside <application>):
 * ─────────────────────────────────────────────────────────────────────────────
 *   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 *
 *   <receiver
 *       android:name=".BootReceiver"
 *       android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.intent.action.BOOT_COMPLETED" />
 *           <action android:name="android.intent.action.QUICKBOOT_POWERON" />
 *       </intent-filter>
 *   </receiver>
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        boolean isBoot =
                Intent.ACTION_BOOT_COMPLETED.equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)  // HTC/OnePlus
                        || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);  // Some HTC ROMs

        if (!isBoot) return;

        Log.d(TAG, "Device booted — starting EmergencyDetectionService");

        Intent serviceIntent = new Intent(context, EmergencyDetectionService.class);

        // On Android 8+ we MUST call startForegroundService() instead of startService()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}