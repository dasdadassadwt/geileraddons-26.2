package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.MuteEndermanSoundsModule;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every sound the client starts passes here: server packets and client-side ones such as the enderman stare. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMuteMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void geileraddons$mute(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> result) {
		if (MuteEndermanSoundsModule.INSTANCE.onPlay(sound.getIdentifier().toString())) {
			result.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}
