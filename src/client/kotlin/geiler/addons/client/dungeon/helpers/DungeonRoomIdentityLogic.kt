package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import net.minecraft.client.Minecraft

/** Kotlin owner for client-thread reads of the physically confirmed current Puzzle room. */
object DungeonRoomIdentityLogic {
    private val roomCoreScanner = DungeonMapCoreScanner()

    @JvmStatic
    fun currentPuzzleRoom(client: Minecraft?): DungeonRoomIdentity.Snapshot? {
        if (client == null || !client.isSameThread) return null
        val player = client.player ?: return null
        if (client.level == null || !DungeonContextTracker.inDungeon()) return null

        val reading = DungeonMapRoomDetector.current()
        if (reading == null || !reading.hasCurrentCell() || reading.geometry == null
            || reading.geometry.roomType() != DungeonMapGeometry.RoomType.PUZZLE
        ) return null

        val cell = reading.currentCell
        val playerCell = DungeonGridRules.cellAt(
            player.blockPosition().getX(),
            player.blockPosition().getZ(),
        )
        if (cell == null || playerCell == null || cell.componentX != playerCell.componentX
            || cell.componentZ != playerCell.componentZ
        ) return null

        val floor: DungeonFloor? = DungeonContextTracker.currentFloor()
        val roomData = roomCoreScanner.roomDataAt(client, cell)
        return DungeonRoomIdentity.Snapshot(floor, reading.signature(), cell, reading.geometry, roomData)
    }

    @JvmStatic
    fun mapIdentity(floor: DungeonFloor?, mapSignature: String?, cell: DungeonGridRules.Cell?): String? {
        if (cell == null) return null
        return "${floor?.name ?: "unknown"}|${mapSignature ?: ""}|${cell.componentX},${cell.componentZ}"
    }
}
