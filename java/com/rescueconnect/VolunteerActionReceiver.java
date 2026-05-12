package com.rescueconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

/**
 * VolunteerActionReceiver
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles the "🚀 I Am Going" action button on the emergency push notification,
 * letting volunteers respond directly from the notification shade WITHOUT having
 * to open the app first (just like a food-delivery driver accepting a trip).
 *
 * Flow:
 *   1. Reads volunteer name from SharedPreferences
 *   2. Adds volunteer to messages/{alertId}.responders in Firestore
 *   3. Starts VolunteerLocationService — GPS now streams to Firestore every 5 s
 *   4. Fetches the SOS sender's pushyToken and fires a personal push:
 *      "🦸 [Name] is coming to save you!"  → opens TrackVolunteerActivity on
 *      the user's device
 *   5. Dismisses the emergency notification
 *   6. Launches VolunteerHomeActivity so the volunteer sees the alert list
 *      and can tap "📍 Navigate" or "✅ I Helped"
 *
 * Registered in AndroidManifest.xml:
 *   <receiver android:name=".VolunteerActionReceiver"
 *             android:exported="false"/>
 */
public class VolunteerActionReceiver extends BroadcastReceiver {

    private static final String TAG = "VolActionReceiver";

    /** Action sent from PushyReceiver when volunteer taps "I Am Going" */
    public static final String ACTION_IM_GOING = "com.rescueconnect.ACTION_IM_GOING";

    /** Notification ID used in PushyReceiver — cancel it after accepting */
    private static final int EMERGENCY_NOTIF_ID = 2001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_IM_GOING.equals(intent.getAction())) return;

        String alertId = intent.getStringExtra("alertId");
        String mapsUrl = intent.getStringExtra("mapsUrl");
        if (alertId == null || alertId.isEmpty()) {
            Log.w(TAG, "ACTION_IM_GOING received with no alertId — ignoring");
            return;
        }

        // Read volunteer name saved by loadVolunteerProfile() / login
        String volName = SPHelper.GetData(context, Constants.SP_NAME);
        if (volName == null || volName.trim().isEmpty()) volName = "Volunteer";
        final String volunteerName = volName.trim();

        Log.d(TAG, "🚀 I Am Going tapped | alertId=" + alertId
                + " | volunteer=" + volunteerName);

        // ── 1. Dismiss the emergency heads-up notification ────────────────────
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(EMERGENCY_NOTIF_ID);

        // ── 2. Firestore: add volunteer to responders ─────────────────────────
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        final String finalAlertId = alertId;

        db.collection("messages").document(alertId)
                .update("responders", FieldValue.arrayUnion(volunteerName))
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "✅ Added " + volunteerName + " to responders");

                    // ── 3. Start live GPS service ─────────────────────────────
                    Intent svc = new Intent(context, VolunteerLocationService.class);
                    svc.setAction(VolunteerLocationService.ACTION_START);
                    svc.putExtra(VolunteerLocationService.EXTRA_ALERT_ID, finalAlertId);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }

                    // ── 4. Push-notify the SOS user ───────────────────────────
                    // Read the alert to get senderMobile, then look up their token
                    db.collection("messages").document(finalAlertId).get()
                            .addOnSuccessListener(alertDoc -> {
                                if (!alertDoc.exists()) return;
                                String senderMobile = alertDoc.getString("senderMobile");
                                if (senderMobile == null || senderMobile.isEmpty()) {
                                    Log.w(TAG, "senderMobile missing — skipping push to user");
                                    return;
                                }

                                // Friendly message for the person in need
                                final String friendlyMsg =
                                        "🦸 Volunteer " + volunteerName
                                                + " is on their way to save you! "
                                                + "Help is coming, stay strong! 💪";

                                db.collection("users").document(senderMobile).get()
                                        .addOnSuccessListener(userDoc -> {
                                            if (!userDoc.exists()) return;
                                            String token = userDoc.getString("pushyToken");
                                            if (token == null || token.isEmpty()) {
                                                Log.w(TAG, "No pushyToken for " + senderMobile);
                                                return;
                                            }
                                            NotificationHelper
                                                    .sendVolunteerComingNotification(
                                                            token,
                                                            volunteerName,
                                                            finalAlertId,
                                                            "",           // no static map URL needed
                                                            friendlyMsg);
                                            Log.d(TAG, "✅ Push sent to SOS user: "
                                                    + senderMobile);
                                        })
                                        .addOnFailureListener(e ->
                                                Log.w(TAG, "Could not load user doc: "
                                                        + e.getMessage()));
                            })
                            .addOnFailureListener(e ->
                                    Log.w(TAG, "Could not load alert doc: " + e.getMessage()));
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "Firestore responders update failed: " + e.getMessage()));

        // ── 5. Open VolunteerHomeActivity so volunteer can navigate / confirm ─
        Intent launchApp = new Intent(context, VolunteerHomeActivity.class);
        launchApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(launchApp);
    }
}