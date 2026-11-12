package geiler.addons.client.location

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.world.level.biome.Biome

private const val SAFARI_BIOME_NAMESPACE = "hypixel"

/**
 * Which part of the Critter Safari the player is standing in.
 *
 * The answer comes from the biome the server reports, not from a coordinate map. Hypixel registers
 * Safari biomes in its own namespace, so the client can query them directly without stale spots.
 * The result is resolved once per tick because it is read from both render and tick paths.
 */
enum class SafariBiome(displayLabel: String, vararg paths: String) {
    FOREST("Forest", "forest"),
    CAVERN("Cavern", "cavern"),

    /** Two ids, and both are the same place as far as anything here is concerned. */
    ICY("Icy", "icy", "icy_caves"),
    HAUNTED("Haunted", "haunted");

    private val label = displayLabel
    private val ids: Array<Identifier> = paths
        .map { Identifier.fromNamespaceAndPath(SAFARI_BIOME_NAMESPACE, it) }
        .toTypedArray()

    /** Reads as the tail of "Not in the ..." in the Click GUI's idle explanation. */
    fun displayName(): String = label

    companion object {
        private var currentBiome: SafariBiome? = null

        /** The Safari biome under the player's feet, or null when the answer is unavailable. */
        @JvmStatic
        fun current(): SafariBiome? = currentBiome

        /** Call once per client tick, after the island is resolved. */
        @JvmStatic
        fun tick() {
            currentBiome = resolve()
        }

        private fun resolve(): SafariBiome? {
            // Nowhere else has these biomes, so off the Safari there is nothing to look up.
            if (HypixelModApi.currentIsland() != Island.SAFARI) return null

            val player = Minecraft.getInstance().player ?: return null
            val level = player.level()
            val feet: BlockPos = player.blockPosition()
            // Outside build height the lookup is meaningless rather than merely wrong.
            if (!level.isInsideBuildHeight(feet)) return null

            val biome: Holder<Biome> = level.getBiome(feet)
            for (candidate in values()) {
                for (id in candidate.ids) {
                    if (biome.`is`(id)) return candidate
                }
            }
            return null
        }
    }
}
