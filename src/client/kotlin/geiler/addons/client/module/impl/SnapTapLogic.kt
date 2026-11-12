package geiler.addons.client.module.impl

/**
 * Last-input-priority SOCD resolution for one pair of opposite keys. Call [resolve] once per input tick
 * with the raw key states; the key pressed most recently wins while both are held.
 */
class SnapTapAxis {
    private var last = NONE
    private var prevPositive = false
    private var prevNegative = false

    /** True when the last [resolve] call overrode a simultaneous press. */
    var overriding = false
        private set

    /** Winner of the last override: [POSITIVE] or [NEGATIVE]; meaningful only while [overriding]. */
    var winner = NONE
        private set

    /** Returns the resolved state as a bit mask: bit 0 positive key, bit 1 negative key. */
    fun resolve(positive: Boolean, negative: Boolean): Int {
        val newPositive = positive && !prevPositive
        val newNegative = negative && !prevNegative
        prevPositive = positive
        prevNegative = negative
        overriding = false
        if (!positive || !negative) {
            last = if (positive) POSITIVE else if (negative) NEGATIVE else NONE
            return (if (positive) POSITIVE else 0) or (if (negative) NEGATIVE else 0)
        }
        // Both held: a fresh press takes priority; simultaneous or no fresh press keeps the previous winner.
        if (newPositive != newNegative) last = if (newPositive) POSITIVE else NEGATIVE
        if (last == NONE) return POSITIVE or NEGATIVE // nothing observed yet (e.g. enabled mid-hold): vanilla cancel
        overriding = true
        winner = last
        return last
    }

    fun reset() {
        last = NONE
        prevPositive = false
        prevNegative = false
        overriding = false
        winner = NONE
    }

    companion object {
        const val NONE = 0
        const val POSITIVE = 1
        const val NEGATIVE = 2
    }
}
