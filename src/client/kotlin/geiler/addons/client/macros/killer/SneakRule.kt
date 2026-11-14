package geiler.addons.client.macros.killer

/**
 * Whether the player is crouched for the mob it has. Decided once per mob, when the fight with it begins, with the
 * configured chance, and held until that mob is gone: a crouch that stopped in the middle of a kill, or started in it, is not
 * how anyone fights. The next mob is a new draw, and a mob that is not the one drawn for is never crouched for.
 *
 * The macro presses the key from [now] in every state, so a crouch drawn for the last mob cannot go on through the walk to
 * the next.
 */
class SneakRule {
    private var forTarget: Any? = null
    private var crouch = false

    /** Draws for [target] if it is not the mob drawn for already; returns whether a draw was made. [roll01] is an even draw from 0 to 1. */
    fun roll(target: Any, chancePercent: Double, roll01: Double): Boolean {
        if (target === forTarget) return false
        forTarget = target
        crouch = roll01 * 100.0 < chancePercent
        return true
    }

    /** Whether to be crouched with this target: only for the mob the draw was made for. */
    fun now(target: Any?): Boolean = crouch && target != null && target === forTarget

    fun clear() {
        forTarget = null
        crouch = false
    }
}
