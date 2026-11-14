package geiler.addons.client.mixin;

import geiler.addons.client.macros.input.MouseRelease;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the pause menu from opening when the window loses focus while a macro runs with the mouse let go: the point of
 * letting it go is to use another window, and with a screen open the macro stands still.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMacroMixin {
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void geileraddons$keepRunningUnfocused(CallbackInfo info) {
		if (MouseRelease.active()) info.cancel();
	}
}
