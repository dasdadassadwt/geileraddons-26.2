package geiler.addons.client.module.impl

/** Pure decisions shared by the highlighter tick and render paths. */
object PestHighlighterBehavior {
    const val BOX_LAYER = 1
    const val RING_LAYER = 1 shl 1
    const val TRACER_LAYER = 1 shl 2

    @JvmStatic
    fun scanDue(ticksSinceScan: Int, scanInterval: Int): Boolean = ticksSinceScan >= scanInterval

    @JvmStatic
    fun scanRangeBlocks(effectiveRenderDistanceBlocks: Double): Double =
        minOf(effectiveRenderDistanceBlocks, MAX_RANGE_BLOCKS)

    @JvmStatic
    fun isCandidate(isArmorStand: Boolean, isAlive: Boolean): Boolean = isArmorStand && isAlive

    @JvmStatic
    fun isCurrentSighting(isRemoved: Boolean, isAlive: Boolean): Boolean = !isRemoved && isAlive

    @JvmStatic
    fun worldLayerMask(box: Boolean, ring: Boolean, tracer: Boolean): Int =
        (if (box) BOX_LAYER else 0) or
            (if (ring) RING_LAYER else 0) or
            (if (tracer) TRACER_LAYER else 0)

    private const val MAX_RANGE_BLOCKS = 64.0
}
