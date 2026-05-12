package com.rescueconnect;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import me.pushy.sdk.Pushy;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // ── Keep Pushy socket alive even when app is backgrounded ─────────────
        Pushy.listen(this);
        Log.d(TAG, "Pushy listen started");

        // ── Start the background emergency detection service ──────────────────
        // This service monitors for phone drops, crashes, and screams 24/7.
        // It runs as a foreground service (persistent notification) so Android
        // will not kill it.  START_STICKY in the service ensures it restarts
        // automatically if the OS does kill it under extreme memory pressure.
        // BootReceiver handles restarts after the device is rebooted.
        startEmergencyDetectionService();
    }

    /**
     * Starts EmergencyDetectionService as a foreground service.
     * Safe to call repeatedly — the service returns START_STICKY and ignores
     * duplicate start commands if already running.
     */
    private void startEmergencyDetectionService() {
        Intent serviceIntent = new Intent(this, EmergencyDetectionService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "EmergencyDetectionService started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start EmergencyDetectionService: " + e.getMessage());
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    public void volunteerImplementation(View view) {
        startActivity(new Intent(this, VolunteerLoginActivity.class));
    }

    public void userImplementation(View view) {
        startActivity(new Intent(this, RegisterActivity.class));
    }

    public void adminImplementation(View view) {
        startActivity(new Intent(this, AdminLoginActivity.class));
    }

    public void donationsImplementation(View view) {
        startActivity(new Intent(this, DonationsActivity.class));
    }

    public void bloodDonationImplementation(View view) {
        startActivity(new Intent(this, BloodDonationActivity.class));
    }
}