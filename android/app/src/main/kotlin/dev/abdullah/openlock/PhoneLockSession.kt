package dev.abdullah.openlock

/** Phone authentication is independent of app/Settings unlocks and never persisted. */
class PhoneLockState {
    private var windows = emptySet<String>()
    private var authenticated = false
    var epoch = 0L
        private set

    val requiresPin: Boolean get() = windows.isNotEmpty() && !authenticated

    fun update(activeWindows: Set<String>) {
        if (windows == activeWindows) return
        windows = activeWindows.toSet()
        authenticated = false
        epoch++
    }

    fun screenOff() {
        authenticated = false
        epoch++
    }

    fun unlock(expectedEpoch: Long = epoch) {
        if (windows.isNotEmpty() && expectedEpoch == epoch) authenticated = true
    }
}

/** All callers use the main looper. The target is a session key, never a package. */
object PhoneLockSession {
    const val TARGET = "openlock:phone"
    val state = PhoneLockState()
}
