package com.rescueconnect;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * NotificationHelper
 * ─────────────────────────────────────────────────────────────────────────────
 * Handles all outbound push notifications for RescueConnect:
 *
 *  1. sendEmergencyAlert()             — SOS broadcast to ALL volunteers (topic push)
 *
 *  2. sendVolunteerComingNotification() — Personal push to the SOS *user* when a
 *                                         volunteer taps "I Am Going".
 *                                         ✅ Now includes alertId + volunteerName so
 *                                         PushyReceiver can open TrackVolunteerActivity
 *                                         directly with the correct live-tracking data.
 */
public class NotificationHelper {

    private static final String TAG = "NotifHelper";

    static final String PUSHY_SECRET_KEY =
            "980fdf0833fce6a29755b0b5d95be9349185b608e0264a1b01edbcc23b38f29a";

    private static final String PUSHY_TOPIC   = "/topics/emergency_alerts";
    private static final String PUSHY_API_URL =
            "https://api.pushy.me/push?api_key=" + PUSHY_SECRET_KEY;

    private static final MediaType JSON_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final String FAST2SMS_API_KEY =
            "lawn6JIteH40hZbM7vkSO1KyYFTCNrQBWx5VRdgqX9sPiA8j3fYiZ928qpGet6RWmJfPIF1aKOQ4VC5j";
    private static final double RADIUS_KM = 10.0;

    // ─────────────────────────────────────────────────────────────────────────
    // 1.  EMERGENCY ALERT  —  broadcast to all volunteers via topic push
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendEmergencyAlert(double userLat, double userLon,
                                          String userName, String userMobile) {

        FirebaseFirestore db = FirebaseFirestore.getInstance();

        String today   = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());
        String message = "Emergency! " + userName + " (" + userMobile + ") needs help near "
                + String.format(Locale.getDefault(), "%.4f, %.4f", userLat, userLon);

        Map<String, Object> alertData = new HashMap<>();
        alertData.put("message",      message);
        alertData.put("date",         today);
        alertData.put("lat",          String.valueOf(userLat));
        alertData.put("lon",          String.valueOf(userLon));
        alertData.put("location",     userLat + "," + userLon);
        alertData.put("senderName",   userName);
        alertData.put("senderMobile", userMobile);
        alertData.put("resolved",     false);

        db.collection("messages")
                .add(alertData)
                .addOnSuccessListener(ref -> {
                    String alertId = ref.getId();

                    ref.update("alertId", alertId)
                            .addOnSuccessListener(v ->
                                    Log.d(TAG, "✅ alertId written back: " + alertId))
                            .addOnFailureListener(e ->
                                    Log.w(TAG, "Could not write alertId back: " + e.getMessage()));

                    Log.d(TAG, "✅ Alert saved to Firestore: " + alertId);

                    new Thread(() -> {
                        sendPushyAlert(message, userLat, userLon, alertId);
                        sendSmsToNearbyVolunteers(db, userLat, userLon, message);
                    }).start();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Firestore save failed: " + e.getMessage());
                    new Thread(() -> sendPushyAlert(message, userLat, userLon, "")).start();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pushy topic push — reaches ALL subscribed volunteers
    // ─────────────────────────────────────────────────────────────────────────

    private static void sendPushyAlert(String message, double lat, double lon,
                                       String alertId) {
        try {
            String mapsUrl = "https://maps.google.com/maps?q=" + lat + "," + lon;

            JSONObject data = new JSONObject();
            data.put("type",    "emergency");
            data.put("message", message);
            data.put("lat",     String.valueOf(lat));
            data.put("lon",     String.valueOf(lon));
            data.put("mapsUrl", mapsUrl);
            data.put("alertId", alertId != null ? alertId : "");

            JSONObject notification = new JSONObject();
            notification.put("title", "RescueConnect");
            notification.put("body",  "🚨 " + message);

            JSONObject payload = new JSONObject();
            payload.put("to",           PUSHY_TOPIC);
            payload.put("data",         data);
            payload.put("notification", notification);

            Log.d(TAG, "📤 Pushy emergency push → " + PUSHY_TOPIC + " | alertId=" + alertId);

            RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
            Request request = new Request.Builder()
                    .url(PUSHY_API_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept",       "application/json")
                    .build();

            try (Response response = HTTP.newCall(request).execute()) {
                String rb = response.body() != null ? response.body().string() : "(empty)";
                if (response.isSuccessful()) {
                    Log.d(TAG, "✅ Emergency push sent! Response: " + rb);
                } else {
                    Log.e(TAG, "❌ Emergency push failed (" + response.code() + "): " + rb);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ sendPushyAlert exception: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2.  VOLUNTEER COMING  —  personal push to the SOS user's device
    //
    //  ✅ UPDATED: alertId and volunteerName are now included in the push data
    //     so that PushyReceiver can open TrackVolunteerActivity directly.
    //
    //  @param userPushyToken    Stored in Firestore: users/{doc}.pushyToken
    //  @param volunteerName     Display name of the responding volunteer
    //  @param alertId           Firestore document ID of the alert — used by
    //                           TrackVolunteerActivity to subscribe to live updates
    //  @param volunteerMapsUrl  One-shot Google Maps URL (fallback if Maps SDK unavailable)
    //  @param friendlyMessage   Full warm message built by VolunteerHomeActivity
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendVolunteerComingNotification(String userPushyToken,
                                                       String volunteerName,
                                                       String alertId,           // ← NEW
                                                       String volunteerMapsUrl,
                                                       String friendlyMessage) {
        if (userPushyToken == null || userPushyToken.isEmpty()) {
            Log.w(TAG, "sendVolunteerComingNotification: userPushyToken is empty — skipping");
            return;
        }

        new Thread(() -> {
            try {
                String msg;
                if (friendlyMessage != null && !friendlyMessage.trim().isEmpty()) {
                    msg = friendlyMessage;
                } else {
                    msg = "🦸 Volunteer " + volunteerName
                            + " is on their way to save you! Help is coming, stay strong! 💪";
                    if (volunteerMapsUrl != null && !volunteerMapsUrl.isEmpty()) {
                        msg += "\n📍 Track their live location here: " + volunteerMapsUrl;
                    }
                }

                // ── data block — PushyReceiver reads these as Intent extras ──
                JSONObject data = new JSONObject();
                data.put("type",          "volunteer_coming");
                data.put("message",       msg);
                data.put("mapsUrl",       volunteerMapsUrl != null ? volunteerMapsUrl : "");
                // ✅ NEW: PushyReceiver passes these to TrackVolunteerActivity
                data.put("alertId",       alertId != null ? alertId : "");
                data.put("volunteerName", volunteerName != null ? volunteerName : "");

                // ── notification block — visible in the system tray ──────────
                JSONObject notification = new JSONObject();
                notification.put("title", "🦸 Help Is On The Way!");
                notification.put("body",  msg);

                // Send to a SPECIFIC device token — NOT a topic broadcast
                JSONObject payload = new JSONObject();
                payload.put("to",           userPushyToken);
                payload.put("data",         data);
                payload.put("notification", notification);

                Log.d(TAG, "📤 Pushy volunteer-coming push → device token | alertId=" + alertId);

                RequestBody body = RequestBody.create(payload.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(PUSHY_API_URL)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept",       "application/json")
                        .build();

                try (Response response = HTTP.newCall(request).execute()) {
                    String rb = response.body() != null ? response.body().string() : "(empty)";
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ Volunteer-coming push sent! Response: " + rb);
                    } else {
                        Log.e(TAG, "❌ Volunteer-coming push failed ("
                                + response.code() + "): " + rb);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ sendVolunteerComingNotification exception: " + e.getMessage(), e);
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMS fallback — sends via Fast2SMS to nearby volunteers
    // ─────────────────────────────────────────────────────────────────────────

    private static void sendSmsToNearbyVolunteers(FirebaseFirestore db,
                                                  double userLat, double userLon,
                                                  String message) {
        CountDownLatch latch    = new CountDownLatch(1);
        List<String> nearbyMobs = new ArrayList<>();

        db.collection("volunteer").get()
                .addOnSuccessListener(snapshot -> {
                    for (com.google.firebase.firestore.DocumentSnapshot doc
                            : snapshot.getDocuments()) {
                        volunteerModel vol = doc.toObject(volunteerModel.class);
                        if (vol == null) continue;
                        if (vol.getLatitude()  == null || vol.getLongitude() == null
                                || (vol.getLatitude() == 0.0 && vol.getLongitude() == 0.0)) {
                            Log.w(TAG, "Skipping " + vol.getName() + " — no location");
                            continue;
                        }
                        double dist = calculateDistance(
                                userLat, userLon,
                                vol.getLatitude(), vol.getLongitude());
                        if (dist <= RADIUS_KM
                                && vol.getMobile() != null
                                && !vol.getMobile().isEmpty()) {
                            nearbyMobs.add(vol.getMobile());
                            Log.d(TAG, vol.getName() + " is " + dist + " km → SMS queued");
                        }
                    }
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Volunteer query failed: " + e.getMessage());
                    latch.countDown();
                });

        try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        if (!nearbyMobs.isEmpty()) {
            Log.d(TAG, "📱 Sending SMS to " + nearbyMobs.size() + " volunteer(s)");
            sendFast2Sms(nearbyMobs, message);
        }
    }

    private static void sendFast2Sms(List<String> mobiles, String message) {
        try {
            String encoded = java.net.URLEncoder.encode(message, "UTF-8");
            String urlStr  = "https://www.fast2sms.com/dev/bulkV2"
                    + "?authorization=" + FAST2SMS_API_KEY
                    + "&route=q"
                    + "&message=" + encoded
                    + "&flash=0"
                    + "&numbers=" + String.join(",", mobiles);

            Request request = new Request.Builder().url(urlStr)
                    .addHeader("cache-control", "no-cache")
                    .get().build();

            try (Response response = HTTP.newCall(request).execute()) {
                String rb = response.body() != null ? response.body().string() : "(empty)";
                Log.d(TAG, "Fast2SMS (" + response.code() + "): " + rb);
            }
        } catch (Exception e) {
            Log.e(TAG, "Fast2SMS failed: " + e.getMessage(), e);
        }
    }

    private static double calculateDistance(double lat1, double lon1,
                                            double lat2, double lon2) {
        float[] r = new float[1];
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, r);
        return r[0] / 1000.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3.  CANT GO  —  volunteer cancels; notify admin + SOS user
    //
    //  Called from VolunteerHomeActivity.handleCantGo() after Firestore is
    //  updated.  Sends two pushes in parallel:
    //    a) cant_go  →  admin device   (so admin sees a heads-up + Reassign btn)
    //    b) volunteer_cancelled → SOS user  (so TrackVolunteerActivity reacts)
    //
    //  @param adminPushyToken    Stored in Firestore: admins/{doc}.pushyToken
    //  @param userPushyToken     Stored in Firestore: users/{doc}.pushyToken
    //  @param volunteerName      Display name of the cancelling volunteer
    //  @param alertId            Firestore document ID
    //  @param alertSnippet       Short description of the alert (for admin notif body)
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendCantGoNotificationToAdmin(String adminPushyToken,
                                                     String volunteerName,
                                                     String alertId,
                                                     String alertSnippet) {
        if (adminPushyToken == null || adminPushyToken.isEmpty()) {
            Log.w(TAG, "sendCantGoNotificationToAdmin: adminPushyToken empty — skipping");
            return;
        }
        new Thread(() -> {
            try {
                String body = "⚠️ " + volunteerName + " cannot respond to the SOS alert.\n"
                        + "Alert: " + (alertSnippet != null ? alertSnippet : "")
                        + "\n👆 Tap to reassign a volunteer.";

                JSONObject data = new JSONObject();
                data.put("type",          "cant_go");
                data.put("message",       body);
                data.put("volunteerName", volunteerName != null ? volunteerName : "");
                data.put("alertId",       alertId != null ? alertId : "");

                JSONObject notification = new JSONObject();
                notification.put("title", "⚠️ Volunteer Cancelled");
                notification.put("body",  body);

                JSONObject payload = new JSONObject();
                payload.put("to",           adminPushyToken);
                payload.put("data",         data);
                payload.put("notification", notification);

                RequestBody reqBody = RequestBody.create(payload.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(PUSHY_API_URL)
                        .post(reqBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept",       "application/json")
                        .build();

                try (Response response = HTTP.newCall(request).execute()) {
                    String rb = response.body() != null ? response.body().string() : "(empty)";
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ cant_go push sent to admin | alertId=" + alertId);
                    } else {
                        Log.e(TAG, "❌ cant_go push failed (" + response.code() + "): " + rb);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ sendCantGoNotificationToAdmin exception: " + e.getMessage(), e);
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.  VOLUNTEER CANCELLED  —  notify SOS user that volunteer pulled out.
    //     TrackVolunteerActivity listens for the `volunteerCancelled` Firestore
    //     flag, but this push wakes the screen if the app is in the background.
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendVolunteerCancelledNotificationToUser(String userPushyToken,
                                                                String volunteerName,
                                                                String alertId) {
        if (userPushyToken == null || userPushyToken.isEmpty()) {
            Log.w(TAG, "sendVolunteerCancelledNotificationToUser: token empty — skipping");
            return;
        }
        new Thread(() -> {
            try {
                String body = "😔 " + volunteerName
                        + " is unable to come. Don't worry — the admin has been notified "
                        + "and is assigning another volunteer. Stay safe! 🙏";

                JSONObject data = new JSONObject();
                data.put("type",          "volunteer_cancelled");
                data.put("message",       body);
                data.put("volunteerName", volunteerName != null ? volunteerName : "");
                data.put("alertId",       alertId != null ? alertId : "");

                JSONObject notification = new JSONObject();
                notification.put("title", "😔 Volunteer Update");
                notification.put("body",  body);

                JSONObject payload = new JSONObject();
                payload.put("to",           userPushyToken);
                payload.put("data",         data);
                payload.put("notification", notification);

                RequestBody reqBody = RequestBody.create(payload.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(PUSHY_API_URL)
                        .post(reqBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept",       "application/json")
                        .build();

                try (Response response = HTTP.newCall(request).execute()) {
                    String rb = response.body() != null ? response.body().string() : "(empty)";
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ volunteer_cancelled push sent to user | alertId=" + alertId);
                    } else {
                        Log.e(TAG, "❌ volunteer_cancelled push failed (" + response.code() + "): " + rb);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ sendVolunteerCancelledNotificationToUser exception: " + e.getMessage(), e);
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5.  VOLUNTEER REASSIGNED  —  admin assigned a new volunteer; notify user.
    //     Carries the new volunteer's name + alertId so TrackVolunteerActivity
    //     can refresh its header and re-attach its Firestore listener.
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendVolunteerReassignedNotificationToUser(String userPushyToken,
                                                                 String newVolunteerName,
                                                                 String alertId) {
        if (userPushyToken == null || userPushyToken.isEmpty()) {
            Log.w(TAG, "sendVolunteerReassignedNotificationToUser: token empty — skipping");
            return;
        }
        new Thread(() -> {
            try {
                String body = "🦸 Great news! " + newVolunteerName
                        + " is now on the way to help you! Stay calm — help is coming! 💪";

                JSONObject data = new JSONObject();
                data.put("type",          "volunteer_reassigned");
                data.put("message",       body);
                data.put("volunteerName", newVolunteerName != null ? newVolunteerName : "");
                data.put("alertId",       alertId != null ? alertId : "");

                JSONObject notification = new JSONObject();
                notification.put("title", "🦸 New Volunteer Assigned!");
                notification.put("body",  body);

                JSONObject payload = new JSONObject();
                payload.put("to",           userPushyToken);
                payload.put("data",         data);
                payload.put("notification", notification);

                RequestBody reqBody = RequestBody.create(payload.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(PUSHY_API_URL)
                        .post(reqBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept",       "application/json")
                        .build();

                try (Response response = HTTP.newCall(request).execute()) {
                    String rb = response.body() != null ? response.body().string() : "(empty)";
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ volunteer_reassigned push sent to user | alertId=" + alertId);
                    } else {
                        Log.e(TAG, "❌ volunteer_reassigned push failed (" + response.code() + "): " + rb);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ sendVolunteerReassignedNotificationToUser exception: " + e.getMessage(), e);
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6.  REASSIGN PUSH TO NEW VOLUNTEER  —  direct push to the newly assigned
    //     volunteer's device token (like a fresh emergency alert, but targeted).
    //     The volunteer receives a heads-up with "I Am Going" action button.
    // ─────────────────────────────────────────────────────────────────────────

    public static void sendReassignPushToVolunteer(String volunteerPushyToken,
                                                   String alertId,
                                                   String alertMessage,
                                                   String mapsUrl) {
        if (volunteerPushyToken == null || volunteerPushyToken.isEmpty()) {
            Log.w(TAG, "sendReassignPushToVolunteer: token empty — skipping");
            return;
        }
        new Thread(() -> {
            try {
                String body = "🔁 You have been reassigned to an active SOS alert.\n"
                        + (alertMessage != null ? alertMessage : "")
                        + "\n📍 Tap to navigate and respond.";

                JSONObject data = new JSONObject();
                data.put("type",    "emergency");   // reuse emergency type so action buttons appear
                data.put("message", body);
                data.put("alertId", alertId != null ? alertId : "");
                data.put("mapsUrl", mapsUrl != null ? mapsUrl : "");

                JSONObject notification = new JSONObject();
                notification.put("title", "🔁 SOS Reassigned To You");
                notification.put("body",  body);

                JSONObject payload = new JSONObject();
                payload.put("to",           volunteerPushyToken);
                payload.put("data",         data);
                payload.put("notification", notification);

                RequestBody reqBody = RequestBody.create(payload.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                        .url(PUSHY_API_URL)
                        .post(reqBody)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Accept",       "application/json")
                        .build();

                try (Response response = HTTP.newCall(request).execute()) {
                    String rb = response.body() != null ? response.body().string() : "(empty)";
                    if (response.isSuccessful()) {
                        Log.d(TAG, "✅ Reassign push sent to volunteer | alertId=" + alertId);
                    } else {
                        Log.e(TAG, "❌ Reassign push failed (" + response.code() + "): " + rb);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ sendReassignPushToVolunteer exception: " + e.getMessage(), e);
            }
        }).start();
    }
}