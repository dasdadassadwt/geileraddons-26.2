package geiler.addons.client.mixin;

import geiler.addons.client.sim.DeviceSimService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a device simulator runs, clicks go to it and the game never acts on them: no prediction, no packet.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftDeviceSimMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void geileraddons$simUse(CallbackInfo ci) {
		if (DeviceSimService.onUse()) {
			rightClickDelay = DeviceSimService.useDelay();
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void geileraddons$simAttack(CallbackInfoReturnable<Boolean> result) {
		if (DeviceSimService.onAttack()) result.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void geileraddons$simHoldAttack(boolean down, CallbackInfo ci) {
		if (DeviceSimService.blocksMining()) ci.cancel();
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void geileraddons$simPick(CallbackInfo ci) {
		if (DeviceSimService.blocksMining()) ci.cancel();
	}
}
