package com.rescueconnect;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.rescueconnect.Loader.ShowLoader;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AdminDashboardActivity — extended with Blood Donors (🩸) tab
 * and "I Am Going" admin-responder feature on the Alerts tab.
 *
 * Alerts tab enhancements:
 *   • Shows who is currently going (responders list)
 *   • Shows who helped (resolvedBy) with resolved status
 *   • Admin can tap "🚀 I Am Going" → user gets a push notification
 *     with admin's live location, and Firestore responders list updates
 *   • Admin dashboard list auto-refreshes to reflect the latest status
 */
public class AdminDashboardActivity extends AppCompatActivity {

    private static final String TAG = "AdminDashboard";

    // ── Views ─────────────────────────────────────────────────────────────────
    FirebaseFirestore db;
    ShowLoader        showLoader;

    ListView adminListView;
    TextView sectionTitle, countText;
    Button   tabUsers, tabVolunteers, tabResponses, tabContributions, tabFundRequests, tabBloodDonors;

    // Parallel lists used by existing tabs
    List<String> currentDocIds      = new ArrayList<>();
    List<String> volunteerNames     = new ArrayList<>();
    List<Double> payoutAmounts      = new ArrayList<>();
    List<String> payoutStatuses     = new ArrayList<>();

    // Parallel lists for Fund Requests tab
    List<String> fundRequestPhones   = new ArrayList<>();
    List<String> fundRequestBillUrls = new ArrayList<>();
    List<String> fundRequestStatuses = new ArrayList<>();

    // ── NEW: Parallel lists for Alerts tab ───────────────────────────────────
    List<String>       alertSenderMobiles  = new ArrayList<>();
    List<String>       alertLats           = new ArrayList<>();
    List<String>       alertLons           = new ArrayList<>();
    List<List<String>> alertRespondersList = new ArrayList<>();
    List<Boolean>      alertResolvedList   = new ArrayList<>();
    List<String>       alertResolvedByList = new ArrayList<>();

    // ── NEW: Parallel lists for Blood Donors tab ──────────────────────────────
    List<String>  donorNames       = new ArrayList<>();
    List<String>  donorBloodGroups = new ArrayList<>();
    List<Boolean> donorAvailable   = new ArrayList<>();

    // ── Parallel lists for photo + aadhaar (Users & Volunteers tabs) ─────────
    List<String> currentSelfieUrls = new ArrayList<>();
    List<String> currentAadhaars   = new ArrayList<>();

    String currentTab = "users";

    // ── NEW: Admin identity + location ────────────────────────────────────────
    String adminName = "Admin";
    FusedLocationProviderClient fusedLocationClient;

    // ── UPI payout result launcher (existing feature) ─────────────────────────
    private String  pendingPayDocId  = "";
    private double  pendingPayAmount = 0;

    private final ActivityResultLauncher<Intent> upiLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Intent data = result.getData();
                        if (data == null) {
                            Toast.makeText(this, "Payment cancelled or app closed.",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String status = data.getStringExtra("Status");
                        if (status == null) status = "";

                        if (status.equalsIgnoreCase("SUCCESS")
                                || status.equalsIgnoreCase("SUBMITTED")) {
                            if (!pendingPayDocId.isEmpty()) {
                                db.collection("contributions").document(pendingPayDocId)
                                        .update("payoutStatus", "paid")
                                        .addOnSuccessListener(v -> {
                                            Toast.makeText(this,
                                                    "Payout of ₹"
                                                            + String.format(Locale.getDefault(),
                                                            "%.0f", pendingPayAmount)
                                                            + " marked as done ✅",
                                                    Toast.LENGTH_LONG).show();
                                            loadContributions();
                                        });
                            }
                        } else if (status.equalsIgnoreCase("FAILURE")) {
                            Toast.makeText(this, "UPI payment failed. Try again.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            confirmManualPayout(pendingPayDocId, pendingPayAmount);
                        }
                    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_admin_dashboard);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        db         = FirebaseFirestore.getInstance();
        showLoader = new ShowLoader(this);

        // ── NEW: init location client + read admin name from SharedPreferences ──
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        String spName = SPHelper.GetData(this, Constants.SP_NAME);
        if (spName != null && !spName.trim().isEmpty()) adminName = spName.trim();

        adminListView    = findViewById(R.id.adminListView);
        sectionTitle     = findViewById(R.id.sectionTitle);
        countText        = findViewById(R.id.countText);
        tabUsers         = findViewById(R.id.tabUsers);
        tabVolunteers    = findViewById(R.id.tabVolunteers);
        tabResponses     = findViewById(R.id.tabResponses);
        tabContributions = findViewById(R.id.tabContributions);
        tabFundRequests  = findViewById(R.id.tabFundRequests);
        tabBloodDonors   = findViewById(R.id.tabBloodDonors);

        // Tab click listeners
        tabUsers.setOnClickListener(v         -> { currentTab = "users";         loadUsers(); });
        tabVolunteers.setOnClickListener(v    -> { currentTab = "volunteers";    loadVolunteers(); });
        tabResponses.setOnClickListener(v     -> { currentTab = "alerts";        loadAlerts(); });
        tabContributions.setOnClickListener(v -> { currentTab = "contributions"; loadContributions(); });
        tabFundRequests.setOnClickListener(v  -> { currentTab = "fundRequests";  loadFundRequests(); });
        tabBloodDonors.setOnClickListener(v   -> { currentTab = "bloodDonors";   loadBloodDonors(); });

        // Tap on a row
        adminListView.setOnItemClickListener((parent, view, position, id) -> {
            if (currentTab.equals("users") || currentTab.equals("volunteers")) {
                showPersonDetailDialog(position);
            } else if (currentTab.equals("contributions")) {
                if (position < volunteerNames.size() && position < payoutAmounts.size()) {
                    if (position < payoutStatuses.size()
                            && payoutStatuses.get(position).equalsIgnoreCase("paid")) {
                        Toast.makeText(this,
                                "✅ Already paid to " + volunteerNames.get(position),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        showPayVolunteerDialog(
                                currentDocIds.get(position),
                                volunteerNames.get(position),
                                payoutAmounts.get(position)
                        );
                    }
                }
            } else if (currentTab.equals("fundRequests")) {
                showFundRequestActionDialog(position);
            } else if (currentTab.equals("bloodDonors")) {
                showBloodDonorActionDialog(position);
            } else if (currentTab.equals("alerts")) {
                // ── NEW: Admin can tap an alert to respond ──
                showAlertActionDialog(position);
            }
        });

        // Long-press to delete
        adminListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (currentTab.equals("alerts")
                    || currentTab.equals("contributions")
                    || currentTab.equals("fundRequests")
                    || currentTab.equals("bloodDonors"))
                confirmDelete(position);
            return true;
        });

        loadUsers();

        // ── Handle launch from cant_go push notification ────────────────────
        // PushyReceiver puts "openTab=alerts" + "alertId" + "autoReassign=true"
        // when admin taps the "👥 Reassign Volunteer" button on the notification.
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    /**
     * Called on fresh launch AND on re-delivery via FLAG_ACTIVITY_SINGLE_TOP.
     * If the notification included "openTab=alerts" + "autoReassign=true", we
     * switch to the Alerts tab and, once it has loaded, pop the reassign dialog
     * for the specific alertId that triggered the notification.
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        String openTab      = intent.getStringExtra("openTab");
        String deepAlertId  = intent.getStringExtra("alertId");
        boolean autoReassign = intent.getBooleanExtra("autoReassign", false);

        if ("alerts".equals(openTab)) {
            currentTab = "alerts";
            // Load alerts; after load, if autoReassign requested, find and open dialog
            if (autoReassign && deepAlertId != null && !deepAlertId.isEmpty()) {
                loadAlertsAndReassign(deepAlertId);
            } else {
                loadAlerts();
            }
        }
    }

    /**
     * Loads the alerts tab and then automatically opens the reassign dialog for
     * the given alertId — so the admin can act immediately from the notification.
     */
    private void loadAlertsAndReassign(String targetAlertId) {
        sectionTitle.setText("📡 All SOS Alerts");
        showLoader.showProgressDialog();
        clearAlertLists();

        db.collection("messages").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    int targetPosition = -1;

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        int pos = currentDocIds.size();   // position before adding
                        boolean isTarget = doc.getId().equals(targetAlertId);
                        if (isTarget) targetPosition = pos;

                        populateAlertRow(doc, rows);
                    }

                    if (rows.isEmpty()) rows.add("No SOS alerts yet.");
                    countText.setText("Total alerts: " + snapshot.size());
                    setListAdapter(rows);

                    // Auto-open reassign dialog for the targeted alert
                    if (targetPosition >= 0) {
                        final int fp = targetPosition;
                        adminListView.post(() -> showAlertActionDialog(fp));
                    }
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    /** Clears all parallel lists used by the Alerts tab. */
    private void clearAlertLists() {
        currentDocIds.clear();
        alertSenderMobiles.clear();
        alertLats.clear();
        alertLons.clear();
        alertRespondersList.clear();
        alertResolvedList.clear();
        alertResolvedByList.clear();
    }

    /**
     * Reads one Firestore document, populates ALL parallel alert lists, and
     * appends the display string to rows.  Extracted so both loadAlerts() and
     * loadAlertsAndReassign() can reuse the same logic.
     */
    private void populateAlertRow(DocumentSnapshot doc, List<String> rows) {
        currentDocIds.add(doc.getId());

        String user = firstNonNull(doc,
                "userName", "name", "user", "senderName", "fullName", "userId");
        String msg  = firstNonNull(doc,
                "message", "alert", "description", "text",
                "content", "sosMessage", "body");
        String time = firstNonNull(doc,
                "timestamp", "time", "sentAt", "createdAt", "date", "submittedAt");
        String loc  = firstNonNullOpt(doc,
                "location", "address", "area", "district");

        String senderMobile = firstNonNullOpt(doc, "senderMobile", "mobile");
        String lat          = firstNonNullOpt(doc, "lat", "latitude");
        String lon          = firstNonNullOpt(doc, "lon", "longitude");

        List<String> responders = new ArrayList<>();
        Object rawResponders = doc.get("responders");
        if (rawResponders instanceof List) {
            for (Object r : (List<?>) rawResponders) {
                if (r instanceof String) responders.add((String) r);
            }
        }
        Boolean resolved   = doc.getBoolean("resolved");
        String  resolvedBy = firstNonNullOpt(doc, "resolvedBy");

        alertSenderMobiles.add(senderMobile);
        alertLats.add(lat);
        alertLons.add(lon);
        alertRespondersList.add(responders);
        alertResolvedList.add(Boolean.TRUE.equals(resolved));
        alertResolvedByList.add(resolvedBy);

        String displayUser = "N/A".equals(user) ? "Unknown user"   : user;
        String displayMsg  = "N/A".equals(msg)  ? "(no message)"   : msg;
        String displayTime = "N/A".equals(time) ? "(no timestamp)" : time;

        StringBuilder row = new StringBuilder(
                "📡 " + displayUser
                        + "\n💬 " + displayMsg
                        + "\n🕐 " + displayTime);

        if (!loc.isEmpty()) row.append("\n📍 ").append(loc);
        if (!responders.isEmpty())
            row.append("\n🚀 Going: ").append(String.join(", ", responders));

        if (Boolean.TRUE.equals(resolved)) {
            row.append("\n✅ Helped by: ")
                    .append(resolvedBy.isEmpty() ? "a volunteer" : resolvedBy);
        } else {
            row.append("\n🔴 Status: Open — tap to respond");
        }
        rows.add(row.toString());
    }

    // ─── Tab 1: USERS ─────────────────────────────────────────────────────────

    private void loadUsers() {
        sectionTitle.setText("👤 All Registered Users");
        showLoader.showProgressDialog();
        currentDocIds.clear();
        currentSelfieUrls.clear();
        currentAadhaars.clear();

        db.collection("users").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        currentDocIds.add(doc.getId());
                        String name      = firstNonNull(doc, "name", "fullName");
                        String mobile    = firstNonNull(doc, "mobile", "phone", "phoneNumber");
                        String email     = firstNonNull(doc, "email", "emailId");
                        String regDate   = firstNonNullOpt(doc, "registeredOn");
                        String emergency = firstNonNullOpt(doc, "emergencyMessage", "emergency", "sosMessage");
                        String aadhaar   = firstNonNullOpt(doc, "aadhaarNumber", "aadhaar");
                        Boolean aadhaarVerified = doc.getBoolean("aadhaarVerified");
                        String selfieUrl = firstNonNullOpt(doc, "selfieUrl", "photoUrl", "profileImage");

                        currentSelfieUrls.add(selfieUrl);
                        currentAadhaars.add(aadhaar);

                        StringBuilder row = new StringBuilder();
                        row.append("👤 ").append(name);
                        row.append("\n📱 ").append(mobile);
                        row.append("\n✉  ").append(email);
                        if (!regDate.isEmpty())   row.append("\n📅 Joined: ").append(regDate);
                        if (!emergency.isEmpty()) row.append("\n🚨 ").append(emergency);

                        if (aadhaar.length() == 12) {
                            String formatted = aadhaar.substring(0,4) + "-"
                                    + aadhaar.substring(4,8) + "-" + aadhaar.substring(8);
                            String badge = Boolean.TRUE.equals(aadhaarVerified)
                                    ? " ✓ Verified" : " ⚠ Unverified";
                            row.append("\n🪪 ").append(formatted).append(badge);
                        } else if (!aadhaar.isEmpty()) {
                            row.append("\n🪪 Aadhaar: ").append(aadhaar).append(" ⚠ Invalid format");
                        }

                        rows.add(row.toString());
                    }

                    if (rows.isEmpty())
                        rows.add("No users yet.\n\nNew users will appear here automatically.");

                    countText.setText("Total users: " + snapshot.size());
                    setPersonListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error loading users: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ─── Tab 2: VOLUNTEERS ────────────────────────────────────────────────────

    private void loadVolunteers() {
        sectionTitle.setText("🦺 All Registered Volunteers");
        showLoader.showProgressDialog();
        currentDocIds.clear();
        volunteerNames.clear();
        payoutAmounts.clear();
        payoutStatuses.clear();
        currentSelfieUrls.clear();
        currentAadhaars.clear();

        db.collection("volunteers").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        currentDocIds.add(doc.getId());
                        String name    = firstNonNull(doc, "name", "fullName", "volunteerName", "displayName");
                        String mobile  = firstNonNullOpt(doc, "mobile", "phone", "phoneNumber", "contactNumber", "mobileNumber");
                        String email   = firstNonNullOpt(doc, "email", "emailId");
                        String skill   = firstNonNullOpt(doc, "skill", "skills", "expertise", "specialization", "category");
                        String area    = firstNonNullOpt(doc, "area", "location", "city", "district", "address", "region");
                        String regDate = firstNonNullOpt(doc, "registeredOn");
                        String status  = firstNonNullOpt(doc, "status", "availability");
                        Boolean availBool = doc.getBoolean("available");
                        if (status.isEmpty() && availBool != null)
                            status = availBool ? "Available" : "Unavailable";

                        String aadhaar = firstNonNullOpt(doc, "aadhaarNumber", "aadhaar");
                        Boolean aadhaarVerified = doc.getBoolean("aadhaarVerified");
                        String selfieUrl = firstNonNullOpt(doc, "selfieUrl", "photoUrl", "profileImage");

                        volunteerNames.add(name);
                        currentSelfieUrls.add(selfieUrl);
                        currentAadhaars.add(aadhaar);

                        StringBuilder row = new StringBuilder("🦺 " + name);
                        if (!mobile.isEmpty())  row.append("\n📱 ").append(mobile);
                        if (!email.isEmpty())   row.append("\n✉  ").append(email);
                        if (!skill.isEmpty())   row.append("\n🔧 ").append(skill);
                        if (!area.isEmpty())    row.append("\n📍 ").append(area);
                        if (!regDate.isEmpty()) row.append("\n📅 Joined: ").append(regDate);

                        if (!status.isEmpty()) {
                            String badge;
                            if (status.equalsIgnoreCase("approved"))       badge = "✓ Approved";
                            else if (status.equalsIgnoreCase("rejected"))   badge = "✗ Rejected";
                            else if (status.equalsIgnoreCase("available")
                                    || status.equalsIgnoreCase("unavailable")) badge = status;
                            else badge = "⏳ " + status;
                            row.append("\n📋 Status: ").append(badge);
                        }

                        if (aadhaar.length() == 12) {
                            String formatted = aadhaar.substring(0,4) + "-"
                                    + aadhaar.substring(4,8) + "-" + aadhaar.substring(8);
                            String badge = Boolean.TRUE.equals(aadhaarVerified)
                                    ? " ✓ Verified" : " ⚠ Unverified";
                            row.append("\n🪪 ").append(formatted).append(badge);
                        } else if (!aadhaar.isEmpty()) {
                            row.append("\n🪪 Aadhaar: ").append(aadhaar).append(" ⚠ Invalid format");
                        }

                        rows.add(row.toString());
                    }

                    if (rows.isEmpty()) rows.add("No volunteers registered yet.");
                    countText.setText("Total volunteers: " + snapshot.size());
                    setPersonListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ─── Tab 3: ALERTS  (enhanced with responders + I Am Going) ──────────────

    private void loadAlerts() {
        sectionTitle.setText("📡 All SOS Alerts");
        showLoader.showProgressDialog();
        clearAlertLists();

        db.collection("messages").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        populateAlertRow(doc, rows);
                    }
                    if (rows.isEmpty()) rows.add("No SOS alerts yet.");
                    countText.setText("Total alerts: " + snapshot.size());
                    setListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ─── NEW: Alert action dialog ─────────────────────────────────────────────

    /**
     * Shows a dialog when admin taps an alert row.
     * Options:
     *   • 🚀 I Am Going        — marks admin as a responder, sends push to SOS user
     *   • 👥 Reassign Volunteer — picks a volunteer from the registered list and
     *                             pushes them a targeted emergency notification;
     *                             also notifies the SOS user with the new name.
     *   • 📍 Open Location      — opens Google Maps at user's SOS location
     *   • 🔄 Refresh            — refreshes the alerts list
     */
    private void showAlertActionDialog(int position) {
        if (position >= currentDocIds.size()) return;

        String       docId       = currentDocIds.get(position);
        String       senderMob   = alertSenderMobiles.get(position);
        String       lat         = alertLats.get(position);
        String       lon         = alertLons.get(position);
        List<String> responders  = alertRespondersList.get(position);
        boolean      resolved    = alertResolvedList.get(position);
        String       resolvedBy  = alertResolvedByList.get(position);

        boolean adminAlreadyGoing = responders.contains(adminName);
        boolean hasLocation       = !lat.isEmpty() && !lon.isEmpty();

        List<String> options = new ArrayList<>();

        if (!resolved && !adminAlreadyGoing) {
            options.add("🚀 I Am Going");
        }
        // ── NEW: Reassign option — always available for open alerts ──────────
        if (!resolved) {
            options.add("👥 Reassign Volunteer");
        }
        if (hasLocation) {
            options.add("📍 Open SOS Location");
        }
        options.add("🔄 Refresh Alerts");

        // Build status summary for dialog message
        StringBuilder statusMsg = new StringBuilder();
        if (!responders.isEmpty()) {
            statusMsg.append("🚀 Responders: ").append(String.join(", ", responders)).append("\n");
        }
        if (resolved) {
            statusMsg.append("✅ Helped by: ").append(resolvedBy.isEmpty() ? "a volunteer" : resolvedBy);
        } else {
            statusMsg.append("🔴 Alert is still open.");
        }
        if (adminAlreadyGoing && !resolved) {
            statusMsg.append("\n\n✔ You have already marked yourself as going.");
        }

        String[] items = options.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("📡 Alert Actions")
                .setMessage(statusMsg.toString())
                .setItems(items, (d, which) -> {
                    String chosen = options.get(which);
                    if (chosen.startsWith("🚀")) {
                        adminImGoing(position, docId, senderMob, lat, lon);
                    } else if (chosen.startsWith("👥")) {
                        // ── NEW: Reassign flow ─────────────────────────────────
                        showReassignVolunteerDialog(position, docId, senderMob);
                    } else if (chosen.startsWith("📍")) {
                        openSosLocation(lat, lon);
                    } else if (chosen.startsWith("🔄")) {
                        loadAlerts();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── NEW: Reassign volunteer flow ─────────────────────────────────────────

    /**
     * Step 1 — Load all registered volunteers from Firestore and display them
     *          in a selection dialog.  Shows name + skill + area for each.
     *          When the admin picks one, calls performReassignment().
     */
    private void showReassignVolunteerDialog(int alertPosition,
                                             String alertDocId,
                                             String senderMobile) {
        showLoader.showProgressDialog();

        db.collection("volunteers").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();

                    if (snapshot.isEmpty()) {
                        Toast.makeText(this,
                                "No registered volunteers found.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Build display items + keep parallel lists for token / name
                    List<String>       displayItems  = new ArrayList<>();
                    List<String>       volNames      = new ArrayList<>();
                    List<String>       volTokens     = new ArrayList<>();
                    List<String>       volDocIds     = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        String name  = firstNonNull(doc,
                                "name", "fullName", "volunteerName", "displayName");
                        String skill = firstNonNullOpt(doc, "skill", "skills", "expertise");
                        String area  = firstNonNullOpt(doc, "area", "location", "city", "district");
                        String token = firstNonNullOpt(doc, "pushyToken");

                        // Build readable label
                        StringBuilder label = new StringBuilder("🦺 " + name);
                        if (!skill.isEmpty()) label.append(" — ").append(skill);
                        if (!area.isEmpty())  label.append(" (").append(area).append(")");
                        if (token.isEmpty())  label.append(" ⚠ No push token");

                        displayItems.add(label.toString());
                        volNames.add(name);
                        volTokens.add(token);
                        volDocIds.add(doc.getId());
                    }

                    new AlertDialog.Builder(this)
                            .setTitle("👥 Assign a Volunteer")
                            .setItems(displayItems.toArray(new String[0]), (d, which) -> {
                                String chosenName  = volNames.get(which);
                                String chosenToken = volTokens.get(which);
                                confirmReassignment(alertPosition, alertDocId,
                                        senderMobile, chosenName, chosenToken);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this,
                            "Could not load volunteers: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Step 2 — Confirm before committing the reassignment.
     */
    private void confirmReassignment(int alertPosition, String alertDocId,
                                     String senderMobile,
                                     String newVolName, String newVolToken) {
        new AlertDialog.Builder(this)
                .setTitle("👥 Confirm Reassignment")
                .setMessage("Assign " + newVolName + " to this SOS alert?\n\n"
                        + "They will receive a push notification immediately.")
                .setPositiveButton("Yes, Assign", (d, w) ->
                        performReassignment(alertPosition, alertDocId,
                                senderMobile, newVolName, newVolToken))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Step 3 — Write to Firestore, then send three pushes in parallel:
     *   a) New volunteer gets a targeted "emergency"-type push (so I-Am-Going
     *      action buttons appear on their device).
     *   b) SOS user gets a "volunteer_reassigned" push so TrackVolunteerActivity
     *      refreshes and shows the new volunteer's name.
     */
    private void performReassignment(int alertPosition, String alertDocId,
                                     String senderMobile,
                                     String newVolName, String newVolToken) {
        showLoader.showProgressDialog();

        // Fetch current alert to get the message text + location for the volunteer push
        db.collection("messages").document(alertDocId).get()
                .addOnSuccessListener(alertDoc -> {

                    String alertMsg = alertDoc.exists()
                            ? firstNonNull(alertDoc, "message", "body", "sosMessage")
                            : "SOS alert";
                    String alertLat = alertDoc.exists()
                            ? firstNonNullOpt(alertDoc, "lat", "latitude")  : "";
                    String alertLon = alertDoc.exists()
                            ? firstNonNullOpt(alertDoc, "lon", "longitude") : "";
                    String mapsUrl  = (!alertLat.isEmpty() && !alertLon.isEmpty())
                            ? "https://maps.google.com/maps?q=" + alertLat + "," + alertLon
                            : "";

                    // ── Firestore update ──────────────────────────────────────
                    // Add new volunteer to responders; clear the cancelled flag
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("responders",        FieldValue.arrayUnion(newVolName));
                    updates.put("volunteerCancelled", false);
                    updates.put("cancelledVolunteer", FieldValue.delete());
                    updates.put("reassignedVolunteer", newVolName);

                    db.collection("messages").document(alertDocId)
                            .update(updates)
                            .addOnSuccessListener(v -> {
                                showLoader.dismissDialog();
                                Toast.makeText(this,
                                        "✅ " + newVolName + " has been assigned!",
                                        Toast.LENGTH_SHORT).show();

                                // Update in-memory list immediately
                                if (alertPosition < alertRespondersList.size()) {
                                    List<String> updated = new ArrayList<>(
                                            alertRespondersList.get(alertPosition));
                                    if (!updated.contains(newVolName)) updated.add(newVolName);
                                    alertRespondersList.set(alertPosition, updated);
                                }

                                // ── Push a) New volunteer device ─────────────
                                if (!newVolToken.isEmpty()) {
                                    NotificationHelper.sendReassignPushToVolunteer(
                                            newVolToken, alertDocId, alertMsg, mapsUrl);
                                } else {
                                    Log.w(TAG, "New volunteer has no pushyToken; skipping push");
                                    Toast.makeText(this,
                                            "⚠️ Volunteer has no device token — push skipped.",
                                            Toast.LENGTH_SHORT).show();
                                }

                                // ── Push b) SOS user ─────────────────────────
                                if (senderMobile != null && !senderMobile.isEmpty()) {
                                    db.collection("users")
                                            .whereEqualTo("mobile", senderMobile)
                                            .limit(1).get()
                                            .addOnSuccessListener(snap -> {
                                                if (!snap.isEmpty()) {
                                                    String userToken = snap.getDocuments()
                                                            .get(0).getString("pushyToken");
                                                    if (userToken != null && !userToken.isEmpty()) {
                                                        NotificationHelper
                                                                .sendVolunteerReassignedNotificationToUser(
                                                                        userToken,
                                                                        newVolName,
                                                                        alertDocId);
                                                    }
                                                }
                                            });
                                }

                                // Refresh the list
                                loadAlerts();
                            })
                            .addOnFailureListener(e -> {
                                showLoader.dismissDialog();
                                Toast.makeText(this,
                                        "Firestore update failed: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this,
                            "Could not load alert: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    // ─── NEW: Admin "I Am Going" flow ─────────────────────────────────────────

    /**
     * Step 1: Write admin name into the alert's "responders" array in Firestore,
     *         then kick off location fetch → push notification to SOS user.
     */
    private void adminImGoing(int position, String docId,
                              String senderMobile, String alertLat, String alertLon) {
        if (docId == null || docId.isEmpty()) {
            Toast.makeText(this, "Alert ID missing.", Toast.LENGTH_SHORT).show();
            return;
        }

        showLoader.showProgressDialog();
        db.collection("messages")
                .document(docId)
                .update("responders", FieldValue.arrayUnion(adminName))
                .addOnSuccessListener(v -> {
                    showLoader.dismissDialog();

                    Toast.makeText(this,
                            "🦸 Marked as going! Notifying the person in need...",
                            Toast.LENGTH_SHORT).show();

                    // Update in-memory list so the UI reflects the change immediately
                    if (position < alertRespondersList.size()) {
                        List<String> updated = new ArrayList<>(alertRespondersList.get(position));
                        if (!updated.contains(adminName)) updated.add(adminName);
                        alertRespondersList.set(position, updated);
                    }

                    // Step 2: Fetch admin's live location, then notify SOS user
                    fetchLocationThenNotifySosUser(senderMobile, docId);

                    // Refresh the list to show updated responders
                    loadAlerts();
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    /**
     * Step 2: Attempt to get a fresh GPS fix. Falls back to last known location,
     *         then falls back to no-location if permission is missing.
     */
    @SuppressLint("MissingPermission")
    private void fetchLocationThenNotifySosUser(String senderMobile, String alertDocId) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // No location permission — notify without live location
            Log.w(TAG, "Location permission not granted — notifying without map link");
            notifySosUserFromAdmin(senderMobile, null, null);
            return;
        }

        CancellationTokenSource cts = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.getToken())
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        notifySosUserFromAdmin(senderMobile,
                                String.valueOf(location.getLatitude()),
                                String.valueOf(location.getLongitude()));
                    } else {
                        // Fallback to last-known location
                        fusedLocationClient.getLastLocation()
                                .addOnSuccessListener(last -> {
                                    if (last != null) {
                                        notifySosUserFromAdmin(senderMobile,
                                                String.valueOf(last.getLatitude()),
                                                String.valueOf(last.getLongitude()));
                                    } else {
                                        notifySosUserFromAdmin(senderMobile, null, null);
                                    }
                                })
                                .addOnFailureListener(e ->
                                        notifySosUserFromAdmin(senderMobile, null, null));
                    }
                })
                .addOnFailureListener(e -> notifySosUserFromAdmin(senderMobile, null, null));
    }

    /**
     * Step 3: Look up the SOS user's Pushy token from Firestore (users collection,
     *         matched by senderMobile), then fire a friendly push notification
     *         using the same NotificationHelper used by volunteers.
     *
     * Notification message sent to user:
     *   "🦸 [Admin Name] is coming to help you! Help is on the way, stay strong! 💪
     *    📍 Track their live location here: https://..."
     */
    private void notifySosUserFromAdmin(String senderMobile, String adminLat, String adminLon) {
        if (senderMobile == null || senderMobile.isEmpty()) {
            Log.w(TAG, "notifySosUserFromAdmin: senderMobile is empty — cannot notify user");
            return;
        }

        final boolean hasLocation = adminLat != null && adminLon != null;
        final String mapsUrl = hasLocation
                ? "https://www.google.com/maps?q=" + adminLat + "," + adminLon
                : "";

        final String friendlyMessage = hasLocation
                ? "🦸 " + adminName + " is coming to help you! "
                + "Help is on the way, stay strong! 💪\n"
                + "📍 Track their live location here: " + mapsUrl
                : "🦸 " + adminName + " is coming to help you! "
                + "Help is on the way, stay strong! 💪";

        // Find the SOS user's Pushy token via their mobile number
        db.collection("users")
                .whereEqualTo("mobile", senderMobile)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap.isEmpty()) {
                        Log.w(TAG, "No user doc found for mobile: " + senderMobile);
                        return;
                    }
                    String pushyToken = snap.getDocuments().get(0).getString("pushyToken");
                    if (pushyToken == null || pushyToken.isEmpty()) {
                        Log.w(TAG, "User has no pushyToken stored for mobile: " + senderMobile);
                        return;
                    }

                    // Re-use the existing NotificationHelper — same as volunteer flow
                    NotificationHelper.sendVolunteerComingNotification(
                            pushyToken,
                            adminName,
                            "",
                            mapsUrl,
                            friendlyMessage);

                    Log.d(TAG, "✅ Admin 'I Am Going' push sent to SOS user: " + senderMobile);

                    runOnUiThread(() ->
                            Toast.makeText(this,
                                    "✅ The person in need has been notified!",
                                    Toast.LENGTH_SHORT).show());
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Could not fetch SOS user Pushy token: " + e.getMessage()));
    }

    // ─── Open SOS location on Maps ────────────────────────────────────────────

    private void openSosLocation(String lat, String lon) {
        if (lat == null || lat.isEmpty()) {
            Toast.makeText(this, "Location not available.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent mapIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon));
            mapIntent.setPackage("com.google.android.apps.maps");
            if (mapIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(mapIntent);
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("http://maps.google.com/maps?q=" + lat + "," + lon)));
            }
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://maps.google.com/maps?q=" + lat + "," + lon)));
        }
    }

    // ─── Tab 4: CONTRIBUTIONS / PAYMENTS ─────────────────────────────────────

    private void loadContributions() {
        sectionTitle.setText("💳 Volunteer Contributions");
        showLoader.showProgressDialog();
        currentDocIds.clear();
        volunteerNames.clear();
        payoutAmounts.clear();
        payoutStatuses.clear();

        db.collection("contributions").get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        currentDocIds.add(doc.getId());
                        String volName  = firstNonNull(doc, "volunteerName", "name");
                        String alertId  = firstNonNull(doc, "alertId", "responseId");
                        String payStatus= firstNonNull(doc, "payoutStatus");
                        Double raw      = doc.getDouble("amount");
                        double amount   = raw != null ? raw : 0;
                        double payout90 = amount * 0.90;

                        volunteerNames.add(volName);
                        payoutAmounts.add(payout90);
                        payoutStatuses.add(payStatus);

                        String actionHint = payStatus.equalsIgnoreCase("paid")
                                ? "✅ Payout done" : "💸 Pending — tap to pay";
                        rows.add("🙌 " + volName
                                + "\n🔔 Alert: " + alertId
                                + "\n💰 Contribution: ₹" + String.format(Locale.getDefault(), "%.0f", amount)
                                + "\n💸 Payout (90%%): ₹" + String.format(Locale.getDefault(), "%.0f", payout90)
                                + "\n" + actionHint);
                    }

                    if (rows.isEmpty()) rows.add("No contributions recorded yet.");
                    countText.setText("Total contributions: " + snapshot.size());

                    setListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ─── Tab 5: FUND REQUESTS ─────────────────────────────────────────────────

    private void loadFundRequests() {
        sectionTitle.setText("💊 Health Fund Requests");
        showLoader.showProgressDialog();
        currentDocIds.clear();
        fundRequestPhones.clear();
        fundRequestBillUrls.clear();
        fundRequestStatuses.clear();

        db.collection("fundRequests")
                .orderBy("submittedAt")
                .get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        currentDocIds.add(doc.getId());

                        String patient = firstNonNull(doc, "patientName");
                        String disease = firstNonNull(doc, "disease");
                        String phone   = firstNonNull(doc, "phone");
                        String upi     = firstNonNull(doc, "upiId");
                        String billUrl = firstNonNull(doc, "billUrl");
                        String status  = firstNonNull(doc, "status");
                        String dateStr = firstNonNull(doc, "submittedAt");
                        Double total   = doc.getDouble("totalAmount");
                        Double raised  = doc.getDouble("raisedAmount");

                        fundRequestPhones.add(phone);
                        fundRequestBillUrls.add(billUrl);
                        fundRequestStatuses.add(status);

                        String statusEmoji = status.equals("verified") ? "✅" :
                                status.equals("rejected") ? "❌" : "⏳";

                        rows.add(statusEmoji + " " + patient.toUpperCase()
                                + "\n🦠 Disease: " + disease
                                + "\n📞 Phone: " + phone + "  (tap to call)"
                                + "\n💳 UPI: " + upi
                                + "\n💰 Goal: ₹" + (total != null
                                ? String.format(Locale.getDefault(), "%.0f", total) : "0")
                                + "  |  Raised: ₹" + (raised != null
                                ? String.format(Locale.getDefault(), "%.0f", raised) : "0")
                                + "\n📅 " + dateStr
                                + "\n👆 Tap for actions"
                        );
                    }

                    if (rows.isEmpty()) rows.add("No fund requests yet.");
                    countText.setText("Total requests: " + snapshot.size());
                    setListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error loading fund requests: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ─── Tab 6: BLOOD DONORS ─────────────────────────────────────────────────
    // ═══════════════════════════════════════════════════════════════════════════

    private void loadBloodDonors() {
        sectionTitle.setText("🩸 Blood Donor Registrations");
        showLoader.showProgressDialog();
        currentDocIds.clear();
        donorNames.clear();
        donorBloodGroups.clear();
        donorAvailable.clear();

        db.collection("bloodDonors")
                .orderBy("registeredAt")
                .get()
                .addOnSuccessListener(snapshot -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        currentDocIds.add(doc.getId());

                        String  name       = firstNonNull(doc, "name");
                        String  bloodGroup = firstNonNull(doc, "bloodGroup");
                        String  pincode    = firstNonNull(doc, "pincode");
                        String  district   = firstNonNull(doc, "district");
                        Boolean available  = doc.getBoolean("available");
                        boolean avail      = available != null && available;

                        donorNames.add(name);
                        donorBloodGroups.add(bloodGroup);
                        donorAvailable.add(avail);

                        String availTag = avail ? "🟢 Available" : "🔴 Unavailable";

                        rows.add("🩸 " + name
                                + "\n🩹 Blood Group: " + bloodGroup
                                + "\n📍 " + district + " – " + pincode
                                + "\n" + availTag
                                + "\n👆 Tap to toggle availability");
                    }

                    if (rows.isEmpty()) rows.add("No blood donors registered yet.\n\n"
                            + "Donors registered via the Blood Donation screen will appear here.");

                    countText.setText("Total donors: " + snapshot.size());
                    setListAdapter(rows);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    List<String> rows = new ArrayList<>();
                    rows.add("Error loading donors: " + e.getMessage());
                    setListAdapter(rows);
                });
    }

    // ── Blood Donor action dialog ─────────────────────────────────────────────

    private void showBloodDonorActionDialog(int position) {
        if (position >= currentDocIds.size()) return;

        String  docId      = currentDocIds.get(position);
        String  name       = donorNames.get(position);
        String  bg         = donorBloodGroups.get(position);
        boolean isAvailable = donorAvailable.get(position);

        String toggleLabel = isAvailable ? "🔴 Mark as Unavailable" : "🟢 Mark as Available";

        new AlertDialog.Builder(this)
                .setTitle("🩸 Donor: " + name + " (" + bg + ")")
                .setItems(new String[]{toggleLabel, "🗑 Delete Donor"}, (d, which) -> {
                    if (which == 0) {
                        boolean newValue = !isAvailable;
                        db.collection("bloodDonors").document(docId)
                                .update("available", newValue)
                                .addOnSuccessListener(v -> {
                                    Toast.makeText(this,
                                            name + " marked as " + (newValue ? "Available ✅" : "Unavailable 🔴"),
                                            Toast.LENGTH_SHORT).show();
                                    loadBloodDonors();
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(this, "Error: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show());
                    } else {
                        confirmDelete(position);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Fund Request action dialog ────────────────────────────────────────────

    private void showFundRequestActionDialog(int position) {
        if (position >= currentDocIds.size()) return;

        String docId   = currentDocIds.get(position);
        String phone   = fundRequestPhones.get(position);
        String billUrl = fundRequestBillUrls.get(position);
        String status  = fundRequestStatuses.get(position);

        List<String> options = new ArrayList<>();
        options.add("📞 Call Applicant (" + phone + ")");
        if (!billUrl.isEmpty() && !billUrl.equals("N/A")) {
            options.add("🔗 View Hospital Bill");
        }
        if (!status.equals("verified")) {
            options.add("✅ Verify & Publish Campaign");
        }
        if (!status.equals("rejected")) {
            options.add("❌ Reject Request");
        }

        String[] items = options.toArray(new String[0]);

        new AlertDialog.Builder(this)
                .setTitle("Fund Request Actions")
                .setItems(items, (d, which) -> {
                    String chosen = options.get(which);
                    if (chosen.startsWith("📞")) {
                        callPhone(phone);
                    } else if (chosen.startsWith("🔗")) {
                        openUrl(billUrl);
                    } else if (chosen.startsWith("✅")) {
                        confirmVerifyRequest(docId, phone, position);
                    } else if (chosen.startsWith("❌")) {
                        confirmRejectRequest(docId, phone, position);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Call phone ────────────────────────────────────────────────────────────

    private void callPhone(String phone) {
        if (phone == null || phone.equals("N/A") || phone.isEmpty()) {
            Toast.makeText(this, "No phone number available", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent callIntent = new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:" + Uri.encode(phone)));
            startActivity(callIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Cannot make calls from this device", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Open bill URL ─────────────────────────────────────────────────────────

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open document", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Verify request ────────────────────────────────────────────────────────

    private void confirmVerifyRequest(String docId, String phone, int position) {
        new AlertDialog.Builder(this)
                .setTitle("✅ Verify Fund Request?")
                .setMessage("This will publish the campaign in the Donate section.\n\n"
                        + "An SMS confirmation will be sent to the applicant.")
                .setPositiveButton("Yes, Verify", (d, w) -> {
                    showLoader.showProgressDialog();
                    db.collection("fundRequests").document(docId)
                            .update("status", "verified")
                            .addOnSuccessListener(v -> {
                                showLoader.dismissDialog();
                                sendSmsToApplicant(phone, true);
                                if (position < fundRequestStatuses.size())
                                    fundRequestStatuses.set(position, "verified");
                                Toast.makeText(this, "Campaign published! ✅", Toast.LENGTH_LONG).show();
                                loadFundRequests();
                            })
                            .addOnFailureListener(e -> {
                                showLoader.dismissDialog();
                                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRejectRequest(String docId, String phone, int position) {
        new AlertDialog.Builder(this)
                .setTitle("❌ Reject Fund Request?")
                .setMessage("The applicant will be notified via SMS.")
                .setPositiveButton("Reject", (d, w) -> {
                    showLoader.showProgressDialog();
                    db.collection("fundRequests").document(docId)
                            .update("status", "rejected")
                            .addOnSuccessListener(v -> {
                                showLoader.dismissDialog();
                                sendSmsToApplicant(phone, false);
                                if (position < fundRequestStatuses.size())
                                    fundRequestStatuses.set(position, "rejected");
                                Toast.makeText(this, "Request rejected.", Toast.LENGTH_SHORT).show();
                                loadFundRequests();
                            })
                            .addOnFailureListener(e -> {
                                showLoader.dismissDialog();
                                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Send SMS ──────────────────────────────────────────────────────────────

    private void sendSmsToApplicant(String phone, boolean verified) {
        if (phone == null || phone.equals("N/A") || phone.isEmpty()) return;

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS}, 101);
            return;
        }

        String message = verified
                ? "RescueConnect: Your health fund request has been VERIFIED ✅ "
                + "and is now live for donations! Share with your network to raise funds faster. "
                + "Thank you for trusting us."
                : "RescueConnect: Unfortunately your fund request could NOT be verified "
                + "at this time. Please contact us with proper documentation to reapply. "
                + "We're sorry for the inconvenience.";

        try {
            SmsManager smsManager = SmsManager.getDefault();
            if (message.length() > 160) {
                ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null);
            }
            Toast.makeText(this, "SMS sent to " + phone, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ─── Show Pay Volunteer dialog ────────────────────────────────────────────

    private void showPayVolunteerDialog(String docId, String volName, double payAmount) {
        if (volName == null || volName.isEmpty()) {
            showPayDialogWithDetails(docId, "", "", payAmount);
            return;
        }
        showLoader.showProgressDialog();
        db.collection("volunteer")
                .whereEqualTo("name", volName)
                .limit(1)
                .get()
                .addOnSuccessListener(snap -> {
                    showLoader.dismissDialog();
                    String mobile = "";
                    if (!snap.isEmpty()) {
                        DocumentSnapshot vDoc = snap.getDocuments().get(0);
                        mobile = firstNonNull(vDoc, "mobile", "phone", "phoneNumber");
                        if ("N/A".equals(mobile)) mobile = "";
                    }
                    showPayDialogWithDetails(docId, mobile, "", payAmount);
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    showPayDialogWithDetails(docId, "", "", payAmount);
                });
    }

    private void showPayDialogWithDetails(String docId, String mobile,
                                          String ignoredUpi, double payAmount) {
        boolean hasMobile = !mobile.isEmpty();

        String message = hasMobile
                ? "📱 Volunteer's Mobile: " + mobile
                + "\n💸 Amount: ₹" + String.format(Locale.getDefault(), "%.0f", payAmount)
                + "\n\nTap \"Open UPI App\" — your app will open with the amount "
                + "pre-filled. Search for this volunteer by their mobile number ("
                + mobile + ") inside the app to complete payment."
                : "💸 Amount: ₹" + String.format(Locale.getDefault(), "%.0f", payAmount)
                + "\n\nCould not find this volunteer's mobile number.";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("💸 Pay Volunteer (90%)")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Mark Paid Manually", (d, w) ->
                        confirmManualPayout(docId, payAmount));

        if (hasMobile) {
            builder.setPositiveButton("Open UPI App 🚀", (d, w) ->
                    launchUpiForVolunteer(docId, mobile, payAmount));
        }
        builder.show();
    }

    private void launchUpiForVolunteer(String docId, String mobile, double amount) {
        pendingPayDocId  = docId;
        pendingPayAmount = amount;

        Uri upiUri = new Uri.Builder()
                .scheme("upi").authority("pay")
                .appendQueryParameter("am", String.format(Locale.US, "%.2f", amount))
                .appendQueryParameter("cu", "INR")
                .appendQueryParameter("tn", "RescueConnect volunteer payout - " + mobile)
                .build();

        Intent upiIntent = new Intent(Intent.ACTION_VIEW, upiUri);
        Intent chooser   = Intent.createChooser(upiIntent, "Pay " + mobile + " via UPI");
        try {
            upiLauncher.launch(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No UPI app found.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmManualPayout(String docId, double amount) {
        new AlertDialog.Builder(this)
                .setTitle("Mark Payout Done?")
                .setMessage("Confirm you have manually sent ₹"
                        + String.format(Locale.getDefault(), "%.0f", amount) + ".")
                .setPositiveButton("Yes, Done", (d, w) ->
                        db.collection("contributions").document(docId)
                                .update("payoutStatus", "paid")
                                .addOnSuccessListener(v -> {
                                    Toast.makeText(this, "Payout marked ✅", Toast.LENGTH_SHORT).show();
                                    loadContributions();
                                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Delete ───────────────────────────────────────────────────────────────

    private void confirmDelete(int position) {
        if (position >= currentDocIds.size()) return;
        String collection;
        if      (currentTab.equals("alerts"))       collection = "messages";
        else if (currentTab.equals("fundRequests")) collection = "fundRequests";
        else if (currentTab.equals("bloodDonors"))  collection = "bloodDonors";
        else                                        collection = "contributions";

        String docId = currentDocIds.get(position);

        new AlertDialog.Builder(this)
                .setTitle("Delete record?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) ->
                        db.collection(collection).document(docId).delete()
                                .addOnSuccessListener(v -> {
                                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                                    if      (currentTab.equals("alerts"))       loadAlerts();
                                    else if (currentTab.equals("fundRequests")) loadFundRequests();
                                    else if (currentTab.equals("bloodDonors"))  loadBloodDonors();
                                    else                                        loadContributions();
                                })
                                .addOnFailureListener(e ->
                                        Toast.makeText(this, "Error: " + e.getMessage(),
                                                Toast.LENGTH_SHORT).show()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String firstNonNull(DocumentSnapshot doc, String... fields) {
        for (String f : fields) {
            String val = doc.getString(f);
            if (val != null && !val.isEmpty()) return val;
            com.google.firebase.Timestamp ts = doc.getTimestamp(f);
            if (ts != null) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                        "dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
                return sdf.format(ts.toDate());
            }
        }
        return "N/A";
    }

    private String firstNonNullOpt(DocumentSnapshot doc, String... fields) {
        String val = firstNonNull(doc, fields);
        return "N/A".equals(val) ? "" : val;
    }

    // ── Custom adapter: shows selfie thumbnail + details ─────────────────────

    private void setPersonListAdapter(List<String> rows) {
        adminListView.setAdapter(new ArrayAdapter<String>(this, 0, rows) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                if (convertView == null)
                    convertView = getLayoutInflater().inflate(
                            R.layout.item_admin_person, parent, false);

                String full   = rows.get(pos);
                int    nl     = full.indexOf('\n');
                String name   = (nl > 0 ? full.substring(0, nl) : full).trim();
                String detail = (nl > 0 ? full.substring(nl + 1) : "").trim();

                ((TextView) convertView.findViewById(R.id.personName)).setText(name);
                ((TextView) convertView.findViewById(R.id.personDetails)).setText(detail);

                ImageView photo = convertView.findViewById(R.id.personPhoto);
                String url = pos < currentSelfieUrls.size() ? currentSelfieUrls.get(pos) : "";
                if (!url.isEmpty()) {
                    Glide.with(AdminDashboardActivity.this)
                            .load(url)
                            .apply(new RequestOptions()
                                    .circleCrop()
                                    .placeholder(R.drawable.ic_person_placeholder)
                                    .error(R.drawable.ic_person_placeholder))
                            .into(photo);
                } else {
                    photo.setImageResource(R.drawable.ic_person_placeholder);
                }
                return convertView;
            }
        });
    }

    // ── Full-screen detail dialog: large photo + full Aadhaar ─────────────────

    private void showPersonDetailDialog(int position) {
        if (position >= currentDocIds.size()) return;

        String selfieUrl = (position < currentSelfieUrls.size()) ? currentSelfieUrls.get(position) : "";
        String aadhaar   = (position < currentAadhaars.size())   ? currentAadhaars.get(position)   : "";

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 8);

        ImageView photo = new ImageView(this);
        android.widget.LinearLayout.LayoutParams photoParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 480);
        photoParams.bottomMargin = 24;
        photo.setLayoutParams(photoParams);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photo.setBackgroundColor(0xFF333333);

        if (!selfieUrl.isEmpty()) {
            Glide.with(this)
                    .load(selfieUrl)
                    .apply(new RequestOptions()
                            .placeholder(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery))
                    .into(photo);
        } else {
            photo.setImageResource(android.R.drawable.ic_menu_gallery);
        }
        layout.addView(photo);

        TextView aadhaarText = new TextView(this);
        aadhaarText.setTextSize(16f);
        aadhaarText.setTextColor(0xFF1A1A1A);
        if (aadhaar.length() == 12) {
            String formatted = aadhaar.substring(0,4) + "-"
                    + aadhaar.substring(4,8) + "-" + aadhaar.substring(8);
            aadhaarText.setText("🪪 Aadhaar: " + formatted);
        } else if (!aadhaar.isEmpty()) {
            aadhaarText.setText("🪪 Aadhaar: " + aadhaar + " ⚠ Invalid format");
        } else {
            aadhaarText.setText("🪪 Aadhaar: Not provided");
        }
        layout.addView(aadhaarText);

        new AlertDialog.Builder(this)
                .setTitle(currentTab.equals("users") ? "👤 User Details" : "🦺 Volunteer Details")
                .setView(layout)
                .setPositiveButton("Close", null)
                .show();
    }

    private void setListAdapter(List<String> rows) {
        adminListView.setAdapter(new ArrayAdapter<String>(this, 0, rows) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                if (convertView == null)
                    convertView = getLayoutInflater().inflate(
                            R.layout.item_admin_card, parent, false);

                String   full  = rows.get(pos);
                String[] lines = full.split("\n");

                // First line = card title
                ((TextView) convertView.findViewById(R.id.cardTitle))
                        .setText(lines.length > 0 ? lines[0].trim() : "");

                // Last line: check if it is an action / status hint
                String last = lines[lines.length - 1].trim();
                boolean isStatus = last.startsWith("\uD83D\uDC46")
                        || last.startsWith("\u2705 Payout")
                        || last.startsWith("\uD83D\uDCB8 Pending")
                        || last.startsWith("\uD83D\uDD34 Status")
                        || last.startsWith("\u2705 Helped");

                // Middle lines = body details
                int endIdx = isStatus ? lines.length - 1 : lines.length;
                StringBuilder mid = new StringBuilder();
                for (int i = 1; i < endIdx; i++) {
                    String ln = lines[i].trim();
                    if (ln.isEmpty()) continue;
                    if (mid.length() > 0) mid.append("\n");
                    mid.append(ln);
                }
                ((TextView) convertView.findViewById(R.id.cardDetails))
                        .setText(mid.toString());

                // Status chip
                TextView statusView = convertView.findViewById(R.id.cardStatus);
                if (isStatus) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(last);
                    if (last.startsWith("\u2705")) {
                        statusView.setTextColor(0xFF00E676);
                    } else if (last.startsWith("\uD83D\uDCB8")) {
                        statusView.setTextColor(0xFFFF9800);
                    } else if (last.startsWith("\uD83D\uDD34")) {
                        statusView.setTextColor(0xFFFF4D4D);
                    } else {
                        statusView.setTextColor(0xFF64748B);
                    }
                } else {
                    statusView.setVisibility(View.GONE);
                }
                return convertView;
            }
        });
    }
}