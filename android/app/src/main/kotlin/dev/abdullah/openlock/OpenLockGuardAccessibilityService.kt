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
    private var watchingSettings = false
    private val settingsWatch = Runnable {
        if (!destroyed && watchingSettings) {
            if (GuardedScreen.needsSettingsWatch() && LockActivity.visiblePackage == null && backNavigation == null) {
                // Window/click notifications can arrive >1 s late. Read the
                // CURRENT root without borrowing a class/title/time from them.
                inspectWindow(eventTiming = null, source = WATCH_SOURCE)
            } else {
                scheduleSettingsWatch()
            }
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
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (detectServiceEntryClick(event, eventTiming)) scheduleRechecks()
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                GuardedScreen.windowChanged()
                windowEpoch++
                windowEventTiming = eventTiming
                windowPackage = event.packageName?.toString()
                windowClass = event.className?.toString()
                windowId = event.windowId
                handler.removeCallbacks(recheck)
                watchingSettings = windowPackage == UninstallGuard.SETTINGS_PACKAGE
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
        handler.removeCallbacks(settingsWatch)
        if (!destroyed && watchingSettings) handler.postDelayed(settingsWatch, SETTINGS_WATCH_MS)
    }

    private fun inspectWindow(eventPaneTitle: String? = null, eventPackage: String? = null,
                              eventWindowId: Int = -1, eventTiming: GuardDiagnostics.EventTiming? = windowEventTiming,
                              source: String = "window-scan") {
        if (destroyed) return
        if (source == WATCH_SOURCE && inspectionPending) {
            // A fallback must never evict a queued event read: that read may
            // carry a real class/pane title needed by an OEM's detail layout.
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
            val result = if (epoch == windowEpoch) readWindow(eventPaneTitle, eventPackage, eventWindowId,
                expectedPackage, expectedClass, expectedId, epoch).copy(queuedAt = queuedAt,
                    event = eventTiming) else null
            handler.post {
                if (request == inspectionSequence) inspectionPending = false
                if (!destroyed && epoch == windowEpoch && result != null) applyInspection(result, source)
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
    )

    @Suppress("DEPRECATION")
    private fun readWindow(
        eventPaneTitle: String?, eventPackage: String?, eventWindowId: Int,
        expectedPackage: String?, expectedClass: String?, expectedId: Int, epoch: Long,
    ): Inspection {
        val scanStartedAt = SystemClock.elapsedRealtime()
        val root = try { rootInActiveWindow } catch (error: RuntimeException) {
            return Inspection(null, startedAt = scanStartedAt, reason = "Root read failed: ${error.javaClass.simpleName}")
        }
        if (root == null) {
            return Inspection(null, startedAt = scanStartedAt, reason = "Active window root is unavailable.")
        }
        try {
            val rootPkg = root.packageName?.toString()
            if (rootPkg == null || !UninstallGuard.isCandidatePackage(rootPkg)) {
                // Unknown OEM packages are identified without reading their text.
                return Inspection(rootPkg, expectedClass, contentReady = true, startedAt = scanStartedAt,
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
            val nodes = if (guarded) initialNodes else flatten(root, initialNodes) { partial ->
                if (epoch != windowEpoch) return@flatten true
                // All positive rules require only accumulated evidence; extra
                // descendants cannot invalidate a match. Negative results still
                // require the complete bounded walk, including Back navigation.
                guarded = matches(partial)
                guarded
            }
            if (!guarded) guarded = matches(nodes)
            val matchedAt = SystemClock.elapsedRealtime()
            return Inspection(rootPkg, cls, root.windowId, nodes, guarded, nodes.isNotEmpty(),
                startedAt = scanStartedAt, finishedAt = matchedAt)
        } catch (error: RuntimeException) {
            // A transitioning Settings tree can become invalid while walking it.
            // Keep the service alive and let the content event/rechecks retry.
            return Inspection(null, startedAt = scanStartedAt,
                reason = "Window read failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            root.recycle()
        }
    }

    /** Only immutable scan data crosses threads; all UI/session state stays here. */
    private fun applyInspection(result: Inspection, source: String) {
        val pkg = result.pkg
        val timing = if (pkg != null) GuardDiagnostics.scan(pkg, result.startedAt, result.finishedAt,
            result.guarded, result.queuedAt,
            event = result.event?.takeIf { it.pkg == pkg && it.windowId == result.id }, source = source) else null
        GuardDiagnostics.inspection(source, pkg, result.id, result.startedAt, result.finishedAt, result.guarded)
        if (pkg != null) watchingSettings = pkg == UninstallGuard.SETTINGS_PACKAGE
        advanceBackNavigation(pkg, result.guarded, result.contentReady)
        if (result.guarded && pkg != null) GuardedScreen.set(pkg, timing) else GuardedScreen.clear()
        if (result.reason != null) {
            if (pkg != packageName) GuardDiagnostics.snapshot("read:$pkg", result.reason)
        } else if (pkg != null) {
            captureDiagnostics(pkg, result.cls, result.id, result.nodes, result.guarded,
                result.finishedAt - result.startedAt)
        }
        if (pkg != null) logDiagnostics(pkg, result.cls, result.nodes, result.guarded)
        // Schedule AFTER delivery, never at fixed rate: one serial worker and
        // no pile-up of watchdog reads when Settings/Binder responds slowly.
        scheduleSettingsWatch()
    }

    override fun onInterrupt() {
        GuardedScreen.windowChanged()
        windowEpoch++
        inspectionSequence++
        inspectionPending = false
        scanHandler.removeCallbacksAndMessages(null)
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        handler.removeCallbacks(recheck)
        watchingSettings = false
        handler.removeCallbacks(settingsWatch)
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
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        handler.removeCallbacks(recheck)
        watchingSettings = false
        handler.removeCallbacks(settingsWatch)
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

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val inToolbar: Boolean,
        val clickableParent: Boolean,
        val inList: Boolean,
    )

    @Suppress("DEPRECATION")
    private fun flatten(
        root: AccessibilityNodeInfo,
        initialNodes: List<ScreenNode>,
        shouldStop: (List<ScreenNode>) -> Boolean,
    ): List<ScreenNode> {
        val out = ArrayList(initialNodes)
        val stack = ArrayDeque<PendingNode>()
        stack.addLast(PendingNode(root, false, false, false))
        var visited = 0
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
                        if (shouldStop(out)) return out
                        if (visited >= nextMatchCheck) {
                            nextMatchCheck = if (nextMatchCheck < 32) nextMatchCheck + MATCH_CHECK_INTERVAL
                                else nextMatchCheck * 2
                        }
                    }
                    for (i in node.childCount - 1 downTo 0) {
                        node.getChild(i)?.let { stack.addLast(PendingNode(it, inToolbar, clickable, inList)) }
                    }
                } finally {
                    if (node !== root) node.recycle()
                }
            }
        } finally {
            for (pending in stack) if (pending.node !== root) pending.node.recycle()
        }
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
    fun windowChanged() { windowRevision++ }

    fun timingFor(windowPackage: String): GuardDiagnostics.DetectionTiming? =
        timing.takeIf { pkg == windowPackage }

    // Both services use the main looper. The monitor unregisters on destroy.
    var onDetected: ((String) -> Unit)? = null
    var onBackRequested: ((String) -> Boolean)? = null
    var onBackFinished: ((String) -> Unit)? = null
    var shouldWatchSettings: (() -> Boolean)? = null
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
