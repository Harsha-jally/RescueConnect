package com.rescueconnect;

import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;

/**
 * TrackVolunteerActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Opened on the SOS *user's* phone when they receive the "volunteer_coming"
 * push notification and tap it (or tap the "📍 Track Volunteer" action button).
 *
 * Listens to Firestore messages/{alertId} in real-time and moves a green marker
 * on the map every time the volunteer's VolunteerLocationService pushes a new
 * GPS fix (every ~5 seconds).
 *
 * NEW in this version:
 *   · Live ETA displayed in the bottom sheet ("🕐 ~4 min away")
 *   · Auto-arrival detection: when volunteer is within ARRIVAL_THRESHOLD_METRES
 *     the UI switches to "reached" automatically — no need to rely solely on the
 *     Firestore `resolved` flag.
 *
 * Firestore fields read from messages/{alertId}:
 *   volunteerLat  (Double) — volunteer's live latitude  (written by VolunteerLocationService)
 *   volunteerLon  (Double) — volunteer's live longitude
 *   lat           (Double) — SOS user's latitude        (written when alert was raised)
 *   lon           (Double) — SOS user's longitude
 *   resolved      (Boolean)— set true by backend / volunteer on arrival
 *
 * Intent extras required:
 *   EXTRA_ALERT_ID       (String)  — Firestore document ID of the alert
 *   EXTRA_VOLUNTEER_NAME (String)  — display name shown on the marker
 *
 * Layout: R.layout.activity_track_volunteer
 *   · R.id.trackStatusText       — top-bar live status line
 *   · R.id.trackVolunteerName    — top-bar name headline
 *   · R.id.volunteerMapFragment  — full-screen SupportMapFragment
 *   · R.id.trackEtaText          — ETA chip in the bottom sheet  (NEW)
 *   · R.id.trackDistanceText     — distance label in bottom sheet (NEW)
 */
public class TrackVolunteerActivity extends AppCompatActivity
        implements OnMapReadyCallback {

    private static final String TAG = "TrackVolunteer";

    // ── Intent extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_ALERT_ID       = "alertId";
    public static final String EXTRA_VOLUNTEER_NAME = "volunteerName";

    /**
     * Volunteer is considered "arrived" when they are within this distance of
     * the user's SOS location.  50 m is tight enough to be meaningful but
     * forgiving enough for urban GPS drift.
     */
    private static final float ARRIVAL_THRESHOLD_METRES = 50f;

    /**
     * Assumed average travel speed used for ETA.
     * 40 km/h  ≈  666 m/min — reasonable for urban driving / scooter.
     * Increase to ~833 (50 km/h) if volunteers typically travel by car on
     * open roads in your deployment area.
     */
    private static final float AVG_SPEED_METRES_PER_MIN = 666f; // 40 km/h

    // ── State ─────────────────────────────────────────────────────────────────
    private GoogleMap            googleMap;
    private Marker               volunteerMarker;
    private FirebaseFirestore    db;
    private ListenerRegistration locationListener;

    private String   alertId;
    private String   volunteerName;

    // SOS user's fixed position — read once from Firestore
    private Double   userLat;
    private Double   userLon;

    // UI
    private TextView statusText;
    private TextView volunteerNameText;
    private TextView etaText;          // NEW — bottom-sheet ETA chip
    private TextView distanceText;     // NEW — bottom-sheet distance label

    private boolean  firstCameraMove  = true;
    private boolean  arrivalConfirmed = false;   // prevent repeated UI flips
    private String   currentState     = "";      // tracks cancelled / reassigned state

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_track_volunteer);

        alertId       = getIntent().getStringExtra(EXTRA_ALERT_ID);
        volunteerName = getIntent().getStringExtra(EXTRA_VOLUNTEER_NAME);
        if (volunteerName == null || volunteerName.isEmpty()) volunteerName = "Volunteer";

        db = FirebaseFirestore.getInstance();

        // ── Bind views ────────────────────────────────────────────────────────
        statusText        = findViewById(R.id.trackStatusText);
        volunteerNameText = findViewById(R.id.trackVolunteerName);
        etaText           = findViewById(R.id.trackEtaText);       // NEW
        distanceText      = findViewById(R.id.trackDistanceText);  // NEW

        statusText.setText("🔍 Connecting to " + volunteerName + "'s location…");
        if (volunteerNameText != null)
            volunteerNameText.setText("🦸 " + volunteerName + " is on the way!");

        // Back button
        View backBtn = findViewById(R.id.trackBackBtn);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        if (alertId == null || alertId.isEmpty()) {
            Toast.makeText(this, "Cannot track: alert ID missing", Toast.LENGTH_LONG).show();
            statusText.setText("❌ Cannot track — alert ID missing.");
            return;
        }

        // Initialise map — onMapReady() fires when tiles are loaded
        SupportMapFragment mapFrag = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.volunteerMapFragment);
        if (mapFrag != null) mapFrag.getMapAsync(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationListener != null) {
            locationListener.remove();
            locationListener = null;
        }
    }

    // ─── Map ready ───────────────────────────────────────────────────────────

    @Override
    public void onMapReady(GoogleMap map) {
        googleMap = map;
        googleMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);
        startListeningToVolunteerLocation();
    }

    // ─── Real-time Firestore listener ─────────────────────────────────────────

    private void startListeningToVolunteerLocation() {
        if (alertId == null || alertId.isEmpty()) return;

        locationListener = db.collection("messages").document(alertId)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) {
                        Log.w(TAG, "Firestore listener error: " + error.getMessage());
                        return;
                    }
                    if (snapshot == null || !snapshot.exists()) return;

                    // ── NEW: Volunteer cancelled — switch UI before anything else ──
                    Boolean volunteerCancelled = snapshot.getBoolean("volunteerCancelled");
                    if (Boolean.TRUE.equals(volunteerCancelled)) {
                        // Check if admin has already reassigned a new volunteer
                        String reassignedVolunteer = snapshot.getString("reassignedVolunteer");
                        if (reassignedVolunteer != null && !reassignedVolunteer.isEmpty()
                                && !reassignedVolunteer.equals(volunteerName)) {
                            // A new volunteer is already assigned — update header and continue
                            handleVolunteerReassigned(reassignedVolunteer);
                        } else {
                            // Cancelled, no replacement yet — show waiting state
                            handleVolunteerCancelled();
                            return;
                        }
                    }

                    // ── NEW: Admin reassigned but flag not yet cleared ─────────────
                    // (reassignedVolunteer may be set without volunteerCancelled == false
                    //  in the brief window between Firestore writes)
                    String reassignedName = snapshot.getString("reassignedVolunteer");
                    if (reassignedName != null && !reassignedName.isEmpty()
                            && !reassignedName.equals(volunteerName)) {
                        handleVolunteerReassigned(reassignedName);
                    }

                    // ── Read SOS user's fixed position ─────────────────────────────
                    if (userLat == null) userLat = safeGetDouble(snapshot, "lat");
                    if (userLon == null) userLon = safeGetDouble(snapshot, "lon");

                    // ── Read volunteer's live position ─────────────────────────────
                    Double volLat = safeGetDouble(snapshot, "volunteerLat");
                    Double volLon = safeGetDouble(snapshot, "volunteerLon");

                    if (volLat == null || volLon == null) {
                        statusText.setText("⏳ Waiting for "
                                + volunteerName + " to share their location…");
                        setEtaVisible(false);
                        return;
                    }

                    // ── Update / create the green volunteer marker ─────────────────
                    LatLng position = new LatLng(volLat, volLon);

                    if (volunteerMarker == null) {
                        volunteerMarker = googleMap.addMarker(new MarkerOptions()
                                .position(position)
                                .title("🦸 " + volunteerName + " is coming!")
                                .icon(BitmapDescriptorFactory
                                        .defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                    } else {
                        volunteerMarker.setPosition(position);
                    }

                    // Animate camera: zoom on first fix, gently follow after
                    if (firstCameraMove) {
                        googleMap.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(position, 15f));
                        firstCameraMove = false;
                    } else {
                        googleMap.animateCamera(
                                CameraUpdateFactory.newLatLng(position));
                    }

                    // ── Distance & ETA (requires user location) ───────────────────
                    Boolean resolvedFlag = snapshot.getBoolean("resolved");

                    if (userLat != null && userLon != null) {
                        float distanceMetres = calculateDistance(
                                volLat, volLon, userLat, userLon);

                        boolean arrived = Boolean.TRUE.equals(resolvedFlag)
                                || distanceMetres <= ARRIVAL_THRESHOLD_METRES;

                        if (arrived) {
                            handleArrived();
                        } else {
                            handleEnRoute(distanceMetres);
                        }
                    } else {
                        setEtaVisible(false);
                        if (Boolean.TRUE.equals(resolvedFlag)) {
                            handleArrived();
                        } else {
                            statusText.setText("📍 " + volunteerName
                                    + " is on the way — location is live!");
                        }
                    }

                    Log.d(TAG, "📍 Volunteer updated → " + volLat + ", " + volLon);
                });
    }

    // ─── State handlers ───────────────────────────────────────────────────────

    /**
     * Called when Firestore sets volunteerCancelled=true and no replacement has
     * been assigned yet.  The UI switches to a warm "hang tight" state so the
     * user knows help is still coming.
     */
    private void handleVolunteerCancelled() {
        // Don't flicker if already in cancelled state
        if ("cancelled".equals(currentState)) return;
        currentState = "cancelled";

        statusText.setText("😔 " + volunteerName
                + " is unable to come. The admin has been notified and is "
                + "arranging a replacement — please stay safe! 🙏");

        if (volunteerNameText != null)
            volunteerNameText.setText("⏳ Finding a new volunteer…");

        if (etaText != null) {
            etaText.setText("🔄 Reassigning…");
            setEtaVisible(true);
        }
        if (distanceText != null)
            distanceText.setText("📏 Waiting for reassignment");

        // Turn the marker orange to signal "on hold"
        if (volunteerMarker != null) {
            volunteerMarker.setIcon(BitmapDescriptorFactory
                    .defaultMarker(BitmapDescriptorFactory.HUE_ORANGE));
            volunteerMarker.setTitle("⏳ Reassigning…");
        }

        Log.d(TAG, "⚠️ handleVolunteerCancelled() — waiting for admin to reassign");
    }

    /**
     * Called when Firestore sets reassignedVolunteer to a new name.
     * Updates the volunteer name displayed throughout the screen and resets the
     * marker to green so live tracking resumes seamlessly for the new volunteer.
     */
    private void handleVolunteerReassigned(String newVolunteerName) {
        if (newVolunteerName.equals(volunteerName)) return;  // same name — no change
        if ("reassigned_to_" .equals(currentState + newVolunteerName)) return;
        currentState = "reassigned_to_" + newVolunteerName;

        String previousName = volunteerName;
        volunteerName = newVolunteerName;   // update for all subsequent UI updates

        statusText.setText("🦸 " + newVolunteerName
                + " is on their way to help you! Stay calm — help is coming! 💪");

        if (volunteerNameText != null)
            volunteerNameText.setText("🦸 " + newVolunteerName + " is on the way!");

        if (etaText != null) {
            etaText.setText("🔄 Connecting to " + newVolunteerName + "…");
            setEtaVisible(true);
        }

        // Reset the map marker for the new volunteer
        if (volunteerMarker != null) {
            volunteerMarker.setIcon(BitmapDescriptorFactory
                    .defaultMarker(BitmapDescriptorFactory.HUE_GREEN));
            volunteerMarker.setTitle("🦸 " + newVolunteerName + " is coming!");
            volunteerMarker.setSnippet(null);
        }

        // Allow camera to zoom in fresh for the new volunteer
        firstCameraMove  = true;
        arrivalConfirmed = false;

        Log.d(TAG, "🔄 Volunteer reassigned: " + previousName + " → " + newVolunteerName);
    }

    /**
     * Called on every location update while the volunteer is still en route.
     * Refreshes the ETA chip and status line.
     */
    private void handleEnRoute(float distanceMetres) {
        arrivalConfirmed = false;

        // ── Status text ───────────────────────────────────────────────────────
        statusText.setText("📍 " + volunteerName + " is on the way — location is live!");

        // ── Distance label ────────────────────────────────────────────────────
        String distLabel;
        if (distanceMetres < 1000f) {
            distLabel = String.format("📏 %.0f m away", distanceMetres);
        } else {
            distLabel = String.format("📏 %.1f km away", distanceMetres / 1000f);
        }
        if (distanceText != null) distanceText.setText(distLabel);

        // ── ETA chip ──────────────────────────────────────────────────────────
        String etaLabel = buildEtaLabel(distanceMetres);
        if (etaText != null) {
            etaText.setText(etaLabel);
            setEtaVisible(true);
        }
    }

    /**
     * Called once when auto-detection or the resolved flag confirms arrival.
     * Flips the entire UI to the "safe" state.
     */
    private void handleArrived() {
        if (arrivalConfirmed) return;   // already shown — don't flicker
        arrivalConfirmed = true;

        // Update status bar
        statusText.setText("✅ " + volunteerName + " has reached you! You are safe. 💚");
        if (volunteerNameText != null)
            volunteerNameText.setText("✅ " + volunteerName + " has reached you!");

        // Swap ETA chip to arrival message
        if (etaText != null) {
            etaText.setText("🎉 Volunteer has arrived!");
            setEtaVisible(true);
        }
        if (distanceText != null)
            distanceText.setText("📏 < " + (int) ARRIVAL_THRESHOLD_METRES + " m — arrived");

        // Move camera tighter on arrival
        if (volunteerMarker != null && googleMap != null) {
            googleMap.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(volunteerMarker.getPosition(), 17f));
            volunteerMarker.setTitle("✅ " + volunteerName + " arrived!");
            volunteerMarker.setIcon(BitmapDescriptorFactory
                    .defaultMarker(BitmapDescriptorFactory.HUE_AZURE));
        }

        Log.d(TAG, "✅ Auto-arrival confirmed for alert " + alertId);
    }

    // ─── Helper utilities ─────────────────────────────────────────────────────

    /**
     * Straight-line distance in metres between two GPS points.
     * Uses Android's Location.distanceBetween which applies the WGS-84 ellipsoid.
     */
    private float calculateDistance(double fromLat, double fromLon,
                                    double toLat,   double toLon) {
        float[] result = new float[1];
        Location.distanceBetween(fromLat, fromLon, toLat, toLon, result);
        return result[0];
    }

    /**
     * Converts a straight-line distance into a human-readable ETA string.
     *
     * The raw straight-line distance is multiplied by a 1.3 routing factor to
     * account for roads not being perfectly straight, then divided by the
     * average speed constant.  The factor keeps estimates honest rather than
     * optimistic.
     *
     * Result examples:
     *   3 200 m → "🕐 ~6 min away"
     *   180 m   → "🕐 Less than a minute away"
     *    48 m   → "🚶 Almost there!"
     */
    private String buildEtaLabel(float straightLineMetres) {
        if (straightLineMetres <= ARRIVAL_THRESHOLD_METRES) {
            return "🚶 Almost there!";
        }

        // Apply road-route correction factor
        float routedMetres = straightLineMetres * 1.3f;
        int etaMinutes     = Math.round(routedMetres / AVG_SPEED_METRES_PER_MIN);

        if (etaMinutes < 1) return "🕐 Less than a minute away";
        if (etaMinutes == 1) return "🕐 ~1 min away";
        return "🕐 ~" + etaMinutes + " min away";
    }

    /** Shows or hides the ETA + distance row in the bottom sheet. */
    private void setEtaVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (etaText     != null) etaText.setVisibility(visibility);
        if (distanceText != null) distanceText.setVisibility(visibility);
    }

    /**
     * Safely reads a Firestore field as a Double whether it was stored as a
     * numeric type OR as a plain String (e.g. "12.9716").
     * Returns null if the field is missing, null, or unparseable.
     */
    private Double safeGetDouble(com.google.firebase.firestore.DocumentSnapshot snap,
                                 String field) {
        Object value = snap.get(field);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "safeGetDouble: cannot parse '" + field + "' = " + value);
            return null;
        }
    }
}