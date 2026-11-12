package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonDoorTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonKeyAlertRules
import geiler.addons.client.dungeon.DungeonKeyRules
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.UUID

/** Highlights dropped Wither/Blood Keys and alerts on confirmed pickup messages or recent drops. */
class DungeonKeyPickupModule private constructor(private val settings: Settings) : Module(
    "Key Pickup",
    "Highlights dropped dungeon keys and alerts on confirmed pickups.",
    Category.F7,
    false,
    settings.highlightDrops,
    settings.keyTracer,
    settings.tracerWidth,
    settings.alertPickups,
    settings.alertLocalPickups,
    settings.alertPartyPickups,
    settings.alertUnattributedPickups,
    settings.alertSystemPickupMessages,
    settings.alertNewDrops,
    settings.range,
    settings.throughWalls,
    settings.alertTitle,
    settings.playSound,
    settings.sound,
    settings.volume,
    settings.pitch,
    settings.witherColor,
    settings.bloodColor,
) {
    @Volatile
    private var targets: List<KeyTarget> = emptyList()

    private val recentPickups = DungeonKeyAlertRules.PickupDeduplicator()
    private val spawnAnnouncements = DungeonKeyAlertRules.SpawnAnnouncementTracker()
    private val pickupArbitrator = DungeonKeyAlertRules.KeyPickupArbitrator()
    private val trackedKeyStands = LinkedHashMap<UUID, KeyTarget>()

    private var lastLevel: ClientLevel? = null
    private var lastFloor: DungeonFloor? = null
    private var lastDungeonContext = false
    private var contextInitialized = false
    private var wasActive = false

    private val diagnostics = ModuleDiagnostics(
        Category.F7, { name() }, MAX_DIAGNOSTIC_EVENTS_PER_TICK, DIAGNOSTIC_HEARTBEAT_TICKS, 1024,
    )
    private var trackedStandEvictions = 0
    private var metadataNonmatches = 0L
    private var metadataMatches = 0L
    private var spawnAlertCount = 0L
    private var packetPickupAccepted = 0L
    private var nearbyExactStands = 0
    private var nearbyPickupItems = 0
    private var lastTickDurationNanos = 0L
    private var lastRenderDurationNanos = 0L
    private var lastRenderOutcome = "not-rendered"

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    /** Preserve the previous saved setting and enabled-state key across the shorter display name. */
    override fun configName(): String = "Dungeon Key Pickup"

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    override fun onEnable() {
        diagnostics.enable("range=${settings.range.intValue()}")
        wasActive = false
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { initializeActiveContext(client) }
            return
        }
        initializeActiveContext(client)
    }

    private fun initializeActiveContext(client: Minecraft) {
        if (!isEnabled()) return
        refreshContext(client)
        if (!isActive() || client.level == null || client.player == null) return
        seedExistingTargets(client)
        wasActive = true
        publishRenderTargets()
    }

    override fun onDisable() {
        diagnostics.disable()
        clearRuntimeState()
        wasActive = false
    }

    fun tick() {
        val client = Minecraft.getInstance()
        if (isEnabled()) diagnostics.advance()
        val tickStarted = System.nanoTime()
        val contextChanged = refreshContext(client)
        val level = client.level
        val player = client.player
        val active = isActive() && level != null && player != null
        if (!active) {
            if (wasActive) clearRuntimeState()
            wasActive = false
            pickupArbitrator.clear()
            targets = emptyList()
            nearbyExactStands = 0
            nearbyPickupItems = 0
            lastRenderOutcome = "inactive"
            lastTickDurationNanos = System.nanoTime() - tickStarted
            reportDiagnostics(client)
            return
        }
        val activeLevel = level ?: return
        val activePlayer = player ?: return
        if (!wasActive || contextChanged) seedExistingTargets(client)
        wasActive = true
        val nowNanos = System.nanoTime()
        nearbyExactStands = 0
        nearbyPickupItems = 0
        for (entity in ClientEntitySnapshot.nearby(activeLevel, activePlayer, settings.range.intValue().toDouble())) {
            if (entity.isRemoved || !entity.isAlive) continue
            if (entity is ItemEntity) {
                val type = pickupItemType(entity)
                if (type != null) {
                    nearbyPickupItems++
                    pickupArbitrator.observeKeyEntity(entity.id, keyKind(type), nowNanos)
                }
            } else if (entity is ArmorStand) {
                val type = keyStandType(entity)
                if (type != null) {
                    nearbyExactStands++
                    trackKeyStand(entity, type, metadataDetection = false)
                }
            }
        }
        pruneTrackedKeyStands(activeLevel)
        targets = if (DungeonKeyAlertRules.shouldCollectRenderTargets(
                settings.highlightDrops.value(), settings.keyTracer.value(),
            )
        ) java.util.List.copyOf(trackedKeyStands.values) else emptyList()

        if (settings.alertPickups.value() && settings.alertLocalPickups.value()) {
            for (pickup in pickupArbitrator.takeReadyPacketPickups(nowNanos)) {
                alert(client, "You picked up ${keyLabel(pickup.kind!!)}", "key.pickup")
                logEvent(client, "PICKUP_FALLBACK kind=${pickup.kind} outcome=alerted")
            }
        }
        lastTickDurationNanos = System.nanoTime() - tickStarted
        reportDiagnostics(client)
    }

    /** Called after vanilla applies ClientboundSetEntityDataPacket metadata on the client thread. */
    fun onEntityMetadataUpdated(entityId: Int) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onEntityMetadataUpdated(entityId) }
            return
        }
        val level = client.level ?: return
        if (!isActive() || !wasActive) return
        val stand = level.getEntity(entityId) as? ArmorStand
        if (stand == null) {
            metadataNonmatches++
            return
        }
        val type = keyStandType(stand)
        if (type == null) {
            metadataNonmatches++
            trackedKeyStands.remove(stand.uuid)
            publishRenderTargets()
            return
        }
        metadataMatches++
        val firstMetadataDetection = spawnAnnouncements.shouldAnnounce(stand.uuid)
        trackKeyStand(stand, type, metadataDetection = true)
        publishRenderTargets()
        if (!firstMetadataDetection) {
            logEvent(client, "KEY_METADATA kind=$type outcome=duplicate")
            return
        }
        val alert = settings.alertPickups.value() && settings.alertNewDrops.value()
        logEvent(client, "KEY_METADATA kind=$type outcome=spawn-detected alert=$alert")
        if (alert) {
            spawnAlertCount++
            alert(client, "${keyLabel(type)} dropped nearby", "key.drop")
        }
    }

    /** Call with the incoming chat line; named actors require verification against the current party roster. */
    fun onChatMessage(line: String?) {
        if (!isActive() || !settings.alertPickups.value() || line == null) return
        val client = Minecraft.getInstance()
        val pickup = DungeonKeyAlertRules.parsePickup(line) ?: return
        val localName = client.player?.name?.string
        val resolved = DungeonKeyAlertRules.resolveAlert(pickup, localName, currentPartyNames()) ?: return
        if (resolved.exactSystemLine) {
            if (!settings.alertSystemPickupMessages.value()) return
        } else if (resolved.actorKind == DungeonKeyAlertRules.ActorKind.SELF) {
            if (!settings.alertLocalPickups.value()) return
        } else if (resolved.actorKind == DungeonKeyAlertRules.ActorKind.PARTY) {
            if (!settings.alertPartyPickups.value()) return
        } else if (!settings.alertUnattributedPickups.value()) {
            return
        }
        val nowNanos = System.nanoTime()
        val arbitration = pickupArbitrator.observeChatPickup(
            pickup.keyKind, pickup.actor, localName, nowNanos,
        )
        if (arbitration.suppressDuplicateChat || recentPickups.shouldSuppress(line, nowNanos)) {
            logEvent(
                client,
                "PICKUP_CHAT kind=${pickup.keyKind} attribution=${resolved.actorKind} outcome=duplicate-suppressed",
            )
            return
        }
        logEvent(client, "PICKUP_CHAT kind=${pickup.keyKind} attribution=${resolved.actorKind} outcome=alerted")
        alert(client, resolved.text!!, "key.pickup")
    }

    /** Confirms only a real, freshly observed key ItemEntity picked up by the local player. */
    fun onItemPickupPacket(packet: ClientboundTakeItemEntityPacket?) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) return
        if (refreshContext(client)) return
        val level = client.level ?: return
        val player = client.player ?: return
        if (!isActive() || !settings.alertPickups.value() || !settings.alertLocalPickups.value() ||
            packet == null || packet.getPlayerId() != player.id
        ) return
        val itemEntityId = packet.getItemId()
        val picked = level.getEntity(itemEntityId)
        if (picked != null) {
            val item = picked as? ItemEntity ?: return
            val type = pickupItemType(item) ?: return
            pickupArbitrator.observeKeyEntity(itemEntityId, keyKind(type), System.nanoTime())
        }
        val accepted = pickupArbitrator.beginPickupPacket(itemEntityId, true, System.nanoTime())
        if (accepted) {
            packetPickupAccepted++
            logEvent(client, "PICKUP_PACKET outcome=queued-for-chat-arbitration")
        }
    }

    private fun refreshContext(client: Minecraft): Boolean {
        val level = client.level
        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = DungeonContextTracker.currentFloor()
        val changed = !contextInitialized || level !== lastLevel || inDungeon != lastDungeonContext || floor != lastFloor
        if (!changed) return false
        clearRuntimeState()
        lastLevel = level
        lastDungeonContext = inDungeon
        lastFloor = floor
        contextInitialized = true
        wasActive = false
        return true
    }

    private fun clearRuntimeState() {
        recentPickups.clear()
        spawnAnnouncements.clear()
        pickupArbitrator.clear()
        trackedKeyStands.clear()
        targets = emptyList()
        nearbyExactStands = 0
        nearbyPickupItems = 0
    }

    private fun seedExistingTargets(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        for (entity in ClientEntitySnapshot.nearby(level, player, settings.range.intValue().toDouble())) {
            if (entity !is ArmorStand || entity.isRemoved || !entity.isAlive) continue
            val type = keyStandType(entity) ?: continue
            trackKeyStand(entity, type, metadataDetection = false)
        }
    }

    private fun trackKeyStand(stand: ArmorStand, type: DungeonDoorTracker.Type, metadataDetection: Boolean) {
        val uuid = stand.uuid
        if (!trackedKeyStands.containsKey(uuid) && trackedKeyStands.size >= MAX_TRACKED_KEY_STANDS) {
            val oldest = trackedKeyStands.keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
                trackedStandEvictions++
            }
        }
        trackedKeyStands[uuid] = KeyTarget(stand, type)
        if (!metadataDetection) spawnAnnouncements.rememberExisting(uuid)
    }

    private fun pruneTrackedKeyStands(level: ClientLevel) {
        val iterator = trackedKeyStands.entries.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next().value
            val entity = target.entity
            if (entity.isRemoved || !entity.isAlive || level.getEntity(entity.id) !== entity) iterator.remove()
        }
    }

    private fun currentPartyNames(): List<String> {
        val snapshot = PartyListBackend.snapshot()
        return DungeonKeyAlertRules.trustedPartyNames(
            PartyListBackend.hasFreshSnapshot(),
            snapshot.inParty(),
            snapshot.members().mapNotNull { it.name() },
        )
    }

    fun render(context: LevelRenderContext) {
        val renderStarted = System.nanoTime()
        if (!isActive() || !DungeonKeyAlertRules.shouldCollectRenderTargets(
                settings.highlightDrops.value(), settings.keyTracer.value(),
            )
        ) {
            lastRenderOutcome = "disabled"
            lastRenderDurationNanos = System.nanoTime() - renderStarted
            return
        }
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            lastRenderOutcome = "missing-player-or-level"
            lastRenderDurationNanos = System.nanoTime() - renderStarted
            return
        }
        val camera = client.gameRenderer.mainCamera().position()
        val pose: PoseStack = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val range = settings.range.intValue()
        var drew = false
        var inRange = 0
        var boxesSubmitted = 0
        var tracersSubmitted = 0
        for (target in targets) {
            val entity = target.entity
            if (entity.isRemoved || !entity.isAlive ||
                player.distanceToSqr(entity) > range.toDouble() * range
            ) continue
            inRange++
            val box = boundsFor(entity)
            val color = if (target.type == DungeonDoorTracker.Type.WITHER) settings.witherColor else settings.bloodColor
            if (settings.highlightDrops.value()) {
                EspRenderer.renderBox(
                    pose,
                    buffers,
                    box.minX - camera.x,
                    box.minY - camera.y,
                    box.minZ - camera.z,
                    box.getXsize(),
                    box.getYsize(),
                    box.getZsize(),
                    color.argb(),
                    color.opaqueArgb(),
                    2.0f,
                    !settings.throughWalls.value(),
                )
                drew = true
                boxesSubmitted++
            }
            if (settings.keyTracer.value()) {
                val targetPoint = Vec3(
                    (box.minX + box.maxX) * 0.5,
                    (box.minY + box.maxY) * 0.5,
                    (box.minZ + box.maxZ) * 0.5,
                )
                val endpoints = BlockEspTracerGeometry.endpoints(
                    camera,
                    client.gameRenderer.mainCamera().forwardVector(),
                    targetPoint,
                    0.5,
                )
                EspRenderer.renderLine(
                    pose,
                    buffers,
                    endpoints.start.x,
                    endpoints.start.y,
                    endpoints.start.z,
                    endpoints.end.x,
                    endpoints.end.y,
                    endpoints.end.z,
                    color.argb(),
                    settings.tracerWidth.value(),
                    !settings.throughWalls.value(),
                )
                drew = true
                tracersSubmitted++
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
        lastRenderOutcome = "targets=${targets.size},inRange=$inRange,boxes=$boxesSubmitted,tracers=$tracersSubmitted"
        lastRenderDurationNanos = System.nanoTime() - renderStarted
    }

    private fun publishRenderTargets() {
        targets = if (DungeonKeyAlertRules.shouldCollectRenderTargets(
                settings.highlightDrops.value(), settings.keyTracer.value(),
            )
        ) java.util.List.copyOf(trackedKeyStands.values) else emptyList()
    }

    private fun logEvent(client: Minecraft?, event: String?) {
        if (client == null || event == null) return
        diagnostics.event(event)
    }

    private fun reportDiagnostics(client: Minecraft?) {
        if (!isEnabled()) return
        val level = client?.level
        diagnostics.statusOnChange(DIAGNOSTIC_SAMPLE_TICKS) {
            "enabled=true, active=${isActive()}, inDungeon=${DungeonContextTracker.inDungeon()}" +
            ", levelPresent=${level != null}, trackedStands=${trackedKeyStands.size}" +
            ", renderTargets=${targets.size}, nearbyExactStands=$nearbyExactStands" +
            ", nearbyPickupItems=$nearbyPickupItems, metadataMatches=$metadataMatches" +
            ", metadataNonmatches=$metadataNonmatches, spawnAlerts=$spawnAlertCount" +
            ", trackedStandEvictions=$trackedStandEvictions, packetPickupQueued=$packetPickupAccepted" +
            ", pendingPacketPickups=${pickupArbitrator.pendingPickupCount()}" +
            ", highlight=${settings.highlightDrops.value()}, tracer=${settings.keyTracer.value()}" +
            ", spawnAlertsEnabled=${settings.alertNewDrops.value()}, range=${settings.range.intValue()}" +
            ", lastRender={$lastRenderOutcome}, tickNanos=$lastTickDurationNanos" +
            ", renderNanos=$lastRenderDurationNanos"
        }
    }

    private fun alert(client: Minecraft?, message: String, source: String) {
        if (client == null) return
        val titleEnabled = settings.alertTitle.value()
        if (titleEnabled) TitleAlertStack.submit(source, message, 60, 0xFFFFFFFF.toInt())
        logEvent(
            client,
            "TITLE source=$source result=${if (titleEnabled) "submitted" else "disabled"}" +
                " placement=fixed-latest-wins",
        )
        if (settings.playSound.value()) playSound()
    }

    private fun playSound() {
        ModuleSounds.play(ChatStringSemantics.trim(settings.sound.value()), settings.volume.value(), settings.pitch.value())
    }

    private fun keyStandType(entity: Entity): DungeonDoorTracker.Type? {
        val stand = entity as? ArmorStand ?: return null
        return when (DungeonKeyAlertRules.matchArmorStandName(true, stand.name.string)) {
            DungeonKeyAlertRules.KeyKind.WITHER -> DungeonDoorTracker.Type.WITHER
            DungeonKeyAlertRules.KeyKind.BLOOD -> DungeonDoorTracker.Type.BLOOD
            null -> null
        }
    }

    private fun pickupItemType(entity: ItemEntity?): DungeonDoorTracker.Type? {
        if (entity == null) return null
        val name = entity.item.hoverName.string
        return when {
            DungeonKeyRules.isMatchingKey(name, DungeonDoorTracker.Type.WITHER) -> DungeonDoorTracker.Type.WITHER
            DungeonKeyRules.isMatchingKey(name, DungeonDoorTracker.Type.BLOOD) -> DungeonDoorTracker.Type.BLOOD
            else -> null
        }
    }

    private fun boundsFor(entity: Entity): AABB = if (entity is ArmorStand) {
        AABB(entity.x - 0.5, entity.y + 0.7, entity.z - 0.5, entity.x + 0.5, entity.y + 1.7, entity.z + 0.5)
    } else {
        entity.boundingBox
    }

    private fun keyLabel(type: DungeonDoorTracker.Type): String =
        if (type == DungeonDoorTracker.Type.WITHER) "Wither Key" else "Blood Key"

    private fun keyLabel(type: DungeonKeyAlertRules.KeyKind): String =
        if (type == DungeonKeyAlertRules.KeyKind.WITHER) "Wither Key" else "Blood Key"

    private fun keyKind(type: DungeonDoorTracker.Type): DungeonKeyAlertRules.KeyKind? = when (type) {
        DungeonDoorTracker.Type.WITHER -> DungeonKeyAlertRules.KeyKind.WITHER
        DungeonDoorTracker.Type.BLOOD -> DungeonKeyAlertRules.KeyKind.BLOOD
        else -> null
    }

    private data class KeyTarget(val entity: ArmorStand, val type: DungeonDoorTracker.Type)

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Key Pickup")
        val highlightDrops = spec.toggle("highlightDrops")
        val keyTracer = spec.toggle("keyTracer")
        val tracerWidth = spec.slider("tracerWidth")
        val alertPickups = spec.toggle("alertPickups")
        val alertLocalPickups = spec.toggle("alertLocalPickups")
        val alertPartyPickups = spec.toggle("alertPartyPickups")
        val alertUnattributedPickups = spec.toggle("alertUnattributedPickups")
        val alertSystemPickupMessages = spec.toggle("alertSystemPickupMessages")
        val alertNewDrops = spec.toggle("alertNewDrops")
        val range = spec.slider("range")
        val throughWalls = spec.toggle("throughWalls")
        val alertTitle = spec.toggle("alertTitle")
        val playSound = spec.toggle("playSound")
        val sound = spec.text("sound")
        val volume = spec.slider("volume")
        val pitch = spec.slider("pitch")
        val witherColor = spec.color("witherColor")
        val bloodColor = spec.color("bloodColor")
    }

    companion object {
        private const val MAX_TRACKED_KEY_STANDS = 256
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 32
        private const val DIAGNOSTIC_SAMPLE_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L

        @JvmField
        val INSTANCE = DungeonKeyPickupModule(Settings())
    }
}
