package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records the visible section count after Sodium completes a chunk-layer pass. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumWorldRendererSpiritLeapMixin {
	@Shadow(remap = false) public abstract int getVisibleChunkCount();

	@Inject(method = "drawChunkLayer", at = @At("TAIL"), remap = false, require = 0)
	private void geiler$recordLeapTerrainSubmission(CallbackInfo ci) {
		SpiritLeapPovRenderer.INSTANCE.noteSodiumLayerCompleted(getVisibleChunkCount());
	}
}
