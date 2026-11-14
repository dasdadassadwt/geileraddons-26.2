package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import geiler.addons.client.module.impl.NoRenderModule.Flag;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: armor bar, food bar, effect overlay and nausea overlay. */
@Mixin(Hud.class)
public abstract class HudNoRenderMixin {
	@Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
	private static void geileraddons$noArmorBar(GuiGraphicsExtractor graphics, Player player, int yLineBase,
		int numHealthRows, int healthRowHeight, int xLeft, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.ARMOR_BAR)) ci.cancel();
	}

	@Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noFoodBar(GuiGraphicsExtractor graphics, Player player, int yLineBase, int xRight,
		CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.FOOD_BAR)) ci.cancel();
	}

	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noEffectOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
		CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.EFFECT_DISPLAY)) ci.cancel();
	}

	@Inject(method = "extractConfusionOverlay", at = @At("HEAD"), cancellable = true)
	private void geileraddons$noNauseaOverlay(GuiGraphicsExtractor graphics, float strength, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.active(Flag.NAUSEA)) ci.cancel();
	}
}
