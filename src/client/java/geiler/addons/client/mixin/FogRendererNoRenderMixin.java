package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pushes the enabled fog parts out of sight after vanilla has set the fog up. */
@Mixin(FogRenderer.class)
public abstract class FogRendererNoRenderMixin {
	@Shadow
	protected abstract FogType getFogType(Camera camera);

	@Inject(method = "setupFog", at = @At("RETURN"))
	private void geileraddons$clearFog(Camera camera, int renderDistance,
		net.minecraft.client.DeltaTracker deltaTracker, float darkness,
		net.minecraft.client.multiplayer.ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		FogData data = cir.getReturnValue();
		if (data == null) return;
		NoRenderModule.INSTANCE.applyFog(this.getFogType(camera), data);
	}
}
