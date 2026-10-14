package com.autoconfirm.smsforwarder;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class WatchdogService extends Service {
    private static final String TAG = "WatchdogService";
    private static final String CHANNEL_ID = "WatchdogChannel";
    private static final int RESTART_INTERVAL_MS = 60 * 1000; // 1 minute

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoConfirm")
            .setContentText("Protection active")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
        startForeground(3, notification);
        scheduleRestart();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Redemarrer SmsService si mort
        Intent smsIntent = new Intent(this, SmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(smsIntent);
        } else {
            startService(smsIntent);
        }
        Log.d(TAG, "Watchdog: SmsService verifie");
        scheduleRestart();
        return START_STICKY;
    }

    private void scheduleRestart() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, WatchdogReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS,
            pendingIntent
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Protection des services");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
