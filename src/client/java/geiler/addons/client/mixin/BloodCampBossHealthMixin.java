package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.BossBarPercentModule;
import geiler.addons.client.module.impl.DungeonBloodCampModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds Odin-style estimated health to the Watcher boss bar before vanilla extracts it. */
@Mixin(BossHealthOverlay.class)
public abstract class BloodCampBossHealthMixin {
	@Inject(method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
		at = @At("HEAD"))
	private void geileraddons$addBloodCampWatcherHealth(GuiGraphicsExtractor graphics, int x, int y,
		BossEvent event, CallbackInfo ci) {
		DungeonBloodCampModule.INSTANCE.applyWatcherBossBar(event);
		BossBarPercentModule.INSTANCE.apply(event);
	}
}
