package geiler.addons.client.dungeon.f7

/**
 * One owner at a time for the dungeon automation that turns the view or clicks (Auto Mask, Auto Simon Says, Auto i4,
 * Lever Aim Assist trigger). While one feature holds it, the others leave the view and the mouse alone. A holder that
 * never releases loses the lock after [TIMEOUT_MS], so a stuck sequence cannot block the others for good.
 */
object AutomationLock {
    const val TIMEOUT_MS = 10_000L

    private var owner: String? = null
    private var sinceMs = 0L

    /** Takes the lock for [who]; false when another owner holds it and has not timed out. Taking it again renews it. */
    @JvmStatic
    fun acquire(who: String, nowMs: Long): Boolean {
        val current = holder(nowMs)
        if (current != null && current != who) return false
        owner = who
        sinceMs = nowMs
        return true
    }

    /** Releases the lock if [who] holds it. */
    @JvmStatic
    fun release(who: String) {
        if (owner == who) owner = null
    }

    /** The owner now, or null when free or timed out. */
    @JvmStatic
    fun holder(nowMs: Long): String? {
        val current = owner ?: return null
        if (nowMs - sinceMs >= TIMEOUT_MS) {
            owner = null
            return null
        }
        return current
    }

    /** True when someone other than [who] holds the lock: [who] must not turn the view or click now. */
    @JvmStatic
    fun blocks(who: String, nowMs: Long): Boolean {
        val current = holder(nowMs)
        return current != null && current != who
    }

    @JvmStatic
    fun clear() {
        owner = null
    }
}
