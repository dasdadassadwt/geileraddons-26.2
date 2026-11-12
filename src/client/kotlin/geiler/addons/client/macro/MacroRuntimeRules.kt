package geiler.addons.client.macro

/** Pure guards shared by the macro runtime and its offline checks. */
object MacroRuntimeRules {
    @JvmStatic
    fun canStart(sameStackRunning: Boolean, activeRuns: Int, maximumRuns: Int): Boolean =
        !sameStackRunning && activeRuns >= 0 && maximumRuns > 0 && activeRuns < maximumRuns

    @JvmStatic
    fun canEnterCall(currentDepth: Int, maximumDepth: Int, recursive: Boolean): Boolean =
        !recursive && currentDepth >= 0 && maximumDepth > 0 && currentDepth < maximumDepth

    @JvmStatic
    fun shouldStartWorldRun(
        inside: Boolean,
        entered: Boolean,
        sameStackRunning: Boolean,
        oncePerWorld: Boolean,
        firedThisWorld: Boolean,
        completedAtNanos: Long,
        nowNanos: Long,
        repeatDelayNanos: Long,
    ): Boolean {
        if (!inside || sameStackRunning) return false
        if (oncePerWorld) return entered && !firedThisWorld
        if (entered) return true
        return completedAtNanos > 0 && nowNanos >= completedAtNanos &&
            nowNanos - completedAtNanos >= maxOf(0L, repeatDelayNanos)
    }

    /** INP-8: a held input block must not outlive an indefinite wait forever. */
    const val INPUT_BLOCK_WAIT_LIMIT_NANOS = 60_000_000_000L

    @JvmStatic
    fun inputBlockWaitExpired(indefiniteBlock: Boolean, blockedSinceNanos: Long, nowNanos: Long): Boolean =
        indefiniteBlock && blockedSinceNanos > 0 && nowNanos - blockedSinceNanos >= INPUT_BLOCK_WAIT_LIMIT_NANOS

    @JvmStatic
    fun canSelectHotbar(containerOpen: Boolean): Boolean = !containerOpen

    @JvmStatic
    fun shouldReleaseHeldMouse(screenOpen: Boolean, nowNanos: Long, deadlineNanos: Long): Boolean =
        screenOpen || nowNanos >= deadlineNanos

    @JvmStatic
    fun colorWithOpacity(opaqueColor: Int, opacity: Int): Int {
        val alpha = opacity.coerceIn(0, 255)
        return (opaqueColor and 0x00FFFFFF) or (alpha shl 24)
    }
}
