package geiler.addons.client.macros.navigation

/**
 * How far a player on the ground still gets after this tick, which is how far the ground has to go for them to be safe: the game moves
 * them by the speed they have kept plus this tick's push, keeps 0.546 of it, and with the keys let go of from then on they slide the
 * rest of the way. At the plain walking speed that is under a block; with a speed of 400 percent it is two and a half, and a fixed look
 * ahead of a block and a half puts such a player over every edge. Plain arithmetic, so checks can run it.
 */
object EdgeGuard {
    /** The share of its speed a player on the ground keeps from one tick to the next. */
    const val GROUND_KEPT = 0.546

    /** How many ticks' worth of the speed kept a player on the ground still slides with no keys down. */
    const val SLIDE_TICKS = GROUND_KEPT / (1.0 - GROUND_KEPT)

    /** The ground has to go this far past the end of the slide: the body's own width and a little more. */
    const val MARGIN = 0.3

    /** The least look ahead, for a player at a standstill: a block and a half, as the chase always had. */
    const val MIN_LOOKAHEAD = 1.6

    /** The distance [kept] (speed carried over, blocks per tick) and [push] (what this tick's keys add) take a player, counting the slide. */
    @JvmStatic
    fun travel(kept: Double, push: Double): Double = (kept + push) * (1.0 + SLIDE_TICKS)

    /** How far ahead the ground is to be checked for this speed. */
    @JvmStatic
    fun lookahead(kept: Double, push: Double): Double = maxOf(MIN_LOOKAHEAD, travel(kept, push) + MARGIN)

    /**
     * Whether to let go of the keys now: the ground goes [groundAhead] blocks in the direction of travel and the player would get
     * [reach] blocks, and no jump or drop of the route is [specialAhead] - a planned edge is walked off on purpose.
     */
    @JvmStatic
    fun brake(groundAhead: Double, reach: Double, specialAhead: Boolean): Boolean = !specialAhead && groundAhead < reach - 0.1
}
