package geiler.addons.client.macros.killer

import geiler.addons.client.macros.area.Paint
import geiler.addons.client.macros.area.PaintBrush
import geiler.addons.client.macros.area.PaintMesh
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.navigation.PathfindMacro
import geiler.addons.client.macros.pathing.AreaMemory
import geiler.addons.client.macros.pathing.MoveType
import geiler.addons.client.macros.pathing.Path
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.min

/**
 * Draws what the macro is doing: the gate it will not pass, the route it is walking and what it is hunting. Purely a view -
 * it never asks the macro for anything it does not already have. Drawn with the game's gizmos from the client tick, as the
 * source does.
 */
object AreaOverlay {
    private const val GATE_COLOR = 0xFFFF3B3B.toInt()

    /** The zone around the gate the macro keeps out of, fainter than the gate itself. */
    private const val SLAB_COLOR = 0x80FF3B3B.toInt()
    private const val SLAB_WIDTH = 1.25f
    private const val ROUTE_COLOR = 0xFF31C8FF.toInt()
    private const val JUMP_COLOR = 0xFFFFD24A.toInt()
    private const val DROP_COLOR = 0xFFFF8A3D.toInt()
    private const val TARGET_COLOR = 0xFFFF4D4D.toInt()
    private const val DESTINATION_COLOR = 0xFF2DD4BF.toInt()
    private const val GATE_WIDTH = 2.5f
    private const val ROUTE_WIDTH = 2.0f
    private const val TARGET_WIDTH = 1.75f

    /** The mob after the one being fought, and the route already worked out to it. */
    private const val NEXT_TARGET_COLOR = 0xFFFFE066.toInt()
    private const val NEXT_ROUTE_COLOR = 0xFFFFE066.toInt()
    private const val NEXT_ROUTE_WIDTH = 1.5f

    /** Lifted off the floor so the line does not fight the surface it runs along. */
    private const val ROUTE_HEIGHT = 0.08
    private const val ANCHOR_SIZE = 0.12f

    /** The waypoints the route is divided into, and the one being walked to, as small boxes on the floor. */
    private const val WAYPOINT_COLOR = 0xFF31C8FF.toInt()
    private const val NEXT_WAYPOINT_COLOR = 0xFF4ADE80.toInt()
    private const val WAYPOINT_HALF = 0.12
    private const val WAYPOINT_HEIGHT = 0.22
    private const val WAYPOINT_WIDTH = 1.5f

    /** Places the area memory knows to be trouble, as flat marks on the floor, stronger the more they cost. */
    private const val TROUBLE_COLOR = 0xFFB45AFF.toInt()
    private const val TROUBLE_RADIUS = 32.0

    /** The painted area: how far from the player it is drawn, how many cells at most, and the colours (ARGB, see) of each paint. */
    private const val PAINT_RANGE = 48.0
    private const val PAINT_CELLS = 10000

    /** The joined rectangles of the painted area drawn last, for which area and revision, and from where the cells were read. */
    private const val MESH_MOVE = 6.0
    private var mesh: List<PaintMesh.Rect> = emptyList()
    private var meshName = ""
    private var meshRevision = -1
    private var meshX = 0.0
    private var meshZ = 0.0
    private const val WALL_HEIGHT = 3.0
    private const val ALLOWED_COLOR = 0x6034D058
    private const val PREFERRED_COLOR = 0x80F5C542.toInt()
    private const val FORBIDDEN_COLOR = 0x80E5484D.toInt()
    private const val WALL_COLOR = 0x587B61FF
    private const val REST_COLOR = 0x7040A8F0

    @JvmStatic
    fun init(config: AreaKillerConfig) {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            PaintStore.tick()
            drawPaint(client)
            val hunting = AreaKillerMacro.isRunning()
            val walking = PathfindMacro.isRunning()
            if (!config.showOverlay || client.level == null || (!hunting && !walking)) return@register

            val area = if (hunting) AreaKillerMacro.currentArea() else PathfindMacro.currentGate()
            if (area != null && area.hasGate()) {
                Gizmos.cuboid(area.slabBox().inflate(0.02), GizmoStyle.stroke(SLAB_COLOR, SLAB_WIDTH)).setAlwaysOnTop()
                Gizmos.cuboid(area.gateBox().inflate(0.02), GizmoStyle.stroke(GATE_COLOR, GATE_WIDTH)).setAlwaysOnTop()
            }

            drawRoute(
                if (hunting) AreaKillerMacro.currentPath() else PathfindMacro.currentPath(),
                if (hunting) AreaKillerMacro.currentProgress() else PathfindMacro.currentProgress(),
            )

            val player = client.player
            if (hunting && player != null) {
                for (spot in AreaMemory.near(player.position(), TROUBLE_RADIUS)) {
                    val inset = 0.45 - min(0.35, spot.penalty / 60.0 * 0.35)
                    val mark = AABB(
                        spot.pos.x + inset, spot.pos.y + 0.02, spot.pos.z + inset,
                        spot.pos.x + 1 - inset, spot.pos.y + 0.06, spot.pos.z + 1 - inset,
                    )
                    Gizmos.cuboid(mark, GizmoStyle.stroke(TROUBLE_COLOR, 1.25f))
                }
            }

            val target = AreaKillerMacro.currentTarget()
            if (hunting && target != null) {
                Gizmos.cuboid(target.boundingBox.inflate(0.02), GizmoStyle.stroke(TARGET_COLOR, TARGET_WIDTH)).setAlwaysOnTop()
                // A straight run has no route to draw: the line it takes is the one to the mob, as it is this tick.
                if (player != null && AreaKillerMacro.isDirect()) {
                    Gizmos.line(lifted(player.position()), lifted(target.position()), ROUTE_COLOR, ROUTE_WIDTH).setAlwaysOnTop()
                    val at = target.position()
                    val box = AABB(at.x - WAYPOINT_HALF, at.y, at.z - WAYPOINT_HALF, at.x + WAYPOINT_HALF, at.y + WAYPOINT_HEIGHT, at.z + WAYPOINT_HALF)
                    Gizmos.cuboid(box, GizmoStyle.stroke(NEXT_WAYPOINT_COLOR, WAYPOINT_WIDTH)).setAlwaysOnTop()
                }
            }
            val next = AreaKillerMacro.nextTarget()
            if (hunting && next != null && next !== target) {
                Gizmos.cuboid(next.boundingBox.inflate(0.02), GizmoStyle.stroke(NEXT_TARGET_COLOR, TARGET_WIDTH)).setAlwaysOnTop()
            }
            if (hunting) drawNextRoute(AreaKillerMacro.nextPath())
            val destination = PathfindMacro.currentTarget()
            if (walking && destination != null) {
                Gizmos.cuboid(AABB(destination).inflate(0.02), GizmoStyle.stroke(DESTINATION_COLOR, TARGET_WIDTH)).setAlwaysOnTop()
            }
        }
    }

    /**
     * The painted area, where the player can see it: a flat mark on the floor of every cell (green allowed, gold preferred, red forbidden)
     * and a tall one for a wall (purple), within [PAINT_RANGE] blocks and at most [PAINT_CELLS] of them, and the brush as a ring on the
     * ground where a click would paint. Drawn whether or not the macro runs, so what it may do can be read before it starts.
     */
    private fun drawPaint(client: net.minecraft.client.Minecraft) {
        val player = client.player ?: return
        if (client.level == null) return
        val area = PaintStore.visible()
        if (area != null) {
            // Joined into rectangles, so the paint reads as a plane; worked out again only when the area changes or the player has moved on.
            val stale = area.name != meshName || area.revision() != meshRevision ||
                (player.x - meshX) * (player.x - meshX) + (player.z - meshZ) * (player.z - meshZ) > MESH_MOVE * MESH_MOVE
            if (stale) {
                val cells = ArrayList<PaintMesh.Cell>()
                area.forEachNear(player.x, player.z, PAINT_RANGE, PAINT_CELLS) { x, y, z, type, eighths -> cells.add(PaintMesh.Cell(x, y, z, type, eighths)) }
                mesh = PaintMesh.merge(cells)
                meshName = area.name
                meshRevision = area.revision()
                meshX = player.x
                meshZ = player.z
            }
            for (rect in mesh) {
                val floor = rect.y + rect.eighths / 8.0
                val box = if (rect.type == Paint.WALL) {
                    AABB(rect.x0 + 0.02, rect.y.toDouble(), rect.z0 + 0.02, rect.x1 - 0.02, rect.y + WALL_HEIGHT, rect.z1 - 0.02)
                } else {
                    AABB(rect.x0.toDouble(), floor + 0.01, rect.z0.toDouble(), rect.x1.toDouble(), floor + 0.04, rect.z1.toDouble())
                }
                val color = when (rect.type) {
                    Paint.ALLOWED -> ALLOWED_COLOR
                    Paint.PREFERRED -> PREFERRED_COLOR
                    Paint.FORBIDDEN -> FORBIDDEN_COLOR
                    Paint.WALL -> WALL_COLOR
                    Paint.REST -> REST_COLOR
                }
                Gizmos.cuboid(box, GizmoStyle.fill(color))
            }
        } else {
            mesh = emptyList()
            meshName = ""
        }
        val brush = PaintBrush.active() ?: return
        val centre = PaintBrush.aim(client) ?: return
        val ring = 0xFFFFFFFF.toInt()
        val steps = 28
        var previous: Vec3? = null
        for (i in 0..steps) {
            val angle = i * 2.0 * Math.PI / steps
            val at = Vec3(centre.x + 0.5 + Math.cos(angle) * (brush.radius + 0.5), centre.y + 0.1, centre.z + 0.5 + Math.sin(angle) * (brush.radius + 0.5))
            previous?.let { Gizmos.line(it, at, ring, 2.0f).setAlwaysOnTop() }
            previous = at
        }
    }

    private fun drawRoute(route: Path?, progress: Double) {
        if (route == null) return

        var nextFound = false
        for (step in route.steps()) {
            if (!step.waypoint || step.distance <= progress) continue
            val next = !nextFound
            nextFound = true
            val at = step.position
            val box = AABB(
                at.x - WAYPOINT_HALF, at.y, at.z - WAYPOINT_HALF, at.x + WAYPOINT_HALF, at.y + WAYPOINT_HEIGHT, at.z + WAYPOINT_HALF,
            )
            Gizmos.cuboid(box, GizmoStyle.stroke(if (next) NEXT_WAYPOINT_COLOR else WAYPOINT_COLOR, WAYPOINT_WIDTH)).setAlwaysOnTop()
        }

        for (i in 1 until route.steps().size) {
            val previous = route.steps()[i - 1]
            val step = route.steps()[i]
            Gizmos.line(lifted(previous.position), lifted(step.position), ROUTE_COLOR, ROUTE_WIDTH).setAlwaysOnTop()

            // Mark the places the follower has to do something other than walk, so a route that keeps getting stuck can be
            // read at a glance.
            if (step.move == MoveType.JUMP) {
                Gizmos.point(lifted(step.position), JUMP_COLOR, ANCHOR_SIZE).setAlwaysOnTop()
            } else if (step.move == MoveType.DROP) {
                Gizmos.point(lifted(step.position), DROP_COLOR, ANCHOR_SIZE).setAlwaysOnTop()
            }
        }
    }

    private fun drawNextRoute(route: Path?) {
        if (route == null) return
        val steps = route.steps()
        for (i in 1 until steps.size) {
            Gizmos.line(lifted(steps[i - 1].position), lifted(steps[i].position), NEXT_ROUTE_COLOR, NEXT_ROUTE_WIDTH).setAlwaysOnTop()
        }
    }

    private fun lifted(point: Vec3): Vec3 = point.add(0.0, ROUTE_HEIGHT, 0.0)
}
