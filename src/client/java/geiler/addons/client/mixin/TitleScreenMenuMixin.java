package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import geiler.addons.client.module.impl.CustomMainMenuModule;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Main Menu on the title screen: hides the logo, splash, version line and copyright, adds a settings button. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMenuMixin extends Screen {
	private TitleScreenMenuMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void geileraddons$mainMenuWidgets(CallbackInfo ci) {
		CustomMainMenuModule module = CustomMainMenuModule.INSTANCE;
		if (!module.isEnabled()) return;
		if (!module.showCopyright()) {
			// The copyright line is the title screen's only plain-text button; hiding it also stops its click.
			for (GuiEventListener child : children()) {
				if (child instanceof PlainTextButton button) button.visible = false;
			}
		}
		AbstractButton settings = module.settingsButton(width, height);
		if (settings != null) addRenderableWidget(settings);
	}

	@WrapWithCondition(method = "extractRenderState", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
	private boolean geileraddons$logo(LogoRenderer logo, GuiGraphicsExtractor graphics, int width, float alpha) {
		return CustomMainMenuModule.INSTANCE.showLogo();
	}

	@WrapWithCondition(method = "extractRenderState", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
	private boolean geileraddons$splash(SplashRenderer splash, GuiGraphicsExtractor graphics, int width, Font font, float alpha) {
		// The splash hangs off the logo's corner, so it goes with it.
		return CustomMainMenuModule.INSTANCE.showLogo();
	}

	@WrapWithCondition(method = "extractRenderState", require = 0, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
	private boolean geileraddons$versionText(GuiGraphicsExtractor graphics, Font font, String text, int x, int y, int color) {
		return CustomMainMenuModule.INSTANCE.showVersion();
	}
}
