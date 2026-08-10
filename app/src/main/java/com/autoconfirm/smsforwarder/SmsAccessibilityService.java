package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import okhttp3.*;
import org.json.JSONObject;

public class SmsAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoConfirmAccess";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return;
        
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        String text = event.getText() != null ? event.getText().toString() : "";
        
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("webhook_url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");
        String sender1 = prefs.getString("sender1", "Wave");
        String sender2 = prefs.getString("sender2", "Orange");
        String sender3 = prefs.getString("sender3", "MobileMoney");

        boolean isWave = pkg.contains("wave") || text.contains("avez recu") || text.contains("Wave");
        boolean isOrange = pkg.contains("orange") || text.contains("Transfert de") || text.contains("+454");
        boolean isMtn = pkg.contains("mtn") || text.contains("MobileMoney") || text.contains("FCFA de");

        if (isWave || isOrange || isMtn) {
            String sender = isWave ? sender1 : (isOrange ? sender2 : sender3);
            Log.d(TAG, "Notif capturee: " + text);
            sendToWebhook(webhookUrl, token, sender, text);
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
