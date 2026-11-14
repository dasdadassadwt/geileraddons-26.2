package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.HideAllParticlesModule;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block-breaking particles, hidden by Hide Particles' Block Breaking switch. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBlockBreakParticlesMixin {
	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideDestroyParticles(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (HideAllParticlesModule.INSTANCE.shouldCancelBlockBreaking()) ci.cancel();
	}

	@Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideBreakingParticles(BlockPos pos, Direction direction, CallbackInfo ci) {
		if (HideAllParticlesModule.INSTANCE.shouldCancelBlockBreaking()) ci.cancel();
	}
}
