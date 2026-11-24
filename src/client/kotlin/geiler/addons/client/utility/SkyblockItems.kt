package geiler.addons.client.utility

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.macro.MacroCarriedInventory
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/** SkyBlock item facts read from a stack: its Hypixel id, unique id, plain lore and right-click ability line. */
object SkyblockItems {
    /** The Hypixel `id` from the stack's custom data, or an empty string. */
    @JvmStatic
    fun skyBlockId(stack: ItemStack): String = if (stack.isEmpty) "" else MacroCarriedInventory.skyBlockId(stack)

    /** The Hypixel per-item `uuid` from the stack's custom data, or an empty string. */
    @JvmStatic
    fun itemUuid(stack: ItemStack): String =
        if (stack.isEmpty) "" else stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getStringOr("uuid", "") ?: ""

    /** The item's display name as the server sent it; Any Rename overrides never leak into name-based logic. */
    @JvmStatic
    fun realName(stack: ItemStack): String = geiler.addons.client.rename.RenameApplier.original(stack).hoverName.string

    /** Lore lines as plain text (formatting codes already dropped by the component string). */
    @JvmStatic
    fun loreLines(stack: ItemStack): List<String> =
        if (stack.isEmpty) emptyList() else stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()

    /** The lore line naming a right-click ability, e.g. `Ability: Instant Transmission  RIGHT CLICK`, or "". */
    @JvmStatic
    fun rightClickAbility(stack: ItemStack): String = rightClickAbility(loreLines(stack))

    @JvmStatic
    fun rightClickAbility(lore: List<String>): String =
        lore.firstOrNull { it.contains("Ability: ") && it.endsWith("RIGHT CLICK") } ?: ""

    /** Whether the Hypixel Mod API has named an island, i.e. the player is on SkyBlock. */
    @JvmStatic
    fun inSkyblock(): Boolean = HypixelModApi.currentIsland() != Island.NONE
}
