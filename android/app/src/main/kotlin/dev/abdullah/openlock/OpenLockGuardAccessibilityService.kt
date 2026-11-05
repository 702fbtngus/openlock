package dev.abdullah.openlock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Inspects candidate OS windows in memory; the monitor owns PIN/session policy. */
class OpenLockGuardAccessibilityService : AccessibilityService() {
    private var windowPackage: String? = null
    private var windowClass: String? = null
    private var windowId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { inspectWindow() }
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
        GuardedScreen.onBackRequested = ::startBackNavigation
        inspectWindow()
        scheduleRechecks()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        GuardDiagnostics.event("type=${event.eventType} package=${event.packageName} " +
            "class=${event.className} windowId=${event.windowId} changes=${event.contentChangeTypes}")
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowPackage = event.packageName?.toString()
                windowClass = event.className?.toString()
                windowId = event.windowId
                handler.removeCallbacks(recheck)
                if (windowPackage?.let(UninstallGuard::isCandidatePackage) == true) scheduleRechecks()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (event.packageName?.toString()?.let(UninstallGuard::isCandidatePackage) != true) return
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
        inspectWindow(paneTitle, event.packageName?.toString(), event.windowId)
    }

    private fun scheduleRechecks() {
        // The initial event can precede its title/controls. Read the CURRENT
        // root each time, never a retained tree from a previous screen.
        for (delay in longArrayOf(75L, 200L, 500L)) handler.postDelayed(recheck, delay)
    }

    @Suppress("DEPRECATION")
    private fun inspectWindow(eventPaneTitle: String? = null, eventPackage: String? = null, eventWindowId: Int = -1) {
        val root = rootInActiveWindow
        if (root == null) {
            GuardedScreen.clear()
            advanceBackNavigation(null, false, false)
            GuardDiagnostics.snapshot("no-root", "Active window root is unavailable.")
            return
        }
        try {
            val rootPkg = root.packageName?.toString()
            if (rootPkg == null || !UninstallGuard.isCandidatePackage(rootPkg)) {
                GuardedScreen.clear()
                advanceBackNavigation(rootPkg, false, true)
                // Unknown OEM packages are identified without reading their text.
                if (rootPkg != packageName) GuardDiagnostics.snapshot("other-package:$rootPkg",
                    "Ignored package=$rootPkg class=$windowClass (not a candidate)")
                if (rootPkg != null) logDiagnostics(rootPkg, windowClass, emptyList(), false)
                return
            }
            val cls = windowClass.takeIf { windowPackage == rootPkg && windowId == root.windowId }
            val nodes = flatten(root).toMutableList()
            if (eventPaneTitle != null && eventPackage == rootPkg && eventWindowId == root.windowId) {
                nodes.add(ScreenNode(null, null, paneTitle = eventPaneTitle))
            }
            val guarded = UninstallGuard.isGuardedScreen(
                windowPackage = rootPkg,
                windowClass = cls,
                nodes = nodes,
                ownLabel = ownLabel,
                ownPackage = packageName,
                ownServiceLabel = serviceLabel,
                localizedSpecialAccessTitles = localizedTitles,
            )
            advanceBackNavigation(rootPkg, guarded, nodes.isNotEmpty())
            if (guarded) GuardedScreen.set(rootPkg) else GuardedScreen.clear()
            captureDiagnostics(rootPkg, cls, root.windowId, nodes, guarded)
            logDiagnostics(rootPkg, cls, nodes, guarded)
        } catch (error: RuntimeException) {
            // A transitioning Settings tree can become invalid while walking it.
            // Keep the service alive and let the content event/rechecks retry.
            GuardedScreen.clear()
            advanceBackNavigation(null, false, false)
            GuardDiagnostics.snapshot("read-error", "Window read failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        handler.removeCallbacks(recheck)
        GuardedScreen.clear()
    }

    override fun onDestroy() {
        GuardedScreen.onBackRequested = null
        if (backNavigation != null) performGlobalAction(GLOBAL_ACTION_HOME)
        finishBackNavigation()
        handler.removeCallbacks(recheck)
        GuardedScreen.clear()
        super.onDestroy()
    }

    private fun startBackNavigation(pkg: String): Boolean {
        if (pkg != UninstallGuard.SETTINGS_PACKAGE) return false
        if (backNavigation != null) return backTarget == pkg
        val cover = TextView(this).apply {
            setBackgroundColor(Color.rgb(18, 20, 24))
            setTextColor(Color.WHITE)
            text = getString(R.string.guard_returning_to_previous_screen)
            gravity = Gravity.CENTER
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
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
    private fun flatten(root: AccessibilityNodeInfo): List<ScreenNode> {
        val out = ArrayList<ScreenNode>()
        val stack = ArrayDeque<PendingNode>()
        stack.addLast(PendingNode(root, false, false, false))
        var visited = 0
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

    private fun captureDiagnostics(pkg: String, cls: String?, id: Int, nodes: List<ScreenNode>, guarded: Boolean) {
        if (!GuardDiagnostics.isCapturing()) return
        val summary = "package=$pkg class=$cls windowId=$id guarded=$guarded nodes=${nodes.size}"
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

    // Both services use the main looper. The monitor unregisters on destroy.
    var onDetected: ((String) -> Unit)? = null
    var onBackRequested: ((String) -> Boolean)? = null
    var onBackFinished: ((String) -> Unit)? = null
    var backNavigationPackage: String? = null

    fun requestBack(windowPackage: String): Boolean = onBackRequested?.invoke(windowPackage) == true

    fun isNavigatingBack(windowPackage: String): Boolean = backNavigationPackage == windowPackage

    fun set(windowPackage: String) {
        pkg = windowPackage
        onDetected?.invoke(windowPackage)
    }

    fun clear() {
        pkg = null
    }

    fun isShowing(windowPackage: String): Boolean = pkg == windowPackage
}
