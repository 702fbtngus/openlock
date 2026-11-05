package dev.abdullah.openlock

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Inspects candidate OS windows in memory; the monitor owns PIN/session policy. */
class OpenLockGuardAccessibilityService : AccessibilityService() {
    private var windowPackage: String? = null
    private var windowClass: String? = null
    private var windowId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val recheck = Runnable { inspectWindow() }
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
            GuardDiagnostics.snapshot("no-root", "Active window root is unavailable.")
            return
        }
        try {
            val rootPkg = root.packageName?.toString()
            if (rootPkg == null || !UninstallGuard.isCandidatePackage(rootPkg)) {
                GuardedScreen.clear()
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
            if (guarded) GuardedScreen.set(rootPkg) else GuardedScreen.clear()
            captureDiagnostics(rootPkg, cls, root.windowId, nodes, guarded)
            logDiagnostics(rootPkg, cls, nodes, guarded)
        } catch (error: RuntimeException) {
            // A transitioning Settings tree can become invalid while walking it.
            // Keep the service alive and let the content event/rechecks retry.
            GuardedScreen.clear()
            GuardDiagnostics.snapshot("read-error", "Window read failed: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            root.recycle()
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(recheck)
        GuardedScreen.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recheck)
        GuardedScreen.clear()
        super.onDestroy()
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

    fun set(windowPackage: String) {
        pkg = windowPackage
        onDetected?.invoke(windowPackage)
    }

    fun clear() {
        pkg = null
    }

    fun isShowing(windowPackage: String): Boolean = pkg == windowPackage
}
