package com.rescueconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.rescueconnect.Contact.ContactModel;
import com.rescueconnect.Contact.ContactsDB;
import com.rescueconnect.Contact.FirestoreContactHelper;
import com.rescueconnect.Loader.ShowLoader;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import me.pushy.sdk.Pushy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HomeActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * Main screen of RescueConnect. Responsibilities:
 *
 *   • SOS button  — call or message emergency contacts + nearby volunteers
 *   • Monitor Me  — start/stop MonitorMeService foreground service
 *   • Volunteer coming banner — real-time Firestore listener for responders
 *   • Resolved-alert confirmation dialog — feedback after a volunteer resolves an alert
 *   • Nearby hospitals — opens Google Maps with hospital search
 *   • Settings — configures SOS cancel window, Monitor Me intervals
 *
 * Improvements over v1:
 *   1. Guard all UI references before use (null-safe) to prevent NPE crashes.
 *   2. monitorStateReceiver is only registered once (in onResume) and always
 *      unregistered in onPause — previously onCreate registered without unregistering.
 *   3. startMonitorMeService() helper deduplicates the API-level branch.
 *   4. showCallOptions() consolidated into a single switch block (removes the
 *      duplicated online/offline branch).
 *   5. fetchLocationThenSendSms() adds a getLastLocation() fallback so the
 *      flow doesn't silently abort when getCurrentLocation() returns null.
 *   6. sendSmsToContactList() logs the sent count at INFO level for debugging.
 *   7. dialNumber() uses ACTION_CALL with a CALL_PHONE permission check and falls
 *      back gracefully to ACTION_DIAL, with a toast on empty numbers.
 *   8. migrateContactsIfNeeded() simplified: removed repeated SPHelper.GetData()
 *      call and null-guard tightened.
 *   9. isMonitorMeRunning() kept but deprecated comment added — developers should
 *      prefer a shared flag/SharedPreferences for Android 8+ where
 *      getRunningServices() is restricted.
 *  10. refreshIntervalBadge() early-returns safely when view is null.
 *  11. cleanPhone() early-returns on null input.
 *  12. GOV_EMERGENCY constant is used consistently everywhere — no more raw "112"
 *      literals scattered through the code.
 *  13. All Firestore/SMS callbacks dismiss the loader before showing a toast so
 *      it is never left spinning on error paths.
 *  14. Volunteer-coming banner: trackLiveBtn click-listener is set once in
 *      showVolunteerComingBanner() per update rather than leaking multiple
 *      anonymous listeners.
 *  15. CancellationTokenSource is cancelled in failure/cancel paths to avoid
 *      location-task leaks.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class HomeActivity extends AppCompatActivity {

    private static final String TAG              = "HomeActivity";
    private static final double MAX_DISTANCE_KM  = 30.0;
    private static final String GOV_EMERGENCY    = "112";
    private static final String VOLUNTEER_COLLECTION = "volunteers";

    // Emergency service numbers (India)
    private static final String POLICE_NUMBER    = "100";
    private static final String AMBULANCE_NUMBER = "108";
    private static final String FIRE_NUMBER      = "101";

    private ShowLoader                  showLoader;
    private FusedLocationProviderClient fusedLocationClient;
    private FirebaseFirestore           firebaseFirestore;
    private ContactsDB                  contactsDB;
    private FirestoreContactHelper      firestoreContacts;

    private double lat, lon;
    private String myName   = "";
    private String myMobile = "";

    private final Set<String>    shownConfirmations = new HashSet<>();
    private ListenerRegistration resolvedAlertsListener;

    // ── Volunteer-coming banner ───────────────────────────────────────────────
    private View     volunteerComingCard;
    private TextView volunteerComingText;
    private Button   trackLiveBtn;
    private ListenerRegistration volunteerComingListener;
    private String   activeAlertId       = "";
    private String   activeVolunteerName = "";

    // ── Monitor Me UI ─────────────────────────────────────────────────────────
    private LinearLayout monitorInactiveLayout;
    private LinearLayout monitorActiveLayout;
    private TextView     monitorNextCheckText;
    private TextView     monitorIntervalBadge;

    /**
     * Receives BROADCAST_STATE_CHANGED from MonitorMeService.
     * Registered in onResume, unregistered in onPause.
     * Created here so it is never null when unregisterReceiver is called.
     */
    private final BroadcastReceiver monitorStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean monitoring = intent.getBooleanExtra(
                    MonitorMeService.EXTRA_IS_MONITORING, false);
            updateMonitorMeUi(monitoring);
        }
    };

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        showLoader          = new ShowLoader(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        firebaseFirestore   = FirebaseFirestore.getInstance();
        contactsDB          = ContactsDB.getInstance(this);

        myName   = SPHelper.GetData(this, Constants.SP_NAME);
        myMobile = SPHelper.GetData(this, Constants.SP_MOBILE);
        if (myName   == null) myName   = "";
        if (myMobile == null) myMobile = "";

        firestoreContacts = new FirestoreContactHelper(myMobile);
        migrateContactsIfNeeded();

        Pushy.listen(this);

        if (!myMobile.isEmpty()) {
            registerPushyTokenAndSave();
            listenForResolvedAlerts();
            listenForVolunteerComing();
        }

        // ── Bind views ────────────────────────────────────────────────────────
        volunteerComingCard  = findViewById(R.id.volunteerComingCard);
        volunteerComingText  = findViewById(R.id.volunteerComingText);
        trackLiveBtn         = findViewById(R.id.trackLiveBtn);

        monitorInactiveLayout = findViewById(R.id.monitorInactiveLayout);
        monitorActiveLayout   = findViewById(R.id.monitorActiveLayout);
        monitorNextCheckText  = findViewById(R.id.monitorNextCheckText);
        monitorIntervalBadge  = findViewById(R.id.monitorIntervalBadge);

        // Restore UI state if the service was already running before this
        // Activity was created (e.g. user navigated away and came back).
        updateMonitorMeUi(isMonitorMeRunning());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(
                monitorStateReceiver,
                new IntentFilter(MonitorMeService.BROADCAST_STATE_CHANGED));

        // Sync UI with current service state and latest settings.
        updateMonitorMeUi(isMonitorMeRunning());
        refreshIntervalBadge();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(monitorStateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (resolvedAlertsListener  != null) resolvedAlertsListener.remove();
        if (volunteerComingListener != null) volunteerComingListener.remove();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pushy token registration
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Registers this device with Pushy on a background thread and writes the
     * token to Firestore so volunteers can push notifications to this user.
     */
    private void registerPushyTokenAndSave() {
        new Thread(() -> {
            try {
                String token = Pushy.register(getApplicationContext());
                Log.d(TAG, "Pushy token: " + token);
                firebaseFirestore.collection("users")
                        .document(myMobile)
                        .update("pushyToken", token)
                        .addOnSuccessListener(v -> Log.d(TAG, "pushyToken saved"))
                        .addOnFailureListener(e -> {
                            // Document may not exist yet — use merge to create it.
                            java.util.Map<String, Object> d = new java.util.HashMap<>();
                            d.put("pushyToken", token);
                            firebaseFirestore.collection("users")
                                    .document(myMobile)
                                    .set(d, com.google.firebase.firestore.SetOptions.merge())
                                    .addOnSuccessListener(v2 -> Log.d(TAG, "pushyToken saved via merge"))
                                    .addOnFailureListener(e2 -> Log.e(TAG, "pushyToken save failed: " + e2.getMessage()));
                        });
            } catch (Exception e) {
                Log.e(TAG, "Pushy.register() failed: " + e.getMessage(), e);
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Volunteer-coming banner (real-time)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Watches messages/{alertId} for THIS user's unresolved alerts.
     * The moment a volunteer adds themselves to "responders", the banner appears.
     */
    @SuppressWarnings("unchecked")
    private void listenForVolunteerComing() {
        if (myMobile.isEmpty()) return;

        volunteerComingListener = firebaseFirestore.collection("messages")
                .whereEqualTo("senderMobile", myMobile)
                .whereEqualTo("resolved", false)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) {
                        Log.e(TAG, "listenForVolunteerComing error: " + error.getMessage());
                        return;
                    }
                    if (snapshot == null) return;

                    String foundVolName = null;
                    String foundAlertId = null;

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        List<String> responders = (List<String>) doc.get("responders");
                        if (responders != null && !responders.isEmpty()) {
                            foundVolName = responders.get(0);
                            foundAlertId = doc.getId();
                            break;
                        }
                    }

                    if (foundVolName != null) {
                        activeAlertId       = foundAlertId;
                        activeVolunteerName = foundVolName;
                        showVolunteerComingBanner(foundVolName, foundAlertId);
                    } else {
                        hideVolunteerComingBanner();
                    }
                });
    }

    private void showVolunteerComingBanner(String volunteerName, String alertId) {
        runOnUiThread(() -> {
            if (volunteerComingCard == null || volunteerComingText == null
                    || trackLiveBtn == null) return;
            volunteerComingText.setText("🦸 " + volunteerName + " is coming to save you!");
            volunteerComingCard.setVisibility(View.VISIBLE);
            // Replace any previously set listener to avoid stacking callbacks.
            trackLiveBtn.setOnClickListener(v -> {
                Intent intent = new Intent(this, TrackVolunteerActivity.class);
                intent.putExtra(TrackVolunteerActivity.EXTRA_ALERT_ID,       alertId);
                intent.putExtra(TrackVolunteerActivity.EXTRA_VOLUNTEER_NAME, volunteerName);
                startActivity(intent);
            });
        });
    }

    private void hideVolunteerComingBanner() {
        runOnUiThread(() -> {
            if (volunteerComingCard != null)
                volunteerComingCard.setVisibility(View.GONE);
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Contact migration (SQLite → Firestore, one-time)
    // ──────────────────────────────────────────────────────────────────────────

    private void migrateContactsIfNeeded() {
        String migrated = SPHelper.GetData(this, "contacts_migrated");
        if ("true".equals(migrated) || myMobile.isEmpty()) return;

        List<ContactModel> sqliteContacts = contactsDB.getAllContacts();
        if (sqliteContacts == null || sqliteContacts.isEmpty()) {
            SPHelper.SaveData(this, "contacts_migrated", "true");
            return;
        }
        firestoreContacts.migrateFromSQLite(sqliteContacts, success -> {
            if (success) SPHelper.SaveData(this, "contacts_migrated", "true");
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Resolved-alert confirmation dialog
    // ──────────────────────────────────────────────────────────────────────────

    private void listenForResolvedAlerts() {
        resolvedAlertsListener = firebaseFirestore.collection("messages")
                .whereEqualTo("senderMobile", myMobile)
                .whereEqualTo("resolved", true)
                .addSnapshotListener((snapshot, error) -> {
                    if (error != null) {
                        Log.e(TAG, "listenForResolvedAlerts error: " + error.getMessage());
                        return;
                    }
                    if (snapshot == null) return;

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String  docId      = doc.getId();
                        Boolean asked      = doc.getBoolean("confirmationAsked");
                        String  resolvedBy = doc.getString("resolvedBy");

                        if (Boolean.TRUE.equals(asked)) continue;
                        if (shownConfirmations.contains(docId)) continue;

                        shownConfirmations.add(docId);
                        String volunteerName = (resolvedBy != null && !resolvedBy.isEmpty())
                                ? resolvedBy : "a volunteer";

                        // Mark immediately so we don't re-show on reconnect.
                        firebaseFirestore.collection("messages")
                                .document(docId)
                                .update("confirmationAsked", true);

                        String alertMsg = doc.getString("message");
                        if (alertMsg == null) alertMsg = "";
                        final String finalAlertMsg = alertMsg;

                        runOnUiThread(() ->
                                showVolunteerConfirmationDialog(volunteerName, docId, finalAlertMsg));
                    }
                });
    }

    private void showVolunteerConfirmationDialog(String volunteerName,
                                                 String alertId,
                                                 String alertMessage) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("Were You Helped? 🤝")
                .setMessage("Did " + volunteerName
                        + " really come to your rescue?\n\n"
                        + "Your feedback helps us verify volunteer activity.")
                .setCancelable(false)
                .setPositiveButton("Yes, They Helped Me! ✅", (d, w) -> {
                    d.dismiss();
                    Intent intent = new Intent(this, ContributionActivity.class);
                    intent.putExtra("volunteerName", volunteerName);
                    intent.putExtra("alertId",       alertId);
                    intent.putExtra("alertMessage",  alertMessage);
                    intent.putExtra("userMobile",    myMobile);
                    intent.putExtra("userName",      myName);
                    startActivity(intent);
                })
                .setNegativeButton("No, They Didn't", (d, w) -> {
                    d.dismiss();
                    firebaseFirestore.collection("messages")
                            .document(alertId)
                            .update("userConfirmed", false);
                })
                .show();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SOS button
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for the SOS button (android:onClick="helpImplementation").
     * When offline, only the Call option is shown since messaging requires internet.
     */
    public void helpImplementation(View view) {
        boolean networkAvailable = isNetworkAvailable();

        String[] options = networkAvailable
                ? new String[]{"📞  Call", "💬  Message"}
                : new String[]{"📞  Call"};

        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.need_help);
        dialog.setTitle("Select option");
        ListView dialogList = dialog.findViewById(R.id.helpList);
        dialogList.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, options));

        dialogList.setOnItemClickListener((parent, v, position, id) -> {
            dialog.dismiss();
            if (isMissingLocationPermission()) {
                showLoader.PresentToast("Location permission is required.");
                return;
            }
            if (position == 0) {
                showCallOptions(networkAvailable);
            } else {
                // "Message" — only reachable when network is available (index 1)
                showLoader.showProgressDialog();
                String message = SPHelper.GetData(this, Constants.SP_EMERGENCY_MESSAGE);
                if (message == null || message.trim().isEmpty()) {
                    message = "EMERGENCY! I need help.";
                }
                fetchLocationThenSendSms(message);
            }
        });

        dialog.show();
    }

    /**
     * Consolidated call-options dialog.
     * With network: volunteer option is included.
     * Without network: government services only (indices are offset accordingly).
     */
    private void showCallOptions(boolean networkAvailable) {
        // Build options and a parallel number array so we avoid duplicated switch blocks.
        final String[] options;
        final String[] directNumbers; // null entry = requires location fetch

        if (networkAvailable) {
            options = new String[]{
                    "🦸  Call Volunteer",
                    "🚔  Police (100)",
                    "🚑  Ambulance (108)",
                    "🔥  Fire Service (101)"
            };
            directNumbers = new String[]{null, POLICE_NUMBER, AMBULANCE_NUMBER, FIRE_NUMBER};
        } else {
            options = new String[]{
                    "🚔  Police (100)",
                    "🚑  Ambulance (108)",
                    "🔥  Fire Service (101)"
            };
            directNumbers = new String[]{POLICE_NUMBER, AMBULANCE_NUMBER, FIRE_NUMBER};
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Emergency Service")
                .setItems(options, (dialog, which) -> {
                    String number = directNumbers[which];
                    if (number != null) {
                        dialNumber(number);
                    } else {
                        // Volunteer — needs location first
                        showLoader.showProgressDialog();
                        fetchLocationThenCall();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void addEmergencyContactImplementation(View view) {
        startActivity(new Intent(this, AddContactsActivity.class));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CALL FLOW
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void fetchLocationThenCall() {
        if (!isNetworkAvailable()) {
            showLoader.dismissDialog();
            fetchLastLocationThenCallGov();
            return;
        }

        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        showLoader.dismissDialog();
                        callEmergencyContactOrGov("Could not get location. Calling emergency contact...");
                        return;
                    }
                    lat = location.getLatitude();
                    lon = location.getLongitude();
                    firebaseFirestore.collection(VOLUNTEER_COLLECTION).get()
                            .addOnSuccessListener(snapshot -> {
                                showLoader.dismissDialog();
                                String nearestNumber = findNearestVolunteerNumber(snapshot);
                                if (nearestNumber != null) {
                                    dialNumber(nearestNumber);
                                } else {
                                    callEmergencyContactOrGov(
                                            "No volunteers within " + MAX_DISTANCE_KM
                                                    + " km. Calling emergency contact...");
                                }
                            })
                            .addOnFailureListener(e -> {
                                showLoader.dismissDialog();
                                callEmergencyContactOrGov("Server error. Calling emergency contact...");
                            });
                })
                .addOnFailureListener(e -> {
                    cts.cancel();
                    showLoader.dismissDialog();
                    callEmergencyContactOrGov("Location error. Calling emergency contact...");
                });
    }

    @SuppressLint("MissingPermission")
    private void fetchLastLocationThenCallGov() {
        showLoader.PresentToast("No internet. Contacting emergency services...");
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        lat = location.getLatitude();
                        lon = location.getLongitude();
                        String coordsText = "EMERGENCY! My location: "
                                + lat + "," + lon
                                + " https://maps.google.com/?q=" + lat + "," + lon;
                        try {
                            SmsManager sms = getSmsManager();
                            if (coordsText.length() > 160) {
                                sms.sendMultipartTextMessage(
                                        GOV_EMERGENCY, null, sms.divideMessage(coordsText),
                                        null, null);
                            } else {
                                sms.sendTextMessage(GOV_EMERGENCY, null, coordsText, null, null);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "SMS to " + GOV_EMERGENCY + " failed", e);
                        }
                    }
                    dialNumber(GOV_EMERGENCY);
                })
                .addOnFailureListener(e -> dialNumber(GOV_EMERGENCY));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SMS / SOS FLOW
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void fetchLocationThenSendSms(final String message) {
        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        // Fallback: try last known location before giving up.
                        Log.w(TAG, "getCurrentLocation returned null — trying getLastLocation");
                        fusedLocationClient.getLastLocation()
                                .addOnSuccessListener(lastLoc -> {
                                    if (lastLoc == null) {
                                        showLoader.dismissDialog();
                                        showLoader.PresentToast(
                                                "Could not get location. Enable GPS and try again.");
                                        return;
                                    }
                                    sendSmsWithLocation(lastLoc.getLatitude(),
                                            lastLoc.getLongitude(), message);
                                })
                                .addOnFailureListener(e2 -> {
                                    showLoader.dismissDialog();
                                    showLoader.PresentToast("Location error: " + e2.getMessage());
                                });
                        return;
                    }
                    sendSmsWithLocation(location.getLatitude(), location.getLongitude(), message);
                })
                .addOnFailureListener(e -> {
                    cts.cancel();
                    showLoader.dismissDialog();
                    showLoader.PresentToast("Location error: " + e.getMessage());
                });
    }

    /**
     * Sends SOS SMS to all contacts and nearby volunteers once a location is known.
     */
    private void sendSmsWithLocation(double latitude, double longitude, String message) {
        lat = latitude;
        lon = longitude;
        String locationUri = buildLocationUri(lat, lon);

        NotificationHelper.sendEmergencyAlert(lat, lon, myName, myMobile);

        if (!isNetworkAvailable()) {
            int n = sendSmsToEmergencyContactsSQLite(message, locationUri);
            showLoader.dismissDialog();
            showLoader.PresentToast(n > 0
                    ? "No internet. SOS sent to your emergency contacts."
                    : "No internet and no emergency contacts saved.");
            return;
        }

        firestoreContacts.getAllContacts(firestoreContactList -> {
            List<ContactModel> allContacts =
                    mergeContacts(firestoreContactList, contactsDB.getAllContacts());
            int emergencyCount = sendSmsToContactList(allContacts, message, locationUri);

            firebaseFirestore.collection(VOLUNTEER_COLLECTION).get()
                    .addOnSuccessListener(snapshot ->
                            sendSmsToNearbyVolunteers(snapshot, message, locationUri, emergencyCount))
                    .addOnFailureListener(e -> {
                        showLoader.dismissDialog();
                        showLoader.PresentToast("Server error. SOS sent to your emergency contacts.");
                    });
        });
    }

    private List<ContactModel> mergeContacts(List<ContactModel> firestore,
                                             List<ContactModel> sqlite) {
        List<ContactModel> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (firestore != null) {
            for (ContactModel c : firestore) {
                String p = cleanPhone(c.getPhone());
                if (!p.isEmpty() && seen.add(p)) merged.add(c);
            }
        }
        if (sqlite != null) {
            for (ContactModel c : sqlite) {
                String p = cleanPhone(c.getPhone());
                if (!p.isEmpty() && seen.add(p)) merged.add(c);
            }
        }
        return merged;
    }

    private int sendSmsToContactList(List<ContactModel> contacts,
                                     String message,
                                     String locationUri) {
        if (contacts == null || contacts.isEmpty()) return 0;
        String fullMessage = message + " " + locationUri;
        int sentCount = 0;
        for (ContactModel contact : contacts) {
            String phone = cleanPhone(contact.getPhone());
            if (phone.isEmpty()) continue;
            try {
                SmsManager sms = getSmsManager();
                if (fullMessage.length() > 160) {
                    sms.sendMultipartTextMessage(phone, null,
                            sms.divideMessage(fullMessage), null, null);
                } else {
                    sms.sendTextMessage(phone, null, fullMessage, null, null);
                }
                sentCount++;
            } catch (Exception e) {
                Log.e(TAG, "SMS failed for " + contact.getName(), e);
            }
        }
        Log.i(TAG, "SOS SMS sent to " + sentCount + " contact(s)");
        return sentCount;
    }

    private int sendSmsToEmergencyContactsSQLite(String message, String locationUri) {
        return sendSmsToContactList(contactsDB.getAllContacts(), message, locationUri);
    }

    private void sendSmsToNearbyVolunteers(QuerySnapshot snapshot,
                                           String message,
                                           String locationUri,
                                           int alreadySent) {
        showLoader.dismissDialog();
        if (snapshot == null || snapshot.isEmpty()) {
            showLoader.PresentToast(alreadySent > 0
                    ? "No volunteers found. SOS sent to your emergency contacts."
                    : "No volunteers found and no emergency contacts saved.");
            return;
        }

        String fullMessage = message + " " + locationUri;
        int smsSentCount = 0;

        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            volunteerModel v = doc.toObject(volunteerModel.class);
            if (v == null) continue;
            String mobile = cleanPhone(v.getMobile());
            if (mobile.isEmpty()) continue;
            // Skip volunteers with no GPS location.
            if (v.getLatitude() == 0 && v.getLongitude() == 0) continue;

            double dist = VolunteerRegisterActivity.calculateDistance(
                    lat, lon, v.getLatitude(), v.getLongitude());
            if (dist > MAX_DISTANCE_KM) continue;

            try {
                SmsManager sms = getSmsManager();
                if (fullMessage.length() > 160) {
                    sms.sendMultipartTextMessage(mobile, null,
                            sms.divideMessage(fullMessage), null, null);
                } else {
                    sms.sendTextMessage(mobile, null, fullMessage, null, null);
                }
                smsSentCount++;
                Log.d(TAG, "SOS SMS sent to volunteer: " + mobile
                        + " (" + String.format("%.1f", dist) + " km)");
            } catch (Exception e) {
                Log.e(TAG, "SMS failed for volunteer " + v.getName(), e);
            }
        }

        showLoader.PresentToast(
                (smsSentCount > 0
                        ? "SOS sent to " + smsSentCount + " volunteer(s)"
                        : "No volunteers within " + MAX_DISTANCE_KM + " km")
                        + (alreadySent > 0
                        ? " + " + alreadySent + " emergency contact(s)."
                        : ". No emergency contacts saved."));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Nearby hospitals
    // ──────────────────────────────────────────────────────────────────────────

    public void showNearbyHospitals(View view) {
        if (isMissingLocationPermission()) {
            showLoader.PresentToast("Location permission is required to find nearby hospitals.");
            return;
        }
        showLoader.showProgressDialog();
        fetchLocationForHospitals();
    }

    @SuppressLint("MissingPermission")
    private void fetchLocationForHospitals() {
        if (!isNetworkAvailable()) {
            fusedLocationClient.getLastLocation()
                    .addOnSuccessListener(location -> {
                        showLoader.dismissDialog();
                        if (location != null) {
                            openNearbyHospitalsOnMap(
                                    location.getLatitude(), location.getLongitude());
                        } else {
                            showLoader.PresentToast(
                                    "Unable to get your location. Please enable GPS.");
                        }
                    })
                    .addOnFailureListener(e -> {
                        showLoader.dismissDialog();
                        showLoader.PresentToast("Location error: " + e.getMessage());
                    });
            return;
        }

        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    showLoader.dismissDialog();
                    if (location == null) {
                        showLoader.PresentToast(
                                "Could not get location. Please enable GPS and try again.");
                        return;
                    }
                    openNearbyHospitalsOnMap(location.getLatitude(), location.getLongitude());
                })
                .addOnFailureListener(e -> {
                    cts.cancel();
                    showLoader.dismissDialog();
                    showLoader.PresentToast("Location error: " + e.getMessage());
                });
    }

    private void openNearbyHospitalsOnMap(double latitude, double longitude) {
        Uri geoUri = Uri.parse(
                "geo:" + latitude + "," + longitude + "?q=hospitals&z=14");
        Intent mapsIntent = new Intent(Intent.ACTION_VIEW, geoUri);
        mapsIntent.setPackage("com.google.android.apps.maps");
        try {
            startActivity(mapsIntent);
        } catch (android.content.ActivityNotFoundException e) {
            // Google Maps not installed — fall back to any map app, then browser.
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, geoUri));
            } catch (android.content.ActivityNotFoundException ex) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/search/hospitals/@"
                                + latitude + "," + longitude + ",14z")));
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — location, calling, SMS
    // ──────────────────────────────────────────────────────────────────────────

    private String findNearestVolunteerNumber(QuerySnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        String nearestNumber   = null;
        double nearestDistance = Double.MAX_VALUE;
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            volunteerModel v = doc.toObject(volunteerModel.class);
            if (v == null) continue;
            String mobile = cleanPhone(v.getMobile());
            if (mobile.isEmpty()) continue;
            if (v.getLatitude() == 0 && v.getLongitude() == 0) continue;
            double dist = VolunteerRegisterActivity.calculateDistance(
                    lat, lon, v.getLatitude(), v.getLongitude());
            if (dist <= MAX_DISTANCE_KM && dist < nearestDistance) {
                nearestDistance = dist;
                nearestNumber   = mobile;
            }
        }
        return nearestNumber;
    }

    private void callEmergencyContactOrGov(String reason) {
        showLoader.PresentToast(reason);
        if (!myMobile.isEmpty()) {
            firestoreContacts.getAllContacts(contacts -> {
                if (contacts != null) {
                    for (ContactModel c : contacts) {
                        String p = cleanPhone(c.getPhone());
                        if (!p.isEmpty()) { dialNumber(p); return; }
                    }
                }
                callFromSQLiteOrGov();
            });
        } else {
            callFromSQLiteOrGov();
        }
    }

    private void callFromSQLiteOrGov() {
        List<ContactModel> contacts = contactsDB.getAllContacts();
        if (contacts != null) {
            for (ContactModel c : contacts) {
                String p = cleanPhone(c.getPhone());
                if (!p.isEmpty()) { dialNumber(p); return; }
            }
        }
        dialNumber(GOV_EMERGENCY);
    }

    private void dialNumber(String cleanedPhone) {
        if (cleanedPhone == null || cleanedPhone.isEmpty()) {
            showLoader.PresentToast("Invalid phone number.");
            return;
        }
        Uri uri = Uri.parse("tel:" + cleanedPhone);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(Intent.ACTION_CALL, uri));
        } else {
            // Permission not granted — open dialler so user can call manually.
            startActivity(new Intent(Intent.ACTION_DIAL, uri));
        }
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return getSystemService(SmsManager.class);
        }
        return SmsManager.getDefault();
    }

    /** Strips all non-digit characters except a leading '+'. */
    private String cleanPhone(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        boolean hasPlus = trimmed.startsWith("+");
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        return hasPlus ? "+" + digits : digits;
    }

    private boolean isMissingLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private String buildLocationUri(double latitude, double longitude) {
        return "http://maps.google.com/maps?saddr=&daddr=" + latitude + "," + longitude;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Settings (multi-step AlertDialog)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for the settings gear icon (android:onClick="settingsImplementation").
     * Opens SafetySettingsActivity — a dedicated full-screen settings page.
     */
    public void settingsImplementation(View view) {
        startActivity(new Intent(this, SafetySettingsActivity.class));
    }

    /**
     * Updates the badge on the Monitor Me card to show the current check-in
     * interval (e.g. "2 min check-ins"). Called on onResume and after saving.
     */
    private void refreshIntervalBadge() {
        if (monitorIntervalBadge == null) return;
        long   ms    = MonitorSettingsHelper.getMonitorPingInterval(this);
        String label = MonitorSettingsHelper.labelForValue(ms);
        monitorIntervalBadge.setText(label + " check-ins");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Monitor Me
    // ──────────────────────────────────────────────────────────────────────────

    /** Called by activity_home.xml android:onClick="monitorMeImplementation". */
    public void monitorMeImplementation(View view) {
        startMonitorMeService(MonitorMeService.ACTION_START);
        updateMonitorMeUi(true); // Optimistic — broadcast confirms
    }

    /** Called by activity_home.xml android:onClick="reachedDestinationImplementation". */
    public void reachedDestinationImplementation(View view) {
        startMonitorMeService(MonitorMeService.ACTION_STOP);
        updateMonitorMeUi(false); // Optimistic — broadcast confirms
    }

    /**
     * Starts (or sends a command to) MonitorMeService with the correct API-level
     * call. Extracted to avoid repeating the Build.VERSION branch.
     */
    private void startMonitorMeService(String action) {
        Intent intent = new Intent(this, MonitorMeService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * Toggles between the inactive (blue "Start Monitoring") and active
     * (green "Monitoring Active" + "Reached Destination" button) card states.
     */
    private void updateMonitorMeUi(boolean monitoring) {
        runOnUiThread(() -> {
            if (monitorInactiveLayout == null || monitorActiveLayout == null) return;
            monitorInactiveLayout.setVisibility(
                    monitoring ? View.GONE : View.VISIBLE);
            monitorActiveLayout.setVisibility(
                    monitoring ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * Checks whether MonitorMeService is currently running.
     *
     * NOTE: {@link android.app.ActivityManager#getRunningServices} is restricted
     * on Android 8+ and only returns the app's own services — this is intentional
     * and still works for our use case, but the API is formally deprecated.
     * A more reliable approach for future versions would be to persist the running
     * state in SharedPreferences (set by the service, cleared on stop).
     */
    @SuppressWarnings("deprecation")
    private boolean isMonitorMeRunning() {
        android.app.ActivityManager am =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info
                : am.getRunningServices(Integer.MAX_VALUE)) {
            if (MonitorMeService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
}