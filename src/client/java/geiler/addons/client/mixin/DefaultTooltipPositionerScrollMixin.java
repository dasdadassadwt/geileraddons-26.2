package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.ScrollableTooltipModule;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Scrollable Tooltip "Start At Top": stops vanilla from shifting a tall tooltip up at the bottom screen edge. */
@Mixin(DefaultTooltipPositioner.class)
public abstract class DefaultTooltipPositionerScrollMixin {
	@ModifyVariable(method = "positionTooltip(IILorg/joml/Vector2i;II)V", at = @At("HEAD"), ordinal = 1, argsOnly = true)
	private int geileraddons$keepTooltipTop(int screenHeight) {
		return ScrollableTooltipModule.INSTANCE.startsAtTop()
			? ScrollableTooltipModule.NO_BOTTOM_CLAMP_HEIGHT : screenHeight;
	}
}
