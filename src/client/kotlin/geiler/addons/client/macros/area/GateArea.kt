package geiler.addons.client.macros.area

import geiler.addons.client.macros.pathing.Region
import geiler.addons.client.macros.pathing.ShutSweep
import geiler.addons.client.macros.pathing.Terrain
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Objects
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** An optional extra box the macro may never leave, on top of the gate: two block positions, both included. */
class GateBounds(@JvmField val min: IntArray, @JvmField val max: IntArray)

/**
 * A gate the macro may neither cross nor come close to.
 *
 * The gate is a row of blocks across a tunnel, along one axis. What is shut is a slab: every column of the gate, at every
 * height, plus the `margin` around it. No stand exists in the slab, so no route can pass through it, and the macro keeps
 * clear of the gate by the margin instead of brushing past it.
 *
 * Nothing here says which part of the world is "the area". That is the pathfinder's doing: it only ever walks from where
 * the player stands, so it stays in whatever that stretch of tunnel connects to without going through the gate. This is
 * deliberately not a half-space. A tunnel winds, and the plane through a gate can cut it again further on, on the very
 * side the macro is meant to work in.
 *
 * What that relies on is that the gate spans the whole width of the tunnel. [findOpening] looks for the one way that goes
 * wrong - walkable ground beside the gate's ends - so it can be reported.
 */
class GateArea private constructor(
    private val axis: Axis,
    private val plane: Int,
    private val lowEnd: Int,
    private val highEnd: Int,
    private val gateY: Int,
    private val margin: Int,
    private val boundsMin: IntArray?,
    private val boundsMax: IntArray?,
    private val gated: Boolean,
    private val paint: PaintedArea?,
) : Region {
    private enum class Axis {
        /** The gate runs along Z, so it sits in the plane x = const. */
        X,

        /** The gate runs along X, so it sits in the plane z = const. */
        Z,
    }

    /** Two gates that shut the same blocks are the same region, so a map of the ground made for one is good for the other. */
    override fun equals(other: Any?): Boolean = other is GateArea &&
        axis == other.axis && plane == other.plane && lowEnd == other.lowEnd && highEnd == other.highEnd &&
        gateY == other.gateY && margin == other.margin && gated == other.gated &&
        boundsMin.contentEquals(other.boundsMin) && boundsMax.contentEquals(other.boundsMax) && paintId() == other.paintId()

    override fun hashCode(): Int = Objects.hash(
        axis, plane, lowEnd, highEnd, gateY, margin, gated, boundsMin.contentHashCode(), boundsMax.contentHashCode(), paintId(),
    )

    /** The paint and its revision: two regions with different paint are different, so a map of the ground is not shared. */
    private fun paintId(): String? = paint?.let { it.name + ":" + it.revision() }

    /** Whether there is a gate: a region may be only a painted area, or only bounds. */
    fun hasGate(): Boolean = gated

    /** The paint that limits where the macro may stand, or null. */
    fun paint(): PaintedArea? = paint

    /** Whether a block column is within `reach` blocks of the gate row, at any height. */
    private fun inSlab(x: Int, z: Int, reach: Int): Boolean {
        if (!gated) return false
        val across = if (axis == Axis.X) x else z
        val along = if (axis == Axis.X) z else x
        return abs(across - plane) <= reach && along >= lowEnd - reach && along <= highEnd + reach
    }

    private fun insideBounds(x: Int, y: Int, z: Int): Boolean {
        val low = boundsMin
        val high = boundsMax
        return low == null || high == null ||
            (x >= low[0] && x <= high[0] && y >= low[1] && y <= high[1] && z >= low[2] && z <= high[2])
    }

    /**
     * Where the planner may put a stand: outside the margin and one block more. The extra block is what the hard limit of
     * [breached] is spared by - a player does not stop on the spot at the end of a route, and a route that ended right at
     * the edge of the limit would trip it with every bit of overshoot.
     */
    override fun contains(x: Int, y: Int, z: Int): Boolean =
        !inSlab(x, z, margin + PLANNING_BUFFER) && insideBounds(x, y, z) && (paint == null || paint.allows(x, y, z))

    /** What a step into this cell costs relative to the plain cost: preferred painted ground is cheaper. */
    override fun costFactor(x: Int, y: Int, z: Int): Double = paint?.costFactor(x, y, z) ?: 1.0

    override fun minCostFactor(): Double = paint?.minCostFactor() ?: 1.0

    override fun crossesShut(from: Vec3, to: Vec3, halfWidth: Double, height: Double): Boolean =
        ShutSweep.crosses(
            from.x, from.y, from.z, to.x, to.y, to.z, halfWidth, height,
            ShutSweep.Shut { x, y, z -> inSlab(x, z, margin + PLANNING_BUFFER) || !insideBounds(x, y, z) || paint?.shutAt(x, y, z) == true },
        )

    /**
     * Whether a position - the feet of the player, the base of a mob - is somewhere the planner may use. A mob hops, so the cell it is
     * in and the two under it count against the paint.
     */
    fun containsPoint(position: Vec3): Boolean {
        val x = Mth.floor(position.x)
        val y = Mth.floor(position.y + 1.0E-4)
        val z = Mth.floor(position.z)
        return contains(x, y, z) || (paint != null && (contains(x, y - 1, z) || contains(x, y - 2, z)))
    }

    /**
     * The hard limit: whether this position is in the gate or its margin, or outside the extra box. The macro stops dead
     * the moment this is true, whatever the planner thought it was doing.
     */
    fun breached(position: Vec3): Boolean {
        val x = Mth.floor(position.x)
        val y = Mth.floor(position.y + 1.0E-4)
        val z = Mth.floor(position.z)
        return inSlab(x, z, margin) || !insideBounds(x, y, z)
    }

    fun containsEntity(entity: Entity): Boolean = containsPoint(entity.position())

    /**
     * Whether the straight line between two points goes through the gate. A mob on the far side of it is in plain sight
     * and out of reach, and is not worth a search that can only end by running out of tunnel.
     */
    fun throughGate(from: Vec3, to: Vec3): Boolean {
        if (!gated) return false
        val middle = plane + 0.5
        val fromAcross = (if (axis == Axis.X) from.x else from.z) - middle
        val toAcross = (if (axis == Axis.X) to.x else to.z) - middle
        if (fromAcross * toAcross >= 0.0) return false
        val t = fromAcross / (fromAcross - toAcross)
        val along = if (axis == Axis.X) from.z + t * (to.z - from.z) else from.x + t * (to.x - from.x)
        val reach = margin + PLANNING_BUFFER
        return along >= lowEnd - reach && along <= highEnd + 1 + reach
    }

    /**
     * Looks for walkable ground right beside the ends of the gate, where the gate would not cover it. That is what a
     * tunnel wider than the gate looks like, and the one thing that lets a route go round it.
     *
     * @return where such ground was found, as `"x y z"`, or null
     */
    fun findOpening(terrain: Terrain): String? {
        if (!gated) return null
        for (offset in 1..OPENING_REACH) {
            for (along in intArrayOf(lowEnd - offset, highEnd + offset)) {
                for (y in gateY - 1..gateY + 3) {
                    val x = if (axis == Axis.X) plane else along
                    val z = if (axis == Axis.X) along else plane
                    if (terrain.standAt(x, y, z) != null) return "$x $y $z"
                }
            }
        }
        return null
    }

    /** The gate itself as a box, for drawing. */
    fun gateBox(): AABB = if (axis == Axis.X) {
        AABB(plane.toDouble(), gateY.toDouble(), lowEnd.toDouble(), (plane + 1).toDouble(), gateY + GATE_HEIGHT, (highEnd + 1).toDouble())
    } else {
        AABB(lowEnd.toDouble(), gateY.toDouble(), plane.toDouble(), (highEnd + 1).toDouble(), gateY + GATE_HEIGHT, (plane + 1).toDouble())
    }

    /** The shut slab as a box, for drawing: the gate and its margin. */
    fun slabBox(): AABB = gateBox().inflate(margin.toDouble(), 0.0, margin.toDouble())

    /** Human-readable description, for chat. */
    fun describe(): String =
        (if (gated) "gate ${describeGate()}, margin $margin" else "no gate") + (paint?.let { ", area '${it.name}' (${it.size()} cells)" } ?: "")

    /** The gate as a short label, e.g. `x=-120 z=-63..-56`. */
    fun describeGate(): String = if (axis == Axis.X) "x=$plane z=$lowEnd..$highEnd" else "z=$plane x=$lowEnd..$highEnd"

    companion object {
        private const val GATE_HEIGHT = 3.0

        /** Blocks the planner keeps beyond the margin; see [contains]. */
        private const val PLANNING_BUFFER = 1

        /** How far past each end of the gate to look for ground the gate does not cover. */
        private const val OPENING_REACH = 3

        /** @throws IllegalArgumentException when the two gate ends do not lie on one straight axis-aligned row */
        @JvmStatic
        @JvmOverloads
        fun of(a: IntArray, b: IntArray, margin: Int, bounds: GateBounds? = null): GateArea {
            val axis: Axis
            val plane: Int
            val lowEnd: Int
            val highEnd: Int
            if (a[0] == b[0]) {
                axis = Axis.X
                plane = a[0]
                lowEnd = min(a[2], b[2])
                highEnd = max(a[2], b[2])
            } else if (a[2] == b[2]) {
                axis = Axis.Z
                plane = a[2]
                lowEnd = min(a[0], b[0])
                highEnd = max(a[0], b[0])
            } else {
                throw IllegalArgumentException("The gate must run along the X or the Z axis: one coordinate has to be equal at both ends")
            }
            var low: IntArray? = null
            var high: IntArray? = null
            if (bounds != null) {
                low = IntArray(3)
                high = IntArray(3)
                for (i in 0 until 3) {
                    low[i] = min(bounds.min[i], bounds.max[i])
                    high[i] = max(bounds.min[i], bounds.max[i])
                }
            }
            return GateArea(axis, plane, lowEnd, highEnd, min(a[1], b[1]), margin, low, high, true, null)
        }

        /**
         * The region of a run: the gate when both ends are given (three numbers each; anything else is no gate), the extra box, and
         * the painted area, any of them. A gate is what [of] says it is.
         *
         * @throws IllegalArgumentException when the two gate ends do not lie on one straight axis-aligned row
         */
        @JvmStatic
        fun create(a: IntArray, b: IntArray, margin: Int, bounds: GateBounds?, paint: PaintedArea?): GateArea {
            var low: IntArray? = null
            var high: IntArray? = null
            if (bounds != null) {
                low = IntArray(3) { min(bounds.min[it], bounds.max[it]) }
                high = IntArray(3) { max(bounds.min[it], bounds.max[it]) }
            }
            if (a.size != 3 || b.size != 3) return GateArea(Axis.X, 0, 0, 0, 0, margin, low, high, false, paint)
            val gate = of(a, b, margin, bounds)
            return GateArea(gate.axis, gate.plane, gate.lowEnd, gate.highEnd, gate.gateY, margin, low, high, true, paint)
        }
    }
}
