package com.autoconfirm.smsforwarder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        buildUI();
        requestAllPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setBackgroundColor(0xFFF0F4F8);

        TextView title = new TextView(this);
        title.setText("AutoConfirm SMS");
        title.setTextSize(24); title.setTextColor(0xFF1a237e);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Capture SMS Wave / Orange / MTN");
        sub.setTextSize(13); sub.setTextColor(0xFF64748b);
        sub.setPadding(0,4,0,20);
        root.addView(sub);

        tvStatus = new TextView(this);
        tvStatus.setPadding(20,16,20,16);
        tvStatus.setTextSize(13);
        root.addView(tvStatus);
        root.addView(spacer(20));

        root.addView(label("URL Webhook"));
        EditText etUrl = input("https://autoconfirm.online/webhook/saas");
        etUrl.setText(prefs.getString("url","https://autoconfirm.online/webhook/saas"));
        root.addView(etUrl);
        root.addView(spacer(12));

        root.addView(label("Token"));
        EditText etToken = input("ex: linebet2026secret");
        etToken.setText(prefs.getString("token",""));
        root.addView(etToken);
        root.addView(spacer(12));

        root.addView(label("Expediteurs (separes par virgule)"));
        EditText etSenders = input("Wave,Orange,MobileMoney,+454,MTN");
        etSenders.setText(prefs.getString("senders","Wave,Orange,MobileMoney,+454,MTN"));
        root.addView(etSenders);
        root.addView(spacer(20));

        Button btnSave = btn("Sauvegarder", 0xFF1a237e);
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("url", etUrl.getText().toString().trim())
                .putString("token", etToken.getText().toString().trim())
                .putString("senders", etSenders.getText().toString().trim())
                .apply();
            Toast.makeText(this,"Sauvegarde!",Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);
        root.addView(spacer(10));

        Button btnAccess = btn("1. Autoriser Accessibilite", 0xFFf59e0b);
        btnAccess.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(btnAccess);
        root.addView(spacer(10));

        Button btnNotif = btn("2. Autoriser Acces Notifications", 0xFF8b5cf6);
        btnNotif.setOnClickListener(v ->
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));
        root.addView(btnNotif);
        root.addView(spacer(10));

        Button btnStart = btn("Demarrer le Service", 0xFF10b981);
        btnStart.setOnClickListener(v -> {
            Intent svc = new Intent(this, SmsService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else startService(svc);
            updateStatus();
        });
        root.addView(btnStart);
        root.addView(spacer(16));

        // ── Section Retraits Wave ─────────────────────────────
        TextView titleWave = label("── Retraits Wave CI ──");
        titleWave.setTextSize(14);
        root.addView(titleWave);
        root.addView(spacer(8));

        root.addView(label("Code PIN Wave"));
        EditText etPin = new EditText(this);
        etPin.setHint("ex: 1234");
        etPin.setPadding(16,12,16,12);
        etPin.setBackgroundColor(0xFFFFFFFF);
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setText(prefs.getString("wave_pin",""));
        root.addView(etPin);
        root.addView(spacer(8));

        Button btnSavePin = btn("Sauvegarder PIN Wave", 0xFF6366f1);
        btnSavePin.setOnClickListener(v -> {
            prefs.edit().putString("wave_pin", etPin.getText().toString().trim()).apply();
            Toast.makeText(this,"PIN sauvegarde!",Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSavePin);
        root.addView(spacer(8));

        Button btnWithdraw = btn("Traiter Retraits Wave", 0xFFf59e0b);
        btnWithdraw.setOnClickListener(v -> {
            tvStatus.setText("Chargement retraits en attente...");
            new Thread(() -> {
                try {
                    String url = prefs.getString("url","https://autoconfirm.online/webhook/saas")
                        .replace("/webhook/saas","/api/withdrawals/pending");
                    String token = prefs.getString("token","");
                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(url).addHeader("x-token", token).get().build();
                    okhttp3.Response resp = client.newCall(req).execute();
                    String body = resp.body().string();
                    resp.close();
                    org.json.JSONObject json = new org.json.JSONObject(body);
                    org.json.JSONArray retraits = json.optJSONArray("retraits");
                    if (retraits != null && retraits.length() > 0) {
                        org.json.JSONObject first = retraits.getJSONObject(0);
                        WaveWithdrawalService.pendingPhone = first.optString("phone","");
                        WaveWithdrawalService.pendingNom = first.optString("nom","Client");
                        WaveWithdrawalService.pendingMontant = first.optLong("montant",0);
                        WaveWithdrawalService.pendingWithdrawalId = first.optString("id","");
                        WaveWithdrawalService.triggerWithdrawal = true;
                        // Ouvrir Wave
                        android.content.Intent waveIntent = getPackageManager().getLaunchIntentForPackage("com.wave.personal");
                        if (waveIntent != null) {
                            waveIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(waveIntent);
                        }
                        runOnUiThread(() -> tvStatus.setText("Retrait en cours: " + WaveWithdrawalService.pendingPhone + " " + WaveWithdrawalService.pendingMontant + "F"));
                    } else {
                        runOnUiThread(() -> tvStatus.setText("Aucun retrait en attente"));
                    }
                } catch(Exception e) {
                    runOnUiThread(() -> tvStatus.setText("Erreur: " + e.getMessage()));
                }
            }).start();
        });
        root.addView(btnWithdraw);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void updateStatus() {
        boolean ok = isAccessibilityEnabled();
        tvStatus.setText(ok ?
            "Service actif - SMS captures en arriere-plan" :
            "ATTENTION: Accessibilite non activee - Clique sur le bouton orange");
        tvStatus.setBackgroundColor(ok ? 0xFFdcfce7 : 0xFFfef3c7);
        tvStatus.setTextColor(ok ? 0xFF166534 : 0xFF92400e);
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo s : services)
            if (s.getId().contains(getPackageName())) return true;
        return false;
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, 1000);
    }

    private Button btn(String text, int color) {
        Button b = new Button(this);
        b.setText(text); b.setBackgroundColor(color); b.setTextColor(0xFFFFFFFF);
        return b;
    }

    private EditText input(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint); et.setPadding(16,12,16,12); et.setBackgroundColor(0xFFFFFFFF);
        return et;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextColor(0xFF1a237e); tv.setTextSize(13);
        return tv;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, h));
        return v;
    }
}
