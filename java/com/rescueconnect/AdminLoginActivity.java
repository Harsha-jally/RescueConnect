package com.rescueconnect;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class AdminLoginActivity extends AppCompatActivity {

    // ── Hardcoded admin credentials — not stored in any database ─────────────
    private static final String ADMIN_ID       = "admin";
    private static final String ADMIN_PASSWORD = "rescue@123";

    EditText adminIdEdit, adminPasswordEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_admin_login);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        adminIdEdit       = findViewById(R.id.adminIdEdit);
        adminPasswordEdit = findViewById(R.id.adminPasswordEdit);
    }

    public void adminLoginImplementation(View view) {
        String id       = adminIdEdit.getText().toString().trim();
        String password = adminPasswordEdit.getText().toString().trim();

        if (id.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Enter admin ID and password", Toast.LENGTH_SHORT).show();
            return;
        }

        if (id.equals(ADMIN_ID) && password.equals(ADMIN_PASSWORD)) {
            startActivity(new Intent(this, AdminDashboardActivity.class));
            finish(); // prevent back navigation to login
        } else {
            Toast.makeText(this, "Invalid admin credentials", Toast.LENGTH_LONG).show();
            adminPasswordEdit.setText("");
        }
    }
}