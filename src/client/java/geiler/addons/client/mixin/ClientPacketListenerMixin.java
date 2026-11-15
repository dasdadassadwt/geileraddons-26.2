package geiler.addons.client.mixin;

import geiler.addons.client.module.impl.I4HelperModule;
import geiler.addons.client.module.impl.MimicAnnouncerModule;
import geiler.addons.client.module.impl.BlessingDisplayModule;
import geiler.addons.client.module.impl.CroesusModule;
import geiler.addons.client.module.impl.SimonSaysModule;
import geiler.addons.client.module.impl.RagnarockModule;
import geiler.addons.client.module.impl.SafariFloorDropsModule;
import geiler.addons.client.module.impl.SpringBootsModule;
import geiler.addons.client.module.impl.TerracottaTimerModule;
import geiler.addons.client.module.impl.TikiHelperModule;
import geiler.addons.client.module.impl.DungeonMobEspModule;
import geiler.addons.client.module.impl.DungeonOdinAssistModule;
import geiler.addons.client.module.impl.DungeonBloodCampModule;
import geiler.addons.client.module.impl.DungeonKeyPickupModule;
import geiler.addons.client.module.impl.SecretWaypointsModule;
import geiler.addons.client.dungeon.DungeonDoorTracker;
import geiler.addons.client.dungeon.DungeonDoorBlockOverlayController;
import geiler.addons.client.dungeon.DungeonMapRoomDetector;
import geiler.addons.client.dungeon.odin.OdinDungeonPlayerRoster;
import geiler.addons.client.chat.ChatTpsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.PacketProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Shadow private ClientLevel level;
	@Unique private Vec3 geileraddons$tpMazePositionBefore;
	@Unique private BlockPos geileraddons$blockUpdatePositionBefore;
	@Unique private BlockState geileraddons$blockUpdateStateBefore;

	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void geileraddons$recordServerTpsSample(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ChatTpsTracker.record(packet.gameTime(), System.nanoTime());
		DungeonOdinAssistModule.INSTANCE.onServerGameTime(packet.gameTime());
	}

	@Inject(method = "handleLogin", at = @At("TAIL"))
	private void geileraddons$resetServerTpsOnLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
		ChatTpsTracker.reset();
		DungeonOdinAssistModule.INSTANCE.onPuzzleLevelLoad();
		DungeonOdinAssistModule.INSTANCE.onBloodWorldChanged();
		DungeonBloodCampModule.INSTANCE.onWorldChanged("login");
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void geileraddons$resetServerTpsOnRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
		ChatTpsTracker.reset();
		DungeonOdinAssistModule.INSTANCE.onPuzzleLevelLoad();
		DungeonOdinAssistModule.INSTANCE.onBloodWorldChanged();
		DungeonBloodCampModule.INSTANCE.onWorldChanged("respawn");
	}

	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void geileraddons$captureTpMazePositionBefore(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		geileraddons$tpMazePositionBefore = DungeonOdinAssistModule.INSTANCE.captureTpMazeCorrectionStart();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void geileraddons$observeTpMazePositionCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Vec3 before = geileraddons$tpMazePositionBefore;
		geileraddons$tpMazePositionBefore = null;
		if (before != null && packet != null && packet.change() != null)
			DungeonOdinAssistModule.INSTANCE.onTpMazePositionCorrection(before, packet.change().position(),
				packet.change().yRot(), packet.change().xRot());
	}

	@Inject(method = "handlePlayerInfoUpdate", at = @At("TAIL"))
	private void geileraddons$trackDungeonMinibossProfiles(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
		DungeonMobEspModule.INSTANCE.onPlayerInfoUpdate(packet);
		OdinDungeonPlayerRoster.onPlayerInfoUpdate(packet);
		CroesusModule.INSTANCE.onPlayerInfoUpdate(packet);
	}

	@Inject(method = "handlePlayerInfoRemove", at = @At("TAIL"))
	private void geileraddons$removeDungeonMapRosterPlayers(ClientboundPlayerInfoRemovePacket packet, CallbackInfo ci) {
		OdinDungeonPlayerRoster.onPlayerInfoRemove(packet);
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void geileraddons$clearDungeonMapRosterOnDisconnect(CallbackInfo ci) {
		OdinDungeonPlayerRoster.onDisconnect();
		ChatTpsTracker.reset();
		DungeonBloodCampModule.INSTANCE.onWorldChanged("disconnect");
	}

	/** Drop client-only door projections before the connection begins disconnect teardown. */
	@Inject(method = "close", at = @At("HEAD"))
	private void geileraddons$restoreDoorOverlayBeforeDisconnect(CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		ClientLevel listenerLevel = level;
		if (client.isSameThread() && listenerLevel != null && client.level == listenerLevel)
			DungeonDoorBlockOverlayController.restoreAndClear(listenerLevel);
	}

	@Inject(method = "handleMapItemData", at = @At("TAIL"))
	private void geileraddons$captureDungeonMapDecorations(ClientboundMapItemDataPacket packet, CallbackInfo ci) {
		DungeonMapRoomDetector.onMapItemData(packet);
	}

	@Inject(method = "handleAddEntity", at = @At("TAIL"))
	private void geileraddons$trackDungeonMinibossSpawn(ClientboundAddEntityPacket packet, CallbackInfo ci) {
		DungeonMobEspModule.INSTANCE.onEntitySpawn(packet);
		geiler.addons.client.module.impl.WitherDragonsModule.INSTANCE.onEntityAdded(packet);
		geiler.addons.client.module.impl.DragonStackAimerModule.INSTANCE.onEntityAdded(packet);
	}

	@Inject(method = "handleRemoveEntities", at = @At("TAIL"))
	private void geileraddons$clearShadowAssassinDespawn(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
		DungeonMobEspModule.INSTANCE.onEntitiesRemoved(packet);
		for (int entityId : packet.getEntityIds())
			DungeonBloodCampModule.INSTANCE.onEntityRemoved(entityId);
	}

	/** Resolve Watcher and blood-mob head textures after vanilla has applied their equipment. */
	@Inject(method = "handleSetEquipment", at = @At("TAIL"))
	private void geileraddons$observeBloodCampHeadEquipment(ClientboundSetEquipmentPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread()) geiler.addons.client.module.impl.WitherDragonsModule.INSTANCE.onEquipment(packet);
		if (!client.isSameThread() || client.level == null) return;
		Entity entity = client.level.getEntity(packet.getEntity());
		if (entity == null) return;
		for (var equipment : packet.getSlots()) {
			if (equipment.getFirst() != EquipmentSlot.HEAD) continue;
			ItemStack head = entity instanceof LivingEntity living ? living.getItemBySlot(EquipmentSlot.HEAD) : null;
			DungeonBloodCampModule.INSTANCE.onHeadEquipment(entity, equipment.getFirst(), head);
		}
	}

	/** Record the post-vanilla absolute position so the tracker derives movement from client state. */
	@Inject(method = "handleMoveEntity", at = @At("TAIL"))
	private void geileraddons$observeBloodCampEntityMove(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread() || client.level == null) return;
		Entity entity = packet.getEntity(client.level);
		if (entity != null) DungeonBloodCampModule.INSTANCE.onEntityMove(entity);
	}

	/** Secret-item collection is confirmed before vanilla removes the picked entity from the client level. */
	@Inject(method = "handleTakeItemEntity", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER))
	private void geileraddons$confirmSecretItemPickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		SecretWaypointsModule.INSTANCE.onSecretPickupPacket(packet);
		DungeonKeyPickupModule.INSTANCE.onItemPickupPacket(packet);
	}

	/** Detect exact Odin-style key ArmorStand names after vanilla applies the metadata update. */
	@Inject(method = "handleSetEntityData", at = @At("TAIL"))
	private void geileraddons$detectDungeonKeyStand(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && packet != null)
			DungeonKeyPickupModule.INSTANCE.onEntityMetadataUpdated(packet.id());
		if (client.isSameThread() && packet != null)
			geiler.addons.client.module.impl.WitherDragonsModule.INSTANCE.onEntityData(packet);
		if (client.isSameThread() && packet != null && client.level != null
			&& client.level.getEntity(packet.id()) instanceof net.minecraft.world.entity.decoration.ArmorStand stand)
			geiler.addons.client.module.impl.CompactDamageModule.INSTANCE.onArmorStand(stand);
	}

	/** Track Devonian-style starred ArmorStand labels after Vanilla applies their metadata. */
	@Inject(method = "handleSetEntityData", at = @At("TAIL"))
	private void geileraddons$trackDungeonStarredMobNameplate(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && packet != null)
			DungeonMobEspModule.INSTANCE.onEntityMetadataUpdated(packet.id());
	}

	/** Puzzle completion events are consumed after vanilla's packet-thread handoff, before mutation. */
	@Inject(method = "handleBlockEvent", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER))
	private void geileraddons$observePuzzleBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
		SecretWaypointsModule.INSTANCE.onSecretBlockEvent(packet);
		DungeonOdinAssistModule.INSTANCE.onPuzzleBlockEvent(packet);
	}

	@Inject(method = "handleBlockUpdate", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER))
	private void geileraddons$capturePuzzleBlockBefore(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		geileraddons$blockUpdatePositionBefore = packet.getPos().immutable();
		geileraddons$blockUpdateStateBefore = client.level != null && client.level.hasChunkAt(packet.getPos())
			? client.level.getBlockState(packet.getPos()) : null;
	}

	@Inject(method = "handleTabListCustomisation", at = @At("TAIL"))
	private void geileraddons$onTabFooter(ClientboundTabListPacket packet, CallbackInfo ci) {
		BlessingDisplayModule.INSTANCE.onTabFooter(packet.footer().getString());
	}

	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void geileraddons$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && client.level != null)
			DungeonDoorBlockOverlayController.onServerBlockState(client.level, packet.getPos(), packet.getBlockState());
		geiler.addons.client.GeilerAddonsClient.onLividMarkerBlockUpdate(packet.getPos(), packet.getBlockState());
		I4HelperModule.INSTANCE.onBlockChange(packet.getPos(), packet.getBlockState());
		TikiHelperModule.INSTANCE.onBlockChange(packet.getPos(), packet.getBlockState());
		DungeonDoorTracker.onBlockChange(packet.getPos());
		geiler.addons.client.module.impl.WitherDragonsModule.INSTANCE.onBlockUpdate(packet.getPos().getX(),
			packet.getPos().getY(), packet.getPos().getZ(), packet.getBlockState().isAir());
		TerracottaTimerModule.INSTANCE.onBlockChange(packet.getPos(), packet.getBlockState());
		SecretWaypointsModule.INSTANCE.onSecretBlockUpdate(geileraddons$blockUpdatePositionBefore,
			geileraddons$blockUpdateStateBefore, packet.getBlockState());
		SimonSaysModule.INSTANCE.onBlockChange(packet.getPos(), geileraddons$blockUpdateStateBefore, packet.getBlockState());
		DungeonOdinAssistModule.INSTANCE.onPuzzleBlockChange(geileraddons$blockUpdatePositionBefore,
			geileraddons$blockUpdateStateBefore, packet.getPos(), packet.getBlockState());
		geileraddons$blockUpdatePositionBefore = null;
		geileraddons$blockUpdateStateBefore = null;
	}

	/** Wraps Vanilla's one update traversal instead of replaying the packet after Vanilla returns. */
	@Redirect(method = "handleChunkBlocksUpdate", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;runUpdates(Ljava/util/function/BiConsumer;)V"))
	private void geileraddons$observeChunkUpdates(ClientboundSectionBlocksUpdatePacket packet,
		BiConsumer<BlockPos, BlockState> vanilla) {
		Minecraft client = Minecraft.getInstance();
		List<BlockChange> changes = new ArrayList<>();
		packet.runUpdates((pos, state) -> {
			BlockState oldState = client.level != null && client.level.hasChunkAt(pos)
				? client.level.getBlockState(pos) : null;
			vanilla.accept(pos, state);
			if (client.isSameThread() && client.level != null)
				DungeonDoorBlockOverlayController.onServerBlockState(client.level, pos, state);
			// runUpdates reuses and mutates one MutableBlockPos across its callbacks.
			changes.add(new BlockChange(pos.immutable(), oldState, state));
		});
		for (BlockChange change : changes) {
			geiler.addons.client.GeilerAddonsClient.onLividMarkerBlockUpdate(change.position(), change.state());
			I4HelperModule.INSTANCE.onBlockChange(change.position(), change.state());
			TikiHelperModule.INSTANCE.onBlockChange(change.position(), change.state());
			DungeonDoorTracker.onBlockChange(change.position());
			SecretWaypointsModule.INSTANCE.onSecretBlockUpdate(change.position(), change.oldState(), change.state());
			TerracottaTimerModule.INSTANCE.onBlockChange(change.position(), change.state());
			DungeonOdinAssistModule.INSTANCE.onPuzzleBlockChange(change.position(), change.oldState(),
				change.position(), change.state());
		}
	}

	/** Rebase and reapply overlays only after Vanilla has installed the incoming chunk. */
	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void geileraddons$seedDoorOverlayFromInstalledChunk(ClientboundLevelChunkWithLightPacket packet,
		CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (client.isSameThread() && client.level != null)
			DungeonDoorBlockOverlayController.onChunkInstalled(client.level, packet.getX(), packet.getZ());
	}

	/** Forget the evicted chunk's source cache and mask; never restore into dropped storage. */
	@Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
	private void geileraddons$forgetDoorOverlayChunk(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		Minecraft client = Minecraft.getInstance();
		if (!client.isSameThread() || client.level == null) return;
		var pos = packet.pos();
		DungeonDoorBlockOverlayController.onChunkUnloaded(client.level, pos.x(), pos.z());
	}

	/** Round-trip samples for the dragon stack aimer; the response echoes the nanosecond time we sent. */
	@Inject(method = "handlePongResponse", at = @At("HEAD"))
	private void geileraddons$onPongResponse(net.minecraft.network.protocol.ping.ClientboundPongResponsePacket packet, CallbackInfo ci) {
		geiler.addons.client.module.impl.DragonStackAimerModule.INSTANCE.onPong(packet.time());
	}

	@Inject(method = "handleParticleEvent", at = @At("TAIL"))
	private void geileraddons$onParticleEvent(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		SafariFloorDropsModule.INSTANCE.onParticle(packet);
		geiler.addons.client.module.impl.WitherDragonsModule.INSTANCE.onParticle(packet);
		geiler.addons.client.module.impl.DragonStackAimerModule.INSTANCE.onParticle(packet);
	}

	/** Terminal Solver replaces the terminal-complete pling while a terminal is open. */
	@Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
	private void geileraddons$cancelTerminalPling(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (geiler.addons.client.module.impl.TerminalSolverModule.INSTANCE.shouldCancelPling(packet)) ci.cancel();
	}

	/** Hide F7 Boss Sounds: drops (or caps) the loud boss-room sounds before they are played. */
	@Inject(method = "handleSoundEvent", at = @At("HEAD"), cancellable = true)
	private void geileraddons$hideF7BossSounds(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (geiler.addons.client.module.impl.HideF7BossSoundsModule.INSTANCE.onSound(packet)) ci.cancel();
	}

	@Inject(method = "handleSoundEvent", at = @At("TAIL"))
	private void geileraddons$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
		TikiHelperModule.INSTANCE.onSound(packet.getSound(), packet.getX(), packet.getY(), packet.getZ(),
			packet.getVolume(), packet.getPitch());
		String soundId = packet.getSound().unwrapKey().map(key -> key.identifier().toString())
			.orElseGet(() -> packet.getSound().value().location().toString());
		SecretWaypointsModule.INSTANCE.onBatDeathSound(soundId, packet.getX(), packet.getY(), packet.getZ());
		SpringBootsModule.INSTANCE.onSound("minecraft:block.note_block.pling".equals(soundId),
			"minecraft:entity.firework_rocket.launch".equals(soundId), packet.getPitch());
		RagnarockModule.INSTANCE.onSound(packet.getPitch(), packet.getSound().value().location());
	}

	/** An entity status event, after the entity handled it; a baby zombie's death event is the Mimic dying. */
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void geileraddons$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		if (level == null) return;
		MimicAnnouncerModule.INSTANCE.onEntityEvent(packet.getEntity(level), packet.getEventId());
	}

	/** Replaces only server title packets, leaving local alert titles and subtitles untouched. */
	@Redirect(method = "setTitleText", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/gui/Hud;setTitle(Lnet/minecraft/network/chat/Component;)V"))
	private void geileraddons$rewriteDungeonTerminalTitle(Hud hud, Component title) {
		Minecraft client = Minecraft.getInstance();
		String localName = client.player == null ? null : client.player.getName().getString();
		String replacement = DungeonOdinAssistModule.INSTANCE.onTerminalTitle(title.getString(), localName);
		hud.setTitle(replacement == null ? title : Component.literal(replacement));
	}

	@Inject(method = "setSubtitleText", at = @At("HEAD"), cancellable = true)
	private void geileraddons$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		if (I4HelperModule.INSTANCE.onSubtitle(packet.text().getString())) {
			ci.cancel();
		}
	}

	/** Score floor and clear percentage are carried by the updated team prefix/suffix. */
	@Inject(method = "handleSetPlayerTeamPacket", at = @At("TAIL"))
	private void geileraddons$observeDungeonTeamUpdate(ClientboundSetPlayerTeamPacket packet, CallbackInfo ci) {
		if (packet == null) return;
		packet.getParameters().ifPresent(parameters -> DungeonOdinAssistModule.INSTANCE.onDungeonTeamUpdate(
			parameters.playerPrefix().getString() + " " + parameters.playerSuffix().getString()));
	}

	private record BlockChange(BlockPos position, BlockState oldState, BlockState state) {
	}
}
