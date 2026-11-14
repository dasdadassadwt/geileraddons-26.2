package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import java.util.Collection;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EffectsInInventory;
import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No Render: the effect list beside the inventory. */
@Mixin(EffectsInInventory.class)
public abstract class EffectsInInventoryNoRenderMixin {
	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noEffectList(GuiGraphicsExtractor graphics, Collection<MobEffectInstance> effects,
		int x0, int yStep, int mouseX, int mouseY, int maxWidth, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.EFFECT_DISPLAY)) ci.cancel();
	}

	@Inject(method = "canSeeEffects", at = @At("HEAD"), cancellable = true)
	private void geileraddons$cannotSeeEffects(CallbackInfoReturnable<Boolean> cir) {
		if (NoRenderModule.INSTANCE.active(Flag.EFFECT_DISPLAY)) cir.setReturnValue(false);
	}
}
