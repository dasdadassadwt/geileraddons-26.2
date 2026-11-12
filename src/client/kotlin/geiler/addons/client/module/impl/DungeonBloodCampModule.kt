package geiler.addons.client.module.impl

import com.mojang.authlib.properties.Property
import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.odin.DungeonBloodCampTracker
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.phys.Vec3
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Odin 0.3.2 Blood Camp behavior, with its Move HUD intentionally omitted. */
class DungeonBloodCampModule private constructor() : Module(
    "Blood Camp",
    "Predicts Blood Camp mob spawn positions and the Watcher's move timing.",
    Category.F7,
    false,
    predictionDropdown,
    movePrediction,
    moveMessage,
    partyMoveMessage,
    killTitle,
    bloodAssistDropdown,
    bloodCampAssist,
    spawnColor,
    finalColor,
    positionColor,
    boxSize,
    line,
    timeLeft,
    advanced,
    offset,
    spawnTick,
    interpolation,
    pingOffset,
    manualMobOffset,
    watcherBar,
), ModulePreview {
    private val tracker = DungeonBloodCampTracker()
    private var candidates: List<DungeonBloodCampTracker.CandidateReading> = emptyList()
    private var observedLevel: ClientLevel? = null
    private var observedConnection: ClientPacketListener? = null
    private var observedSession = false
    private var clientTick = 0L
    private var diagnosticTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var detailWindow = Long.MIN_VALUE
    private var detailsThisWindow = 0
    private var detailsSinceStatus = 0
    private var eventTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedDetails = 0
    private var openerStartedNanos = -1L
    private var openerStartedTick = -1L
    private var killTitleAtTick = -1L
    private var lastPredictedMoveTicks = -1
    private var watcherTextureMatches = 0
    private var mobTextureMatches = 0
    private var watcherDistanceRejects = 0
    private var otherRejects = 0
    private var entityResets = 0
    private var positionBoxSubmissions = 0
    private var spawnBoxSubmissions = 0
    private var finalBoxSubmissions = 0
    private var lineSubmissions = 0
    private var labelSubmissions = 0
    private var watcherBarUpdates = 0

    init {
        group(
            SettingGroup.switched("Prediction", predictionDropdown, movePrediction, moveMessage, partyMoveMessage, killTitle),
            SettingGroup.switched(
                "Blood Camp Assist",
                bloodAssistDropdown,
                bloodCampAssist,
                positionColor,
                spawnColor,
                finalColor,
                boxSize,
                line,
                timeLeft,
            ).containing(
                SettingGroup.switchedFolded(
                    "Advanced",
                    advanced,
                    offset,
                    spawnTick,
                    interpolation,
                    pingOffset,
                    manualMobOffset,
                ),
            ),
            SettingGroup(null, watcherBar),
        )
    }

    fun bloodCampAssistSetting(): BooleanSetting = bloodCampAssist

    fun positionColorSetting(): ColorSetting = positionColor

    fun spawnColorSetting(): ColorSetting = spawnColor

    fun boxSizeSetting(): NumberSetting = boxSize

    fun lineSetting(): BooleanSetting = line

    fun timeLeftSetting(): BooleanSetting = timeLeft

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === predictionDropdown || setting === bloodAssistDropdown || setting === watcherBar) return true
        if (setting === bloodCampAssist) return bloodAssistDropdown.value()
        if (setting === movePrediction) return predictionDropdown.value()
        if (setting === moveMessage || setting === partyMoveMessage || setting === killTitle) {
            return predictionDropdown.value() && movePrediction.value()
        }
        if (setting === positionColor || setting === spawnColor || setting === finalColor || setting === boxSize ||
            setting === line || setting === timeLeft || setting === advanced
        ) {
            return bloodAssistDropdown.value() && bloodCampAssist.value()
        }
        if (setting === offset || setting === spawnTick || setting === interpolation || setting === pingOffset) {
            return bloodAssistDropdown.value() && bloodCampAssist.value() && advanced.value()
        }
        if (setting === manualMobOffset) {
            return bloodAssistDropdown.value() && bloodCampAssist.value() && advanced.value() && !pingOffset.value()
        }
        return super.isSettingVisible(setting)
    }

    override fun onEnable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        clearState("module-enabled", true)
    }

    override fun onDisable() {
        clearState("module-disabled", true)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
    }

    /** Called once per client tick after DungeonContextTracker has refreshed its current phase. */
    fun tick(client: Minecraft?, clientTick: Long, nowNanos: Long) {
        this.clientTick = Math.max(0L, clientTick)
        diagnosticTick = this.clientTick
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        if (!isEnabled()) return
        if (client == null) return

        if (!observedSession) {
            observedSession = true
            observedLevel = client.level
            observedConnection = client.getConnection()
        } else if (observedLevel !== client.level || observedConnection !== client.getConnection()) {
            clearState("level-or-connection-change", true)
            observedLevel = client.level
            observedConnection = client.getConnection()
        }

        if (!DungeonContextTracker.isMobEspPhase()) {
            if (hasRuntimeState()) clearState("clear-phase-ended", true)
        } else {
            if (!bloodCampAssist.value() && (tracker.hasWatcher() || tracker.eligibleCount() > 0)) {
                noteReset("assist-disabled")
                tracker.clearEntities()
                candidates = emptyList()
            }
            candidates = if (bloodCampAssist.value()) {
                tracker.candidateSnapshot(trackerTimeNanos(), spawnTick.intValue())
            } else {
                emptyList()
            }
        }

        if (killTitleAtTick >= 0L && this.clientTick >= killTitleAtTick) {
            val scheduled = killTitleAtTick
            killTitleAtTick = -1L
            if (DungeonContextTracker.isMobEspPhase() && movePrediction.value() && killTitle.value()) {
                val gui = client.gui
                if (gui != null) {
                    gui.hud.setTimes(0, 40, 0)
                    gui.hud.setTitle(Component.literal("Kill Mobs"))
                    detail("move-title result=submitted,scheduledTick=$scheduled")
                } else {
                    detail("move-title result=suppressed,reason=gui-unavailable")
                }
            } else {
                detail("move-title result=suppressed,reason=setting-or-phase-disabled")
            }
        }

        if (lastStatusTick == Long.MIN_VALUE || this.clientTick - lastStatusTick >= 100L) {
            writeStatus(nowNanos)
            lastStatusTick = this.clientTick
        }
    }

    /** Receives the already-normalized client chat line. */
    fun onChatMessage(rawMessage: String?, nowNanos: Long, clientTick: Long) {
        if (!isEnabled() || rawMessage == null || !DungeonContextTracker.isMobEspPhase()) return
        this.clientTick = Math.max(0L, clientTick)
        diagnosticTick = this.clientTick
        val message = ChatText.plain(rawMessage).trim()
        if (WATCHER_OPEN_LINES.contains(message)) {
            openerStartedNanos = nowNanos
            openerStartedTick = this.clientTick
            lastPredictedMoveTicks = -1
            detail("watcher-chat kind=open-line,elapsedStart=recorded")
            return
        }
        if (message != WATCHER_MOVE_LINE) return

        tracker.observeWatcherMoved()
        if (openerStartedTick < 0L) {
            detail("watcher-chat kind=move-line,wave=later,alerts=suppressed,reason=opening-line-missing")
            return
        }
        val elapsedTicks = Math.max(0L, this.clientTick - openerStartedTick)
        val elapsedSeconds = elapsedTicks / 20L
        val predictedTicks = movePredictionTicks(elapsedSeconds)
        lastPredictedMoveTicks = predictedTicks
        if (!movePrediction.value()) {
            killTitleAtTick = -1L
            detail(
                "watcher-chat kind=move-line,wave=later,predictedTicks=$predictedTicks" +
                    ",alerts=suppressed,reason=move-prediction-disabled",
            )
            return
        }

        val seconds = predictedTicks / 20.0f
        val formatted = String.format(Locale.ROOT, "%.1f", seconds)
        val client = Minecraft.getInstance()
        val gui = client.gui
        if (moveMessage.value() && gui != null) {
            gui.hud.getChat().addClientSystemMessage(
                Component.literal("[Blood Camp] Watcher will move in " + formatted + "s."),
            )
            detail("move-message result=submitted,seconds=$formatted")
        } else {
            detail("move-message result=suppressed,enabled=${moveMessage.value()}")
        }
        val connection = client.player?.connection
        if (partyMoveMessage.value() && connection != null) {
            connection.sendCommand("pc Watcher will move in " + formatted + "s.")
            detail("party-move-message result=submitted,seconds=$formatted")
        } else if (partyMoveMessage.value()) {
            detail("party-move-message result=suppressed,reason=connection-unavailable")
        }
        killTitleAtTick = if (killTitle.value()) this.clientTick + predictedTicks else -1L
        detail(
            "watcher-chat kind=move-line,wave=later,elapsedSeconds=$elapsedSeconds" +
                ",predictedTicks=$predictedTicks,titleScheduled=${killTitleAtTick >= 0L}",
        )
    }

    /** Called after vanilla applies a HEAD equipment packet on the client thread. */
    fun onHeadEquipment(entity: Entity?, slot: EquipmentSlot?, head: ItemStack?) {
        if (!isEnabled() || entity == null || slot != EquipmentSlot.HEAD) return
        val texture = textureHash(head)
        val watcherMatch = WATCHER_TEXTURES.contains(texture)
        val mobMatch = MOB_TEXTURES.contains(texture)
        if (watcherMatch) watcherTextureMatches++
        if (mobMatch) mobTextureMatches++
        if (!DungeonContextTracker.isMobEspPhase()) {
            if (watcherMatch || mobMatch) reject("equipment", entity.id, "outside-clear-phase", texture, Double.NaN)
            return
        }
        if (watcherMatch) {
            if (entity is Zombie && bloodCampAssist.value()) {
                if (!tracker.hasWatcher() || tracker.watcherEntityId() == entity.id) {
                    tracker.observeWatcher(entity.id, position(entity))
                    detail(
                        "watcher-texture-match id=${entity.id},texture=${shortHash(texture)}" +
                            ",outcome=tracked,pos=${positionText(position(entity))}",
                    )
                } else {
                    reject(
                        "watcher",
                        entity.id,
                        "watcher-already-tracked",
                        texture,
                        Math.sqrt(tracker.watcherPosition()!!.distanceSquared(position(entity))),
                    )
                }
            } else {
                val reason = if (entity is Zombie) "assist-disabled" else "not-zombie"
                reject("watcher", entity.id, reason, texture, Double.NaN)
            }
        }
        if (mobMatch || (head != null && head.`is`(Items.PLAYER_HEAD))) {
            if (entity !is ArmorStand) {
                if (mobMatch) reject("mob", entity.id, "not-armor-stand", texture, Double.NaN)
                return
            }
            if (!bloodCampAssist.value()) {
                if (mobMatch) reject("mob", entity.id, "assist-disabled", texture, Double.NaN)
                return
            }
            val check = tracker.observeCandidateHead(
                entity.id,
                true,
                true,
                head != null && head.`is`(Items.PLAYER_HEAD),
                mobMatch,
                position(entity),
            )
            if (check.accepted) {
                detail(
                    "mob-texture-match id=${entity.id},texture=${shortHash(texture)}" +
                        ",distance=${formatDistance(check.watcherDistance)},outcome=eligible",
                )
            } else {
                reject("mob", entity.id, check.reason!!, texture, check.watcherDistance)
            }
        }
    }

    /** Called at the tail of the vanilla movement handler after its absolute position is applied. */
    fun onEntityMove(entity: Entity?) {
        if (!isEnabled() || entity == null || !DungeonContextTracker.isMobEspPhase()) return
        if (entity.id == tracker.watcherEntityId() && entity is Zombie) {
            tracker.observeWatcher(entity.id, position(entity))
            return
        }
        if (entity !is ArmorStand) return
        if (!bloodCampAssist.value()) return

        val head = entity.getItemBySlot(EquipmentSlot.HEAD)
        val texture = textureHash(head)
        val mobMatch = MOB_TEXTURES.contains(texture)
        if (mobMatch) mobTextureMatches++
        if (!mobMatch || head == null || !head.`is`(Items.PLAYER_HEAD)) {
            if (tracker.tracksCandidate(entity.id)) {
                val rejected = tracker.observeCandidateHead(
                    entity.id,
                    true,
                    true,
                    head != null && head.`is`(Items.PLAYER_HEAD),
                    mobMatch,
                    position(entity),
                )
                reject("mob-move", entity.id, rejected.reason!!, texture, rejected.watcherDistance)
            }
            return
        }
        val headCheck = tracker.observeCandidateHead(entity.id, true, true, true, true, position(entity))
        if (!headCheck.accepted) {
            reject("mob-move", entity.id, headCheck.reason!!, texture, headCheck.watcherDistance)
            return
        }
        val trackerNowNanos = trackerTimeNanos()
        val check = tracker.observeEntityMove(entity.id, position(entity), trackerNowNanos, spawnTick.intValue())
        val reading = check.reading
        if (reading != null) {
            candidates = tracker.candidateSnapshot(trackerNowNanos, spawnTick.intValue())
            detail(
                "candidate-move id=${entity.id},distance=${formatDistance(check.watcherDistance)}" +
                    ",reason=${check.reason},wave=${if (reading.firstWave) "first" else "later"}" +
                    ",start=${positionText(reading.spawn!!)},spawn=${positionText(reading.destination!!)}",
            )
        } else if (check.reason != "zero-movement") {
            reject("mob-move", entity.id, check.reason!!, texture, check.watcherDistance)
        }
    }

    fun onEntityRemoved(entityId: Int) {
        if (!isEnabled()) return
        val watcher = tracker.watcherEntityId() == entityId
        val candidate = tracker.tracksCandidate(entityId)
        tracker.removeEntity(entityId)
        if (watcher) {
            candidates = emptyList()
            entityResets++
            detail("entity-remove id=$entityId,kind=watcher,tracking=cleared")
        } else if (candidate) {
            candidates = tracker.candidateSnapshot(trackerTimeNanos(), spawnTick.intValue())
            detail("entity-remove id=$entityId,kind=blood-mob,tracking=cleared")
        }
    }

    /** Clears stale entity ids immediately on login, respawn, disconnect, and world replacement. */
    fun onWorldChanged(reason: String?) {
        val client: Minecraft? = Minecraft.getInstance()
        observedSession = true
        observedLevel = client?.level
        observedConnection = client?.getConnection()
        clearState(reason ?: "world-change", true)
    }

    /** Adds Odin's estimated Watcher health count to the vanilla boss bar. */
    fun applyWatcherBossBar(event: BossEvent?) {
        if (event == null) return
        val plainName = ChatText.plain(event.getName().getString()).trim()
        if (!plainName.matches(Regex("The Watcher(?:\\s+\\d+\\s*/\\s*\\d+)?"))) return
        if (!isEnabled() || !watcherBar.value() || !DungeonContextTracker.isMobEspPhase()) {
            if (plainName != "The Watcher") event.setName(Component.literal(WATCHER_BAR_NAME))
            return
        }
        val floor = DungeonContextTracker.currentFloor()
        val amount = 12 + (floor?.number() ?: 0)
        val progress = event.getProgress()
        if (progress < 0.05f) {
            event.setName(Component.literal(WATCHER_BAR_NAME))
            return
        }
        val health = Math.round(amount * progress)
        event.setName(Component.literal("$WATCHER_BAR_NAME $health/$amount"))
        watcherBarUpdates++
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !bloodCampAssist.value() || candidates.isEmpty() || !DungeonContextTracker.isMobEspPhase()) {
            return
        }
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) return
        val camera = client.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        val trackerNowNanos = trackerTimeNanos()
        val size = boxSize.value().toDouble()
        val mobOffsetMillis = resolvedMobOffset(client)
        val partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        for (candidate in candidates) {
            val endpoint = interpolatedDestination(candidate, trackerNowNanos)
            val current = candidate.current!!
            val timeMillis = candidate.timeLeftMillis + offset.intValue()
            val pingPosition = current.plus(candidate.velocityPerMs!!.scale(mobOffsetMillis.toDouble()))
            if (mobOffsetMillis < timeMillis) {
                if (positionColor.alpha() > 0) {
                    renderWireBox(pose, buffers, pingPosition, size, camera, positionColor.argb())
                    positionBoxSubmissions++
                }
                if (spawnColor.alpha() > 0) {
                    renderWireBox(pose, buffers, endpoint, size, camera, spawnColor.argb())
                    spawnBoxSubmissions++
                }
            } else if (finalColor.alpha() > 0) {
                renderWireBox(pose, buffers, endpoint, size, camera, finalColor.argb())
                finalBoxSubmissions++
            }
            if (line.value()) {
                EspRenderer.renderLine(
                    pose,
                    buffers,
                    current.x - camera.x,
                    current.y + 2.0 - camera.y,
                    current.z - camera.z,
                    endpoint.x - camera.x,
                    endpoint.y + 2.0 - camera.y,
                    endpoint.z - camera.z,
                    LINE_RED,
                    2.0f,
                    true,
                )
                lineSubmissions++
            }
            if (timeLeft.value()) {
                val seconds = candidate.timeLeftMillis.toFloat() / 1000.0f
                val color = timeColor(seconds)
                EspRenderer.renderLabel(
                    pose,
                    buffers,
                    client.gameRenderer.mainCamera().rotation(),
                    endpoint.x - camera.x,
                    endpoint.y + 2.0 - camera.y,
                    endpoint.z - camera.z,
                    String.format(Locale.ROOT, "%.2fs", seconds),
                    color,
                    0.20f,
                    1.5f,
                )
                labelSubmissions++
            }
        }
        if (candidates.isNotEmpty()) {
            GeilerAddonsRenderTypes.endBatches(buffers)
            if (eventTick != diagnosticTick) {
                eventTick = diagnosticTick
                eventsThisTick = 0
            }
            if (detailsThisWindow < MAX_DETAIL_SAMPLES_PER_100_TICKS) {
                detail(
                    "render-submit candidates=${candidates.size},partial=${formatFloat(partialTick)}" +
                        ",offsetMs=$mobOffsetMillis,timeOffsetMs=${offset.intValue()}",
                )
            }
        }
    }

    override fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val centerY = y + Math.max(22, height / 2)
        val left = x + Math.max(12, width / 8)
        val right = x + width - Math.max(12, width / 8)
        val box = Math.max(8, Math.min(18, Math.round(11 * boxSize.value())))
        val posX = left + Math.max(12, (right - left) / 5)
        val spawnX = left + Math.max(30, (right - left) * 3 / 5)
        val finalX = right - box
        if (line.value()) fillRect(graphics, posX + box, centerY + box / 2 - 1, spawnX, centerY + box / 2 + 1, LINE_RED)
        previewBox(graphics, posX, centerY, box, positionColor)
        previewBox(graphics, spawnX, centerY, box, spawnColor)
        previewBox(graphics, finalX, centerY, box, finalColor)
        graphics.text(font, Component.literal("Position"), posX - 3, centerY + box + 4, 0xFFE6EAF0.toInt())
        graphics.text(font, Component.literal("Spawn"), spawnX, centerY + box + 4, 0xFFE6EAF0.toInt())
        graphics.text(font, Component.literal("Final"), finalX, centerY + box + 4, 0xFFE6EAF0.toInt())
        return true
    }

    override fun previewLines(font: Font, width: Int): List<Component> = listOf(
        Component.literal("Static world marker preview"),
        Component.literal("Move prediction: ${state(movePrediction.value())} · assist: ${state(bloodCampAssist.value())}"),
        Component.literal("No countdown HUD"),
    )

    private fun writeStatus(nowNanos: Long) {
        val floor = DungeonContextTracker.currentFloor()
        val floorText = floor?.displayName() ?: "unknown"
        val snapshot = tracker.candidateSnapshot(trackerTimeNanos(), spawnTick.intValue())
        candidates = if (bloodCampAssist.value() && DungeonContextTracker.isMobEspPhase()) snapshot else emptyList()
        val sample = StringBuilder()
        for (i in 0 until Math.min(MAX_CANDIDATE_DIAGNOSTIC_SAMPLES, snapshot.size)) {
            val candidate = snapshot[i]
            if (i > 0) sample.append(';')
            sample.append(candidate.entityId).append('@').append(positionText(candidate.current!!))
                .append("->").append(positionText(candidate.destination!!))
        }
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            diagnosticTick,
            "STATUS floor=$floorText,inDungeon=${DungeonContextTracker.inDungeon()}" +
                ",clearPhase=${DungeonContextTracker.isClearPhase()},mobEspPhase=${DungeonContextTracker.isMobEspPhase()}" +
                ",assist=${bloodCampAssist.value()},watcherId=${tracker.watcherEntityId()}" +
                ",eligible=${tracker.eligibleCount()},tracked=${tracker.trackedCount()}" +
                ",wave=${if (tracker.firstWave()) "first" else "later"}" +
                ",openingAgeMs=${ageMillis(nowNanos, openerStartedNanos)}" +
                ",moveTicks=$lastPredictedMoveTicks,titleInTicks=${ticksUntilTitle()}" +
                ",tick=${spawnTick.intValue()},offsetMs=${offset.intValue()}" +
                ",pingOffset=${pingOffset.value()},manualMobOffsetMs=${manualMobOffset.intValue()}" +
                ",textureMatchesWatcher=$watcherTextureMatches,textureMatchesMob=$mobTextureMatches" +
                ",rejectOutside20=$watcherDistanceRejects,rejectOther=$otherRejects" +
                ",renderPositionBoxes=$positionBoxSubmissions,renderSpawnBoxes=$spawnBoxSubmissions" +
                ",renderFinalBoxes=$finalBoxSubmissions,renderLines=$lineSubmissions" +
                ",renderLabels=$labelSubmissions,watcherBarUpdates=$watcherBarUpdates" +
                ",entityResets=$entityResets,detailSamples=$detailsSinceStatus" +
                ",suppressedDetails=$suppressedDetails,sample=${if (sample.length == 0) "none" else sample}",
        )
        watcherTextureMatches = 0
        mobTextureMatches = 0
        watcherDistanceRejects = 0
        otherRejects = 0
        entityResets = 0
        positionBoxSubmissions = 0
        spawnBoxSubmissions = 0
        finalBoxSubmissions = 0
        lineSubmissions = 0
        labelSubmissions = 0
        watcherBarUpdates = 0
        suppressedDetails = 0
        detailsSinceStatus = 0
    }

    private fun reject(kind: String, entityId: Int, reason: String, texture: String, distance: Double) {
        if (reason == "outside-watcher-range") watcherDistanceRejects++ else otherRejects++
        detail("$kind-reject id=$entityId,reason=$reason,texture=${shortHash(texture)},distance=${formatDistance(distance)}")
    }

    private fun detail(text: String) {
        if (!isEnabled()) return
        val tick = Math.max(0L, diagnosticTick)
        val window = tick / 100L
        if (window != detailWindow) {
            detailWindow = window
            detailsThisWindow = 0
        }
        if (eventTick != tick) {
            eventTick = tick
            eventsThisTick = 0
        }
        if (detailsThisWindow >= MAX_DETAIL_SAMPLES_PER_100_TICKS || eventsThisTick >= MAX_DETAIL_EVENTS_PER_TICK) {
            suppressedDetails++
            return
        }
        detailsThisWindow++
        detailsSinceStatus++
        eventsThisTick++
        val bounded = if (text.length <= MAX_DIAGNOSTIC_TEXT) text else text.substring(0, MAX_DIAGNOSTIC_TEXT)
        GeilerAddonsLog.write(Category.F7, name(), tick, "EVENT $bounded")
    }

    private fun noteReset(reason: String) {
        entityResets++
        detail(
            "reset reason=$reason,watcher=${tracker.watcherEntityId()}" +
                ",eligible=${tracker.eligibleCount()},tracked=${tracker.trackedCount()}",
        )
    }

    private fun clearState(reason: String, resetWave: Boolean) {
        val oldWatcher = tracker.watcherEntityId()
        val oldEligible = tracker.eligibleCount()
        val oldTracked = tracker.trackedCount()
        val hadState = oldWatcher >= 0 || oldEligible > 0 || oldTracked > 0 || openerStartedNanos >= 0L ||
            killTitleAtTick >= 0L || candidates.isNotEmpty()
        tracker.reset()
        if (!resetWave) tracker.observeWatcherMoved()
        candidates = emptyList()
        openerStartedNanos = -1L
        openerStartedTick = -1L
        killTitleAtTick = -1L
        lastPredictedMoveTicks = -1
        entityResets++
        detail(
            "reset reason=$reason,hadState=$hadState,watcher=$oldWatcher,eligible=$oldEligible" +
                ",tracked=$oldTracked,waveReset=$resetWave",
        )
    }

    private fun hasRuntimeState(): Boolean = tracker.hasWatcher() || tracker.eligibleCount() > 0 ||
        tracker.trackedCount() > 0 || openerStartedNanos >= 0L || killTitleAtTick >= 0L || candidates.isNotEmpty()

    private fun ticksUntilTitle(): Int {
        if (killTitleAtTick < 0L) return -1
        return Math.max(0L, Math.min(Int.MAX_VALUE.toLong(), killTitleAtTick - clientTick)).toInt()
    }

    private fun movePredictionTicks(elapsedSeconds: Long): Int {
        if (elapsedSeconds >= 31L && elapsedSeconds < 34L) return 36
        if (elapsedSeconds >= 28L && elapsedSeconds < 31L) return 33
        if (elapsedSeconds >= 25L && elapsedSeconds < 28L) return 30
        if (elapsedSeconds >= 22L && elapsedSeconds < 25L) return 27
        if (elapsedSeconds >= 1L && elapsedSeconds < 22L) return 24
        return Math.max(1L, Math.min(1_200L, elapsedSeconds + 3L)).toInt()
    }

    private fun resolvedMobOffset(client: Minecraft): Long {
        if (!pingOffset.value()) return manualMobOffset.intValue().toLong()
        val player = client.player ?: return 0L
        val connection = client.getConnection() ?: return 0L
        val info = connection.getPlayerInfo(player.getUUID())
        return if (info == null) 0L else Math.max(0, Math.min(1_000, info.latency)).toLong()
    }

    private fun interpolatedDestination(
        candidate: DungeonBloodCampTracker.CandidateReading,
        nowNanos: Long,
    ): DungeonBloodCampTracker.Position {
        if (!interpolation.value()) return candidate.destination!!
        val elapsed = Math.max(0L, nowNanos - candidate.destinationUpdatedNanos)
        val fraction = Math.max(0.0, Math.min(1.0, elapsed / 100_000_000.0))
        return lerp(candidate.previousDestination!!, candidate.destination!!, fraction)
    }

    private fun lerp(
        from: DungeonBloodCampTracker.Position,
        to: DungeonBloodCampTracker.Position,
        amount: Double,
    ): DungeonBloodCampTracker.Position = DungeonBloodCampTracker.Position(
        from.x + (to.x - from.x) * amount,
        from.y + (to.y - from.y) * amount,
        from.z + (to.z - from.z) * amount,
    )

    private fun renderWireBox(
        pose: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        point: DungeonBloodCampTracker.Position,
        size: Double,
        camera: Vec3,
        color: Int,
    ) {
        EspRenderer.renderBox(
            pose,
            buffers,
            point.x - size / 2.0 - camera.x,
            point.y + 1.5 - camera.y,
            point.z - size / 2.0 - camera.z,
            size,
            size,
            size,
            0x00000000,
            color,
            2.0f,
            true,
        )
    }

    private fun previewBox(graphics: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: ColorSetting) {
        if (color.alpha() <= 0) return
        val argb = color.argb()
        fillRect(graphics, x, y, x + size, y + 2, argb)
        fillRect(graphics, x, y + size - 2, x + size, y + size, argb)
        fillRect(graphics, x, y, x + 2, y + size, argb)
        fillRect(graphics, x + size - 2, y, x + size, y + size, argb)
    }

    private fun fillRect(graphics: GuiGraphicsExtractor, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        if (x2 > x1 && y2 > y1) graphics.fill(x1, y1, x2, y2, color)
    }

    private fun position(entity: Entity): DungeonBloodCampTracker.Position =
        DungeonBloodCampTracker.Position(entity.x, entity.y, entity.z)

    private fun textureHash(head: ItemStack?): String {
        if (head == null || !head.`is`(Items.PLAYER_HEAD)) return ""
        val profile: ResolvableProfile? = head.get(DataComponents.PROFILE)
        if (profile == null) return ""
        for (property in profile.partialProfile().properties().get("textures")) {
            if (property == null || property.value() == null) continue
            val encoded = property.value()
            if (encoded.length > MAX_TEXTURE_PROPERTY_BYTES) continue
            try {
                val decoded = Base64.getDecoder().decode(encoded)
                if (decoded.size > MAX_TEXTURE_PROPERTY_BYTES) continue
                val matcher = TEXTURE_URL.matcher(String(decoded, StandardCharsets.UTF_8))
                if (matcher.find()) return matcher.group(1).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                // Invalid profile properties are not texture identities.
            }
        }
        return ""
    }

    private fun timeColor(seconds: Float): Int {
        if (seconds > 1.5f) return POSITION_GREEN
        if (seconds >= 0.5f) return 0xFFFFAA00.toInt()
        if (seconds >= 0.0f) return SPAWN_RED
        return 0xFF55FFFF.toInt()
    }

    private fun ageMillis(nowNanos: Long, startNanos: Long): Long =
        if (startNanos < 0L) -1L else Math.max(0L, (nowNanos - startNanos) / 1_000_000L)

    private fun positionText(position: DungeonBloodCampTracker.Position): String =
        String.format(Locale.ROOT, "%.2f,%.2f,%.2f", position.x, position.y, position.z)

    private fun formatDistance(distance: Double): String =
        if (java.lang.Double.isFinite(distance)) String.format(Locale.ROOT, "%.1f", distance) else "unknown"

    private fun formatFloat(value: Float): String = String.format(Locale.ROOT, "%.2f", value)

    /** Blood Camp timings follow server ticks (Odin counts them too) so server lag does not skew predictions. */
    private fun trackerTimeNanos(): Long {
        val server = DungeonOdinAssistModule.INSTANCE.serverTicks()
        return Math.max(0L, if (server > 0L) server else clientTick) * 50_000_000L
    }

    private fun shortHash(texture: String?): String =
        if (texture.isNullOrEmpty()) "none" else texture.substring(0, Math.min(12, texture.length))

    private fun state(value: Boolean): String = if (value) "On" else "Off"

    companion object {
        private const val MAX_DETAIL_SAMPLES_PER_100_TICKS = 8
        private const val MAX_DETAIL_EVENTS_PER_TICK = 12
        private const val MAX_DIAGNOSTIC_TEXT = 420
        private const val MAX_TEXTURE_PROPERTY_BYTES = 16_384
        private const val MAX_CANDIDATE_DIAGNOSTIC_SAMPLES = 8
        private const val WATCHER_RANGE = 20.0
        private val POSITION_GREEN = 0xFF55FF55.toInt()
        private val SPAWN_RED = 0xFFFF5555.toInt()
        private val FINAL_DARK_AQUA = 0xFF00AAAA.toInt()
        private val LINE_RED = 0xFFFF5555.toInt()
        private val TEXTURE_URL: Pattern = Pattern.compile("textures\\.minecraft\\.net/texture/([0-9a-fA-F]{32,64})")
        private val WATCHER_TEXTURES = setOf(
            "5662b6fb4b8b586dc4cdf803b0444d9b41d245cdf668dab38fa6c064afe8e461",
            "2739d7f4e66a7db2ea6cd414e4c4ba41df7a92455c9fc42caab014665c367ad5",
            "bf6e1e7ed36586c2d98057002bc1adc981e2889f7bd7b5b3852bc55cc7802204",
            "4cec40008e1c31c1984f4d650abb3410f2037119fd624afc953563b73515a077",
            "b37dd18b5983a767e556dc64424af4b9abdb75d4c9e8b097818afbc431bf0e09",
            "f5f0d78fe38d1d7f75f08cdcf2a1855d6da0337e114a3c63e3bf3c618bc732b0",
            "51967db5e3199916252021903cf4e9952ef7cec220faaca1ba79bafe5938bd80",
            "9fd61e8055f6ee97ab5b6196a8d7ec98078ac37e00376157b6b520eaaa2f93af",
            "e5c1dc47a04ce57001a8b726f018cdef40b7ea9d7bd6d835ca495a0ef169f893",
        )
        private val MOB_TEXTURES = setOf(
            "5a79860aca799407c0faa10b1bbcf42998fad4ebcf31d7a214180826b4ac94e1",
            "4774871190c878c9a2c4496c1e10257c6c4ea13807d72c15d7ac6ab3a7a9a8dc",
            "f4624a9a8c69ca204504abb043d47456cd9b09749a36357462303f276a229d4",
            "c919e5b8d56f062a21d224de14af771e2f55d09b59e7b099d09daa57540b79cf",
            "a89f6303af85877610912dc04b8b1e89724752f0a7eea05ab6547e228179c06f",
            "67237eddaebdbbdaacfa912885560ccdc65da93b4c3d513532868ec23bb5b448",
            "ff184c19e725623d32828a0a4e741e86f135ac63dbc828ff3c8468338f3683b",
            "5cccd53f5191c29a9dc8f0170fbdc4e59e66476aae33de27b468f1de1b7cf3b",
            "b5ba76e02cab72fa7d8ac54ceec849976ab0b00a01068d68c266766bf70c3997",
            "aa23c8cde2943c84249de8351bc3540be5f8afaaba8b2cb032fc5acad78a269b",
            "9171f35b8f508142bd8c65417d0f324153ab9147739ee4d10dea733cc80eaa20",
            "7d12b2ade413a6cd7cca3c95e961ba9f0ae7165fa41fc7b5d5f094a01240c609",
            "96c3e31cfc66733275c42fcfb5d9a44342d643b55cd14c9c77d273a2352",
            "8421ba5b8e3573ef97beb5b40e15d15b20f30631c4c5330c3deda3047df0e92",
            "ad22772f769045fdc5be819ad68b01a97ac04c60886d2ca7afee39b282f7a383",
            "ad67f97d7f821729beb34a82c3f13592b40439fe5248e72576fde7aa180bf77",
            "fb3973a752b24a2f3abb003427f6dbe6ca3a61db0a1bcf351c6eab27ec27e50",
            "12716ecbf5b8da00b05f316ec6af61e8bd02805b21eb8e440151468dc656549c",
            "3260325171a7ba8460830c0eea515c757a665e5b16a14207ba1a3182752bee87",
            "62d8fd3aa5617b1dac0aae9c81f6dd70ad93a59942f460d27e4d55a5cb8918e8",
            "56fc854bb84cf4b7697297973e02b79bc10698460b51a639c60e5e417734e11",
            "7de7bbbdf22bfe17980d4e20687e386f11d59ee1db6f8b4762391b79a5ac532d",
            "c1007c5b7114abec734206d4fc613da4f3a0e99f71ff949cedadc99079135a0b",
        )
        private val WATCHER_OPEN_LINES = setOf(
            "[BOSS] The Watcher: Congratulations, you made it through the Entrance.",
            "[BOSS] The Watcher: Ah, you've finally arrived.",
            "[BOSS] The Watcher: Ah, we meet again...",
            "[BOSS] The Watcher: So you made it this far... interesting.",
            "[BOSS] The Watcher: You've managed to scratch and claw your way here, eh?",
            "[BOSS] The Watcher: I'm starting to get tired of seeing you around here...",
            "[BOSS] The Watcher: Oh.. hello?",
            "[BOSS] The Watcher: Things feel a little more roomy now, eh?",
            "[BOSS] The Watcher: Ah, we meet again. As I foresaw...",
        )

        @JvmStatic
        fun isWatcherOpenLine(message: String?): Boolean = message != null && WATCHER_OPEN_LINES.contains(message)
        private const val WATCHER_MOVE_LINE = "[BOSS] The Watcher: Let's see how you can handle this."
        private const val WATCHER_BAR_NAME = "§c§lThe Watcher"

        private val predictionDropdown = BooleanSetting("Prediction Dropdown", true)
        private val movePrediction = BooleanSetting("Move Prediction", true)
        private val moveMessage = BooleanSetting("Move Message", true)
        private val partyMoveMessage = BooleanSetting("Party Move Message", false)
        private val killTitle = BooleanSetting("Kill Title", true)
        private val bloodAssistDropdown = BooleanSetting("Blood Assist Dropdown", true)
        private val bloodCampAssist = BooleanSetting("Blood Camp Assist", true)
        private val spawnColor = ColorSetting("Spawn Color", 255, 85, 85, 255)
        private val finalColor = ColorSetting("Final Color", 0, 170, 170, 255)
        private val positionColor = ColorSetting("Position Color", 85, 255, 85, 255)
        private val boxSize = NumberSetting("Box Size", 0.1f, 1.0f, 1.0f)
        private val line = BooleanSetting("Line", true)
        private val timeLeft = BooleanSetting("Time Left", true)
        private val advanced = BooleanSetting("Advanced", false)
        private val offset = NumberSetting("Offset", -100f, 100f, 40f, true)
        private val spawnTick = NumberSetting("Tick", 35f, 41f, 38f, true)
        private val interpolation = BooleanSetting("Interpolation", true)
        private val pingOffset = BooleanSetting("Ping Offset", true)
        private val manualMobOffset = NumberSetting("Mob Box Offset", 0f, 300f, 0f, true)
        private val watcherBar = BooleanSetting("Watcher Bar", true)

        @JvmField
        val INSTANCE = DungeonBloodCampModule()
    }
}
