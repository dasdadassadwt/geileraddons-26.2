package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.gui.PlayerInventorySlotScreenAccess;
import geiler.addons.client.inventory.PlayerInventoryControls;
import geiler.addons.client.module.impl.PlayerInventoryControlsModule;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the configurable slot-lock and inventory-to-hotbar gesture keys. */
@Mixin(KeyboardHandler.class)
public abstract class PlayerInventorySlotBindingMixin {
	@Shadow @Final private Minecraft minecraft;

	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void geileraddons$handleInventorySlotGesture(long window, int action, KeyEvent event,
		CallbackInfo ci) {
		if (!PlayerInventoryControlsModule.INSTANCE.isEnabled()
			|| !(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
		PlayerInventorySlotScreenAccess accessor = (PlayerInventorySlotScreenAccess) screen;
		if (action == InputConstants.PRESS
			&& PlayerInventoryControls.INSTANCE.handleScreenKeyPressed(screen, accessor.geileraddons$hoveredSlot(), event)) {
			ci.cancel();
		} else if (action == InputConstants.RELEASE
			&& PlayerInventoryControls.INSTANCE.handleScreenKeyReleased(screen, accessor.geileraddons$hoveredSlot(), event)) {
			ci.cancel();
		}
	}
}
