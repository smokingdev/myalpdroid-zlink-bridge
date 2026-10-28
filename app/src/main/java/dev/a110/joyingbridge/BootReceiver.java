package dev.a110.joyingbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("bridge", 0);
        if (!prefs.getBoolean("autoStart", true) || !prefs.getBoolean("enabled", false)) return;
        try { context.startForegroundService(new Intent(context, BridgeService.class)); }
        catch (RuntimeException e) {
            prefs.edit().putString("startupError", e.toString()).apply();
        }
    }
}
