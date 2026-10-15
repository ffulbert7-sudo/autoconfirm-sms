package com.autoconfirm.smsforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent service = new Intent(context, SmsService.class);
            context.startForegroundService(service);
            Intent watchdog = new Intent(context, WatchdogService.class);
            context.startForegroundService(watchdog);
            Intent alert = new Intent(context, AlertService.class);
            context.startService(alert);
        }
    }
}
