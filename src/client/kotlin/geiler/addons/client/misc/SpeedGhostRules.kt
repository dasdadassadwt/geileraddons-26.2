package geiler.addons.client.misc

import geiler.addons.client.location.Island

/** Speed Display text, after Devonian's SpeedDisplay: Hypixel stores SkyBlock speed / 1000 as the base movement speed. */
object SpeedRules {
    const val PREVIEW = "§f400✦"

    @JvmStatic
    fun speed(baseMovementSpeed: Double): Int = (baseMovementSpeed * 1000f + 0.5f).toInt()

    @JvmStatic
    fun text(speed: Int): String = "§f$speed✦"
}

/** Ghost Vision detection, after NoFrills: the Dwarven Mines ghosts are creepers below Y 100. */
object GhostRules {
    const val MAX_Y = 100.0

    @JvmStatic
    fun isGhost(island: Island, isCreeper: Boolean, y: Double): Boolean =
        isCreeper && island == Island.DWARVEN_MINES && y < MAX_Y
}
