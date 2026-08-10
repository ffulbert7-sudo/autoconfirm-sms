package com.autoconfirm.smsforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "AutoConfirmSMS";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;
        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        SharedPreferences prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE);
        String webhookUrl = prefs.getString("webhook_url", "https://autoconfirm.online/webhook/saas");
        String token = prefs.getString("token", "");
        String sender1 = prefs.getString("sender1", "Wave");
        String sender2 = prefs.getString("sender2", "Orange");
        String sender3 = prefs.getString("sender3", "MTN");

        for (Object pdu : pdus) {
            SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
            String sender = msg.getOriginatingAddress();
            String body = msg.getMessageBody();
            if (sender == null || body == null) continue;

            boolean match = sender.contains(sender1) || sender.contains(sender2) || 
                           sender.contains(sender3) || sender.contains("+454") || 
                           sender.contains("MobileMoney");

            if (match) {
                Log.d(TAG, "SMS recu de: " + sender);
                sendToWebhook(webhookUrl, token, sender, body);
            }
        }
    }

    private void sendToWebhook(String url, String token, String sender, String message) {
        OkHttpClient client = new OkHttpClient();
        try {
            JSONObject json = new JSONObject();
            json.put("token", token);
            json.put("sender", sender);
            json.put("message", message);
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                .url(url)
                .addHeader("x-token", token)
                .post(body)
                .build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Erreur envoi: " + e.getMessage());
                }
                @Override
                public void onResponse(Call call, Response response) {
                    Log.d(TAG, "Reponse: " + response.code());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e.getMessage());
        }
    }
}
