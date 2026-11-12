package geiler.addons.client.module.impl

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.GeilerAddons
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.DungeonMimicChestDetector
import geiler.addons.client.dungeon.DungeonRoomTracker
import geiler.addons.client.dungeon.odin.OdinDungeonMapCoreScanner
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.entity.Nameplates
import geiler.addons.client.farming.PestAnimation
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartyMember
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID

/** Pure readiness gate for manual capture; inputs are snapshots, not Minecraft client objects. */
object DungeonMobEspCaptureReadiness {
    @JvmStatic
    fun isReady(levelPresent: Boolean, playerPresent: Boolean, inDungeon: Boolean): Boolean =
        levelPresent && playerPresent && inDungeon
}

/** Independent starred-mob and miniboss highlights collected under one dungeon module. */
class DungeonMobEspModule private constructor(
    private val settings: Settings,
) : Module(
    "Dungeon Mob ESP",
    "Highlights selected dungeon mobs, minibosses, bosses, Mimics, and party classes.",
    Category.F7,
    false,
    settings.starredEnabled,
    settings.starredOnlyRoom,
    settings.starredRange,
    settings.depthCheck,
    settings.starredStyle,
    settings.starredOutline,
    settings.starredFill,
    settings.starredLineWidth,
    settings.starredRingCount,
    settings.starredRingHeight,
    settings.starredRingRadius,
    settings.starredRingSpeed,
    settings.starredRingLineWidth,
    settings.minibossEnabled,
    settings.minibossRange,
    settings.minibossOnlyRoom,
    settings.minibossStyle,
    settings.minibossOnlyStarred,
    settings.minibossOutline,
    settings.minibossFill,
    settings.minibossLineWidth,
    settings.minibossTracer,
    settings.minibossTracerColor,
    settings.minibossTracerWidth,
    settings.minibossRingCount,
    settings.minibossRingHeight,
    settings.minibossRingRadius,
    settings.minibossRingSpeed,
    settings.minibossRingLineWidth,
    settings.appearanceCapture,
    settings.manualAppearanceLabel,
    settings.captureCrosshairAppearance,
    settings.felsEnabled,
    settings.felOnlyRoom,
    settings.highlightFels,
    settings.highlightFelSkulls,
    settings.felStyle,
    settings.felOutline,
    settings.felFill,
    settings.felLineWidth,
    settings.felRingCount,
    settings.felRingHeight,
    settings.felRingRadius,
    settings.felRingSpeed,
    settings.felRingLineWidth,
    settings.felSkullTracer,
    settings.felTracerColor,
    settings.felTracerWidth,
    settings.debug,
    settings.specialWithers,
    settings.specialMimic,
    settings.mimicFill,
    settings.mimicOutline,
    settings.partyClasses,
    settings.specialRange,
    settings.specialMobFill,
    settings.specialMobOutline,
    settings.tank,
    settings.healer,
    settings.mage,
    settings.berserk,
    settings.archer,
    settings.partyFill,
) {
    companion object {
        @JvmField
        val INSTANCE = DungeonMobEspModule(Settings())

        private const val STARRED_DEFAULT_LINE_WIDTH = 2.0f
        private const val MINIBOSS_DEFAULT_LINE_WIDTH = 2.0f
    }

    private val debug = settings.debug

    @Volatile private var starredMatches: List<LivingEntity> = emptyList()
    @Volatile private var minibossMatches: List<LivingEntity> = emptyList()
    @Volatile private var shadowAssassinMatches: List<LivingEntity> = emptyList()
    @Volatile private var stationaryFelSkulls: List<LivingEntity> = emptyList()
    @Volatile private var movingFelMatches: List<LivingEntity> = emptyList()
    @Volatile private var specialTargets: List<SpecialTarget> = emptyList()
    @Volatile private var mimicChests: List<BlockPos> = emptyList()
    @Volatile private var rememberedTargets: List<DungeonMobTargetMemory.Target> = emptyList()
    private val knownFelBodies = HashMap<UUID, KnownFelBody>()
    private val targetMemory = DungeonMobTargetMemory()
    private val minibossProfileTracker = DungeonMinibossProfileTracker()
    private val devonianMobTracker = DungeonDevonianMobTracker()
    private val mimicRoomScanner = OdinDungeonMapCoreScanner()
    private val appearanceCapture = MinibossAppearanceCapture()
    private var appearanceCatalog = MinibossAppearanceCatalog.empty()
    private var appearanceCatalogLoaded = false
    private var activeRoomFootprint: DungeonRoomTracker.MapRoomFootprint? = null
    private var lastLevel: ClientLevel? = null
    private var lastRoomKey: String? = null
    private var lastClearedRoomKey: String? = null
    private var packetDungeonWasActive = false
    private var lastDiagnostic = ""
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastTracerDiagnostic = ""
    private var lastTracerDiagnosticTick = Long.MIN_VALUE
    private var lastHudDiagnostic = ""
    private var lastHudDiagnosticTick = Long.MIN_VALUE
    private var lastEntityCensusSummary = "not-sampled"
    private var lastEntityCensusTick = Long.MIN_VALUE
    private var appearanceCaptureFailureReported = false

    init {
        group(
            SettingGroup(null, settings.depthCheck, settings.debug),
            SettingGroup.switched(
                "Fels", settings.felsEnabled, settings.felOnlyRoom,
                settings.highlightFels, settings.highlightFelSkulls, settings.felStyle,
                settings.felOutline, settings.felFill, settings.felLineWidth, settings.felRingCount,
                settings.felRingHeight, settings.felRingRadius, settings.felRingSpeed,
                settings.felRingLineWidth, settings.felSkullTracer, settings.felTracerColor,
                settings.felTracerWidth,
            ),
            SettingGroup.switched(
                "Starred Mobs", settings.starredEnabled, settings.starredOnlyRoom,
                settings.starredRange, settings.starredStyle, settings.starredOutline,
                settings.starredFill, settings.starredLineWidth, settings.starredRingCount,
                settings.starredRingHeight, settings.starredRingRadius, settings.starredRingSpeed,
                settings.starredRingLineWidth,
            ),
            SettingGroup.switched(
                "Minibosses", settings.minibossEnabled, settings.minibossRange,
                settings.minibossOnlyRoom, settings.minibossOnlyStarred, settings.minibossStyle,
                settings.minibossOutline, settings.minibossFill, settings.minibossLineWidth,
                settings.minibossTracer, settings.minibossTracerColor, settings.minibossTracerWidth,
                settings.minibossRingCount, settings.minibossRingHeight, settings.minibossRingRadius,
                settings.minibossRingSpeed, settings.minibossRingLineWidth,
            ),
            SettingGroup.switched(
                "Automatic miniboss appearance capture", settings.appearanceCapture,
                settings.manualAppearanceLabel, settings.captureCrosshairAppearance,
            ),
            SettingGroup.switched(
                "Wither Bosses", settings.specialWithers, settings.specialRange,
                settings.specialMobFill, settings.specialMobOutline,
            ),
            SettingGroup.switched(
                "Mimics", settings.specialMimic, settings.specialRange,
                settings.mimicFill, settings.mimicOutline,
            ),
            SettingGroup.switched(
                "Party Classes", settings.partyClasses, settings.specialRange,
                settings.tank, settings.healer, settings.mage, settings.berserk,
                settings.archer, settings.partyFill,
            ),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === settings.starredRingCount || setting === settings.starredRingHeight ||
            setting === settings.starredRingRadius || setting === settings.starredRingSpeed ||
            setting === settings.starredRingLineWidth
        ) return settings.starredStyle.value() == "Ring"
        if (setting === settings.minibossRingCount || setting === settings.minibossRingHeight ||
            setting === settings.minibossRingRadius || setting === settings.minibossRingSpeed ||
            setting === settings.minibossRingLineWidth
        ) return settings.minibossStyle.value() == "Ring"
        if (setting === settings.felRingCount || setting === settings.felRingHeight ||
            setting === settings.felRingRadius || setting === settings.felRingSpeed ||
            setting === settings.felRingLineWidth
        ) return settings.felStyle.value() == "Ring"
        if (setting === settings.starredLineWidth) return settings.starredStyle.value() != "Ring"
        if (setting === settings.minibossLineWidth) return settings.minibossStyle.value() != "Ring"
        if (setting === settings.felLineWidth) return settings.felStyle.value() != "Ring"
        if (setting === settings.minibossTracerColor || setting === settings.minibossTracerWidth) {
            return settings.minibossTracer.value()
        }
        if (setting === settings.felSkullTracer) return settings.highlightFelSkulls.value()
        if (setting === settings.felTracerColor || setting === settings.felTracerWidth) {
            return settings.highlightFelSkulls.value() && settings.felSkullTracer.value()
        }
        if (setting === settings.manualAppearanceLabel || setting === settings.captureCrosshairAppearance) {
            return settings.appearanceCapture.value()
        }
        return true
    }

    override fun isActive(): Boolean {
        if (!isEnabled() || !DungeonContextTracker.inDungeon()) return false
        val floor = DungeonContextTracker.currentFloor()
        val bossObserved = !DungeonContextTracker.isMobEspPhase()
        return DungeonContextTracker.isMobEspPhase() ||
            DungeonSpecialMobRules.shouldScanF7WitherBoss(
                settings.specialWithers.value(), true,
                floor != null && floor.number() == 7, floor != null && floor.master(), bossObserved,
            ) ||
            settings.partyClasses.value() && DungeonContextTracker.isMobEspPhase() ||
            settings.specialMimic.value() && DungeonContextTracker.isClearPhase()
    }

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) {
            "Waiting for a dungeon entry"
        } else if (isEnabled() && !DungeonContextTracker.isMobEspPhase()) {
            if (DungeonContextTracker.inDungeon()) "ESP is unavailable after boss entry" else "Waiting for a dungeon entry"
        } else {
            null
        }

    fun tick() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        val dungeonPresent = DungeonContextTracker.inDungeon()
        if (packetDungeonWasActive && !dungeonPresent) {
            minibossProfileTracker.clear()
            devonianMobTracker.clear()
        }
        packetDungeonWasActive = dungeonPresent
        if (level == null || !dungeonPresent) {
            starredMatches = emptyList()
            minibossMatches = emptyList()
            shadowAssassinMatches = emptyList()
            stationaryFelSkulls = emptyList()
            movingFelMatches = emptyList()
            specialTargets = emptyList()
            mimicChests = emptyList()
            rememberedTargets = emptyList()
            knownFelBodies.clear()
            targetMemory.clear()
            lastRoomKey = null
            lastClearedRoomKey = null
            activeRoomFootprint = null
            if (level == null) {
                minibossProfileTracker.clear()
                devonianMobTracker.clear()
            }
            if (level == null) mimicRoomScanner.reset()
            lastLevel = level
            reportDebug(
                "inactive; enabled=" + isEnabled() + ", dungeon=" + DungeonContextTracker.inDungeon() +
                    ", mobEspPhase=" + DungeonContextTracker.isMobEspPhase(),
            )
            return
        }
        if (lastLevel !== level) {
            lastLevel = level
            minibossProfileTracker.onWorld(level)
            devonianMobTracker.onWorld(level)
            starredMatches = emptyList()
            minibossMatches = emptyList()
            shadowAssassinMatches = emptyList()
            stationaryFelSkulls = emptyList()
            movingFelMatches = emptyList()
            specialTargets = emptyList()
            mimicChests = emptyList()
            mimicRoomScanner.reset()
            knownFelBodies.clear()
            targetMemory.clear()
            rememberedTargets = emptyList()
            lastRoomKey = null
            lastClearedRoomKey = null
            activeRoomFootprint = null
            lastTracerDiagnostic = ""
            lastTracerDiagnosticTick = Long.MIN_VALUE
            lastHudDiagnostic = ""
            lastHudDiagnosticTick = Long.MIN_VALUE
            lastEntityCensusSummary = "not-sampled"
            lastEntityCensusTick = Long.MIN_VALUE
        }
        if (player == null || !isEnabled()) {
            starredMatches = emptyList()
            minibossMatches = emptyList()
            shadowAssassinMatches = emptyList()
            stationaryFelSkulls = emptyList()
            movingFelMatches = emptyList()
            specialTargets = emptyList()
            mimicChests = emptyList()
            reportDebug("inactive; enabled=" + isEnabled() + ", player=" + (player != null))
            return
        }
        devonianMobTracker.reconcileLoadedNameplates(level)
        updateSpecialTargets(client)
        if (!DungeonContextTracker.isMobEspPhase()) {
            starredMatches = emptyList()
            minibossMatches = emptyList()
            shadowAssassinMatches = emptyList()
            stationaryFelSkulls = emptyList()
            movingFelMatches = emptyList()
            knownFelBodies.clear()
            val gameTick = level.getGameTime()
            rememberedTargets = targetMemory.update(
                emptyList<DungeonMobTargetMemory.Target?>(), gameTick,
                { target -> rememberedPresence(level, target) },
                { target -> refreshRememberedTarget(client, target, gameTick) },
            )
            reportDebug(
                "inactive; enabled=true, dungeon=true, mobEspPhase=false, trackedNameplates=" +
                    devonianMobTracker.trackedNameplates(),
            )
            return
        }
        val gameTick = level.getGameTime()
        activeRoomFootprint = updateRoomFootprint(client)
        val roomKey = currentRoomKey(client)
        if (roomKey == null || roomKey != lastRoomKey) {
            knownFelBodies.clear()
            targetMemory.clearTransientGroups()
            lastRoomKey = roomKey
        }
        val roomCleared = DungeonMapRoomDetector.currentRoomCleared()
        if (roomCleared) {
            if (roomKey != null && roomKey != lastClearedRoomKey) {
                knownFelBodies.clear()
                targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_SKULL)
                targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_MOVING)
                lastClearedRoomKey = roomKey
            }
        } else {
            lastClearedRoomKey = null
        }
        val scanForFelBodies = DungeonMobEspSupport.shouldClassifyFelsForStarFilter(
            settings.starredEnabled.value(), settings.felsEnabled.value(),
        )
        val felSkullMarkers = if (!roomCleared && settings.felsEnabled.value() && settings.highlightFelSkulls.value()) {
            DungeonMobEspSupport.findFelSkullMarkers(
                client, settings.starredRange.intValue(), settings.felOnlyRoom.value(), activeRoomFootprint,
            ).asSequence()
                .filter { marker -> !settings.felOnlyRoom.value() || isInCurrentRoom(client, marker) }
                .map { marker -> marker as LivingEntity }
                .toList()
        } else {
            emptyList()
        }
        val spatialBodyCandidates = ArrayList<DungeonDevonianMobTracker.BodyCandidate>()
        if (devonianMobTracker.trackedNameplates() > 0) {
            for (entity in level.entitiesForRendering()) {
                if (entity !is LivingEntity || entity is ArmorStand || entity === player ||
                    entity.isRemoved() || !entity.isAlive()
                ) continue
                spatialBodyCandidates.add(
                    DungeonDevonianMobTracker.BodyCandidate(entity.getId(), entity.getX(), entity.getY(), entity.getZ()),
                )
            }
        }
        val packetMobLabels = devonianMobTracker.resolveLoadedBodies(
            { entityId ->
                val entity = level.getEntity(entityId)
                entity is LivingEntity && entity !is ArmorStand &&
                    entity !== player && !entity.isRemoved() && entity.isAlive()
            },
            { entityId ->
                val entity = level.getEntity(entityId)
                if (entity is ArmorStand) {
                    DungeonDevonianMobTracker.LabelPosition(entity.getX(), entity.getY(), entity.getZ())
                } else {
                    null
                }
            },
            spatialBodyCandidates,
        )
        val packetStarred = ArrayList<LivingEntity>()
        val packetLabelMinibosses = ArrayList<LivingEntity>()
        val packetStarredFels = ArrayList<LivingEntity>()
        val packetStarredIdentity = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        val packetStarredGroupIdentity = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        val packetMinibossIdentity = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        val packetFelIdentity = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        val packetLabelsByEntity = IdentityHashMap<LivingEntity, String>()
        val shadowAssassinSet = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        var shadowAssassinNameplateCandidates = 0
        for (association in packetMobLabels) {
            val loaded = level.getEntity(association.bodyId())
            if (loaded !is LivingEntity) continue
            val body = loaded
            val mobClass = association.mobClass()
            // Target identity and the star filter are independent evidence. In particular, a
            // nameplate may identify an SA even when its star glyph is absent or formatted oddly.
            if (association.starred()) packetStarredIdentity.add(body)
            packetLabelsByEntity[body] = mobClass.label()
            if (mobClass.isFel()) {
                if (association.starred() && packetFelIdentity.add(body)) packetStarredFels.add(body)
                continue
            }
            if (mobClass.isShadowAssassin()) {
                shadowAssassinSet.add(body)
                shadowAssassinNameplateCandidates++
            }
            if (mobClass.isMinibossNameplate() && packetMinibossIdentity.add(body)) packetLabelMinibosses.add(body)
            if (association.starred() && mobClass.belongsToStarredGroup() && packetStarredGroupIdentity.add(body)) {
                packetStarred.add(body)
            }
        }
        // Keep unstarred Fel tracking and the Fel skull feature, while starred packet matches bypass
        // the old range-limited scan because the client has already received their entity and label.
        val scannedFels = if (scanForFelBodies) {
            DungeonMobEspSupport.findFels(
                client, settings.starredRange.intValue(),
                settings.felsEnabled.value() && settings.felOnlyRoom.value(), activeRoomFootprint,
            )
        } else {
            emptyList()
        }
        val classifiedFelStates = IdentityHashMap<LivingEntity, Boolean>()
        for (candidate in scannedFels) {
            val entity = candidate.entity()
            classifiedFelStates[entity] = (classifiedFelStates[entity] == true) || candidate.starred()
        }
        for (body in packetStarredFels) classifiedFelStates[body] = true
        val classifiedFels = classifiedFelStates.entries.map { entry ->
            DungeonMobEspSupport.FelCandidate(entry.key, entry.value == true)
        }
        val felBodies = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        classifiedFels.forEach { candidate -> felBodies.add(candidate.entity()) }
        val fels = if (settings.felsEnabled.value() && !roomCleared) {
            ArrayList(classifiedFels)
        } else {
            ArrayList()
        }
        val identifiedFels = HashSet<UUID>()
        for (candidate in fels) {
            val entity = candidate.entity()
            identifiedFels.add(entity.getUUID())
            if (!roomCleared && roomKey != null && isInCurrentRoom(client, entity)) {
                val known = knownFelBodies.computeIfAbsent(entity.getUUID()) { KnownFelBody(entity) }
                known.observe(entity, candidate.starred())
            }
        }
        if (!roomCleared && settings.felsEnabled.value()) {
            val knownIterator = knownFelBodies.entries.iterator()
            while (knownIterator.hasNext()) {
                val entry = knownIterator.next()
                val known = entry.value
                val loaded = level.getEntity(known.entityId)
                if (loaded !is LivingEntity || known.uuid != loaded.getUUID() || !loaded.isAlive()) {
                    knownIterator.remove()
                    continue
                }
                val body = loaded
                known.observe(body, known.starred)
                if (identifiedFels.contains(known.uuid) ||
                    body.distanceToSqr(player) >
                    settings.starredRange.intValue().toDouble() * settings.starredRange.intValue() ||
                    settings.felOnlyRoom.value() && !isInCurrentRoom(client, body)
                ) continue
                fels.add(DungeonMobEspSupport.FelCandidate(body, known.starred))
                identifiedFels.add(known.uuid)
            }
        }
        while (knownFelBodies.size > 512) {
            val oldest = knownFelBodies.keys.iterator()
            if (!oldest.hasNext()) break
            oldest.next()
            oldest.remove()
        }
        val starredFelBodies = ArrayList<LivingEntity>()
        for (candidate in fels) {
            val entity = candidate.entity()
            felBodies.add(entity)
            if (DungeonMobEspSupport.shouldHighlightFel(settings.highlightFels.value(), candidate.starred())) {
                starredFelBodies.add(entity)
            }
        }
        stationaryFelSkulls = felSkullMarkers.toList()
        val minibossScan = if (settings.appearanceCapture.value()) {
            DungeonMobEspSupport.scan(
                client, settings.minibossRange.intValue(), false, { false }, false,
                debug.value(), activeRoomFootprint, null, true,
            )
        } else {
            DungeonMobEspSupport.ScanResult.empty("appearance capture disabled")
        }
        val captureIdentities = currentMinibossIdentitySnapshot(client)
        val shadowAssassins = ArrayList<LivingEntity>()
        val minibosses = ArrayList<LivingEntity>()
        val minibossSet = Collections.newSetFromMap(IdentityHashMap<LivingEntity, Boolean>())
        val earlyProfileLabels = IdentityHashMap<LivingEntity, String>()
        var packetResolvedMinibosses = 0
        var shadowAssassinIdentityRejected = 0
        var shadowAssassinStarFilterBypassed = 0
        var shadowAssassinRoomFilterRejected = 0
        if (settings.minibossEnabled.value()) {
            for (candidate in packetLabelMinibosses) {
                if (shadowAssassinSet.contains(candidate) && candidate is Player &&
                    captureIdentities.identifies(candidate.getUUID(), candidate.getGameProfile().name())
                ) {
                    shadowAssassinIdentityRejected++
                    continue
                }
                val shadowAssassin = shadowAssassinSet.contains(candidate)
                val hasStarredLabel = packetStarredIdentity.contains(candidate)
                if (!DungeonMobEspSupport.shouldAcceptMinibossProfile(
                        settings.minibossOnlyStarred.value(), hasStarredLabel, shadowAssassin,
                    )
                ) {
                    continue
                }
                if (shadowAssassin && settings.minibossOnlyStarred.value() && !hasStarredLabel) {
                    shadowAssassinStarFilterBypassed++
                }
                if (settings.minibossOnlyRoom.value() &&
                    !DungeonMobEspSupport.isAllowedByRoomFilter(true, activeRoomFootprint, candidate.blockPosition())
                ) {
                    if (shadowAssassinSet.contains(candidate)) shadowAssassinRoomFilterRejected++
                    continue
                }
                if (minibossSet.add(candidate)) {
                    minibosses.add(candidate)
                    packetResolvedMinibosses++
                }
                if (shadowAssassinSet.contains(candidate)) shadowAssassins.add(candidate)
                earlyProfileLabels[candidate] = packetLabelsByEntity[candidate]!!
            }
        }
        if (settings.minibossEnabled.value() || settings.appearanceCapture.value()) {
            val profileRange = if (settings.appearanceCapture.value()) 128 else settings.minibossRange.intValue()
            val profileRoomFilter = settings.minibossEnabled.value() && settings.minibossOnlyRoom.value()
            for (candidate in minibossProfileTracker.resolve(
                client, profileRange, activeRoomFootprint, profileRoomFilter, captureIdentities,
            )) {
                val profileLabel = minibossProfileTracker.minibossLabel(candidate.getId()) ?: continue
                if (profileLabel == "Shadow Assassin" && candidate is Player &&
                    captureIdentities.identifies(candidate.getUUID(), candidate.getGameProfile().name())
                ) {
                    shadowAssassinSet.add(candidate)
                    shadowAssassinIdentityRejected++
                    continue
                }
                if (settings.appearanceCapture.value() && candidate is Player &&
                    minibossProfileTracker.shouldCaptureKnownAppearance(candidate.getId())
                ) {
                    captureKnownAppearance(candidate, profileLabel, gameTick, captureIdentities)
                }
                if (!settings.minibossEnabled.value()) continue
                val shadowAssassin = profileLabel == "Shadow Assassin"
                val hasStarredLabel = packetStarredIdentity.contains(candidate)
                // Shadow Assassin has a dedicated identity path and does not depend on normal star evidence.
                if (!DungeonMobEspSupport.shouldAcceptMinibossProfile(
                        settings.minibossOnlyStarred.value(), hasStarredLabel, shadowAssassin,
                    )
                ) {
                    continue
                }
                if (shadowAssassin && settings.minibossOnlyStarred.value() && !hasStarredLabel) {
                    shadowAssassinStarFilterBypassed++
                }
                earlyProfileLabels[candidate] = profileLabel
                if (profileLabel == "Shadow Assassin" && shadowAssassinSet.add(candidate)) {
                    shadowAssassins.add(candidate)
                }
                if (minibossSet.add(candidate)) {
                    minibosses.add(candidate)
                    packetResolvedMinibosses++
                }
            }
        }
        var equipmentShadowAssassinCandidates = 0
        var equipmentShadowAssassinAdded = 0
        if (settings.minibossEnabled.value()) {
            for (entity in level.entitiesForRendering()) {
                if (entity !is Player || entity === player || entity.isRemoved() || !entity.isAlive()) continue
                val playerBody = entity
                val playerId = playerBody.getUUID()
                val profileName = playerBody.getGameProfile().name()
                if (!DungeonMobEspSupport.hasShadowAssassinEquipment(playerBody)) continue
                equipmentShadowAssassinCandidates++
                if (!MinibossProfileMatchPolicy.allowsShadowAssassinEquipmentFallback(
                        playerId, profileName, captureIdentities,
                    )
                ) {
                    shadowAssassinIdentityRejected++
                    continue
                }
                if (settings.minibossOnlyStarred.value() && !packetStarredIdentity.contains(playerBody)) {
                    shadowAssassinStarFilterBypassed++
                }
                if (settings.minibossOnlyRoom.value() &&
                    !DungeonMobEspSupport.isAllowedByRoomFilter(
                        true, activeRoomFootprint, playerBody.blockPosition(),
                    )
                ) {
                    shadowAssassinRoomFilterRejected++
                    continue
                }
                if (minibossSet.add(playerBody)) minibosses.add(playerBody)
                if (shadowAssassinSet.add(playerBody)) {
                    shadowAssassins.add(playerBody)
                    equipmentShadowAssassinAdded++
                }
                earlyProfileLabels[playerBody] = "Shadow Assassin"
            }
        }
        if (settings.appearanceCapture.value()) {
            captureConfirmedNameplateAppearances(
                minibossScan.confirmedMinibossNameplates(), captureIdentities, gameTick,
            )
        }
        val starred = ArrayList<LivingEntity>()
        if (settings.starredEnabled.value()) {
            for (entity in packetStarred) {
                if (felBodies.contains(entity) || shadowAssassinSet.contains(entity)) continue
                if (settings.starredOnlyRoom.value() &&
                    !DungeonMobEspSupport.isAllowedByRoomFilter(true, activeRoomFootprint, entity.blockPosition())
                ) continue
                starred.add(entity)
            }
        }
        starredMatches = starred.toList()
        minibossMatches = minibosses.toList()
        shadowAssassinMatches = shadowAssassins.toList()
        movingFelMatches = if (!roomCleared && settings.felsEnabled.value() && settings.highlightFels.value()) {
            starredFelBodies.filter { entity -> !settings.felOnlyRoom.value() || isInCurrentRoom(client, entity) }
        } else {
            emptyList()
        }
        val liveTargets = ArrayList<DungeonMobTargetMemory.Target>()
        if (roomKey != null && !roomCleared) {
            for (entity in starredMatches) {
                if (isInCurrentRoom(client, entity)) {
                    liveTargets.add(
                        memoryTarget(entity, DungeonMobTargetMemory.Group.STARRED, roomKey, gameTick, false),
                    )
                }
            }
            for (entity in minibossMatches) {
                if (isInCurrentRoom(client, entity)) {
                    liveTargets.add(
                        memoryTarget(
                            entity, DungeonMobTargetMemory.Group.MINIBOSS, roomKey, gameTick,
                            shadowAssassinSet.contains(entity), earlyProfileLabels[entity],
                        ),
                    )
                }
            }
            for (entity in stationaryFelSkulls) {
                if (isInCurrentRoom(client, entity)) {
                    liveTargets.add(
                        memoryTarget(entity, DungeonMobTargetMemory.Group.FEL_SKULL, roomKey, gameTick, false),
                    )
                }
            }
            for (entity in movingFelMatches) {
                if (isInCurrentRoom(client, entity)) {
                    liveTargets.add(
                        memoryTarget(entity, DungeonMobTargetMemory.Group.FEL_MOVING, roomKey, gameTick, false),
                    )
                }
            }
        }
        if (!settings.starredEnabled.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.STARRED)
        if (!settings.minibossEnabled.value()) targetMemory.clearGroup(DungeonMobTargetMemory.Group.MINIBOSS)
        if (!settings.felsEnabled.value() || !settings.highlightFelSkulls.value()) {
            targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_SKULL)
        }
        if (!settings.felsEnabled.value() || !settings.highlightFels.value()) {
            targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_MOVING)
        }
        if (roomCleared) {
            targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_SKULL)
            targetMemory.clearGroup(DungeonMobTargetMemory.Group.FEL_MOVING)
        }
        rememberedTargets = targetMemory.update(
            liveTargets, gameTick,
            { target -> rememberedPresence(level, target) },
            { target -> refreshRememberedTarget(client, target, gameTick) },
        )
        val entityCensus = entityCensusSummary(
            client, packetMobLabels, shadowAssassinMatches, packetStarredIdentity, captureIdentities,
        )
        reportDebug(
            "starredPacket{" + devonianMobTracker.packetDiagnostics() + "}; minibossPacket=" +
                minibosses.size + "; appearanceScan{" + minibossScan.summary() + "}" +
                "; fullShadowAssassins=" + shadowAssassinMatches.size +
                " (nameplateCandidates=" + shadowAssassinNameplateCandidates +
                ", packet-label/profile-associated=" + packetResolvedMinibosses +
                ", equipmentCandidates=" + equipmentShadowAssassinCandidates +
                ", equipmentAdded=" + equipmentShadowAssassinAdded +
                ", identityRejected=" + shadowAssassinIdentityRejected +
                ", onlyStarredBypassed=" + shadowAssassinStarFilterBypassed +
                ", roomRejected=" + shadowAssassinRoomFilterRejected +
                ", remembered=" + rememberedTargets.count { target ->
                    target.group() == DungeonMobTargetMemory.Group.MINIBOSS && target.fullShadowAssassin()
                } + ")" +
                "; minibossSettings{enabled=" + settings.minibossEnabled.value() +
                ",onlyStarred=" + settings.minibossOnlyStarred.value() +
                ",onlyRoom=" + settings.minibossOnlyRoom.value() +
                ",range=" + settings.minibossRange.intValue() +
                ",style=" + settings.minibossStyle.value() +
                ",tracer=" + settings.minibossTracer.value() +
                ",depth=" + settings.depthCheck.value() + "}" +
                "; exact miniboss profiles{" + minibossProfileTracker.packetDiagnostics() + "}" +
                "; profile resolve{" + minibossProfileTracker.resolveDiagnostics() + "}" +
                "; starredFels=" + starredFelBodies.size + ", felHeadMarkers=" + stationaryFelSkulls.size +
                "; depth=" + settings.depthCheck.value() + ", minibossTracer=" +
                settings.minibossTracer.value() + ", minibossStyle=" + settings.minibossStyle.value() +
                "; " + entityCensus,
        )
    }

    /** Updates the former special-mob groups under this module's single master toggle. */
    private fun updateSpecialTargets(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        val next = ArrayList<SpecialTarget>()
        val floor = DungeonContextTracker.currentFloor()
        val inDungeon = DungeonContextTracker.inDungeon()
        val bossPhaseObserved = inDungeon && !DungeonContextTracker.isMobEspPhase()
        val scanF7WitherBoss = DungeonSpecialMobRules.shouldScanF7WitherBoss(
            settings.specialWithers.value(),
            inDungeon,
            floor != null && floor.number() == 7,
            floor != null && floor.master(),
            bossPhaseObserved,
        )
        if (scanF7WitherBoss && floor != null) {
            val range = settings.specialRange.intValue()
            for (entity in ClientEntitySnapshot.nearby(level, player, range.toDouble())) {
                if (entity !is LivingEntity || entity.isRemoved() || !entity.isAlive() ||
                    entity.distanceToSqr(player) > range.toDouble() * range ||
                    !DungeonSpecialMobRules.isEarlyF7WitherBoss(
                        floor.number() == 7, floor.master(), bossPhaseObserved,
                        entity.getType() == EntityTypes.WITHER,
                    )
                ) continue
                next.add(SpecialTarget(entity, settings.specialMobFill.argb(), settings.specialMobOutline.argb()))
            }
            val scan = DungeonMobEspSupport.scan(
                client, range, false, DungeonSpecialMobRules::isF7WitherBossName, false, false, null,
                { entity ->
                    DungeonSpecialMobRules.isF7WitherBoss(
                        floor.number() == 7, floor.master(),
                        entity.getType() == EntityTypes.WITHER, DungeonMobEspSupport.plainName(entity),
                    )
                },
            )
            for (entity in scan.matches()) {
                if (next.none { target -> target.entity() === entity }) {
                    next.add(SpecialTarget(entity, settings.specialMobFill.argb(), settings.specialMobOutline.argb()))
                }
            }
        }
        if (settings.partyClasses.value() && DungeonContextTracker.isMobEspPhase() &&
            PartyListBackend.hasFreshSnapshot()
        ) {
            val classes = HashMap<String, DungeonClass>()
            for (member in PartyListBackend.snapshot().members()) {
                if (member == null) continue
                val memberName = member.name()
                val dungeonClass = member.dungeonClass()
                if (memberName != null && dungeonClass != null) {
                    classes[memberName.lowercase(Locale.ROOT)] = dungeonClass
                }
            }
            for (entity in ClientEntitySnapshot.nearby(level, player, settings.specialRange.intValue().toDouble())) {
                if (entity !is Player || entity === player || entity.isRemoved() || !entity.isAlive()) continue
                val dungeonClass = classes[entity.getName().getString().lowercase(Locale.ROOT)] ?: continue
                next.add(SpecialTarget(entity, settings.partyFill.argb(), classColor(dungeonClass).argb()))
            }
        }
        specialTargets = next.toList()
        mimicChests = if (settings.specialMimic.value() && DungeonContextTracker.isClearPhase()) {
            DungeonMimicChestDetector.detect(client, mimicRoomScanner)
        } else {
            emptyList()
        }
    }

    private fun classColor(dungeonClass: DungeonClass): ColorSetting =
        when (dungeonClass) {
            DungeonClass.TANK -> settings.tank
            DungeonClass.HEALER -> settings.healer
            DungeonClass.MAGE -> settings.mage
            DungeonClass.BERSERK -> settings.berserk
            DungeonClass.ARCHER -> settings.archer
        }

    /** Called by the client packet mixin after Vanilla handles each player-info update. */
    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket?) {
        if (packet == null) return
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        minibossProfileTracker.onWorld(Minecraft.getInstance().level)
        val addPlayerAction = packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)
        minibossProfileTracker.onPlayerInfoUpdate(addPlayerAction, packet.entries().size)
        if (!addPlayerAction) return
        val catalog = appearanceCatalog()
        val identities = currentMinibossIdentitySnapshot(Minecraft.getInstance())
        for (entry in packet.entries()) {
            val profile = entry.profile()
            val appearance = MinibossAppearanceCapture.appearanceIdentity(profile)
            minibossProfileTracker.onPlayerInfo(
                entry.profileId(), if (profile == null) null else profile.name(),
                appearance, catalog, identities,
            )
        }
    }

    /** Builds a transient local and currently verified party identity view for appearance matching. */
    private fun currentMinibossIdentitySnapshot(client: Minecraft?): MinibossProfileMatchPolicy.IdentitySnapshot {
        val player = client?.player
        val localId = player?.getUUID()
        val localName = player?.getGameProfile()?.name()
        val partyIds = HashSet<UUID>()
        val partyNames = HashSet<String>()
        val party = PartyListBackend.snapshot()
        if (party.inParty() && PartyListBackend.hasFreshSnapshot()) {
            for (member in party.members()) {
                val memberUuid = member.uuid()
                if (memberUuid != null) partyIds.add(memberUuid)
                val memberName = member.name()
                if (memberName != null && !DungeonMobEspSupport.javaStringIsBlank(memberName)) {
                    partyNames.add(memberName)
                }
            }
        }
        return MinibossProfileMatchPolicy.IdentitySnapshot(localId, localName, partyIds, partyNames)
    }

    private fun appearanceCatalog(): MinibossAppearanceCatalog {
        if (appearanceCatalogLoaded) return appearanceCatalog
        appearanceCatalogLoaded = true
        val loaded = MinibossAppearanceCatalogStore.load()
        appearanceCatalog = loaded.catalog() ?: MinibossAppearanceCatalog.empty()
        if (!DungeonMobEspSupport.javaStringIsBlank(loaded.status())) {
            GeilerAddons.LOGGER.warn("Miniboss appearance catalog was not fully loaded: {}", loaded.status())
        }
        return appearanceCatalog
    }

    /** Called after Vanilla inserts the entity so resolution can be retried if it is not available yet. */
    fun onEntitySpawn(packet: ClientboundAddEntityPacket?) {
        if (packet == null) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread()) return
        val level = client.level
        minibossProfileTracker.onWorld(level)
        devonianMobTracker.onWorld(level)
        if (EntityTypes.ARMOR_STAND.equals(packet.getType())) {
            devonianMobTracker.onArmorStandSpawn(level, packet.getId())
            val spawned = level?.getEntity(packet.getId())
            if (spawned is ArmorStand && spawned.hasCustomName()) {
                devonianMobTracker.onNameplateMetadata(
                    level, spawned.getId(), DungeonMobEspSupport.plainName(spawned),
                )
            }
        }
        minibossProfileTracker.onPlayerSpawn(
            packet.getUUID(), packet.getId(), EntityTypes.PLAYER.equals(packet.getType()),
        )
    }

    /** Captures ArmorStand custom-name updates after Vanilla applies the metadata packet. */
    fun onEntityMetadataUpdated(entityId: Int) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread()) return
        val level = client.level ?: return
        val entity = level.getEntity(entityId)
        if (entity !is ArmorStand) return
        devonianMobTracker.onWorld(level)
        devonianMobTracker.onNameplateMetadata(level, entityId, DungeonMobEspSupport.plainName(entity))
    }

    /** Despawn packets are authoritative; entity IDs may be reused later in the same level. */
    fun onEntitiesRemoved(packet: ClientboundRemoveEntitiesPacket?) {
        if (packet == null) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread()) return
        minibossProfileTracker.onWorld(client.level)
        devonianMobTracker.onWorld(client.level)
        val removedIds = HashSet<Int>()
        for (entityId in packet.getEntityIds()) {
            minibossProfileTracker.onEntityRemoved(entityId)
            removedIds.add(entityId)
            removedIds.addAll(devonianMobTracker.onEntityRemoved(entityId))
        }
        for (entityId in removedIds) targetMemory.removeEntityId(entityId)
        starredMatches = starredMatches.filter { entity -> !removedIds.contains(entity.getId()) }
        minibossMatches = minibossMatches.filter { entity -> !removedIds.contains(entity.getId()) }
        shadowAssassinMatches = shadowAssassinMatches.filter { entity -> !removedIds.contains(entity.getId()) }
        rememberedTargets = rememberedTargets.filter { target -> !removedIds.contains(target.entityId()) }
    }

    fun clearDungeonMinibossProfiles() {
        minibossProfileTracker.clear()
    }

    private fun captureKnownAppearance(
        body: Player,
        label: String,
        gameTick: Long,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot,
    ) {
        captureAutomaticAppearance(body, label, MinibossAppearanceCapture.Source.CONFIRMED_PROFILE, gameTick, identities)
    }

    private fun captureConfirmedNameplateAppearances(
        matches: List<DungeonMobEspSupport.MinibossNameplateMatch>?,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot,
        gameTick: Long,
    ) {
        if (matches.isNullOrEmpty()) return
        val labelsByPlayer = IdentityHashMap<Player, MutableSet<String>>()
        for (match in matches) {
            val body = match?.body()
            if (body !is Player) continue
            labelsByPlayer.computeIfAbsent(body) { HashSet() }.add(match.label())
        }
        for ((body, labels) in labelsByPlayer) {
            val label = MinibossProfileMatchPolicy.uniqueRecognizedMinibossLabel(labels) ?: continue
            captureAutomaticAppearance(
                body, label, MinibossAppearanceCapture.Source.CONFIRMED_NAMEPLATE, gameTick, identities,
            )
        }
    }

    private fun captureAutomaticAppearance(
        body: Player?,
        label: String,
        source: MinibossAppearanceCapture.Source?,
        gameTick: Long,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot,
    ) {
        // Avoid hashing profile textures on every tick while the opt-in writer backs off after I/O failure.
        if (!settings.appearanceCapture.value() || !GeilerAddonsLog.enabled() || body == null || source == null) return
        val profile = body.getGameProfile()
        val evidence = when (source) {
            MinibossAppearanceCapture.Source.CONFIRMED_PROFILE ->
                MinibossProfileMatchPolicy.AutomaticCaptureEvidence.EXACT_PROFILE_NAME
            MinibossAppearanceCapture.Source.CONFIRMED_NAMEPLATE ->
                MinibossProfileMatchPolicy.AutomaticCaptureEvidence.UNIQUE_RECOGNIZED_NAMEPLATE
            MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION ->
                MinibossProfileMatchPolicy.AutomaticCaptureEvidence.MANUAL_CONFIRMATION
        }
        if (!MinibossProfileMatchPolicy.shouldCaptureAppearanceAutomatically(
                evidence, label, profile.id(), profile.name(), identities,
                appearanceCapture.wasAutomaticallyInspected(profile.id()),
            )
        ) return
        val identity = MinibossAppearanceCapture.appearanceIdentity(profile) ?: return
        val result = recordAppearance(source, label, profile, body.getId(), identity, gameTick, false)
        if (MinibossAppearanceCapture.shouldMarkAutomaticallyInspected(result)) {
            appearanceCapture.markAutomaticallyInspected(profile.id())
        }
    }

    private fun captureCrosshairAppearance() {
        val client = Minecraft.getInstance()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        if (!settings.appearanceCapture.value()) {
            showCaptureMessage("Enable Capture Miniboss Appearances first.")
            return
        }
        val level = client.level
        val player = client.player
        if (level == null || player == null || !DungeonMobEspCaptureReadiness.isReady(
                level != null, player != null, DungeonContextTracker.inDungeon(),
            )
        ) {
            showCaptureMessage("Manual miniboss capture only works inside a dungeon.")
            return
        }
        val hit = client.hitResult
        if (hit !is EntityHitResult || hit.getType() != HitResult.Type.ENTITY ||
            hit.getEntity() !is Player || hit.getEntity() === player ||
            hit.getEntity().isRemoved() || !hit.getEntity().isAlive()
        ) {
            showCaptureMessage("Aim at a loaded player-type miniboss and try again.")
            return
        }
        val target = hit.getEntity() as Player
        val profile = target.getGameProfile()
        val partyIds = HashSet<UUID>()
        val partyNames = HashSet<String>()
        for (member in PartyListBackend.snapshot().members()) {
            val memberUuid = member.uuid()
            if (memberUuid != null) partyIds.add(memberUuid)
            val memberName = member.name()
            if (memberName != null) partyNames.add(memberName)
        }
        val connection = client.getConnection()
        val listedOnline = connection == null || connection.getPlayerInfo(target.getUUID()) != null
        if (MinibossAppearanceCapture.isExcludedPlayerProfile(
                player.getUUID(), target.getUUID(), profile.name(), listedOnline, partyIds, partyNames,
            )
        ) {
            showCaptureMessage(
                "Capture blocked for yourself, party members, and players listed online. Aim at an unlisted NPC.",
            )
            return
        }
        val label = DungeonMobEspSupport.javaStringTrim(settings.manualAppearanceLabel.value())
        if (DungeonMobEspSupport.javaStringIsBlank(label)) {
            showCaptureMessage("Enter a manual capture label first.")
            return
        }
        val identity = MinibossAppearanceCapture.appearanceIdentity(profile)
        if (identity == null) {
            showCaptureMessage("No supported Minecraft skin texture signature is available for that target.")
            return
        }
        recordAppearance(
            MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION, label, profile, target.getId(),
            identity, level.getGameTime(), true,
        )
    }

    private fun recordAppearance(
        source: MinibossAppearanceCapture.Source,
        label: String,
        profile: GameProfile,
        entityId: Int,
        identity: MinibossAppearanceCapture.AppearanceIdentity,
        gameTick: Long,
        reportResult: Boolean,
    ): MinibossAppearanceCapture.RecordResult {
        val logPath = GeilerAddonsLog.path(Category.F7, name())
        val result = appearanceCapture.record(
            source, label, profile, entityId, identity.textureSignature(), identity.model(), gameTick,
        ) { line ->
            GeilerAddonsLog.writeWithCompletion(
                Category.F7, name(), gameTick, "appearance-capture " + line,
            ) { flushed ->
                Minecraft.getInstance().execute {
                    finishAppearanceCapture(
                        label, profile, identity, logPath, reportResult,
                        source != MinibossAppearanceCapture.Source.MANUAL_CONFIRMATION, flushed,
                    )
                }
            }
        }
        if (result == MinibossAppearanceCapture.RecordResult.QUEUE_REJECTED) {
            val message =
                "Appearance capture was not queued and was not saved. The local log is unavailable or busy; retry when it is available."
            if (reportResult) showCaptureMessage(message) else reportAppearanceCaptureFailure(message)
            return result
        }
        if (!reportResult) return result
        val message = when (result) {
            MinibossAppearanceCapture.RecordResult.QUEUED ->
                "Appearance capture queued. Waiting for the local log write to finish."
            MinibossAppearanceCapture.RecordResult.QUEUE_REJECTED ->
                "Appearance capture was not queued and was not saved."
            MinibossAppearanceCapture.RecordResult.DUPLICATE ->
                "That appearance is already saved or awaiting the log write this session."
            MinibossAppearanceCapture.RecordResult.LIMIT_REACHED ->
                "Appearance capture reached its 64-record session limit."
            MinibossAppearanceCapture.RecordResult.INVALID ->
                "Could not create a valid appearance record."
        }
        showCaptureMessage(message)
        return result
    }

    private fun finishAppearanceCapture(
        label: String,
        profile: GameProfile,
        identity: MinibossAppearanceCapture.AppearanceIdentity,
        logPath: java.nio.file.Path?,
        reportResult: Boolean,
        automaticCapture: Boolean,
        flushed: Boolean,
    ) {
        appearanceCapture.completeAsyncWrite(
            profile, label, identity.textureSignature(), identity.model(), automaticCapture, flushed,
        )
        if (!flushed) {
            reportAppearanceCaptureFailure(
                "The local log write could not be confirmed. Check the session log before retrying; a partial line may have reached it.",
            )
            return
        }
        appearanceCaptureFailureReported = false
        if (!reportResult) return
        var message = "Saved " + label + " appearance profile to the local Dungeon Mob ESP session log."
        if (logPath != null) message += " Send this file: " + logPath
        showCaptureMessage(message)
    }

    private fun reportAppearanceCaptureFailure(message: String) {
        if (appearanceCaptureFailureReported) return
        appearanceCaptureFailureReported = true
        showCaptureMessage(message)
    }

    private fun showCaptureMessage(message: String) {
        val client = Minecraft.getInstance()
        if (client.gui != null) {
            client.gui.hud.chat.addClientSystemMessage(Component.literal(message))
        }
    }

    private fun reportDebug(state: String) {
        if (!isEnabled()) {
            lastDiagnostic = ""
            lastDiagnosticTick = Long.MIN_VALUE
            return
        }
        val client = Minecraft.getInstance()
        val tick = client.level?.getGameTime() ?: 0L
        // Packet-stage counters can change during entity-spawn bursts. Keep diagnostics useful without
        // writing a new log line for every packet in the same short interval.
        if (lastDiagnosticTick != Long.MIN_VALUE && tick - lastDiagnosticTick < 20) return
        if (state == lastDiagnostic && tick - lastDiagnosticTick < 100) return
        lastDiagnostic = state
        lastDiagnosticTick = tick
        GeilerAddonsLog.write(Category.F7, name(), tick, "mob ESP: " + state)
    }

    private fun entityCensusSummary(
        client: Minecraft?,
        associations: List<DungeonDevonianMobTracker.Association>,
        publishedTargets: List<LivingEntity>,
        starredIdentities: Set<LivingEntity>,
        identities: MinibossProfileMatchPolicy.IdentitySnapshot,
    ): String {
        val level = client?.level ?: return "entityCensus{unavailable}"
        val tick = level.getGameTime()
        if (lastEntityCensusTick != Long.MIN_VALUE && tick >= lastEntityCensusTick &&
            tick - lastEntityCensusTick < 20L
        ) return lastEntityCensusSummary
        lastEntityCensusTick = tick
        lastEntityCensusSummary = DungeonMobEspEntityCensus.capture(
            level, tick, associations, publishedTargets, starredIdentities, minibossProfileTracker, identities,
            settings.minibossEnabled.value(), settings.minibossOnlyStarred.value(),
            settings.minibossOnlyRoom.value(), activeRoomFootprint,
        ).summary()
        return lastEntityCensusSummary
    }

    private fun updateRoomFootprint(client: Minecraft?): DungeonRoomTracker.MapRoomFootprint? {
        val level = client?.level ?: return null
        val player = client.player ?: return null
        val observed = DungeonMobEspSupport.currentConfirmedRoomFootprint()
        if (observed != null) return observed
        val sameLevel = level === lastLevel
        val playerInside = activeRoomFootprint != null &&
            activeRoomFootprint!!.contains(player.blockPosition())
        return if (DungeonMobEspSupport.shouldRetainRoomFootprint(sameLevel, playerInside)) {
            activeRoomFootprint
        } else {
            null
        }
    }

    private fun currentRoomKey(client: Minecraft?): String? {
        if (client == null || client.player == null) return null
        if (activeRoomFootprint != null && !DungeonMobEspSupport.javaStringIsBlank(activeRoomFootprint!!.footprintKey())) {
            return activeRoomFootprint!!.footprintKey()
        }
        return null
    }

    private fun isInCurrentRoom(client: Minecraft?, entity: LivingEntity?): Boolean {
        if (client?.player == null || entity == null) return false
        return activeRoomFootprint != null && activeRoomFootprint!!.contains(entity.blockPosition())
    }

    /** Re-checks the live room at render time so a published Fel snapshot cannot leak across rooms. */
    private fun currentRoomFels(client: Minecraft): List<LivingEntity> {
        if (!settings.felOnlyRoom.value()) return movingFelMatches
        return movingFelMatches.filter { entity -> isInCurrentRoom(client, entity) }
    }

    private fun memoryTarget(
        entity: LivingEntity,
        group: DungeonMobTargetMemory.Group,
        roomKey: String,
        gameTick: Long,
        fullShadowAssassin: Boolean,
        profileName: String? = null,
    ): DungeonMobTargetMemory.Target {
        if (group == DungeonMobTargetMemory.Group.FEL_SKULL) {
            return DungeonMobTargetMemory.Target(
                entity.getUUID(), entity.getId(), group, java.lang.String.valueOf(entity.getType()),
                DungeonMobEspSupport.plainName(entity), FelSkullMarkerGeometry.bounds(entity),
                roomKey, gameTick, false,
            )
        }
        val box = if (fullShadowAssassin) {
            DungeonMobEspSupport.fullShadowAssassinBounds(entity) ?: entity.getBoundingBox()
        } else {
            entity.getBoundingBox()
        }
        return DungeonMobTargetMemory.Target(
            entity.getUUID(), entity.getId(), group, java.lang.String.valueOf(entity.getType()),
            profileName ?: DungeonMobEspSupport.plainName(entity),
            DungeonMobTargetMemory.Bounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ),
            roomKey, gameTick, fullShadowAssassin,
        )
    }

    private fun renderFelSkullMarker(
        pose: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        camera: Vec3,
        forward: Vector3fc,
        bounds: DungeonMobTargetMemory.Bounds,
        elapsedTicks: Double,
    ) {
        val x = bounds.minX() - camera.x
        val y = bounds.minY() - camera.y
        val z = bounds.minZ() - camera.z
        val box = AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())
        var ringHeights: DoubleArray? = null
        if (settings.felStyle.value() == "Ring") {
            val feet = box.minY + 0.05
            var head = minOf(box.maxY - 0.05, feet + settings.felRingHeight.value())
            if (head < feet) head = feet
            ringHeights = PestAnimation.ringPositions(
                feet, head, elapsedTicks, settings.felRingSpeed.value().toDouble(), settings.felRingCount.intValue(),
            )
            for (ringY in ringHeights) {
                EspRenderer.renderRing(
                    pose, buffers, bounds.centerX() - camera.x, ringY - camera.y,
                    bounds.centerZ() - camera.z, settings.felRingRadius.value(),
                    settings.felOutline.argb(), settings.felRingLineWidth.value(), settings.depthCheck.value(),
                )
            }
        } else if (settings.felStyle.value() != "2D Box") {
            val fillColor = if (settings.felStyle.value() == "Chams") settings.felFill.argb() else 0
            EspRenderer.renderBox(
                pose, buffers, x, y, z, box.getXsize(), box.getYsize(), box.getZsize(),
                fillColor, settings.felOutline.argb(), settings.felLineWidth.value(), settings.depthCheck.value(),
            )
        }
        if (!settings.felSkullTracer.value()) return
        var targetPoint = Vec3(bounds.centerX(), bounds.centerY(), bounds.centerZ())
        if (ringHeights != null) {
            val ring = PestAnimation.closestRingTarget(
                bounds.centerX(), bounds.centerZ(), camera.x, camera.y, camera.z,
                settings.felRingRadius.value(), ringHeights,
            )
            targetPoint = Vec3(ring.x, ring.y, ring.z)
        }
        val endpoints = BlockEspTracerGeometry.endpoints(camera, forward, targetPoint, 0.5)
        val start = endpoints.start
        val end = endpoints.end
        EspRenderer.renderLine(
            pose, buffers, start.x, start.y, start.z,
            end.x, end.y, end.z,
            settings.felTracerColor.argb(), settings.felTracerWidth.value(), settings.depthCheck.value(),
        )
    }

    private fun rememberedPresence(
        level: ClientLevel?,
        target: DungeonMobTargetMemory.Target?,
    ): DungeonMobTargetMemory.Presence {
        if (level == null || target == null) return DungeonMobTargetMemory.Presence.LOADED_GONE
        val loaded = level.getEntity(target.entityId())
        if (loaded != null && target.uuid().equals(loaded.getUUID())) {
            return if (!loaded.isRemoved() && loaded.isAlive()) {
                DungeonMobTargetMemory.Presence.LOADED_ALIVE
            } else {
                DungeonMobTargetMemory.Presence.LOADED_GONE
            }
        }
        val bounds = target.bounds()
        val anchor = BlockPos(
            Math.floor(bounds.centerX()).toInt(),
            Math.floor(bounds.minY()).toInt(),
            Math.floor(bounds.centerZ()).toInt(),
        )
        return if (level.hasChunkAt(anchor)) {
            DungeonMobTargetMemory.Presence.LOADED_GONE
        } else {
            DungeonMobTargetMemory.Presence.UNLOADED
        }
    }

    private fun refreshRememberedTarget(
        client: Minecraft?,
        target: DungeonMobTargetMemory.Target?,
        gameTick: Long,
    ): DungeonMobTargetMemory.Target? {
        val level = client?.level ?: return null
        if (target == null) return null
        val loaded = level.getEntity(target.entityId())
        if (target.group() == DungeonMobTargetMemory.Group.FEL_SKULL &&
            (loaded !is ArmorStand || !DungeonMobEspSupport.isFelSkullMarker(loaded))
        ) return null
        if (loaded !is LivingEntity || loaded.isRemoved() || !loaded.isAlive() ||
            target.uuid() != loaded.getUUID()
        ) return null
        if (!DungeonMobTargetMemory.isPersistent(target.group()) &&
            !java.util.Objects.equals(target.roomKey(), currentRoomKey(client))
        ) return null
        if (!DungeonMobTargetMemory.isPersistent(target.group()) && !isInCurrentRoom(client, loaded)) return null
        val refreshed = memoryTarget(
            loaded, target.group(), target.roomKey(), gameTick, target.fullShadowAssassin(),
        )
        return DungeonMobTargetMemory.Target(
            refreshed.uuid(), refreshed.entityId(), refreshed.group(), refreshed.type(), target.label(),
            refreshed.bounds(), target.roomKey(), gameTick, refreshed.fullShadowAssassin(),
        )
    }

    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        if (starredMatches.isEmpty() && minibossMatches.isEmpty() && movingFelMatches.isEmpty() &&
            stationaryFelSkulls.isEmpty() && rememberedTargets.isEmpty() &&
            specialTargets.isEmpty() && mimicChests.isEmpty()
        ) {
            reportTracerDiagnostic(
                TracerRenderDiagnostics(
                    settings.minibossTracer.value(), settings.minibossTracerColor.argb(),
                    settings.minibossTracerColor.alpha(), settings.minibossTracerWidth.value(),
                    settings.minibossStyle.value(), settings.minibossRange.intValue(),
                    settings.minibossOnlyRoom.value(), settings.minibossOnlyStarred.value(),
                    settings.depthCheck.value(), shadowAssassinMatches.size,
                ),
                false,
            )
            return
        }
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val gameRenderer = client.gameRenderer ?: return
        val level = client.level ?: return
        val camera = gameRenderer.mainCamera().position()
        val partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        val elapsedTicks = level.getGameTime().toDouble() + partialTick.toDouble()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var drew = renderWorldGroup(
            client, pose, buffers, camera, elapsedTicks, partialTick, starredMatches,
            settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
            settings.starredLineWidth, null, null, null, settings.starredRingCount,
            settings.starredRingHeight, settings.starredRingRadius, settings.starredRingSpeed,
            settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH, null, emptyList(),
        )
        val felRoomClear = DungeonMapRoomDetector.currentRoomCleared()
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
            drew = drew or renderWorldGroup(
                client, pose, buffers, camera, elapsedTicks, partialTick, currentRoomFels(client),
                settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
                settings.starredLineWidth, null, null, null, settings.starredRingCount,
                settings.starredRingHeight, settings.starredRingRadius, settings.starredRingSpeed,
                settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH, null, emptyList(),
            )
        }
        val tracerDiagnostics = TracerRenderDiagnostics(
            settings.minibossTracer.value(), settings.minibossTracerColor.argb(),
            settings.minibossTracerColor.alpha(), settings.minibossTracerWidth.value(),
            settings.minibossStyle.value(), settings.minibossRange.intValue(),
            settings.minibossOnlyRoom.value(), settings.minibossOnlyStarred.value(),
            settings.depthCheck.value(), shadowAssassinMatches.size,
        )
        drew = drew or renderWorldGroup(
            client, pose, buffers, camera, elapsedTicks, partialTick, minibossMatches,
            settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
            settings.minibossLineWidth, settings.minibossTracer, settings.minibossTracerColor,
            settings.minibossTracerWidth, settings.minibossRingCount, settings.minibossRingHeight,
            settings.minibossRingRadius, settings.minibossRingSpeed, settings.minibossRingLineWidth,
            MINIBOSS_DEFAULT_LINE_WIDTH, tracerDiagnostics, shadowAssassinMatches,
        )
        drew = drew or renderRememberedGroup(
            client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.STARRED,
            settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
            settings.starredLineWidth, null, null, null, settings.starredRingCount,
            settings.starredRingHeight, settings.starredRingRadius, settings.starredRingSpeed,
            settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH,
        )
        drew = drew or renderRememberedGroup(
            client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.MINIBOSS,
            settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
            settings.minibossLineWidth, settings.minibossTracer, settings.minibossTracerColor,
            settings.minibossTracerWidth, settings.minibossRingCount, settings.minibossRingHeight,
            settings.minibossRingRadius, settings.minibossRingSpeed, settings.minibossRingLineWidth,
            MINIBOSS_DEFAULT_LINE_WIDTH,
        )
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
            drew = drew or renderRememberedGroup(
                client, pose, buffers, camera, elapsedTicks, partialTick, DungeonMobTargetMemory.Group.FEL_MOVING,
                settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
                settings.starredLineWidth, null, null, null, settings.starredRingCount,
                settings.starredRingHeight, settings.starredRingRadius, settings.starredRingSpeed,
                settings.starredRingLineWidth, STARRED_DEFAULT_LINE_WIDTH,
            )
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
        reportTracerDiagnostic(tracerDiagnostics, drew)
        if (!felRoomClear && stationaryFelSkulls.isNotEmpty()) {
            val forward = client.gameRenderer.mainCamera().forwardVector()
            for (entity in stationaryFelSkulls) {
                if (entity.isRemoved() || !entity.isAlive() ||
                    settings.felOnlyRoom.value() && !isInCurrentRoom(client, entity) ||
                    entity.distanceToSqr(player) >
                    settings.starredRange.intValue().toDouble() * settings.starredRange.intValue()
                ) continue
                renderFelSkullMarker(
                    pose, buffers, camera, forward, FelSkullMarkerGeometry.bounds(entity), elapsedTicks,
                )
            }
            GeilerAddonsRenderTypes.endBatches(buffers)
        }
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value()) {
            var drewFelGhost = false
            val forward = client.gameRenderer.mainCamera().forwardVector()
            val currentRoom = currentRoomKey(client)
            val felRangeSquared = settings.starredRange.intValue().toDouble() * settings.starredRange.intValue()
            for (target in rememberedTargets) {
                if (target.group() != DungeonMobTargetMemory.Group.FEL_SKULL ||
                    !java.util.Objects.equals(currentRoom, target.roomKey())
                ) continue
                val bounds = target.bounds()
                if (player.distanceToSqr(bounds.centerX(), bounds.centerY(), bounds.centerZ()) > felRangeSquared) {
                    continue
                }
                renderFelSkullMarker(pose, buffers, camera, forward, bounds, elapsedTicks)
                drewFelGhost = true
            }
            if (drewFelGhost) GeilerAddonsRenderTypes.endBatches(buffers)
        }
        var drewSpecial = false
        for (target in specialTargets) {
            val entity = target.entity()
            if (entity.isRemoved() || !entity.isAlive() || entity is ArmorStand ||
                player.distanceToSqr(entity) >
                settings.specialRange.intValue().toDouble() * settings.specialRange.intValue()
            ) continue
            val bounds = entity.getBoundingBox()
            EspRenderer.renderBox(
                pose, buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                bounds.getXsize(), bounds.getYsize(), bounds.getZsize(),
                target.fillColor(), target.outlineColor(), 2.0f, settings.depthCheck.value(),
            )
            drewSpecial = true
        }
        if (settings.specialMimic.value() && DungeonContextTracker.isClearPhase()) {
            val maxDistanceSquared =
                settings.specialRange.intValue().toDouble() * settings.specialRange.intValue()
            for (chest in mimicChests) {
                if (player.distanceToSqr(
                        chest.getX() + 0.5, chest.getY() + 0.5, chest.getZ() + 0.5,
                    ) > maxDistanceSquared
                ) continue
                val bounds = AABB(
                    chest.getX().toDouble(), chest.getY().toDouble(), chest.getZ().toDouble(),
                    chest.getX() + 1.0, chest.getY() + 1.0, chest.getZ() + 1.0,
                )
                EspRenderer.renderBox(
                    pose, buffers, bounds.minX - camera.x, bounds.minY - camera.y, bounds.minZ - camera.z,
                    bounds.getXsize(), bounds.getYsize(), bounds.getZsize(),
                    settings.mimicFill.argb(), settings.mimicOutline.argb(), 2.0f,
                    settings.depthCheck.value(),
                )
                drewSpecial = true
            }
        }
        if (drewSpecial) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun renderRememberedGroup(
        client: Minecraft,
        pose: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        camera: Vec3,
        elapsedTicks: Double,
        partialTick: Float,
        group: DungeonMobTargetMemory.Group,
        style: ChoiceSetting,
        depth: BooleanSetting,
        outline: ColorSetting,
        fill: ColorSetting,
        lineWidth: NumberSetting,
        tracer: BooleanSetting?,
        tracerColor: ColorSetting?,
        tracerWidth: NumberSetting?,
        ringCount: NumberSetting,
        ringHeight: NumberSetting,
        ringRadius: NumberSetting,
        ringSpeed: NumberSetting,
        ringLineWidth: NumberSetting,
        defaultLineWidth: Float,
    ): Boolean {
        var drew = false
        val player = client.player ?: return false
        val forward = client.gameRenderer.mainCamera().forwardVector()
        val maxRange = if (group == DungeonMobTargetMemory.Group.MINIBOSS) {
            settings.minibossRange.intValue().toDouble()
        } else {
            settings.starredRange.intValue().toDouble()
        }
        val maxRangeSquared = maxRange * maxRange
        for (target in rememberedTargets) {
            if (target.group() != group || !shouldRenderRememberedRoom(client, target)) continue
            val bounds = target.bounds()
            val shadowAssassin = group == DungeonMobTargetMemory.Group.MINIBOSS &&
                DungeonMobEspSupport.isShadowAssassin(target.label())
            val box = rememberedShadowAssassinBounds(client, target, partialTick) ?: AABB(
                bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ(),
            )
            if (group != DungeonMobTargetMemory.Group.STARRED &&
                group != DungeonMobTargetMemory.Group.MINIBOSS &&
                player.distanceToSqr(box.getCenter().x, box.getCenter().y, box.getCenter().z) > maxRangeSquared
            ) continue
            val centerX = box.getCenter().x
            val centerY = box.getCenter().y
            val centerZ = box.getCenter().z
            var ringHeights: DoubleArray? = null
            if (style.value() == "Ring") {
                val feet = box.minY + 0.05
                var head = minOf(box.maxY - 0.05, feet + ringHeight.value())
                if (head < feet) head = feet
                ringHeights = PestAnimation.ringPositions(
                    feet, head, elapsedTicks, ringSpeed.value().toDouble(), ringCount.intValue(),
                )
                for (y in ringHeights) {
                    EspRenderer.renderRing(
                        pose, buffers, centerX - camera.x, y - camera.y, centerZ - camera.z,
                        ringRadius.value(), outline.argb(), ringLineWidth.value(), depth.value(),
                    )
                }
                drew = true
            } else if (style.value() != "2D Box") {
                val fillColor = if (style.value() == "Chams") fill.argb() else 0
                EspRenderer.renderBox(
                    pose, buffers, box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z,
                    box.getXsize(), box.getYsize(), box.getZsize(), fillColor, outline.argb(),
                    if (lineWidth.value() > 0) lineWidth.value() else defaultLineWidth, depth.value(),
                )
                drew = true
            }
            if (tracer != null && tracer.value()) {
                var targetPoint = Vec3(centerX, centerY, centerZ)
                if (ringHeights != null) {
                    val ring = PestAnimation.closestRingTarget(
                        centerX, centerZ, camera.x, camera.y, camera.z, ringRadius.value(), ringHeights,
                    )
                    targetPoint = Vec3(ring.x, ring.y, ring.z)
                }
                val endpoints = BlockEspTracerGeometry.endpoints(camera, forward, targetPoint, 0.5)
                EspRenderer.renderLine(
                    pose, buffers, endpoints.start.x, endpoints.start.y, endpoints.start.z,
                    endpoints.end.x, endpoints.end.y, endpoints.end.z,
                    tracerColor!!.argb(), tracerWidth!!.value(), depth.value(),
                )
                drew = true
            }
        }
        return drew
    }

    private fun rememberedShadowAssassinBounds(
        client: Minecraft?,
        target: DungeonMobTargetMemory.Target?,
        partialTick: Float,
    ): AABB? {
        val level = client?.level ?: return null
        if (target == null || !target.fullShadowAssassin()) return null
        val loaded = level.getEntity(target.entityId())
        if (loaded !is LivingEntity || loaded.isRemoved() || !loaded.isAlive() ||
            target.uuid() != loaded.getUUID()
        ) return null
        return DungeonMobEspSupport.fullShadowAssassinBounds(loaded, partialTick)
    }

    private fun renderWorldGroup(
        client: Minecraft,
        pose: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        camera: Vec3,
        elapsedTicks: Double,
        partialTick: Float,
        entities: List<LivingEntity>,
        style: ChoiceSetting,
        depth: BooleanSetting,
        outline: ColorSetting,
        fill: ColorSetting,
        lineWidth: NumberSetting,
        tracer: BooleanSetting?,
        tracerColor: ColorSetting?,
        tracerWidth: NumberSetting?,
        ringCount: NumberSetting,
        ringHeight: NumberSetting,
        ringRadius: NumberSetting,
        ringSpeed: NumberSetting,
        ringLineWidth: NumberSetting,
        defaultLineWidth: Float,
        tracerDiagnostics: TracerRenderDiagnostics?,
        fullShadowAssassins: List<LivingEntity>,
    ): Boolean {
        var drew = false
        val forward = client.gameRenderer.mainCamera().forwardVector()
        for (entity in entities) {
            val removed = entity.isRemoved()
            val alive = entity.isAlive()
            val shadowAssassin = fullShadowAssassins.contains(entity)
            tracerDiagnostics?.consider(removed, alive, shadowAssassin)
            if (removed || !alive) continue
            val box = if (shadowAssassin) {
                DungeonMobEspSupport.fullShadowAssassinBounds(entity, partialTick) ?: entity.getBoundingBox()
            } else {
                entity.getBoundingBox()
            }
            val centerX = box.getCenter().x
            val centerZ = box.getCenter().z
            var ringHeights: DoubleArray? = null
            if (style.value() == "Ring") {
                val feet = box.minY + 0.05
                var head = minOf(box.maxY - 0.05, feet + ringHeight.value())
                if (head < feet) head = feet
                ringHeights = PestAnimation.ringPositions(
                    feet, head, elapsedTicks, ringSpeed.value().toDouble(), ringCount.intValue(),
                )
                for (y in ringHeights) {
                    EspRenderer.renderRing(
                        pose, buffers, centerX - camera.x, y - camera.y, centerZ - camera.z,
                        ringRadius.value(), outline.argb(), ringLineWidth.value(), depth.value(),
                    )
                    if (shadowAssassin) tracerDiagnostics?.shadowAssassinWorldSubmission("ring")
                    drew = true
                }
            } else if (style.value() != "2D Box") {
                val fillColor = if (style.value() == "Chams") fill.argb() else 0
                EspRenderer.renderBox(
                    pose, buffers, box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z,
                    box.getXsize(), box.getYsize(), box.getZsize(), fillColor, outline.argb(),
                    if (lineWidth.value() > 0) lineWidth.value() else defaultLineWidth, depth.value(),
                )
                if (shadowAssassin) tracerDiagnostics?.shadowAssassinWorldSubmission("box")
                drew = true
            }
            if (tracer != null && tracer.value()) {
                var target = Vec3(centerX, box.getCenter().y, centerZ)
                if (ringHeights != null) {
                    val ring = PestAnimation.closestRingTarget(
                        centerX, centerZ, camera.x, camera.y, camera.z, ringRadius.value(), ringHeights,
                    )
                    target = Vec3(ring.x, ring.y, ring.z)
                }
                val endpoints = BlockEspTracerGeometry.endpoints(camera, forward, target, 0.5)
                val start = endpoints.start
                val end = endpoints.end
                val verticesSubmitted = hasLineVertices(start, end)
                EspRenderer.renderLine(
                    pose, buffers, start.x, start.y, start.z,
                    end.x, end.y, end.z,
                    tracerColor!!.argb(), tracerWidth!!.value(), depth.value(),
                )
                tracerDiagnostics?.submit(
                    entity, start, end, verticesSubmitted, shadowAssassin,
                )
                drew = true
            }
        }
        return drew
    }

    private fun hasLineVertices(start: Vec3, end: Vec3): Boolean {
        val segment = end.subtract(start)
        val x = segment.x.toFloat()
        val y = segment.y.toFloat()
        val z = segment.z.toFloat()
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        return length != 0.0f
    }

    private fun reportTracerDiagnostic(diagnostic: TracerRenderDiagnostics, batchesFlushed: Boolean) {
        if (!isEnabled()) {
            lastTracerDiagnostic = ""
            lastTracerDiagnosticTick = Long.MIN_VALUE
            return
        }
        val client = Minecraft.getInstance()
        val tick = client.level?.getGameTime() ?: 0L
        if (lastTracerDiagnosticTick != Long.MIN_VALUE && tick - lastTracerDiagnosticTick < 20) return
        val state = diagnostic.describe(batchesFlushed)
        if (state == lastTracerDiagnostic && tick - lastTracerDiagnosticTick < 100) return
        lastTracerDiagnostic = state
        lastTracerDiagnosticTick = tick
        GeilerAddonsLog.write(Category.F7, name(), tick, "miniboss tracer: " + state)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isActive()) return
        val client = Minecraft.getInstance()
        val player = client.player
        val felRoomClear = DungeonMapRoomDetector.currentRoomCleared()
        val camera = client.gameRenderer.mainCamera()
        val partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        val hudDiagnostics = HudRenderDiagnostics(
            settings.minibossEnabled.value(), settings.minibossOnlyStarred.value(),
            settings.minibossOnlyRoom.value(), settings.minibossRange.intValue(),
            settings.minibossStyle.value(), settings.depthCheck.value(),
        )
        renderHudGroup(
            client, graphics, camera, partialTick, starredMatches, settings.starredStyle,
            settings.depthCheck, settings.starredOutline, settings.starredFill,
            settings.starredLineWidth, emptyList(), null,
        )
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFels.value()) {
            renderHudGroup(
                client, graphics, camera, partialTick, currentRoomFels(client), settings.starredStyle,
                settings.depthCheck, settings.starredOutline, settings.starredFill,
                settings.starredLineWidth, emptyList(), null,
            )
        }
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value() &&
            settings.felStyle.value() == "2D Box"
        ) {
            for (entity in stationaryFelSkulls) {
                if (entity.isRemoved() || !entity.isAlive() ||
                    settings.depthCheck.value() && !DungeonMobEspSupport.visibleFromPlayer(
                        client, Vec3(entity.getX(), FelSkullMarkerGeometry.bounds(entity).centerY(), entity.getZ()),
                    )
                ) continue
                val bounds = FelSkullMarkerGeometry.bounds(entity)
                val box = AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())
                DungeonMobEspSupport.draw2dBox(
                    graphics, camera, entity, box, settings.felFill.argb(), settings.felOutline.argb(),
                    maxOf(1, Math.round(settings.felLineWidth.value())),
                )
            }
        }
        renderHudGroup(
            client, graphics, camera, partialTick, minibossMatches, settings.minibossStyle,
            settings.depthCheck, settings.minibossOutline, settings.minibossFill, settings.minibossLineWidth,
            shadowAssassinMatches, hudDiagnostics,
        )
        renderRememberedHudGroup(
            client, graphics, camera, DungeonMobTargetMemory.Group.STARRED, partialTick,
            settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
            settings.starredLineWidth, null,
        )
        renderRememberedHudGroup(
            client, graphics, camera, DungeonMobTargetMemory.Group.MINIBOSS, partialTick,
            settings.minibossStyle, settings.depthCheck, settings.minibossOutline, settings.minibossFill,
            settings.minibossLineWidth, hudDiagnostics,
        )
        renderRememberedHudGroup(
            client, graphics, camera, DungeonMobTargetMemory.Group.FEL_MOVING, partialTick,
            settings.starredStyle, settings.depthCheck, settings.starredOutline, settings.starredFill,
            settings.starredLineWidth, null,
        )
        if (!felRoomClear && settings.felsEnabled.value() && settings.highlightFelSkulls.value() &&
            settings.felStyle.value() == "2D Box"
        ) {
            val roomKey = currentRoomKey(client)
            val maxRangeSquared =
                settings.starredRange.intValue().toDouble() * settings.starredRange.intValue()
            for (target in rememberedTargets) {
                if (target.group() != DungeonMobTargetMemory.Group.FEL_SKULL ||
                    !java.util.Objects.equals(roomKey, target.roomKey())
                ) continue
                val bounds = target.bounds()
                if (player!!.distanceToSqr(bounds.centerX(), bounds.centerY(), bounds.centerZ()) > maxRangeSquared ||
                    settings.depthCheck.value() && !DungeonMobEspSupport.visibleFromPlayer(
                        client, Vec3(bounds.centerX(), bounds.centerY(), bounds.centerZ()),
                    )
                ) continue
                val box = AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())
                DungeonMobEspSupport.draw2dBox(
                    graphics, camera, null, box, settings.felFill.argb(), settings.felOutline.argb(),
                    maxOf(1, Math.round(settings.felLineWidth.value())),
                )
            }
        }
        reportHudDiagnostic(hudDiagnostics)
    }

    private fun reportHudDiagnostic(diagnostic: HudRenderDiagnostics) {
        if (!isEnabled()) {
            lastHudDiagnostic = ""
            lastHudDiagnosticTick = Long.MIN_VALUE
            return
        }
        val client = Minecraft.getInstance()
        val tick = client.level?.getGameTime() ?: 0L
        if (lastHudDiagnosticTick != Long.MIN_VALUE && tick - lastHudDiagnosticTick < 20) return
        val state = diagnostic.describe()
        if (state == lastHudDiagnostic && tick - lastHudDiagnosticTick < 100) return
        lastHudDiagnostic = state
        lastHudDiagnosticTick = tick
        GeilerAddonsLog.write(Category.F7, name(), tick, "miniboss HUD: " + state)
    }

    private fun renderRememberedHudGroup(
        client: Minecraft,
        graphics: GuiGraphicsExtractor,
        camera: net.minecraft.client.Camera,
        group: DungeonMobTargetMemory.Group,
        partialTick: Float,
        style: ChoiceSetting,
        depth: BooleanSetting,
        outline: ColorSetting,
        fill: ColorSetting,
        lineWidth: NumberSetting,
        diagnostics: HudRenderDiagnostics?,
    ) {
        val player = client.player ?: return
        val maxRange = if (group == DungeonMobTargetMemory.Group.MINIBOSS) {
            settings.minibossRange.intValue().toDouble()
        } else {
            settings.starredRange.intValue().toDouble()
        }
        for (target in rememberedTargets) {
            if (target.group() != group) continue
            val shadowAssassin = group == DungeonMobTargetMemory.Group.MINIBOSS &&
                DungeonMobEspSupport.isShadowAssassin(target.label())
            diagnostics?.rememberedCandidate(shadowAssassin)
            if (style.value() != "2D Box") continue
            if (!shouldRenderRememberedRoom(client, target)) {
                diagnostics?.rememberedRoomRejected()
                continue
            }
            val cached = target.bounds()
            val bounds = if (target.fullShadowAssassin()) {
                rememberedShadowAssassinBounds(client, target, partialTick)
            } else {
                null
            } ?: AABB(cached.minX(), cached.minY(), cached.minZ(), cached.maxX(), cached.maxY(), cached.maxZ())
            val center = bounds.getCenter()
            if (DungeonMobEspSupport.isOutsideRememberedHudRange(
                    group, player.distanceToSqr(center.x, center.y, center.z), maxRange,
                )
            ) {
                diagnostics?.rememberedRangeRejected()
                continue
            }
            val point = center
            if (depth.value() && !DungeonMobEspSupport.visibleFromPlayer(client, point)) {
                diagnostics?.rememberedDepthRejected()
                continue
            }
            val submission = DungeonMobEspSupport.draw2dBox(
                graphics, camera, null, bounds, fill.argb(), outline.argb(),
                maxOf(1, Math.round(lineWidth.value())),
            )
            diagnostics?.rememberedBoxResult(submission, shadowAssassin)
        }
    }

    private fun shouldRenderRememberedRoom(
        client: Minecraft,
        target: DungeonMobTargetMemory.Target,
    ): Boolean {
        val currentRoomOnly = when (target.group()) {
            DungeonMobTargetMemory.Group.STARRED -> settings.starredOnlyRoom.value()
            DungeonMobTargetMemory.Group.MINIBOSS -> settings.minibossOnlyRoom.value()
            DungeonMobTargetMemory.Group.FEL_SKULL, DungeonMobTargetMemory.Group.FEL_MOVING -> true
        }
        return !currentRoomOnly || java.util.Objects.equals(currentRoomKey(client), target.roomKey())
    }

    private fun renderHudGroup(
        client: Minecraft,
        graphics: GuiGraphicsExtractor,
        camera: net.minecraft.client.Camera,
        partialTick: Float,
        entities: List<LivingEntity>,
        style: ChoiceSetting,
        depth: BooleanSetting,
        outline: ColorSetting,
        fill: ColorSetting,
        lineWidth: NumberSetting,
        fullShadowAssassins: List<LivingEntity>?,
        diagnostics: HudRenderDiagnostics?,
    ) {
        val shadowAssassins = fullShadowAssassins ?: emptyList()
        diagnostics?.liveCandidates(entities.size, shadowAssassins.size)
        if (style.value() != "2D Box") return
        for (entity in entities) {
            val shadowAssassin = shadowAssassins.contains(entity)
            if (entity.isRemoved()) {
                diagnostics?.liveRemoved()
                continue
            }
            if (!entity.isAlive()) {
                diagnostics?.liveNotAlive()
                continue
            }
            if (depth.value() && !DungeonMobEspSupport.visibleFromPlayer(client, entity)) {
                diagnostics?.liveDepthRejected()
                continue
            }
            val bounds = if (shadowAssassin) {
                DungeonMobEspSupport.fullShadowAssassinBounds(entity, partialTick)
            } else {
                entity.getBoundingBox()
            }
            val submission = DungeonMobEspSupport.draw2dBox(
                graphics, camera, entity, bounds, fill.argb(), outline.argb(),
                maxOf(1, Math.round(lineWidth.value())),
            )
            diagnostics?.liveBoxResult(submission, shadowAssassin)
        }
    }

    /** Restores both old module groups once; saved settings on this module always win. */
    fun restoreLegacySettings(
        numbers: Map<String, Float>,
        toggles: Map<String, Boolean>,
        choices: Map<String, String>,
        colors: Map<String, IntArray>,
    ) {
        restoreNumber(numbers, "Starred Mob ESP.Range", settings.starredRange)
        val depthKey = configName() + "." + settings.depthCheck.name()
        if (!toggles.containsKey(depthKey)) {
            val legacyDepth = toggles["Starred Mob ESP.Depth Check"] ?: toggles["Miniboss ESP.Depth Check"]
            if (legacyDepth != null) settings.depthCheck.setValue(legacyDepth)
        }
        restoreChoice(choices, "Starred Mob ESP.Style", settings.starredStyle)
        restoreColor(colors, "Starred Mob ESP.Outline Color", settings.starredOutline)
        restoreColor(colors, "Starred Mob ESP.Fill Color", settings.starredFill)
        restoreNumber(numbers, "Miniboss ESP.Range", settings.minibossRange)
        restoreToggle(toggles, "Miniboss ESP.Only Current Room", settings.minibossOnlyRoom)
        restoreChoice(choices, "Miniboss ESP.Style", settings.minibossStyle)
        restoreToggle(toggles, "Miniboss ESP.Only Starred Minibosses", settings.minibossOnlyStarred)
        restoreColor(colors, "Miniboss ESP.Outline Color", settings.minibossOutline)
        restoreColor(colors, "Miniboss ESP.Fill Color", settings.minibossFill)
        restoreToggle(toggles, "Miniboss ESP.Tracer", settings.minibossTracer)
        restoreColor(colors, "Miniboss ESP.Tracer Color", settings.minibossTracerColor)
        restoreNumber(numbers, "Miniboss ESP.Tracer Width", settings.minibossTracerWidth)
        restoreNumber(numbers, "Dungeon Mob ESP.Starred Line Width", settings.starredLineWidth)
        restoreNumber(numbers, "Dungeon Mob ESP.Miniboss Line Width", settings.minibossLineWidth)
    }

    private fun restoreNumber(values: Map<String, Float>, oldKey: String, setting: NumberSetting) {
        val newKey = configName() + "." + setting.name()
        if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.getValue(oldKey))
    }

    private fun restoreToggle(values: Map<String, Boolean>, oldKey: String, setting: BooleanSetting) {
        val newKey = configName() + "." + setting.name()
        if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.getValue(oldKey))
    }

    private fun restoreChoice(values: Map<String, String>, oldKey: String, setting: ChoiceSetting) {
        val newKey = configName() + "." + setting.name()
        if (!values.containsKey(newKey) && values.containsKey(oldKey)) setting.setValue(values.getValue(oldKey))
    }

    private fun restoreColor(values: Map<String, IntArray>, oldKey: String, setting: ColorSetting) {
        val newKey = configName() + "." + setting.name()
        if (values.containsKey(newKey)) return
        val rgba = values[oldKey]
        if (rgba != null && rgba.size == 4) setting.set(rgba[0], rgba[1], rgba[2], rgba[3])
    }

    private data class SpecialTarget(
        private val targetEntity: LivingEntity,
        private val targetFillColor: Int,
        private val targetOutlineColor: Int,
    ) {
        fun entity(): LivingEntity = targetEntity
        fun fillColor(): Int = targetFillColor
        fun outlineColor(): Int = targetOutlineColor
    }

    private class Settings {
        val starredEnabled = BooleanSetting("Starred Group Enabled", true)
        val starredOnlyRoom = BooleanSetting("Starred Only Current Room", false)
        val starredRange = NumberSetting("Starred Range", 8f, 128f, 128f, true)
        val depthCheck = BooleanSetting("Depth Check", false)
        val starredStyle = ChoiceSetting("Starred Style", "Chams", "Chams", "3D Box", "2D Box", "Ring")
        val starredOutline = ColorSetting("Starred Outline Color", 255, 255, 255, 255)
        val starredFill = ColorSetting("Starred Fill Color", 0, 0, 0, 255)
        val starredLineWidth = NumberSetting("Starred Outline Width", 0.5f, 25.0f, 5.0f)
        val starredRingCount = NumberSetting("Starred Ring Count", 1f, 8f, 3f, true)
        val starredRingHeight = NumberSetting("Starred Ring Height", 0.4f, 6.0f, 2.0f)
        val starredRingRadius = NumberSetting("Starred Ring Radius", 0.1f, 3.5f, 0.65f)
        val starredRingSpeed = NumberSetting("Starred Ring Speed", 0.1f, 5.0f, 0.8f)
        val starredRingLineWidth = NumberSetting("Starred Ring Line Width", 0.5f, 5.0f, 1.5f)
        val minibossEnabled = BooleanSetting("Minibosses Enabled", true)
        val minibossRange = NumberSetting("Miniboss Range", 8f, 128f, 64f, true)
        val minibossOnlyRoom = BooleanSetting("Miniboss Only Current Room", false)
        val minibossStyle = ChoiceSetting("Miniboss Style", "Chams", "Chams", "3D Box", "2D Box", "Ring")
        val minibossOnlyStarred = BooleanSetting("Only Starred Minibosses", false)
        val minibossOutline = ColorSetting("Miniboss Outline Color", 0, 0, 0, 255)
        val minibossFill = ColorSetting("Miniboss Fill Color", 255, 255, 255, 255)
        val minibossLineWidth = NumberSetting("Miniboss Outline Width", 0.5f, 25.0f, 5.0f)
        val minibossTracer = BooleanSetting("Miniboss Tracer", true)
        val minibossTracerColor = ColorSetting("Miniboss Tracer Color", 255, 255, 255, 220)
        val minibossTracerWidth = NumberSetting("Miniboss Tracer Width", 0.5f, 5.0f, 1.5f)
        val minibossRingCount = NumberSetting("Miniboss Ring Count", 1f, 8f, 3f, true)
        val minibossRingHeight = NumberSetting("Miniboss Ring Height", 0.4f, 6.0f, 2.0f)
        val minibossRingRadius = NumberSetting("Miniboss Ring Radius", 0.1f, 3.5f, 0.8f)
        val minibossRingSpeed = NumberSetting("Miniboss Ring Speed", 0.1f, 5.0f, 0.8f)
        val minibossRingLineWidth = NumberSetting("Miniboss Ring Line Width", 0.5f, 5.0f, 1.5f)
        val appearanceCapture = BooleanSetting("Capture Miniboss Appearances", false)
        val manualAppearanceLabel = TextSetting("Optional Manual Appearance Label", "", 80)
        val captureCrosshairAppearance = ModuleAction(
            "Capture Crosshair Player Profile",
            "Optional manual fallback. Automatic capture does not require a crosshair or entered label.",
            Runnable { INSTANCE.captureCrosshairAppearance() },
        )
        val debug = BooleanSetting("Debug Target Matching", false)
        val felsEnabled = BooleanSetting("Fels Enabled", true)
        val felOnlyRoom = BooleanSetting("Fels Only Current Room", false)
        val highlightFels = BooleanSetting("Highlight Starred Fels", true)
        val highlightFelSkulls = BooleanSetting(
            "Highlight Hidden Fel Skulls", "Highlight Stationary Fels", true,
        )
        val felStyle = ChoiceSetting("Fel Style", "Chams", "Chams", "3D Box", "2D Box", "Ring")
        val felOutline = ColorSetting("Fel Outline Color", 255, 255, 255, 255)
        val felFill = ColorSetting("Fel Fill Color", 255, 255, 255, 255)
        val felLineWidth = NumberSetting("Fel Outline Width", 0.5f, 25.0f, 5.0f)
        val felRingCount = NumberSetting("Fel Ring Count", 1f, 8f, 3f, true)
        val felRingHeight = NumberSetting("Fel Ring Height", 0.4f, 6.0f, 2.0f)
        val felRingRadius = NumberSetting("Fel Ring Radius", 0.1f, 3.5f, 0.65f)
        val felRingSpeed = NumberSetting("Fel Ring Speed", 0.1f, 5.0f, 0.8f)
        val felRingLineWidth = NumberSetting("Fel Ring Line Width", 0.5f, 5.0f, 1.5f)
        val felSkullTracer = BooleanSetting("Fel Skull Tracer", true)
        val felTracerColor = ColorSetting("Fel Tracer Color", 255, 255, 255, 220)
        val felTracerWidth = NumberSetting("Fel Tracer Width", 0.5f, 5.0f, 1.5f)
        val specialWithers = BooleanSetting("F7 Wither Boss ESP", true)
        val specialMimic = BooleanSetting("Known Mimic Chest ESP", true)
        val mimicFill = ColorSetting("Mimic Chest Fill", 209, 29, 5, 160)
        val mimicOutline = ColorSetting("Mimic Chest Outline", 209, 29, 5, 255)
        val partyClasses = BooleanSetting("Party Only Class Highlight", false)
        val specialRange = NumberSetting("Highlight Range", 8f, 128f, 64f, true)
        val specialMobFill = ColorSetting("Special Mob Fill", 255, 80, 35, 48)
        val specialMobOutline = ColorSetting("Special Mob Outline", 255, 80, 35, 255)
        val tank = specialClassColor("Tank Color", DungeonClass.TANK)
        val healer = specialClassColor("Healer Color", DungeonClass.HEALER)
        val mage = specialClassColor("Mage Color", DungeonClass.MAGE)
        val berserk = specialClassColor("Berserk Color", DungeonClass.BERSERK)
        val archer = specialClassColor("Archer Color", DungeonClass.ARCHER)
        val partyFill = ColorSetting("Party Class Fill", 0, 0, 0, 24)

        private fun specialClassColor(name: String, dungeonClass: DungeonClass): ColorSetting {
            val color = DungeonSpecialMobRules.defaultClassColor(dungeonClass)
            return ColorSetting(name, color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF, 255)
        }
    }

    /** Remembers a previously identified Fel body only while its actual client entity stays verifiable. */
    private class KnownFelBody(entity: LivingEntity) {
        val uuid: UUID = entity.getUUID()
        var entityId: Int = entity.getId()
        var starred = false

        init {
            observe(entity, false)
        }

        fun observe(entity: LivingEntity, hasStar: Boolean) {
            entityId = entity.getId()
            starred = starred || hasStar
        }
    }

    private class TracerRenderDiagnostics(
        private val enabled: Boolean,
        private val colorArgb: Int,
        private val alpha: Int,
        private val width: Float,
        style: String?,
        range: Int,
        private val onlyCurrentRoom: Boolean,
        private val onlyStarred: Boolean,
        private val depthTested: Boolean,
        private val shadowAssassinCandidates: Int,
    ) {
        private val style = style ?: ""
        private var snapshotCandidates = 0
        private var liveEligible = 0
        private var removed = 0
        private var notAlive = 0
        private var renderLineCalls = 0
        private var vertexSubmissions = 0
        private var shadowAssassinSnapshotCandidates = 0
        private var shadowAssassinLiveEligible = 0
        private var shadowAssassinWorldBoxSubmissions = 0
        private var shadowAssassinWorldRingSubmissions = 0
        private var shadowAssassinLineCalls = 0
        private var shadowAssassinVertexSubmissions = 0
        private var sampleIsShadowAssassin = false
        private var sampleStart: Vec3? = null
        private var sampleEndpoint: Vec3? = null
        private var sampleEntity = "none"
        private val configuredRange = range

        fun consider(isRemoved: Boolean, alive: Boolean, shadowAssassin: Boolean) {
            snapshotCandidates++
            if (isRemoved) removed++
            if (!alive) notAlive++
            if (!isRemoved && alive) liveEligible++
            if (shadowAssassin) {
                shadowAssassinSnapshotCandidates++
                if (!isRemoved && alive) shadowAssassinLiveEligible++
            }
        }

        fun shadowAssassinWorldSubmission(kind: String) {
            if (kind == "ring") shadowAssassinWorldRingSubmissions++ else shadowAssassinWorldBoxSubmissions++
        }

        fun submit(entity: LivingEntity, start: Vec3, endpoint: Vec3, verticesWritten: Boolean, shadowAssassin: Boolean) {
            renderLineCalls++
            if (verticesWritten) vertexSubmissions++
            if (shadowAssassin) {
                shadowAssassinLineCalls++
                if (verticesWritten) shadowAssassinVertexSubmissions++
            }
            if (sampleEndpoint == null || shadowAssassin && !sampleIsShadowAssassin) {
                sampleStart = start
                sampleEndpoint = endpoint
                sampleEntity = entity.javaClass.simpleName + "#" + entity.getId()
                sampleIsShadowAssassin = shadowAssassin
            }
        }

        fun describe(batchesFlushed: Boolean): String {
            val endpoints = if (sampleEndpoint == null) "none" else String.format(
                Locale.ROOT, "(%.2f,%.2f,%.2f)->(%.2f,%.2f,%.2f)",
                sampleStart!!.x, sampleStart!!.y, sampleStart!!.z,
                sampleEndpoint!!.x, sampleEndpoint!!.y, sampleEndpoint!!.z,
            )
            return String.format(
                Locale.ROOT,
                "enabled=%s, color=0x%08X, alpha=%d, width=%.2f, style=%s, configuredRangeIgnored=%d, " +
                    "onlyCurrentRoom=%s, onlyStarred=%s, depthTested=%s, " +
                    "pipeline=%s, candidates=%d, eligible=%d, removed=%d, notAlive=%d, " +
                    "renderLineCalls=%d, vertexSubmissions=%d, SAList=%d, SACandidates=%d, SAEligible=%d, " +
                    "SAWorldBoxes=%d, SAWorldRings=%d, SALineCalls=%d, SAVertexSubmissions=%d, " +
                    "sampleEntity=%s, endpoints=%s, targetDistance=%.2f, batchesFlushed=%s",
                enabled, colorArgb, alpha, width, style, configuredRange, onlyCurrentRoom, onlyStarred, depthTested,
                if (depthTested) "DEPTH_LINES" else "ESP_LINES", snapshotCandidates, liveEligible,
                removed, notAlive, renderLineCalls, vertexSubmissions, shadowAssassinCandidates,
                shadowAssassinSnapshotCandidates, shadowAssassinLiveEligible,
                shadowAssassinWorldBoxSubmissions, shadowAssassinWorldRingSubmissions,
                shadowAssassinLineCalls, shadowAssassinVertexSubmissions, sampleEntity, endpoints,
                if (sampleEndpoint == null || sampleStart == null) -1.0 else sampleEndpoint!!.distanceTo(sampleStart!!),
                batchesFlushed,
            )
        }
    }

    private class HudRenderDiagnostics(
        private val minibossEnabled: Boolean,
        private val onlyStarred: Boolean,
        private val onlyCurrentRoom: Boolean,
        private val configuredRange: Int,
        style: String?,
        private val depthTested: Boolean,
    ) {
        private val style = style ?: ""
        private var liveCandidates = 0
        private var liveShadowAssassinCandidates = 0
        private var liveRemoved = 0
        private var liveNotAlive = 0
        private var liveDepthRejected = 0
        private var liveBoxAttempts = 0
        private var liveBoxGeometrySubmissions = 0
        private var liveShadowAssassinGeometrySubmissions = 0
        private var liveProjectionRejected = 0
        private var liveOutsideViewport = 0
        private var liveTransparent = 0
        private var liveNoDrawableArea = 0
        private var liveInputUnavailable = 0
        private var rememberedCandidates = 0
        private var rememberedShadowAssassinCandidates = 0
        private var rememberedRoomRejected = 0
        private var rememberedRangeRejected = 0
        private var rememberedDepthRejected = 0
        private var rememberedBoxAttempts = 0
        private var rememberedBoxGeometrySubmissions = 0
        private var rememberedShadowAssassinGeometrySubmissions = 0
        private var rememberedProjectionRejected = 0
        private var rememberedOutsideViewport = 0
        private var rememberedTransparent = 0
        private var rememberedNoDrawableArea = 0
        private var rememberedInputUnavailable = 0

        fun liveCandidates(candidates: Int, shadowAssassins: Int) {
            liveCandidates += candidates
            liveShadowAssassinCandidates += shadowAssassins
        }

        fun liveRemoved() {
            liveRemoved++
        }

        fun liveNotAlive() {
            liveNotAlive++
        }

        fun liveDepthRejected() {
            liveDepthRejected++
        }

        fun liveBoxResult(result: DungeonMobEspSupport.HudBoxSubmission, shadowAssassin: Boolean) {
            liveBoxAttempts++
            when (result) {
                DungeonMobEspSupport.HudBoxSubmission.SUBMITTED -> {
                    liveBoxGeometrySubmissions++
                    if (shadowAssassin) liveShadowAssassinGeometrySubmissions++
                }
                DungeonMobEspSupport.HudBoxSubmission.PROJECTION_REJECTED -> liveProjectionRejected++
                DungeonMobEspSupport.HudBoxSubmission.OUTSIDE_VIEWPORT -> liveOutsideViewport++
                DungeonMobEspSupport.HudBoxSubmission.TRANSPARENT -> liveTransparent++
                DungeonMobEspSupport.HudBoxSubmission.NO_DRAWABLE_AREA -> liveNoDrawableArea++
                DungeonMobEspSupport.HudBoxSubmission.INPUT_UNAVAILABLE -> liveInputUnavailable++
            }
        }

        fun rememberedCandidate(shadowAssassin: Boolean) {
            rememberedCandidates++
            if (shadowAssassin) rememberedShadowAssassinCandidates++
        }

        fun rememberedRoomRejected() {
            rememberedRoomRejected++
        }

        fun rememberedRangeRejected() {
            rememberedRangeRejected++
        }

        fun rememberedDepthRejected() {
            rememberedDepthRejected++
        }

        fun rememberedBoxResult(result: DungeonMobEspSupport.HudBoxSubmission, shadowAssassin: Boolean) {
            rememberedBoxAttempts++
            when (result) {
                DungeonMobEspSupport.HudBoxSubmission.SUBMITTED -> {
                    rememberedBoxGeometrySubmissions++
                    if (shadowAssassin) rememberedShadowAssassinGeometrySubmissions++
                }
                DungeonMobEspSupport.HudBoxSubmission.PROJECTION_REJECTED -> rememberedProjectionRejected++
                DungeonMobEspSupport.HudBoxSubmission.OUTSIDE_VIEWPORT -> rememberedOutsideViewport++
                DungeonMobEspSupport.HudBoxSubmission.TRANSPARENT -> rememberedTransparent++
                DungeonMobEspSupport.HudBoxSubmission.NO_DRAWABLE_AREA -> rememberedNoDrawableArea++
                DungeonMobEspSupport.HudBoxSubmission.INPUT_UNAVAILABLE -> rememberedInputUnavailable++
            }
        }

        fun describe(): String =
            "enabled=$minibossEnabled, style=$style, configuredRange=$configuredRange" +
                ", onlyStarred=$onlyStarred, onlyCurrentRoom=$onlyCurrentRoom" +
                ", depthTested=$depthTested, liveCandidates=$liveCandidates" +
                ", liveShadowAssassinCandidates=$liveShadowAssassinCandidates" +
                ", liveRemoved=$liveRemoved, liveNotAlive=$liveNotAlive" +
                ", liveDepthRejected=$liveDepthRejected, liveBoxAttempts=$liveBoxAttempts" +
                ", liveBoxGeometrySubmissions=$liveBoxGeometrySubmissions" +
                ", liveShadowAssassinGeometrySubmissions=$liveShadowAssassinGeometrySubmissions" +
                ", liveProjectionRejected=$liveProjectionRejected" +
                ", liveOutsideViewport=$liveOutsideViewport, liveTransparent=$liveTransparent" +
                ", liveNoDrawableArea=$liveNoDrawableArea, liveInputUnavailable=$liveInputUnavailable" +
                ", rememberedCandidates=$rememberedCandidates" +
                ", rememberedShadowAssassinCandidates=$rememberedShadowAssassinCandidates" +
                ", rememberedRoomRejected=$rememberedRoomRejected" +
                ", rememberedRangeRejected=$rememberedRangeRejected" +
                ", rememberedDepthRejected=$rememberedDepthRejected" +
                ", rememberedBoxAttempts=$rememberedBoxAttempts" +
                ", rememberedBoxGeometrySubmissions=$rememberedBoxGeometrySubmissions" +
                ", rememberedShadowAssassinGeometrySubmissions=$rememberedShadowAssassinGeometrySubmissions" +
                ", rememberedProjectionRejected=$rememberedProjectionRejected" +
                ", rememberedOutsideViewport=$rememberedOutsideViewport" +
                ", rememberedTransparent=$rememberedTransparent" +
                ", rememberedNoDrawableArea=$rememberedNoDrawableArea" +
                ", rememberedInputUnavailable=$rememberedInputUnavailable"
    }
}
