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
    private static final String REDDY_URL = "https://autoconfirm.online/webhook/reddy";
    private static final String SAAS_URL_DEFAULT = "https://autoconfirm.online/webhook/saas";

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
        String text = sb.toString().trim();
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String token = prefs.getString("token", "");
        String webhookUrl = prefs.getString("url", SAAS_URL_DEFAULT);

        Log.d(TAG, "pkg=" + pkg + " text=" + text.substring(0, Math.min(80, text.length())));

        // ── Reddy ────────────────────────────────────────────────
        if (pkg.contains("insystem") && text.contains("APPROVED") && !text.contains("Withdrawal")) {
            Log.d(TAG, ">>> REDDY APPROVED - envoi vers " + REDDY_URL);
            send(REDDY_URL, token, "Reddy", text);
            return;
        }

        // ── Wave ─────────────────────────────────────────────────
        if (pkg.contains("wave") || text.contains("avez recu") || text.contains("avez reçu") ||
            text.contains("Vous avez") || text.contains("a paye") || text.contains("Paiement A DISTANCE")) {
            Log.d(TAG, ">>> WAVE - envoi vers " + webhookUrl);
            send(webhookUrl, token, "Wave", text);
        }
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

    private void send(String url, String token, String sender, String message) {
        new Thread(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                JSONObject json = new JSONObject();
                json.put("token", token);
                json.put("sender", sender);
                json.put("message", message);
                RequestBody body = RequestBody.create(json.toString(), JSON_TYPE);
                Request req = new Request.Builder()
                    .url(url).addHeader("x-token", token)
                    .addHeader("Content-Type", "application/json")
                    .post(body).build();
                Response resp = client.newCall(req).execute();
                Log.d(TAG, "Reponse " + url + ": " + resp.code());
                resp.close();
            } catch (Exception e) {
                Log.e(TAG, "Erreur: " + e.getMessage());
            }
        }).start();
    }
}
