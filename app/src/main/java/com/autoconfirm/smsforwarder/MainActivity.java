package com.autoconfirm.smsforwarder;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private EditText etUrl, etToken, etSender1, etSender2, etSender3;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);

        setContentView(R.layout.activity_main);
        etUrl = findViewById(R.id.etUrl);
        etToken = findViewById(R.id.etToken);
        etSender1 = findViewById(R.id.etSender1);
        etSender2 = findViewById(R.id.etSender2);
        etSender3 = findViewById(R.id.etSender3);

        etUrl.setText(prefs.getString("webhook_url", "https://autoconfirm.online/webhook/saas"));
        etToken.setText(prefs.getString("token", ""));
        etSender1.setText(prefs.getString("sender1", "Wave"));
        etSender2.setText(prefs.getString("sender2", "Orange"));
        etSender3.setText(prefs.getString("sender3", "MTN"));

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("webhook_url", etUrl.getText().toString().trim())
                .putString("token", etToken.getText().toString().trim())
                .putString("sender1", etSender1.getText().toString().trim())
                .putString("sender2", etSender2.getText().toString().trim())
                .putString("sender3", etSender3.getText().toString().trim())
                .apply();
            Toast.makeText(this, "Configuration sauvegardee!", Toast.LENGTH_SHORT).show();
            startService();
        });

        Button btnStart = findViewById(R.id.btnStart);
        btnStart.setOnClickListener(v -> startService());

        requestPermissions();
        startService();
    }

    private void startService() {
        Intent service = new Intent(this, SmsService.class);
        startForegroundService(service);
        Toast.makeText(this, "Service demarre!", Toast.LENGTH_SHORT).show();
    }

    private void requestPermissions() {
        String[] perms = {Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS};
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 1);
                break;
            }
        }
    }
}
