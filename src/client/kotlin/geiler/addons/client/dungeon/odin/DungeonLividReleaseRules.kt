package geiler.addons.client.dungeon.odin

/** Decides when the tracked Livid counts as gone, so a chunk flicker does not drop a live target. */
object DungeonLividReleaseRules {
    /** Consecutive client ticks the matched entity must be missing, removed or dead before it is released. */
    const val RELEASE_TICKS = 10

    /** The run-end boss-room chat line; the Livid fight is over for good once it appears. */
    const val RUN_END_LINE = "> EXTRA STATS <"

    @JvmStatic
    fun nextMissingTicks(previous: Int, entityPresentAndAlive: Boolean): Int =
        if (entityPresentAndAlive) 0 else minOf(previous + 1, RELEASE_TICKS)

    @JvmStatic
    fun shouldRelease(missingTicks: Int): Boolean = missingTicks >= RELEASE_TICKS

    @JvmStatic
    fun isRunEnd(plainMessage: String?): Boolean =
        plainMessage != null && plainMessage.trim { it <= ' ' } == RUN_END_LINE
}
