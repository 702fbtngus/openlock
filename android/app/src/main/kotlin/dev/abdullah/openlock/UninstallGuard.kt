package dev.abdullah.openlock

/** One node of the active window's accessibility tree: its resource id (e.g.
 *  `com.android.settings:id/entity_header_title`) and its visible text or
 *  content description. */
data class ScreenNode(
    val viewId: String?,
    val text: String?,
    val className: String? = null,
    val checkable: Boolean = false,
    val clickable: Boolean = false,
    // Set only for toolbar descendants, never preference-row titles/headings.
    val inToolbar: Boolean = false,
    val paneTitle: String? = null,
    val heading: Boolean = false,
    val inList: Boolean = false,
)

/**
 * Decides whether the active window is an OS screen *about OpenLock itself*
 * that could remove it: its App info page (and sub-pages), its uninstall
 * confirmation, or the device-admin deactivation screen naming it. Also guards
 * its accessibility-service details and Special access (including other apps).
 *
 * Single-app screens are identified by what they show; generic activity names
 * alone cannot identify an app. The same Settings activities serve every app, and
 * lists that merely contain OpenLock's row (Apps list, Settings search
 * results, the device-admin list) must not match.
 */
object UninstallGuard {

    const val SETTINGS_PACKAGE = "com.android.settings"

    /** Resource-id suffixes of the single-app header in Settings: the App info
     *  header (SettingsLib EntityHeaderController, used by AOSP and One UI) and
     *  the device-admin activate/deactivate screen's app name. */
    private val HEADER_ID_SUFFIXES = listOf(
        ":id/entity_header_title",
        ":id/admin_name",
    )

    private const val DEVICE_ADMIN_ADD_CLASS = "deviceadminadd"

    private val PAGE_TITLE_IDS = setOf("action_bar_title", "toolbar_title", "collapsing_toolbar_title")
    private val TOGGLE_IDS = setOf("switch_widget", "switch_bar", "switchbar", "switch_text", "main_switch_bar")
    private val DIRECTION_MARKS = Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val TITLE_WHITESPACE = Regex("[\\s\\u00a0\\u202f]+")

    // Exact screen names, not "accessibility"/"specialaccess" substrings:
    // DeviceAdminSettings and generic ManageApplications must stay unguarded.
    private val ACCESSIBILITY_DETAILS = setOf(
        "toggleaccessibilityservicepreferencefragment", "accessibilityservicedetails",
    )
    private val SPECIAL_ACCESS_SCREENS = setOf(
        "specialaccesssettings", "specialaccess", "specialappaccesssettings",
        "drawoverlaysettings", "drawoverlaydetails",
        "overlaysettings", "appdrawoverlaysettings", "manageexternalstorage", "appmanageexternalstorage",
        "manageexternalstoragedetails", "alarmsandreminders", "alarmsandremindersapp",
        "alarmsandremindersdetails", "scheduleexactalarm",
        "usageaccesssettings", "appusageaccesssettings", "usageaccessdetails",
        "writesettings", "appwritesettings", "writesettingsdetails",
        "notificationaccesssettings", "notificationaccessdetails",
        "pictureinpicturesettings", "pictureinpicturedetails", "apppictureinpicturesettings",
        "manageexternalsources", "manageappexternalsources", "externalsourcesdetails",
        "highpowerapplications", "highpowerdetails", "premiumsmsaccess",
        "zenaccesssettings", "zenaccessdetails", "zenaccessdetailsettings",
        "turnscreenonsettings", "turnscreenondetails", "appturnscreenonsettings",
        "interactacrossprofilessettings", "interactacrossprofilesdetails",
        "unrestricteddataaccess", "vrlistenerssettings", "vrlistenersettings",
        "mediamanagementapps", "appmediamanagementapps", "mediamanagementappsdetails",
        "changewifistate", "changewifistatedetails", "longbackgroundtasks", "longbackgroundtasksapp",
    )

    // Fallback for generic SubSettings activities. Titles require the structural
    // checks below; plain preference rows/search results cannot trigger a lock.
    internal val specialAccessTitles = setOf(
        "Special access", "Special app access", "특별한 접근", "특수 앱 액세스", "특별한 앱 액세스",
        "Display over other apps", "Draw over other apps", "Appear on top",
        "다른 앱 위에 표시", "다른 앱 위에 그리기", "Alarms & reminders", "Alarms and reminders",
        "알람 및 리마인더", "알람 및 알림", "All files access", "모든 파일 접근", "모든 파일에 대한 접근",
        "Usage access", "Usage data access", "사용정보 접근", "사용 정보 접근", "사용 데이터 접근",
        "Change system settings", "Modify system settings", "시스템 설정 변경",
        "Install unknown apps", "출처를 알 수 없는 앱 설치", "Notification access", "알림 접근 허용",
        "Do Not Disturb access", "방해 금지 권한", "Picture-in-picture", "사진 속 사진", "화면 속 화면",
        "Unrestricted data", "제한 없는 데이터 사용", "Optimize battery usage", "배터리 사용량 최적화",
        "Premium text message services", "프리미엄 문자 메시지 서비스",
        "特殊应用权限", "特殊访问权限", "显示在其他应用的上层", "闹钟和提醒",
    )
    private val SPECIAL_ACCESS_CONTROLS = setOf(
        "Allow display over other apps", "Permit drawing over other apps",
        "Allow setting alarms and reminders", "Allow alarms and reminders", "Allow usage access",
        "Allow modifying system settings", "Allow access to manage all files", "Allow from this source",
        "다른 앱 위에 표시 허용", "알람 및 리마인더 허용", "사용정보 접근 허용", "사용 정보 접근 허용",
        "시스템 설정 변경 허용", "모든 파일에 대한 접근 허용", "이 출처 허용",
    )
    private val ON_LABELS = setOf("On", "In use", "사용 중", "사용중", "켜짐", "已开启")
    private val OFF_ACTIONS = setOf("Off", "Turn off", "Stop", "Disable", "사용 안 함", "사용 안함", "끄기", "중지", "关闭")

    // A Settings content event can arrive repeatedly while a page is loading.
    // Normalize fixed labels once, rather than running both regexes for every
    // candidate/label pair on the accessibility service's main thread.
    private val normalizedSpecialAccessTitles = specialAccessTitles.map(::normalizeText)
    private val normalizedSpecialAccessControls = SPECIAL_ACCESS_CONTROLS.map(::normalizeText)
    private val normalizedOnLabels = ON_LABELS.map(::normalizeText)
    private val normalizedOffActions = OFF_ACTIONS.map(::normalizeText)
    @Volatile private var localizedTitleCache: Pair<Set<String>, List<String>> = emptySet<String>() to emptyList()

    fun isPageTitle(node: ScreenNode): Boolean = node.inToolbar ||
        node.viewId?.substringAfter(":id/") in PAGE_TITLE_IDS ||
        // OEM/Compose headers may expose no toolbar ID. Exclude collection
        // children and clickable preference rows before accepting plain labels.
        (!node.inList && !node.clickable && (node.heading ||
            node.className?.endsWith("TextView") == true))

    /** The original single-app rules below remain separate from new protection. */
    fun isGuardedScreen(
        windowPackage: String,
        windowClass: String?,
        nodes: List<ScreenNode>,
        ownLabel: String,
        ownPackage: String,
        ownServiceLabel: String,
        localizedSpecialAccessTitles: Set<String> = emptySet(),
    ): Boolean {
        if (isAboutOwnApp(windowPackage, windowClass, nodes, ownLabel, ownPackage)) return true
        if (windowPackage != SETTINGS_PACKAGE) return false
        // A single-service pane/toolbar title already identifies the detail
        // page. Its switch and status can arrive in a later content event.
        if (nodes.any { isOwnServiceTitle(it, ownServiceLabel) }) return true
        // A distinctive activity/fragment name is useful even before its text
        // loads. Generic host activities are deliberately absent from the set.
        if (isSpecialAccessScreenClass(windowPackage, windowClass)) return true
        val titles = nodes.filter(::isPageTitle).mapNotNull { it.text } + nodes.mapNotNull { it.paneTitle }
        val localizedTitles = normalizedLocalizedTitles(localizedSpecialAccessTitles)
        if (titles.any { title ->
                matchesNormalizedText(title, normalizedSpecialAccessTitles, localizedTitles)
            }) return true

        val singleAppHeader = nodes.any { it.viewId?.endsWith(":id/entity_header_title") == true }
        val toggle = nodes.any { it.checkable || it.viewId?.substringAfter(":id/") in TOGGLE_IDS }
        if (singleAppHeader && toggle && nodes.any { node ->
                matchesNormalizedText(node.text, normalizedSpecialAccessControls)
            }) return true

        // Fallback if a very fast tap opens the disable confirmation first.
        // A dialog title, positive action and the full service identity are
        // required; a service row in an accessibility list is still insufficient.
        val serviceDialog = nodes.any { it.viewId?.endsWith(":id/alertTitle") == true } &&
            nodes.any { it.viewId?.endsWith(":id/button1") == true } &&
            nodes.any { mentions(it.text, ownServiceLabel,
                "$ownPackage/$ownPackage.OpenLockGuardAccessibilityService") }
        if (serviceDialog) return true

        // Generic text headers still need controls or a specific detail class.
        // The explicit toolbar/pane-title fast path above does not wait for them.
        val namesOurService = nodes.any { sameText(it.text.orEmpty(), ownServiceLabel) ||
            sameText(it.paneTitle.orEmpty(), ownServiceLabel) }
        if (!namesOurService) return false
        if (screenName(windowClass) in ACCESSIBILITY_DETAILS) return true
        if (titles.none { sameText(it, ownServiceLabel) }) return false
        val hasOnState = nodes.any { node -> matchesNormalizedText(node.text, normalizedOnLabels) }
        val hasOffButton = nodes.any { node ->
            (node.clickable || node.className?.endsWith("Button") == true) &&
                matchesNormalizedText(node.text, normalizedOffActions)
        }
        return toggle || (hasOnState && hasOffButton)
    }

    /** Only distinctive Settings classes qualify without a loaded node tree. */
    fun isSpecialAccessScreenClass(windowPackage: String, windowClass: String?): Boolean =
        windowPackage == SETTINGS_PACKAGE && screenName(windowClass) in SPECIAL_ACCESS_SCREENS

    fun isOwnServiceTitle(node: ScreenNode, serviceLabel: String): Boolean =
        !node.inList && (
            sameText(node.paneTitle.orEmpty(), serviceLabel) ||
                ((node.inToolbar || node.viewId?.substringAfter(":id/") in PAGE_TITLE_IDS) &&
                    sameText(node.text.orEmpty(), serviceLabel)))

    /** A real click on our entry is an entry attempt, not a passive list scan.
     *  The caller allows only non-input entry classes and validates the window. */
    fun isOwnServiceEntryClick(
        windowPackage: String,
        source: ScreenNode,
        eventTexts: List<String>,
        serviceLabel: String,
    ): Boolean = windowPackage == SETTINGS_PACKAGE && !source.inList &&
        (sameText(source.text.orEmpty(), serviceLabel) ||
            eventTexts.any { sameText(it, serviceLabel) })

    private fun screenName(windowClass: String?): String? =
        windowClass?.substringAfterLast('.')?.substringAfterLast('$')
            ?.lowercase()?.removeSuffix("activity")

    /** Packages whose windows can host a screen that removes OpenLock. */
    fun isCandidatePackage(pkg: String): Boolean =
        pkg == SETTINGS_PACKAGE || isPackageInstaller(pkg)

    fun isPackageInstaller(pkg: String): Boolean =
        pkg == "com.android.packageinstaller" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg.endsWith(".packageinstaller")

    /**
     * [windowPackage]/[windowClass] come from the latest window-state change;
     * [nodes] is the active window's flattened tree; [ownLabel] is OpenLock's
     * label from PackageManager in the current locale (the same string the OS
     * screens render) and [ownPackage] its package name.
     */
    fun isAboutOwnApp(
        windowPackage: String,
        windowClass: String?,
        nodes: List<ScreenNode>,
        ownLabel: String,
        ownPackage: String,
    ): Boolean {
        // The uninstaller dialog only ever concerns one app; it names it in the
        // title (AOSP) or message (One UI).
        if (isPackageInstaller(windowPackage)) {
            return nodes.any { mentions(it.text, ownLabel, ownPackage) }
        }
        if (windowPackage != SETTINGS_PACKAGE) return false

        val headerNamesUs = nodes.any { node ->
            val id = node.viewId ?: return@any false
            HEADER_ID_SUFFIXES.any { id.endsWith(it) } &&
                node.text != null &&
                (sameText(node.text, ownLabel) || node.text.trim() == ownPackage)
        }
        if (headerNamesUs) return true

        // DeviceAdminAdd is a single-app screen; its body text names the admin
        // app even where the OEM layout lacks the AOSP header id.
        val isDeviceAdminAdd =
            windowClass?.lowercase()?.contains(DEVICE_ADMIN_ADD_CLASS) == true
        return isDeviceAdminAdd && nodes.any { mentions(it.text, ownLabel, ownPackage) }
    }

    private fun sameText(text: String, label: String): Boolean =
        normalizeText(text).equals(normalizeText(label), ignoreCase = true)

    private fun matchesNormalizedText(
        text: String?,
        labels: List<String>,
        extraLabels: List<String> = emptyList(),
    ): Boolean {
        if (text == null) return false
        val normalized = normalizeText(text)
        return labels.any { normalized.equals(it, ignoreCase = true) } ||
            extraLabels.any { normalized.equals(it, ignoreCase = true) }
    }

    private fun normalizedLocalizedTitles(titles: Set<String>): List<String> {
        val cached = localizedTitleCache
        if (cached.first == titles) return cached.second
        // Keep a snapshot so a caller changing its set cannot leave stale labels.
        val snapshot = titles.toSet()
        val normalized = snapshot.map(::normalizeText)
        localizedTitleCache = snapshot to normalized
        return normalized
    }

    // Samsung can insert line breaks, non-breaking spaces or bidi marks in a
    // rendered title. These formatting differences do not change its identity.
    private fun normalizeText(text: String): String = text
        .replace(DIRECTION_MARKS, "")
        .replace(TITLE_WHITESPACE, " ").trim()

    /** [text] names the app: its package name, or its label as a whole word
     *  ("OpenLock" in "Uninstall OpenLock?" but not in "OpenLockPro"). */
    private fun mentions(text: String?, label: String, pkg: String): Boolean {
        if (text.isNullOrEmpty()) return false
        if (text.contains(pkg)) return true
        val needle = label.trim()
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from, ignoreCase = true)
            if (i < 0) return false
            val end = i + needle.length
            val before = if (i == 0) null else text[i - 1]
            val after = if (end >= text.length) null else text[end]
            if ((before == null || !before.isLetterOrDigit()) &&
                (after == null || !after.isLetterOrDigit())
            ) {
                return true
            }
            from = i + 1
        }
    }
}
