package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.CustomMainMenuModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Custom Main Menu's Click GUI button style: replaces the body sprite every vanilla button draws. */
@Mixin(AbstractButton.class)
public abstract class AbstractButtonStyleMixin extends AbstractWidget {
	private AbstractButtonStyleMixin(int x, int y, int width, int height, Component message) {
		super(x, y, width, height, message);
	}

	@Inject(method = "extractDefaultSprite", at = @At("HEAD"), cancellable = true, require = 0)
	private void geileraddons$clickGuiButton(GuiGraphicsExtractor graphics, CallbackInfo ci) {
		if (CustomMainMenuModule.INSTANCE.drawButton(graphics, getX(), getY(), getWidth(), getHeight(),
			active, isHoveredOrFocused(), alpha)) {
			ci.cancel();
		}
	}
}
