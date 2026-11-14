package geiler.addons.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.chat.ChatNativeCopy;
import geiler.addons.client.module.ModuleKeybindManager;
import geiler.addons.client.module.impl.FreecamModule;
import geiler.addons.client.module.impl.NoCursorResetModule;
import geiler.addons.client.module.impl.ThirdPersonCameraModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Shadow private double xpos;
	@Shadow private double ypos;
	@Unique private double geileraddons$beforeX;
	@Unique private double geileraddons$beforeY;

	/** Remembers where the cursor was in the screen that is closing, before vanilla recenters it. */
	@Inject(method = "grabMouse", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;xpos:D",
		opcode = Opcodes.PUTFIELD, ordinal = 0))
	private void geileraddons$rememberCursor(CallbackInfo ci) {
		this.geileraddons$beforeX = this.xpos;
		this.geileraddons$beforeY = this.ypos;
	}

	/** Vanilla recenters the cursor before releasing it; No Cursor Reset puts the remembered spot back first. */
	@Inject(method = "releaseMouse", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/Minecraft;getWindow()Lcom/mojang/blaze3d/platform/Window;", ordinal = 2))
	private void geileraddons$keepCursor(CallbackInfo ci) {
		if (NoCursorResetModule.INSTANCE.keepCursor(Minecraft.getInstance().gui.screen())) {
			this.xpos = this.geileraddons$beforeX;
			this.ypos = this.geileraddons$beforeY;
		}
	}

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
		if (FreecamModule.INSTANCE.captureTurn(yawDelta, pitchDelta)) return;
		if (!ThirdPersonCameraModule.INSTANCE.captureFreeLookTurn(yawDelta, pitchDelta)) player.turn(yawDelta, pitchDelta);
	}
}
