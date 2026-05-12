package com.rescueconnect;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.rescueconnect.Contact.ContactModel;
import com.rescueconnect.Contact.ContactsDB;
import com.rescueconnect.Contact.FirestoreContactHelper;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SosDispatcher
 * ─────────────────────────────────────────────────────────────────────────────
 * Sends an auto-detected SOS from a background Service context.
 *
 * NORMAL PATH — EmergencyDetectionService calls sendSos() directly:
 *   EmergencyDetectionService maintains a continuous HIGH_ACCURACY location
 *   subscription and passes the warm cached fix straight to sendSos(). In
 *   this path fetchLocationAndSendSos() is never called — the location is
 *   already known and verified non-zero. This is the fast path with accurate
 *   coordinates and zero cold-start latency.
 *
 * FALLBACK PATH — fetchLocationAndSendSos() is called when cache is stale/null:
 *   Location strategy (most reliable first):
 *     1. getCurrentLocation(HIGH_ACCURACY) — fresh GPS + network fix.
 *        Reliable from a foreground service because the CPU wake-lock is held.
 *        Typical accuracy: ±5–20 m indoors/outdoors.
 *     2. getLastLocation()                 — instant OS-cached fix. Used only
 *        if step 1 returns null (rare from a foreground service).
 *     3. sendSosWithoutLocation()          — absolute last resort. Sends an
 *        SMS without coordinates so contacts know to call immediately.
 *        Passes 0.0,0.0 to Firestore so the alert still appears on the map.
 *
 * LOCATION ACCURACY NOTE:
 *   PRIORITY_BALANCED_POWER_ACCURACY (old) has been replaced throughout with
 *   PRIORITY_HIGH_ACCURACY. The foreground service wake-lock means the extra
 *   battery cost during an SOS event is negligible, and the improvement from
 *   network-only (~50–300 m) to GPS (~5–20 m) is critical for emergency use.
 *
 * SUPPORTED SOS TYPES (passed from EmergencyDetectionService):
 *   "drop"           — phone dropped / thrown
 *   "crash"          — vehicle crash or violent collision
 *   "rapid_stop"     — rapid movement then sudden stop
 *   "scream"         — scream / panic audio detected
 *   "volume_hold"    — Volume-Down button held for 15 seconds (manual SOS)
 *   "monitor_not_safe"  — user tapped "Not Safe" in Monitor Me
 *   "monitor_timeout"   — no response to Monitor Me check-in
 */
public class SosDispatcher {

    private static final String TAG = "SosDispatcher";

    // ──────────────────────────────────────────────────────────────────────────
    // Fallback entry point — used when EmergencyDetectionService has no warm cache
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetches a fresh HIGH_ACCURACY location then dispatches SOS.
     *
     * Only called when EmergencyDetectionService's warm cachedLocation is absent
     * or stale (> 60 s old). In normal operation the service passes coordinates
     * directly to sendSos() and this method is not used.
     *
     * Strategy:
     *   1. getCurrentLocation(HIGH_ACCURACY) — primary, most accurate
     *   2. getLastLocation()                 — fast fallback if step 1 returns null
     *   3. sendSosWithoutLocation()          — last resort, no coordinates
     */
    public static void fetchLocationAndSendSos(Context ctx, String reason, String type) {
        boolean hasFine   = ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "No location permission — sending SOS without coordinates");
            sendSosWithoutLocation(ctx, reason, type);
            return;
        }

        FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(ctx);

        // ── Step 1: getCurrentLocation(HIGH_ACCURACY) — primary ───────────────
        // Preferred as first call because it returns the freshest possible fix.
        // HIGH_ACCURACY uses GPS + Wi-Fi + network → typically ±5–20 m.
        // From a foreground service this reliably returns within 2–5 s because
        // the wake-lock keeps the GPS driver active.
        CancellationTokenSource cts = new CancellationTokenSource();
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null
                            && (location.getLatitude() != 0.0
                            || location.getLongitude() != 0.0)) {
                        Log.d(TAG, "Fresh HIGH_ACCURACY fix: "
                                + location.getLatitude() + "," + location.getLongitude()
                                + " acc=" + location.getAccuracy() + "m");
                        sendSos(ctx, reason, type,
                                location.getLatitude(), location.getLongitude());
                    } else {
                        // Null is rare from a foreground service but possible when
                        // GPS is disabled system-wide. Fall back to last-known.
                        Log.w(TAG, "getCurrentLocation returned null — trying getLastLocation");
                        tryGetLastLocation(ctx, reason, type, client);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getCurrentLocation failed: " + e.getMessage()
                            + " — trying getLastLocation");
                    tryGetLastLocation(ctx, reason, type, client);
                });
    }

    /**
     * Secondary fallback: getLastLocation() — instant OS-cached fix.
     * Falls back to no-location SOS if it also fails or returns null/zero.
     */
    @SuppressWarnings("MissingPermission")
    private static void tryGetLastLocation(Context ctx, String reason, String type,
                                           FusedLocationProviderClient client) {
        boolean hasFine   = ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarse = ActivityCompat.checkSelfPermission(ctx,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasFine && !hasCoarse) {
            sendSosWithoutLocation(ctx, reason, type);
            return;
        }

        client.getLastLocation()
                .addOnSuccessListener(lastLocation -> {
                    if (lastLocation != null
                            && (lastLocation.getLatitude() != 0.0
                            || lastLocation.getLongitude() != 0.0)) {
                        Log.d(TAG, "Last location (fallback): "
                                + lastLocation.getLatitude() + "," + lastLocation.getLongitude()
                                + " acc=" + lastLocation.getAccuracy() + "m");
                        sendSos(ctx, reason, type,
                                lastLocation.getLatitude(), lastLocation.getLongitude());
                    } else {
                        Log.w(TAG, "getLastLocation returned null — no location available");
                        sendSosWithoutLocation(ctx, reason, type);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getLastLocation failed: " + e.getMessage());
                    sendSosWithoutLocation(ctx, reason, type);
                });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core send methods
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Full SOS with confirmed, non-zero coordinates.
     *
     * Called either:
     *   (a) Directly by EmergencyDetectionService with a warm cached GPS fix, OR
     *   (b) Internally by fetchLocationAndSendSos() after a successful fetch.
     *
     * Sends:
     *   • SMS with Google Maps link to all emergency contacts (local + Firestore)
     *   • Pushy push notification to nearby volunteers via NotificationHelper
     */
    public static void sendSos(Context ctx, String reason, String type,
                               double lat, double lon) {
        String myName   = safeGet(ctx, Constants.SP_NAME);
        String myMobile = safeGet(ctx, Constants.SP_MOBILE);

        // Direct Google Maps link — tapping opens Maps app or browser
        String locationUri = "https://maps.google.com/maps?q=" + lat + "," + lon;

        String label   = buildLabel(type);
        String message = label + "\n"
                + (myName.isEmpty() ? "" : myName + " may need urgent help!\n")
                + "📍 Location: " + locationUri;

        Log.w(TAG, "🆘 Dispatching SOS | type=" + type
                + " lat=" + lat + " lon=" + lon
                + " reason=" + reason);

        sendSmsToAllContacts(ctx, myMobile, message);
        NotificationHelper.sendEmergencyAlert(lat, lon, myName, myMobile);

        Log.d(TAG, "✅ SOS dispatch complete");
    }

    /**
     * Last-resort SOS when no location is available at all.
     *
     * Sends 0.0, 0.0 to Firestore so volunteers still see the alert on the map,
     * and includes a "Location unavailable — call them" message in the SMS.
     * This is far better than silently failing to send anything.
     */
    public static void sendSosWithoutLocation(Context ctx, String reason, String type) {
        String myName   = safeGet(ctx, Constants.SP_NAME);
        String myMobile = safeGet(ctx, Constants.SP_MOBILE);

        String label   = buildLabel(type);
        String message = label + "\n"
                + (myName.isEmpty() ? "" : myName + " may need urgent help!\n")
                + "⚠️ Location unavailable — please call them immediately.";

        Log.w(TAG, "🆘 Dispatching SOS (no location) — reason: " + reason);

        sendSmsToAllContacts(ctx, myMobile, message);
        // Still notifies volunteers via Pushy even without coordinates
        NotificationHelper.sendEmergencyAlert(0.0, 0.0, myName, myMobile);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SMS helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static void sendSmsToAllContacts(Context ctx, String myMobile, String message) {
        // Local SQLite contacts — synchronous, fires immediately
        ContactsDB db = ContactsDB.getInstance(ctx);
        List<ContactModel> localContacts = db.getAllContacts();
        Set<String> sentNumbers = new HashSet<>();
        if (localContacts != null) {
            for (ContactModel c : localContacts) {
                String cleaned = cleanPhone(c.getPhone());
                if (!cleaned.isEmpty() && sentNumbers.add(cleaned))
                    trySendSms(ctx, cleaned, message);
            }
        }

        // Firestore contacts — async, de-duplicated against sentNumbers set
        if (myMobile != null && !myMobile.isEmpty()) {
            FirestoreContactHelper fch = new FirestoreContactHelper(myMobile);
            fch.getAllContacts(firestoreContacts -> {
                if (firestoreContacts == null) return;
                for (ContactModel c : firestoreContacts) {
                    String cleaned = cleanPhone(c.getPhone());
                    if (!cleaned.isEmpty() && sentNumbers.add(cleaned))
                        trySendSms(ctx, cleaned, message);
                }
            });
        }

        Log.d(TAG, "SMS fired to " + sentNumbers.size()
                + " local contacts (Firestore may add more async)");
    }

    private static void trySendSms(Context ctx, String cleanPhone, String message) {
        try {
            SmsManager sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ctx.getSystemService(SmsManager.class)
                    : SmsManager.getDefault();
            if (sms == null) {
                Log.e(TAG, "SmsManager null for " + cleanPhone);
                return;
            }
            if (message.length() > 160) {
                sms.sendMultipartTextMessage(cleanPhone, null,
                        sms.divideMessage(message), null, null);
            } else {
                sms.sendTextMessage(cleanPhone, null, message, null, null);
            }
            Log.d(TAG, "📱 SMS → " + cleanPhone);
        } catch (Exception e) {
            Log.e(TAG, "SMS failed for " + cleanPhone + ": " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Strips all non-digit characters from a phone number, preserving a leading
     * '+' for international format numbers. Returns "" if nothing usable remains.
     */
    private static String cleanPhone(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String trimmed  = raw.trim();
        boolean hasPlus = trimmed.startsWith("+");
        String digits   = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        return hasPlus ? "+" + digits : digits;
    }

    private static String safeGet(Context ctx, String key) {
        String v = SPHelper.GetData(ctx, key);
        return v != null ? v : "";
    }

    /**
     * Maps the machine-readable SOS type to a human-readable SMS label.
     * Keep this in sync with the type strings used in EmergencyDetectionService.
     */
    private static String buildLabel(String type) {
        if (type == null) return "🚨 AUTO-SOS ALERT";
        switch (type) {
            case "drop":             return "🚨 AUTO-SOS: Phone Drop Detected";
            case "crash":            return "🚨 AUTO-SOS: Crash / Collision Detected";
            case "rapid_stop":       return "🚨 AUTO-SOS: Rapid Movement / Sudden Stop";
            case "scream":           return "🚨 AUTO-SOS: Scream / Panic Detected";
            case "volume_hold":      return "🚨 MANUAL SOS: Volume Button Held 15 Seconds";
            case "monitor_not_safe": return "🚨 AUTO-SOS: User Reported NOT Safe";
            case "monitor_timeout":  return "🚨 AUTO-SOS: No Response to Check-in";
            default:                 return "🚨 AUTO-SOS: Emergency Detected";
        }
    }
}