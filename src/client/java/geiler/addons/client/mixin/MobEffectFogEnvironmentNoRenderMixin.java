package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No Render: the blindness fog, fog color and darkening. Vanilla picks the first fog environment whose
 * {@code isApplicable} is true, so a blindness environment that never applies leaves the world fully visible.
 * Blindness itself stays on the player, so features that read the effect (Livid helper) keep working.
 */
@Mixin(MobEffectFogEnvironment.class)
public abstract class MobEffectFogEnvironmentNoRenderMixin {
	@Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noBlindness(FogType type, Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (((Object) this) instanceof BlindnessFogEnvironment && NoRenderModule.INSTANCE.active(Flag.BLINDNESS)) {
			cir.setReturnValue(false);
		}
	}
}
