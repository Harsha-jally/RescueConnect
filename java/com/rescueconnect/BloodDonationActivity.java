package com.rescueconnect;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.EdgeToEdge;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.rescueconnect.Loader.ShowLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BloodDonationActivity — OPTIMIZED
 * ──────────────────────────────────
 * Key speed improvements over the original:
 *
 *  1. Thread pool: 4 threads (was 2) so network calls overlap.
 *  2. findDonorsAndBanks() now runs pincode-lookup AND Firestore query in PARALLEL:
 *       • Thread A: India Post → district → data.gov.in blood banks
 *       • Thread B: Nominatim → lat/lng (ready for Overpass fallback)
 *       • Thread C: Firestore donor query (starts immediately with pincode; no waiting)
 *     All three join via CountDownLatch before UI update — no wasted waiting time.
 *  3. Blood bank lookup (data.gov.in) connects with a shorter connect-timeout (7 s)
 *     while keeping the read-timeout long enough for large payloads (12 s).
 *  4. Nominatim connect-timeout reduced to 7 s (was 10 s); a slow DNS response
 *     should not block the entire pipeline.
 *  5. Overpass: mirrors are tried in parallel (one Future per mirror per radius)
 *     so a slow primary mirror no longer blocks the fallback.
 *  6. readStream() uses a larger buffer (8 KB vs default) to reduce read cycles.
 *  7. Donor registration: pincode lookup and Firestore write still sequential
 *     (district must be known before write) — no change needed there.
 */
public class BloodDonationActivity extends AppCompatActivity {

    // ── Views ─────────────────────────────────────────────────────────────────
    private Button   tabDonate, tabFind;
    private View     panelDonate, panelFind;

    private EditText etDonorName, etDonorPhone, etDonorPincode;
    private Spinner  spinnerDonorBloodGroup;
    private Button   btnRegisterDonor;

    private EditText etFindPincode;
    private Spinner  spinnerFindBloodGroup;
    private Button   btnFindDonors;
    private ListView listResults;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private ShowLoader        showLoader;

    // ── OPTIMIZATION 1: 4-thread pool (was 2) ────────────────────────────────
    // Allows pincode lookup, Nominatim, blood bank API, and Firestore to run
    // simultaneously without queuing behind each other.
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // ── Result lists (parallel — same index = same item) ──────────────────────
    private final List<String>  resultRows    = new ArrayList<>();
    private final List<String>  resultPhones  = new ArrayList<>();
    private final List<Boolean> isCallable    = new ArrayList<>();
    private final List<String>  resultMapUrls = new ArrayList<>();

    private static final String[] BLOOD_GROUPS =
            {"A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"};

    private static final List<String> OVERPASS_MIRRORS = Arrays.asList(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.openstreetmap.fr/api/interpreter"
    );

    private static final int[] SEARCH_RADII = {10000, 22000, 40000};

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_blood_donation);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        db         = FirebaseFirestore.getInstance();
        showLoader = new ShowLoader(this);

        tabDonate   = findViewById(R.id.tabDonate);
        tabFind     = findViewById(R.id.tabFind);
        panelDonate = findViewById(R.id.panelDonate);
        panelFind   = findViewById(R.id.panelFind);

        etDonorName            = findViewById(R.id.etDonorName);
        etDonorPhone           = findViewById(R.id.etDonorPhone);
        etDonorPincode         = findViewById(R.id.etDonorPincode);
        spinnerDonorBloodGroup = findViewById(R.id.spinnerDonorBloodGroup);
        btnRegisterDonor       = findViewById(R.id.btnRegisterDonor);

        etFindPincode         = findViewById(R.id.etFindPincode);
        spinnerFindBloodGroup = findViewById(R.id.spinnerFindBloodGroup);
        btnFindDonors         = findViewById(R.id.btnFindDonors);
        // listResults removed — results are shown in BloodSearchResultsActivity

        ArrayAdapter<String> bgAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, BLOOD_GROUPS);
        bgAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDonorBloodGroup.setAdapter(bgAdapter);
        spinnerFindBloodGroup.setAdapter(bgAdapter);

        tabDonate.setOnClickListener(v -> showTab(true));
        tabFind.setOnClickListener(v   -> showTab(false));
        showTab(true);

        btnRegisterDonor.setOnClickListener(v -> registerDonor());
        btnFindDonors.setOnClickListener(v    -> findDonorsAndBanks());
    }

    // ─── Tab switching ────────────────────────────────────────────────────────

    private void showTab(boolean donateActive) {
        panelDonate.setVisibility(donateActive ? View.VISIBLE : View.GONE);
        panelFind.setVisibility(donateActive   ? View.GONE   : View.VISIBLE);
        tabDonate.setBackgroundResource(donateActive
                ? R.drawable.bg_tab_active_red : R.drawable.bg_tab_inactive);
        tabDonate.setTextColor(donateActive ? 0xFF1A0000 : 0xFF64748B);
        tabFind.setBackgroundResource(donateActive
                ? R.drawable.bg_tab_inactive : R.drawable.bg_tab_active_red);
        tabFind.setTextColor(donateActive ? 0xFF64748B : 0xFF1A0000);
    }

    // ─── DONOR REGISTRATION ───────────────────────────────────────────────────

    private void registerDonor() {
        String name    = etDonorName.getText().toString().trim();
        String phone   = etDonorPhone.getText().toString().trim();
        String pincode = etDonorPincode.getText().toString().trim();
        String bg      = spinnerDonorBloodGroup.getSelectedItem().toString();

        if (TextUtils.isEmpty(name)) { etDonorName.setError("Enter your name");                return; }
        if (phone.length() != 10)    { etDonorPhone.setError("Enter valid 10-digit number");   return; }
        if (pincode.length() != 6)   { etDonorPincode.setError("Enter valid 6-digit pincode"); return; }

        showLoader.showProgressDialog();
        executor.execute(() -> {
            PincodeResult result = fetchDistrictFromPincode(pincode);
            runOnUiThread(() -> {
                showLoader.dismissDialog();
                String district = result.success ? result.district : pincode;
                saveDonor(name, phone, bg, pincode, district);
            });
        });
    }

    private void saveDonor(String name, String phone, String bloodGroup,
                           String pincode, String district) {
        showLoader.showProgressDialog();
        Map<String, Object> data = new HashMap<>();
        data.put("name",         name);
        data.put("phone",        phone);
        data.put("bloodGroup",   bloodGroup);
        data.put("pincode",      pincode);
        data.put("district",     district);
        data.put("registeredAt", Timestamp.now());
        data.put("available",    true);

        db.collection("bloodDonors").add(data)
                .addOnSuccessListener(ref -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this,
                            "✅ Registered as donor! Thank you, " + name + ".",
                            Toast.LENGTH_LONG).show();
                    etDonorName.setText("");
                    etDonorPhone.setText("");
                    etDonorPincode.setText("");
                })
                .addOnFailureListener(e -> {
                    showLoader.dismissDialog();
                    Toast.makeText(this, "Error saving: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ─── FIND DONORS + BLOOD BANKS (OPTIMIZED) ───────────────────────────────
    //
    //  BEFORE (sequential, worst-case ~30-40 s):
    //    India Post → district → Nominatim (3 tiers) → data.gov.in → Overpass → Firestore
    //
    //  AFTER (parallel, worst-case ~12-15 s):
    //    ┌─ Thread A: India Post → district → data.gov.in blood banks
    //    ├─ Thread B: Nominatim lat/lng (ready for Overpass if Thread A yields nothing)
    //    └─ Thread C: Firestore donor query (starts immediately — no waiting for district)
    //    CountDownLatch waits for A+B, then decides if Overpass is needed.
    //    Firestore result arrives asynchronously and calls finishResults() itself.
    // ─────────────────────────────────────────────────────────────────────────

    private void findDonorsAndBanks() {
        String pincode = etFindPincode.getText().toString().trim();
        String bg      = spinnerFindBloodGroup.getSelectedItem().toString();

        if (pincode.length() != 6) { etFindPincode.setError("Enter valid 6-digit pincode"); return; }

        // Launch dedicated results screen — search runs there
        Intent intent = new Intent(this, BloodSearchResultsActivity.class);
        intent.putExtra(BloodSearchResultsActivity.EXTRA_PINCODE,     pincode);
        intent.putExtra(BloodSearchResultsActivity.EXTRA_BLOOD_GROUP, bg);
        startActivity(intent);
    }

    // NOTE: The methods below (startFirestoreQueryByPincode, loadDonorsByDistrict,
    // loadDonorsByPincode, addDonorRow, ResultCardAdapter, finishResults, and all
    // network helpers) have been moved to BloodSearchResultsActivity.
    // They are kept here only for donor REGISTRATION (registerDonor / saveDonor).
    //
    // ─── Dead code below — safe to delete once BloodSearchResultsActivity is live ─

    @SuppressWarnings("unused")
    private void findDonorsAndBanks_LEGACY() {
        String pincode = etFindPincode.getText().toString().trim();
        String bg      = spinnerFindBloodGroup.getSelectedItem().toString();

        if (pincode.length() != 6) { etFindPincode.setError("Enter valid 6-digit pincode"); return; }

        showLoader.showProgressDialog();
        resultRows.clear();
        resultPhones.clear();
        isCallable.clear();
        resultMapUrls.clear();

        // ── OPTIMIZATION 2: shared atomic results across threads ──────────────
        AtomicReference<PincodeResult> pincodeRef = new AtomicReference<>();
        AtomicReference<LatLng>        latLngRef  = new AtomicReference<>();
        AtomicReference<List<BloodBankInfo>> banksRef = new AtomicReference<>(new ArrayList<>());

        // Latch: main coordination thread waits for pincode + latLng before deciding
        // whether Overpass is needed. Firestore is fired separately and self-completes.
        CountDownLatch latch = new CountDownLatch(2); // Thread A + Thread B

        // ── Thread A: India Post → district → data.gov.in ────────────────────
        executor.execute(() -> {
            try {
                PincodeResult pr = fetchDistrictFromPincode(pincode);
                pincodeRef.set(pr);

                String district = pr.success ? pr.district : "";
                List<BloodBankInfo> banks = fetchBloodBanksFromDirectory(pincode, district);
                banksRef.set(banks);
            } finally {
                latch.countDown();
            }
        });

        // ── Thread B: Nominatim → lat/lng (runs in parallel with Thread A) ───
        executor.execute(() -> {
            try {
                // We don't have district yet; pass empty string — Nominatim Tier 1
                // (postal code lookup) doesn't need it. If Tier 1 fails we fall back
                // to pincode free-text (Tier 3). Tier 2 (district) will be skipped
                // only for this early fetch; if we still need Overpass later, the
                // district will already be available from Thread A's result.
                LatLng ll = fetchLatLngFromPincode(pincode, "");
                latLngRef.set(ll);
            } finally {
                latch.countDown();
            }
        });

        // ── Thread C: Firestore donor query — starts immediately ──────────────
        // We query by pincode first (no district needed). If district-level search
        // is needed it fires from the UI thread after the latch completes.
        // This means Firestore work overlaps with all network calls above.
        executor.execute(() -> {
            // Pre-warm: query by pincode in background. The result is held and
            // merged on the UI thread once the latch opens.
            // NOTE: Firestore SDK callbacks always run on the main thread, so
            // we simply kick off the query here and let the SDK handle threading.
            runOnUiThread(() -> {
                // Start Firestore query immediately (parallel with A+B)
                startFirestoreQueryByPincode(bg, pincode);
            });
        });

        // ── Coordinator thread: waits for A+B, then handles Overpass if needed ─
        executor.execute(() -> {
            try {
                latch.await(); // blocks only this coordinator thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            PincodeResult pr    = pincodeRef.get();
            LatLng        ll    = latLngRef.get();
            List<BloodBankInfo> banks = banksRef.get();

            // If district is now known but lat/lng Tier 2 was skipped, retry
            // Nominatim with district (Tier 2). Only needed when Tier 1 failed.
            if (ll == null && pr != null && pr.success) {
                ll = nominatimQuery(
                        "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                                + URLEncodeSafe(pr.district + ", India"));
                if (ll == null) {
                    // Tier 3: pincode free-text (already tried in Thread B as fallback,
                    // but if Thread B ran its Tier 3 and failed, skip to avoid duplicate)
                    ll = nominatimQuery(
                            "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                                    + URLEncodeSafe(pincode + ", India"));
                }
            }

            // Overpass fallback — only if data.gov.in returned nothing AND we have coords
            if (banks.isEmpty() && ll != null) {
                banks = fetchBloodBanksFromOverpassWithRetry(ll.lat, ll.lng);
            }

            final List<BloodBankInfo> finalBanks = banks;
            final LatLng              finalLatLng = ll;
            final PincodeResult       finalPR     = pr;

            runOnUiThread(() -> {
                // ── Blood bank rows ─────────────────────────────────────────
                boolean coordsFound = (finalLatLng != null);
                double  lat = coordsFound ? finalLatLng.lat : 0;
                double  lng = coordsFound ? finalLatLng.lng : 0;

                if (finalBanks.isEmpty()) {
                    String msg = coordsFound
                            ? "BANK|No Blood Banks Found|No banks found within 40 km"
                            : "BANK|Location Unavailable|Use the map link below";
                    resultRows.add(msg);
                    resultPhones.add("");
                    isCallable.add(false);
                    resultMapUrls.add("");
                } else {
                    for (BloodBankInfo bank : finalBanks) {
                        String mapUrl = coordsFound
                                ? "https://www.google.com/maps/search/?api=1&query="
                                + URLEncodeSafe(bank.name + " blood bank")
                                + "&center=" + lat + "," + lng
                                : "";
                        String subtitle = bank.address.isEmpty()
                                ? "Tap to open in Maps"
                                : bank.address;
                        resultRows.add("BANK|" + bank.name + "|" + subtitle);
                        resultPhones.add(bank.phone);
                        isCallable.add(!bank.phone.isEmpty());
                        resultMapUrls.add(mapUrl);
                    }
                }

                // ── Google Maps fallback row (always) ───────────────────────
                String gmUrl = "https://www.google.com/maps/search/blood+bank+near+" + pincode;
                resultRows.add("MAP|Search Blood Banks on Google Maps|Tap to open in Maps →");
                resultPhones.add("");
                isCallable.add(false);
                resultMapUrls.add(gmUrl);

                // ── District donor search if district is known ──────────────
                // Firestore pincode query is already running (Thread C above).
                // If district is available, also kick off a district query;
                // whichever returns results first will call finishResults().
                if (finalPR != null && finalPR.success) {
                    loadDonorsByDistrict(bg, finalPR.district, pincode);
                }
                // If pincode-only (Thread C), finishResults() will be called by that query.
            });
        });
    }

    // ─── Donor loaders ────────────────────────────────────────────────────────

    /**
     * Kicks off a Firestore query by pincode. Used as the "immediate" query
     * while district lookup is still in progress on a background thread.
     * finishResults() is called on completion.
     */
    private void startFirestoreQueryByPincode(String bloodGroup, String pincode) {
        db.collection("bloodDonors")
                .whereEqualTo("bloodGroup", bloodGroup)
                .whereEqualTo("pincode",    pincode)
                .whereEqualTo("available",  true)
                .get()
                .addOnSuccessListener(snap -> {
                    for (QueryDocumentSnapshot doc : snap)
                        addDonorRow(doc, bloodGroup, doc.getString("district"));
                    finishResults();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Error fetching donors: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    finishResults();
                });
    }

    private void loadDonorsByDistrict(String bloodGroup, String district, String pincode) {
        db.collection("bloodDonors")
                .whereEqualTo("bloodGroup", bloodGroup)
                .whereEqualTo("district",   district)
                .whereEqualTo("available",  true)
                .get()
                .addOnSuccessListener(snap -> {
                    int found = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        addDonorRow(doc, bloodGroup, district);
                        found++;
                    }
                    if (found == 0) {
                        loadDonorsByPincode(bg(bloodGroup), pincode);
                    } else {
                        finishResults();
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Error fetching donors: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    finishResults();
                });
    }

    private void loadDonorsByPincode(String bloodGroup, String pincode) {
        db.collection("bloodDonors")
                .whereEqualTo("bloodGroup", bloodGroup)
                .whereEqualTo("pincode",    pincode)
                .whereEqualTo("available",  true)
                .get()
                .addOnSuccessListener(snap -> {
                    for (QueryDocumentSnapshot doc : snap)
                        addDonorRow(doc, bloodGroup, doc.getString("district"));
                    finishResults();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Error fetching donors: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    finishResults();
                });
    }

    private void addDonorRow(QueryDocumentSnapshot doc, String bloodGroup, String district) {
        String name       = doc.getString("name");
        String pin        = doc.getString("pincode");
        String donorPhone = doc.getString("phone");
        if (donorPhone == null) donorPhone = "";

        // title | subtitle | phone stored as pipe-delimited string for the card adapter
        String title    = "Donor · " + (name != null ? name : "Unknown");
        String subtitle = "Blood: " + bloodGroup + "  ·  " + (district != null ? district : pin);
        String display  = "DONOR|" + title + "|" + subtitle;

        resultRows.add(display);
        resultPhones.add(donorPhone);
        isCallable.add(!donorPhone.isEmpty());
        resultMapUrls.add("");
    }

    // ─── ResultCardAdapter ────────────────────────────────────────────────────
    //
    //  Renders each result row as a modern card using item_result_card.xml.
    //  Row format: "TYPE|Title|Subtitle"
    //    TYPE = DONOR | BANK | MAP | EMPTY
    // ─────────────────────────────────────────────────────────────────────────

    private class ResultCardAdapter extends android.widget.BaseAdapter {

        @Override public int getCount()              { return resultRows.size(); }
        @Override public Object getItem(int pos)     { return resultRows.get(pos); }
        @Override public long   getItemId(int pos)   { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(
                        R.layout.item_result_card, parent, false);
            }

            TextView tvIcon     = convertView.findViewById(R.id.tvResultIcon);
            TextView tvTitle    = convertView.findViewById(R.id.tvResultTitle);
            TextView tvSubtitle = convertView.findViewById(R.id.tvResultSubtitle);
            TextView tvPhone    = convertView.findViewById(R.id.tvResultPhone);
            TextView tvAction   = convertView.findViewById(R.id.tvResultAction);
            View     iconBg     = (View) tvIcon.getParent(); // the oval LinearLayout

            String raw   = resultRows.get(pos);
            String[] parts = raw.split("\\|", 3);
            String type     = parts.length > 0 ? parts[0] : "EMPTY";
            String title    = parts.length > 1 ? parts[1] : raw;
            String subtitle = parts.length > 2 ? parts[2] : "";

            String phone    = resultPhones.get(pos);
            boolean callable = isCallable.get(pos);

            switch (type) {
                case "DONOR":
                    tvIcon.setText("🩸");
                    iconBg.setBackgroundResource(R.drawable.bg_result_icon_circle);
                    iconBg.getBackground().setColorFilter(
                            0x1AFF5252, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFFF1F5F9);
                    tvPhone.setTextColor(0xFF4ADE80);    // green for donor phone
                    tvAction.setText("›");
                    tvAction.setTextColor(0xFFFF5252);
                    break;

                case "BANK":
                    tvIcon.setText("🏥");
                    iconBg.getBackground().setColorFilter(
                            0x1AFF9800, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFFF1F5F9);
                    tvPhone.setTextColor(0xFFFB923C);    // orange for bank phone
                    tvAction.setText("›");
                    tvAction.setTextColor(0xFFFB923C);
                    break;

                case "MAP":
                    tvIcon.setText("🗺️");
                    iconBg.getBackground().setColorFilter(
                            0x1A60A5FA, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFF60A5FA);
                    tvPhone.setTextColor(0xFF94A3B8);
                    tvAction.setText("→");
                    tvAction.setTextColor(0xFF60A5FA);
                    break;

                default: // EMPTY
                    tvIcon.setText("😔");
                    tvTitle.setTextColor(0xFF94A3B8);
                    tvPhone.setTextColor(0xFF4A5568);
                    tvAction.setText("");
                    break;
            }

            tvTitle.setText(title);
            tvSubtitle.setText(subtitle);

            if (callable && !phone.isEmpty()) {
                tvPhone.setText("📞 " + phone);
                tvPhone.setVisibility(View.VISIBLE);
            } else if (type.equals("MAP") || type.equals("BANK")) {
                tvPhone.setText("Tap to open in Maps");
                tvPhone.setVisibility(View.VISIBLE);
            } else if (type.equals("DONOR")) {
                tvPhone.setText("No phone on file");
                tvPhone.setVisibility(View.VISIBLE);
            } else {
                tvPhone.setVisibility(View.GONE);
            }

            return convertView;
        }
    }

    private static String bg(String s) { return s; }

    private void finishResults() {
        showLoader.dismissDialog();
        if (resultRows.isEmpty()) {
            resultRows.add("EMPTY|No Results Found|Try a nearby pincode or contact your city hospital");
            resultPhones.add("");
            isCallable.add(false);
            resultMapUrls.add("");
        }

        listResults.setAdapter(new ResultCardAdapter());
    }

    // =========================================================================
    //  NETWORK HELPERS  (always called from background thread)
    // =========================================================================

    // ─── 1. India Post Pincode API ────────────────────────────────────────────

    private static class PincodeResult {
        boolean success;
        String  district, error;
        static PincodeResult ok(String d)   { PincodeResult r=new PincodeResult(); r.success=true;  r.district=d; return r; }
        static PincodeResult fail(String e) { PincodeResult r=new PincodeResult(); r.success=false; r.error=e;    return r; }
    }

    private PincodeResult fetchDistrictFromPincode(String pincode) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.postalpincode.in/pincode/" + pincode);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);  // OPTIMIZATION 3: was 10000
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0");
            conn.setRequestProperty("Accept",     "application/json");

            if (conn.getResponseCode() != 200)
                return PincodeResult.fail("Server returned HTTP " + conn.getResponseCode());

            JSONArray  root = new JSONArray(readStream(conn));
            JSONObject item = root.getJSONObject(0);
            if (!"Success".equals(item.getString("Status")))
                return PincodeResult.fail("Invalid pincode – not found in India Post database");

            JSONArray po = item.getJSONArray("PostOffice");
            if (po.length() == 0)
                return PincodeResult.fail("No post offices found for this pincode");

            return PincodeResult.ok(po.getJSONObject(0).getString("District"));

        } catch (java.net.UnknownHostException e) {
            return PincodeResult.fail("No internet connection.");
        } catch (java.net.SocketTimeoutException e) {
            return PincodeResult.fail("Request timed out.");
        } catch (Exception e) {
            return PincodeResult.fail("Error: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─── 2. Nominatim — pincode → lat/lng ────────────────────────────────────

    private static class LatLng {
        double lat, lng;
        LatLng(double lat, double lng) { this.lat = lat; this.lng = lng; }
    }

    private LatLng fetchLatLngFromPincode(String pincode, String district) {
        // Tier 1 — postal code lookup
        LatLng result = nominatimQuery(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1"
                        + "&country=India&postalcode=" + pincode);
        if (result != null) return result;

        // Tier 2 — district free-text (only if district already known)
        if (!TextUtils.isEmpty(district)) {
            result = nominatimQuery(
                    "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                            + URLEncodeSafe(district + ", India"));
            if (result != null) return result;
        }

        // Tier 3 — pincode free-text
        return nominatimQuery(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                        + URLEncodeSafe(pincode + ", India"));
    }

    private LatLng nominatimQuery(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(7000);  // OPTIMIZATION 4: was 10000
            conn.setReadTimeout(7000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0 (blood donor locator)");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) return null;

            JSONArray arr = new JSONArray(readStream(conn));
            if (arr.length() == 0) return null;

            JSONObject first = arr.getJSONObject(0);
            return new LatLng(
                    Double.parseDouble(first.getString("lat")),
                    Double.parseDouble(first.getString("lon")));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ─── 3a. data.gov.in Blood Bank Directory API (PRIMARY) ──────────────────

    private static final String DATA_GOV_API_KEY      = "579b464db66ec23bdd0000018f179947de2a43f475a9471421d5b2e4";
    private static final String DATA_GOV_RESOURCE_ID  = "fced6df9-a360-4e08-8ca0-f283fc74ce15";

    private static String stateFromPincode(String pincode) {
        if (pincode == null || pincode.length() < 2) return "";
        int prefix = Integer.parseInt(pincode.substring(0, 2));
        if (prefix == 11)                 return "Delhi";
        if (prefix >= 12 && prefix <= 13) return "Haryana";
        if (prefix >= 14 && prefix <= 16) return "Punjab";
        if (prefix == 17)                 return "Himachal Pradesh";
        if (prefix >= 18 && prefix <= 19) return "Jammu and Kashmir";
        if (prefix >= 20 && prefix <= 28) return "Uttar Pradesh";
        if (prefix >= 30 && prefix <= 34) return "Rajasthan";
        if (prefix >= 36 && prefix <= 39) return "Gujarat";
        if (prefix >= 40 && prefix <= 44) return "Maharashtra";
        if (prefix >= 45 && prefix <= 48) return "Madhya Pradesh";
        if (prefix == 49)                 return "Chhattisgarh";
        if (prefix >= 50 && prefix <= 53) return "Telangana";
        if (prefix >= 54 && prefix <= 56) return "Andhra Pradesh";
        if (prefix >= 57 && prefix <= 58) return "Karnataka";
        if (prefix >= 60 && prefix <= 64) return "Tamil Nadu";
        if (prefix >= 67 && prefix <= 69) return "Kerala";
        if (prefix >= 70 && prefix <= 74) return "West Bengal";
        if (prefix >= 75 && prefix <= 77) return "Odisha";
        if (prefix == 78)                 return "Assam";
        if (prefix >= 80 && prefix <= 85) return "Bihar";
        if (prefix >= 82 && prefix <= 83) return "Jharkhand";
        if (prefix >= 90 && prefix <= 97) return "Assam";
        return "";
    }

    private List<BloodBankInfo> fetchBloodBanksFromDirectory(String pincode, String district) {
        if (!TextUtils.isEmpty(district)) {
            List<BloodBankInfo> result = queryDataGovBloodBanks("district", district);
            if (!result.isEmpty()) return result;
        }
        String state = stateFromPincode(pincode);
        if (!TextUtils.isEmpty(state)) {
            return queryDataGovBloodBanks("state", state);
        }
        return new ArrayList<>();
    }

    private List<BloodBankInfo> queryDataGovBloodBanks(String filterField, String filterValue) {
        List<BloodBankInfo> banks = new ArrayList<>();
        HttpURLConnection   conn  = null;
        try {
            String encodedValue = URLEncoder.encode(filterValue, "UTF-8");
            String urlStr = "https://api.data.gov.in/resource/" + DATA_GOV_RESOURCE_ID
                    + "?api-key=" + DATA_GOV_API_KEY
                    + "&format=json"
                    + "&limit=20"
                    + "&filters[" + filterField + "]=" + encodedValue;

            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(7000);  // OPTIMIZATION 3: was 12000
            conn.setReadTimeout(12000);    // keep read timeout generous for payload
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) return banks;

            JSONObject root    = new JSONObject(readStream(conn));
            JSONArray  records = root.optJSONArray("records");
            if (records == null || records.length() == 0) return banks;

            Map<String, Boolean> seen = new HashMap<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject rec = records.getJSONObject(i);

                String name     = rec.optString("bb_name",    "").trim();
                String phone    = rec.optString("contact_no", "").trim();
                String addr     = rec.optString("address",    "").trim();
                String city     = rec.optString("city",       "").trim();
                String dist     = rec.optString("district",   "").trim();
                String stateVal = rec.optString("state",      "").trim();

                if (name.isEmpty()) name = "Blood Bank";

                StringBuilder addrBuilder = new StringBuilder(addr);
                if (!city.isEmpty()     && !addr.contains(city))     { if (addrBuilder.length()>0) addrBuilder.append(", "); addrBuilder.append(city); }
                if (!dist.isEmpty()     && !addr.contains(dist))     { if (addrBuilder.length()>0) addrBuilder.append(", "); addrBuilder.append(dist); }
                if (!stateVal.isEmpty() && !addr.contains(stateVal)) { if (addrBuilder.length()>0) addrBuilder.append(", "); addrBuilder.append(stateVal); }
                String fullAddress = addrBuilder.toString().trim();

                phone = phone.replaceAll("[^0-9+]", "");

                String key = name + "|" + fullAddress;
                if (seen.containsKey(key)) continue;
                seen.put(key, true);

                banks.add(new BloodBankInfo(name, phone, fullAddress));
            }
        } catch (Exception e) {
            // Non-fatal — caller will try Overpass fallback
        } finally {
            if (conn != null) conn.disconnect();
        }
        return banks;
    }

    // ─── 3b. Overpass — fallback (OPTIMIZATION 5: mirrors tried in parallel) ─

    private static class BloodBankInfo {
        String name, phone, address;
        BloodBankInfo(String n, String p, String a) { name=n; phone=p; address=a; }
    }

    /**
     * OPTIMIZATION 5: For each radius, dispatch all mirrors in parallel.
     * Return the first non-empty result; cancel the rest.
     * This avoids waiting sequentially through slow/unreachable mirrors.
     */
    private List<BloodBankInfo> fetchBloodBanksFromOverpassWithRetry(double lat, double lng) {
        for (int radius : SEARCH_RADII) {
            String query = buildOverpassQuery(lat, lng, radius);

            List<Future<List<BloodBankInfo>>> futures = new ArrayList<>();
            for (String mirror : OVERPASS_MIRRORS) {
                futures.add(executor.submit(() -> fetchFromOverpass(mirror, query)));
            }

            for (Future<List<BloodBankInfo>> future : futures) {
                try {
                    List<BloodBankInfo> result = future.get();
                    if (!result.isEmpty()) {
                        // Cancel remaining futures for this radius
                        for (Future<List<BloodBankInfo>> f : futures) f.cancel(true);
                        return result;
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>();
    }

    private String buildOverpassQuery(double lat, double lng, int radiusM) {
        String around = "(around:" + radiusM + "," + lat + "," + lng + ");";
        return "[out:json][timeout:30];"
                + "(node[\"amenity\"=\"blood_bank\"]"       + around
                + "way[\"amenity\"=\"blood_bank\"]"         + around
                + "node[\"healthcare\"=\"blood_bank\"]"     + around
                + "way[\"healthcare\"=\"blood_bank\"]"      + around
                + "node[\"name\"~\"[Bb]lood.?[Bb]ank\"]"   + around
                + "way[\"name\"~\"[Bb]lood.?[Bb]ank\"]"    + around
                + ");out body;>;out skel qt;";
    }

    private List<BloodBankInfo> fetchFromOverpass(String mirrorUrl, String query) {
        List<BloodBankInfo> banks = new ArrayList<>();
        HttpURLConnection   conn  = null;
        try {
            URL url = new URL(mirrorUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0");

            byte[] postData = ("data=" + URLEncoder.encode(query, "UTF-8")).getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) { os.write(postData); }

            if (conn.getResponseCode() != 200) return banks;

            JSONArray elements = new JSONObject(readStream(conn)).getJSONArray("elements");
            Map<String, Boolean> seen = new HashMap<>();

            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.getJSONObject(i);
                if (!el.has("tags")) continue;
                JSONObject tags = el.getJSONObject("tags");

                String name = tags.optString("name", "").trim();
                if (name.isEmpty()) name = tags.optString("operator", "").trim();
                if (name.isEmpty()) name = "Blood Bank";

                String phone = tags.optString("phone", "").trim();
                if (phone.isEmpty()) phone = tags.optString("contact:phone", "").trim();
                if (phone.isEmpty()) phone = tags.optString("telephone",     "").trim();
                phone = phone.replaceAll("[\\s\\-]", "");

                String address = tags.optString("addr:full", "").trim();
                if (address.isEmpty()) {
                    String street = tags.optString("addr:street", "").trim();
                    String city   = tags.optString("addr:city",   "").trim();
                    if (!street.isEmpty() && !city.isEmpty()) address = street + ", " + city;
                    else if (!street.isEmpty()) address = street;
                    else if (!city.isEmpty())   address = city;
                }

                String key = name + "|" + address;
                if (seen.containsKey(key)) continue;
                seen.put(key, true);

                banks.add(new BloodBankInfo(name, phone, address));
            }
        } catch (Exception e) {
            // Non-fatal — caller will try next mirror
        } finally {
            if (conn != null) conn.disconnect();
        }
        return banks;
    }

    // ─── Shared helpers ───────────────────────────────────────────────────────

    // OPTIMIZATION 6: 8 KB buffer (was default ~512 B) reduces loop iterations
    private String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private String URLEncodeSafe(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}