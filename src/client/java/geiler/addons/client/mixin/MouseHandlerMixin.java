package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.chat.ChatNativeCopy;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onMouseButton(long window, MouseButtonInfo button, int action, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (button != null && action == InputConstants.PRESS && button.button() == 1
			&& ChatNativeCopy.copyMessageAt(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
				minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()))) {
			ci.cancel();
			return;
		}
		if (ModuleKeybindManager.handleMouseButtonEvent(Minecraft.getInstance(), action, button)) ci.cancel();
	}

	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void geileraddons$holdFreeLook(LocalPlayer player, double yawDelta, double pitchDelta) {
		if (!ThirdPersonCameraModule.INSTANCE.captureFreeLookTurn(yawDelta, pitchDelta)) player.turn(yawDelta, pitchDelta);
	}
}
