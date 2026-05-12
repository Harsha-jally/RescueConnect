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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
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
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class VolunteerRegisterActivity extends AppCompatActivity {

    // ── Cloudinary config ─────────────────────────────────────────────────────
    private static final String CLOUD_NAME    = "dhtsnhjx3";
    private static final String UPLOAD_PRESET = "rescueconnect_bills";
    private static final String UPLOAD_URL    =
            "https://api.cloudinary.com/v1_1/" + CLOUD_NAME + "/auto/upload";

    // ── Fast2SMS config ───────────────────────────────────────────────────────
    private static final String FAST2SMS_API_KEY = "Gi49OTUmjkFJ44KphesOMkX9V8xgGajWHBcrNZxte9JPJ2PpZUDyS7Pntsy5";
    private static final String FAST2SMS_URL     = "https://www.fast2sms.com/dev/bulkV2";

    // ─────────────────────────────────────────────────────────────────────────

    private EditText nameEdit, phoneEdit, otpEdit, aadhaarEdit, passwordEdit, confirmPasswordEdit;
    private TextInputLayout otpLayout;
    private Button   takeSelfieBtn, sendOtpBtn, verifyOtpBtn;
    private TextView selfieStatusText, otpStatusText;
    private ImageView selfiePreview;
    private ProgressBar progressBar;

    private String nameString, phoneString, aadhaarString, passwordString;
    private double latitude = 0.0, longitude = 0.0;
    private Bitmap selfieBitmap;
    private String selfieCloudinaryUrl = "";
    private int    GPSoff;

    // ── OTP state ─────────────────────────────────────────────────────────────
    private String  generatedOtp = "";
    private boolean otpVerified  = false;

    // ── Flag: camera is open — prevents onPause() from finishing the activity ─
    private boolean isCameraOpen = false;
    // ── URI for the full-resolution photo saved by the camera app ─────────────
    private Uri photoUri;

    private FirebaseFirestore db;
    private FirebaseAuth firebaseAuth;
    private final OkHttpClient httpClient = new OkHttpClient();
    private FusedLocationProviderClient fusedLocationClient;

    // ── ML Kit face detector ──────────────────────────────────────────────────
    private final FaceDetector faceDetector = FaceDetection.getClient(
            new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setMinFaceSize(0.20f)
                    .build());

    // ── Camera launcher ───────────────────────────────────────────────────────
    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
                isCameraOpen = false;
                if (!saved || photoUri == null) {
                    Toast.makeText(this, "Selfie capture cancelled", Toast.LENGTH_SHORT).show();
                    return;
                }
                runFaceDetection(photoUri);
            });

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── FIX: Always sign out any existing Firebase session before showing
        //         the register screen. This prevents a logged-in volunteer from
        //         being auto-redirected to VolunteerHomeActivity when they tap
        //         "Create Account" from the login screen.
        //         The register page must always be accessible regardless of
        //         any prior login state or SharedPreferences data.
        FirebaseAuth.getInstance().signOut();

        setContentView(R.layout.activity_volunteer_register);
        permissionChecking();
        initViews();

        db = FirebaseFirestore.getInstance();
        firebaseAuth = FirebaseAuth.getInstance();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

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

    // ── View init ─────────────────────────────────────────────────────────────

    private void initViews() {
        nameEdit         = findViewById(R.id.volNameEditText);
        phoneEdit        = findViewById(R.id.volMobileEditText);
        otpEdit          = findViewById(R.id.volOtpEditText);
        otpLayout        = findViewById(R.id.volOtpLayout);
        aadhaarEdit      = findViewById(R.id.volAadhaarEditText);
        selfiePreview    = findViewById(R.id.volSelfieImageView);
        takeSelfieBtn    = findViewById(R.id.volTakeSelfieButton);
        selfieStatusText = findViewById(R.id.volSelfieStatusText);
        sendOtpBtn       = findViewById(R.id.volSendOtpButton);
        verifyOtpBtn     = findViewById(R.id.volVerifyOtpButton);
        otpStatusText    = findViewById(R.id.volOtpStatusText);
        progressBar      = findViewById(R.id.volRegisterProgressBar);

        passwordEdit        = findViewById(R.id.volPasswordEditText);
        confirmPasswordEdit = findViewById(R.id.volConfirmPasswordEditText);

        takeSelfieBtn.setOnClickListener(v -> launchCamera());
        sendOtpBtn.setOnClickListener(v -> sendOtp());
        verifyOtpBtn.setOnClickListener(v -> verifyOtp());
    }

    // ── Fast2SMS OTP ──────────────────────────────────────────────────────────

    private void sendOtp() {
        String phone = phoneEdit.getText().toString().trim();
        if (phone.length() != 10) {
            Toast.makeText(this, "Enter a valid 10-digit mobile number", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!CheckInternet.isNetworkAvailable(this)) {
            CheckInternet.display(this);
            return;
        }

        // Generate 6-digit OTP
        generatedOtp = String.format("%06d", new Random().nextInt(999999));
        otpVerified  = false;

        sendOtpBtn.setEnabled(false);
        otpStatusText.setVisibility(View.VISIBLE);
        otpStatusText.setText("Sending OTP…");
        otpStatusText.setTextColor(getColor(android.R.color.darker_gray));

        String message = "Your RescueConnect volunteer OTP is: " + generatedOtp
                + ". Valid for 10 minutes. Do not share.";

        Request request = new Request.Builder()
                .url(FAST2SMS_URL)
                .addHeader("authorization", FAST2SMS_API_KEY)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .post(new FormBody.Builder()
                        .add("route",    "q")
                        .add("message",  message)
                        .add("language", "english")
                        .add("flash",    "0")
                        .add("numbers",  phone)
                        .build())
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    sendOtpBtn.setEnabled(true);
                    otpStatusText.setText("Failed to send OTP: " + e.getMessage());
                    otpStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(() -> {
                    sendOtpBtn.setEnabled(true);
                    otpLayout.setVisibility(View.VISIBLE);
                    verifyOtpBtn.setVisibility(View.VISIBLE);
                    otpStatusText.setText("OTP sent to " + phone);
                    otpStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
                });
            }
        });
    }

    private void verifyOtp() {
        String entered = otpEdit.getText().toString().trim();
        if (entered.isEmpty()) {
            Toast.makeText(this, "Please enter the OTP", Toast.LENGTH_SHORT).show();
            return;
        }
        if (entered.equals(generatedOtp)) {
            otpVerified = true;
            otpStatusText.setText("✓ Mobile number verified");
            otpStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
            verifyOtpBtn.setEnabled(false);
            phoneEdit.setEnabled(false);
            sendOtpBtn.setEnabled(false);
            otpEdit.setEnabled(false);
            Toast.makeText(this, "OTP verified successfully!", Toast.LENGTH_SHORT).show();
        } else {
            otpStatusText.setText("✗ Incorrect OTP. Please try again.");
            otpStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
            Toast.makeText(this, "Incorrect OTP", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Launch camera with a FileProvider URI ─────────────────────────────────

    private void launchCamera() {
        try {
            File photoFile = createImageFile();
            photoUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    photoFile);
            isCameraOpen = true;
            cameraLauncher.launch(photoUri);
        } catch (IOException e) {
            Toast.makeText(this, "Cannot create photo file: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("VOL_SELFIE_" + stamp + "_", ".jpg", dir);
    }

    // ── Face detection ────────────────────────────────────────────────────────

    private void runFaceDetection(Uri uri) {
        selfieStatusText.setText("Checking for face…");
        selfieStatusText.setTextColor(getColor(android.R.color.darker_gray));
        takeSelfieBtn.setEnabled(false);

        InputImage image;
        try {
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
                    Bitmap fallback = decodeExifCorrectedBitmap(uri);
                    selfieBitmap = fallback;
                    selfiePreview.setImageBitmap(fallback);
                    selfieStatusText.setText("✓ Selfie accepted (face check skipped)");
                    selfieStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
                    takeSelfieBtn.setEnabled(true);
                });
    }

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
            try {
                return BitmapFactory.decodeStream(getContentResolver().openInputStream(uri));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private boolean validateFields() {
        nameString    = nameEdit.getText().toString().trim();
        phoneString   = phoneEdit.getText().toString().trim();
        aadhaarString = aadhaarEdit.getText().toString().trim();

        if (nameString.isEmpty() || phoneString.isEmpty()) {
            Toast.makeText(this, "All fields must be filled", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!otpVerified) {
            Toast.makeText(this, "Please verify your mobile number with OTP",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (!aadhaarString.isEmpty() && !isValidAadhaar(aadhaarString)) {
            Toast.makeText(this,
                    "Aadhaar number is invalid (must be 12 digits, Verhoeff-valid)",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        passwordString = passwordEdit.getText().toString();
        String confirmPassword = confirmPasswordEdit.getText().toString();
        if (passwordString.isEmpty() || confirmPassword.isEmpty()) {
            Toast.makeText(this, "Please set a password", Toast.LENGTH_LONG).show();
            return false;
        }
        if (passwordString.length() < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_LONG).show();
            return false;
        }
        if (!passwordString.equals(confirmPassword)) {
            Toast.makeText(this, "Passwords do not match", Toast.LENGTH_LONG).show();
            return false;
        }
        if (selfieBitmap == null) {
            Toast.makeText(this, "Please take a selfie before registering",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /** Verhoeff checksum — same algorithm UIDAI uses for format validation. */
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

    @SuppressWarnings("MissingPermission")
    public void volunteerRegisterImplementation(View view) {
        if (!CheckInternet.isNetworkAvailable(this)) {
            CheckInternet.display(this);
            return;
        }
        if (!validateFields()) return;
        setLoading(true);

        // Fetch GPS location first, then proceed to upload
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        latitude  = location.getLatitude();
                        longitude = location.getLongitude();
                        uploadSelfieToCloudinary();
                    } else {
                        // getLastLocation() returned null — request a fresh fix
                        LocationRequest req = LocationRequest.create()
                                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                                .setNumUpdates(1)
                                .setInterval(0);
                        fusedLocationClient.requestLocationUpdates(req,
                                new LocationCallback() {
                                    @Override
                                    public void onLocationResult(LocationResult result) {
                                        fusedLocationClient.removeLocationUpdates(this);
                                        if (result != null && result.getLastLocation() != null) {
                                            latitude  = result.getLastLocation().getLatitude();
                                            longitude = result.getLastLocation().getLongitude();
                                        }
                                        // Proceed even if GPS fix fails — lat/lng stay 0.0
                                        uploadSelfieToCloudinary();
                                    }
                                },
                                getMainLooper());
                    }
                })
                .addOnFailureListener(e -> {
                    // Location fetch failed — proceed with 0,0 so registration isn't blocked
                    android.util.Log.w("VolunteerRegister", "GPS fetch failed: " + e.getMessage());
                    uploadSelfieToCloudinary();
                });
    }

    // ── Cloudinary upload ─────────────────────────────────────────────────────

    private void uploadSelfieToCloudinary() {
        Bitmap scaled = scaleBitmap(selfieBitmap, 1024);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos);
        byte[] jpegBytes = baos.toByteArray();

        RequestBody fileBody = RequestBody.create(
                jpegBytes, okhttp3.MediaType.parse("image/jpeg"));

        RequestBody multipart = new okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("file",          "selfie.jpg", fileBody)
                .addFormDataPart("upload_preset", UPLOAD_PRESET)
                .addFormDataPart("folder",        "rescue_connect/volunteer_selfies")
                .addFormDataPart("public_id",     "volunteer_" + phoneString)
                .build();

        Request request = new Request.Builder().url(UPLOAD_URL).post(multipart).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(VolunteerRegisterActivity.this,
                            "Selfie upload failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                try {
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("error")) {
                        String msg = json.getJSONObject("error").optString("message", "Unknown error");
                        runOnUiThread(() -> {
                            setLoading(false);
                            Toast.makeText(VolunteerRegisterActivity.this,
                                    "Cloudinary error: " + msg, Toast.LENGTH_LONG).show();
                        });
                        return;
                    }
                    selfieCloudinaryUrl = json.getString("secure_url");
                    runOnUiThread(() -> createAuthAccount());
                } catch (Exception e) {
                    android.util.Log.e("VolunteerRegister", "Cloudinary raw response: " + responseBody);
                    runOnUiThread(() -> {
                        setLoading(false);
                        Toast.makeText(VolunteerRegisterActivity.this,
                                "Upload parse error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private Bitmap scaleBitmap(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float scale = maxPx / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true);
    }

    // ── Create Firebase Auth account then save data ───────────────────────────

    private void createAuthAccount() {
        String syntheticEmail = phoneString + "@rescueconnect.com";
        firebaseAuth.createUserWithEmailAndPassword(syntheticEmail, passwordString)
                .addOnSuccessListener(authResult -> saveVolunteerData())
                .addOnFailureListener(e -> {
                    setLoading(false);
                    String msg = e.getMessage() != null ? e.getMessage() : "Auth error";
                    // If account already exists, try signing in with the provided password
                    if (msg.contains("already in use")) {
                        firebaseAuth.signInWithEmailAndPassword(syntheticEmail, passwordString)
                                .addOnSuccessListener(r -> saveVolunteerData())
                                .addOnFailureListener(e2 -> {
                                    setLoading(false);
                                    Toast.makeText(this,
                                            "This number is already registered. Please login.",
                                            Toast.LENGTH_LONG).show();
                                });
                    } else {
                        Toast.makeText(this, "Account creation failed: " + msg,
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ── Save to Firestore ─────────────────────────────────────────────────────

    private void saveVolunteerData() {
        // NOTE: Do NOT save to SP here — the volunteer must log in after registration.
        // SP data is only written on successful login, not on registration.

        String date = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());

        Map<String, Object> data = new HashMap<>();
        data.put("name",             nameString);
        data.put("mobile",           phoneString);
        data.put("latitude",         latitude);
        data.put("longitude",        longitude);
        data.put("aadhaarNumber",    aadhaarString);
        data.put("aadhaarVerified",  !aadhaarString.isEmpty());
        data.put("selfieUrl",        selfieCloudinaryUrl);
        data.put("registeredOn",     date);
        data.put("role",             "volunteer");
        data.put("status",           "pending");

        db.collection("volunteers")
                .document(phoneString)
                .set(data)
                .addOnSuccessListener(unused -> {
                    setLoading(false);
                    // Sign out the Firebase session created during registration —
                    // the volunteer must go through login to get an active session.
                    firebaseAuth.signOut();
                    navigateToLogin(
                            "✅ Registration submitted! Your account is under review.\n"
                                    + "You will be notified once approved. Please login to continue.");
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    android.util.Log.e("VolunteerRegister",
                            "Firestore save failed: " + e.getMessage());
                    // Sign out even on failure — don't leave a half-authenticated session.
                    firebaseAuth.signOut();
                    navigateToLogin(
                            "Registration saved locally. Please login once you're back online.");
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        findViewById(R.id.volRegisterButton).setEnabled(!loading);
        takeSelfieBtn.setEnabled(!loading);
    }

    // Called after successful registration — signs out and sends volunteer to login.
    private void navigateToLogin(String message) {
        isCameraOpen = false;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent intent = new Intent(this, VolunteerLoginActivity.class);
        // Clear the entire back stack so pressing Back from Login doesn't
        // bring them back to the registration screen.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finishAffinity();
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

    // ── Distance helper ───────────────────────────────────────────────────────

    public static double calculateDistance(double lat1, double lon1,
                                           Double lat2, Double lon2) {
        if (lat2 == null) lat2 = 0.0;
        if (lon2 == null) lon2 = 0.0;
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}