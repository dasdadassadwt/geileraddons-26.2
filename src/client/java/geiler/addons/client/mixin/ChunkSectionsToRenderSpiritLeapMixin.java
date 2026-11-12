package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts completed vanilla chunk draw batches only while a POV render is active. */
@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderSpiritLeapMixin {
	@Inject(method = "renderGroup", at = @At(value = "INVOKE",
		target = "Lcom/mojang/blaze3d/systems/RenderPass;drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
		shift = At.Shift.AFTER), require = 1)
	private void geiler$countLeapTerrainBatch(ChunkSectionLayerGroup group,
		com.mojang.blaze3d.textures.GpuSampler sampler, CallbackInfo ci) {
		SpiritLeapPovRenderer.INSTANCE.noteVanillaTerrainBatch(group);
	}
}
