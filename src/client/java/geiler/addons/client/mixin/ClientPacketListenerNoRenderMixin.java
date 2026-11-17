package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.NoRenderModule;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Render: particle packets and the name tags of dead mobs. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerNoRenderMixin {
	@Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER), cancellable = true)
	private void geileraddons$noRenderParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (NoRenderModule.INSTANCE.shouldCancelParticlePacket(packet)) ci.cancel();
	}

	/** Geyser Fishing: records the geyser and drops its particles next to the bobber. */
	@Inject(method = "handleParticleEvent", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER), cancellable = true)
	private void geileraddons$geyserParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (geiler.addons.client.module.impl.GeyserFishingModule.INSTANCE.onParticle(packet)) ci.cancel();
	}

	@SuppressWarnings("unchecked")
	@Inject(method = "handleSetEntityData", at = @At("TAIL"))
	private void geileraddons$hideDeadNameTags(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
		if (!NoRenderModule.INSTANCE.isEnabled()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return;
		Entity entity = client.level.getEntity(packet.id());
		if (!(entity instanceof ArmorStand)) return;
		for (SynchedEntityData.DataValue<?> entry : packet.packedItems()) {
			if (entry.serializer().equals(EntityDataSerializers.OPTIONAL_COMPONENT)) {
				((Optional<Component>) entry.value()).ifPresent(name ->
					NoRenderModule.INSTANCE.onEntityNamed(entity, name.getString()));
				break;
			}
		}
	}
}
