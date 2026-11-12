package geiler.addons.client.mixin;

import geiler.addons.client.scoreboard.ScoreboardBackend;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses the vanilla display pass only while the opt-in replacement has a live sidebar. */
@Mixin(Hud.class)
abstract class ScoreboardSidebarMixin {
	@Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideVanillaSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker,
		CallbackInfo callback) {
		if (ScoreboardBackend.shouldReplaceVanillaSidebar()) callback.cancel();
	}
}
