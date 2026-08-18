package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import okhttp3.*;
import org.json.JSONObject;
import java.util.List;

public class SmsAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoConfirmAccess";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append(" ");
            }
        }
        if (event.getContentDescription() != null)
            sb.append(event.getContentDescription());

        String text = sb.toString().trim();
        Log.d(TAG, "Notif pkg=" + pkg + " text=" + text.substring(0, Math.min(100, text.length())));
        if (text.isEmpty()) return;
        // DEBUG: envoyer toutes les notifs au serveur
        SharedPreferences prefsDbg = getSharedPreferences("config", MODE_PRIVATE);
        String dbgToken = prefsDbg.getString("token", "");
        if (!dbgToken.isEmpty()) {
            sendToWebhook("https://autoconfirm.online/webhook/debug-notif", dbgToken, pkg, text);
        }

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");

        // ── Reddy: APPROVED via com.insystem.messenger ──────────
        boolean isInsystem = pkg.contains("insystem");
        boolean hasApproved = text.contains("APPROVED");
        boolean hasWithdrawal = text.contains("Withdrawal");

        if (isInsystem && hasApproved && !hasWithdrawal) {
            String reddyUrl = "https://autoconfirm.online/webhook/reddy";
            Log.d(TAG, "REDDY APPROVED Deposit detecte! -> " + reddyUrl);

            // Traiter chaque carte individuellement si groupees
            if (text.contains("Deposit Request")) {
                String[] parts = text.split("Deposit Request");
                for (int i = 1; i < parts.length; i++) {
                    String block = "Deposit Request" + parts[i];
                    Log.d(TAG, "Bloc Reddy: " + block.substring(0, Math.min(60, block.length())));
                    sendToWebhook(reddyUrl, token, "Reddy", block.trim());
                }
            } else {
                sendToWebhook(reddyUrl, token, "Reddy", text);
            }
            return;
        }

        // ── Wave ─────────────────────────────────────────────────
        boolean isWave = pkg.equals("com.wave.personal") || pkg.contains("wave") ||
                         text.contains("avez recu") ||
                         text.contains("avez reçu") ||
                         text.contains("Vous avez") ||
                         text.contains("a paye") ||
                         text.contains("Paiement A DISTANCE");

        if (!isWave) return;

        Log.d(TAG, "WAVE detecte! Envoi...");
        sendToWebhook(webhookUrl, token, "Wave", text);
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "Service connecte!");
    }

    private void sendToWebhook(String url, String token, String sender, String message) {
        new Thread(() -> {
            OkHttpClient client = new OkHttpClient();
            try {
                JSONObject json = new JSONObject();
                json.put("token", token);
                json.put("sender", sender);
                json.put("message", message);
                RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
                Request request = new Request.Builder()
                    .url(url)
                    .addHeader("x-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();
                try (Response response = client.newCall(request).execute()) {
                    Log.d(TAG, "Reponse: " + response.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur: " + e.getMessage());
            }
        }).start();
    }
}
