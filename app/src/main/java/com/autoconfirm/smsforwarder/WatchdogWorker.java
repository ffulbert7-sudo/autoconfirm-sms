package com.autoconfirm.smsforwarder;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

public class WatchdogWorker extends Worker {
    public WatchdogWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        // Redemarrer SmsService
        Intent intent = new Intent(ctx, SmsService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
        // Redemarrer AlertService
        Intent alertIntent = new Intent(ctx, AlertService.class);
        ctx.startService(alertIntent);
        // Redemarrer WatchdogService si existant
        try {
            Intent watchdogIntent = new Intent(ctx, WatchdogService.class);
            ctx.startForegroundService(watchdogIntent);
        } catch (Exception e) {}
        return Result.success();
    }

    public static void schedule(Context ctx) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
            WatchdogWorker.class, 15, TimeUnit.MINUTES)
            .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "watchdog", 
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, 
            request);
    }
}
