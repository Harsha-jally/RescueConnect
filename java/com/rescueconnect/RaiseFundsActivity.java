package com.rescueconnect;

import android.app.ProgressDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
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

import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.firestore.FirebaseFirestore;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RaiseFundsActivity
 * ──────────────────
 * Lets a user submit a health fund-raising request.
 * Fields   : patientName, disease, phone, upiId, totalAmount, billFile
 * Upload   : Cloudinary  (cloud: dhtsnhjx3, preset: rescueconnect_bills)
 * Firestore: "fundRequests" collection
 * Status   : "pending" on submit  →  admin verifies  →  "verified"
 *
 * NOTE: Only CLOUD_NAME + UPLOAD_PRESET are used here (unsigned upload).
 * Never put your API Secret inside Android code — it can be extracted
 * from the APK. Keep it only in Firebase Cloud Functions or a backend.
 */
public class RaiseFundsActivity extends AppCompatActivity {

    // ── Cloudinary config ─────────────────────────────────────────────────────
    //    Cloud Name    : dhtsnhjx3
    //    Upload Preset : rescueconnect_bills  (must be set to UNSIGNED
    //                    in Cloudinary → Settings → Upload → Upload presets)
    // ─────────────────────────────────────────────────────────────────────────
    private static final String CLOUD_NAME    = "dhtsnhjx3";
    private static final String UPLOAD_PRESET = "rescueconnect_bills";
    private static final String UPLOAD_URL    =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";

    // ── Views ─────────────────────────────────────────────────────────────────
    TextInputEditText etPatientName, etDisease, etPhone, etUpiId, etTotalAmount;
    TextView          tvUploadLabel, tvFileName;
    ProgressBar       uploadProgress;
    Button            btnSubmit;

    // ── Firebase ──────────────────────────────────────────────────────────────
    FirebaseFirestore db;

    // ── Selected file URI ─────────────────────────────────────────────────────
    Uri selectedFileUri = null;

    // ── File picker launcher ──────────────────────────────────────────────────
    private final ActivityResultLauncher<Intent> filePicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getData() != null
                                && result.getData().getData() != null) {
                            selectedFileUri = result.getData().getData();
                            tvFileName.setText("📄 " + getFileName(selectedFileUri));
                            tvFileName.setVisibility(View.VISIBLE);
                            tvUploadLabel.setText("✅ File selected — tap to change");
                        }
                    });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_raise_funds);
        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main), (v, insets) -> {
                    Insets sb = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars());
                    v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
                    return insets;
                });

        db = FirebaseFirestore.getInstance();

        // Bind views
        etPatientName  = findViewById(R.id.etPatientName);
        etDisease      = findViewById(R.id.etDisease);
        etPhone        = findViewById(R.id.etPhone);
        etUpiId        = findViewById(R.id.etUpiId);
        etTotalAmount  = findViewById(R.id.etTotalAmount);
        tvUploadLabel  = findViewById(R.id.tvUploadLabel);
        tvFileName     = findViewById(R.id.tvFileName);
        uploadProgress = findViewById(R.id.uploadProgress);
        btnSubmit      = findViewById(R.id.btnSubmit);

        findViewById(R.id.cardUpload).setOnClickListener(v -> openFilePicker());
        btnSubmit.setOnClickListener(v -> validateAndSubmit());
    }

    // ── Open file picker ──────────────────────────────────────────────────────

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/pdf", "image/*"});
        filePicker.launch(
                Intent.createChooser(intent, "Select Hospital Bill (PDF or Image)"));
    }

    // ── Get display name of picked file ──────────────────────────────────────

    private String getFileName(Uri uri) {
        String result = "hospital_bill";
        try (Cursor cursor = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) result = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        return result;
    }

    // ── Validate form, then upload or save ────────────────────────────────────

    private void validateAndSubmit() {
        String name    = getText(etPatientName);
        String disease = getText(etDisease);
        String phone   = getText(etPhone);
        String upi     = getText(etUpiId);
        String amount  = getText(etTotalAmount);

        if (TextUtils.isEmpty(name)) {
            etPatientName.setError("Patient name is required");
            etPatientName.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(disease)) {
            etDisease.setError("Disease / condition is required");
            etDisease.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(phone) || phone.length() < 10) {
            etPhone.setError("Enter a valid 10-digit phone number");
            etPhone.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(upi) || !upi.contains("@")) {
            etUpiId.setError("Enter a valid UPI ID — e.g. name@upi");
            etUpiId.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(amount)) {
            etTotalAmount.setError("Enter the total amount needed");
            etTotalAmount.requestFocus();
            return;
        }

        double totalAmt;
        try {
            totalAmt = Double.parseDouble(amount);
        } catch (NumberFormatException e) {
            etTotalAmount.setError("Enter a valid number — e.g. 150000");
            return;
        }

        if (totalAmt <= 0) {
            etTotalAmount.setError("Amount must be greater than zero");
            return;
        }

        if (selectedFileUri != null) {
            // Upload bill to Cloudinary first, then save to Firestore
            uploadToCloudinaryThenSave(name, disease, phone, upi, totalAmt);
        } else {
            // No bill attached — save directly (admin will request docs if needed)
            Toast.makeText(this,
                    "Tip: Attaching a hospital bill helps admin verify faster.",
                    Toast.LENGTH_LONG).show();
            saveToFirestore(name, disease, phone, upi, totalAmt, "");
        }
    }

    // ── Upload file to Cloudinary (background thread) ─────────────────────────

    private void uploadToCloudinaryThenSave(String name, String disease,
                                            String phone, String upi,
                                            double totalAmt) {
        btnSubmit.setEnabled(false);
        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setProgress(10);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                // ── Step 1: Read file bytes ───────────────────────────────
                InputStream inputStream =
                        getContentResolver().openInputStream(selectedFileUri);
                if (inputStream == null)
                    throw new Exception("Could not read the selected file.");

                byte[] fileBytes = inputStream.readAllBytes();
                inputStream.close();

                runOnUiThread(() -> uploadProgress.setProgress(30));

                String fileName = getFileName(selectedFileUri);
                String mimeType = getContentResolver().getType(selectedFileUri);
                if (mimeType == null) mimeType = "application/octet-stream";

                // ── Step 2: Build multipart POST ──────────────────────────
                String boundary =
                        "----RescueBoundary" + System.currentTimeMillis();

                URL url = new URL(UPLOAD_URL);
                HttpURLConnection conn =
                        (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);   // 30 seconds
                conn.setReadTimeout(60_000);       // 60 seconds
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + boundary);

                DataOutputStream dos =
                        new DataOutputStream(conn.getOutputStream());

                // -- upload_preset field --
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes(
                        "Content-Disposition: form-data; name=\"upload_preset\"\r\n\r\n");
                dos.writeBytes(UPLOAD_PRESET + "\r\n");

                // -- folder field --
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes(
                        "Content-Disposition: form-data; name=\"folder\"\r\n\r\n");
                dos.writeBytes("hospital_bills\r\n");

                // -- file field --
                dos.writeBytes("--" + boundary + "\r\n");
                dos.writeBytes("Content-Disposition: form-data; "
                        + "name=\"file\"; filename=\"" + fileName + "\"\r\n");
                dos.writeBytes("Content-Type: " + mimeType + "\r\n\r\n");
                dos.write(fileBytes);
                dos.writeBytes("\r\n");

                // -- close boundary --
                dos.writeBytes("--" + boundary + "--\r\n");
                dos.flush();
                dos.close();

                runOnUiThread(() -> uploadProgress.setProgress(75));

                // ── Step 3: Read response ─────────────────────────────────
                int responseCode = conn.getResponseCode();
                InputStream responseStream = (responseCode == 200)
                        ? conn.getInputStream()
                        : conn.getErrorStream();

                byte[] responseBytes = responseStream.readAllBytes();
                String responseBody  = new String(responseBytes);
                conn.disconnect();

                android.util.Log.d("Cloudinary",
                        "HTTP " + responseCode + " → " + responseBody);

                // ── Step 4: Parse secure_url from JSON response ───────────
                // Cloudinary returns: { "secure_url": "https://res.cloudinary.com/..." }
                String fileUrl = "";
                if (responseBody.contains("\"secure_url\"")) {
                    int start =
                            responseBody.indexOf("\"secure_url\":\"") + 14;
                    int end   = responseBody.indexOf("\"", start);
                    if (start > 14 && end > start) {
                        fileUrl = responseBody.substring(start, end);
                    }
                }

                final String finalUrl = fileUrl;

                runOnUiThread(() -> {
                    uploadProgress.setProgress(100);
                    uploadProgress.setVisibility(View.GONE);

                    if (!finalUrl.isEmpty()) {
                        // ✅ Upload successful
                        saveToFirestore(name, disease, phone, upi,
                                totalAmt, finalUrl);
                    } else {
                        // ❌ Upload failed — show helpful error
                        btnSubmit.setEnabled(true);

                        String errorHint = "";
                        if (responseBody.contains("Upload preset not found")) {
                            errorHint = "\n\n👉 Fix: In Cloudinary → Settings → "
                                    + "Upload → Upload presets, make sure "
                                    + "\"rescueconnect_bills\" exists and is "
                                    + "set to UNSIGNED.";
                        } else if (responseBody.contains("Invalid")) {
                            errorHint = "\n\n👉 Fix: Check your Cloud Name "
                                    + "(currently: dhtsnhjx3).";
                        }

                        new AlertDialog.Builder(RaiseFundsActivity.this)
                                .setTitle("❌ Upload Failed")
                                .setMessage("Could not upload the hospital "
                                        + "bill to Cloudinary."
                                        + errorHint
                                        + "\n\nYou can still submit without "
                                        + "the document and attach it later.")
                                .setPositiveButton("Submit Without Document",
                                        (d, w) -> saveToFirestore(
                                                name, disease, phone, upi,
                                                totalAmt, ""))
                                .setNegativeButton("Try Again", null)
                                .show();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.GONE);
                    btnSubmit.setEnabled(true);
                    android.util.Log.e("Cloudinary", "Upload exception", e);

                    new AlertDialog.Builder(RaiseFundsActivity.this)
                            .setTitle("⚠️ Connection Error")
                            .setMessage("Could not connect to upload service.\n\n"
                                    + "Error: " + e.getMessage()
                                    + "\n\nCheck your internet connection and "
                                    + "try again, or submit without the document.")
                            .setPositiveButton("Submit Without Document",
                                    (d, w) -> saveToFirestore(
                                            name, disease, phone, upi,
                                            totalAmt, ""))
                            .setNegativeButton("Try Again", null)
                            .show();
                });
            }
        });
    }

    // ── Save fund request document to Firestore ───────────────────────────────

    private void saveToFirestore(String name, String disease, String phone,
                                 String upi, double totalAmt, String billUrl) {

        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Submitting your request…");
        pd.setCancelable(false);
        pd.show();

        String timestamp = new SimpleDateFormat(
                "dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(new Date());

        Map<String, Object> data = new HashMap<>();
        data.put("patientName",  name);
        data.put("disease",      disease);
        data.put("phone",        phone);
        data.put("upiId",        upi);
        data.put("totalAmount",  totalAmt);
        data.put("raisedAmount", 0.0);            // starts at 0
        data.put("billUrl",      billUrl);         // Cloudinary URL or ""
        data.put("status",       "pending");       // admin sets to "verified"
        data.put("submittedAt",  timestamp);

        db.collection("fundRequests")
                .add(data)
                .addOnSuccessListener(docRef -> {
                    pd.dismiss();
                    android.util.Log.d("Firestore",
                            "Fund request saved: " + docRef.getId());
                    showPendingVerificationDialog();
                })
                .addOnFailureListener(e -> {
                    pd.dismiss();
                    btnSubmit.setEnabled(true);
                    android.util.Log.e("Firestore",
                            "Save failed: " + e.getMessage());
                    Toast.makeText(this,
                            "Submission failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }

    // ── Success dialog shown after submission ─────────────────────────────────

    private void showPendingVerificationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("✅ Request Submitted!")
                .setMessage(
                        "Your fund request has been submitted successfully.\n\n"
                                + "⏳ Please wait till our admin team verifies "
                                + "your request.\n\n"
                                + "📞 We may call you on the number you provided "
                                + "for verification purposes.\n\n"
                                + "📲 You will receive an SMS once your campaign "
                                + "goes live for donations.")
                .setCancelable(false)
                .setPositiveButton("OK, Got It", (d, w) -> finish())
                .show();
    }

    // ── Utility: get trimmed text from TextInputEditText ──────────────────────

    private String getText(TextInputEditText et) {
        return et.getText() != null
                ? et.getText().toString().trim() : "";
    }
}