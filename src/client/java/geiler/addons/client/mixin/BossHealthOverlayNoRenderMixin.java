package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: boss bar. */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayNoRenderMixin {
	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noBossBar(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.BOSS_BAR)) ci.cancel();
	}
}
