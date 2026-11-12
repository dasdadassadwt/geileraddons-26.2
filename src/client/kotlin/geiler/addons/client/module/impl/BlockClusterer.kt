package geiler.addons.client.module.impl

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.longs.LongIterator
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.concurrent.CancellationException

/** Pure six-neighbour connected-component grouping for ESP block positions. */
object BlockClusterer {
    private val faces = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
    )

    /** Public static bridge keeps the Java-facing Block ESP call shape. */
    @JvmStatic
    fun cluster(source: Collection<BlockPos?>?, connectTouching: Boolean): List<Cluster> {
        if (source == null || source.isEmpty()) return java.util.List.copyOf(emptyList())
        if (!connectTouching) {
            val seen = LongOpenHashSet(source.size)
            val singles = ArrayList<Cluster>()
            for (position in source) {
                if (position != null && seen.add(position.asLong())) singles.add(clusterOf(listOf(position)))
            }
            return java.util.List.copyOf(singles)
        }

        val remaining = LongOpenHashSet(source.size)
        var checked = 0
        for (position in source) {
            checkCancelled(checked++)
            if (position != null) remaining.add(position.asLong())
        }
        if (remaining.isEmpty()) return java.util.List.copyOf(emptyList())

        val result = ArrayList<Cluster>()
        while (!remaining.isEmpty()) {
            val starts: LongIterator = remaining.iterator()
            val start = starts.nextLong()
            remaining.remove(start)
            val pending = LongArrayFIFOQueue()
            val component = ArrayList<BlockPos>()
            pending.enqueue(start)
            var sumX = 0L
            var sumY = 0L
            var sumZ = 0L
            while (!pending.isEmpty()) {
                checkCancelled(checked++)
                val current = pending.dequeueLong()
                val position = BlockPos.of(current)
                component.add(position)
                sumX += position.x
                sumY += position.y
                sumZ += position.z
                for (face in faces) {
                    val adjacent = BlockPos.offset(current, face[0], face[1], face[2])
                    if (remaining.remove(adjacent)) pending.enqueue(adjacent)
                }
            }
            val size = component.size.toDouble()
            result.add(
                Cluster(
                    component,
                    Vec3(
                        (sumX + 0.5 * size) / size,
                        (sumY + 0.5 * size) / size,
                        (sumZ + 0.5 * size) / size,
                    ),
                ),
            )
        }
        return java.util.List.copyOf(result)
    }

    private fun checkCancelled(checked: Int) {
        if ((checked and 0x3FF) == 0 && Thread.currentThread().isInterrupted) {
            throw CancellationException()
        }
    }

    private fun clusterOf(blocks: List<BlockPos>): Cluster {
        val immutable = java.util.List.copyOf(blocks)
        var sumX = 0L
        var sumY = 0L
        var sumZ = 0L
        for (position in immutable) {
            sumX += position.x
            sumY += position.y
            sumZ += position.z
        }
        val size = immutable.size.toDouble()
        return Cluster(
            immutable,
            Vec3(
                (sumX + 0.5 * size) / size,
                (sumY + 0.5 * size) / size,
                (sumZ + 0.5 * size) / size,
            ),
        )
    }

    /** Immutable result retaining the old Java constructor and accessor names. */
    class Cluster(blocks: List<BlockPos>?, center: Vec3?) {
        private val blockValues: List<BlockPos> = java.util.List.copyOf(blocks)
        private val centerValue: Vec3? = center

        fun blocks(): List<BlockPos> = blockValues

        fun center(): Vec3? = centerValue

        override fun equals(other: Any?): Boolean =
            this === other || other is Cluster && blockValues == other.blockValues && centerValue == other.centerValue

        override fun hashCode(): Int = 31 * blockValues.hashCode() + (centerValue?.hashCode() ?: 0)

        override fun toString(): String = "Cluster[blocks=$blockValues, center=$centerValue]"
    }
}
