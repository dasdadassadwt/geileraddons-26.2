package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoLoadingScreenModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Loading Screen: closes the terrain loading screen on its first tick and draws nothing before that. */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void geileraddons$closeImmediately(CallbackInfo ci) {
		if (!NoLoadingScreenModule.INSTANCE.shouldSkip()) return;
		NoLoadingScreenModule.INSTANCE.recordSkip("tick");
		((Screen) (Object) this).onClose();
		ci.cancel();
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
	private void geileraddons$drawNothing(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta,
		CallbackInfo ci) {
		if (NoLoadingScreenModule.INSTANCE.shouldSkip()) ci.cancel();
	}
}
