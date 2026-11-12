package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import geiler.addons.client.module.impl.SpiritLeapPovRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Taps the player's own attack/use input. Injected at HEAD so the skull's state is recorded as it
 * was <em>before</em> the click, which is the half of the before/after pair the block-update packet
 * can't provide, and so a floor drop stops being marked the instant it is acted on.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	/** Restore projections before Minecraft replaces the level reference. */
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void geileraddons$restoreDoorOverlayBeforeLevelReplacement(ClientLevel nextLevel, CallbackInfo ci) {
		Minecraft client = (Minecraft) (Object) this;
		ClientLevel previousLevel = client.level;
		if (previousLevel != nextLevel) SpiritLeapPovRenderer.INSTANCE.onWorldChanged(previousLevel, nextLevel);
		if (client.isSameThread() && previousLevel != null && previousLevel != nextLevel)
			geiler.addons.client.dungeon.DungeonDoorBlockOverlayController.restoreAndClear(previousLevel);
	}

	/** Disconnect also clears the connection before clearClientLevel; restore while the old chunk data exists. */
	@Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
	private void geileraddons$restoreDoorOverlayBeforeDisconnect(Screen screen, boolean preserveResourcePacks,
		boolean showTitle, CallbackInfo ci) {
		Minecraft client = (Minecraft) (Object) this;
		if (client.level != null) SpiritLeapPovRenderer.INSTANCE.onWorldChanged(client.level, null);
		if (client.isSameThread() && client.level != null)
			geiler.addons.client.dungeon.DungeonDoorBlockOverlayController.restoreAndClear(client.level);
	}

	/** Safety net for teardown paths that clear the world without using Minecraft.disconnect. */
	@Inject(method = "clearClientLevel", at = @At("HEAD"))
	private void geileraddons$restoreDoorOverlayBeforeLevelTeardown(Screen screen, CallbackInfo ci) {
		Minecraft client = (Minecraft) (Object) this;
		if (client.level != null) SpiritLeapPovRenderer.INSTANCE.onWorldChanged(client.level, null);
		if (client.isSameThread() && client.level != null)
			geiler.addons.client.dungeon.DungeonDoorBlockOverlayController.restoreAndClear(client.level);
	}

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void geileraddons$onStartAttack(CallbackInfoReturnable<Boolean> cir) {
		TikiHelperModule.INSTANCE.onClick(false);
		SafariFloorDropsModule.INSTANCE.onClick();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void geileraddons$onStartUseItem(CallbackInfo ci) {
		TikiHelperModule.INSTANCE.onClick(true);
		SafariFloorDropsModule.INSTANCE.onClick();
	}
}
