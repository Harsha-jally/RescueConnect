package com.rescueconnect;

import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * SafetySettingsActivity
 * ─────────────────────────────────────────────────────────────────────────────
 * A dedicated full-screen settings page for all three timing options.
 * Uses plain RadioGroup + RadioButton widgets — no dialogs, no API quirks.
 *
 * Each RadioButton has its millisecond value stored as an integer in its
 * android:tag attribute. The Activity reads the tag of the checked button
 * to get the chosen value, with zero reflection or index arithmetic.
 *
 * Add to AndroidManifest.xml inside <application>:
 *   <activity android:name=".SafetySettingsActivity" android:exported="false" />
 */
public class SafetySettingsActivity extends AppCompatActivity {

    private RadioGroup rgCancelWindow;
    private RadioGroup rgPingInterval;
    private RadioGroup rgResponseTimeout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_safety_settings);

        // ── Bind views ────────────────────────────────────────────────────────
        rgCancelWindow     = findViewById(R.id.rgCancelWindow);
        rgPingInterval     = findViewById(R.id.rgPingInterval);
        rgResponseTimeout  = findViewById(R.id.rgResponseTimeout);

        Button saveBtn = findViewById(R.id.saveSettingsBtn);
        findViewById(R.id.backBtn).setOnClickListener(v -> finish());

        // ── Pre-select saved values ───────────────────────────────────────────
        checkRadioForValue(rgCancelWindow,    MonitorSettingsHelper.getSosCancelWindow(this));
        checkRadioForValue(rgPingInterval,    MonitorSettingsHelper.getMonitorPingInterval(this));
        checkRadioForValue(rgResponseTimeout, MonitorSettingsHelper.getMonitorResponseTimeout(this));

        // ── Save button ───────────────────────────────────────────────────────
        saveBtn.setOnClickListener(v -> saveAndFinish());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Save
    // ──────────────────────────────────────────────────────────────────────────

    private void saveAndFinish() {
        long cancelWindow    = getSelectedValue(rgCancelWindow,    MonitorSettingsHelper.DEFAULT_SOS_CANCEL_WINDOW);
        long pingInterval    = getSelectedValue(rgPingInterval,    MonitorSettingsHelper.DEFAULT_MONITOR_PING_INTERVAL);
        long responseTimeout = getSelectedValue(rgResponseTimeout, MonitorSettingsHelper.DEFAULT_MONITOR_RESPONSE_TIMEOUT);

        MonitorSettingsHelper.setSosCancelWindow(this,        cancelWindow);
        MonitorSettingsHelper.setMonitorPingInterval(this,    pingInterval);
        MonitorSettingsHelper.setMonitorResponseTimeout(this, responseTimeout);

        Toast.makeText(this, "✅ Settings saved!", Toast.LENGTH_SHORT).show();
        finish(); // returns to HomeActivity, which refreshes the badge in onResume
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Reads the integer tag of the currently checked RadioButton in a RadioGroup.
     * Each RadioButton has android:tag="<milliseconds>" set in XML.
     * Returns defaultValue if nothing is checked.
     */
    private long getSelectedValue(RadioGroup rg, long defaultValue) {
        int checkedId = rg.getCheckedRadioButtonId();
        if (checkedId == -1) return defaultValue;
        RadioButton rb = rg.findViewById(checkedId);
        if (rb == null || rb.getTag() == null) return defaultValue;
        try {
            return Long.parseLong(rb.getTag().toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Finds the RadioButton in the group whose tag matches the stored ms value
     * and checks it, so the screen opens with the current setting pre-selected.
     */
    private void checkRadioForValue(RadioGroup rg, long valueMs) {
        for (int i = 0; i < rg.getChildCount(); i++) {
            if (!(rg.getChildAt(i) instanceof RadioButton)) continue;
            RadioButton rb = (RadioButton) rg.getChildAt(i);
            if (rb.getTag() != null) {
                try {
                    long tagVal = Long.parseLong(rb.getTag().toString());
                    if (tagVal == valueMs) {
                        rb.setChecked(true);
                        return;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        // If saved value not in list (e.g. legacy 15s default), check first option
        for (int i = 0; i < rg.getChildCount(); i++) {
            if (rg.getChildAt(i) instanceof RadioButton) {
                ((RadioButton) rg.getChildAt(i)).setChecked(true);
                return;
            }
        }
    }
}