package com.autoconfirm.smsforwarder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.*;
import java.util.List;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout contentArea;
    private Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // Couleurs
    private static final int COLOR_BG = 0xFF0f172a;
    private static final int COLOR_CARD = 0xFF1e293b;
    private static final int COLOR_PRIMARY = 0xFF6366f1;
    private static final int COLOR_GREEN = 0xFF10b981;
    private static final int COLOR_YELLOW = 0xFFf59e0b;
    private static final int COLOR_RED = 0xFFef4444;
    private static final int COLOR_TEXT = 0xFFf1f5f9;
    private static final int COLOR_MUTED = 0xFF94a3b8;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_PRIVATE);
        
        // Layout principal
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, getStatusBarHeight(), 0, 0);
        
        // Header
        root.addView(buildHeader());
        
        // Tabs
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(COLOR_CARD);
        
        String[] tabNames = {"⚡ Dashboard", "⚙️ Config", "💸 Retraits"};
        int[] tabIds = {1, 2, 3};
        
        for (int i = 0; i < tabNames.length; i++) {
            Button tab = new Button(this);
            tab.setText(tabNames[i]);
            tab.setTextSize(12);
            tab.setPadding(8, 16, 8, 16);
            tab.setBackgroundColor(i == 0 ? COLOR_PRIMARY : Color.TRANSPARENT);
            tab.setTextColor(COLOR_TEXT);
            tab.setAllCaps(false);
            tab.setStateListAnimator(null);
            final int tabIndex = i;
            tab.setOnClickListener(v -> showTab(tabIndex, tabs, root));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            tabs.addView(tab, lp);
        }
        root.addView(tabs);
        
        // Zone de contenu
        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        contentArea.setPadding(16, 16, 16, 16);
        root.addView(contentArea);
        
        scroll.addView(root);
        setContentView(scroll);
        
        showTab(0, tabs, root);
        startAutoRefresh();
        requestAllPermissions();
    }

    private void showTab(int index, LinearLayout tabs, LinearLayout root) {
        // Mettre a jour les couleurs des tabs
        for (int i = 0; i < tabs.getChildCount(); i++) {
            Button t = (Button) tabs.getChildAt(i);
            t.setBackgroundColor(i == index ? COLOR_PRIMARY : Color.TRANSPARENT);
        }
        contentArea.removeAllViews();
        switch (index) {
            case 0: buildDashboard(); break;
            case 1: buildConfig(); break;
            case 2: buildRetraits(); break;
        }
    }

    // ─── DASHBOARD ──────────────────────────────────────────────
    private void buildDashboard() {
        contentArea.addView(sectionTitle("📊 Dépôts du jour"));
        
        // Stats
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(android.view.Gravity.CENTER);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(4, 0, 4, 0);
        
        TextView statBot = statCard("🤖 Bot", "...", COLOR_GREEN);
        TextView statManuel = statCard("👤 Manuel", "...", COLOR_YELLOW);
        TextView statTotal = statCard("💰 Total", "...", COLOR_PRIMARY);
        
        stats.addView(wrapStat(statBot), lp);
        stats.addView(wrapStat(statManuel), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        stats.addView(wrapStat(statTotal), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        contentArea.addView(stats);
        
        // Liste depots
        contentArea.addView(spacer(12));
        contentArea.addView(sectionTitle("📋 Derniers dépôts"));
        
        LinearLayout depotsList = new LinearLayout(this);
        depotsList.setOrientation(LinearLayout.VERTICAL);
        depotsList.setTag("depots_list");
        contentArea.addView(depotsList);
        
        loadDepotsFromServer(depotsList, statBot, statManuel, statTotal);
    }

    private void loadDepotsFromServer(LinearLayout list, TextView statBot, TextView statManuel, TextView statTotal) {
        String token = prefs.getString("token", "");
        String url = prefs.getString("url", "https://autoconfirm.online/webhook/saas")
            .replace("/webhook/saas", "/api/user/confirmes");
        
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                Request req = new Request.Builder()
                    .url(url)
                    .addHeader("x-token", token)
                    .build();
                Response resp = client.newCall(req).execute();
                String body = resp.body().string();
                resp.close();
                JSONArray arr = new JSONArray(body);
                
                int botCount = 0, manuelCount = 0, totalF = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.getJSONObject(i);
                    String source = d.optString("source", "bot");
                    int montant = d.optInt("montant", 0);
                    totalF += montant;
                    if ("human".equals(source)) manuelCount++;
                    else botCount++;
                }
                
                final int fc = botCount, mc = manuelCount, tot = totalF;
                final JSONArray data = arr;
                
                runOnUiThread(() -> {
                    statBot.setText("🤖 Bot\n" + fc);
                    statManuel.setText("👤 Manuel\n" + mc);
                    statTotal.setText("💰 " + tot + "F");
                    
                    list.removeAllViews();
                    int limit = Math.min(data.length(), 15);
                    for (int i = 0; i < limit; i++) {
                        try {
                            JSONObject d = data.getJSONObject(i);
                            list.addView(depotRow(d));
                        } catch(Exception e) {}
                    }
                });
            } catch(Exception e) {
                runOnUiThread(() -> {
                    TextView err = new TextView(this);
                    err.setText("Erreur: " + e.getMessage());
                    err.setTextColor(COLOR_RED);
                    list.addView(err);
                });
            }
        }).start();
    }

    private View depotRow(JSONObject d) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(COLOR_CARD);
        row.setPadding(12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 4, 0, 4);
        row.setLayoutParams(lp);
        
        try {
            String source = d.optString("source", "bot");
            String badge = "human".equals(source) ? "👤" : "🤖";
            String phone = d.optString("phone", "—");
            int montant = d.optInt("montant", 0);
            String reseau = d.optString("reseau", "—");
            String compte = d.optString("compte_code", "—");
            
            TextView badgeView = new TextView(this);
            badgeView.setText(badge);
            badgeView.setTextSize(18);
            badgeView.setPadding(0, 0, 8, 0);
            row.addView(badgeView);
            
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            info.setLayoutParams(infoLp);
            
            TextView phoneView = new TextView(this);
            phoneView.setText(phone);
            phoneView.setTextColor(COLOR_TEXT);
            phoneView.setTextSize(13);
            info.addView(phoneView);
            
            TextView compteView = new TextView(this);
            compteView.setText(compte + " • " + reseau);
            compteView.setTextColor(COLOR_MUTED);
            compteView.setTextSize(11);
            info.addView(compteView);
            
            row.addView(info);
            
            TextView montantView = new TextView(this);
            montantView.setText(montant + "F");
            montantView.setTextColor(COLOR_GREEN);
            montantView.setTextSize(14);
            montantView.setTypeface(null, android.graphics.Typeface.BOLD);
            row.addView(montantView);
        } catch(Exception e) {}
        
        return row;
    }

    // ─── CONFIG ─────────────────────────────────────────────────
    private void buildConfig() {
        contentArea.addView(sectionTitle("⚙️ Configuration"));
        
        contentArea.addView(label("URL Serveur"));
        EditText etUrl = inputField(prefs.getString("url", "https://autoconfirm.online/webhook/saas"));
        contentArea.addView(etUrl);
        
        contentArea.addView(spacer(8));
        contentArea.addView(label("Token"));
        EditText etToken = inputField(prefs.getString("token", ""));
        contentArea.addView(etToken);
        
        contentArea.addView(spacer(8));
        Button btnSave = actionButton("💾 Sauvegarder", COLOR_PRIMARY);
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("url", etUrl.getText().toString().trim())
                .putString("token", etToken.getText().toString().trim())
                .apply();
            Toast.makeText(this, "✅ Sauvegardé!", Toast.LENGTH_SHORT).show();
        });
        contentArea.addView(btnSave);
        
        contentArea.addView(spacer(16));
        contentArea.addView(sectionTitle("🔔 Notifications"));
        
        Switch swDismiss = new Switch(this);
        swDismiss.setText("Balayer notifications auto (1 min)");
        swDismiss.setTextColor(COLOR_TEXT);
        swDismiss.setChecked(prefs.getBoolean("auto_dismiss_notif", false));
        swDismiss.setOnCheckedChangeListener((b, checked) -> 
            prefs.edit().putBoolean("auto_dismiss_notif", checked).apply());
        contentArea.addView(swDismiss);
        
        contentArea.addView(spacer(16));
        contentArea.addView(sectionTitle("🔑 Services"));
        
        Button btnNotif = actionButton("🔔 Activer Notifications", COLOR_GREEN);
        btnNotif.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        contentArea.addView(btnNotif);
        
        contentArea.addView(spacer(8));
        Button btnAccess = actionButton("♿ Activer Accessibilité", COLOR_YELLOW);
        btnAccess.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        contentArea.addView(btnAccess);
        
        contentArea.addView(spacer(8));
        Button btnStart = actionButton("▶️ Démarrer Service", COLOR_GREEN);
        btnStart.setOnClickListener(v -> {
            Intent svc = new Intent(this, SmsService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this, "Service démarré!", Toast.LENGTH_SHORT).show();
        });
        contentArea.addView(btnStart);
    }

    // ─── RETRAITS ────────────────────────────────────────────────
    private void buildRetraits() {
        contentArea.addView(sectionTitle("💸 Retraits Wave"));
        
        contentArea.addView(label("Code PIN Wave"));
        EditText etPin = new EditText(this);
        etPin.setHint("ex: 1234");
        etPin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | 
                          android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etPin.setText(prefs.getString("wave_pin", ""));
        etPin.setBackgroundColor(COLOR_CARD);
        etPin.setTextColor(COLOR_TEXT);
        etPin.setHintTextColor(COLOR_MUTED);
        etPin.setPadding(16, 12, 16, 12);
        contentArea.addView(etPin);
        
        contentArea.addView(spacer(8));
        Button btnSavePin = actionButton("💾 Sauvegarder PIN", COLOR_PRIMARY);
        btnSavePin.setOnClickListener(v -> {
            prefs.edit().putString("wave_pin", etPin.getText().toString().trim()).apply();
            Toast.makeText(this, "PIN sauvegardé!", Toast.LENGTH_SHORT).show();
        });
        contentArea.addView(btnSavePin);
        
        contentArea.addView(spacer(16));
        
        Switch swWithdraw = new Switch(this);
        swWithdraw.setText("Activer retraits automatiques");
        swWithdraw.setTextColor(COLOR_TEXT);
        swWithdraw.setChecked(prefs.getBoolean("wave_withdrawal_enabled", false));
        swWithdraw.setOnCheckedChangeListener((b, checked) ->
            prefs.edit().putBoolean("wave_withdrawal_enabled", checked).apply());
        contentArea.addView(swWithdraw);
        
        contentArea.addView(spacer(8));
        
        TextView tvRetrait = new TextView(this);
        tvRetrait.setText("En attente...");
        tvRetrait.setTextColor(COLOR_MUTED);
        tvRetrait.setTextSize(12);
        contentArea.addView(tvRetrait);
        
        contentArea.addView(spacer(8));
        Button btnWithdraw = actionButton("⚡ Traiter Retraits Wave", COLOR_YELLOW);
        btnWithdraw.setOnClickListener(v -> {
            if (!prefs.getBoolean("wave_withdrawal_enabled", false)) {
                Toast.makeText(this, "Active le switch d abord!", Toast.LENGTH_SHORT).show();
                return;
            }
            tvRetrait.setText("Chargement...");
            String token = prefs.getString("token", "");
            String url = prefs.getString("url", "https://autoconfirm.online/webhook/saas")
                .replace("/webhook/saas", "/api/withdrawals/pending");
            new Thread(() -> {
                try {
                    OkHttpClient client = new OkHttpClient();
                    Request req = new Request.Builder().url(url)
                        .addHeader("x-token", token).get().build();
                    Response resp = client.newCall(req).execute();
                    String body = resp.body().string();
                    resp.close();
                    JSONObject json = new JSONObject(body);
                    JSONArray retraits = json.optJSONArray("retraits");
                    if (retraits != null && retraits.length() > 0) {
                        JSONObject first = retraits.getJSONObject(0);
                        WaveWithdrawalService.pendingPhone = first.optString("phone", "");
                        WaveWithdrawalService.pendingNom = first.optString("nom", "Client");
                        WaveWithdrawalService.pendingMontant = first.optLong("montant", 0);
                        WaveWithdrawalService.pendingWithdrawalId = first.optString("id", "");
                        WaveWithdrawalService.pendingUserId = first.optString("user_id", "");
                        WaveWithdrawalService.pendingSubagentId = first.optLong("subagent_id", 0);
                        WaveWithdrawalService.pendingRefId = first.optLong("ref_id", 0);
                        WaveWithdrawalService.triggerWithdrawal = true;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            Intent waveIntent = getPackageManager().getLaunchIntentForPackage("com.wave.personal");
                            if (waveIntent != null) {
                                waveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(waveIntent);
                            }
                        }, 500);
                        runOnUiThread(() -> tvRetrait.setText("✅ Retrait: " + WaveWithdrawalService.pendingPhone + 
                            " • " + WaveWithdrawalService.pendingMontant + "F"));
                    } else {
                        runOnUiThread(() -> tvRetrait.setText("Aucun retrait en attente"));
                    }
                } catch(Exception e) {
                    runOnUiThread(() -> tvRetrait.setText("❌ Erreur: " + e.getMessage()));
                }
            }).start();
        });
        contentArea.addView(btnWithdraw);
    }

    // ─── AUTO REFRESH ─────────────────────────────────────────────
    private void startAutoRefresh() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                // Refresh si on est sur le dashboard
                refreshHandler.postDelayed(this, 30000);
            }
        };
        refreshHandler.postDelayed(refreshRunnable, 30000);
    }

    // ─── UI HELPERS ───────────────────────────────────────────────
    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(COLOR_CARD);
        header.setPadding(20, 16, 20, 16);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        
        TextView title = new TextView(this);
        title.setText("⚡ AutoConfirm V2");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        title.setLayoutParams(lp);
        header.addView(title);
        
        TextView version = new TextView(this);
        version.setText("v2.0");
        version.setTextColor(COLOR_MUTED);
        version.setTextSize(12);
        header.addView(version);
        
        return header;
    }

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(15);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_MUTED);
        tv.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText inputField(String value) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setBackgroundColor(COLOR_CARD);
        et.setTextColor(COLOR_TEXT);
        et.setPadding(16, 12, 16, 12);
        et.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return et;
    }

    private Button actionButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setBackgroundColor(color);
        btn.setTextColor(COLOR_TEXT);
        btn.setAllCaps(false);
        btn.setStateListAnimator(null);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private TextView statCard(String label, String value, int color) {
        TextView tv = new TextView(this);
        tv.setText(label + "\n" + value);
        tv.setTextColor(color);
        tv.setTextSize(13);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private LinearLayout wrapStat(TextView tv) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setBackgroundColor(COLOR_CARD);
        wrap.setPadding(8, 12, 8, 12);
        wrap.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(4, 0, 4, 0);
        wrap.setLayoutParams(lp);
        wrap.addView(tv);
        return wrap;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp));
        return v;
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) result = getResources().getDimensionPixelSize(resourceId);
        return result;
    }

    private void requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = {Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS};
            ActivityCompat.requestPermissions(this, perms, 1);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacks(refreshRunnable);
    }
}
