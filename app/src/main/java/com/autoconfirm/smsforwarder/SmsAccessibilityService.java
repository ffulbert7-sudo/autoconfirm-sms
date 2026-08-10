package com.autoconfirm.smsforwarder;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import okhttp3.*;
import org.json.JSONObject;
import java.util.List;

public class SmsAccessibilityService extends AccessibilityService {
    private static final String TAG = "AutoConfirmAccess";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return;

        // Recuperer le texte de l event
        StringBuilder sb = new StringBuilder();
        List<CharSequence> texts = event.getText();
        if (texts != null) {
            for (CharSequence t : texts) {
                if (t != null) sb.append(t).append(" ");
            }
        }
        // Essayer aussi contentDescription
        if (event.getContentDescription() != null)
            sb.append(event.getContentDescription());

        String text = sb.toString().trim();
        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";

        Log.d(TAG, "Event pkg=" + pkg + " text=" + text.substring(0, Math.min(50, text.length())));

        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");
        String sendersStr = prefs.getString("senders", "Wave,Orange,MobileMoney,+454,MTN");
        String[] sendersList = sendersStr.split(",");

        // Detecter si c est un SMS Mobile Money
        boolean isMobileMoney =
            text.contains("avez recu") || text.contains("Transfert de") ||
            text.contains("FCFA de") || text.contains("recu du") ||
            pkg.contains("wave") || pkg.contains("orange") || pkg.contains("mtn");

        if (!isMobileMoney) return;

        // Determiner l expediteur
        String sender = "Wave";
        if (text.contains("Transfert de") || text.contains("recu du") || pkg.contains("orange"))
            sender = "Orange";
        else if (text.contains("FCFA de") || pkg.contains("mtn"))
            sender = "MobileMoney";

        Log.d(TAG, "Mobile Money detecte! sender=" + sender);
        sendToWebhook(webhookUrl, token, sender, text);
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
        Log.d(TAG, "Accessibility Service connecte!");
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
                    Log.d(TAG, "Reponse: " + response.code() + " " + response.body().string());
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur: " + e.getMessage());
            }
        }).start();
    }
}
