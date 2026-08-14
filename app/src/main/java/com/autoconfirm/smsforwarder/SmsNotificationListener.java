package com.autoconfirm.smsforwarder;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;

public class SmsNotificationListener extends NotificationListenerService {
    private static final String TAG = "AutoConfirmNotif";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Bundle extras = sbn.getNotification().extras;
        
        String title = extras.getString("android.title", "");
        String text = extras.getCharSequence("android.text", "").toString();
        String bigText = extras.getString("android.bigText", "");
        
        String fullText = title + " " + text + " " + bigText;
        
        Log.d(TAG, "Notif: pkg=" + pkg + " title=" + title + " text=" + text);

        boolean isWave = pkg.contains("wave") || 
                         fullText.contains("avez recu") ||
                         fullText.contains("avez reçu") ||
                         fullText.contains("Transfert recu");

        boolean isOrange = pkg.contains("orange") ||
                           fullText.contains("Transfert de") ||
                           fullText.contains("recu du");

        boolean isMtn = pkg.contains("mtn") ||
                        fullText.contains("FCFA de") ||
                        fullText.contains("MobileMoney");

        boolean isReddy = pkg.contains("reddy") ||
                          fullText.contains("Reddy") ||
                          fullText.contains("reddy");

        if (!isWave && !isOrange && !isMtn && !isReddy) return;

        String sender = isWave ? "Wave" : (isOrange ? "Orange" : (isMtn ? "MobileMoney" : "Reddy"));
        Log.d(TAG, "Mobile Money detecte! sender=" + sender + " msg=" + fullText);

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");

        sendToWebhook(webhookUrl, token, sender, fullText.trim());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {}

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
