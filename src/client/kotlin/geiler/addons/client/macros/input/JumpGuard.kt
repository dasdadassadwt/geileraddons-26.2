package geiler.addons.client.macros.input

/**
 * Keeps a walk's jumps from being read as a double tap. Where the player may fly (creative), the game turns flight on when the jump
 * key goes down twice within seven ticks, and a walker that hops up two stairs in a row does exactly that. So a fresh press of the key
 * is held back until [GAP_TICKS] have passed since the last one began. A key that stays down is not a new press, and where the player
 * cannot fly nothing is held back.
 */
class JumpGuard {
    private var last = false
    private var sinceStart = GAP_TICKS

    /** The key state to use this tick, given what the walk wants. Call once per tick. */
    fun filter(wanted: Boolean, canFly: Boolean): Boolean {
        if (sinceStart < Int.MAX_VALUE) sinceStart++
        var out = wanted
        if (canFly && wanted && !last && sinceStart < GAP_TICKS) out = false
        if (out && !last) sinceStart = 0
        last = out
        return out
    }

    companion object {
        /** The game's window for the second tap is seven ticks from the first. */
        const val GAP_TICKS = 8
    }
}
