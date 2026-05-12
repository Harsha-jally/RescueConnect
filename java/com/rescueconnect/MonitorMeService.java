package com.rescueconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;



/**
 * MonitorMeService
 * ─────────────────────────────────────────────────────────────────────────────
 * Runs as a foreground service while "Monitor Me" is active.
 *
 * Timeline per cycle:
 *   ┌─ T+0 min ──────────────────────────────────────────────────────────────┐
 *   │  Cycle starts / previous check-in was answered "Safe"                  │
 *   │  Schedule check-in notification for T+5 min                            │
 *   └────────────────────────────────────────────────────────────────────────┘
 *   ┌─ T+5 min ──────────────────────────────────────────────────────────────┐
 *   │  Show "Are you safe?" notification with 3 action buttons:              │
 *   │    ✅  I Am Safe          → MonitorMeReceiver → ACTION_SAFE            │
 *   │    🚨  I Am NOT Safe      → MonitorMeReceiver → ACTION_NOT_SAFE        │
 *   │    🏁  Reached Destination → MonitorMeReceiver → ACTION_STOP          │
 *   │  Start 2-minute response timeout                                        │
 *   └────────────────────────────────────────────────────────────────────────┘
 *   ┌─ T+7 min (if no response) ─────────────────────────────────────────────┐
 *   │  No response → fire SOS automatically via SosDispatcher                │
 *   └────────────────────────────────────────────────────────────────────────┘
 *
 * Intent actions handled by onStartCommand():
 *   ACTION_START      — called by HomeActivity "Monitor Me" button
 *   ACTION_SAFE       — user tapped "I Am Safe" in notification
 *   ACTION_NOT_SAFE   — user tapped "I Am NOT Safe" in notification
 *   ACTION_STOP       — user tapped "Reached Destination"
 *
 * LocalBroadcast sent back to HomeActivity:
 *   BROADCAST_STATE_CHANGED  with extra EXTRA_IS_MONITORING (boolean)
 *
 * AndroidManifest.xml additions needed (inside <application>):
 * ─────────────────────────────────────────────────────────────────────────────
 *   <service
 *       android:name=".MonitorMeService"
 *       android:foregroundServiceType="dataSync"
 *       android:exported="false"
 *       android:stopWithTask="false" />
 *
 *   <receiver
 *       android:name=".MonitorMeReceiver"
 *       android:exported="false" />
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class MonitorMeService extends Service {

    private static final String TAG = "MonitorMeService";

    // ── Intent actions ────────────────────────────────────────────────────────
    public static final String ACTION_START    = "com.rescueconnect.MONITOR_START";
    public static final String ACTION_SAFE     = "com.rescueconnect.MONITOR_SAFE";
    public static final String ACTION_NOT_SAFE = "com.rescueconnect.MONITOR_NOT_SAFE";
    public static final String ACTION_STOP     = "com.rescueconnect.MONITOR_STOP";

    // ── LocalBroadcast (tells HomeActivity the monitoring state changed) ──────
    public static final String BROADCAST_STATE_CHANGED = "com.rescueconnect.MONITOR_STATE";
    public static final String EXTRA_IS_MONITORING     = "is_monitoring";

    // ── Notification channels & IDs ───────────────────────────────────────────
    private static final String CH_FOREGROUND  = "monitor_me_fg_channel";
    private static final String CH_CHECKIN     = "monitor_me_checkin_channel";
    private static final int    NOTIF_FG       = 8001;
    private static final int    NOTIF_CHECKIN  = 8002;

    // ── Timing constants ──────────────────────────────────────────────────────
    // Timing is read from SafetySettingsHelper at schedule time — not hardcoded here

    // ── State ─────────────────────────────────────────────────────────────────
    private Handler   handler;
    private Runnable  pingRunnable;
    private Runnable  timeoutRunnable;
    private boolean   isMonitoring      = false;
    private boolean   awaitingResponse  = false;
    private int       checkInCount      = 0;   // shown in notification subtitle


    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler       = new Handler(Looper.getMainLooper());
        createNotificationChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (action == null) action = ACTION_START;

        Log.d(TAG, "onStartCommand action=" + action);

        switch (action) {

            case ACTION_START:
                if (!isMonitoring) {
                    isMonitoring = true;
                    checkInCount = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_FG, buildForegroundNotification(),
                                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                    } else {
                        startForeground(NOTIF_FG, buildForegroundNotification());
                    }
                    scheduleNextPing();
                    broadcastState(true);
                    Log.d(TAG, "✅ MonitorMe started — ping in 5 min");
                }
                break;

            case ACTION_SAFE:
                // User confirmed they're safe — dismiss check-in, schedule next one
                if (awaitingResponse) {
                    cancelResponseTimeout();
                    dismissCheckInNotification();
                    awaitingResponse = false;
                    Log.d(TAG, "✅ User confirmed safe — next ping in 5 min");
                    scheduleNextPing();
                }
                break;

            case ACTION_NOT_SAFE:
                // User explicitly said they're not safe — fire SOS immediately
                cancelResponseTimeout();
                dismissCheckInNotification();
                awaitingResponse = false;
                Log.w(TAG, "🚨 User reported NOT safe — dispatching SOS");
                dispatchSos("User reported NOT safe via Monitor Me", "monitor_not_safe");
                // Keep monitoring running so they still get check-ins if SOS was sent
                scheduleNextPing();
                break;

            case ACTION_STOP:
                // User reached their destination — stop everything
                stopMonitoring();
                break;
        }

        return START_NOT_STICKY; // Don't restart if killed — user must re-enable
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "MonitorMeService destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ──────────────────────────────────────────────────────────────────────────
    // Core scheduling logic
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Posts a delayed runnable that fires after the user-configured interval,
     * shows the check-in notification, and starts the response timeout.
     */
    private void scheduleNextPing() {
        cancelPingRunnable();

        final long   intervalMs  = MonitorSettingsHelper.getMonitorPingInterval(this);
        final String intervalLbl = MonitorSettingsHelper.labelForValue(intervalMs);

        pingRunnable = () -> {
            checkInCount++;
            awaitingResponse = true;
            showCheckInNotification();
            startResponseTimeout();
            Log.d(TAG, "🔔 Check-in #" + checkInCount + " sent — waiting for response");
        };

        handler.postDelayed(pingRunnable, intervalMs);
        Log.d(TAG, "Next ping in " + intervalLbl + " (check-in #" + (checkInCount + 1) + ")");
        updateForegroundNotification("Next check-in in " + intervalLbl);
    }

    /**
     * Starts the user-configured response countdown.
     * If it expires without a response, SOS is triggered automatically.
     */
    private void startResponseTimeout() {
        cancelResponseTimeout();

        final long   timeoutMs  = MonitorSettingsHelper.getMonitorResponseTimeout(this);
        final String timeoutLbl = MonitorSettingsHelper.labelForValue(timeoutMs);

        timeoutRunnable = () -> {
            if (awaitingResponse && isMonitoring) {
                awaitingResponse = false;
                Log.w(TAG, "⏰ No response to check-in #" + checkInCount + " — auto-SOS");
                dismissCheckInNotification();
                dispatchSos(
                        "No response to Monitor Me check-in #" + checkInCount,
                        "monitor_timeout");
                scheduleNextPing();
            }
        };

        handler.postDelayed(timeoutRunnable, timeoutMs);
        updateForegroundNotification("⚠️ Respond to check-in — " + timeoutLbl + " left");
        Log.d(TAG, "Response timeout started (" + timeoutLbl + ")");
    }

    /** Cancels a pending response timeout without doing anything else. */
    private void cancelResponseTimeout() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /** Cancels the scheduled ping without stopping the service. */
    private void cancelPingRunnable() {
        if (pingRunnable != null) {
            handler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }

    /** Full shutdown — called when user reaches their destination. */
    private void stopMonitoring() {
        isMonitoring    = false;
        awaitingResponse = false;
        cancelPingRunnable();
        cancelResponseTimeout();
        dismissCheckInNotification();
        broadcastState(false);
        Log.d(TAG, "✅ MonitorMe stopped — user reached destination");
        stopForeground(true);
        stopSelf();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Foreground service notification
    // ──────────────────────────────────────────────────────────────────────────

    private Notification buildForegroundNotification() {
        return buildForegroundNotification(
                "Next check-in in " + MonitorSettingsHelper.labelForValue(MonitorSettingsHelper.getMonitorPingInterval(this)));
    }

    private Notification buildForegroundNotification(String subtitle) {
        // Tap → open HomeActivity (this is how the user stops monitoring too)
        Intent openApp = new Intent(this, HomeActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CH_FOREGROUND)
                .setSmallIcon(R.mipmap.ic_emergency)
                .setContentTitle("👁️ Monitor Me — Active")
                .setContentText(subtitle + "  •  Open app to stop")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setColor(Color.parseColor("#3B82F6"))
                .setColorized(false)
                .setContentIntent(openPending)  // tap opens app — no action buttons
                .build();
    }

    private void updateForegroundNotification(String subtitle) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_FG, buildForegroundNotification(subtitle));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Check-in notification (shown every 5 minutes)
    // ──────────────────────────────────────────────────────────────────────────

    private void showCheckInNotification() {
        // ── Safe button ───────────────────────────────────────────────────────
        Intent safeIntent = new Intent(this, MonitorMeReceiver.class);
        safeIntent.setAction(ACTION_SAFE);
        PendingIntent safePending = PendingIntent.getBroadcast(
                this, 30, safeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Not Safe button ───────────────────────────────────────────────────
        Intent notSafeIntent = new Intent(this, MonitorMeReceiver.class);
        notSafeIntent.setAction(ACTION_NOT_SAFE);
        PendingIntent notSafePending = PendingIntent.getBroadcast(
                this, 31, notSafeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // ── Tap → open app ────────────────────────────────────────────────────
        Intent openApp = new Intent(this, HomeActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 33, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String timeoutLbl = MonitorSettingsHelper.labelForValue(MonitorSettingsHelper.getMonitorResponseTimeout(this));

        String bigText =
                "Check-in #" + checkInCount + "\n\n"
                        + "You have " + timeoutLbl + " to respond.\n"
                        + "If you don't respond, SOS will be sent automatically to your\n"
                        + "emergency contacts and nearby volunteers.\n\n"
                        + "Open the app to stop monitoring.";

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, CH_CHECKIN)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("🔵 Are you safe? Respond now!")
                        .setContentText("Check-in #" + checkInCount
                                + " — SOS in " + timeoutLbl + " if no response.")
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.parseColor("#3B82F6"))
                        .setColorized(true)
                        .setOngoing(true)
                        .setAutoCancel(false)
                        .setVibrate(new long[]{0, 300, 200, 300, 200, 300})
                        .setLights(Color.BLUE, 500, 500)
                        .setContentIntent(openPending)
                        .addAction(0, "✅  I Am Safe",     safePending)
                        .addAction(0, "🚨  I Am NOT Safe", notSafePending);
        // No "Reached Destination" — user must open the app to stop

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_CHECKIN, builder.build());
    }

    private void dismissCheckInNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIF_CHECKIN);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOS dispatch
    // ──────────────────────────────────────────────────────────────────────────

    private void dispatchSos(String reason, String type) {
        // SosDispatcher handles the full location strategy internally:
        //   getLastLocation first → getCurrentLocation as refinement → no-location fallback
        SosDispatcher.fetchLocationAndSendSos(this, reason, type);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Local broadcast — informs HomeActivity of state changes
    // ──────────────────────────────────────────────────────────────────────────

    private void broadcastState(boolean monitoring) {
        Intent intent = new Intent(BROADCAST_STATE_CHANGED);
        intent.putExtra(EXTRA_IS_MONITORING, monitoring);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification channel setup
    // ──────────────────────────────────────────────────────────────────────────

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Foreground persistent channel (silent, low importance)
        if (nm.getNotificationChannel(CH_FOREGROUND) == null) {
            NotificationChannel fg = new NotificationChannel(
                    CH_FOREGROUND, "Monitor Me (Active)", NotificationManager.IMPORTANCE_LOW);
            fg.setDescription("Persistent notification while Monitor Me is running");
            fg.setShowBadge(false);
            nm.createNotificationChannel(fg);
        }

        // Check-in channel (high importance — must break through Do Not Disturb)
        if (nm.getNotificationChannel(CH_CHECKIN) == null) {
            NotificationChannel ci = new NotificationChannel(
                    CH_CHECKIN, "Monitor Me Check-ins", NotificationManager.IMPORTANCE_HIGH);
            ci.setDescription("Regular safety check-ins while Monitor Me is active");
            ci.enableLights(true);
            ci.setLightColor(Color.BLUE);
            ci.enableVibration(true);
            ci.setVibrationPattern(new long[]{0, 300, 200, 300, 200, 300});
            ci.setBypassDnd(true);  // Break through DND — this is a safety feature
            nm.createNotificationChannel(ci);
        }
    }
}