package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.CustomMainMenuModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Main Menu: every menu panorama (title screen and out-of-world menus) goes through extractPanorama. */
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {
	@Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true, require = 0)
	private void geileraddons$customPanorama(GuiGraphicsExtractor graphics, float partialTick, CallbackInfo ci) {
		if (CustomMainMenuModule.INSTANCE.drawPanorama((Screen) (Object) this, graphics)) ci.cancel();
	}
}
