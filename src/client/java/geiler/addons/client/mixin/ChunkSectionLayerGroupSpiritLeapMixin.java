package geiler.addons.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the vanilla chunk-layer target lookup inside the active private POV target. */
@Mixin(ChunkSectionLayerGroup.class)
public abstract class ChunkSectionLayerGroupSpiritLeapMixin {
	@Inject(method = "outputTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("RETURN"),
		cancellable = true)
	private void geiler$routeLeapLayerGroupTarget(CallbackInfoReturnable<RenderTarget> cir) {
		ChunkSectionLayerGroup group = (ChunkSectionLayerGroup) (Object) this;
		RenderTarget routed = SpiritLeapPovRenderer.INSTANCE.routeLayerGroupOutput(group, cir.getReturnValue());
		if (routed != cir.getReturnValue()) cir.setReturnValue(routed);
	}
}
