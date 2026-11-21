package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonGridRules
import java.util.ArrayList
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Pure geometry and fail-closed gate for the observed Ice Fill world path. */
@JvmSuppressWildcards
class IceFillRenderGeometry private constructor() {
    @JvmRecord
    data class Point(val x: Double, val y: Double, val z: Double) {
        override fun toString(): String = "Point[x=$x, y=$y, z=$z]"
    }

    companion object {
        @JvmStatic
        fun shouldRender(
            completeScan: Boolean,
            sameLevel: Boolean,
            sameRoom: Boolean,
            sameFloor: Boolean,
            routeBlocksStillLoadedIce: Boolean,
            route: List<DungeonPuzzleLogic.Cell?>?,
        ): Boolean = completeScan && sameLevel && sameRoom && sameFloor && routeBlocksStillLoadedIce &&
            route != null && route.size >= 2

        /** Projects room-relative (x,z) cells to block centers just above their observed floor. */
        @JvmStatic
        fun worldPoints(
            route: List<DungeonPuzzleLogic.Cell?>?,
            roomBaseX: Int,
            roomBaseZ: Int,
            floorY: Int,
        ): List<IceFillRenderGeometry.Point> {
            if (route == null || route.size < 2) return java.util.List.of()
            val points = ArrayList<IceFillRenderGeometry.Point>(route.size)
            for (cell in route) {
                if (cell == null || cell.x < 0 || cell.x >= DungeonGridRules.ROOM_SIZE ||
                    cell.y < 0 || cell.y >= DungeonGridRules.ROOM_SIZE
                ) return java.util.List.of()
                points.add(
                    IceFillRenderGeometry.Point(
                        roomBaseX + cell.x + 0.5,
                        floorY + 1.02,
                        roomBaseZ + cell.y + 0.5,
                    ),
                )
            }
            return java.util.List.copyOf(points)
        }
    }
}
