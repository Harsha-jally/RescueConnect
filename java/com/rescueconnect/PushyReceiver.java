package com.rescueconnect;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * PushyReceiver
 * ─────────────────────────────────────────────────────────────────────────────
 * Receives all Pushy push notifications.
 *
 *   "emergency"         → Full alarm-style notification to volunteers with
 *                          📍 Navigate + 🚀 I Am Going action buttons.
 *
 *   "volunteer_coming"  → Shown on the SOS *user's* phone when a volunteer
 *                          taps "I Am Going".
 *                          ✅ UPDATED: tap and "📍 Track Volunteer" button both
 *                          open TrackVolunteerActivity (live map) instead of a
 *                          static Google Maps link.
 *
 *   default             → Quiet info notification.
 */
public class PushyReceiver extends BroadcastReceiver {

    private static final String TAG        = "PushyReceiver";
    private static final String CHANNEL_ID = "emergency_channel";
    private static final int    NOTIF_ID   = 2001;

    private static final long[] SOS_PATTERN = {
            0,   200, 100, 200, 100, 200,
            300, 500, 100, 500, 100, 500,
            300, 200, 100, 200, 100, 200
    };

    @Override
    public void onReceive(Context context, Intent intent) {

        String message       = intent.getStringExtra("message");
        String mapsUrl       = intent.getStringExtra("mapsUrl");
        String type          = intent.getStringExtra("type");
        String alertId       = intent.getStringExtra("alertId");
        String volunteerName = intent.getStringExtra("volunteerName");  // ← NEW

        if (message == null || message.isEmpty()) message = "Emergency alert nearby!";
        if (alertId == null) alertId = "";
        if (volunteerName == null) volunteerName = "Volunteer";

        Log.d(TAG, "✅ Push received | type=" + type + " | alertId=" + alertId
                + " | volunteer=" + volunteerName);

        switch (type != null ? type : "") {
            case "emergency":
                showEmergencyNotification(context, message, mapsUrl, alertId);
                break;

            case "volunteer_coming":
                // ✅ Pass alertId + volunteerName so we can open the live map
                showVolunteerComingNotification(
                        context, message, mapsUrl, alertId, volunteerName);
                break;

            // ── NEW: volunteer pulled out — shown on ADMIN's device ───────────
            case "cant_go":
                showCantGoAdminNotification(context, message, alertId, volunteerName);
                break;

            // ── NEW: admin reassigned — shown on the SOS USER's device ────────
            case "volunteer_reassigned":
                showVolunteerReassignedNotification(
                        context, message, alertId, volunteerName);
                break;

            // ── NEW: volunteer cancelled — shown on the SOS USER's device ─────
            case "volunteer_cancelled":
                showVolunteerCancelledNotification(context, message, alertId);
                break;

            default:
                showInfoNotification(context, message);
                break;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Emergency notification — volunteers see this when someone sends SOS
    // ─────────────────────────────────────────────────────────────────────────

    private void showEmergencyNotification(Context context, String message,
                                           String mapsUrl, String alertId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("High-priority emergency alerts from RescueConnect");
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(true);
            channel.setVibrationPattern(SOS_PATTERN);
            channel.setShowBadge(true);
            channel.setBypassDnd(true);
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            channel.setSound(alarmUri, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            manager.createNotificationChannel(channel);
        }

        Intent tapIntent = buildMapIntent(context, mapsUrl);
        PendingIntent tapPending = PendingIntent.getActivity(
                context, 0, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent navigatePending = null;
        if (mapsUrl != null && !mapsUrl.isEmpty()) {
            Intent navIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            navIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            navigatePending = PendingIntent.getActivity(
                    context, 1, navIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        Intent goingIntent = new Intent(context, VolunteerActionReceiver.class);
        goingIntent.setAction(VolunteerActionReceiver.ACTION_IM_GOING);
        goingIntent.putExtra("alertId", alertId);
        goingIntent.putExtra("mapsUrl", mapsUrl != null ? mapsUrl : "");
        PendingIntent goingPending = PendingIntent.getBroadcast(
                context, 2, goingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("🚨 RescueConnect — SOS Alert!")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.RED)
                        .setColorized(true)
                        .setSound(alarmUri)
                        .setVibrate(SOS_PATTERN)
                        .setLights(Color.RED, 500, 500)
                        .setAutoCancel(true)
                        .setContentIntent(tapPending);

        if (navigatePending != null) builder.addAction(0, "📍 Navigate", navigatePending);
        builder.addAction(0, "🚀 I Am Going", goingPending);

        manager.notify(NOTIF_ID, builder.build());
        Log.d(TAG, "✅ Emergency notification shown with action buttons");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Volunteer-coming notification — shown to the SOS *user*.
    //
    // ✅ UPDATED:
    //   • Tap  →  opens TrackVolunteerActivity (live map) if alertId is present
    //   • "📍 Track Volunteer" button  →  also opens TrackVolunteerActivity
    //   • Falls back to static Maps link / HomeActivity if alertId is missing
    // ─────────────────────────────────────────────────────────────────────────

    private void showVolunteerComingNotification(Context context,
                                                 String message,
                                                 String mapsUrl,
                                                 String alertId,
                                                 String volunteerName) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        final String COMING_CHANNEL_ID = "volunteer_coming_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    COMING_CHANNEL_ID,
                    "Volunteer Arriving",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifies you when a volunteer is on their way");
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 200, 300});
            Uri notifUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            channel.setSound(notifUri, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build());
            manager.createNotificationChannel(channel);
        }

        // ── Build the intent that opens the live-tracking map ────────────────
        // If we have an alertId, go straight to the live map.
        // Otherwise fall back to HomeActivity (graceful degradation).
        PendingIntent tapPending;
        PendingIntent trackPending = null;

        boolean hasAlertId = alertId != null && !alertId.isEmpty();

        if (hasAlertId) {
            // ✅ Primary: open TrackVolunteerActivity with the live map
            Intent trackIntent = new Intent(context, TrackVolunteerActivity.class);
            trackIntent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID,       alertId);
            trackIntent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, volunteerName);
            trackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            tapPending = PendingIntent.getActivity(
                    context, 3, trackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // "📍 Track Volunteer" button — same destination
            Intent trackBtnIntent = new Intent(context, TrackVolunteerActivity.class);
            trackBtnIntent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID,       alertId);
            trackBtnIntent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, volunteerName);
            trackBtnIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            trackPending = PendingIntent.getActivity(
                    context, 4, trackBtnIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        } else {
            // Fallback: no alertId — open HomeActivity (or static Maps link)
            Intent fallbackIntent = buildMapIntent(context, mapsUrl);
            fallbackIntent.setClass(context, HomeActivity.class);
            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            tapPending = PendingIntent.getActivity(
                    context, 3, fallbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Static Maps link as action button if we at least have a URL
            if (mapsUrl != null && !mapsUrl.isEmpty()) {
                Intent mapsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
                mapsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                trackPending = PendingIntent.getActivity(
                        context, 4, mapsIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            }
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, COMING_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("🦸 Help Is On The Way!")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.GREEN)
                        .setColorized(true)
                        .setAutoCancel(true)
                        .setContentIntent(tapPending);

        if (trackPending != null) {
            builder.addAction(0, "📍 Track Volunteer", trackPending);
        }

        manager.notify(NOTIF_ID + 2, builder.build());
        Log.d(TAG, "✅ Volunteer-coming notification shown | alertId=" + alertId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: cant_go notification — shown on the ADMIN's device.
    //
    // Displays a high-priority alert with a "👥 Reassign Volunteer" action
    // button that opens AdminDashboardActivity pre-focused on that alert.
    // ─────────────────────────────────────────────────────────────────────────

    private void showCantGoAdminNotification(Context context, String message,
                                             String alertId, String volunteerName) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        final String CANT_GO_CHANNEL_ID = "cant_go_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CANT_GO_CHANNEL_ID,
                    "Volunteer Cancellations",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alerts admin when a volunteer cancels an SOS response");
            channel.enableLights(true);
            channel.setLightColor(Color.YELLOW);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 400, 200, 400});
            manager.createNotificationChannel(channel);
        }

        // Tap → open AdminDashboardActivity, jump straight to Alerts tab
        Intent tapIntent = new Intent(context, AdminDashboardActivity.class);
        tapIntent.putExtra("openTab",    "alerts");
        tapIntent.putExtra("alertId",    alertId != null ? alertId : "");
        tapIntent.putExtra("autoReassign", true);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent tapPending = PendingIntent.getActivity(
                context, 10, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // "👥 Reassign Volunteer" action button — same destination
        Intent reassignIntent = new Intent(context, AdminDashboardActivity.class);
        reassignIntent.putExtra("openTab",    "alerts");
        reassignIntent.putExtra("alertId",    alertId != null ? alertId : "");
        reassignIntent.putExtra("autoReassign", true);
        reassignIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent reassignPending = PendingIntent.getActivity(
                context, 11, reassignIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CANT_GO_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("⚠️ Volunteer Cancelled — Action Required")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.YELLOW)
                        .setColorized(true)
                        .setAutoCancel(true)
                        .setContentIntent(tapPending)
                        .addAction(0, "👥 Reassign Volunteer", reassignPending);

        manager.notify(NOTIF_ID + 3, builder.build());
        Log.d(TAG, "✅ cant_go admin notification shown | alertId=" + alertId
                + " | volunteer=" + volunteerName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: volunteer_cancelled notification — shown on the SOS USER's device
    //      to let them know their previous volunteer pulled out and the admin
    //      has been notified to arrange a replacement.
    // ─────────────────────────────────────────────────────────────────────────

    private void showVolunteerCancelledNotification(Context context,
                                                    String message,
                                                    String alertId) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        final String CANCELLED_CHANNEL_ID = "volunteer_cancelled_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CANCELLED_CHANNEL_ID,
                    "Volunteer Updates",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Updates when your volunteer status changes");
            channel.enableLights(true);
            channel.setLightColor(Color.YELLOW);
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }

        // Tap → keep them on the tracking screen (it updates automatically via Firestore)
        boolean hasAlertId = alertId != null && !alertId.isEmpty();
        Intent tapIntent;
        if (hasAlertId) {
            tapIntent = new Intent(context, TrackVolunteerActivity.class);
            tapIntent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID, alertId);
            tapIntent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, "");
        } else {
            tapIntent = new Intent(context, HomeActivity.class);
        }
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent tapPending = PendingIntent.getActivity(
                context, 12, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CANCELLED_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("😔 Volunteer Update")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .setContentIntent(tapPending);

        manager.notify(NOTIF_ID + 4, builder.build());
        Log.d(TAG, "✅ volunteer_cancelled notification shown to user");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NEW: volunteer_reassigned notification — shown on the SOS USER's device
    //      when the admin assigns a new volunteer after the previous one left.
    //      Opens TrackVolunteerActivity with the new volunteer's name.
    // ─────────────────────────────────────────────────────────────────────────

    private void showVolunteerReassignedNotification(Context context,
                                                     String message,
                                                     String alertId,
                                                     String newVolunteerName) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        final String REASSIGNED_CHANNEL_ID = "volunteer_reassigned_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    REASSIGNED_CHANNEL_ID,
                    "Volunteer Reassignment",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifies user when a new volunteer is assigned");
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 300, 200, 300});
            manager.createNotificationChannel(channel);
        }

        boolean hasAlertId = alertId != null && !alertId.isEmpty();
        PendingIntent tapPending;
        PendingIntent trackPending = null;

        if (hasAlertId) {
            Intent trackIntent = new Intent(context, TrackVolunteerActivity.class);
            trackIntent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID,       alertId);
            trackIntent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, newVolunteerName);
            trackIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            tapPending = PendingIntent.getActivity(
                    context, 13, trackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Intent trackBtnIntent = new Intent(context, TrackVolunteerActivity.class);
            trackBtnIntent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID,       alertId);
            trackBtnIntent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, newVolunteerName);
            trackBtnIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            trackPending = PendingIntent.getActivity(
                    context, 14, trackBtnIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent fallback = new Intent(context, HomeActivity.class);
            fallback.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            tapPending = PendingIntent.getActivity(
                    context, 13, fallback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, REASSIGNED_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("🦸 New Volunteer Assigned!")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(Color.GREEN)
                        .setColorized(true)
                        .setAutoCancel(true)
                        .setContentIntent(tapPending);

        if (trackPending != null) {
            builder.addAction(0, "📍 Track New Volunteer", trackPending);
        }

        manager.notify(NOTIF_ID + 5, builder.build());
        Log.d(TAG, "✅ volunteer_reassigned notification shown | alertId=" + alertId
                + " | newVolunteer=" + newVolunteerName);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Quiet informational notification
    // ─────────────────────────────────────────────────────────────────────────

    private void showInfoNotification(Context context, String message) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        final String INFO_CHANNEL_ID = "rescue_info_channel";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    INFO_CHANNEL_ID, "RescueConnect Updates",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Volunteer status updates from RescueConnect");
            channel.enableLights(true);
            channel.setLightColor(Color.GREEN);
            manager.createNotificationChannel(channel);
        }

        Intent tapIntent = new Intent(context, VolunteerHomeActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 5, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, INFO_CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_emergency)
                        .setContentTitle("RescueConnect")
                        .setContentText(message)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

        manager.notify(NOTIF_ID + 1, builder.build());
        Log.d(TAG, "ℹ️ Info notification shown: " + message);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Intent buildMapIntent(Context context, String mapsUrl) {
        if (mapsUrl != null && !mapsUrl.isEmpty()) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return i;
        }
        Intent i = new Intent(context, VolunteerHomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}