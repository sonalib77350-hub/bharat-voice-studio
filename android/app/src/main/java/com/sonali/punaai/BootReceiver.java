package com.sonali.punaai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the 24/7 service after reboot / app update when the user enabled it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        Log.i(PunaService.TAG, "boot receiver: " + a);
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            PunaService.sync(c);
        }
    }
}
