package geiler.addons.client.utility

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData

/** Client-thread adapter that finds the first matching carried inventory stack, including armor and offhand. */
object CarriedSupplyCounter {
    @JvmStatic
    fun count(minecraft: Minecraft?, supply: CarriedSupplyPlanner.Supply?): Int =
        scan(minecraft, supply).carriedCount

    @JvmStatic
    fun scan(minecraft: Minecraft?, supply: CarriedSupplyPlanner.Supply?): ScanResult {
        val player = minecraft?.player
        if (player == null || supply == null) {
            return ScanResult(false, 0, 0, 0, false, -1, 0)
        }
        val inventory: Inventory = player.inventory
        var nonEmptyStacks = 0
        var customDataStacks = 0
        for (slot in 0 until inventory.containerSize) {
            val stack: ItemStack? = inventory.getItem(slot)
            if (stack == null || stack.isEmpty) continue
            nonEmptyStacks++
            val customData = stack.get(DataComponents.CUSTOM_DATA)
            if (customData != null && !customData.isEmpty) customDataStacks++
            if (!CarriedSupplyPlanner.matches(
                    CarriedSupplyPlanner.StackIdentity(skyBlockId(customData), stack.count),
                    supply,
                )
            ) continue
            return ScanResult(true, slot + 1, nonEmptyStacks, customDataStacks, true, slot, stack.count)
        }
        return ScanResult(true, inventory.containerSize, nonEmptyStacks, customDataStacks, false, -1, 0)
    }

    /** Odin-compatible item identity: read the root custom-data `id`, not its SNBT text. */
    private fun skyBlockId(customData: CustomData?): String =
        customData?.copyTag()?.getStringOr("id", "") ?: ""

    /** Bounded first-match inventory summary used by automatic command diagnostics. */
    @JvmRecord
    data class ScanResult(
        val available: Boolean,
        val slotsScanned: Int,
        val nonEmptyStacks: Int,
        val customDataStacks: Int,
        val firstMatchFound: Boolean,
        val firstMatchingSlot: Int,
        val carriedCount: Int,
    )
}
