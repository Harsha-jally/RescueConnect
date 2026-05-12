package com.rescueconnect;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.rescueconnect.Loader.ShowLoader;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import me.pushy.sdk.Pushy;

public class VolunteerLoginActivity extends AppCompatActivity {

    private static final String TAG = "VolunteerLogin";

    EditText mobileEdit, passwordEdit;
    String   mobileString, passwordString;
    ShowLoader showLoader;
    FirebaseAuth firebaseAuth;
    FirebaseFirestore db;

    // ── Android 13+ notification permission launcher ──────────────────────────
    private final ActivityResultLauncher<String> notifPermLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> Log.d(TAG, "POST_NOTIFICATIONS granted: " + granted)
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_volunteer_login);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        showLoader   = new ShowLoader(this);
        firebaseAuth = FirebaseAuth.getInstance();
        db           = FirebaseFirestore.getInstance();

        // ── Request POST_NOTIFICATIONS on Android 13+ ─────────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mobileEdit   = findViewById(R.id.mobileEdit);
        passwordEdit = findViewById(R.id.passwordEdit);
    }

    public void volunteerLoginImplementation(View view) {
        mobileString   = mobileEdit.getText().toString().trim();
        passwordString = passwordEdit.getText().toString();

        if (mobileString.isEmpty() || passwordString.isEmpty()) {
            showLoader.PresentToast("Fields must not be empty");
            return;
        }
        if (mobileString.length() != 10 || !mobileString.matches("[6-9][0-9]{9}")) {
            mobileEdit.setError("Enter a valid 10-digit mobile number");
            mobileEdit.requestFocus();
            return;
        }

        String syntheticEmail = mobileString + "@rescueconnect.com";

        showLoader.showProgressDialog();
        firebaseAuth.signInWithEmailAndPassword(syntheticEmail, passwordString)
                .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
                    @Override
                    public void onSuccess(AuthResult authResult) {

                        // ── FIX: Fetch volunteer name from Firestore and save
                        //         to SharedPreferences so the session is valid.
                        //         Without this, SP_NAME stays null after login,
                        //         and the register screen wrongly stays accessible.
                        db.collection("volunteers").document(mobileString).get()
                                .addOnSuccessListener((DocumentSnapshot doc) -> {
                                    showLoader.dismissDialog();

                                    String name = doc.exists()
                                            ? doc.getString("name")
                                            : mobileString; // fallback if doc missing

                                    // Save session to SharedPreferences
                                    SPHelper.SaveData(VolunteerLoginActivity.this,
                                            Constants.SP_NAME, name);
                                    SPHelper.SaveData(VolunteerLoginActivity.this,
                                            Constants.SP_MOBILE, mobileString);

                                    mobileEdit.setText("");
                                    passwordEdit.setText("");

                                    // ── Pushy device registration ─────────────
                                    new Thread(() -> {
                                        try {
                                            String token = Pushy.register(getApplicationContext());
                                            Log.d(TAG, "✅ Pushy device token: " + token);
                                        } catch (Exception e) {
                                            Log.e(TAG, "❌ Pushy registration exception: "
                                                    + e.getMessage(), e);
                                        }
                                    }).start();

                                    // Navigate to volunteer home
                                    Intent intent = new Intent(VolunteerLoginActivity.this,
                                            VolunteerHomeActivity.class);
                                    intent.putExtra("MOBILE", mobileString);
                                    startActivity(intent);
                                })
                                .addOnFailureListener(e -> {
                                    // Firestore fetch failed — still log in with mobile as fallback
                                    showLoader.dismissDialog();
                                    Log.w(TAG, "Firestore fetch failed, using mobile as name fallback");

                                    SPHelper.SaveData(VolunteerLoginActivity.this,
                                            Constants.SP_NAME, mobileString);
                                    SPHelper.SaveData(VolunteerLoginActivity.this,
                                            Constants.SP_MOBILE, mobileString);

                                    mobileEdit.setText("");
                                    passwordEdit.setText("");

                                    Intent intent = new Intent(VolunteerLoginActivity.this,
                                            VolunteerHomeActivity.class);
                                    intent.putExtra("MOBILE", mobileString);
                                    startActivity(intent);
                                });
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        showLoader.PresentToast(e.getMessage());
                        showLoader.dismissDialog();
                    }
                });
    }

    public void volunteerSignupImplementation(View view) {
        startActivity(new Intent(VolunteerLoginActivity.this, VolunteerRegisterActivity.class));
    }
}