package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ScrollableTooltipModule;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds the mouse wheel over a container screen to Scrollable Tooltip without consuming it. */
@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenScrollMixin {
	@Inject(method = "mouseScrolled", at = @At("HEAD"))
	private void geileraddons$scrollTooltip(double mouseX, double mouseY, double scrollX, double scrollY,
		CallbackInfoReturnable<Boolean> cir) {
		ScrollableTooltipModule.INSTANCE.onScroll((AbstractContainerScreen<?>) (Object) this, scrollX, scrollY);
	}
}
