package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome

/**
 * The fixed Safari positions worth walking to, as `[x, y, z]`. They are map knowledge anyone could write down
 * (collected by Critter Mod, MrCloudy2, MIT), so they may be marked through terrain; what is *at* a position
 * is read from the world and has to be earned by looking at it (see [MarkerRules]).
 */
object SafariPresets {
    /** The Cavern walls. Snoozle comes from behind one of these, if it comes at all. */
    @JvmField
    val SNOOPER_WALLS: List<IntArray> = listOf(
        intArrayOf(-126, 39, 74), intArrayOf(-114, 39, 87), intArrayOf(-70, 39, 68),
        intArrayOf(-96, 40, 17), intArrayOf(-95, 40, 42),
    )

    /** The Icy walls. */
    @JvmField
    val TROODON_WALLS: List<IntArray> = listOf(
        intArrayOf(-104, 80, -95), intArrayOf(-131, 78, -61), intArrayOf(-109, 89, -27),
    )

    /** The eleven Forest bee spots. The last two sit some fifteen blocks above the rest. */
    @JvmField
    val BEE_SPOTS: List<IntArray> = listOf(
        intArrayOf(-17, 68, 41), intArrayOf(-22, 68, 39), intArrayOf(23, 70, 46), intArrayOf(25, 70, 41),
        intArrayOf(-9, 81, 58), intArrayOf(-1, 81, 60), intArrayOf(15, 88, 31), intArrayOf(20, 88, 33),
        intArrayOf(-1, 84, 9), intArrayOf(-13, 103, 21), intArrayOf(-11, 103, 13),
    )

    /** The eight Haunted Hideonwall perches. */
    @JvmField
    val HIDEONWALL_PERCHES: List<IntArray> = listOf(
        intArrayOf(18, 71, -57), intArrayOf(-5, 71, -85), intArrayOf(-24, 71, -76), intArrayOf(-25, 71, -65),
        intArrayOf(-25, 79, -57), intArrayOf(6, 79, -84), intArrayOf(17, 80, -75), intArrayOf(17, 79, -52),
    )
}

/** What a marker marks. A kind with a biome only shows while the player is in that biome. */
enum class MarkerKind(val biome: SafariBiome?) {
    SNOOPER_WALL(SafariBiome.CAVERN),
    TROODON_WALL(SafariBiome.ICY),
    BEE_SPOT(SafariBiome.FOREST),
    HIDEONWALL_PERCH(SafariBiome.HAUNTED),
    MOUND(SafariBiome.CAVERN),
    TRADE(null),
    BLOODBAT(null),
}

enum class WallState { INTACT, BROKEN, UNKNOWN }
enum class BeeStatus { UNCHECKED, NEST, CLEARED }
enum class BeeBlock { NEST, HIVE, OTHER }

/**
 * The decisions behind the world markers, kept free of Minecraft types so they can be checked offline.
 *
 * The rule they all serve: a fixed position is free, but anything read out of the world (a wall's state, a
 * bee nest, a mound, a critter) is only used once there is a clear line to it. The client is sent blocks and
 * entities far past what the player can see, and reporting those raw would tell the player what is behind a wall.
 */
object MarkerRules {
    /** Whether a marker of [kind] should be drawn while the player is in [current]. */
    @JvmStatic
    fun shows(kind: MarkerKind, current: SafariBiome?): Boolean = kind.biome == null || kind.biome == current

    /**
     * A wall being gone is latched, and only on sight. Unloaded chunks read as air, and loaded ones reach far
     * past view, so air counts as broken only when the chunk is loaded and the position is seen; anything else
     * is [WallState.UNKNOWN], which stays marked as still to do.
     */
    @JvmStatic
    fun wallState(previous: WallState, loaded: Boolean, visible: Boolean, solid: Boolean): WallState = when {
        previous == WallState.BROKEN -> WallState.BROKEN
        loaded && solid -> WallState.INTACT
        loaded && !solid && visible -> WallState.BROKEN
        else -> WallState.UNKNOWN
    }

    /** Snoozle only comes from the Cavern walls: once all are broken and none was seen or caught, none can. */
    @JvmStatic
    fun snoozleGone(allWallsBroken: Boolean, caught: Int, nearby: Int): Boolean =
        allWallsBroken && caught == 0 && nearby == 0

    /**
     * A bee spot is resolved by looking at it: a nest is worth walking to, a hive or nothing is crossed off.
     * Cleared sticks, because a nest that gave up its bee is still a nest block.
     */
    @JvmStatic
    fun beeStatus(previous: BeeStatus, visible: Boolean, block: BeeBlock): BeeStatus {
        if (!visible || previous == BeeStatus.CLEARED) return previous
        return if (block == BeeBlock.NEST) BeeStatus.NEST else BeeStatus.CLEARED
    }

    /** Index of the preset within [radius] of the point nearest to it, or -1. Punches land beside a block, not on it. */
    @JvmStatic
    fun nearestPreset(presets: List<IntArray>, x: Int, y: Int, z: Int, radius: Double): Int {
        var best = -1
        var bestSqr = radius * radius
        for ((i, p) in presets.withIndex()) {
            val dx = (p[0] - x).toDouble()
            val dy = (p[1] - y).toDouble()
            val dz = (p[2] - z).toDouble()
            val d = dx * dx + dy * dy + dz * dz
            if (d <= bestSqr) {
                bestSqr = d
                best = i
            }
        }
        return best
    }

    /**
     * Whether an interaction entity is a Rockmite mound. Size alone is weak (the breakable waterfall fish are
     * interaction entities too), so three rules combine: on the Cavern floor, centred on a block, and with
     * nothing standing inside the box.
     */
    @JvmStatic
    fun isMound(width: Double, height: Double, y: Double, centredOnBlock: Boolean, occupied: Boolean): Boolean =
        width in MOUND_MIN_WIDTH..MOUND_MAX_WIDTH &&
            height in MOUND_MIN_HEIGHT..MOUND_MAX_HEIGHT &&
            y < MOUND_MAX_Y && centredOnBlock && !occupied

    const val MOUND_MIN_WIDTH = 0.35
    const val MOUND_MAX_WIDTH = 1.10
    const val MOUND_MIN_HEIGHT = 0.25
    const val MOUND_MAX_HEIGHT = 0.95
    const val MOUND_MAX_Y = 65.0
    const val BEE_PUNCH_RADIUS = 2.5
}
