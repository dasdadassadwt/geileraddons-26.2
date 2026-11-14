package geiler.addons.client.macros.sim

/**
 * What a held item does as a teleport, from its SkyBlock data alone: a right click carries the player a short way along the view
 * (the Aspect of the End / Void, [instant]), and with the ethermerge upgrade - or as the Etherwarp Conduit - a sneaking one warps
 * far ([ether]). How far each goes is read off the Transmission Tuners. Free of game state so checks can run it.
 */
object TeleportItem {
    private val INSTANT_IDS = setOf("ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID")

    class Info(
        @JvmField val instant: Boolean,
        @JvmField val instantReach: Double,
        @JvmField val ether: Boolean,
        @JvmField val etherReach: Double,
        @JvmField val conduit: Boolean,
    ) {
        /** How well it fits a macro that hops and warps: both is best, a hop alone next, a warp alone last. */
        fun rank(): Int = (if (instant) 2 else 0) + (if (ether) 1 else 0)
    }

    /** The item's behaviour, or null when it does not teleport. [tuned] is the `tuned_transmission` count. */
    @JvmStatic
    fun classify(skyBlockId: String?, ethermerge: Int, tuned: Int): Info? {
        val instant = skyBlockId in INSTANT_IDS
        val ether = EtherwarpRules.isEtherwarpItem(skyBlockId, ethermerge)
        if (!instant && !ether) return null
        return Info(
            instant, InstantTransmission.distance(tuned), ether, EtherwarpRules.distance(tuned), EtherwarpRules.isConduit(skyBlockId),
        )
    }

    /** The Etherwarp Sim's shovel: an Aspect of the Void that warps too, with the tuners the sim is set to. */
    @JvmStatic
    fun simulated(tuners: Int): Info = Info(true, InstantTransmission.distance(tuners), true, EtherwarpMath.range(tuners), false)

    /** The index of the best of these, or -1 when none teleports; the first of equals, so the lowest slot wins a tie. */
    @JvmStatic
    fun best(items: List<Info?>): Int {
        var index = -1
        var rank = 0
        for ((i, info) in items.withIndex()) {
            if (info != null && info.rank() > rank) {
                index = i
                rank = info.rank()
            }
        }
        return index
    }
}
