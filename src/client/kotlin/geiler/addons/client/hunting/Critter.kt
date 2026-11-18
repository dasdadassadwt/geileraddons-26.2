package geiler.addons.client.hunting

import geiler.addons.client.location.SafariBiome
import java.util.Locale

/** Hypixel rarity, used for display colour only. */
enum class Rarity(val argb: Int) {
    COMMON(0xFFFFFFFF.toInt()),
    UNCOMMON(0xFF55FF55.toInt()),
    RARE(0xFF5555FF.toInt()),
    EPIC(0xFFAA00AA.toInt()),
    LEGENDARY(0xFFFFAA00.toInt()),
}

/**
 * One Critter Safari species. [quota] is how many spawn per run where that number is fixed, or 0 for
 * species that respawn (no total exists, so one catch is all that can be asked of them).
 */
class Critter(val name: String, val biome: SafariBiome, val rarity: Rarity, val quota: Int = 0) {
    fun hasQuota(): Boolean = quota > 0

    /** Derived rather than tabulated: every shard product is `SHARD_` + the upper-cased, underscored name. */
    fun bazaarId(): String = "SHARD_" + name.uppercase(Locale.ROOT).replace(' ', '_')

    override fun toString(): String = name
}

/** Text colour per biome for HUD rows, roughly the in-game biome colours. */
object BiomeStyle {
    @JvmStatic
    fun argb(biome: SafariBiome): Int = when (biome) {
        SafariBiome.FOREST -> 0xFF55FF55.toInt()
        SafariBiome.CAVERN -> 0xFFFFAA00.toInt()
        SafariBiome.ICY -> 0xFF55FFFF.toInt()
        SafariBiome.HAUNTED -> 0xFFAA00AA.toInt()
    }

    /** Resolves a bare biome name such as `Icy`, as chat and announcements spell it. */
    @JvmStatic
    fun fromName(name: String?): SafariBiome? {
        if (name == null) return null
        return SafariBiome.values().firstOrNull { it.displayName().equals(name.trim(), ignoreCase = true) }
    }
}
