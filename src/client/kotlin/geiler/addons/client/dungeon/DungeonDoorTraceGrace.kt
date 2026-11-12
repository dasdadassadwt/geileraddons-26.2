package geiler.addons.client.dungeon

import java.util.function.Function

/** Retains an eligible dungeon-door trace briefly through transient target-selection gaps. */
class DungeonDoorTraceGrace {
    private var world: Any? = null
    private var floor: DungeonFloor? = null
    private var target: DungeonDoorTracker.Door? = null
    private var lastEligibleTick: Long = Long.MIN_VALUE

    fun update(
        inDungeon: Boolean,
        currentWorld: Any?,
        currentFloor: DungeonFloor?,
        selected: DungeonDoorTracker.Door?,
        closed: Boolean,
        eligible: Boolean,
        gameTick: Long,
    ): DungeonDoorTracker.Door? = update(
        inDungeon,
        currentWorld,
        currentFloor,
        selected,
        if (closed) BloodDoorTraceRules.DoorPresence.CLOSED else BloodDoorTraceRules.DoorPresence.OPEN,
        eligible,
        Function { _ -> BloodDoorTraceRules.DoorPresence.UNKNOWN },
        gameTick,
    )

    fun update(
        inDungeon: Boolean,
        currentWorld: Any?,
        currentFloor: DungeonFloor?,
        selected: DungeonDoorTracker.Door?,
        selectedPresence: BloodDoorTraceRules.DoorPresence?,
        eligible: Boolean,
        presenceOfRetainedTarget: Function<DungeonDoorTracker.Door, BloodDoorTraceRules.DoorPresence>?,
        gameTick: Long,
    ): DungeonDoorTracker.Door? {
        if (!inDungeon || currentWorld == null) {
            reset()
            return null
        }
        if (world !== currentWorld || floor != currentFloor) {
            reset()
            world = currentWorld
            floor = currentFloor
        }
        if (selected != null && selectedPresence == BloodDoorTraceRules.DoorPresence.CLOSED && eligible) {
            if (selected != target) clearTarget()
            target = selected
            lastEligibleTick = gameTick
            return target
        }
        val retained = target ?: return null
        if (selected == retained && selectedPresence == BloodDoorTraceRules.DoorPresence.OPEN) {
            clearTarget()
            return null
        }
        val retainedPresence = presenceOfRetainedTarget?.apply(retained)
            ?: BloodDoorTraceRules.DoorPresence.UNKNOWN
        if (retainedPresence == BloodDoorTraceRules.DoorPresence.OPEN || gameTick < lastEligibleTick ||
            gameTick - lastEligibleTick >= GRACE_TICKS
        ) {
            clearTarget()
            return null
        }
        return retained
    }

    fun reset() {
        world = null
        floor = null
        clearTarget()
    }

    private fun clearTarget() {
        target = null
        lastEligibleTick = Long.MIN_VALUE
    }

    companion object {
        const val GRACE_TICKS = 10L * 20L
    }
}
