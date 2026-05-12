package com.rescueconnect;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.rescueconnect.Util.CheckInternet;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.PermissionUtils;
import com.rescueconnect.Util.SPHelper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class RegisterActivity extends AppCompatActivity {

    // ── Cloudinary config ─────────────────────────────────────────────────────
    private static final String CLOUD_NAME    = "dhtsnhjx3";
    private static final String UPLOAD_PRESET = "rescueconnect_bills";
    private static final String UPLOAD_URL    =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";
    // ─────────────────────────────────────────────────────────────────────────

    private EditText    nameEdit, phoneEdit, emailEdit, emergencyEdit, aadhaarEdit;
    private ImageView   selfiePreview;
    private Button      takeSelfieBtn;
    private TextView    selfieStatusText;
    private ProgressBar progressBar;

    private String nameString, phoneString, emailString, emergencyString, aadhaarString;
    private Bitmap selfieBitmap;
    private String selfieCloudinaryUrl = "";
    private int    GPSoff;

    // ── Flag: camera is open — prevents onPause() from finishing the activity ─
    private boolean isCameraOpen = false;
    // ── URI for the full-resolution photo saved by the camera app ─────────────
    private Uri photoUri;

    private FirebaseFirestore db;
    private final OkHttpClient httpClient = new OkHttpClient();

    // ── ML Kit face detector (fast / lightweight options) ─────────────────────
    private final FaceDetector faceDetector = FaceDetection.getClient(
            new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.20f)   // face must occupy ≥20 % of the frame
                    .build());

    // ── Camera launcher ───────────────────────────────────────────────────────
    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
                isCameraOpen = false;
                if (!saved || photoUri == null) {
                    Toast.makeText(this, "Selfie capture cancelled", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Pass the URI directly — EXIF rotation is handled inside runFaceDetection
                runFaceDetection(photoUri);
            });
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (SPHelper.GetData(this, Constants.SP_NAME) != null) {
            navigateToHome();
            return;
        }

        setContentView(R.layout.activity_register);
        permissionChecking();
        initViews();

        db = FirebaseFirestore.getInstance();

        try {
            GPSoff = Settings.Secure.getInt(
                    getContentResolver(), Settings.Secure.LOCATION_MODE);
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (GPSoff == 0) openGps();
    }

    // ── CRITICAL FIX: only finish() when the camera is NOT open ──────────────
    @Override
    protected void onPause() {
        super.onPause();
        if (!isCameraOpen) {
            finish();
        }
    }

    private void initViews() {
        nameEdit         = findViewById(R.id.nameEditText);
        phoneEdit        = findViewById(R.id.mobileEditText);
        emailEdit        = findViewById(R.id.emailEditText);
        emergencyEdit    = findViewById(R.id.emergencyEditText);
        aadhaarEdit      = findViewById(R.id.aadhaarEditText);
        selfiePreview    = findViewById(R.id.selfieImageView);
        takeSelfieBtn    = findViewById(R.id.takeSelfieButton);
        selfieStatusText = findViewById(R.id.selfieStatusText);
        progressBar      = findViewById(R.id.registerProgressBar);

        takeSelfieBtn.setOnClickListener(v -> launchCamera());
    }

    // ── Launch camera with a FileProvider URI ─────────────────────────────────
    private void launchCamera() {
        try {
            File photoFile = createImageFile();
            photoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",   // must match AndroidManifest authority
                    photoFile);
            isCameraOpen = true;
            cameraLauncher.launch(photoUri);
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create photo file: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** Creates a uniquely-named JPEG file in the app's private Pictures directory. */
    private File createImageFile() throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("SELFIE_" + stamp + "_", ".jpg", dir);
    }

    // ── Face detection ────────────────────────────────────────────────────────

    /**
     * Uses InputImage.fromFilePath() so ML Kit reads the EXIF orientation tag
     * automatically — this is the key fix. fromBitmap(bitmap, 0) assumed zero
     * rotation and missed faces in portrait photos (typically saved at 90°).
     */
    private void runFaceDetection(Uri uri) {
        selfieStatusText.setText("Checking for face…");
        selfieStatusText.setTextColor(getColor(android.R.color.darker_gray));
        takeSelfieBtn.setEnabled(false);

        InputImage image;
        try {
            // fromFilePath handles EXIF rotation — fromBitmap does NOT
            image = InputImage.fromFilePath(this, uri);
        } catch (IOException e) {
            selfieStatusText.setText("✗ Could not read photo");
            selfieStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
            takeSelfieBtn.setEnabled(true);
            return;
        }

        faceDetector.process(image)
                .addOnSuccessListener((List<Face> faces) -> {
                    if (faces.isEmpty()) {
                        selfieStatusText.setText("✗ No face detected — please retake");
                        selfieStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
                        takeSelfieBtn.setEnabled(true);
                        selfieBitmap = null;
                        selfiePreview.setVisibility(View.GONE);
                        Toast.makeText(this,
                                "No face detected. Make sure your face is clearly visible.",
                                Toast.LENGTH_LONG).show();
                    } else if (faces.size() > 1) {
                        selfieStatusText.setText("✗ Multiple faces — please retake alone");
                        selfieStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
                        takeSelfieBtn.setEnabled(true);
                        selfieBitmap = null;
                        selfiePreview.setVisibility(View.GONE);
                        Toast.makeText(this,
                                "Multiple faces detected. Please take a solo selfie.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        // One face confirmed — now decode + rotate bitmap for display/upload
                        Bitmap corrected = decodeExifCorrectedBitmap(uri);
                        selfieBitmap = corrected;
                        selfiePreview.setImageBitmap(corrected);
                        selfiePreview.setVisibility(View.VISIBLE);
                        selfieStatusText.setText("✓ Face detected — selfie accepted");
                        selfieStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
                        takeSelfieBtn.setEnabled(true);
                    }
                })
                .addOnFailureListener(e -> {
                    // Detection engine failed — fall back gracefully, don't block registration
                    Bitmap fallback = decodeExifCorrectedBitmap(uri);
                    selfieBitmap = fallback;
                    selfiePreview.setImageBitmap(fallback);
                    selfiePreview.setVisibility(View.VISIBLE);
                    selfieStatusText.setText("✓ Selfie captured (detection unavailable)");
                    selfieStatusText.setTextColor(getColor(android.R.color.holo_orange_dark));
                    takeSelfieBtn.setEnabled(true);
                });
    }

    /**
     * Decodes the JPEG and rotates it to match its EXIF orientation so the
     * preview and Cloudinary upload are always right-side-up.
     */
    private Bitmap decodeExifCorrectedBitmap(Uri uri) {
        try {
            Bitmap raw = BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(uri));
            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(
                            getContentResolver().openInputStream(uri));
            int orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90)  degrees = 90;
            else if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            if (degrees == 0) return raw;
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(degrees);
            return Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
        } catch (Exception e) {
            // If anything fails just return the raw decode
            try {
                return BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private boolean validateFields() {
        nameString      = nameEdit.getText().toString().trim();
        phoneString     = phoneEdit.getText().toString().trim();
        emailString     = emailEdit.getText().toString().trim();
        emergencyString = emergencyEdit.getText().toString().trim();
        aadhaarString   = aadhaarEdit.getText().toString().trim();

        if (nameString.isEmpty() || phoneString.isEmpty()
                || emailString.isEmpty() || emergencyString.isEmpty()) {
            Toast.makeText(this, "All fields must be filled", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!emailString.contains("@")) {
            Toast.makeText(this, "Please enter a valid email", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!aadhaarString.isEmpty() && !isValidAadhaar(aadhaarString)) {
            Toast.makeText(this,
                    "Aadhaar number is invalid (must be 12 digits)",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (selfieBitmap == null) {
            Toast.makeText(this, "Please take a selfie before registering",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** Verhoeff algorithm — same checksum UIDAI uses for format validation. */
    private boolean isValidAadhaar(String aadhaar) {
        if (!aadhaar.matches("\\d{12}")) return false;
        int[][] d = {
                {0,1,2,3,4,5,6,7,8,9},{1,2,3,4,0,6,7,8,9,5},{2,3,4,0,1,7,8,9,5,6},
                {3,4,0,1,2,8,9,5,6,7},{4,0,1,2,3,9,5,6,7,8},{5,9,8,7,6,0,4,3,2,1},
                {6,5,9,8,7,1,0,4,3,2},{7,6,5,9,8,2,1,0,4,3},{8,7,6,5,9,3,2,1,0,4},
                {9,8,7,6,5,4,3,2,1,0}
        };
        int[][] p = {
                {0,1,2,3,4,5,6,7,8,9},{1,5,7,6,2,8,3,0,9,4},{5,8,0,3,7,9,6,1,4,2},
                {8,9,1,6,0,4,3,5,2,7},{9,4,5,3,1,2,6,8,7,0},{4,2,8,6,5,7,3,9,0,1},
                {2,7,9,3,8,0,6,4,1,5},{7,0,4,6,9,1,3,2,5,8}
        };
        int[] inv = {0,4,3,2,1,9,8,7,6,5};
        int c = 0;
        String rev = new StringBuilder(aadhaar).reverse().toString();
        for (int i = 0; i < rev.length(); i++)
            c = d[c][p[i % 8][rev.charAt(i) - '0']];
        return inv[c] == 0;
    }

    // ── Register button ───────────────────────────────────────────────────────

    public void registerImplementation(View view) {
        if (!CheckInternet.isNetworkAvailable(this)) {
            CheckInternet.display(this);
            return;
        }
        if (!validateFields()) return;
        setLoading(true);
        uploadSelfieToCloudinary();
    }

    // ── Cloudinary upload ─────────────────────────────────────────────────────

    private void uploadSelfieToCloudinary() {
        // Compress to JPEG bytes — scale down to max 1024px to keep upload size reasonable
        Bitmap scaled = scaleBitmap(selfieBitmap, 1024);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        byte[] jpegBytes = baos.toByteArray();

        // Use MultipartBody with raw bytes — NOT base64 in a FormBody.
        // FormBody encodes the entire base64 string as a URL-encoded field which
        // Cloudinary rejects for large images, returning an error JSON with no secure_url.
        RequestBody fileBody = RequestBody.create(
                jpegBytes, okhttp3.MediaType.parse("image/jpeg"));

        RequestBody multipart = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file",           "selfie.jpg", fileBody)
                .addFormDataPart("upload_preset",  UPLOAD_PRESET)
                .addFormDataPart("folder",         "rescue_connect/user_selfies")
                .addFormDataPart("public_id",      "user_" + phoneString)
                .build();

        Request request = new Request.Builder().url(UPLOAD_URL).post(multipart).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(RegisterActivity.this,
                            "Selfie upload failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                try {
                    JSONObject json = new JSONObject(body);
                    // Cloudinary returns {"error":{"message":"..."}} on failure
                    if (json.has("error")) {
                        String msg = json.getJSONObject("error").optString("message", "Unknown error");
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(RegisterActivity.this,
                                    "Cloudinary error: " + msg, Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    selfieCloudinaryUrl = json.getString("secure_url");
                    runOnUiThread(() -> saveUserData());
                } catch (Exception e) {
                    android.util.Log.e("RegisterActivity", "Cloudinary raw response: " + body);
                    runOnUiThread(() -> {
                        setLoading(false);
                        Toast.makeText(RegisterActivity.this,
                                "Upload parse error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    /** Scales a bitmap down so its longest side is at most {@code maxPx}. */
    private Bitmap scaleBitmap(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float scale = maxPx / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
    }

    // ── Save to SP + Firestore ────────────────────────────────────────────────

    private void saveUserData() {
        SPHelper.SaveData(this, Constants.SP_NAME,              nameString);
        SPHelper.SaveData(this, Constants.SP_MOBILE,            phoneString);
        SPHelper.SaveData(this, Constants.SP_EMAIL,             emailString);
        SPHelper.SaveData(this, Constants.SP_EMERGENCY_MESSAGE, emergencyString);

        String date = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());

        Map<String, Object> userData = new HashMap<>();
        userData.put("name",             nameString);
        userData.put("mobile",           phoneString);
        userData.put("email",            emailString);
        userData.put("emergencyMessage", emergencyString);
        userData.put("aadhaarNumber",    aadhaarString);
        userData.put("aadhaarVerified",  !aadhaarString.isEmpty());
        userData.put("selfieUrl",        selfieCloudinaryUrl);
        userData.put("registeredOn",     date);
        userData.put("role",             "user");

        db.collection("users")
                .document(phoneString)
                .set(userData)
                .addOnSuccessListener(unused -> {
                    setLoading(false);
                    Toast.makeText(this, "Registered successfully!", Toast.LENGTH_LONG).show();
                    navigateToHome();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    android.util.Log.e("RegisterActivity", "Firestore: " + e.getMessage());
                    Toast.makeText(this, "Registered (sync pending)", Toast.LENGTH_LONG).show();
                    navigateToHome();
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setLoading(boolean on) {
        progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
        findViewById(R.id.registerButton).setEnabled(!on);
        takeSelfieBtn.setEnabled(!on);
    }

    public void navigateToHome() {
        isCameraOpen = false;           // safety reset before navigating away
        startActivity(new Intent(this, HomeActivity.class));
    }

    private void openGps() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("Location Services Not Active");
            b.setCancelable(false);
            b.setMessage("Please enable Location Services and Internet");
            b.setPositiveButton("OK", (d, i) ->
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
            Dialog dlg = b.create();
            dlg.setCanceledOnTouchOutside(false);
            dlg.show();
        }
    }

    private void permissionChecking() {
        PermissionUtils.requestPermission(this, 3,
                Manifest.permission.CAMERA,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE);
    }
}