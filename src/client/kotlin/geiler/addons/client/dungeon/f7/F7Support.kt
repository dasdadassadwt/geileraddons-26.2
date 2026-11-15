package geiler.addons.client.dungeon.f7

import geiler.addons.client.dungeon.DungeonContextTracker
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.core.component.DataComponents

/** Small shared helpers for the F7/M7 boss modules. */
object F7Support {
    enum class Phase { UNKNOWN, P1, P2, P3, P4, P5 }

    /** Odin's y cut-offs for the F7 boss phases. */
    @JvmStatic
    fun phaseForY(y: Double): Phase = when {
        y > 210 -> Phase.P1
        y > 155 -> Phase.P2
        y > 100 -> Phase.P3
        y > 45 -> Phase.P4
        else -> Phase.P5
    }

    /** Floor 7 boss room, master mode or not. */
    @JvmStatic
    fun inFloor7Boss(): Boolean = DungeonContextTracker.inDungeon() &&
        DungeonContextTracker.currentFloor()?.number() == 7 && !DungeonContextTracker.isMobEspPhase()

    @JvmStatic
    fun currentPhase(): Phase {
        if (!inFloor7Boss()) return Phase.UNKNOWN
        val player = Minecraft.getInstance().player ?: return Phase.UNKNOWN
        return phaseForY(player.y)
    }

    @JvmStatic
    fun inPhase5(): Boolean = currentPhase() == Phase.P5

    /** Client-only chat line with the mod prefix. */
    @JvmStatic
    fun message(text: String) {
        Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("§d[GeilerAddons] §r$text"))
    }

    /** The Skyblock `id` from an item's custom data, or null. */
    @JvmStatic
    fun skyblockId(stack: ItemStack): String? =
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("id", "")?.ifEmpty { null }
}
