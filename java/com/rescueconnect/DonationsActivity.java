package com.rescueconnect;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class DonationsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_donations);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom);
            return insets;
        });

        // Raise funds button
        findViewById(R.id.btnRaiseFunds).setOnClickListener(v ->
                startActivity(new Intent(this, RaiseFundsActivity.class)));

        // Donate button
        findViewById(R.id.btnDonate).setOnClickListener(v ->
                startActivity(new Intent(this, DonateActivity.class)));

        // Card clicks also work
        findViewById(R.id.cardRaiseFunds).setOnClickListener(v ->
                startActivity(new Intent(this, RaiseFundsActivity.class)));

        findViewById(R.id.cardDonate).setOnClickListener(v ->
                startActivity(new Intent(this, DonateActivity.class)));
    }
}