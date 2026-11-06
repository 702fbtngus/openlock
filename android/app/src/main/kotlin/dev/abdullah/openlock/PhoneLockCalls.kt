package dev.abdullah.openlock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager

/** Do not place a phone-wide PIN over the dialer or common system call UIs.
 *  No call-state/phone-number permission, hidden API or global timed bypass. */
object PhoneLockCalls {
    val essentialPackages = setOf(
        "com.android.systemui", "com.android.phone", "com.android.server.telecom",
        "com.android.emergency", "com.android.incallui", "com.samsung.android.incallui",
        "com.samsung.android.app.telephonyui", "com.samsung.android.dialer",
        "com.google.android.dialer", "com.android.dialer", "com.android.contacts",
    )

    fun dialIntent() = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))

    @Suppress("DEPRECATION")
    fun allowedPackages(context: Context): Set<String> = buildSet {
        addAll(essentialPackages)
        runCatching {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        }.getOrNull()?.let { add(it) }
        runCatching {
            context.packageManager.resolveActivity(dialIntent(), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.takeUnless { it == "android" }?.let { add(it) }
    }
}
