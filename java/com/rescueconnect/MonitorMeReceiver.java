package com.rescueconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * MonitorMeReceiver
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles taps on the three action buttons inside the Monitor Me check-in
 * notification.  Forwards the action to MonitorMeService so the service can
 * update its state machine.
 *
 * This receiver is used because notification action buttons need a
 * BroadcastReceiver — they can't call a Service method directly.
 *
 * Handled actions:
 *   com.rescueconnect.MONITOR_SAFE      → user is safe, reschedule ping
 *   com.rescueconnect.MONITOR_NOT_SAFE  → trigger SOS immediately
 *   com.rescueconnect.MONITOR_STOP      → user reached destination, stop service
 */
public class MonitorMeReceiver extends BroadcastReceiver {

    private static final String TAG = "MonitorMeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        // Forward action to MonitorMeService
        Intent serviceIntent = new Intent(context, MonitorMeService.class);
        serviceIntent.setAction(action);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}