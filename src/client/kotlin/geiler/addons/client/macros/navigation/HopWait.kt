package geiler.addons.client.macros.navigation

/**
 * How long a teleport that is being set up may wait for its click: the item to come to hand, the view to turn onto the landing, the
 * crouch, the way to clear. A wait with no end looks from outside like a macro standing still with the item out, so it is reported
 * now and then and given up on after [GIVE_UP_AFTER] ticks. Plain arithmetic, so checks can run it.
 */
object HopWait {
    enum class Action { NONE, REPORT, GIVE_UP }

    const val REPORT_EVERY = 10
    const val GIVE_UP_AFTER = 40

    /** What to do after [ticks] ticks of waiting (1 for the first). */
    @JvmStatic
    fun action(ticks: Int): Action = when {
        ticks >= GIVE_UP_AFTER -> Action.GIVE_UP
        ticks > 0 && ticks % REPORT_EVERY == 0 -> Action.REPORT
        else -> Action.NONE
    }
}
