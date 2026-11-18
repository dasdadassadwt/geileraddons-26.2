package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.access.AbstractContainerScreenAccess;
import geiler.addons.client.module.impl.DungeonOdinAssistModule;
import geiler.addons.client.module.impl.LoadoutKeybindsModule;
import geiler.addons.client.module.impl.CommandKeybindsModule;
import geiler.addons.client.module.impl.InventoryCaptureModule;
import geiler.addons.client.module.impl.PetKeybindsModule;
import geiler.addons.client.module.impl.WardrobeKeybindsModule;
import geiler.addons.client.module.impl.SpiritLeapSurfaceOwner;
import geiler.addons.client.inventory.PlayerInventoryControls;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (PlayerInventoryControls.INSTANCE.handleKeyEvent(client, action, event)) {
			ci.cancel();
			return;
		}
		if (ModuleKeybindManager.handleKeyEvent(client, action, event)) {
			ci.cancel();
			return;
		}
		if (action == InputConstants.PRESS && client.gui.screen() == null && client.player != null
			&& CommandKeybindsModule.INSTANCE.onKey(InputConstants.getKey(event).getName())) {
			ci.cancel();
			return;
		}
		if (action == InputConstants.PRESS && client.gui.screen() instanceof AbstractContainerScreen<?> menu) {
			String keyName = InputConstants.getKey(event).getName();
			if (WardrobeKeybindsModule.INSTANCE.onKey(menu, keyName) || LoadoutKeybindsModule.INSTANCE.onKey(menu, keyName)
				|| PetKeybindsModule.INSTANCE.onKey(menu, keyName)
				|| InventoryCaptureModule.INSTANCE.onKey(menu, keyName)) {
				ci.cancel();
				return;
			}
		}
		if (action == InputConstants.PRESS && client.gui.screen() instanceof AbstractContainerScreen<?> screen
			&& DungeonOdinAssistModule.INSTANCE.handleSpiritLeapKey(spiritLeapKeyName(event),
				(slot, slotId, button, input) -> SpiritLeapSurfaceOwner.runDispatchedClick(() ->
					((AbstractContainerScreenAccess) (Object) screen)
						.geileraddons$invokeSlotClicked(slot, slotId, button, input)))) {
			ci.cancel();
		}
	}

	private static String spiritLeapKeyName(KeyEvent event) {
		int key = event.key();
		if (key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return Character.toString((char) key);
		if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z)
			return Character.toString(Character.toLowerCase((char) key));
		String name = InputConstants.getKey(event).getName();
		return name.startsWith("key.keyboard.") ? name.substring("key.keyboard.".length()) : name;
	}
}
