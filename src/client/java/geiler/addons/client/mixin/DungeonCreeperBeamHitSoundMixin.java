package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.DungeonOdinAssistModule;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Replaces the local vanilla block-hit instance only at confirmed Creeper Beams endpoints. */
@Mixin(MultiPlayerGameMode.class)
public abstract class DungeonCreeperBeamHitSoundMixin {
	@ModifyArg(method = "continueDestroyBlock", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"),
		index = 0)
	private SoundInstance geileraddons$replaceEndpointHitSound(SoundInstance original) {
		return DungeonOdinAssistModule.INSTANCE.replaceCreeperBeamHitSound(original);
	}
}
