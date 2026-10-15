package com.autoconfirm.smsforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class AlertService extends Service {
    private static final String ALERT_CHANNEL = "AutoConfirmAlerts";
    private static final int CHECK_INTERVAL = 30000; // 30 secondes
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createAlertChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            new Thread(this::checkAlerts).start();
        }
        return START_STICKY;
    }

    private void checkAlerts() {
        while (running) {
            try {
                SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
                String serverUrl = prefs.getString("url", "https://autoconfirm.online");
                String token = prefs.getString("token", "");
                if (!token.isEmpty()) {
                    URL url = new URL(serverUrl + "/api/alerts?token=" + token);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    if (conn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        JSONObject json = new JSONObject(sb.toString());
                        JSONArray alerts = json.optJSONArray("alerts");
                        if (alerts != null) {
                            for (int i = 0; i < alerts.length(); i++) {
                                JSONObject alert = alerts.getJSONObject(i);
                                sendAlert(alert.optString("title"), alert.optString("body"));
                            }
                        }
                    }
                    conn.disconnect();
                }
            } catch (Exception e) {}
            try { Thread.sleep(CHECK_INTERVAL); } catch (InterruptedException e) { break; }
        }
    }

    private void sendAlert(String title, String body) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notif = new NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setOngoing(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(new long[]{0, 500, 200, 500, 200, 500})
            .build();
        nm.notify((int) System.currentTimeMillis(), notif);
    }

    private void createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                ALERT_CHANNEL, "Alertes AutoConfirm", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Alertes sessions expirées");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500, 200, 500});
            channel.setSound(Settings.System.DEFAULT_ALARM_ALERT_URI,
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() { running = false; super.onDestroy(); }
}
