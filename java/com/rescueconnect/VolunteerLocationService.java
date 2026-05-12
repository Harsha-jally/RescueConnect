package com.rescueconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * VolunteerLocationService
 * ─────────────────────────────────────────────────────────────────────────────
 * Foreground service that writes the volunteer's live GPS co-ordinates to
 * Firestore every 5 seconds so the SOS user can track them in real-time
 * on the TrackVolunteerActivity map.
 *
 * Firestore path: messages/{alertId}
 *   Fields written:  volunteerLat  (double)
 *                    volunteerLon  (double)
 *                    volunteerLastUpdate (long — epoch ms)
 *
 * Lifecycle:
 *   START  → VolunteerHomeActivity calls startForegroundService() on "I Am Going"
 *   STOP   → VolunteerHomeActivity calls startService(ACTION_STOP) on "I Helped"
 *            Service also self-stops if it loses location permission.
 */
public class VolunteerLocationService extends Service {

    private static final String TAG         = "VolLocService";
    public  static final String ACTION_START    = "com.rescueconnect.START_TRACKING";
    public  static final String ACTION_STOP     = "com.rescueconnect.STOP_TRACKING";
    public  static final String EXTRA_ALERT_ID  = "alertId";

    private static final String CHANNEL_ID  = "vol_location_channel";
    private static final int    NOTIF_ID    = 3001;
    private static final long   INTERVAL_MS = 5_000L;   // 5 s

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback            locationCallback;
    private FirebaseFirestore           db;
    private String                      alertId;

    // ─── Service entry points ─────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        if (ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            return START_NOT_STICKY;
        }

        alertId = intent.getStringExtra(EXTRA_ALERT_ID);
        if (alertId == null || alertId.isEmpty()) {
            Log.w(TAG, "No alertId — cannot start tracking, stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }

        db                  = FirebaseFirestore.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Must call startForeground() immediately on O+ or the system kills us
        startForeground(NOTIF_ID, buildForegroundNotification());
        startLocationUpdates();

        Log.d(TAG, "✅ Tracking started for alertId: " + alertId);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTracking();
    }

    // ─── Location updates ─────────────────────────────────────────────────────

    private void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
                .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
                .setWaitForAccurateLocation(false)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null || alertId == null) return;
                android.location.Location loc = result.getLastLocation();
                if (loc == null) return;

                Map<String, Object> update = new HashMap<>();
                update.put("volunteerLat",        loc.getLatitude());
                update.put("volunteerLon",         loc.getLongitude());
                update.put("volunteerLastUpdate",  System.currentTimeMillis());

                db.collection("messages").document(alertId)
                        .update(update)
                        .addOnSuccessListener(v ->
                                Log.d(TAG, "📍 Location pushed: "
                                        + loc.getLatitude() + ", " + loc.getLongitude()))
                        .addOnFailureListener(e ->
                                Log.w(TAG, "Location update failed: " + e.getMessage()));
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(
                    request, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted: " + e.getMessage());
            stopSelf();
        }
    }

    private void stopTracking() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
        stopForeground(true);
        stopSelf();
        Log.d(TAG, "Tracking stopped");
    }

    // ─── Foreground notification ──────────────────────────────────────────────

    private Notification buildForegroundNotification() {
        NotificationManager mgr =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Volunteer Live Tracking",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Sharing your live location with the person you are helping");
            ch.setShowBadge(false);
            mgr.createNotificationChannel(ch);
        }

        Intent tapIntent = new Intent(this, VolunteerHomeActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🦸 RescueConnect — Live Tracking Active")
                .setContentText("Sharing your location with the person you're helping…")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pi)
                .build();
    }
}