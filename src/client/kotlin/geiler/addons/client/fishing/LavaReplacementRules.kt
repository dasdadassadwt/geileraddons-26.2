package geiler.addons.client.fishing

import geiler.addons.client.location.Island

/** When lava is drawn as water, after SkyHanni's LavaReplacement: SkyBlock only, everywhere or on chosen islands. */
object LavaReplacementRules {
    /**
     * The per-fluid decision the model hook makes on every chunk-build thread: borrow the water model only for lava,
     * only while replacing, and only when a water model exists. Pure and allocation-free.
     */
    @JvmStatic
    fun useWater(isLava: Boolean, replacing: Boolean, hasWaterModel: Boolean): Boolean =
        isLava && replacing && hasWaterModel

    @JvmStatic
    fun shouldReplace(
        inSkyblock: Boolean,
        island: Island,
        everywhere: Boolean,
        crimsonIsle: Boolean,
        kuudra: Boolean,
        dungeons: Boolean,
    ): Boolean {
        if (!inSkyblock) return false
        if (everywhere) return true
        return when (island) {
            Island.CRIMSON_ISLE -> crimsonIsle
            Island.KUUDRA -> kuudra
            Island.CATACOMBS -> dungeons
            else -> false
        }
    }
}
