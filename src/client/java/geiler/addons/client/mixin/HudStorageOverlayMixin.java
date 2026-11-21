package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.StorageOverlayModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the vanilla hotbar and its decorations while the storage overlay shows its own inventory. */
@Mixin(Hud.class)
abstract class HudStorageOverlayMixin {
	@Inject(method = "extractHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideHotbarUnderStorageOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
		CallbackInfo callback) {
		if (Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen
			&& StorageOverlayModule.INSTANCE.ownsScreen(screen)) callback.cancel();
	}
}
