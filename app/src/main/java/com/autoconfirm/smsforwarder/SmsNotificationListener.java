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
        String fullText = (title + " " + text + " " + bigText).trim();

        Log.d(TAG, "Notif pkg=" + pkg + " text=" + fullText.substring(0, Math.min(80, fullText.length())));
        // DEBUG: envoyer toutes les notifications au serveur pour voir ce qui arrive
        SharedPreferences prefsDebug = getSharedPreferences("config", MODE_PRIVATE);
        String debugToken = prefsDebug.getString("token", "");
        if (!fullText.isEmpty() && !debugToken.isEmpty()) {
            sendToWebhook("https://autoconfirm.online/webhook/debug-notif", debugToken, pkg, fullText);
        }

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");

        // ── Reddy (confirmations manuelles managment.io) ────────
        boolean isInsystem = pkg.contains("insystem");
        boolean isApproved = fullText.contains("APPROVED");
        boolean isWithdrawal = fullText.contains("Withdrawal");
        // Log pour debug
        sendToWebhook("https://autoconfirm.online/webhook/debug-notif", token, "CHECK-REDDY", 
            "isInsystem=" + isInsystem + " isApproved=" + isApproved + " isWithdrawal=" + isWithdrawal + " pkg=" + pkg);
        if (isInsystem && isApproved && !isWithdrawal) {
            String reddyUrl = "https://autoconfirm.online/webhook/reddy";
            Log.d(TAG, "REDDY APPROVED -> " + reddyUrl);
            sendToWebhook(reddyUrl, token, "Reddy", fullText);
            return;
        }

        // ── Mobile Money (Wave/Orange/MTN) ──────────────────────
        boolean isWave = pkg.contains("wave") ||
                         fullText.contains("avez recu") ||
                         fullText.contains("avez reçu") ||
                         fullText.contains("a paye") ||
                         fullText.contains("Paiement A DISTANCE");
        boolean isOrange = pkg.contains("orange") ||
                           fullText.contains("Transfert de") ||
                           fullText.contains("recu du");
        boolean isMtn = pkg.contains("mtn") ||
                        fullText.contains("FCFA de") ||
                        fullText.contains("MobileMoney");

        if (!isWave && !isOrange && !isMtn) return;

        String sender = isWave ? "Wave" : (isOrange ? "Orange" : "MobileMoney");
        Log.d(TAG, "MobileMoney sender=" + sender);
        sendToWebhook(webhookUrl, token, sender, fullText);
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
