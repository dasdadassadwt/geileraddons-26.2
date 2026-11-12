package geiler.addons.client.mixin;

import geiler.addons.client.gui.SlotItemOverlays;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws the same item backgrounds and cooldown fills on the hotbar slots. */
@Mixin(Hud.class)
public abstract class HudHotbarSlotMixin {
	@Inject(method = "extractSlot", at = @At("HEAD"))
	private void geileraddons$hotbarBackground(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker tracker,
		Player player, ItemStack stack, int seed, CallbackInfo ci) {
		SlotItemOverlays.beforeHotbarSlot(graphics, player, stack, x, y, seed);
	}

	@Inject(method = "extractSlot", at = @At("TAIL"))
	private void geileraddons$hotbarFill(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker tracker,
		Player player, ItemStack stack, int seed, CallbackInfo ci) {
		SlotItemOverlays.afterItem(graphics, stack, x, y);
	}
}
