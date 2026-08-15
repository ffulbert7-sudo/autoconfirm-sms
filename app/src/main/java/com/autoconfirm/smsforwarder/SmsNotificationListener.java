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

        Log.d(TAG, "Notif pkg=" + pkg + " title=" + title);

        SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
        String webhookUrl = prefs.getString("url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");

        // ── Reddy / Web Management Bot ──────────────────────────
        boolean isApproved = fullText.contains("APPROVED");
        boolean isDeposit = fullText.contains("Deposit Request");
        boolean isWithdrawal = fullText.contains("Withdrawal Request");

        if (isApproved && isDeposit && !isWithdrawal) {
            // Traiter chaque carte individuellement (notifications groupees)
            // Le bigText peut contenir plusieurs blocs separes par newline
            String[] lines = fullText.split("\n");
            StringBuilder currentBlock = new StringBuilder();
            for (String line : lines) {
                currentBlock.append(line).append("\n");
                // Quand on a un bloc complet (contient Amount)
                if (line.contains("Amount:") || line.contains("XOF")) {
                    String block = currentBlock.toString();
                    if (block.contains("APPROVED") && block.contains("Deposit Request")) {
                        Log.d(TAG, "REDDY APPROVED Deposit: " + block.substring(0, Math.min(80, block.length())));
                        String reddyUrl = webhookUrl.replace("/webhook/saas", "/webhook/reddy");
                        sendToWebhook(reddyUrl, token, "Reddy", block.trim());
                    }
                    currentBlock = new StringBuilder();
                }
            }
            // Si le bloc entier est une seule carte
            if (currentBlock.length() > 0 && fullText.contains("APPROVED")) {
                String reddyUrl = webhookUrl.replace("/webhook/saas", "/webhook/reddy");
                sendToWebhook(reddyUrl, token, "Reddy", fullText);
            }
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
                    Log.d(TAG, "Reponse " + url + ": " + response.code());
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur: " + e.getMessage());
            }
        }).start();
    }
}
