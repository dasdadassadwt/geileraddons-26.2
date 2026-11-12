package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.HashMap
import java.util.UUID

/** Client-thread implementation for loaded-only Blaze target boxes and kill-order links. */
internal class DungeonOdinBlazeOverlayLogic {
    private var lastLevel: Any? = null
    private var lastRoomKey: String? = null
    private var lastScanTick = Long.MIN_VALUE
    private var cachedMarkers: List<Marker> = emptyList()
    private var lastTrackedTargetCount = -1
    private val roomCoreScanner = OdinDungeonMapCoreScanner()

    /** Call only on the client render thread after the module has selected the Blaze solver. */
    fun render(
        context: LevelRenderContext?,
        settings: DungeonOdinBlazeOverlay.Settings?,
    ): DungeonOdinBlazeOverlay.Frame {
        val client: Minecraft? = Minecraft.getInstance()
        if (client == null || !client.isSameThread || client.level == null || client.player == null ||
            client.gameRenderer == null || !DungeonContextTracker.inDungeon() || settings == null
        ) {
            reset()
            return DungeonOdinBlazeOverlay.Frame.none()
        }
        val level = client.level ?: run {
            reset()
            return DungeonOdinBlazeOverlay.Frame.none()
        }
        val puzzleRoom = currentPuzzleRoom(client)
        if (puzzleRoom == null) {
            reset()
            return DungeonOdinBlazeOverlay.Frame.none()
        }
        val room = puzzleRoom.cell
        val roomKey = "${puzzleRoom.floorNumber}|${puzzleRoom.mapSignature}|" +
            "${room.componentX},${room.componentZ}|${room.baseX},${room.baseZ}"
        if (lastLevel !== level || roomKey != lastRoomKey) {
            lastLevel = level
            lastRoomKey = roomKey
            lastScanTick = Long.MIN_VALUE
            cachedMarkers = emptyList()
            lastTrackedTargetCount = -1
        }

        val gameTick = level.gameTime
        if (lastScanTick == Long.MIN_VALUE || gameTick < lastScanTick ||
            gameTick - lastScanTick >= SCAN_PERIOD_TICKS
        ) {
            cachedMarkers = scan(level)
            lastScanTick = gameTick
        }
        val resolved = resolveCurrentTargets(level)
        val roomData = roomCoreScanner.roomDataAt(client, room)
        val roomName = roomData?.name()
        val position = DungeonOdinBlazeRules.positionForRoomName(roomName)
        if (position == DungeonOdinBlazeRules.Position.UNSET) return DungeonOdinBlazeOverlay.Frame.none()
        val candidates = resolved.map { it.candidate }
        val ordered = DungeonOdinBlazeRules.orderTargets(candidates, true, position)
        val completedLastTarget = isLastTargetTransition(lastTrackedTargetCount, ordered.size)
        lastTrackedTargetCount = ordered.size
        val frame = DungeonOdinBlazeOverlay.Frame(roomName, ordered.size, completedLastTarget)
        if (ordered.isEmpty()) return frame

        val byId = HashMap<String, ResolvedTarget>()
        for (target in resolved) target.candidate.stableId()?.let { byId.putIfAbsent(it, target) }
        val camera = client.gameRenderer.mainCamera().position()
        val partialTick = client.deltaTracker.getGameTimeDeltaPartialTick(true)
        val pose = context!!.poseStack()
        val buffers = WorldRenderBuffer.current()
        val drawTargets = ArrayList<DrawTarget>(ordered.size)
        for ((index, candidate) in ordered.withIndex()) {
            val target = candidate.stableId()?.let(byId::get) ?: continue
            val label = target.label
            val renderPosition = label.getPosition(partialTick)
            val box = label.boundingBox
                .move(renderPosition.subtract(label.position()))
                .inflate(0.5, 1.0, 0.5)
                .move(0.0, -1.0, 0.0)
            val line = colorFor(settings, index)
            val fill = fillFor(line, settings.style())
            val outline = if (settings.style() == DungeonPuzzleVisualRenderer.BoxStyle.FILLED) 0 else line
            EspRenderer.renderBox(
                pose,
                buffers,
                box.minX - camera.x,
                box.minY - camera.y,
                box.minZ - camera.z,
                box.getXsize(),
                box.getYsize(),
                box.getZsize(),
                fill,
                outline,
                BOX_OUTLINE_WIDTH,
                true,
            )
            drawTargets.add(DrawTarget(renderPosition, box.center))
        }
        val lastLinkIndex = lastLinkIndex(settings, drawTargets.size)
        for (index in 1..lastLinkIndex) {
            val from = drawTargets[index - 1].renderPosition.subtract(camera)
            val to = drawTargets[index].boxCenter.subtract(camera)
            EspRenderer.renderLine(
                pose,
                buffers,
                from.x,
                from.y,
                from.z,
                to.x,
                to.y,
                to.z,
                colorFor(settings, index),
                settings.lineWidth(),
                true,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
        return frame
    }

    fun reset() {
        lastLevel = null
        lastRoomKey = null
        lastScanTick = Long.MIN_VALUE
        cachedMarkers = emptyList()
        lastTrackedTargetCount = -1
    }

    private fun scan(level: net.minecraft.client.multiplayer.ClientLevel): List<Marker> {
        val markers = ArrayList<Marker>()
        for (label in level.entitiesForRendering()) {
            if (label !is ArmorStand || label.isRemoved || !label.isAlive || label.customName == null ||
                DungeonPuzzleLogic.parseBlazeHealth(ChatText.plain(label.customName!!.string)) == null
            ) continue
            markers.add(Marker(label.id, label.uuid))
        }
        return java.util.List.copyOf(markers)
    }

    private fun resolveCurrentTargets(level: net.minecraft.client.multiplayer.ClientLevel): List<ResolvedTarget> {
        val resolved = ArrayList<ResolvedTarget>()
        for (marker in cachedMarkers) {
            val label = level.getEntity(marker.entityId)
            if (label == null || label.isRemoved || !label.isAlive || marker.uuid != label.uuid ||
                label.customName == null
            ) continue
            val health: Int? = DungeonPuzzleLogic.parseBlazeHealth(ChatText.plain(label.customName!!.string))
            if (health == null || health < 0) continue
            val candidate = DungeonOdinBlazeRules.Candidate(
                marker.uuid.toString(),
                health,
                DungeonOdinBlazeRules.Source.ARMOR_STAND_NAMEPLATE,
                true,
                true,
                true,
                true,
            )
            resolved.add(ResolvedTarget(candidate, label))
        }
        return java.util.List.copyOf(resolved)
    }

    private fun currentPuzzleRoom(client: Minecraft?): PuzzleRoom? {
        if (client == null || client.player == null || client.level == null || !client.isSameThread) return null
        val map = DungeonMapRoomDetector.current()
        if (map == null || !map.hasCurrentCell()) return null
        val geometry = map.geometry ?: return null
        if (geometry.roomType() != DungeonMapGeometry.RoomType.PUZZLE) return null
        val room: DungeonGridRules.Cell? = map.currentCell
        val player = client.player ?: return null
        val playerCell = DungeonGridRules.cellAt(
            player.blockPosition().x,
            player.blockPosition().z,
        )
        if (room == null || playerCell == null || room.componentX != playerCell.componentX ||
            room.componentZ != playerCell.componentZ
        ) return null
        val floor: DungeonFloor? = DungeonContextTracker.currentFloor()
        return PuzzleRoom(room, map.signature(), floor?.number() ?: -1)
    }

    private data class Marker(val entityId: Int, val uuid: UUID)
    private data class PuzzleRoom(
        val cell: DungeonGridRules.Cell,
        val mapSignature: String,
        val floorNumber: Int,
    )
    private data class ResolvedTarget(val candidate: DungeonOdinBlazeRules.Candidate, val label: Entity)
    private data class DrawTarget(val renderPosition: Vec3, val boxCenter: Vec3)

    companion object {
        private const val SCAN_PERIOD_TICKS = 5L
        private const val BOX_OUTLINE_WIDTH = 3.0f

        @JvmStatic
        fun colorFor(settings: DungeonOdinBlazeOverlay.Settings?, index: Int): Int {
            if (settings == null) return 0xFFFFFFFF.toInt()
            return when (index) {
                0 -> settings.firstColor()
                1 -> settings.secondColor()
                2 -> settings.thirdColor()
                else -> settings.otherColor()
            }
        }

        @JvmStatic
        fun fillFor(argb: Int, style: DungeonPuzzleVisualRenderer.BoxStyle?): Int {
            val effectiveStyle = style ?: DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE
            return when (effectiveStyle) {
                DungeonPuzzleVisualRenderer.BoxStyle.OUTLINE -> 0
                DungeonPuzzleVisualRenderer.BoxStyle.FILLED -> argb
                DungeonPuzzleVisualRenderer.BoxStyle.FILLED_OUTLINE ->
                    (argb and 0x00FFFFFF) or ((((argb ushr 24) / 2) shl 24))
            }
        }

        @JvmStatic
        fun lastLinkIndex(settings: DungeonOdinBlazeOverlay.Settings?, targetCount: Int): Int {
            if (settings == null || !settings.nextLine() || targetCount < 2) return 0
            return minOf(settings.lineCount(), targetCount - 1)
        }

        @JvmStatic
        fun isLastTargetTransition(previousCount: Int, currentCount: Int): Boolean =
            previousCount == 1 && currentCount == 0

        @JvmStatic
        fun shouldSendComplete(
            settings: DungeonOdinBlazeOverlay.Settings?,
            frame: DungeonOdinBlazeOverlay.Frame?,
        ): Boolean = settings != null && settings.sendComplete() && frame != null && frame.completedLastTarget()
    }
}
