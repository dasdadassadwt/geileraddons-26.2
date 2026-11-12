package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records completed Nvidium world and translucent terrain passes during POV rendering. */
@Pseudo
@Mixin(targets = "me.cortex.nvidium.NvidiumWorldRenderer", remap = false)
public abstract class NvidiumWorldRendererSpiritLeapMixin {
	@Inject(method = "renderFrame(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;Lnet/caffeinemc/mods/sodium/client/util/FogParameters;Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDDLcom/mojang/blaze3d/textures/GpuSampler;)V",
		at = @At("TAIL"), remap = false, require = 0)
	private void geiler$recordLeapNvidiumWorldPass(CallbackInfo ci) {
		SpiritLeapPovRenderer.INSTANCE.noteNvidiumTerrainPassCompleted(false);
	}

	@Inject(method = "renderTranslucent(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
		at = @At("TAIL"), remap = false, require = 0)
	private void geiler$recordLeapNvidiumTranslucentPass(CallbackInfo ci) {
		SpiritLeapPovRenderer.INSTANCE.noteNvidiumTerrainPassCompleted(true);
	}
}
