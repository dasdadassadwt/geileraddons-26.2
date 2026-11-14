package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import geiler.addons.client.module.impl.ScrollableTooltipModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the Scrollable Tooltip scale and offset around the drawing of an item tooltip. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ScrollableTooltipMixin {
	@Shadow
	private Matrix3x2fStack pose;

	@Unique
	private boolean geileraddons$transformed;

	/** Positions the tooltip against the unscaled cursor, then leaves the transform pushed until the tooltip is done. */
	@WrapOperation(method = "tooltip", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
	private Vector2ic geileraddons$transformTooltip(ClientTooltipPositioner positioner, int screenWidth,
		int screenHeight, int mouseX, int mouseY, int width, int height, Operation<Vector2ic> original) {
		this.geileraddons$transformed = false;
		ScrollableTooltipModule module = ScrollableTooltipModule.INSTANCE;
		if (!module.beginTooltip()) {
			return original.call(positioner, screenWidth, screenHeight, mouseX, mouseY, width, height);
		}
		module.transform(this.pose);
		this.geileraddons$transformed = true;
		if (module.locked()) {
			return original.call(positioner, screenWidth, screenHeight, 0, 0, width, height);
		}
		float scale = module.scale();
		return original.call(positioner, screenWidth, screenHeight, Math.round(mouseX / scale),
			Math.round(mouseY / scale), width, height);
	}

	@Inject(method = "tooltip", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix3x2fStack;popMatrix()Lorg/joml/Matrix3x2fStack;",
		shift = At.Shift.BEFORE))
	private void geileraddons$endTooltipTransform(CallbackInfo ci) {
		if (!this.geileraddons$transformed) return;
		this.geileraddons$transformed = false;
		this.pose.popMatrix();
	}
}
