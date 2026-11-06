package dev.abdullah.openlock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Inspects candidate OS windows in memory; the monitor owns PIN/session policy. */
class OpenLockGuardAccessibilityService : AccessibilityService() {
    private var windowPackage: String? = null
    private var windowClass: String? = null
    private var windowId = -1
    private var windowEventTiming: GuardDiagnostics.EventTiming? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scanThread = HandlerThread("OpenLock-settings-scan").apply { start() }
    private val scanHandler = Handler(scanThread.looper)
    @Volatile private var windowEpoch = 0L
    private var destroyed = false
    private var inspectionSequence = 0L
    private var inspectionPending = false
    private val recheck = Runnable { inspectWindow() }
    private val settingsWatchState by lazy {
        SettingsWatchState(UninstallGuard.SETTINGS_PACKAGE, packageName)
    }
    private var settingsWatchScheduled = false
    private val settingsWatch = Runnable {
        settingsWatchScheduled = false
        if (!destroyed && settingsWatchState.isActive(SystemClock.elapsedRealtime())) {
            if (GuardedScreen.needsSettingsWatch() && LockActivity.visiblePackage == null && backNavigation == null) {
                // Window/click notifications can arrive >1 s late. Read the
                // CURRENT root without borrowing a class/title/time from them.
                inspectWindow(eventTiming = null, source = WATCH_SOURCE)
            } else {
                GuardDiagnostics.watch("paused: policy=${GuardedScreen.needsSettingsWatch()} " +
                    "lockVisible=${LockActivity.visiblePackage != null} back=${backNavigation != null}")
                scheduleSettingsWatch()
            }
        } else {
            GuardDiagnostics.watch("stopped: ${settingsWatchState.reason} destroyed=$destroyed")
        }
    }
    private var backNavigation: GuardBackNavigation? = null
    private var backTarget: String? = null
    private var navigationCover: View? = null
    private val inspectionGate = SettingsInspectionGate()
    private var inspectionCover: View? = null
    private val inspectionCoverPoller = object : Runnable {
        override fun run() {
            if (inspectionCover == null) return
            if (!GuardedScreen.needsSettingsWatch() || backNavigation != null) {
                dismissInspectionCover("policy inactive or navigating back")
                return
            }
            when (inspectionGate.timeout(SystemClock.elapsedRealtime())) {
                SettingsInspectionGate.TimeoutAction.NONE -> Unit
                SettingsInspectionGate.TimeoutAction.REQUEST_PIN -> {
                    GuardDiagnostics.cover("inspection timed out; requesting PIN")
                    GuardedScreen.set(UninstallGuard.SETTINGS_PACKAGE)
                }
                SettingsInspectionGate.TimeoutAction.HOME -> {
                    // A refused activity launch must not expose an unverified
                    // screen. Home/Back/Power remain available under the cover.
                    val accepted = performGlobalAction(GLOBAL_ACTION_HOME)
                    GuardDiagnostics.cover("PIN handoff timed out; Home accepted=$accepted")
                }
            }
            handler.postDelayed(this, SETTINGS_WATCH_MS)
        }
    }
    private val navigationPoller = object : Runnable {
        override fun run() {
            if (backNavigation == null) return
            inspectWindow()
            if (backNavigation != null) handler.postDelayed(this, 75L)
        }
    }
    private var lastDiagnosticAt = -1_000L
    private var lastDiagnosticSignature: Int? = null
    private val ownLabel by lazy { applicationInfo.loadLabel(packageManager).toString() }
    private val serviceLabel by lazy { getString(R.string.guard_accessibility_label) }

    // Resolve known Settings resource names in the device locale where available.
    private val localizedTitles by lazy {
        runCatching {
            val resources = packageManager.getResourcesForApplication(UninstallGuard.SETTINGS_PACKAGE)
            TITLE_RESOURCES.mapNotNull { name ->
                val id = resources.getIdentifier(name, "string", UninstallGuard.SETTINGS_PACKAGE)
                if (id == 0) null else runCatching { resources.getString(id) }.getOrNull()
            }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Framework cache handling runs BEFORE onAccessibilityEvent, and can
        // synchronously refresh a clicked node. It can also retain descendants
        // of a same-window fragment until its delayed content event arrives.
        // Fresh worker reads avoid both dependencies (API 33+ public API).
        val cacheMode = if (Build.VERSION.SDK_INT >= 33) {
            runCatching { if (setCacheEnabled(false)) "disabled (prefetch disabled)" else "disable refused" }
                .getOrElse { "disable failed: ${it.javaClass.simpleName}" }
        } else "platform default (API <33)"
        GuardDiagnostics.accessibilityMode("nodeCache=$cacheMode settingsWatchIntervalMs=$SETTINGS_WATCH_MS")
        MonitorStartup.restoreAsync(this, "accessibility_connected")
        GuardedScreen.onBackRequested = ::startBackNavigation
        GuardedScreen.onLockDrawn = { target ->
            if (LockActivity.visiblePackage == target) dismissInspectionCover("PIN first draw")
        }
        inspectWindow()
        scheduleRechecks()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventTiming = GuardDiagnostics.event(
            "type=${event.eventType} package=${event.packageName} " +
                "class=${event.className} windowId=${event.windowId} changes=${event.contentChangeTypes}",
            event.packageName?.toString(), event.windowId, event.eventType, event.eventTime)
        if (!OpenLockMonitorService.isRunning) MonitorStartup.restoreAsync(this, "accessibility_event")
        // Some OEMs announce non-focusable overlay containers as windows.
        // They are not an exit to MainActivity or the PIN activity.
        if (inspectionCover != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == packageName &&
            event.className?.toString() in setOf(FrameLayout::class.java.name, LinearLayout::class.java.name)) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (detectServiceEntryClick(event, eventTiming)) scheduleRechecks()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg == UninstallGuard.SETTINGS_PACKAGE) {
                    // Attach BEFORE asking the monitor/worker to inspect this
                    // window. No Settings node or event.source is read here.
                    beginInspectionCover("Settings window changed")
                } else if (pkg != null && pkg != "com.android.systemui" &&
                    !(pkg == packageName && (LockActivity.visiblePackage != null ||
                        event.className?.toString() == LockActivity::class.java.name))) {
                    dismissInspectionCover("left Settings")
                }
                GuardedScreen.windowChanged(event.packageName?.toString())
                windowEpoch++
                windowEventTiming = eventTiming
                windowPackage = event.packageName?.toString()
                windowClass = event.className?.toString()
                windowId = event.windowId
                handler.removeCallbacks(recheck)
                settingsWatchState.observePackage(windowPackage, SystemClock.elapsedRealtime())
                scheduleSettingsWatch()
                if (windowPackage?.let(UninstallGuard::isCandidatePackage) == true) scheduleRechecks()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString()?.let(UninstallGuard::isCandidatePackage) != true) return
                if (event.packageName?.toString() == UninstallGuard.SETTINGS_PACKAGE &&
                    (event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED ||
                        event.contentChangeTypes and (AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE or
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
                            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0)) {
                    // OEMs also change fragments inside the same window ID.
                    beginInspectionCover("Settings structure changed")
                    if (inspectionCover != null) windowEpoch++
                }
                windowEventTiming = eventTiming
            }
            else -> return
        }
        // Only pane-title/appeared events guarantee a title in the first entry.
        // General event.text can contain an entire preference list, so treating
        // all of it as titles would incorrectly guard Apps/search/list screens.
        val paneChange = event.contentChangeTypes and
            (AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED) != 0
        val paneTitle = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && paneChange) {
            event.text.firstOrNull()?.toString()
        } else null
        inspectWindow(paneTitle, event.packageName?.toString(), event.windowId, eventTiming)
    }

    private fun detectServiceEntryClick(event: AccessibilityEvent, eventTiming: GuardDiagnostics.EventTiming?): Boolean {
        if (event.packageName?.toString() != UninstallGuard.SETTINGS_PACKAGE ||
            windowPackage != UninstallGuard.SETTINGS_PACKAGE || event.windowId != windowId ||
            event.isPassword) return false
        val startedAt = SystemClock.elapsedRealtime()
        val cls = event.className?.toString() ?: return false
        // Use only event metadata here: fetching event.source/root can itself
        // wait on Settings' Binder response, defeating this fast path.
        if (cls !in ENTRY_CLICK_CLASSES) return false
        val node = ScreenNode(null, event.contentDescription?.toString(), className = cls)
        if (!UninstallGuard.isOwnServiceEntryClick(UninstallGuard.SETTINGS_PACKAGE,
                node, event.text.map { it.toString() }, serviceLabel)) return false
        val timing = GuardDiagnostics.scan(UninstallGuard.SETTINGS_PACKAGE, startedAt, SystemClock.elapsedRealtime(), true,
            event = eventTiming, source = "service-entry-click")
        GuardDiagnostics.snapshot("service-entry-click", "Own accessibility entry clicked; requesting lock before detail controls load.")
        beginInspectionCover("own service clicked")
        if (inspectionCover != null) inspectionGate.requirePin(SystemClock.elapsedRealtime())
        windowEpoch++ // Invalidate an older list scan still in progress.
        GuardedScreen.set(UninstallGuard.SETTINGS_PACKAGE, timing)
        return true
    }

    private fun scheduleRechecks() {
        // The initial event can precede its title/controls. Read the CURRENT
        // root each time, never a retained tree from a previous screen.
        for (delay in longArrayOf(75L, 200L, 500L)) handler.postDelayed(recheck, delay)
    }

    private fun scheduleSettingsWatch() {
        if (destroyed || !settingsWatchState.isActive(SystemClock.elapsedRealtime())) {
            handler.removeCallbacks(settingsWatch)
            settingsWatchScheduled = false
            GuardDiagnostics.watch("stopped: ${settingsWatchState.reason} destroyed=$destroyed")
        } else if (!settingsWatchScheduled) {
            // Keep an existing deadline. Content events must not perpetually
            // postpone an independent watch by another 150 ms.
            settingsWatchScheduled = true
            handler.postDelayed(settingsWatch, SETTINGS_WATCH_MS)
            GuardDiagnostics.watch("scheduled: reason=${settingsWatchState.reason} " +
                "dueAtElapsedMs=${SystemClock.elapsedRealtime() + SETTINGS_WATCH_MS}")
        }
    }

    private fun inspectWindow(eventPaneTitle: String? = null, eventPackage: String? = null,
                              eventWindowId: Int = -1, eventTiming: GuardDiagnostics.EventTiming? = windowEventTiming,
                              source: String = "window-scan") {
        if (destroyed) return
        if (source == WATCH_SOURCE && inspectionPending) {
            // A fallback must never evict a queued event read: that read may
            // carry a real class/pane title needed by an OEM's detail layout.
            GuardDiagnostics.watch("waiting: inspectionPending request=$inspectionSequence")
            scheduleSettingsWatch()
            return
        }
        val request = ++inspectionSequence
        inspectionPending = true
        val epoch = windowEpoch
        val queuedAt = SystemClock.elapsedRealtime()
        val expectedPackage = windowPackage
        val expectedClass = windowClass.takeUnless { source == WATCH_SOURCE }
        val expectedId = windowId
        // Coalesce pending reads. A slow Binder call must not block event
        // dispatch, the monitor, PIN rendering, or queue many obsolete trees.
        scanHandler.removeCallbacksAndMessages(null)
        scanHandler.post {
            val startedAt = SystemClock.elapsedRealtime()
            val trace = GuardDiagnostics.readStarted(request, source, queuedAt)
            val result = try {
                if (epoch == windowEpoch) readWindow(eventPaneTitle, eventPackage, eventWindowId,
                    expectedPackage, expectedClass, expectedId, epoch, trace).copy(queuedAt = queuedAt,
                        event = eventTiming) else null
            } catch (error: RuntimeException) {
                // Also survive a node becoming invalid during cleanup.
                Inspection(null, startedAt = startedAt, queuedAt = queuedAt,
                    reason = "Read worker failed: ${error.javaClass.simpleName}")
            }
            val finishedAt = SystemClock.elapsedRealtime()
            GuardDiagnostics.readStage(trace, "awaiting-main", result?.nodes?.size ?: 0)
            handler.post {
                if (request == inspectionSequence) inspectionPending = false
                val accepted = !destroyed && epoch == windowEpoch && result != null
                val outcome = when {
                    destroyed -> "destroyed"
                    epoch != windowEpoch -> "epoch-changed"
                    result == null -> "skipped-before-read"
                    result.pkg == null -> "unavailable-or-failed"
                    else -> "applied"
                }
                try {
                    // Record even discarded reads: a slow query can outlive a
                    // window transition and used to disappear from diagnostics.
                    GuardDiagnostics.inspection(source, result?.pkg, result?.id ?: -1,
                        result?.startedAt ?: startedAt, finishedAt,
                        result?.guarded == true, rootReadMs = result?.rootReadMs,
                        accepted = accepted, outcome = outcome, queuedAt = queuedAt,
                        complete = result?.complete)
                    if (accepted && result != null) applyInspection(result, source, request == inspectionSequence)
                } finally {
                    GuardDiagnostics.readFinished(trace)
                    // Completion, stale epoch and failed reads all rearm the
                    // watch. Never depend solely on applyInspection succeeding.
                    scheduleSettingsWatch()
                }
            }
        }
    }

    private data class Inspection(
        val pkg: String?,
        val cls: String? = null,
        val id: Int = -1,
        val nodes: List<ScreenNode> = emptyList(),
        val guarded: Boolean = false,
        val contentReady: Boolean = false,
        val startedAt: Long,
        val queuedAt: Long = startedAt,
        val finishedAt: Long = SystemClock.elapsedRealtime(),
        val reason: String? = null,
        val event: GuardDiagnostics.EventTiming? = null,
        val rootReadMs: Long? = null,
        val complete: Boolean = false,
    )

    @Suppress("DEPRECATION")
    private fun readWindow(
        eventPaneTitle: String?, eventPackage: String?, eventWindowId: Int,
        expectedPackage: String?, expectedClass: String?, expectedId: Int, epoch: Long,
        trace: GuardDiagnostics.ReadToken?,
    ): Inspection {
        val scanStartedAt = SystemClock.elapsedRealtime()
        GuardDiagnostics.readStage(trace, "root")
        val root = try { rootInActiveWindow } catch (error: RuntimeException) {
            return Inspection(null, startedAt = scanStartedAt,
                rootReadMs = SystemClock.elapsedRealtime() - scanStartedAt,
                reason = "Root read failed: ${error.javaClass.simpleName}")
        }
        val rootReadMs = SystemClock.elapsedRealtime() - scanStartedAt
        if (root == null) {
            return Inspection(null, startedAt = scanStartedAt, rootReadMs = rootReadMs,
                reason = "Active window root is unavailable.")
        }
        try {
            GuardDiagnostics.readStage(trace, "metadata")
            val rootPkg = root.packageName?.toString()
            if (rootPkg == null || !UninstallGuard.isCandidatePackage(rootPkg)) {
                // Unknown OEM packages are identified without reading their text.
                return Inspection(rootPkg, expectedClass, contentReady = true, startedAt = scanStartedAt,
                    rootReadMs = rootReadMs,
                    reason = "Ignored package=$rootPkg class=$expectedClass (not a candidate)")
            }
            val cls = expectedClass.takeIf { expectedPackage == rootPkg && expectedId == root.windowId }
            val initialNodes = ArrayList<ScreenNode>()
            if (eventPaneTitle != null && eventPackage == rootPkg && eventWindowId == root.windowId) {
                initialNodes.add(ScreenNode(null, null, paneTitle = eventPaneTitle))
            }
            fun matches(nodes: List<ScreenNode>) = UninstallGuard.isGuardedScreen(
                windowPackage = rootPkg,
                windowClass = cls,
                nodes = nodes,
                ownLabel = ownLabel,
                ownPackage = packageName,
                ownServiceLabel = serviceLabel,
                localizedSpecialAccessTitles = if (rootPkg == UninstallGuard.SETTINGS_PACKAGE) localizedTitles else emptySet(),
            )
            // These are the SAME positive rules as the full matcher. A known
            // class or confirmed pane title can prove a match without a walk.
            var guarded = UninstallGuard.isSpecialAccessScreenClass(rootPkg, cls) ||
                (initialNodes.isNotEmpty() && matches(initialNodes))
            val tree = if (guarded) FlattenedWindow(initialNodes, complete = false) else flatten(root, initialNodes, trace) { partial ->
                if (epoch != windowEpoch) return@flatten true
                // All positive rules require only accumulated evidence; extra
                // descendants cannot invalidate a match. Negative results still
                // require the complete bounded walk, including Back navigation.
                guarded = matches(partial)
                guarded
            }
            val nodes = tree.nodes
            GuardDiagnostics.readStage(trace, "match", nodes.size)
            if (!guarded) guarded = matches(nodes)
            val matchedAt = SystemClock.elapsedRealtime()
            return Inspection(rootPkg, cls, root.windowId, nodes, guarded, nodes.isNotEmpty(),
                startedAt = scanStartedAt, finishedAt = matchedAt, rootReadMs = rootReadMs,
                complete = tree.complete && nodes.size >= 3 && nodes.any {
                    !it.text.isNullOrBlank() && !it.inToolbar && it.paneTitle == null &&
                        it.viewId?.substringAfter(":id/") !in PAGE_TITLE_IDS
                } &&
                    nodes.none { it.className?.endsWith("ProgressBar") == true })
        } catch (error: RuntimeException) {
            // A transitioning Settings tree can become invalid while walking it.
            // Keep the service alive and let the content event/rechecks retry.
            return Inspection(null, startedAt = scanStartedAt,
                rootReadMs = rootReadMs,
                reason = "Window read failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            GuardDiagnostics.readStage(trace, "cleanup")
            root.recycle()
        }
    }

    /** Only immutable scan data crosses threads; all UI/session state stays here. */
    private fun applyInspection(result: Inspection, source: String, newest: Boolean) {
        val pkg = result.pkg
        val timing = if (pkg != null) GuardDiagnostics.scan(pkg, result.startedAt, result.finishedAt,
            result.guarded, result.queuedAt,
            event = result.event?.takeIf { it.pkg == pkg && it.windowId == result.id }, source = source) else null
        settingsWatchState.observePackage(pkg, SystemClock.elapsedRealtime())
        advanceBackNavigation(pkg, result.guarded, result.contentReady)
        if (pkg == UninstallGuard.SETTINGS_PACKAGE && result.guarded) {
            beginInspectionCover("protected Settings screen")
            if (inspectionCover != null) inspectionGate.requirePin(SystemClock.elapsedRealtime())
        }
        if (result.guarded && pkg != null) {
            GuardedScreen.set(pkg, timing)
        } else if (!inspectionGate.awaitingPin) {
            GuardedScreen.clear()
        }
        if (newest && inspectionCover != null) {
            if (pkg != null && pkg != UninstallGuard.SETTINGS_PACKAGE) {
                // A non-focusable accessibility overlay leaves the active root
                // underneath inspectable. A different root confirms an exit.
                // MainActivity's real window event clears the cover above.
                // An own-package root alone could be this overlay on an OEM.
                if (pkg != packageName) {
                    dismissInspectionCover("different active root")
                }
            } else if (pkg == UninstallGuard.SETTINGS_PACKAGE && !result.guarded &&
                inspectionGate.allowScreen(result.complete &&
                    (windowPackage != UninstallGuard.SETTINGS_PACKAGE || windowId < 0 || result.id == windowId),
                    result.nodes.hashCode(),
                    result.startedAt, SystemClock.elapsedRealtime())) {
                dismissInspectionCover("complete stable unguarded screen")
            }
        }
        if (result.reason != null) {
            if (pkg != packageName) GuardDiagnostics.snapshot("read:$pkg", result.reason)
        } else if (pkg != null) {
            captureDiagnostics(pkg, result.cls, result.id, result.nodes, result.guarded,
                result.finishedAt - result.startedAt)
        }
        if (pkg != null) logDiagnostics(pkg, result.cls, result.nodes, result.guarded)
    }

    override fun onInterrupt() {
        GuardedScreen.windowChanged()
        windowEpoch++
        inspectionSequence++
        inspectionPending = false
        scanHandler.removeCallbacksAndMessages(null)
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        dismissInspectionCover("service interrupted")
        handler.removeCallbacks(recheck)
        settingsWatchState.stop("interrupt")
        handler.removeCallbacks(settingsWatch)
        settingsWatchScheduled = false
        GuardDiagnostics.watch("stopped: interrupt")
        GuardedScreen.clear()
    }

    override fun onDestroy() {
        GuardedScreen.windowChanged()
        destroyed = true
        windowEpoch++
        inspectionSequence++
        inspectionPending = false
        scanHandler.removeCallbacksAndMessages(null)
        scanThread.quitSafely()
        GuardedScreen.onBackRequested = null
        GuardedScreen.onLockDrawn = null
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        dismissInspectionCover("service destroyed")
        handler.removeCallbacks(recheck)
        settingsWatchState.stop("destroy")
        handler.removeCallbacks(settingsWatch)
        settingsWatchScheduled = false
        GuardDiagnostics.watch("stopped: destroy")
        GuardedScreen.clear()
        super.onDestroy()
    }

    private fun startBackNavigation(pkg: String): Boolean {
        if (pkg != UninstallGuard.SETTINGS_PACKAGE) return false
        if (backNavigation != null) return backTarget == pkg
        val cover = createLoadingCover()
        if (!attachLoadingCover(cover)) return false
        navigationCover = cover
        dismissInspectionCover("back-navigation cover attached")
        backTarget = pkg
        backNavigation = GuardBackNavigation(pkg, packageName, SystemClock.elapsedRealtime())
        GuardedScreen.backNavigationPackage = pkg
        handler.post(navigationPoller)
        return true
    }

    private fun beginInspectionCover(reason: String) {
        if (destroyed || backNavigation != null || LockActivity.visiblePackage != null ||
            !GuardedScreen.needsSettingsWatch()) return
        inspectionGate.begin(SystemClock.elapsedRealtime())
        if (inspectionCover != null) return
        val cover = createLoadingCover(getString(R.string.guard_checking_settings))
        if (!attachLoadingCover(cover)) {
            inspectionGate.clear()
            GuardDiagnostics.cover("could not attach inspection cover; requesting PIN")
            GuardedScreen.set(UninstallGuard.SETTINGS_PACKAGE)
            return
        }
        inspectionCover = cover
        GuardDiagnostics.cover("visible: $reason")
        handler.post(inspectionCoverPoller)
    }

    private fun dismissInspectionCover(reason: String) {
        handler.removeCallbacks(inspectionCoverPoller)
        val cover = inspectionCover
        inspectionCover = null
        inspectionGate.clear()
        if (cover != null) {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(cover) }
            GuardDiagnostics.cover("hidden: $reason")
        }
    }

    private fun createLoadingCover(status: String? = null): View {
        val palette = LockPalette.resolve(this)
        val indicatorSize = (32 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setBackgroundColor(palette.background)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val content = LinearLayout(this@OpenLockGuardAccessibilityService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            content.addView(
                ProgressBar(this@OpenLockGuardAccessibilityService).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.accent)
                },
                LinearLayout.LayoutParams(indicatorSize, indicatorSize),
            )
            if (status != null) content.addView(TextView(this@OpenLockGuardAccessibilityService).apply {
                text = status
                setTextColor(palette.accent)
                textSize = 14f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * resources.displayMetrics.density).toInt()
            })
            addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun attachLoadingCover(cover: View): Boolean {
        // A non-focusable accessibility overlay blocks touches while keeping
        // the underlying Settings window available for global Back/inspection.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        )
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        return runCatching { manager.addView(cover, params) }.isSuccess
    }

    private fun advanceBackNavigation(pkg: String?, guarded: Boolean, contentReady: Boolean) {
        val navigation = backNavigation ?: return
        val action = navigation.next(pkg, guarded, contentReady,
            LockActivity.visiblePackage == backTarget, SystemClock.elapsedRealtime())
        when (action) {
            GuardBackNavigation.Action.WAIT -> Unit
            GuardBackNavigation.Action.BACK -> {
                val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
                GuardDiagnostics.launch("Settings cancellation: Back accepted=$accepted")
            }
            GuardBackNavigation.Action.HOME -> {
                val accepted = performGlobalAction(GLOBAL_ACTION_HOME)
                GuardDiagnostics.launch("Settings cancellation fallback: Home accepted=$accepted")
            }
            GuardBackNavigation.Action.RESTORE_LOCK -> {
                // Home did not leave Settings. Keep the cover until the lock
                // actually resumes; never grant an unlock to break a loop.
                val intent = Intent(this, LockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putExtra(LockActivity.EXTRA_PACKAGE, backTarget)
                    putExtra(LockActivity.EXTRA_GUARDED_SETTINGS, true)
                }
                runCatching { startActivity(intent) }.onFailure {
                    GuardDiagnostics.launch("Settings cancellation: restore lock failed: ${it.javaClass.simpleName}")
                }
            }
            GuardBackNavigation.Action.COMPLETE -> finishBackNavigation()
        }
    }

    private fun finishBackNavigation() {
        val target = backTarget
        handler.removeCallbacks(navigationPoller)
        navigationCover?.let { cover ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(cover) }
        }
        navigationCover = null
        backNavigation = null
        backTarget = null
        GuardedScreen.backNavigationPackage = null
        if (target != null) GuardedScreen.onBackFinished?.invoke(target)
    }

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val inToolbar: Boolean,
        val clickableParent: Boolean,
        val inList: Boolean,
    )

    private data class FlattenedWindow(val nodes: List<ScreenNode>, val complete: Boolean)

    @Suppress("DEPRECATION")
    private fun flatten(
        root: AccessibilityNodeInfo,
        initialNodes: List<ScreenNode>,
        trace: GuardDiagnostics.ReadToken?,
        shouldStop: (List<ScreenNode>) -> Boolean,
    ): FlattenedWindow {
        val out = ArrayList(initialNodes)
        val stack = ArrayDeque<PendingNode>()
        stack.addLast(PendingNode(root, false, false, false))
        var visited = 0
        var childrenAvailable = true
        var nextMatchCheck = MATCH_CHECK_INTERVAL
        try {
            while (stack.isNotEmpty() && visited++ < MAX_NODES) {
                val (node, parentToolbar, clickableParent, parentList) = stack.removeLast()
                try {
                    val id = node.viewIdResourceName
                    val cls = node.className?.toString()
                    val inToolbar = parentToolbar || id?.substringAfter(":id/") in TOOLBAR_IDS ||
                        cls?.endsWith("Toolbar") == true
                    val inList = parentList || node.collectionInfo != null ||
                        cls?.endsWith("RecyclerView") == true || cls?.endsWith("ListView") == true
                    val clickable = node.isClickable || clickableParent ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    val pane = if (Build.VERSION.SDK_INT >= 28 && (node.isVisibleToUser || node === root)) {
                        node.paneTitle?.toString()
                    } else null
                    val heading = Build.VERSION.SDK_INT >= 28 && node.isHeading
                    if (node.isVisibleToUser) {
                        val text = if (node.isPassword || node.isEditable) null else node.text?.toString()
                        val description = if (node.isPassword || node.isEditable) null else node.contentDescription?.toString()
                        out.add(ScreenNode(id, text ?: description, cls, node.isCheckable, clickable, inToolbar, pane, heading, inList))
                        if (text != null && description != null && text != description) {
                            out.add(ScreenNode(id, description, cls, node.isCheckable, clickable, inToolbar, pane, heading, inList))
                        }
                        if (Build.VERSION.SDK_INT >= 30 && !node.isPassword && !node.isEditable) node.stateDescription?.toString()?.let {
                            out.add(ScreenNode(id, it, cls, node.isCheckable, clickable, inToolbar, inList = inList))
                        }
                    } else if (pane != null) {
                        out.add(ScreenNode(id, null, cls, paneTitle = pane))
                    }
                    // Check early headers frequently, then widen the interval
                    // (32/64/128/...) so negative screens are not rescanned 75
                    // times. The caller always checks the final accumulated tree.
                    val serviceTitle = out.subList((out.size - 3).coerceAtLeast(0), out.size).any {
                        UninstallGuard.isOwnServiceTitle(it, serviceLabel)
                    }
                    if (serviceTitle || visited == 1 || visited >= nextMatchCheck) {
                        if (shouldStop(out)) return FlattenedWindow(out, complete = false)
                        if (visited >= nextMatchCheck) {
                            nextMatchCheck = if (nextMatchCheck < 32) nextMatchCheck + MATCH_CHECK_INTERVAL
                                else nextMatchCheck * 2
                        }
                    }
                    for (i in node.childCount - 1 downTo 0) {
                        if (trace != null) GuardDiagnostics.readStage(trace, "child[$i]", visited)
                        val child = node.getChild(i)
                        if (child != null) stack.addLast(PendingNode(child, inToolbar, clickable, inList))
                        else childrenAvailable = false
                    }
                } finally {
                    if (node !== root) node.recycle()
                }
            }
            return FlattenedWindow(out, complete = stack.isEmpty() && childrenAvailable)
        } finally {
            for (pending in stack) if (pending.node !== root) pending.node.recycle()
        }
    }

    private fun captureDiagnostics(pkg: String, cls: String?, id: Int, nodes: List<ScreenNode>, guarded: Boolean, scanMs: Long) {
        if (!GuardDiagnostics.isCapturing()) return
        val summary = "package=$pkg class=$cls windowId=$id guarded=$guarded nodesRead=${nodes.size} scanMs=$scanMs " +
            "(positive scans may stop early)"
        val titleKey = nodes.filter { UninstallGuard.isPageTitle(it) || it.paneTitle != null }
            .take(12).joinToString { (it.paneTitle ?: it.text.orEmpty()).take(120) }
        val snapshot = buildString {
            appendLine(summary)
            nodes.take(120).forEach { node ->
                fun clean(value: String?) = value?.replace('\n', ' ')?.replace('\r', ' ')?.take(120)
                appendLine("id=${clean(node.viewId)} class=${clean(node.className)} " +
                    "toolbar=${node.inToolbar} heading=${node.heading} list=${node.inList} " +
                    "checkable=${node.checkable} clickable=${node.clickable} " +
                    "pane=${clean(node.paneTitle)} text=${clean(node.text)}")
            }
        }
        GuardDiagnostics.snapshot("$pkg/$cls/$titleKey", snapshot)
    }

    private fun logDiagnostics(pkg: String, cls: String?, nodes: List<ScreenNode>, guarded: Boolean) {
        if (!BuildConfig.GUARD_DIAGNOSTICS) return
        val now = SystemClock.elapsedRealtime()
        val signature = listOf(pkg, cls, guarded, nodes.filter(UninstallGuard::isPageTitle),
            nodes.map { it.viewId to it.checkable }).hashCode()
        if (signature == lastDiagnosticSignature && now - lastDiagnosticAt < 1_000L) return
        lastDiagnosticSignature = signature
        lastDiagnosticAt = now
        Log.d(TAG, "package=$pkg windowClass=$cls guarded=$guarded nodes=${nodes.size}")
        // Only page titles and our labels are logged as text; arbitrary app
        // rows, summaries and search queries stay redacted.
        nodes.take(120).forEach { node ->
            val safeText = if (node.inToolbar || node.text == serviceLabel || node.text == ownLabel) {
                node.text?.replace('\n', ' ')?.replace('\r', ' ')?.take(160)
            } else if (node.text != null) "<redacted>" else null
            Log.d(TAG, "id=${node.viewId} class=${node.className} toolbar=${node.inToolbar} " +
                "checkable=${node.checkable} clickable=${node.clickable} text=$safeText")
        }
    }

    private companion object {
        const val TAG = "OpenLockGuard"
        const val MAX_NODES = 600
        const val MATCH_CHECK_INTERVAL = 8
        const val SETTINGS_WATCH_MS = 150L
        const val WATCH_SOURCE = "window-watchdog"
        // Container rows only: a TextView may be a search/input field on OEM
        // builds. Unrecognised sources use the normal detail-title fallback.
        val ENTRY_CLICK_CLASSES = setOf("android.widget.LinearLayout", "android.widget.RelativeLayout",
            "android.widget.FrameLayout", "android.view.ViewGroup")
        val TOOLBAR_IDS = setOf("action_bar", "toolbar", "collapsing_toolbar", "action_bar_container")
        val PAGE_TITLE_IDS = setOf("action_bar_title", "toolbar_title", "collapsing_toolbar_title")
        val TITLE_RESOURCES = listOf(
            "special_access", "special_access_title", "special_access_settings_title",
            "overlay_settings", "draw_overlay", "alarms_and_reminders_title",
            "usage_access", "usage_access_title", "write_settings", "manage_external_storage_title",
            "picture_in_picture_title", "install_other_apps", "notification_access", "manage_zen_access_title",
        )
    }
}

/** Current guarded OS window, shared with the monitor in this process. */
object GuardedScreen {
    @Volatile private var pkg: String? = null
    @Volatile var windowRevision: Long = 0L
        private set
    private var timing: GuardDiagnostics.DetectionTiming? = null

    /** Main-thread notification; invalidates usage queries for a previous window. */
    fun windowChanged(windowPackage: String? = null) {
        windowRevision++
        onWindowChanged?.invoke(windowPackage)
    }

    fun timingFor(windowPackage: String): GuardDiagnostics.DetectionTiming? =
        timing.takeIf { pkg == windowPackage }

    // Both services use the main looper. The monitor unregisters on destroy.
    var onDetected: ((String) -> Unit)? = null
    var onBackRequested: ((String) -> Boolean)? = null
    var onBackFinished: ((String) -> Unit)? = null
    var onLockDrawn: ((String) -> Unit)? = null
    var shouldWatchSettings: (() -> Boolean)? = null
    var onWindowChanged: ((String?) -> Unit)? = null
    var backNavigationPackage: String? = null

    fun requestBack(windowPackage: String): Boolean = onBackRequested?.invoke(windowPackage) == true

    fun isNavigatingBack(windowPackage: String): Boolean = backNavigationPackage == windowPackage

    fun needsSettingsWatch(): Boolean = shouldWatchSettings?.invoke() == true

    fun set(windowPackage: String, detection: GuardDiagnostics.DetectionTiming? = null) {
        pkg = windowPackage
        timing = detection
        onDetected?.invoke(windowPackage)
    }

    fun clear() {
        pkg = null
        timing = null
    }

    fun isShowing(windowPackage: String): Boolean = pkg == windowPackage
}
