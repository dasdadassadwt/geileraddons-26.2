package geiler.addons.client.mixin;

import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Evicts overlay authority and masks with the chunk, without writing into removed storage. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

	@Inject(method = "unload", at = @At("HEAD"))
	private void geileraddons$forgetDoorOverlayChunk(LevelChunk chunk, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread() || chunk == null) return;
		var pos = chunk.getPos();
		DungeonDoorBlockOverlayController.onChunkUnloaded((ClientLevel) (Object) this, pos.x(), pos.z());
	}
}
