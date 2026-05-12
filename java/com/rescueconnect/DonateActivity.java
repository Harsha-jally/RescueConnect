package com.rescueconnect;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DonateActivity
 * ─────────────
 * Shows all "verified" fund requests as donation campaigns.
 * Each card shows: patient, disease, raised/total amounts, progress bar.
 * Tapping "Donate" opens a dialog for amount, then launches UPI.
 * After UPI success → Firestore raisedAmount is updated.
 */
public class DonateActivity extends AppCompatActivity {

    RecyclerView    rvCampaigns;
    LinearLayout    emptyState, loadingState;
    FirebaseFirestore db;

    // Holds the campaign being donated to (for UPI callback)
    String pendingDocId = "";
    double pendingAmount = 0;
    double pendingCurrentRaised = 0;

    private final ActivityResultLauncher<Intent> upiLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getData() == null) {
                            Toast.makeText(this, "Payment cancelled.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String status = result.getData().getStringExtra("Status");
                        if (status == null) status = "";

                        if (status.equalsIgnoreCase("SUCCESS")
                                || status.equalsIgnoreCase("SUBMITTED")) {
                            recordDonation(pendingDocId, pendingAmount, pendingCurrentRaised);
                        } else if (status.equalsIgnoreCase("FAILURE")) {
                            Toast.makeText(this, "Payment failed. Try again.", Toast.LENGTH_LONG).show();
                        } else {
                            // App didn't report status — ask user
                            new AlertDialog.Builder(this)
                                    .setTitle("Payment Status Unknown")
                                    .setMessage("Did your payment go through?")
                                    .setPositiveButton("Yes, I paid", (d, w) ->
                                            recordDonation(pendingDocId, pendingAmount, pendingCurrentRaised))
                                    .setNegativeButton("No", null)
                                    .show();
                        }
                    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_donate);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        db           = FirebaseFirestore.getInstance();
        rvCampaigns  = findViewById(R.id.rvCampaigns);
        emptyState   = findViewById(R.id.emptyState);
        loadingState = findViewById(R.id.loadingState);

        rvCampaigns.setLayoutManager(new LinearLayoutManager(this));

        loadCampaigns();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCampaigns(); // refresh amounts when returning from UPI app
    }

    // ── Load verified campaigns from Firestore ────────────────────────────────

    private void loadCampaigns() {
        loadingState.setVisibility(View.VISIBLE);
        rvCampaigns.setVisibility(View.GONE);
        emptyState.setVisibility(View.GONE);

        db.collection("fundRequests")
                .whereEqualTo("status", "verified")
                .get()
                .addOnSuccessListener(snapshot -> {
                    loadingState.setVisibility(View.GONE);
                    List<DocumentSnapshot> docs = snapshot.getDocuments();

                    if (docs.isEmpty()) {
                        emptyState.setVisibility(View.VISIBLE);
                        return;
                    }

                    rvCampaigns.setVisibility(View.VISIBLE);
                    rvCampaigns.setAdapter(new CampaignAdapter(docs));
                })
                .addOnFailureListener(e -> {
                    loadingState.setVisibility(View.GONE);
                    emptyState.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Error loading campaigns: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ── Donate dialog ─────────────────────────────────────────────────────────

    private void showDonateDialog(String docId, String patientName,
                                  String upiId, double totalAmt, double raisedAmt) {

        double remaining = totalAmt - raisedAmt;
        if (remaining <= 0) {
            Toast.makeText(this, "This campaign has already reached its goal! 🎉",
                    Toast.LENGTH_LONG).show();
            return;
        }

        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_donate_amount, null);

        EditText etAmount = dialogView.findViewById(R.id.etDonateAmount);

        new AlertDialog.Builder(this)
                .setTitle("💛 Donate to " + patientName)
                .setMessage("Remaining needed: ₹"
                        + String.format(Locale.getDefault(), "%.0f", remaining)
                        + "\nDonation goes directly via UPI to the patient's account.")
                .setView(dialogView)
                .setPositiveButton("Proceed to Pay", (d, w) -> {
                    String amtStr = etAmount.getText().toString().trim();
                    if (amtStr.isEmpty()) {
                        Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double donateAmt;
                    try { donateAmt = Double.parseDouble(amtStr); }
                    catch (NumberFormatException ex) {
                        Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (donateAmt <= 0) {
                        Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    launchUpiDonation(docId, upiId, patientName, donateAmt, raisedAmt);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Launch UPI ────────────────────────────────────────────────────────────

    private void launchUpiDonation(String docId, String upiId, String patientName,
                                   double amount, double currentRaised) {
        pendingDocId           = docId;
        pendingAmount          = amount;
        pendingCurrentRaised   = currentRaised;

        Uri upiUri = new Uri.Builder()
                .scheme("upi")
                .authority("pay")
                .appendQueryParameter("pa", upiId)
                .appendQueryParameter("pn", patientName)
                .appendQueryParameter("am", String.format(Locale.US, "%.2f", amount))
                .appendQueryParameter("cu", "INR")
                .appendQueryParameter("tn", "RescueConnect Health Donation for " + patientName)
                .build();

        Intent intent  = new Intent(Intent.ACTION_VIEW, upiUri);
        Intent chooser = Intent.createChooser(intent, "Donate via UPI");

        try {
            upiLauncher.launch(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "No UPI app found. Please install GPay, PhonePe, or Paytm.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ── Record donation in Firestore ──────────────────────────────────────────

    private void recordDonation(String docId, double amount, double currentRaised) {
        double newRaised = currentRaised + amount;
        db.collection("fundRequests").document(docId)
                .update("raisedAmount", newRaised)
                .addOnSuccessListener(v -> {
                    Toast.makeText(this,
                            "Thank you! ₹" + String.format(Locale.getDefault(), "%.0f", amount)
                                    + " donated successfully 💛",
                            Toast.LENGTH_LONG).show();
                    loadCampaigns(); // refresh
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Donation recorded but update failed: "
                                + e.getMessage(), Toast.LENGTH_LONG).show());
    }

    // ══ RecyclerView Adapter ══════════════════════════════════════════════════

    private class CampaignAdapter extends RecyclerView.Adapter<CampaignAdapter.VH> {

        final List<DocumentSnapshot> items;

        CampaignAdapter(List<DocumentSnapshot> items) {
            this.items = new ArrayList<>(items);
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_campaign, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            DocumentSnapshot doc = items.get(position);

            String name    = safe(doc.getString("patientName"));
            String disease = safe(doc.getString("disease"));
            String upiId   = safe(doc.getString("upiId"));
            double total   = doc.getDouble("totalAmount")  != null
                    ? doc.getDouble("totalAmount")  : 0;
            double raised  = doc.getDouble("raisedAmount") != null
                    ? doc.getDouble("raisedAmount") : 0;

            h.tvName.setText(name);
            h.tvDisease.setText(disease);
            h.tvRaised.setText("₹" + String.format(Locale.getDefault(), "%.0f", raised));
            h.tvTotal.setText("₹" + String.format(Locale.getDefault(), "%.0f", total));

            int pct = total > 0 ? (int) Math.min(100, (raised / total) * 100) : 0;
            h.progress.setProgress(pct);
            h.tvPercent.setText(pct + "% funded");

            final String finalUpi   = upiId;
            final double finalTotal = total;
            final double finalRaised = raised;
            final String docId       = doc.getId();

            h.btnDonate.setOnClickListener(v ->
                    showDonateDialog(docId, name, finalUpi, finalTotal, finalRaised));
        }

        @Override
        public int getItemCount() { return items.size(); }

        private String safe(String s) { return s != null ? s : "N/A"; }

        class VH extends RecyclerView.ViewHolder {
            TextView    tvName, tvDisease, tvRaised, tvTotal, tvPercent;
            ProgressBar progress;
            Button      btnDonate;

            VH(View v) {
                super(v);
                tvName    = v.findViewById(R.id.tvPatientName);
                tvDisease = v.findViewById(R.id.tvDisease);
                tvRaised  = v.findViewById(R.id.tvRaisedAmount);
                tvTotal   = v.findViewById(R.id.tvTotalAmount);
                tvPercent = v.findViewById(R.id.tvPercent);
                progress  = v.findViewById(R.id.progressDonation);
                btnDonate = v.findViewById(R.id.btnDonateCampaign);
            }
        }
    }
}