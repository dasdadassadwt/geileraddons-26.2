package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.renderer.GameRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No Render: the nausea wobble, by zeroing the portal intensity and the nausea blend factor read by renderLevel. */
@Mixin(GameRenderer.class)
public abstract class GameRendererNoRenderMixin {
	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/player/LocalPlayer;oPortalEffectIntensity:F", opcode = Opcodes.GETFIELD))
	private float geileraddons$noLastNausea(float original) {
		return NoRenderModule.INSTANCE.active(Flag.NAUSEA) ? 0.0f : original;
	}

	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "FIELD",
		target = "Lnet/minecraft/client/player/LocalPlayer;portalEffectIntensity:F", opcode = Opcodes.GETFIELD))
	private float geileraddons$noNausea(float original) {
		return NoRenderModule.INSTANCE.active(Flag.NAUSEA) ? 0.0f : original;
	}

	@ModifyExpressionValue(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;getEffectBlendFactor(Lnet/minecraft/core/Holder;F)F"))
	private float geileraddons$noNauseaBlend(float original) {
		return NoRenderModule.INSTANCE.active(Flag.NAUSEA) ? 0.0f : original;
	}
}
