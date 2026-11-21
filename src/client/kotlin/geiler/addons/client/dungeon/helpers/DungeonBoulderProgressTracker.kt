package geiler.addons.client.dungeon.helpers

import java.util.HashSet

/** Mutable local-click progress for the Java-compatible Boulder tracker. */
class DungeonBoulderProgressTracker {
    private var identity: String? = null
    private var targets: List<DungeonBoulderRules.Target> = java.util.List.of()
    private val observedSteps = HashSet<Int>()

    fun observe(identity: String?, targets: List<DungeonBoulderRules.Target?>?) {
        val nextIdentity = identity ?: ""
        val nextTargets = if (targets == null) java.util.List.of() else java.util.List.copyOf(
            targets.map { it ?: throw NullPointerException() },
        )
        if (nextIdentity == this.identity && nextTargets == this.targets) return
        this.identity = nextIdentity
        this.targets = nextTargets
        observedSteps.clear()
    }

    fun observeClick(position: DungeonBoulderRules.WorldPosition?): Boolean {
        if (position == null || targets.isEmpty()) return false
        for (index in targets.indices) {
            if (observedSteps.contains(index)) continue
            val target = targets[index]
            if (target != null && position == target.clickPosition()) return observedSteps.add(index)
        }
        return false
    }

    fun remainingTargets(showAll: Boolean): List<DungeonBoulderRules.Target> {
        val remaining = ArrayList<DungeonBoulderRules.Target>()
        for (index in targets.indices) {
            if (observedSteps.contains(index)) continue
            val target = targets[index]
            if (target != null) remaining.add(target)
            if (!showAll && remaining.isNotEmpty()) break
        }
        return java.util.List.copyOf(remaining)
    }

    fun snapshot(): DungeonBoulderProgressTracker.Snapshot {
        var current: DungeonBoulderRules.Target? = null
        for (index in targets.indices) {
            if (observedSteps.contains(index)) continue
            current = targets[index]
            if (current != null) break
        }
        return DungeonBoulderProgressTracker.Snapshot(observedSteps.size, targets.size, current)
    }

    fun clear() {
        identity = null
        targets = java.util.List.of()
        observedSteps.clear()
    }

    class Snapshot(completed: Int, total: Int, current: DungeonBoulderRules.Target?) {
        private val completedValue = completed.coerceAtLeast(0)
        private val totalValue = total.coerceAtLeast(0)
        private val currentValue = current

        fun completed(): Int = completedValue
        fun total(): Int = totalValue
        fun current(): DungeonBoulderRules.Target? = currentValue

        fun known(): Boolean = totalValue > 0

        fun complete(): Boolean = known() && completedValue >= totalValue

        override fun equals(other: Any?): Boolean = other is Snapshot && completedValue == other.completedValue &&
            totalValue == other.totalValue && currentValue == other.currentValue

        override fun hashCode(): Int {
            var result = completedValue
            result = 31 * result + totalValue
            result = 31 * result + (currentValue?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = "Snapshot[completed=$completedValue, total=$totalValue, current=$currentValue]"
    }
}
