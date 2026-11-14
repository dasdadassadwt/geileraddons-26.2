package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DoubleUseFixModule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Double Use Fix: acts on the two use-item calls inside startUseItem. */
@Mixin(Minecraft.class)
public abstract class MinecraftDoubleUseFixMixin {
	@Inject(method = "startUseItem", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"),
		cancellable = true)
	private void geileraddons$rodOnBlock(CallbackInfo ci) {
		if (DoubleUseFixModule.INSTANCE.redirectBlockUse()) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"),
		cancellable = true)
	private void geileraddons$daggerOnBlock(CallbackInfo ci) {
		if (DoubleUseFixModule.INSTANCE.shouldCancelItemUse()) ci.cancel();
	}
}
