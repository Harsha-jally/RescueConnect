package com.rescueconnect;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.EdgeToEdge;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BloodSearchResultsActivity
 * ──────────────────────────
 * Full-screen results page launched from BloodDonationActivity.
 *
 * FIX SUMMARY (vs previous broken version):
 *
 *  BUG 1 — Race condition: Thread C (Firestore pincode query) was fired
 *  immediately and called finishResults() before the coordinator thread
 *  had added any blood bank rows. The adapter was set with an empty list.
 *
 *  BUG 2 — Double finishResults(): when district was known, both the
 *  pincode Firestore query AND loadDonorsByDistrict called finishResults(),
 *  causing the adapter to be replaced — sometimes with fewer results.
 *
 *  FIX — Single linear pipeline:
 *    1. Background thread: pincode → district (India Post)
 *    2. Background threads in parallel: lat/lng (Nominatim) + blood banks (data.gov.in)
 *    3. Overpass fallback if banks list is empty
 *    4. UI thread: blood bank rows added to list
 *    5. UI thread: Firestore donors fetched (district → pincode fallback)
 *    6. UI thread: finishResults() called ONCE via AtomicBoolean guard
 */
public class BloodSearchResultsActivity extends AppCompatActivity {

    public static final String EXTRA_PINCODE     = "pincode";
    public static final String EXTRA_BLOOD_GROUP = "bloodGroup";

    // ── Views ─────────────────────────────────────────────────────────────────
    private ListView listResults;
    private TextView tvSearchSummary;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseFirestore db;
    private ShowLoader        showLoader;

    // ── Thread pool ───────────────────────────────────────────────────────────
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    // ── Result lists ─────────────────────────────────────────────────────────
    private final List<String>  resultRows    = new ArrayList<>();
    private final List<String>  resultPhones  = new ArrayList<>();
    private final List<Boolean> isCallable    = new ArrayList<>();
    private final List<String>  resultMapUrls = new ArrayList<>();

    // Prevents finishResults() from firing more than once
    private final AtomicBoolean finished = new AtomicBoolean(false);

    // ── Search params ─────────────────────────────────────────────────────────
    private String pincode;
    private String bloodGroup;

    private static final List<String> OVERPASS_MIRRORS = Arrays.asList(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.openstreetmap.fr/api/interpreter"
    );
    private static final int[]  SEARCH_RADII         = {10000, 22000, 40000};
    private static final String DATA_GOV_API_KEY      = "579b464db66ec23bdd0000018f179947de2a43f475a9471421d5b2e4";
    private static final String DATA_GOV_RESOURCE_ID  = "fced6df9-a360-4e08-8ca0-f283fc74ce15";

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_blood_search_results);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        db         = FirebaseFirestore.getInstance();
        showLoader = new ShowLoader(this);

        listResults     = findViewById(R.id.listResults);
        tvSearchSummary = findViewById(R.id.tvSearchSummary);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        pincode    = getIntent().getStringExtra(EXTRA_PINCODE);
        bloodGroup = getIntent().getStringExtra(EXTRA_BLOOD_GROUP);

        if (TextUtils.isEmpty(pincode) || TextUtils.isEmpty(bloodGroup)) {
            Toast.makeText(this, "Invalid search parameters.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        tvSearchSummary.setText(bloodGroup + " · " + pincode);

        listResults.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= resultRows.size()) return;
            String  phone    = resultPhones.get(position);
            String  mapUrl   = resultMapUrls.get(position);
            boolean callable = isCallable.get(position);
            String  row      = resultRows.get(position);
            boolean isBank   = row.startsWith("BANK");
            boolean isMapRow = row.startsWith("MAP");

            if (isMapRow && !TextUtils.isEmpty(mapUrl)) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)));
                return;
            }
            if (callable && !TextUtils.isEmpty(phone)) {
                new AlertDialog.Builder(this)
                        .setTitle(isBank ? "📞 Call Blood Bank" : "📞 Call Donor")
                        .setMessage("Call " + phone + "?")
                        .setPositiveButton("Call", (d, w) ->
                                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone))))
                        .setNegativeButton("Cancel", null)
                        .show();
            } else if (isBank && !TextUtils.isEmpty(mapUrl)) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)));
            } else {
                Toast.makeText(this, "No contact available.", Toast.LENGTH_SHORT).show();
            }
        });

        runSearch();
    }

    // =========================================================================
    //  SEARCH PIPELINE  (fixed — single linear flow, no race conditions)
    //
    //  All network calls run on background threads.
    //  Blood bank rows are added to resultRows on the UI thread BEFORE
    //  the Firestore donor query is even started.
    //  finishResults() is called exactly once, after donors are loaded.
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void runSearch() {
        showLoader.showProgressDialog();
        resultRows.clear();
        resultPhones.clear();
        isCallable.clear();
        resultMapUrls.clear();
        finished.set(false);

        executor.execute(() -> {

            // Step 1: pincode → district
            PincodeResult pr       = fetchDistrictFromPincode(pincode);
            String        district = (pr != null && pr.success) ? pr.district : "";

            // Step 2+3: lat/lng and blood banks in parallel
            Future<LatLng>             latLngFuture = executor.submit(() -> fetchLatLngFromPincode(pincode, district));
            Future<List<BloodBankInfo>> banksFuture = executor.submit(() -> fetchBloodBanksFromDirectory(pincode, district));

            LatLng             ll    = null;
            List<BloodBankInfo> banks = new ArrayList<>();
            try { ll    = latLngFuture.get(); } catch (Exception ignored) {}
            try { banks = banksFuture.get();  } catch (Exception ignored) {}

            // Step 3b: Overpass fallback
            if (banks.isEmpty() && ll != null)
                banks = fetchBloodBanksFromOverpassWithRetry(ll.lat, ll.lng);

            final LatLng              finalLL       = ll;
            final List<BloodBankInfo> finalBanks    = banks;
            final String              finalDistrict = district;
            final boolean             districtFound = (pr != null && pr.success);

            // Step 4: back on UI thread — add bank rows THEN start Firestore
            runOnUiThread(() -> {

                boolean coordsFound = (finalLL != null);
                double  lat = coordsFound ? finalLL.lat : 0;
                double  lng = coordsFound ? finalLL.lng : 0;

                // ── Blood bank rows ───────────────────────────────────────────
                for (BloodBankInfo bank : finalBanks) {
                    String mapUrl = "https://www.google.com/maps/search/?api=1&query="
                            + URLEncodeSafe(bank.name + " blood bank near " + pincode)
                            + (coordsFound ? "&center=" + lat + "," + lng : "");
                    resultRows.add("BANK|" + bank.name + "|"
                            + (bank.address.isEmpty() ? "Tap to open in Maps" : bank.address));
                    resultPhones.add(bank.phone);
                    isCallable.add(!bank.phone.isEmpty());
                    resultMapUrls.add(mapUrl);
                }

                // ── Google Maps fallback row (always shown) ───────────────────
                resultRows.add("MAP|Search Blood Banks on Google Maps|Tap to open Maps →");
                resultPhones.add(""); isCallable.add(false);
                resultMapUrls.add("https://www.google.com/maps/search/blood+bank+near+" + pincode);

                // ── Step 5: Firestore donors (district → pincode fallback) ─────
                // finishResults() is called inside fetchDonors* after this completes
                if (districtFound && !TextUtils.isEmpty(finalDistrict)) {
                    fetchDonorsByDistrict(finalDistrict);
                } else {
                    fetchDonorsByPincode();
                }
            });
        });
    }

    // =========================================================================
    //  DONOR FETCHERS
    // =========================================================================

    private void fetchDonorsByDistrict(String district) {
        db.collection("bloodDonors")
                .whereEqualTo("bloodGroup", bloodGroup)
                .whereEqualTo("district",   district)
                .whereEqualTo("available",  true)
                .get()
                .addOnSuccessListener(snap -> {
                    int count = 0;
                    for (QueryDocumentSnapshot doc : snap) {
                        addDonorRow(doc, district);
                        count++;
                    }
                    if (count == 0) {
                        // No donors in district — try exact pincode
                        fetchDonorsByPincode();
                    } else {
                        finishResults();
                    }
                })
                .addOnFailureListener(e -> fetchDonorsByPincode()); // try pincode anyway
    }

    private void fetchDonorsByPincode() {
        db.collection("bloodDonors")
                .whereEqualTo("bloodGroup", bloodGroup)
                .whereEqualTo("pincode",    pincode)
                .whereEqualTo("available",  true)
                .get()
                .addOnSuccessListener(snap -> {
                    for (QueryDocumentSnapshot doc : snap)
                        addDonorRow(doc, doc.getString("district"));
                    finishResults();
                })
                .addOnFailureListener(e -> finishResults()); // blood banks still shown
    }

    private void addDonorRow(QueryDocumentSnapshot doc, String district) {
        String name     = doc.getString("name");
        String phone    = doc.getString("phone");
        String docPin   = doc.getString("pincode");
        if (phone == null) phone = "";
        String location = !TextUtils.isEmpty(district) ? district
                : (!TextUtils.isEmpty(docPin)  ? docPin : "");
        resultRows.add("DONOR|Donor · " + (name != null ? name : "Unknown")
                + "|" + bloodGroup + (location.isEmpty() ? "" : "  ·  " + location));
        resultPhones.add(phone);
        isCallable.add(!phone.isEmpty());
        resultMapUrls.add("");
    }

    // =========================================================================
    //  FINISH — called exactly once (AtomicBoolean guard)
    // =========================================================================

    private void finishResults() {
        if (!finished.compareAndSet(false, true)) return;

        showLoader.dismissDialog();

        // If the only row is the Maps fallback, prepend an empty-state card
        boolean hasRealResults = false;
        for (String row : resultRows) {
            if (row.startsWith("DONOR") || row.startsWith("BANK")) {
                hasRealResults = true;
                break;
            }
        }
        if (!hasRealResults) {
            resultRows.add(0, "EMPTY|No Donors or Banks Found|Try a nearby pincode or contact your city hospital");
            resultPhones.add(0, ""); isCallable.add(0, false); resultMapUrls.add(0, "");
        }

        listResults.setAdapter(new ResultCardAdapter());
    }

    // =========================================================================
    //  CARD ADAPTER
    // =========================================================================

    private class ResultCardAdapter extends android.widget.BaseAdapter {
        @Override public int    getCount()         { return resultRows.size(); }
        @Override public Object getItem(int pos)   { return resultRows.get(pos); }
        @Override public long   getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            if (convertView == null)
                convertView = getLayoutInflater()
                        .inflate(R.layout.item_result_card, parent, false);

            TextView tvIcon     = convertView.findViewById(R.id.tvResultIcon);
            TextView tvTitle    = convertView.findViewById(R.id.tvResultTitle);
            TextView tvSubtitle = convertView.findViewById(R.id.tvResultSubtitle);
            TextView tvPhone    = convertView.findViewById(R.id.tvResultPhone);
            TextView tvAction   = convertView.findViewById(R.id.tvResultAction);
            View     iconBg     = (View) tvIcon.getParent();

            String[] parts    = resultRows.get(pos).split("\\|", 3);
            String   type     = parts.length > 0 ? parts[0] : "EMPTY";
            String   title    = parts.length > 1 ? parts[1] : resultRows.get(pos);
            String   subtitle = parts.length > 2 ? parts[2] : "";
            String   phone    = resultPhones.get(pos);
            boolean  callable = isCallable.get(pos);

            // Always reset background resource on recycled views
            iconBg.setBackgroundResource(R.drawable.bg_result_icon_circle);

            switch (type) {
                case "DONOR":
                    tvIcon.setText("🩸");
                    iconBg.getBackground().setColorFilter(0x1AFF5252, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFFF1F5F9);
                    tvPhone.setTextColor(0xFF4ADE80);
                    tvAction.setText("›"); tvAction.setTextColor(0xFFFF5252);
                    break;
                case "BANK":
                    tvIcon.setText("🏥");
                    iconBg.getBackground().setColorFilter(0x1AFF9800, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFFF1F5F9);
                    tvPhone.setTextColor(0xFFFB923C);
                    tvAction.setText("›"); tvAction.setTextColor(0xFFFB923C);
                    break;
                case "MAP":
                    tvIcon.setText("🗺️");
                    iconBg.getBackground().setColorFilter(0x1A60A5FA, android.graphics.PorterDuff.Mode.SRC_IN);
                    tvTitle.setTextColor(0xFF60A5FA);
                    tvPhone.setTextColor(0xFF94A3B8);
                    tvAction.setText("→"); tvAction.setTextColor(0xFF60A5FA);
                    break;
                default: // EMPTY
                    tvIcon.setText("😔");
                    iconBg.getBackground().setColorFilter(0x1A94A3B8, android.graphics.PorterDuff.Mode.SRC_IN);
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
            } else if (type.equals("MAP")) {
                tvPhone.setText("Tap to open Google Maps");
                tvPhone.setVisibility(View.VISIBLE);
            } else if (type.equals("BANK")) {
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

    // =========================================================================
    //  NETWORK HELPERS
    // =========================================================================

    // ── India Post pincode → district ─────────────────────────────────────────

    private static class PincodeResult {
        boolean success; String district;
        static PincodeResult ok(String d) { PincodeResult r=new PincodeResult(); r.success=true; r.district=d; return r; }
        static PincodeResult fail()       { return new PincodeResult(); }
    }

    private PincodeResult fetchDistrictFromPincode(String pin) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://api.postalpincode.in/pincode/" + pin).openConnection();
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return PincodeResult.fail();
            JSONArray  root = new JSONArray(readStream(conn));
            JSONObject item = root.getJSONObject(0);
            if (!"Success".equals(item.getString("Status"))) return PincodeResult.fail();
            JSONArray po = item.getJSONArray("PostOffice");
            if (po.length() == 0) return PincodeResult.fail();
            return PincodeResult.ok(po.getJSONObject(0).getString("District"));
        } catch (Exception e) {
            return PincodeResult.fail();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Nominatim pincode → lat/lng ───────────────────────────────────────────

    private static class LatLng {
        double lat, lng;
        LatLng(double lat, double lng) { this.lat=lat; this.lng=lng; }
    }

    private LatLng fetchLatLngFromPincode(String pin, String district) {
        LatLng r = nominatimQuery(
                "https://nominatim.openstreetmap.org/search?format=json&limit=1&country=India&postalcode=" + pin);
        if (r != null) return r;
        if (!TextUtils.isEmpty(district)) {
            r = nominatimQuery("https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                    + URLEncodeSafe(district + ", India"));
            if (r != null) return r;
        }
        return nominatimQuery("https://nominatim.openstreetmap.org/search?format=json&limit=1&q="
                + URLEncodeSafe(pin + ", India"));
    }

    private LatLng nominatimQuery(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setConnectTimeout(7000); conn.setReadTimeout(7000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0 (blood-locator)");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return null;
            JSONArray arr = new JSONArray(readStream(conn));
            if (arr.length() == 0) return null;
            JSONObject first = arr.getJSONObject(0);
            return new LatLng(Double.parseDouble(first.getString("lat")),
                    Double.parseDouble(first.getString("lon")));
        } catch (Exception e) { return null;
        } finally { if (conn != null) conn.disconnect(); }
    }

    // ── data.gov.in blood bank directory ─────────────────────────────────────

    private List<BloodBankInfo> fetchBloodBanksFromDirectory(String pin, String district) {
        if (!TextUtils.isEmpty(district)) {
            List<BloodBankInfo> r = queryDataGovBloodBanks("district", district);
            if (!r.isEmpty()) return r;
        }
        String state = stateFromPincode(pin);
        if (!TextUtils.isEmpty(state)) return queryDataGovBloodBanks("state", state);
        return new ArrayList<>();
    }

    private List<BloodBankInfo> queryDataGovBloodBanks(String field, String value) {
        List<BloodBankInfo> banks = new ArrayList<>();
        HttpURLConnection   conn  = null;
        try {
            String url = "https://api.data.gov.in/resource/" + DATA_GOV_RESOURCE_ID
                    + "?api-key=" + DATA_GOV_API_KEY + "&format=json&limit=30"
                    + "&filters[" + field + "]=" + URLEncoder.encode(value, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(7000); conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "RescueConnect-Android/1.0");
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) return banks;

            JSONArray records = new JSONObject(readStream(conn)).optJSONArray("records");
            if (records == null || records.length() == 0) return banks;

            Map<String, Boolean> seen = new HashMap<>();
            for (int i = 0; i < records.length(); i++) {
                JSONObject rec   = records.getJSONObject(i);
                String     name  = rec.optString("bb_name",    "").trim();
                String     phone = rec.optString("contact_no", "").trim().replaceAll("[^0-9+]", "");
                String     addr  = rec.optString("address",    "").trim();
                String     city  = rec.optString("city",       "").trim();
                String     dist  = rec.optString("district",   "").trim();
                String     st    = rec.optString("state",      "").trim();
                if (name.isEmpty()) name = "Blood Bank";
                StringBuilder ab = new StringBuilder(addr);
                if (!city.isEmpty() && !addr.contains(city)) { if (ab.length()>0) ab.append(", "); ab.append(city); }
                if (!dist.isEmpty() && !addr.contains(dist)) { if (ab.length()>0) ab.append(", "); ab.append(dist); }
                if (!st.isEmpty()   && !addr.contains(st))   { if (ab.length()>0) ab.append(", "); ab.append(st); }
                String key = name + "|" + ab;
                if (seen.containsKey(key)) continue;
                seen.put(key, true);
                banks.add(new BloodBankInfo(name, phone, ab.toString().trim()));
            }
        } catch (Exception ignored) {
        } finally { if (conn != null) conn.disconnect(); }
        return banks;
    }

    // ── Overpass fallback ─────────────────────────────────────────────────────

    private static class BloodBankInfo {
        String name, phone, address;
        BloodBankInfo(String n, String p, String a) { name=n; phone=p; address=a; }
    }

    private List<BloodBankInfo> fetchBloodBanksFromOverpassWithRetry(double lat, double lng) {
        for (int radius : SEARCH_RADII) {
            String query = buildOverpassQuery(lat, lng, radius);
            List<Future<List<BloodBankInfo>>> futures = new ArrayList<>();
            for (String mirror : OVERPASS_MIRRORS)
                futures.add(executor.submit(() -> fetchFromOverpass(mirror, query)));
            for (Future<List<BloodBankInfo>> f : futures) {
                try {
                    List<BloodBankInfo> r = f.get();
                    if (!r.isEmpty()) {
                        for (Future<List<BloodBankInfo>> fx : futures) fx.cancel(true);
                        return r;
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>();
    }

    private String buildOverpassQuery(double lat, double lng, int radiusM) {
        String around = "(around:" + radiusM + "," + lat + "," + lng + ");";
        return "[out:json][timeout:30];("
                + "node[\"amenity\"=\"blood_bank\"]"      + around
                + "way[\"amenity\"=\"blood_bank\"]"       + around
                + "node[\"healthcare\"=\"blood_bank\"]"   + around
                + "way[\"healthcare\"=\"blood_bank\"]"    + around
                + "node[\"name\"~\"[Bb]lood.?[Bb]ank\"]" + around
                + "way[\"name\"~\"[Bb]lood.?[Bb]ank\"]"  + around
                + ");out body;>;out skel qt;";
    }

    private List<BloodBankInfo> fetchFromOverpass(String mirrorUrl, String query) {
        List<BloodBankInfo>  banks = new ArrayList<>();
        HttpURLConnection    conn  = null;
        try {
            conn = (HttpURLConnection) new URL(mirrorUrl).openConnection();
            conn.setConnectTimeout(15000); conn.setReadTimeout(35000);
            conn.setRequestMethod("POST"); conn.setDoOutput(true);
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
                String name = tags.optString("name","").trim();
                if (name.isEmpty()) name = tags.optString("operator","").trim();
                if (name.isEmpty()) name = "Blood Bank";
                String phone = tags.optString("phone","").trim();
                if (phone.isEmpty()) phone = tags.optString("contact:phone","").trim();
                if (phone.isEmpty()) phone = tags.optString("telephone","").trim();
                phone = phone.replaceAll("[\\s\\-]","");
                String addr = tags.optString("addr:full","").trim();
                if (addr.isEmpty()) {
                    String street = tags.optString("addr:street","").trim();
                    String city   = tags.optString("addr:city",  "").trim();
                    if (!street.isEmpty() && !city.isEmpty()) addr = street + ", " + city;
                    else if (!street.isEmpty()) addr = street;
                    else addr = city;
                }
                String key = name + "|" + addr;
                if (seen.containsKey(key)) continue;
                seen.put(key, true);
                banks.add(new BloodBankInfo(name, phone, addr));
            }
        } catch (Exception ignored) {
        } finally { if (conn != null) conn.disconnect(); }
        return banks;
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    private String readStream(HttpURLConnection conn) throws Exception {
        BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"), 8192);
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close(); return sb.toString();
    }

    private String URLEncodeSafe(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String stateFromPincode(String pincode) {
        if (pincode == null || pincode.length() < 2) return "";
        int p = Integer.parseInt(pincode.substring(0, 2));
        if (p == 11) return "Delhi";
        if (p >= 12 && p <= 13) return "Haryana";
        if (p >= 14 && p <= 16) return "Punjab";
        if (p == 17) return "Himachal Pradesh";
        if (p >= 18 && p <= 19) return "Jammu and Kashmir";
        if (p >= 20 && p <= 28) return "Uttar Pradesh";
        if (p >= 30 && p <= 34) return "Rajasthan";
        if (p >= 36 && p <= 39) return "Gujarat";
        if (p >= 40 && p <= 44) return "Maharashtra";
        if (p >= 45 && p <= 48) return "Madhya Pradesh";
        if (p == 49) return "Chhattisgarh";
        if (p >= 50 && p <= 53) return "Telangana";
        if (p >= 54 && p <= 56) return "Andhra Pradesh";
        if (p >= 57 && p <= 58) return "Karnataka";
        if (p >= 60 && p <= 64) return "Tamil Nadu";
        if (p >= 67 && p <= 69) return "Kerala";
        if (p >= 70 && p <= 74) return "West Bengal";
        if (p >= 75 && p <= 77) return "Odisha";
        if (p == 78) return "Assam";
        if (p >= 80 && p <= 85) return "Bihar";
        if (p >= 82 && p <= 83) return "Jharkhand";
        if (p >= 90 && p <= 97) return "Assam";
        return "";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}