package com.rescueconnect;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;


public class SettingsActivity extends AppCompatActivity {

    EditText changeEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        changeEdit= findViewById(R.id.changeemergencyEditText);
    }

    public void changeImplementation(View view) {
        String changemessageString=changeEdit.getText().toString();

        SPHelper.SaveData(SettingsActivity.this, Constants.SP_EMERGENCY_MESSAGE, changemessageString);

        Toast.makeText(SettingsActivity.this,"Message changed successfully",Toast.LENGTH_LONG).show();
    }
}
