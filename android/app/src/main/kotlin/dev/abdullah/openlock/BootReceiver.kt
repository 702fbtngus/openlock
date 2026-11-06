package dev.abdullah.openlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the monitor service after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pending = goAsync()
        MonitorStartup.restoreAsync(context,
            if (action == Intent.ACTION_BOOT_COMPLETED) "boot" else "app_update") {
            pending.finish()
        }
    }
}
