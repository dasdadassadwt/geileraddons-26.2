package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.FullbrightModule;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Overrides the extracted lightmap state so every light level renders at full brightness. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorFullbrightMixin {
	@Shadow
	private boolean needsUpdate;

	/** One refresh after the module is switched, so the lightmap is rebuilt with or without the override. */
	@Inject(method = "extract", at = @At("HEAD"))
	private void geileraddons$refreshOnToggle(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		if (FullbrightModule.INSTANCE.consumeRefresh()) this.needsUpdate = true;
	}

	@Inject(method = "extract", at = @At("RETURN"))
	private void geileraddons$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		if (!state.needsUpdate || !FullbrightModule.INSTANCE.isEnabled()) return;
		state.ambientColor = LightmapRenderStateExtractor.WHITE;
		state.brightness = FullbrightModule.BRIGHTNESS;
		state.nightVisionEffectIntensity = FullbrightModule.NIGHT_VISION;
		state.darknessEffectScale = FullbrightModule.DARKNESS_SCALE;
		state.bossOverlayWorldDarkening = FullbrightModule.BOSS_DARKENING;
		FullbrightModule.INSTANCE.noteApplied();
	}
}
