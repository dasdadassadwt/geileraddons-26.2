package geiler.addons.client.module.impl

import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

/** Pure rules for Armor Transparency, kept apart from the module so the offline checks can run them. */
object ArmorTransparencyRules {
    /** The slot's opacity (0-1): its percent, but 1 (untouched) for a wearer the switches leave out. */
    @JvmStatic
    fun opacity(percent: Int, isSelf: Boolean, ownArmor: Boolean, otherPlayers: Boolean): Float {
        if (isSelf && !ownArmor) return 1.0f
        if (!isSelf && !otherPlayers) return 1.0f
        return percent.coerceIn(0, 100) / 100.0f
    }

    /** Scales the alpha byte of [argb] by [opacity], keeping the rgb. */
    @JvmStatic
    fun fade(argb: Int, opacity: Float): Int {
        val alpha = Math.round(((argb ushr 24) and 0xFF) * opacity.coerceIn(0.0f, 1.0f))
        return (alpha shl 24) or (argb and 0x00FFFFFF)
    }

    /** The slot [stack] is worn in, matched by identity against the wearer's equipment stacks, or null. */
    @JvmStatic
    fun slotOf(stack: ItemStack, head: ItemStack, chest: ItemStack, legs: ItemStack, feet: ItemStack): EquipmentSlot? = when {
        stack === head -> EquipmentSlot.HEAD
        stack === chest -> EquipmentSlot.CHEST
        stack === legs -> EquipmentSlot.LEGS
        stack === feet -> EquipmentSlot.FEET
        else -> null
    }
}
