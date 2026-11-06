package dev.abdullah.openlock

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.util.concurrent.Executors

/** Boot/reconnect recovery. No PIN or app lists are stored in these preferences. */
object MonitorStartup {
    private const val PREFS = "openlock_monitor_state"
    private const val ENABLED = "enabled"
    private val executor = Executors.newSingleThreadExecutor()
    private var nextCheckAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean? = prefs(context).let {
        if (it.contains(ENABLED)) it.getBoolean(ENABLED, false) else null
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, value).apply()
    }

    fun record(context: Context, outcome: String) {
        // Fixed reasons and exception class names only; never config/secret values.
        Log.i("OpenLockStartup", outcome)
        prefs(context).edit().putString("lastOutcome", "${System.currentTimeMillis()} $outcome").apply()
    }

    fun report(context: Context): String {
        val last = prefs(context).getString("lastOutcome", "No startup recorded")
        return "Monitor recovery: enabled=${enabled(context)} last=$last"
    }

    fun start(context: Context) {
        val intent = Intent(context, OpenLockMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    /** Bound accessibility reconnection and visible app resume are backup paths.
     *  OS refusal is recorded; we never assume that a start request succeeded. */
    @Synchronized
    fun restoreAsync(context: Context, reason: String, completed: () -> Unit = {}) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (OpenLockMonitorService.isRunning || (reason == "accessibility_event" && now < nextCheckAt)) {
            completed()
            return
        }
        nextCheckAt = now + 5_000L
        executor.execute {
            try {
                val user = app.getSystemService(Context.USER_SERVICE) as UserManager
                if (!user.isUserUnlocked) return@execute
                val requested = enabled(app)
                if (requested == false || OpenLockMonitorService.isRunning) return@execute
                // Keystore initialization can block; keep it off the receiver,
                // accessibility event dispatcher and Flutter's UI thread.
                val store = ConfigStore.get(app)
                val restart = MonitorRestartPolicy.shouldRestart(requested,
                    store.pinHash() != null, store.lockedPackages().isNotEmpty(),
                    store.schedules().length() > 0, store.preventUninstall())
                if (!restart) {
                    record(app, "$reason skipped: no PIN or configured protection")
                    return@execute
                }
                if (enabled(app) == false) return@execute // A stop while config was loading wins.
                record(app, "$reason requesting monitor start")
                start(app)
            } catch (error: Exception) {
                record(app, "$reason failed: ${error.javaClass.simpleName}")
            } finally {
                completed()
            }
        }
    }
}
