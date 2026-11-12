package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.odin.OdinDungeonMapCoreScanner
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import java.util.ArrayList

/** Strict Mimic candidate detector: known room secret coordinate, trapped chest, and pre-blood Clear. */
class DungeonMimicChestDetector private constructor() {
    companion object {
        /** Performs bounded reads only from the current room's already-loaded chunks on the client thread. */
        @JvmStatic
        fun detect(client: Minecraft?, scanner: OdinDungeonMapCoreScanner?): List<BlockPos> {
            if (client == null || !client.isSameThread()) return emptyList()
            val level = client.level ?: return emptyList()
            if (client.player == null
                || scanner == null || !DungeonContextTracker.inDungeon() || !DungeonContextTracker.isClearPhase()
            ) return emptyList()

            val reading = DungeonMapRoomDetector.current()
            val currentCell = reading?.currentCell
            val footprint = DungeonRoomTracker.currentMapFootprint(client)
            if (currentCell == null || footprint == null || footprint.cells().isEmpty()) return emptyList()

            val core = scanner.roomCoreAt(client, currentCell) ?: return emptyList()
            val room = core.data ?: return emptyList()
            if (core.highestBlock <= 0) return emptyList()

            val catalog = DevonianDungeonWaypointCatalog.bundled()
            if (!catalog.available()) return emptyList()
            val roomChests = catalog.find(room.name(), "chest")
            if (roomChests.isEmpty()) return emptyList()

            val cells = ArrayList<DungeonGridRules.Cell>(footprint.cells().size)
            for (key in footprint.cells()) {
                val cell = DungeonGridRules.cellAtOrigin(
                    DungeonGridRules.GRID_MIN + key.x * DungeonGridRules.CELL_STRIDE,
                    DungeonGridRules.GRID_MIN + key.z * DungeonGridRules.CELL_STRIDE,
                ) ?: return emptyList()
                cells.add(cell)
            }

            val orientation = DungeonRoomOrientationDetector.resolve(
                client,
                cells,
                room.shape(),
                room.type(),
                core.highestBlock,
            ) ?: return emptyList()

            val mimics = ArrayList<BlockPos>()
            for (chest in roomChests) {
                val point = DungeonRoomOrientationRules.toWorld(
                    orientation,
                    chest.x,
                    chest.y,
                    chest.z,
                ) ?: return emptyList()
                val position = BlockPos(point.x, point.y, point.z)
                // A malformed or mismatched coordinate must never escape the exact room footprint.
                if (!footprint.contains(position) || !level.hasChunkAt(position)) return emptyList()
                if (level.getBlockState(position).`is`(Blocks.TRAPPED_CHEST)) {
                    mimics.add(position.immutable())
                }
            }
            return java.util.List.copyOf(mimics)
        }
    }
}
