package geiler.addons.client.mixin;

import geiler.addons.client.fishing.LavaReplacementRules;
import geiler.addons.client.module.impl.LavaReplacementModule;
import java.util.Map;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lava Replacement: lava asks for its model here and gets the opaque water model while the module is replacing.
 * Runs per fluid block on chunk-build threads, so it reads one volatile first, never calls {@code get()} again
 * (that re-entered this hook and overflowed the stack), and takes the water model straight from the backing map.
 */
@Mixin(FluidStateModelSet.class)
public abstract class FluidStateModelSetMixin {
	@Shadow
	@Final
	private Map<Fluid, FluidModel> modelByFluid;

	@Inject(method = "get", at = @At("RETURN"), cancellable = true)
	private void geileraddons$replaceLava(FluidState state, CallbackInfoReturnable<FluidModel> cir) {
		if (!LavaReplacementModule.isReplacing()) return;
		Fluid type = state.getType();
		boolean lava = type == Fluids.LAVA || type == Fluids.FLOWING_LAVA;
		FluidModel water = lava ? this.modelByFluid.get(Fluids.WATER) : null;
		if (LavaReplacementRules.useWater(lava, true, water != null)) {
			cir.setReturnValue(LavaReplacementModule.opaqueWaterFor(water));
		}
	}
}
