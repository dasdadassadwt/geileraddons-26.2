package geiler.addons.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes both terrain target branches while the exact Sodium build is verified by the mixin plugin. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass", remap = false)
public abstract class SodiumTerrainRenderPassSpiritLeapMixin {
	@Inject(method = "getTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", remap = false,
		require = 0, at = @At("RETURN"), cancellable = true)
	private void geiler$routeLeapTerrainTarget(CallbackInfoReturnable<RenderTarget> cir) {
		SpiritLeapPovRenderer renderer = SpiritLeapPovRenderer.INSTANCE;
		if (!renderer.isPrivateRenderTargetActive()) return;
		boolean translucentBranch = renderer.isSodiumTranslucentBranch(this, Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency());
		RenderTarget target = renderer.sodiumTerrainTarget(translucentBranch);
		renderer.noteSodiumTerrainTargetRoute(translucentBranch, target);
		if (target == null) {
			throw new IllegalStateException(translucentBranch
				? "private-translucent-target-unavailable" : "private-terrain-target-unavailable");
		}
		cir.setReturnValue(target);
	}
}
