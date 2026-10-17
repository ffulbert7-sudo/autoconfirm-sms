package com.autoconfirm.smsforwarder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.PowerManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class SmsService extends Service {
    private PowerManager.WakeLock wakeLock;
    public static final String CHANNEL_ID = "SmsForwarderChannel";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoConfirm SMS")
            .setContentText("✅ Service actif - Capture SMS en cours")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
        startForeground(1, notification);
        startHeartbeat();
        // WakeLock pour rester actif écran éteint
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoConfirm::SmsWakeLock");
        wakeLock.acquire();
        // Demarrer WorkManager watchdog
        WatchdogWorker.schedule(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startHeartbeat() {
        new Thread(() -> {
            while (true) {
                try {
                    android.content.SharedPreferences prefs = getSharedPreferences("config", MODE_PRIVATE);
                    String serverUrl = prefs.getString("url", "https://autoconfirm.online");
                    String token = prefs.getString("token", "");
                    if (!token.isEmpty()) {
                        String flat = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
                        String notif = (flat != null && flat.contains(getPackageName())) ? "1" : "0";
                        // Verifier accessibilite
                        String access = "0";
                        try {
                            android.accessibilityservice.AccessibilityServiceInfo info = null;
                            android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
                            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> list = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                            for (android.accessibilityservice.AccessibilityServiceInfo s : list) {
                                if (s.getResolveInfo().serviceInfo.packageName.equals(getPackageName())) {
                                    access = "1"; break;
                                }
                            }
                        } catch (Exception e) {}
                        java.net.URL u = new java.net.URL(serverUrl + "/api/heartbeat");
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                        String body = "token=" + token + "&notif=" + notif + "&access=" + access + "&version=38";
                        conn.getOutputStream().write(body.getBytes());
                        conn.getResponseCode();
                        conn.disconnect();
                    }
                    Thread.sleep(5 * 60 * 1000);
                } catch (Exception e) {
                    try { Thread.sleep(60 * 1000); } catch (Exception ex) {}
                }
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "AutoConfirm SMS", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
}
