package geiler.addons.client.macros.sim

/**
 * Hypixel SkyBlock etherwarp facts as plain functions: which item warps, how far, and from what height the ray starts.
 * The reference is Odin's Etherwarp module (BSD 3-Clause); the rules here are written independently from its behavior.
 */
object EtherwarpRules {
    const val CONDUIT_ID = "ETHERWARP_CONDUIT"
    const val BASE_DISTANCE = 57.0

    /** How far above the block's cell the player is put: the block's top plus a little, as the warp does. */
    const val LAND_ABOVE = 1.05

    /** An item etherwarps when it carries the ethermerge upgrade, or is the Etherwarp Conduit. */
    @JvmStatic
    fun isEtherwarpItem(skyBlockId: String?, ethermerge: Int): Boolean = ethermerge == 1 || skyBlockId == CONDUIT_ID

    /** The conduit warps without sneaking. */
    @JvmStatic
    fun isConduit(skyBlockId: String?): Boolean = skyBlockId == CONDUIT_ID

    /** 57 blocks plus one for every Transmission Tuner the item carries. */
    @JvmStatic
    fun distance(tunedTransmission: Int): Double = BASE_DISTANCE + maxOf(0, tunedTransmission)

    /** Height of the eye above the feet for the ray's origin. */
    @JvmStatic
    fun eyeHeight(swimming: Boolean, sneaking: Boolean): Double = when {
        swimming -> 0.4
        sneaking -> 1.27
        else -> 1.62
    }

    @JvmStatic
    fun landY(blockY: Int): Double = blockY + LAND_ABOVE
}

/**
 * One warp per two ticks: after a warp at tick T the next is allowed at T + 2, so a full tick passes between two. Clicks
 * inside that time are dropped. A tick counter that goes backwards (a new world) forgets the last warp.
 */
class EtherwarpCooldown {
    private var last = Long.MIN_VALUE

    /** Whether a warp may happen at [tick]; when it may, it is recorded. */
    fun tryWarp(tick: Long): Boolean {
        if (last != Long.MIN_VALUE && tick >= last && tick < last + MIN_GAP_TICKS) return false
        last = tick
        return true
    }

    fun reset() {
        last = Long.MIN_VALUE
    }

    companion object {
        const val MIN_GAP_TICKS = 2L
    }
}
