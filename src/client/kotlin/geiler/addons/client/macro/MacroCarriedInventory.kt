package geiler.addons.client.macro

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack

/** Client-thread adapter that counts only the player's carried slots, never open containers or sack contents. */
object MacroCarriedInventory {
    @JvmStatic
    fun count(minecraft: Minecraft?, skyBlockId: String?, vanillaId: String?): Int {
        val player = minecraft?.player ?: return 0
        val inventory = player.inventory
        val stacks = ArrayList<MacroItemCountRules.StackIdentity?>(inventory.containerSize)
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack == null || stack.isEmpty) continue
            stacks.add(
                MacroItemCountRules.StackIdentity(
                    MacroCarriedInventory.skyBlockId(stack),
                    BuiltInRegistries.ITEM.getKey(stack.item).toString(),
                    stack.count,
                ),
            )
        }
        return MacroItemCountRules.count(stacks, skyBlockId, vanillaId)
    }

    /** Hypixel stores its item id in the custom-data component's textual NBT form. */
    @JvmStatic
    fun skyBlockId(stack: ItemStack): String {
        val customData = stack.get(DataComponents.CUSTOM_DATA)
        return customData?.toString()?.let(MacroItemCountRules::skyBlockIdFromCustomData) ?: ""
    }
}
