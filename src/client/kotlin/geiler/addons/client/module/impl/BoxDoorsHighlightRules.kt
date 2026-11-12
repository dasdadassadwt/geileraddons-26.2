package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.BloodDoorTraceRules
import geiler.addons.client.dungeon.DungeonDoorTracker
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/** Small source-state and highlight rules shared by Box Doors and its offline checks. */
object BoxDoorsHighlightRules {
    /** Box Doors submits fill/outline geometry for only the two key-gated door types. */
    @JvmStatic
    fun rendersBox(type: DungeonDoorTracker.Type?): Boolean =
        type == DungeonDoorTracker.Type.WITHER || type == DungeonDoorTracker.Type.BLOOD

    /** A retained location is still a door record after opening, but an open/unknown door is not drawn. */
    @JvmStatic
    fun rendersBox(
        type: DungeonDoorTracker.Type?,
        presence: BloodDoorTraceRules.DoorPresence?,
    ): Boolean = rendersBox(type) && presence == BloodDoorTraceRules.DoorPresence.CLOSED

    /** Tracers are suppressed when a retained record's current source samples are open or unknown. */
    @JvmStatic
    fun rendersTracer(presence: BloodDoorTraceRules.DoorPresence?): Boolean =
        presence == BloodDoorTraceRules.DoorPresence.CLOSED

    /** Tests the authoritative closed-door source state, which may differ from a local glass projection. */
    @JvmStatic
    fun isDoorSourceState(type: DungeonDoorTracker.Type?, state: BlockState?): Boolean {
        if (type == null || state == null) return false
        return (type == DungeonDoorTracker.Type.WITHER && state.`is`(Blocks.COAL_BLOCK)) ||
            (type == DungeonDoorTracker.Type.BLOOD && state.`is`(Blocks.DYED_TERRACOTTA.pick(DyeColor.RED)))
    }
}
