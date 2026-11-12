package geiler.addons.client.module.impl

/** Tracks the one short, dispatch-linked container replacement allowed after a Leap click. */
class SpiritLeapTransitionGuard {
    enum class Decision {
        INACTIVE,
        SOURCE_SCREEN,
        SUPPRESS_FIRST_REPLACEMENT,
        SUPPRESS_SAME_REPLACEMENT,
        CLEARED_DISABLED,
        CLEARED_EXPIRED,
        CLEARED_UNRELATED,
        CLEARED_SCREEN_CHANGED,
    }

    private var sourceScreen: Any? = null
    private var replacementScreen: Any? = null
    private var dispatchedAtNanos = 0L

    fun arm(source: Any?, nowNanos: Long): Boolean {
        clear()
        if (source == null) return false
        sourceScreen = source
        dispatchedAtNanos = nowNanos
        return true
    }

    fun evaluate(currentScreen: Any?, enabled: Boolean, leapContainer: Boolean, nowNanos: Long): Decision {
        if (!isArmed()) return Decision.INACTIVE
        if (!enabled) {
            clear()
            return Decision.CLEARED_DISABLED
        }
        if (!isFresh(nowNanos)) {
            clear()
            return Decision.CLEARED_EXPIRED
        }
        if (currentScreen == null) return Decision.INACTIVE
        if (currentScreen === sourceScreen) return Decision.SOURCE_SCREEN
        val replacement = replacementScreen
        if (replacement != null) {
            if (currentScreen === replacement) return Decision.SUPPRESS_SAME_REPLACEMENT
            clear()
            return Decision.CLEARED_SCREEN_CHANGED
        }
        if (!leapContainer) {
            clear()
            return Decision.CLEARED_UNRELATED
        }
        replacementScreen = currentScreen
        return Decision.SUPPRESS_FIRST_REPLACEMENT
    }

    fun clearForValidatedLeapScreen(screen: Any?): Boolean {
        if (!isArmed() || screen == null || screen === sourceScreen) return false
        clear()
        return true
    }

    fun clearForRemovedScreen(screen: Any?): Boolean {
        if (!isArmed() || screen == null || screen !== replacementScreen) return false
        clear()
        return true
    }

    fun expireIfStale(nowNanos: Long): Boolean {
        if (!isArmed() || isFresh(nowNanos)) return false
        clear()
        return true
    }

    fun isSuppressing(screen: Any?, enabled: Boolean, nowNanos: Long): Boolean =
        enabled && screen != null && screen === replacementScreen && isFresh(nowNanos)

    fun isArmed(): Boolean = sourceScreen != null

    fun clear() {
        sourceScreen = null
        replacementScreen = null
        dispatchedAtNanos = 0L
    }

    private fun isFresh(nowNanos: Long): Boolean {
        val elapsed = nowNanos - dispatchedAtNanos
        return elapsed >= 0L && elapsed < WINDOW_NANOS
    }

    companion object {
        const val WINDOW_NANOS = 1_500_000_000L
    }
}
