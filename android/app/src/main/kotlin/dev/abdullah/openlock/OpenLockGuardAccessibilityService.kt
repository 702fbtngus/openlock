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
import android.widget.ProgressBar
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
    private val appInfoEntry by lazy {
        // Local manifest/resources only: event callbacks must not wait on the
        // worker's synchronized ownLabel lazy or a PackageManager lookup.
        val info = applicationInfo
        val label = info.nonLocalizedLabel?.toString() ?: info.labelRes.takeIf { it != 0 }?.let {
            runCatching { resources.getString(it) }.getOrNull()
        } ?: packageName
        AppInfoEntryGuard(label, packageName)
    }
    // Populated on the scan worker. A click must not wait for PackageManager
    // resource lookup, or for a synchronized lazy currently loading there.
    @Volatile private var clickSpecialAccessTitles: Set<String> = emptySet()

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
        inspectWindow()
        scheduleRechecks()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventTiming = GuardDiagnostics.event(
            "type=${event.eventType} package=${event.packageName} " +
                "class=${event.className} windowId=${event.windowId} changes=${event.contentChangeTypes}",
            event.packageName?.toString(), event.windowId, event.eventType, event.eventTime)
        if (!OpenLockMonitorService.isRunning) MonitorStartup.restoreAsync(this, "accessibility_event")
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = ScreenNode(null, event.contentDescription?.toString(), className = event.className?.toString())
                val armed = !event.isPassword && appInfoEntry.longClicked(event.packageName?.toString(),
                    node, event.text.map { it.toString() }, SystemClock.elapsedRealtime())
                if (event.isPassword) appInfoEntry.clear()
                if (armed) GuardDiagnostics.snapshot("own-launcher-long-click",
                    "Own Samsung launcher icon long-clicked; waiting for an App info action. class=${node.className}")
                return
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (detectGuardEntryClick(event, eventTiming)) scheduleRechecks()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                GuardedScreen.windowChanged(event.packageName?.toString())
                windowEpoch++
                windowEventTiming = eventTiming
                windowPackage = event.packageName?.toString()
                windowClass = event.className?.toString()
                windowId = event.windowId
                appInfoEntry.windowChanged(windowPackage)
                handler.removeCallbacks(recheck)
                settingsWatchState.observePackage(windowPackage, SystemClock.elapsedRealtime())
                scheduleSettingsWatch()
                if (windowPackage?.let(UninstallGuard::isCandidatePackage) == true) scheduleRechecks()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString()?.let(UninstallGuard::isCandidatePackage) != true) return
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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == UninstallGuard.SETTINGS_PACKAGE &&
            UninstallGuard.isDeviceAdminScreenClass(UninstallGuard.SETTINGS_PACKAGE, windowClass)) {
            requestEntryLock("device-admin-window", eventTiming, SystemClock.elapsedRealtime())
            return // Class metadata is sufficient; scheduled rechecks still run.
        }
        inspectWindow(paneTitle, event.packageName?.toString(), event.windowId, eventTiming)
    }

    private fun detectGuardEntryClick(event: AccessibilityEvent, eventTiming: GuardDiagnostics.EventTiming?): Boolean {
        val pkg = event.packageName?.toString()
        if (event.isPassword || (pkg != UninstallGuard.SETTINGS_PACKAGE && pkg != AppInfoEntryGuard.SAMSUNG_LAUNCHER) ||
            (pkg == UninstallGuard.SETTINGS_PACKAGE && windowPackage != UninstallGuard.SETTINGS_PACKAGE)) return false
        val startedAt = SystemClock.elapsedRealtime()
        val cls = event.className?.toString() ?: return false
        // Use only event metadata here: fetching event.source/root can itself
        // wait on Settings' Binder response, defeating this fast path.
        val node = ScreenNode(null, event.contentDescription?.toString(), className = cls)
        val texts = event.text.map { it.toString() }
        if (pkg == AppInfoEntryGuard.SAMSUNG_LAUNCHER && appInfoEntry.hasLauncherContext(startedAt) &&
            GuardDiagnostics.isCapturing()) {
            GuardDiagnostics.snapshot("own-launcher-action", "Own-icon context; class=$cls " +
                "description=${node.text?.take(120)} texts=${texts.take(4).map { it.take(120) }}")
        }
        val appInfo = appInfoEntry.clicked(pkg, event.windowId, windowClass, node, texts, startedAt)
        val ownService = pkg == UninstallGuard.SETTINGS_PACKAGE && event.windowId == windowId && cls in ENTRY_CLICK_CLASSES &&
            UninstallGuard.isOwnServiceEntryClick(UninstallGuard.SETTINGS_PACKAGE, node, texts, serviceLabel)
        // A menu popup can have its own ID before its window-state event has
        // arrived. Exact Special access click metadata is sufficient there;
        // the original own-service path retains its same-window validation.
        val specialAccess = event.windowId >= 0 && UninstallGuard.isSpecialAccessEntryClick(
            pkg.orEmpty(), windowClass, node, texts, clickSpecialAccessTitles)
        val deviceAdmin = event.windowId >= 0 && UninstallGuard.isDeviceAdminEntryClick(pkg.orEmpty(), windowClass, node, texts)
        val source = when {
            ownService -> "service-entry-click"
            specialAccess -> "special-access-entry-click"
            deviceAdmin -> "device-admin-entry-click"
            appInfo == AppInfoEntryGuard.Entry.SETTINGS_APP_ROW -> "own-app-row-click"
            appInfo == AppInfoEntryGuard.Entry.LAUNCHER_APP_INFO -> "launcher-app-info-click"
            else -> return false
        }
        requestEntryLock(source, eventTiming, startedAt)
        return true
    }

    private fun requestEntryLock(source: String, eventTiming: GuardDiagnostics.EventTiming?, startedAt: Long) {
        val timing = GuardDiagnostics.scan(UninstallGuard.SETTINGS_PACKAGE, startedAt, SystemClock.elapsedRealtime(), true,
            event = eventTiming, source = source)
        GuardDiagnostics.snapshot(source, "Protected Settings entry detected ($source); requesting existing PIN.")
        windowEpoch++ // Invalidate an older list scan still in progress.
        GuardedScreen.set(UninstallGuard.SETTINGS_PACKAGE, timing)
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
                        accepted = accepted, outcome = outcome, queuedAt = queuedAt)
                    if (accepted && result != null) applyInspection(result, source)
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
            fun matches(nodes: List<ScreenNode>): Boolean {
                val specialAccessTitles = if (rootPkg == UninstallGuard.SETTINGS_PACKAGE) {
                    localizedTitles.also { clickSpecialAccessTitles = it }
                } else emptySet()
                return UninstallGuard.isGuardedScreen(
                    windowPackage = rootPkg,
                    windowClass = cls,
                    nodes = nodes,
                    ownLabel = ownLabel,
                    ownPackage = packageName,
                    ownServiceLabel = serviceLabel,
                    localizedSpecialAccessTitles = specialAccessTitles,
                )
            }
            // These are the SAME positive rules as the full matcher. A known
            // class or confirmed pane title can prove a match without a walk.
            var guarded = UninstallGuard.isSpecialAccessScreenClass(rootPkg, cls) ||
                UninstallGuard.isDeviceAdminScreenClass(rootPkg, cls) ||
                (initialNodes.isNotEmpty() && matches(initialNodes))
            val nodes = if (guarded) initialNodes else flatten(root, initialNodes, trace, epoch) { partial ->
                if (epoch != windowEpoch) return@flatten true
                // All positive rules require only accumulated evidence; extra
                // descendants cannot invalidate a match. Negative results still
                // require the complete bounded walk, including Back navigation.
                guarded = matches(partial)
                guarded
            }
            GuardDiagnostics.readStage(trace, "match", nodes.size)
            if (!guarded) guarded = matches(nodes)
            val matchedAt = SystemClock.elapsedRealtime()
            return Inspection(rootPkg, cls, root.windowId, nodes, guarded, nodes.isNotEmpty(),
                startedAt = scanStartedAt, finishedAt = matchedAt, rootReadMs = rootReadMs)
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
    private fun applyInspection(result: Inspection, source: String) {
        val pkg = result.pkg
        val timing = if (pkg != null) GuardDiagnostics.scan(pkg, result.startedAt, result.finishedAt,
            result.guarded, result.queuedAt,
            event = result.event?.takeIf { it.pkg == pkg && it.windowId == result.id }, source = source) else null
        settingsWatchState.observePackage(pkg, SystemClock.elapsedRealtime())
        appInfoEntry.observeScreen(pkg, result.cls, result.id, result.nodes)
        advanceBackNavigation(pkg, result.guarded, result.contentReady)
        if (result.guarded && pkg != null) GuardedScreen.set(pkg, timing) else GuardedScreen.clear()
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
        appInfoEntry.clear()
        scanHandler.removeCallbacksAndMessages(null)
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
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
        appInfoEntry.clear()
        windowEpoch++
        inspectionSequence++
        inspectionPending = false
        scanHandler.removeCallbacksAndMessages(null)
        scanThread.quitSafely()
        GuardedScreen.onBackRequested = null
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
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
        val palette = LockPalette.resolve(this)
        val indicatorSize = (32 * resources.displayMetrics.density).toInt()
        val cover = FrameLayout(this).apply {
            setBackgroundColor(palette.background)
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(
                ProgressBar(this@OpenLockGuardAccessibilityService).apply {
                    isIndeterminate = true
                    indeterminateTintList = ColorStateList.valueOf(palette.accent)
                },
                FrameLayout.LayoutParams(indicatorSize, indicatorSize, Gravity.CENTER),
            )
        }
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
        if (runCatching { manager.addView(cover, params) }.isFailure) return false
        navigationCover = cover
        backTarget = pkg
        backNavigation = GuardBackNavigation(pkg, packageName, SystemClock.elapsedRealtime())
        GuardedScreen.backNavigationPackage = pkg
        handler.post(navigationPoller)
        return true
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

    private class PendingNode(
        val node: AccessibilityNodeInfo,
        var inToolbar: Boolean,
        var clickableParent: Boolean,
        var inList: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun flatten(
        root: AccessibilityNodeInfo,
        initialNodes: List<ScreenNode>,
        trace: GuardDiagnostics.ReadToken?,
        epoch: Long,
        shouldStop: (List<ScreenNode>) -> Boolean,
    ): List<ScreenNode> {
        val out = ArrayList(initialNodes)
        var nextMatchCheck = MATCH_CHECK_INTERVAL
        walkGuardTree(
            root = PendingNode(root, false, false, false),
            limit = MAX_NODES,
            visit = { pending, visited ->
                if (epoch != windowEpoch) true else {
                    val node = pending.node
                    val id = node.viewIdResourceName
                    val cls = node.className?.toString()
                    val inToolbar = pending.inToolbar || id?.substringAfter(":id/") in TOOLBAR_IDS ||
                        cls?.endsWith("Toolbar") == true
                    val inList = pending.inList || node.collectionInfo != null ||
                        cls?.endsWith("RecyclerView") == true || cls?.endsWith("ListView") == true
                    val clickable = node.isClickable || pending.clickableParent ||
                        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                    pending.inToolbar = inToolbar
                    pending.inList = inList
                    pending.clickableParent = clickable
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
                    val identity = out.subList((out.size - 3).coerceAtLeast(0), out.size).any {
                        UninstallGuard.isOwnServiceTitle(it, serviceLabel) ||
                            UninstallGuard.isOwnAppHeader(it, ownLabel, packageName)
                    }
                    if (identity || visited == 1 || visited >= nextMatchCheck) {
                        val matched = shouldStop(out)
                        if (visited >= nextMatchCheck) {
                            nextMatchCheck = if (nextMatchCheck < 32) nextMatchCheck + MATCH_CHECK_INTERVAL
                                else nextMatchCheck * 2
                        }
                        matched
                    } else false
                }
            },
            childCount = { it.node.childCount },
            childAt = { parent, index, visited ->
                if (trace != null) GuardDiagnostics.readStage(trace, "child[$index]", visited)
                parent.node.getChild(index)?.let {
                    PendingNode(it, parent.inToolbar, parent.clickableParent, parent.inList)
                }
            },
            release = { if (it.node !== root) it.node.recycle() },
        )
        return out
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
