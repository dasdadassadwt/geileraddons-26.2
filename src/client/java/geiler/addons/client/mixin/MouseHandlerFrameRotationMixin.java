package geiler.addons.client.mixin;

import geiler.addons.client.macros.input.MouseRelease;
import geiler.addons.client.macros.rotation.FrameRotations;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Steps macro-driven rotation at the exact point in the frame where real mouse movement is applied.
 *
 * <p>{@code Minecraft.runTick} runs the ticks that are due, then calls this method, then renders - so this is once per
 * frame, immediately before the frame is drawn, and the view the frame draws is whatever was written here. Hooking the
 * tick instead moves the view in twenty steps a second.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerFrameRotationMixin {
	@Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
	private void geileraddons$stepMacroRotation(CallbackInfo info) {
		FrameRotations.onFrame();
	}

	/** The mouse stays free while a macro runs with it let go: a click into the window or a closing screen would take it back. */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void geileraddons$keepMouseFree(CallbackInfo info) {
		if (MouseRelease.active()) info.cancel();
	}
}
