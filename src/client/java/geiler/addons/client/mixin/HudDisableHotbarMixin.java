package geiler.addons.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import geiler.addons.client.module.impl.InventoryHudModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inventory HUD's Disable Hotbar: drops the vanilla hotbar and moves the bars and action bar above the HUD panel. */
@Mixin(Hud.class)
abstract class HudDisableHotbarMixin {
	@Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
	private void geileraddons$skipHotbar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (InventoryHudModule.INSTANCE.disableHotbarActive()) ci.cancel();
	}

	@WrapMethod(method = "extractHotbarAndDecorations")
	private void geileraddons$moveBars(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		geileraddons$shifted(graphics, deltaTracker, original);
	}

	@WrapMethod(method = "extractOverlayMessage")
	private void geileraddons$moveActionBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		geileraddons$shifted(graphics, deltaTracker, original);
	}

	private static void geileraddons$shifted(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, Operation<Void> original) {
		int[] offset = InventoryHudModule.INSTANCE.statusBarOffset(graphics.guiWidth(), graphics.guiHeight());
		if (offset == null) {
			original.call(graphics, deltaTracker);
			return;
		}
		graphics.pose().pushMatrix();
		graphics.pose().translate((float) offset[0], (float) offset[1]);
		try {
			original.call(graphics, deltaTracker);
		} finally {
			graphics.pose().popMatrix();
		}
	}
}
