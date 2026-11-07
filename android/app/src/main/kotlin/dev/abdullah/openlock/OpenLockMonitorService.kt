package dev.abdullah.openlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent foreground service that detects the current foreground app via
 * [UsageStatsManager] and throws up [LockActivity] over any locked app that is
 * not currently in an unlocked session (per relock policy + schedules).
 */
class OpenLockMonitorService : Service() {

    private lateinit var store: ConfigStore
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var keyguardManager: KeyguardManager
    private val handler = Handler(Looper.getMainLooper())
    private val usageThread = HandlerThread("OpenLock-usage-poll").apply { start() }
    private val usageHandler = Handler(usageThread.looper)
    private val pollSession = ForegroundPollSession()
    private var accessibilityRecovery: AccessibilityRecovery? = null

    private val leftAppAt = ConcurrentHashMap<String, Long>()
    @Volatile private var screenOffAt: Long = 0L
    private var screenInteractive = false
    private var lastForegroundPkg: String? = null
    private var lastLaunchedPkg: String? = null
    private var lastLaunchAt: Long = 0L
    private var observedForeground: String? = null
    private var callPackages = PhoneLockCalls.essentialPackages
    private var lastPhoneLaunchAt = 0L
    private val phonePoller = object : Runnable {
        override fun run() {
            if (!isRunning) return
            // Independent of UsageStats/Binder latency and foreground events:
            // a schedule must start even if an app has been open for hours.
            runCatching { enforcePhoneLock() }.onFailure {
                GuardDiagnostics.launch("Phone schedule check failed: ${it.javaClass.simpleName}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = System.currentTimeMillis()
                    screenInteractive = false
                    PhoneLockSession.state.screenOff()
                    lastPhoneLaunchAt = 0L
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    accessibilityRecovery?.requestCheck()
                    screenInteractive = true
                    // The last event may still name the keyguard. Do not let
                    // that stale SystemUI exemption suppress the wake-up PIN.
                    if (observedForeground == "com.android.systemui") observedForeground = null
                    enforcePhoneLock()
                }
            }
        }
    }

    private fun startPolling() {
        val ticket = pollSession.start()
        usageHandler.removeCallbacksAndMessages(null)
        usageHandler.post {
            val allowed = PhoneLockCalls.allowedPackages(this)
            handler.post { callPackages = allowed }
        }
        usageHandler.post(object : Runnable {
            override fun run() {
                if (!pollSession.isCurrent(ticket)) return
                val window = GuardedScreen.windowRevision
                val startedAt = SystemClock.elapsedRealtime()
                var error: String? = null
                val current = try { foregroundApp() } catch (failure: Exception) {
                    error = failure.javaClass.simpleName
                    null
                }
                val finishedAt = SystemClock.elapsedRealtime()
                handler.post deliver@{
                    if (!pollSession.isCurrent(ticket)) return@deliver
                    val accepted = pollSession.canApply(ticket, window, GuardedScreen.windowRevision)
                    try {
                        GuardDiagnostics.usagePoll(startedAt, finishedAt, accepted, error)
                        if (accepted && current != null) {
                            observedForeground = current
                            tick(current)
                        }
                    } catch (_: Exception) {
                        // Keep the polling fallback alive after transient errors.
                    } finally {
                        // Schedule only after delivery: at most one query/result
                        // is in flight, even when the main looper is busy.
                        if (pollSession.isCurrent(ticket)) usageHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
        })
    }

    override fun onCreate() {
        super.onCreate()
        // Promote before potentially slow Keystore/config initialization.
        startForeground(NOTIFICATION_ID, buildNotification())
        store = ConfigStore.get(this)
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        PhoneLockSession.state.screenOff() // A monitor restart never preserves a phone unlock.
        screenInteractive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        GuardedScreen.onWindowChanged = { pkg ->
            if (pkg != null) observedForeground = pkg
            enforcePhoneLock()
        }
        GuardedScreen.onDetected = { pkg ->
            // The accessibility event identifies the active OS window directly;
            // do not wait for UsageStats or the next 300 ms poll.
            if (!enforcePhoneLock(pkg)) enforceGuardedScreen(pkg, System.currentTimeMillis())
        }
        GuardedScreen.shouldWatchSettings = {
            val unlockedAt = LockSession.unlockedAt(UninstallGuard.SETTINGS_PACKAGE)
            screenInteractive && store.pinHash() != null && store.preventUninstall() &&
                (unlockedAt == 0L || screenOffAt > unlockedAt)
        }
        GuardedScreen.onBackFinished = { pkg ->
            // Cancellation did not authenticate. A quick re-entry must not
            // inherit the debounce timestamp from the dismissed PIN activity.
            if (lastLaunchedPkg == pkg) {
                lastLaunchedPkg = null
                lastLaunchAt = 0L
            }
        }
        accessibilityRecovery = AccessibilityRecovery(this, store).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MonitorStartup.enabled(this) == false) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        MonitorStartup.setEnabled(this, true)
        MonitorStartup.record(this, "monitor running")
        isRunning = true
        startPolling()
        handler.removeCallbacks(phonePoller)
        handler.post(phonePoller)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        accessibilityRecovery?.stop()
        accessibilityRecovery = null
        pollSession.stop()
        usageHandler.removeCallbacksAndMessages(null)
        usageThread.quitSafely()
        handler.removeCallbacks(phonePoller)
        MonitorStartup.record(this, "monitor destroyed")
        GuardedScreen.onDetected = null
        GuardedScreen.onBackFinished = null
        GuardedScreen.shouldWatchSettings = null
        GuardedScreen.onWindowChanged = null
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun tick(current: String) {
        val now = System.currentTimeMillis()

        // Track departures from the foreground for the relock policy.
        val previous = lastForegroundPkg
        if (previous != null && previous != current) {
            leftAppAt[previous] = now
        }
        lastForegroundPkg = current

        // Once the user is in an app, its hidden-notification backlog is stale —
        // clear OpenLock's redacted notice for it. (No-op when the listener is
        // off or nothing was hidden.)
        usageHandler.post { OpenLockNotificationListenerService.onAppForegrounded(current) }

        if (enforcePhoneLock(current)) return

        // Never lock ourselves.
        if (current == packageName) return

        // Includes OpenLock's removal/accessibility screens and Special access.
        if (enforceGuardedScreen(current, now)) return

        val locked = HashSet(store.lockedPackages())
        locked.addAll(LockLogic.scheduledLockedPackages(store.schedules(), Calendar.getInstance()))
        if (!locked.contains(current)) return

        // No PIN configured yet → nothing to enforce.
        if (store.pinHash() == null) return

        val shouldLock = LockLogic.isLocked(
            mode = store.relockMode(),
            timeoutMinutes = store.relockTimeoutMinutes(),
            unlockedAt = LockSession.unlockedAt(current),
            leftAppAt = leftAppAt[current] ?: 0L,
            screenOffAt = screenOffAt,
            now = now,
        )
        if (!shouldLock) return

        // Debounce so we don't stack multiple lock screens for the same app.
        if (current == lastLaunchedPkg && now - lastLaunchAt < RELAUNCH_GUARD_MS) return
        lastLaunchedPkg = current
        lastLaunchAt = now
        launchLock(current)
    }

    private fun enforcePhoneLock(current: String? = observedForeground): Boolean {
        val active = if (store.pinHash() != null) {
            LockLogic.activePhoneScheduleKeys(store.schedules(), Calendar.getInstance())
        } else emptySet()
        PhoneLockSession.state.update(active)
        if (!PhoneLockSession.state.requiresPin) {
            lastPhoneLaunchAt = 0L
            return false
        }
        // Phone-wide protection is deliberately best effort. Leave keyguard,
        // system controls and telephone UIs available without new permissions.
        if (!screenInteractive || (current != null && current in callPackages) || keyguardManager.isKeyguardLocked ||
            LockActivity.visiblePackage != null || GuardedScreen.backNavigationPackage != null) return true
        val now = SystemClock.elapsedRealtime()
        if (lastPhoneLaunchAt != 0L && now - lastPhoneLaunchAt < PHONE_RELAUNCH_MS) return true
        lastPhoneLaunchAt = now
        launchLock(PhoneLockSession.TARGET)
        return true
    }

    /** Shared by event-driven detection and the polling fallback. */
    private fun enforceGuardedScreen(current: String, now: Long): Boolean {
        // The accessibility service covers the screen while backing out. This
        // is navigation, not an authenticated session or a timed bypass.
        if (GuardedScreen.isNavigatingBack(current)) return true
        if (!GuardedScreen.isShowing(current)) return false
        if (store.pinHash() == null || !store.preventUninstall()) {
            GuardDiagnostics.enforcement("$current matched; protection inactive " +
                "(PIN configured=${store.pinHash() != null}, preventUninstall=${store.preventUninstall()})")
            return false
        }
        // Preserve the original per-package session: authentication permits
        // changing settings, including disabling the service, until screen-off.
        val unlockedAt = LockSession.unlockedAt(current)
        val stillUnlocked = unlockedAt != 0L && screenOffAt <= unlockedAt
        val debounced = current == lastLaunchedPkg && now - lastLaunchAt < RELAUNCH_GUARD_MS
        GuardDiagnostics.enforcement("$current matched; sessionUnlocked=$stillUnlocked " +
            "debounced=$debounced unlockedAt=$unlockedAt screenOffAt=$screenOffAt " +
            "launchRequested=${!stillUnlocked && !debounced}")
        if (!stillUnlocked && !debounced) {
            lastLaunchedPkg = current
            lastLaunchAt = now
            launchLock(current, guardedSettings = current == UninstallGuard.SETTINGS_PACKAGE,
                timing = GuardedScreen.timingFor(current))
        }
        return true
    }

    private fun launchLock(packageName: String, guardedSettings: Boolean = false,
                           timing: GuardDiagnostics.DetectionTiming? = null) {
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            putExtra(LockActivity.EXTRA_PACKAGE, packageName)
            putExtra(LockActivity.EXTRA_GUARDED_SETTINGS, guardedSettings)
        }
        GuardDiagnostics.launch("LockActivity launch requested for $packageName")
        GuardDiagnostics.lockRequested(packageName, timing)
        runCatching { startActivity(intent) }.onFailure {
            GuardDiagnostics.launch("LockActivity launch failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** The latest foreground package. */
    private fun foregroundApp(): String? {
        val end = System.currentTimeMillis()
        val begin = end - LOOKBACK_MS
        val events = usageStatsManager.queryEvents(begin, end) ?: return null
        var pkg: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App lock protection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while OpenLock is guarding your apps."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pending = openIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("OpenLock is protecting your apps")
            .setContentText("Locked apps stay behind your PIN.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "openlock_monitor"
        private const val NOTIFICATION_ID = 4711
        private const val POLL_INTERVAL_MS = 300L
        private const val LOOKBACK_MS = 10_000L
        private const val RELAUNCH_GUARD_MS = 1_500L
        private const val PHONE_RELAUNCH_MS = 750L
    }
}
