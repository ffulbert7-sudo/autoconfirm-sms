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


    private android.os.Handler dismissHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable dismissRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
                if (prefs.getBoolean("auto_dismiss_notif", false)) {
                    cancelAllNotifications();
                    Log.d(TAG, "Notifications balayees");
                }
            } catch(Exception e) {}
            dismissHandler.postDelayed(this, 60 * 1000);
        }
    };

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        dismissHandler.postDelayed(dismissRunnable, 60 * 1000);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString("android.title", "");
        String text = extras.getCharSequence("android.text", "").toString();
        String bigText = extras.getString("android.bigText", "");
        // Extraire aussi les messages individuels de la notification groupee
        StringBuilder sbLines = new StringBuilder();
        android.os.Parcelable[] messages = extras.getParcelableArray("android.messages");
        if (messages != null) {
            for (android.os.Parcelable msg : messages) {
                if (msg instanceof android.os.Bundle) {
                    CharSequence lineText = ((android.os.Bundle) msg).getCharSequence("text");
                    if (lineText != null) sbLines.append(lineText).append(" ");
                }
            }
        }
        String fullText = (title + " " + text + " " + bigText + " " + sbLines.toString()).trim();

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
        boolean isRejected = fullText.contains("REJECTED") || fullText.contains("CANCELED") || fullText.contains("SENT");
        boolean isDepositRequest = fullText.contains("Deposit Request");
        // Log pour debug
        sendToWebhook("https://autoconfirm.online/webhook/debug-notif", token, "CHECK-REDDY", 
            "isInsystem=" + isInsystem + " isApproved=" + isApproved + " isWithdrawal=" + isWithdrawal + " pkg=" + pkg);
        boolean isWebMgmtBot = fullText.contains("Web Management Bot");
        if (isInsystem && isApproved && isWebMgmtBot && isDepositRequest && !isWithdrawal && !isRejected) {
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
