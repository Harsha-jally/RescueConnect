package com.rescueconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.rescueconnect.Loader.ShowLoader;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import me.pushy.sdk.Pushy;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class VolunteerHomeActivity extends AppCompatActivity
        implements AlertAdapter.OnAlertActionListener {

    private static final String TAG        = "VolunteerHome";
    private static final String CHANNEL_ID = "emergency_channel";

    ShowLoader        showLoader;
    FirebaseFirestore firebaseFirestore;
    FirebaseAuth      auth;

    FusedLocationProviderClient fusedLocationClient;

    ArrayList<MessageModel> msgModelLst = new ArrayList<>();
    AlertAdapter            adapter;

    ListView listView;
    TextView greetingText, alertCountText, totalEarnedText, earningsListHeader;
    TextView avatarInitial;
    ListView earningsListView;

    View     earningsToggleRow;
    View     earningsExpandPanel;
    TextView earningsArrow;
    private boolean earningsExpanded = false;

    private ListenerRegistration alertsListener;
    private String currentVolName  = "Volunteer";
    private String currentVolEmail = "";

    private final Set<String> knownDocIds     = new HashSet<>();
    private final Set<String> knownResponders = new HashSet<>();
    private boolean           initialLoadDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_volunteer_home);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        showLoader            = new ShowLoader(this);
        firebaseFirestore     = FirebaseFirestore.getInstance();
        auth                  = FirebaseAuth.getInstance();
        fusedLocationClient   = LocationServices.getFusedLocationProviderClient(this);

        listView           = findViewById(R.id.listView);
        greetingText       = findViewById(R.id.greetingText);
        alertCountText     = findViewById(R.id.alertCountText);
        totalEarnedText    = findViewById(R.id.totalEarnedText);
        earningsListHeader = findViewById(R.id.earningsListHeader);
        earningsListView   = findViewById(R.id.earningsListView);
        avatarInitial      = findViewById(R.id.avatarInitial);

        earningsToggleRow   = findViewById(R.id.earningsToggleRow);
        earningsExpandPanel = findViewById(R.id.earningsExpandPanel);
        earningsArrow       = findViewById(R.id.earningsArrow);

        earningsToggleRow.setOnClickListener(v -> {
            earningsExpanded = !earningsExpanded;
            earningsExpandPanel.setVisibility(earningsExpanded ? View.VISIBLE : View.GONE);
            earningsArrow.setText(earningsExpanded ? "▲" : "▼");
        });

        if (auth.getCurrentUser() != null && auth.getCurrentUser().getEmail() != null)
            currentVolEmail = auth.getCurrentUser().getEmail();

        createNotificationChannel();
        Pushy.listen(this);
        loadVolunteerProfile();
        attachAlertsListener();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (alertsListener != null) alertsListener.remove();
    }

    // ─── Notification channel ─────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("High priority emergency alerts from RescueConnect");
            ch.enableLights(true);
            ch.setLightColor(android.graphics.Color.RED);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 200, 100, 200, 100, 200,
                    300, 500, 100, 500, 100, 500,
                    300, 200, 100, 200, 100, 200});
            ch.setSound(sound, aa);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
    }

    // ─── Profile ──────────────────────────────────────────────────────────────

    private void loadVolunteerProfile() {
        String spName   = SPHelper.GetData(this, Constants.SP_NAME);
        String spMobile = SPHelper.GetData(this, Constants.SP_MOBILE);

        if (spName != null && !spName.trim().isEmpty()) {
            currentVolName = spName.trim();
            greetingText.setText("Hi, " + currentVolName + " 👋");
            avatarInitial.setText(String.valueOf(currentVolName.charAt(0)).toUpperCase());
            rebuildAdapter();
            loadEarnings();
        }

        if (spMobile == null || spMobile.trim().isEmpty()) {
            Log.w(TAG, "No mobile in SP — skipping Firestore name refresh");
            return;
        }

        firebaseFirestore.collection("volunteers")
                .document(spMobile.trim())
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        String firestoreName = doc.getString("name");
                        if (firestoreName != null && !firestoreName.trim().isEmpty()) {
                            currentVolName = firestoreName.trim();
                            greetingText.setText("Hi, " + currentVolName + " 👋");
                            avatarInitial.setText(
                                    String.valueOf(currentVolName.charAt(0)).toUpperCase());
                            SPHelper.SaveData(this, Constants.SP_NAME, currentVolName);
                            rebuildAdapter();
                            loadEarnings();
                        }
                    } else {
                        Log.w(TAG, "Volunteer doc not found for mobile: " + spMobile);
                    }
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "Firestore name refresh failed: " + e.getMessage()));
    }

    // ─── Real-time listener ───────────────────────────────────────────────────

    private void attachAlertsListener() {
        showLoader.showProgressDialog();
        String today = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                .format(new Date());

        alertsListener = firebaseFirestore.collection("messages")
                .addSnapshotListener((snapshot, error) -> {
                    showLoader.dismissDialog();
                    if (error != null || snapshot == null) {
                        if (error != null) showLoader.PresentToast(error.getMessage());
                        return;
                    }

                    msgModelLst.clear();
                    snapshot.getDocuments().forEach(doc -> {
                        MessageModel msg = doc.toObject(MessageModel.class);
                        if (msg != null && today.equals(msg.getDate())) {
                            msg.setFirestoreId(doc.getId());
                            msgModelLst.add(msg);
                        }
                    });

                    alertCountText.setText(msgModelLst.size()
                            + (msgModelLst.size() == 1 ? " Alert" : " Alerts"));
                    rebuildAdapter();

                    if (!initialLoadDone) {
                        snapshot.getDocuments().forEach(doc -> {
                            knownDocIds.add(doc.getId());
                            MessageModel m = doc.toObject(MessageModel.class);
                            if (m != null && m.getResponders() != null)
                                m.getResponders().forEach(r ->
                                        knownResponders.add(doc.getId() + "_" + r));
                        });
                        initialLoadDone = true;
                        return;
                    }

                    snapshot.getDocuments().forEach(doc -> {
                        String docId = doc.getId();
                        if (!knownDocIds.contains(docId)) {
                            knownDocIds.add(docId);
                            MessageModel a = doc.toObject(MessageModel.class);
                            if (a != null && today.equals(a.getDate())) {
                                a.setFirestoreId(docId);
                                showInAppAlertDialog(a);
                                showHeadsUpNotification("🚨 RescueConnect",
                                        a.getMessage(), a.getLat(), a.getLon());
                            }
                        } else {
                            MessageModel msg = doc.toObject(MessageModel.class);
                            if (msg == null || msg.getResponders() == null) return;
                            msg.setFirestoreId(docId);
                            msg.getResponders().forEach(name -> {
                                String key = docId + "_" + name;
                                if (!knownResponders.contains(key)) {
                                    knownResponders.add(key);
                                    if (!name.equals(currentVolName)) {
                                        showResponderDialog(name, msg);
                                        showHeadsUpNotification("✅ RescueConnect",
                                                name + " is on the way!",
                                                msg.getLat(), msg.getLon());
                                    }
                                }
                            });
                        }
                    });
                });
    }

    private void rebuildAdapter() {
        adapter = new AlertAdapter(this, msgModelLst, currentVolName, this);
        listView.setAdapter(adapter);
    }

    // ─── AlertAdapter callbacks ───────────────────────────────────────────────

    @Override
    public void onNavigate(MessageModel alert) {
        openMapsNavigation(alert.getLat(), alert.getLon());
    }

    @Override
    public void onImGoing(MessageModel alert, int position) {
        if (alert.getFirestoreId() == null || alert.getFirestoreId().isEmpty()) {
            Toast.makeText(this, "Alert ID missing", Toast.LENGTH_SHORT).show();
            return;
        }
        showLoader.showProgressDialog();
        firebaseFirestore.collection("messages")
                .document(alert.getFirestoreId())
                .update("responders", FieldValue.arrayUnion(currentVolName))
                .addOnSuccessListener(v -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this,
                            "🦸 You're on the way! The person in need will be notified.",
                            Toast.LENGTH_SHORT).show();

                    if (alert.getResponders() == null)
                        alert.setResponders(new ArrayList<>());
                    if (!alert.getResponders().contains(currentVolName))
                        alert.getResponders().add(currentVolName);
                    adapter.notifyDataSetChanged();

                    fetchLocationThenNotifySOSUser(alert);

                    Intent svc = new Intent(this, VolunteerLocationService.class);
                    svc.setAction(VolunteerLocationService.ACTION_START);
                    svc.putExtra(VolunteerLocationService.EXTRA_ALERT_ID,
                            alert.getFirestoreId());
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(svc);
                    } else {
                        startService(svc);
                    }
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    @SuppressLint("MissingPermission")
    private void fetchLocationThenNotifySOSUser(MessageModel alert) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            notifySOSUser(alert, null, null);
            return;
        }
        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        notifySOSUser(alert,
                                String.valueOf(location.getLatitude()),
                                String.valueOf(location.getLongitude()));
                    } else {
                        fusedLocationClient.getLastLocation()
                                .addOnSuccessListener(last -> {
                                    if (last != null) {
                                        notifySOSUser(alert,
                                                String.valueOf(last.getLatitude()),
                                                String.valueOf(last.getLongitude()));
                                    } else {
                                        notifySOSUser(alert, null, null);
                                    }
                                })
                                .addOnFailureListener(e -> notifySOSUser(alert, null, null));
                    }
                })
                .addOnFailureListener(e -> notifySOSUser(alert, null, null));
    }

    /**
     * Sends a push notification to the SOS user including the volunteer's
     * current GPS location and a recalculated straight-line distance to them.
     */
    private void notifySOSUser(MessageModel alert, String volLat, String volLon) {
        String senderMobile = alert.getSenderMobile();
        if (senderMobile == null || senderMobile.isEmpty()) {
            Log.w(TAG, "senderMobile missing — cannot notify SOS user");
            return;
        }

        final boolean hasLocation = volLat != null && volLon != null;
        final String mapsUrl = hasLocation
                ? "https://www.google.com/maps?q=" + volLat + "," + volLon : "";

        // ── Recalculate distance from volunteer to SOS user ───────────────────
        String distanceInfo = buildDistanceLabel(volLat, volLon, alert.getLat(), alert.getLon());

        final String friendlyMessage = hasLocation
                ? "🦸 Volunteer " + currentVolName + " is on their way to save you! "
                + "Help is coming, stay strong! 💪"
                + distanceInfo
                + "\n📍 Track live: " + mapsUrl
                : "🦸 Volunteer " + currentVolName + " is on their way to save you! "
                + "Help is coming, stay strong! 💪";

        firebaseFirestore.collection("users")
                .document(senderMobile)
                .get()
                .addOnSuccessListener(doc -> {
                    if (!doc.exists()) return;
                    String pushyToken = doc.getString("pushyToken");
                    if (pushyToken == null || pushyToken.isEmpty()) return;
                    NotificationHelper.sendVolunteerComingNotification(
                            pushyToken, currentVolName, alert.getFirestoreId(),
                            mapsUrl, friendlyMessage);
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Firestore FAILED: " + e.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // onCantGo — Full cancellation flow:
    //   1. Confirms intent via dialog (prevents accidental taps)
    //   2. Atomically updates Firestore:
    //        · removes volunteer from responders array
    //        · sets needsReplacement = true   → admin dashboard highlights alert
    //        · sets volunteerCancelled = true → TrackVolunteerActivity reacts
    //        · sets cancelledVolunteer = name → admin knows who dropped out
    //        · deletes volunteerLat/volunteerLon → clears stale GPS pin
    //   3. Stops VolunteerLocationService (startForegroundService on O+)
    //   4. Notifies the SOS user via Pushy
    //   5. Notifies the admin via Pushy (admins/main_admin → users fallback)
    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCantGo(MessageModel alert, int position) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Can't Go?")
                .setMessage("Are you sure you cannot respond to this SOS alert?\n\n"
                        + "The person in need will be notified and the admin will "
                        + "arrange another volunteer.")
                .setPositiveButton("Yes, I Can't Go", (d, w) -> {
                    d.dismiss();
                    performCantGo(alert, position);
                })
                .setNegativeButton("Keep My Commitment", null)
                .show();
    }

    private void performCantGo(MessageModel alert, int position) {
        final String alertId      = alert.getFirestoreId();
        final String senderMobile = alert.getSenderMobile();

        if (alertId == null || alertId.isEmpty()) {
            Toast.makeText(this, "Alert ID missing — cannot update.", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoader.showProgressDialog();

        // ── Step 1: Atomic Firestore update ───────────────────────────────────
        Map<String, Object> updates = new HashMap<>();
        updates.put("responders",         FieldValue.arrayRemove(currentVolName));
        updates.put("needsReplacement",   true);
        updates.put("volunteerCancelled", true);
        updates.put("cancelledVolunteer", currentVolName);
        // Delete stale GPS so TrackVolunteerActivity / user map resets gracefully
        updates.put("volunteerLat",       FieldValue.delete());
        updates.put("volunteerLon",       FieldValue.delete());

        firebaseFirestore.collection("messages")
                .document(alertId)
                .update(updates)
                .addOnSuccessListener(v -> {
                    showLoader.dismissDialog();
                    Log.d(TAG, "✅ Firestore updated — " + currentVolName + " marked can't go");

                    if (alert.getResponders() != null)
                        alert.getResponders().remove(currentVolName);
                    adapter.notifyDataSetChanged();

                    Toast.makeText(this,
                            "✅ Removed from alert. Admin notified to assign a replacement.",
                            Toast.LENGTH_LONG).show();

                    // ── Step 2: Stop live GPS service (foreground-safe) ───────
                    Intent stopSvc = new Intent(this, VolunteerLocationService.class);
                    stopSvc.setAction(VolunteerLocationService.ACTION_STOP);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(stopSvc);
                    } else {
                        startService(stopSvc);
                    }

                    // ── Step 3: Notify SOS user ───────────────────────────────
                    if (senderMobile != null && !senderMobile.isEmpty()) {
                        firebaseFirestore.collection("users")
                                .whereEqualTo("mobile", senderMobile)
                                .limit(1).get()
                                .addOnSuccessListener(snap -> {
                                    if (!snap.isEmpty()) {
                                        String userToken = snap.getDocuments()
                                                .get(0).getString("pushyToken");
                                        if (userToken != null && !userToken.isEmpty()) {
                                            NotificationHelper
                                                    .sendVolunteerCancelledNotificationToUser(
                                                            userToken,
                                                            currentVolName,
                                                            alertId);
                                        }
                                    }
                                })
                                .addOnFailureListener(e ->
                                        Log.w(TAG, "Could not find SOS user: " + e.getMessage()));
                    }

                    // ── Step 4: Notify admin ──────────────────────────────────
                    String alertSnippet = alert.getMessage() != null
                            ? alert.getMessage() : "SOS alert";
                    fetchAdminPushyTokenAndNotify(currentVolName, alertId, alertSnippet);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Log.w(TAG, "Firestore update failed: " + e.getMessage());
                    Toast.makeText(this,
                            "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Looks up the admin's Pushy token and sends a can't-go notification.
     * Primary path: admins/main_admin { pushyToken }
     * Fallback path: users collection where role == "admin"
     */
    private void fetchAdminPushyTokenAndNotify(String volunteerName,
                                               String alertId,
                                               String alertSnippet) {
        firebaseFirestore.collection("admins").document("main_admin").get()
                .addOnSuccessListener(adminDoc -> {
                    if (!adminDoc.exists()) {
                        Log.w(TAG, "admins/main_admin not found — trying users fallback");
                        fetchAdminTokenFromUsers(volunteerName, alertId, alertSnippet);
                        return;
                    }
                    String adminToken = adminDoc.getString("pushyToken");
                    if (adminToken == null || adminToken.isEmpty()) {
                        Log.w(TAG, "admins/main_admin pushyToken is empty");
                        fetchAdminTokenFromUsers(volunteerName, alertId, alertSnippet);
                        return;
                    }
                    NotificationHelper.sendCantGoNotificationToAdmin(
                            adminToken, volunteerName, alertId, alertSnippet);
                    Log.d(TAG, "✅ cant_go push sent to admin via admins/main_admin");
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "admins collection error: " + e.getMessage());
                    fetchAdminTokenFromUsers(volunteerName, alertId, alertSnippet);
                });
    }

    /** Fallback: find admin token from users collection where role == "admin". */
    private void fetchAdminTokenFromUsers(String volunteerName,
                                          String alertId,
                                          String alertSnippet) {
        firebaseFirestore.collection("users")
                .whereEqualTo("role", "admin")
                .limit(1).get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        Log.w(TAG, "No admin found in users collection");
                        return;
                    }
                    String adminToken = snap.getDocuments().get(0).getString("pushyToken");
                    if (adminToken == null || adminToken.isEmpty()) {
                        Log.w(TAG, "Admin user has no pushyToken");
                        return;
                    }
                    NotificationHelper.sendCantGoNotificationToAdmin(
                            adminToken, volunteerName, alertId, alertSnippet);
                    Log.d(TAG, "✅ cant_go push sent to admin via users fallback");
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "users admin query failed: " + e.getMessage()));
    }

    @Override
    public void onCallUser(MessageModel alert) {
        String mobile = alert.getSenderMobile();
        if (mobile == null || mobile.trim().isEmpty()) {
            Toast.makeText(this, "User number not available.", Toast.LENGTH_SHORT).show();
            return;
        }
        String cleanedMobile = mobile.trim().replaceAll("[^0-9+]", "");
        if (cleanedMobile.isEmpty()) {
            Toast.makeText(this, "Invalid user number.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("📞 Call User")
                .setMessage("Call " + (alert.getSenderName() != null && !alert.getSenderName().isEmpty()
                        ? alert.getSenderName() : "this user") + " at\n" + cleanedMobile + "?")
                .setPositiveButton("Call Now", (d, w) -> {
                    d.dismiss();
                    dialNumber(cleanedMobile);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void dialNumber(String phone) {
        Uri uri = Uri.parse("tel:" + phone);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(Intent.ACTION_CALL, uri));
        } else {
            startActivity(new Intent(Intent.ACTION_DIAL, uri));
        }
    }

    private void markAlertResolved(MessageModel alert) {
        if (alert.getFirestoreId() == null) return;
        Map<String, Object> updates = new HashMap<>();
        updates.put("resolved",   true);
        updates.put("resolvedBy", currentVolName);
        showLoader.showProgressDialog();
        firebaseFirestore.collection("messages")
                .document(alert.getFirestoreId())
                .update(updates)
                .addOnSuccessListener(v -> {
                    showLoader.dismissDialog();
                    alert.setResolved(true);
                    alert.setResolvedBy(currentVolName);
                    adapter.notifyDataSetChanged();
                    Intent stopSvc = new Intent(this, VolunteerLocationService.class);
                    stopSvc.setAction(VolunteerLocationService.ACTION_STOP);
                    startService(stopSvc);
                    new AlertDialog.Builder(this)
                            .setTitle("🎉 Thank You!")
                            .setMessage("You are a hero, " + currentVolName + "! 🦸\n\n"
                                    + "The person you helped will be notified to confirm.\n\n"
                                    + "Your contribution has been recorded.")
                            .setPositiveButton("OK", (d2, w) -> d2.dismiss())
                            .show();
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    @Override
    public void onIHelped(MessageModel alert, int position) {
        new AlertDialog.Builder(this)
                .setTitle("✅ Confirm Help")
                .setMessage("Confirm that you successfully helped "
                        + (alert.getSenderName() != null && !alert.getSenderName().isEmpty()
                        ? alert.getSenderName() : "this person") + "?")
                .setPositiveButton("Yes, I Helped", (d, w) -> {
                    d.dismiss();
                    markAlertResolved(alert);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Dialogs ──────────────────────────────────────────────────────────────

    private void showInAppAlertDialog(MessageModel alert) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() ->
                new AlertDialog.Builder(this)
                        .setTitle("🚨 New Emergency Alert!")
                        .setMessage(alert.getMessage())
                        .setCancelable(false)
                        .setPositiveButton("Navigate Now", (d, w) -> {
                            d.dismiss();
                            openMapsNavigation(alert.getLat(), alert.getLon());
                        })
                        .setNegativeButton("Dismiss", (d, w) -> d.dismiss())
                        .show());
    }

    private void showResponderDialog(String name, MessageModel alert) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() ->
                new AlertDialog.Builder(this)
                        .setTitle("✅ Volunteer Responding")
                        .setMessage(name + " is on their way!\n\n" + alert.getMessage())
                        .setPositiveButton("OK", (d, w) -> d.dismiss())
                        .show());
    }

    // ─── Heads-up notification ────────────────────────────────────────────────

    private void showHeadsUpNotification(String title, String body, String lat, String lon) {
        NotificationManager mgr =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent tapIntent;
        if (lat != null && !lat.isEmpty()) {
            tapIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("google.navigation:q=" + lat + "," + lon));
            tapIntent.setPackage("com.google.android.apps.maps");
        } else {
            tapIntent = new Intent(this, VolunteerHomeActivity.class);
        }
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, (int) System.currentTimeMillis(), tapIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder notif =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_MAX)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setColor(android.graphics.Color.RED)
                        .setColorized(true)
                        .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        .setVibrate(new long[]{0, 200, 100, 200, 100, 200,
                                300, 500, 100, 500, 100, 500,
                                300, 200, 100, 200, 100, 200})
                        .setFullScreenIntent(pi, true)
                        .setContentIntent(pi);
        mgr.notify((int) System.currentTimeMillis() % 10000, notif.build());
    }

    // ─── Earnings ─────────────────────────────────────────────────────────────

    private void loadEarnings() {
        if (currentVolName == null || currentVolName.isEmpty()
                || currentVolName.equals("Volunteer")) return;
        firebaseFirestore.collection("contributions")
                .whereEqualTo("volunteerName", currentVolName)
                .get()
                .addOnSuccessListener(snapshot -> {
                    double totalEarned = 0;
                    ArrayList<String> rows = new ArrayList<>();
                    for (com.google.firebase.firestore.DocumentSnapshot doc
                            : snapshot.getDocuments()) {
                        Double raw = doc.getDouble("amount");
                        double contribution = raw != null ? raw : 0;
                        double earned       = contribution * 0.90;
                        totalEarned        += earned;
                        String savedUser = doc.getString("userName");
                        if (savedUser == null || savedUser.isEmpty())
                            savedUser = doc.getString("senderName");
                        if (savedUser == null || savedUser.isEmpty()) savedUser = "Unknown User";
                        String alertId = doc.getString("alertId");
                        if (alertId == null) alertId = "";
                        String payStatus = doc.getString("payoutStatus");
                        String badge = "paid".equalsIgnoreCase(payStatus) ? "✅" : "⏳";
                        String row = badge + " Rescued: " + savedUser
                                + "\n   Contribution: ₹"
                                + String.format(Locale.getDefault(), "%.0f", contribution)
                                + "  →  You earned: ₹"
                                + String.format(Locale.getDefault(), "%.0f", earned)
                                + (alertId.isEmpty() ? "" : "\n   Alert ID: " + alertId);
                        rows.add(row);
                    }
                    totalEarnedText.setText("💰 Total Earned: ₹"
                            + String.format(Locale.getDefault(), "%.0f", totalEarned));
                    earningsListHeader.setText(rows.isEmpty()
                            ? "No earnings yet — start helping people!"
                            : "📋 Earnings Breakdown (" + rows.size() + " rescues):");
                    android.widget.ArrayAdapter<String> ea =
                            new android.widget.ArrayAdapter<String>(
                                    this, android.R.layout.simple_list_item_1, rows) {
                                @Override
                                public android.view.View getView(int pos,
                                                                 android.view.View convertView,
                                                                 android.view.ViewGroup parent) {
                                    android.view.View v = super.getView(pos, convertView, parent);
                                    android.widget.TextView tv = v.findViewById(android.R.id.text1);
                                    tv.setTextColor(0xFFF1F5F9);
                                    tv.setTextSize(13f);
                                    tv.setPadding(16, 14, 16, 14);
                                    tv.setBackgroundColor(0xFF1E293B);
                                    return v;
                                }
                            };
                    earningsListView.setAdapter(ea);
                })
                .addOnFailureListener(e ->
                        totalEarnedText.setText("💰 Could not load earnings"));
    }

    // ─── Maps ─────────────────────────────────────────────────────────────────

    private void openMapsNavigation(String lat, String lon) {
        if (lat == null || lat.isEmpty()) {
            showLoader.PresentToast("Location not available.");
            return;
        }
        Uri mapUri = Uri.parse("google.navigation:q=" + lat + "," + lon);
        Intent i = new Intent(Intent.ACTION_VIEW, mapUri);
        i.setPackage("com.google.android.apps.maps");
        if (i.resolveActivity(getPackageManager()) != null) startActivity(i);
        else startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("http://maps.google.com/maps?q=" + lat + "," + lon)));
    }

    // ─── Distance helper (shared by volunteer & reassignment notifications) ───

    /**
     * Builds a human-readable distance string from point A (volunteer / new
     * assignee starting location) to point B (SOS user's location).
     * Returns an empty string if either coordinate is unavailable.
     */
    static String buildDistanceLabel(String fromLat, String fromLon,
                                     String toLat,   String toLon) {
        if (fromLat == null || fromLon == null || toLat == null || toLon == null) return "";
        if (fromLat.isEmpty() || fromLon.isEmpty() || toLat.isEmpty() || toLon.isEmpty()) return "";
        try {
            float[] result = new float[1];
            android.location.Location.distanceBetween(
                    Double.parseDouble(fromLat), Double.parseDouble(fromLon),
                    Double.parseDouble(toLat),   Double.parseDouble(toLon),
                    result);
            float distM = result[0];
            if (distM < 1000f)
                return String.format(Locale.getDefault(),
                        "\n📏 Currently ~%.0f m from you.", distM);
            else
                return String.format(Locale.getDefault(),
                        "\n📏 Currently ~%.1f km from you.", distM / 1000f);
        } catch (Exception e) {
            return "";
        }
    }
}