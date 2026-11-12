package geiler.addons.client.farming

import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Optional

/** Reads Hypixel's decorative player-head marker and identifies known Garden pests. */
class PestDetector private constructor() {
    companion object {
        /**
         * Matches only ArmorStands with a player head in the head slot and a known texture property.
         * Missing profile data is deliberately treated as unavailable rather than as a match.
         */
        @JvmStatic
        fun detect(entity: ArmorStand?): Optional<PestKind> {
            if (entity == null || !entity.hasItemInSlot(EquipmentSlot.HEAD)) return Optional.empty()
            return PestKind.fromTexture(headTexture(entity.getItemBySlot(EquipmentSlot.HEAD)))
        }

        /** Kept static for the existing Java PestChecks fixture and callers. */
        @JvmStatic
        fun headTexture(stack: ItemStack?): String {
            if (stack == null || !stack.`is`(Items.PLAYER_HEAD)) return ""

            val profile = stack.get(DataComponents.PROFILE) ?: return ""
            return profile.partialProfile().properties().get("textures").asSequence()
                .filterNotNull()
                .map { it.value() }
                .filterNotNull()
                .firstOrNull() ?: ""
        }
    }
}
