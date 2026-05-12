package com.rescueconnect;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.firestore.FirebaseFirestore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ContributionActivity — Peer-to-peer UPI payment
 * ─────────────────────────────────────────────────────────────────────────────
 * Uses Android's built-in UPI intent — no SDK, no KYC, no gateway needed.
 * When user taps Pay, Android shows GPay / PhonePe / Paytm / BHIM chooser.
 * Money goes directly to YOUR_UPI_ID below.
 *
 * ── Setup ────────────────────────────────────────────────────────────────────
 * 1. Replace YOUR_UPI_ID with your actual UPI ID (e.g. yourname@upi)
 * 2. Replace YOUR_NAME with the name shown in payment apps
 * 3. No extra dependencies needed — uses Android Intent only
 * ─────────────────────────────────────────────────────────────────────────────
 */
public class ContributionActivity extends AppCompatActivity {

    // ── REPLACE these with your actual UPI details ────────────────────────────
    private static final String YOUR_UPI_ID = "vharsha1109-1@okaxis";   // e.g. john@okaxis
    private static final String YOUR_NAME   = "RescueConnect";  // shown in payment app

    EditText amountEdit;
    Button   payBtn, skipBtn;
    Button   chip100, chip500, chip1000;
    TextView volunteerNameText;

    FirebaseFirestore db;

    String volunteerName = "";
    String alertId       = "";
    String alertMessage  = "";
    String userMobile    = "";
    String userName      = "";
    double pendingAmount = 0;
    String transactionId = "";

    // Launcher to handle result when user returns from payment app
    private final ActivityResultLauncher<Intent> upiLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> handleUpiResult(result.getResultCode(), result.getData())
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_contribution);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(android.R.id.content), (v, insets) -> {
                    Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
                    return insets;
                });

        db = FirebaseFirestore.getInstance();

        // Get data passed from HomeActivity
        volunteerName = getIntentStr("volunteerName");
        alertId       = getIntentStr("alertId");
        alertMessage  = getIntentStr("alertMessage");
        userMobile    = getIntentStr("userMobile");
        userName      = getIntentStr("userName");

        volunteerNameText = findViewById(R.id.volunteerNameText);
        amountEdit        = findViewById(R.id.amountEdit);
        payBtn            = findViewById(R.id.payBtn);
        skipBtn           = findViewById(R.id.skipBtn);
        chip100           = findViewById(R.id.chip100);
        chip500           = findViewById(R.id.chip500);
        chip1000          = findViewById(R.id.chip1000);

        if (!volunteerName.isEmpty())
            volunteerNameText.setText(volunteerName + " came to your rescue 🦸");

        chip100.setOnClickListener(v  -> setAmount(100));
        chip500.setOnClickListener(v  -> setAmount(500));
        chip1000.setOnClickListener(v -> setAmount(1000));

        payBtn.setOnClickListener(v  -> startUpiPayment());
        skipBtn.setOnClickListener(v -> finish());
    }

    private void setAmount(int amount) {
        amountEdit.setText(String.valueOf(amount));
        amountEdit.clearFocus();
    }

    // ─── Launch UPI payment intent ────────────────────────────────────────────

    private void startUpiPayment() {
        String amountStr = amountEdit.getText().toString().trim();
        if (amountStr.isEmpty()) {
            Toast.makeText(this, "Please enter or select an amount", Toast.LENGTH_SHORT).show();
            return;
        }

        double amountRupees;
        try {
            amountRupees = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
            return;
        }

        if (amountRupees < 1) {
            Toast.makeText(this, "Minimum amount is ₹1", Toast.LENGTH_SHORT).show();
            return;
        }

        pendingAmount = amountRupees;

        // Unique transaction ID for tracking in Firestore
        transactionId = "RC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Build UPI deep link — works with GPay, PhonePe, Paytm, BHIM, etc.
        Uri upiUri = new Uri.Builder()
                .scheme("upi")
                .authority("pay")
                .appendQueryParameter("pa", YOUR_UPI_ID)
                .appendQueryParameter("pn", YOUR_NAME)
                .appendQueryParameter("am", String.format(Locale.US, "%.2f", amountRupees))
                .appendQueryParameter("cu", "INR")
                .appendQueryParameter("tn", "RescueConnect contribution - Thank you!")
                .appendQueryParameter("tr", transactionId)
                .build();

        Intent upiIntent = new Intent(Intent.ACTION_VIEW, upiUri);

        // Show app chooser so user picks GPay / PhonePe / etc.
        Intent chooser = Intent.createChooser(upiIntent, "Pay with UPI");

        try {
            upiLauncher.launch(chooser);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this,
                    "No UPI app found. Please install GPay, PhonePe, or Paytm.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─── Handle result when user returns from payment app ────────────────────

    private void handleUpiResult(int resultCode, Intent data) {
        if (data == null) {
            Toast.makeText(this, "Payment cancelled.", Toast.LENGTH_SHORT).show();
            return;
        }

        String status  = data.getStringExtra("Status");   // SUCCESS / FAILURE / SUBMITTED
        String txnRef  = data.getStringExtra("txnRef");   // UPI transaction reference

        if (status == null) status = "";

        if (status.equalsIgnoreCase("SUCCESS") || status.equalsIgnoreCase("SUBMITTED")) {
            // SUBMITTED = pending bank processing, treat as success
            saveContributionToFirestore(
                    txnRef != null ? txnRef : transactionId,
                    status.equalsIgnoreCase("SUCCESS") ? "success" : "submitted"
            );
        } else if (status.equalsIgnoreCase("FAILURE")) {
            Toast.makeText(this, "Payment failed. Please try again.", Toast.LENGTH_LONG).show();
        } else {
            // Some apps don't return status properly — save as unverified for admin to check
            saveContributionToFirestore(transactionId, "unverified");
            Toast.makeText(this,
                    "If money was deducted, we'll verify and confirm shortly.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ─── Save to Firestore ────────────────────────────────────────────────────

    private void saveContributionToFirestore(String txnId, String status) {
        String date = new SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault())
                .format(new Date());

        Map<String, Object> contribution = new HashMap<>();
        contribution.put("transactionId",  txnId);
        contribution.put("volunteerName",  volunteerName);
        contribution.put("userName",       userName);
        contribution.put("userMobile",     userMobile);
        contribution.put("alertId",        alertId);
        contribution.put("amount",         pendingAmount);
        contribution.put("upiId",          YOUR_UPI_ID);
        contribution.put("date",           date);
        contribution.put("paymentStatus",  status);
        contribution.put("payoutStatus",   "pending");
        contribution.put("paidBy",         "user");

        db.collection("contributions")
                .add(contribution)
                .addOnSuccessListener(ref -> {
                    if (!alertId.isEmpty()) {
                        db.collection("messages").document(alertId)
                                .update("userConfirmed", true, "transactionId", txnId);
                    }
                    Toast.makeText(this,
                            "Payment of ₹" + String.format("%.0f", pendingAmount)
                                    + " recorded! Thank you 🙏",
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Payment done! Ref: " + txnId,
                                Toast.LENGTH_LONG).show()
                );
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private String getIntentStr(String key) {
        String val = getIntent().getStringExtra(key);
        return val != null ? val : "";
    }
}