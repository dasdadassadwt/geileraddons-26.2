package geiler.addons.client.dungeon

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks

/** Resolves one room's corner orientation from already-loaded blue-terracotta roof markers. */
class DungeonRoomOrientationDetector private constructor() {
    companion object {
        /** Returns null when shape, chunk availability, or marker evidence is ambiguous or incomplete. */
        @JvmStatic
        fun resolve(
            client: Minecraft?,
            cells: List<DungeonGridRules.Cell>?,
            shape: String?,
            markerY: Int,
        ): DungeonRoomOrientationRules.Candidate? = resolve(client, cells, shape, "", markerY)

        /** Handles Devonian's fixed north-west transform for Fairy rooms before roof-marker lookup. */
        @JvmStatic
        fun resolve(
            client: Minecraft?,
            cells: List<DungeonGridRules.Cell>?,
            shape: String?,
            roomType: String?,
            markerY: Int,
        ): DungeonRoomOrientationRules.Candidate? {
            if (client == null || !client.isSameThread()) return null
            val level = client.level ?: return null
            val candidates = DungeonRoomOrientationRules.candidates(cells, shape, markerY)
            if (candidates.isEmpty()) return null
            if (roomType.equals("FAIRY", ignoreCase = true)) {
                return candidates.firstOrNull { it.rotation == 0 }
            }
            for (candidate in candidates) {
                if (!level.hasChunkAt(BlockPos(candidate.markerX, candidate.markerY, candidate.markerZ))) {
                    return null
                }
            }
            return DungeonRoomOrientationRules.uniqueMarkedCandidate(candidates) { candidate ->
                if (candidate == null) return@uniqueMarkedCandidate false
                level.getBlockState(BlockPos(candidate.markerX, candidate.markerY, candidate.markerZ))
                    .`is`(Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE))
            }
        }
    }
}
