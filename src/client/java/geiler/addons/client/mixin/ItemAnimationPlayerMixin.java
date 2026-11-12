package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ItemAnimationsModule;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Supplies a local swing clock without changing attack input or network behavior. */
@Mixin(LocalPlayer.class)
public abstract class ItemAnimationPlayerMixin {
	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;)V", at = @At("HEAD"))
	private void geileraddons$startConfiguredSwing(InteractionHand hand, CallbackInfo ci) {
		ItemAnimationsModule.INSTANCE.onLocalSwing();
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void geileraddons$tickConfiguredSwing(CallbackInfo ci) {
		ItemAnimationsModule.INSTANCE.tickSwing();
	}
}
