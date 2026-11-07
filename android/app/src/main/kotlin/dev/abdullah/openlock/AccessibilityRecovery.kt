package dev.abdullah.openlock

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import java.util.concurrent.atomic.AtomicBoolean

/** Runs with the monitor, not with the accessibility service it may restore.
 * All Settings-provider reads/writes and observer callbacks stay off main. */
internal class AccessibilityRecovery(context: Context, private val store: ConfigStore) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val thread = HandlerThread("OpenLock-accessibility-recovery").apply { start() }
    private val handler = Handler(thread.looper)
    private val requested = AtomicBoolean(false)
    @Volatile private var stopped = false
    private var observerRegistered = false
    private var lastAttemptAt: Long? = null
    private var attempts = 0
    private var restored = 0
    private var lastRepair = "none"
    private val logic = AccessibilityRestoreLogic(
        ComponentName(app, OpenLockGuardAccessibilityService::class.java).flattenToString())
    private val settings = object : AccessibilityRestoreSettings {
        override fun services(): String? = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        override fun masterEnabled(): Boolean = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        override fun putServices(value: String): Boolean = Settings.Secure.putString(resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        override fun enableMaster(): Boolean = Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { requestCheck("settings-change") }
    }
    private val periodic = object : Runnable {
        override fun run() {
            check("periodic")
            if (!stopped) handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        handler.post {
            if (stopped) return@post
            try {
                // Mark before registering: cleanup also covers a partial registration.
                observerRegistered = true
                for (key in listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED)) {
                    resolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
                }
            } catch (error: RuntimeException) {
                publish("observer unavailable: ${error.javaClass.simpleName}; periodic fallback remains")
            }
            periodic.run()
        }
    }

    fun requestCheck(source: String = "wake") {
        if (stopped || !requested.compareAndSet(false, true)) return
        handler.post {
            requested.set(false)
            check(source)
        }
    }

    /** Re-evaluate before EACH write: an explicit stop/config change wins. */
    private fun wanted(): Boolean = !stopped && MonitorStartup.enabled(app) != false &&
        store.pinHash() != null && store.preventUninstall() && store.autoRestoreAccessibility()

    private fun check(source: String) {
        if (stopped) return
        try {
            if (!wanted()) {
                publish("paused: requires active protection, PIN, Prevent uninstall and auto-restore")
                return
            }
            if (!hasPermission(app)) {
                publish("unavailable: WRITE_SECURE_SETTINGS not granted")
                return
            }
            val listed = logic.isListed(settings.services())
            val master = settings.masterEnabled()
            if (listed && master) {
                publish("configured: connectionConfirmed=${OpenLockGuardAccessibilityService.isConnected}")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (lastAttemptAt?.let { now - it < INTERVAL_MS } == true) {
                publish("retry pending: master=$master ownServiceListed=$listed")
                return
            }
            lastAttemptAt = now
            attempts++
            val result = logic.restore(settings) { wanted() && hasPermission(app) }
            if (result == AccessibilityRestoreLogic.Result.SETTINGS_RESTORED) restored++
            lastRepair = "atElapsedMs=$now source=$source result=$result"
            publish("source=$source result=$result connectionConfirmed=${OpenLockGuardAccessibilityService.isConnected}")
        } catch (error: RuntimeException) {
            lastRepair = "source=$source failed=${error.javaClass.simpleName}"
            publish("source=$source failed: ${error.javaClass.simpleName}")
        }
    }

    private fun publish(outcome: String) {
        if (!stopped) summary = "Accessibility recovery: atElapsedMs=${SystemClock.elapsedRealtime()} " +
            "intervalMs=$INTERVAL_MS attempts=$attempts settingsRestores=$restored $outcome lastRepair=[$lastRepair]"
    }

    fun stop() {
        stopped = true
        summary = "Accessibility recovery: stopped with monitor"
        handler.removeCallbacksAndMessages(null)
        handler.post {
            if (observerRegistered) runCatching { resolver.unregisterContentObserver(observer) }
            thread.quitSafely()
        }
    }

    companion object {
        private const val INTERVAL_MS = 5_000L
        @Volatile private var summary = "Accessibility recovery: monitor not running"
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        fun report(): String = summary
    }
}
