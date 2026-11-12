package geiler.addons.client.module.impl

import java.util.LinkedHashMap

/** Bounded starred-position evidence retained per physical dungeon room for one run. */
class DungeonMobEncounterHistory {
    companion object {
        const val MAX_ROOMS = 36
        const val MAX_POSITIONS_PER_ROOM = 1024
    }

    private data class Cell(val x: Int, val y: Int, val z: Int)

    private val rooms = LinkedHashMap<String, LinkedHashMap<Cell, DungeonMobEspSupport.WorldPosition>>()

    fun observe(roomKey: String?, x: Double, y: Double, z: Double) {
        if (roomKey == null || roomKey.isBlank() || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val positions = rooms.computeIfAbsent(roomKey) { LinkedHashMap() }
        positions.putIfAbsent(
            Cell(kotlin.math.floor(x).toInt(), kotlin.math.floor(y).toInt(), kotlin.math.floor(z).toInt()),
            DungeonMobEspSupport.WorldPosition(x, y, z),
        )
        while (positions.size > MAX_POSITIONS_PER_ROOM) {
            val oldest = positions.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        while (rooms.size > MAX_ROOMS) {
            val oldestRoom = rooms.keys.iterator()
            if (!oldestRoom.hasNext()) break
            oldestRoom.next()
            oldestRoom.remove()
        }
    }

    @kotlin.jvm.JvmName("positions")
    internal fun positions(roomKey: String?): List<DungeonMobEspSupport.WorldPosition> {
        if (roomKey == null) return emptyList()
        val positions = rooms[roomKey] ?: return emptyList()
        return java.util.List.copyOf(positions.values)
    }

    fun clearRoom(roomKey: String?) {
        if (roomKey != null) rooms.remove(roomKey)
    }

    fun clear() {
        rooms.clear()
    }

    fun roomCount(): Int = rooms.size
}
