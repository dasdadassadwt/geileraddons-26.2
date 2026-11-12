package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.dungeon.DungeonGuideNode
import geiler.addons.client.dungeon.DungeonGuidePhaseEntryBuffer
import geiler.addons.client.dungeon.DungeonGuidePhaseOutputState
import geiler.addons.client.dungeon.DungeonGuidePhaseTracker
import geiler.addons.client.dungeon.DungeonGuideProgress
import geiler.addons.client.dungeon.DungeonGuideProgress.RouteStep
import geiler.addons.client.dungeon.DungeonGuideSegments
import geiler.addons.client.dungeon.DungeonGuideStore
import geiler.addons.client.farming.PestAnimation
import geiler.addons.client.gui.DungeonFloorPickerScreen
import geiler.addons.client.gui.DungeonGuideEditorScreen
import geiler.addons.client.gui.DungeonGuideStagesScreen
import geiler.addons.client.hud.HudElement
import geiler.addons.client.hud.HudManager
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

/** Independent player-authored dungeon guide and route runner for Catacombs floors. */
class DungeonHelperModule private constructor(
    private val settings: Settings,
) : Module(
    "Dungeon Guide",
    "One ordered route per floor with condition-driven guide steps and linked macros. Bind this module to open its editor.",
    Category.F7,
    false,
    *moduleSettings(settings),
), HudElement {
    private val phaseOutputState = DungeonGuidePhaseOutputState()
    private val pendingPhaseEntry = DungeonGuidePhaseEntryBuffer()
    private val progress = DungeonGuideProgress()
    private val phases = DungeonGuidePhaseTracker()

    private var lastDebugState = ""
    private var lastDebugTick = Long.MIN_VALUE
    private var lastStatusState = ""
    private var lastStatusNanos = Long.MIN_VALUE
    private var publishedRoute: DungeonGuideProgress.RouteSnapshot? = null
    private var lastUpdateNanos = 0L
    private var lastUpdateState = "not-built"
    private var lastRenderNanos = 0L
    private var lastRenderState = "not-rendered"

    init {
        group(
            SettingGroup(
                null,
                settings.labels,
                settings.alwaysDisplaySteps,
                settings.connectors,
                settings.throughWalls,
                settings.range,
                settings.size,
                settings.ringCount,
                settings.ringHeight,
                settings.ringRadius,
                settings.ringSpeed,
                settings.ringWidth,
                settings.debug,
            ),
            settings.phaseOutputGroup,
        )
        DungeonGuideStore.load()
    }

    /** The module's standard keybind opens this editor without toggling the route runner. */
    fun openEditorFromKeybind() = openEditor()

    override fun configName(): String = "Dungeon Helper"

    private fun openEditor() {
        val client = Minecraft.getInstance()
        client.gui.setScreen(DungeonGuideEditorScreen(client.gui.screen()))
    }

    private fun openFloorPicker() {
        val client = Minecraft.getInstance()
        client.gui.setScreen(DungeonFloorPickerScreen(client.gui.screen()))
    }

    private fun openStages() {
        val client = Minecraft.getInstance()
        client.gui.setScreen(DungeonGuideStagesScreen(client.gui.screen()))
    }

    fun tick() {
        tickPhaseOutput()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled() || settings.debug.value())
        migrateLegacyRingAppearance()

        val floor = DungeonContextTracker.currentFloor()
        val now = System.nanoTime()
        phases.setFloor(floor, now)
        progress.setFloor(floor, now)
        if (floor != null && isEnabled()) {
            val client = Minecraft.getInstance()
            client.player?.let { phases.tickCustom(it.getX(), it.getY(), it.getZ(), now) }
            syncPhase(now)

            val route = routeSnapshot()
            val current = route.current()
            if (current != null && !skipAheadIfInRange(route, client, now)) {
                advanceIfReady(current, "", now, client, route)
            }
        }

        reportDebug()
        reportAutomaticStatus()
    }

    private fun tickPhaseOutput() {
        val inDungeon = DungeonContextTracker.inDungeon()
        val floor = if (inDungeon) DungeonContextTracker.currentFloor() else null
        phaseOutputState.tickContext(floor)

        val client = Minecraft.getInstance()
        val now = System.nanoTime()
        pendingPhaseEntry.discardIfStale(inDungeon, floor, client.level, client.getConnection(), now)
        if (!isEnabled()) {
            pendingPhaseEntry.clear()
            return
        }

        val entry = pendingPhaseEntry.consumeIfReady(inDungeon, floor, client.level, client.getConnection(), now)
        if (entry != null) {
            observePhaseOutput(entry.floor, DungeonGuideSegments.Event("ENTRY", "Run countdown chat"))
        }
    }

    /** Emits only source-backed phase events, shared with Dungeon Guide route progression. */
    fun observePhaseOutput(floor: DungeonFloor?, event: DungeonGuideSegments.Event?) {
        if (!isEnabled() || floor == null || event == null) return
        if ("ENTRY" == event.segmentId && !DungeonContextTracker.inDungeon()) {
            val client = Minecraft.getInstance()
            pendingPhaseEntry.capture(floor, client.level, client.getConnection(), System.nanoTime())
            return
        }
        showPhaseOutput(phaseOutputState.observeEvent(floor, event))
    }

    private fun showPhaseOutput(transition: DungeonGuidePhaseOutputState.Transition?) {
        if (transition == null || !isEnabled()) return

        val phaseId = transition.phaseId
        val selected = phaseOutputKey(transition.floor, phaseId)?.let(settings.phaseOutputs::get)
        val title = selected?.title?.value() == true
        val hud = selected?.hud?.value() == true
        val chat = selected?.chat?.value() == true
        val sound = selected?.sound?.value() == true
        val label = DungeonGuideSegments.label(transition.floor, phaseId)
        val message = "Dungeon Guide: $label"
        val client = Minecraft.getInstance()

        if (title && client.gui != null) client.gui.hud.setTitle(Component.literal(label))
        if (hud && client.gui != null) client.gui.hud.setOverlayMessage(Component.literal(message), true)
        if (chat && client.gui != null) client.gui.hud.getChat().addClientSystemMessage(Component.literal(message))
        if (sound) client.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f),
        )

        GeilerAddonsLog.write(
            Category.F7,
            name(),
            client.level?.getGameTime() ?: 0L,
            "EVENT phase-output phase=$phaseId title=$title hud=$hud chat=$chat sound=$sound",
        )
    }

    /** Clears per-run output memory on disconnect. */
    fun onDisconnect() {
        pendingPhaseEntry.clear()
        phaseOutputState.tickContext(null)
    }

    override fun onDisable() {
        pendingPhaseEntry.clear()
        phaseOutputState.tickContext(null)
    }

    fun onChatMessage(message: String?) {
        if (!isEnabled() || message == null || ChatStringSemantics.isBlank(message)) return
        val floor = DungeonContextTracker.currentFloor() ?: return
        val now = System.nanoTime()
        phases.setFloor(floor, now)
        progress.setFloor(floor, now)

        val event = DungeonGuideSegments.fromChat(floor, message)
        val routeName = floor.displayName()
        val timing = DungeonGuideStore.routeTiming(
            routeName,
            progress.phase(),
            DungeonGuideStore.activeRoute(routeName, progress.phase()),
        )
        phases.observe(event, timing, now)
        phases.observeCustomChat(message, now)
        syncPhase(now)

        val route = routeSnapshot()
        val current = route.current() ?: return
        val client = Minecraft.getInstance()
        if (!skipAheadIfInRange(route, client, now)) advanceIfReady(current, message, now, client, route)
    }

    private fun skipAheadIfInRange(
        route: DungeonGuideProgress.RouteSnapshot,
        client: Minecraft,
        now: Long,
    ): Boolean {
        val player = client.player ?: return false
        if (route.current() == null) return false
        val targetIndex = DungeonGuideProgress.furthestSkipAheadIndexInRange(
            route,
            player.getX(),
            player.getY(),
            player.getZ(),
        )
        return targetIndex >= 0 && progress.advanceTo(route, targetIndex, now)
    }

    private fun advanceIfReady(
        node: RouteStep,
        chatMessage: String,
        now: Long,
        client: Minecraft,
        route: DungeonGuideProgress.RouteSnapshot,
    ) {
        val player = client.player
        val distanceSquared = if (player == null) {
            Double.POSITIVE_INFINITY
        } else {
            player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5)
        }
        if (DungeonGuideProgress.triggerReady(node, progress.elapsedNanos(now), distanceSquared, chatMessage, phases.phase())) {
            triggerLinkedMacro(progress.advance(route, now))
        }
    }

    private fun syncPhase(now: Long) {
        val floor = phases.floor() ?: return
        val routePhase = phases.phase()
        if (routePhase != progress.phase()) progress.setPhase(routePhase, now, routeSnapshot())
        progress.setRoute(DungeonGuideStore.routeName(floor.displayName()), now)
    }

    private fun reportDebug() {
        if (!settings.debug.value()) {
            lastDebugState = ""
            return
        }

        val floor = DungeonContextTracker.currentFloor()
        val route = routeSnapshot()
        val state = "enabled=${isEnabled()}, dungeon=${DungeonContextTracker.inDungeon()}" +
            ", floor=${floor?.displayName() ?: "unknown"}" +
            ", phase=${phases.phase()}, phaseSource=${phases.evidence()}" +
            ", phaseManual=${phases.manual()}" +
            ", route=${floor?.let { DungeonGuideStore.routeName(it.displayName()) } ?: "none"}" +
            ", step=${route.stepIndex()}/${route.nodeCount()}" +
            ", remaining=${route.remaining()}, complete=${route.complete()}" +
            ", routeNodes=${route.nodeCount()}"

        val client = Minecraft.getInstance()
        val tick = client.level?.getGameTime() ?: 0L
        if (state == lastDebugState && tick - lastDebugTick < 100) return
        lastDebugState = state
        lastDebugTick = tick
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            tick,
            "guide: $state, update=$lastUpdateState/${lastUpdateNanos}ns, " +
                "render=$lastRenderState/${lastRenderNanos}ns",
        )
    }

    /** A bounded status heartbeat plus coalesced changes, always recorded while the module is enabled. */
    private fun reportAutomaticStatus() {
        if (!isEnabled()) {
            lastStatusState = ""
            lastStatusNanos = Long.MIN_VALUE
            return
        }

        val floor = DungeonContextTracker.currentFloor()
        val route = routeSnapshot()
        val routeName = floor?.let { DungeonGuideStore.routeName(it.displayName()) } ?: "none"
        val state = (
            "enabled=true, dungeon=${DungeonContextTracker.inDungeon()}, floor=${floor?.displayName() ?: "unknown"}" +
                ", phase=${phases.phase()}, phaseSource=${phases.evidence().take(64)}, phaseManual=${phases.manual()}" +
                ", route=${routeName.take(MAX_STATUS_ROUTE_LENGTH)}, step=${route.stepIndex()}/${route.nodeCount()}" +
                ", remaining=${route.remaining()}, complete=${route.complete()}, routeNodes=${route.nodeCount()}" +
                ", update=$lastUpdateState, render=$lastRenderState"
            ).take(MAX_STATUS_LENGTH)
        val now = System.nanoTime()
        val elapsed = if (lastStatusNanos == Long.MIN_VALUE) Long.MAX_VALUE else now - lastStatusNanos
        val changed = state != lastStatusState
        if (lastStatusNanos != Long.MIN_VALUE &&
            ((!changed && elapsed < STATUS_HEARTBEAT_NANOS) || (changed && elapsed < STATUS_CHANGE_INTERVAL_NANOS))
        ) return

        lastStatusState = state
        lastStatusNanos = now
        val client = Minecraft.getInstance()
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            client.level?.getGameTime() ?: 0L,
            "STATUS $state, build=${lastUpdateNanos}ns, renderTime=${lastRenderNanos}ns".take(MAX_STATUS_LINE_LENGTH),
        )
    }

    fun nextStep() {
        triggerLinkedMacro(progress.advanceManually(routeSnapshot(), System.nanoTime()))
    }

    fun setPhase(phase: String?): Boolean {
        val now = System.nanoTime()
        if (!phases.choose(phase ?: return false, now)) return false
        syncPhase(now)
        return true
    }

    fun resumeAutomaticPhase() = phases.resumeAuto()

    fun availablePhases(): List<String> = phases.orderedSegments()

    fun phaseEvidence(): String = phases.evidence()

    fun phaseIsManual(): Boolean = phases.manual()

    fun activePhase(): String = phases.phase()

    fun detectedPhaseFor(floor: DungeonFloor?): String? = phases.detectedPhaseFor(floor)

    fun remaining(): Int = routeSnapshot().remaining()

    /** Restores old shared ring values into any imported or loaded ring nodes that still lack them. */
    fun migrateLegacyRingAppearance() {
        DungeonGuideStore.migrateLegacyRingAppearance(
            settings.ringCount.intValue(),
            settings.ringHeight.value(),
            settings.ringRadius.value(),
            settings.ringSpeed.value(),
            settings.ringWidth.value(),
        )
    }

    /** Keep legacy shared values persisted for migration, but no longer present them as active controls. */
    override fun isSettingVisible(setting: Setting): Boolean =
        setting !== settings.ringCount && setting !== settings.ringHeight && setting !== settings.ringRadius &&
            setting !== settings.ringSpeed && setting !== settings.ringWidth

    private fun routeSnapshot(): DungeonGuideProgress.RouteSnapshot {
        val route = progress.snapshot(DungeonGuideStore.all(), DungeonGuideStore.guideRevision())
        if (route !== publishedRoute) {
            publishedRoute = route
            lastUpdateState = route.rebuildReason() ?: "unknown"
            lastUpdateNanos = route.buildNanos()
        }
        return route
    }

    /** Shared route ring controls, also exposed in the route editor's advanced appearance section. */
    fun guideRingCountSetting(): NumberSetting = settings.ringCount

    fun guideRingHeightSetting(): NumberSetting = settings.ringHeight

    fun guideRingRadiusSetting(): NumberSetting = settings.ringRadius

    fun guideRingSpeedSetting(): NumberSetting = settings.ringSpeed

    fun guideRingWidthSetting(): NumberSetting = settings.ringWidth

    override fun inactiveReason(): String? {
        if (!isEnabled()) return null
        if (!DungeonContextTracker.inDungeon()) return "Waiting for dungeon detection"
        return if (DungeonContextTracker.currentFloor() == null) "Floor unknown; choose a manual floor" else null
    }

    fun render(context: LevelRenderContext) {
        val startNanos = if (isEnabled()) System.nanoTime() else 0L
        if (!isEnabled()) {
            finishRender(startNanos, "disabled")
            return
        }

        val route = routeSnapshot()
        if (route.nodeCount() == 0) {
            finishRender(startNanos, "empty-route")
            return
        }
        if (route.complete() && !settings.alwaysDisplaySteps.value()) {
            finishRender(startNanos, "complete")
            return
        }

        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null) {
            finishRender(startNanos, "no-player")
            return
        }

        val partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        val elapsedTicks = (client.level?.getGameTime() ?: 0L).toDouble() + partialTick.toDouble()
        val camera = client.gameRenderer.mainCamera().position()
        val pose = context.poseStack()
        val buffers = WorldRenderBuffer.current()
        var drew = false

        if (settings.alwaysDisplaySteps.value()) {
            for (step in route.steps()) drew = renderStep(step, client, camera, pose, buffers, elapsedTicks) || drew
        } else {
            route.current()?.let { drew = renderStep(it, client, camera, pose, buffers, elapsedTicks) }
        }

        val active = route.current()
        if (settings.connectors.value() && active != null) {
            val eye = player.getEyePosition(partialTick)
            EspRenderer.renderLine(
                pose,
                buffers,
                eye.x - camera.x,
                eye.y - camera.y,
                eye.z - camera.z,
                active.x() + 0.5 - camera.x,
                active.y() + 0.5 - camera.y,
                active.z() + 0.5 - camera.z,
                active.color(),
                1.5f,
                !settings.throughWalls.value(),
            )
            drew = true
        }

        if (drew) GeilerAddonsRenderTypes.endBatches(buffers)
        finishRender(startNanos, if (drew) "drawn" else "no-geometry")
    }

    private fun renderStep(
        node: RouteStep,
        client: Minecraft,
        camera: Vec3,
        pose: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        elapsedTicks: Double,
    ): Boolean {
        val player = client.player ?: return false
        val range = if (node.visibilityDistance() > 0) node.visibilityDistance() else settings.range.intValue()
        if (player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5) > range.toDouble() * range.toDouble()) return false

        val size = max(0.25f, node.size() * settings.size.value())
        val x = node.x() + 0.5 - camera.x
        val y = node.y() - camera.y
        val z = node.z() + 0.5 - camera.z
        val color = node.color()
        val fill = node.fillColor()
        val depthTest = !settings.throughWalls.value()

        when (node.shape() ?: DungeonGuideNode.Shape.BEACON) {
            DungeonGuideNode.Shape.BOX -> {
                if (node.rotation() == 0.0f) {
                    EspRenderer.renderBox(
                        pose,
                        buffers,
                        x - size / 2,
                        y,
                        z - size / 2,
                        size.toDouble(),
                        size.toDouble(),
                        size.toDouble(),
                        fill,
                        color,
                        1.5f,
                        depthTest,
                    )
                } else {
                    EspRenderer.renderRotatedBox(
                        pose,
                        buffers,
                        x,
                        y,
                        z,
                        size,
                        node.rotation(),
                        fill,
                        color,
                        1.5f,
                        depthTest,
                    )
                }
                return true
            }

            DungeonGuideNode.Shape.BEACON -> {
                EspRenderer.renderLine(
                    pose,
                    buffers,
                    x,
                    y,
                    z,
                    x,
                    y + max(1.0, size.toDouble() * 2.0),
                    z,
                    color,
                    2.0f,
                    depthTest,
                )
                EspRenderer.renderSphere(pose, buffers, x, y + size * 0.2, z, max(0.15f, size * 0.18f), color)
                return true
            }

            DungeonGuideNode.Shape.RING -> {
                val radius = node.ringRadius() * size
                // Match Java's int + (float * float) expression before widening for the helper.
                val headY = (node.y() + node.ringHeight() * size).toDouble()
                val heights = PestAnimation.ringPositions(
                    node.y().toDouble(),
                    headY,
                    elapsedTicks,
                    node.ringSpeed().toDouble(),
                    node.ringCount(),
                )
                for (height in heights) {
                    if (node.ringFill() && (fill ushr 24) != 0) {
                        EspRenderer.renderFlatArea(
                            pose,
                            buffers,
                            x,
                            height - camera.y,
                            z,
                            radius.toDouble(),
                            0.0,
                            false,
                            fill,
                            depthTest,
                        )
                    }
                    EspRenderer.renderRing(pose, buffers, x, height - camera.y, z, radius, color, node.ringWidth(), depthTest)
                }
                return true
            }

            DungeonGuideNode.Shape.TEXT_ONLY -> return false
        }
        return false
    }

    private fun finishRender(startNanos: Long, state: String) {
        if (startNanos == 0L) return
        lastRenderNanos = max(0L, System.nanoTime() - startNanos)
        lastRenderState = state
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!visible()) return
        val client = Minecraft.getInstance()
        val route = routeSnapshot()
        drawStatusLines(graphics, client.font, statusLines(route), hudX(graphics), hudY(graphics))
        renderUpcomingLabels(graphics, client, route.current())
    }

    override fun id(): String = "dungeon_guide_status"

    override fun displayName(): String = "Dungeon Guide Status"

    override fun width(font: Font?): Int {
        val actualFont = font!!
        val status = statusLines(routeSnapshot())
        return max(1, max(actualFont.width(status[0]), actualFont.width(status[1])))
    }

    override fun height(font: Font?): Int = font!!.lineHeight * 2 + 3

    override fun previewWidth(font: Font?): Int {
        val actualFont = font!!
        val status = previewStatusLines()
        return max(1, max(actualFont.width(status[0]), actualFont.width(status[1])))
    }

    override fun visible(): Boolean {
        if (!isEnabled() || !settings.labels.value() || DungeonContextTracker.currentFloor() == null) return false
        val route = routeSnapshot()
        return route.nodeCount() > 0 && route.remaining() > 0
    }

    override fun previewMode(): String = if (hasPreviewRoute()) "live" else "sample"

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (!visible()) return
        val route = routeSnapshot()
        drawStatusLines(graphics, font, statusLines(route), x, y)
        renderUpcomingLabels(graphics, Minecraft.getInstance(), route.current())
    }

    /** Uses the cached route snapshot only; preview rendering never advances or rebuilds route state. */
    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        drawStatusLines(graphics, font, previewStatusLines(), x, y)
    }

    private fun hudX(graphics: GuiGraphicsExtractor): Int =
        HudManager.x(this, Minecraft.getInstance().font, graphics.guiWidth())

    private fun hudY(graphics: GuiGraphicsExtractor): Int =
        HudManager.y(this, Minecraft.getInstance().font, graphics.guiHeight())

    private fun statusLines(route: DungeonGuideProgress.RouteSnapshot): Array<String> =
        statusLines(route, DungeonContextTracker.currentFloor())

    private fun statusLines(
        route: DungeonGuideProgress.RouteSnapshot,
        floor: DungeonFloor?,
    ): Array<String> {
        val currentNode = route.current()
        val current = if (floor == null || currentNode == null) "" else " · ${currentNode.label()}"
        val first = if (floor == null) {
            "Dungeon Guide"
        } else {
            "Dungeon Guide · ${DungeonGuideSegments.label(floor, activePhase())}$current · ${route.remaining()} left"
        }
        return arrayOf(first, "${if (phases.manual()) "Manual" else "Auto"} · ${phases.evidence()}")
    }

    private fun previewStatusLines(): Array<String> {
        val route = publishedRoute
        val floor = DungeonContextTracker.currentFloor()
        if (!hasPreviewRoute()) return PREVIEW_STATUS_LINES
        return statusLines(route!!, floor)
    }

    private fun hasPreviewRoute(): Boolean {
        val route = publishedRoute ?: return false
        val floor = DungeonContextTracker.currentFloor() ?: return false
        return isEnabled() && settings.labels.value() && route.floor() == floor &&
            route.nodeCount() > 0 && route.remaining() > 0
    }

    private fun renderUpcomingLabels(
        graphics: GuiGraphicsExtractor,
        client: Minecraft,
        node: RouteStep?,
    ) {
        val player = client.player ?: return
        node ?: return
        val range = if (node.visibilityDistance() > 0) node.visibilityDistance() else settings.range.intValue()
        if (player.distanceToSqr(node.x() + 0.5, node.y() + 0.5, node.z() + 0.5) > range.toDouble() * range) return

        val world = Vec3(
            node.x() + 0.5,
            node.y() + max(1.0f, node.size() * settings.size.value()) + 0.25,
            node.z() + 0.5,
        )
        ProjectedLabelRenderer.drawFloatingText(
            graphics,
            client.gameRenderer.mainCamera(),
            client.font,
            world,
            node.label(),
            node.labelColor(),
            node.labelScale(),
        )
    }

    private fun triggerLinkedMacro(node: RouteStep?) {
        if (node != null && node.macroId() >= 0) {
            geiler.addons.client.macro.MacroRunner.triggerGuideMacro(node.macroId())
        }
    }

    private class PhaseOutputs(stableKey: String, label: String) {
        val title = BooleanSetting("$stableKey Title", "$label Title", false)
        val hud = BooleanSetting("$stableKey HUD Hint", "$label HUD Hint", false)
        val chat = BooleanSetting("$stableKey Chat", "$label Chat", false)
        val sound = BooleanSetting("$stableKey Sound", "$label Sound", false)

    }

    private class Settings {
        val phaseOutputs = LinkedHashMap<String, PhaseOutputs>()
        val phaseOutputSettings = mutableListOf<Setting>()

        val labels = BooleanSetting("Show Guide Status", false)
        val alwaysDisplaySteps = BooleanSetting("Always Display All Route Steps", false)
        val connectors = BooleanSetting("Connect Guide Steps", true)
        val throughWalls = BooleanSetting("Draw Through Walls", "Draw Through Walls", true)
        val range = NumberSetting("Render Range", 8f, 128f, 64f, true)
        val size = NumberSetting("Marker Scale", 0.25f, 4.0f, 1.0f)
        val ringCount = NumberSetting("Guide Ring Count", 1f, 8f, 3f, true)
        val ringHeight = NumberSetting("Guide Ring Height", 0.25f, 8.0f, 2.5f)
        val ringRadius = NumberSetting("Guide Ring Radius", 0.25f, 8.0f, 1.2f)
        val ringSpeed = NumberSetting("Guide Ring Speed", 0.05f, 2.0f, 0.35f)
        val ringWidth = NumberSetting("Guide Ring Width", 0.5f, 5.0f, 1.5f)
        val debug = BooleanSetting("Debug Route Progress", false)

        val phaseOutputGroup: SettingGroup = buildPhaseOutputGroup()

        private fun buildPhaseOutputGroup(): SettingGroup {
            val clearEvents = mutableListOf<SettingGroup>()
            for (phaseId in SHARED_PHASES) {
                val label = DungeonGuideSegments.label(DungeonFloor.F1, phaseId)
                val outputs = addPhase(phaseId, label)
                clearEvents += SettingGroup.folded(label, outputs.title, outputs.hud, outputs.chat, outputs.sound)
                    .keyed("guide-phase-shared-${phaseId.lowercase(Locale.ROOT)}")
            }

            val normalFloors = mutableListOf<SettingGroup>()
            val masterFloors = mutableListOf<SettingGroup>()
            for (floor in DungeonFloor.values()) {
                val floorPhases = mutableListOf<SettingGroup>()
                for (phaseId in DungeonGuidePhaseOutputState.supportedPhaseIds(floor)) {
                    if (sharedPhase(phaseId)) continue
                    val key = phaseOutputKey(floor, phaseId) ?: continue
                    val label = DungeonGuideSegments.label(floor, phaseId)
                    val outputs = addPhase(key, "${floor.displayName()} $label")
                    floorPhases += SettingGroup.folded(label, outputs.title, outputs.hud, outputs.chat, outputs.sound)
                        .keyed(
                            "guide-phase-${floor.displayName().lowercase(Locale.ROOT)}-" +
                                phaseId.lowercase(Locale.ROOT),
                        )
                }
                if (floorPhases.isEmpty()) continue

                val floorGroup = SettingGroup(floor.displayName()).containing(*floorPhases.toTypedArray())
                if (floor.master()) masterFloors += floorGroup else normalFloors += floorGroup
            }

            return SettingGroup.folded("Phase Outputs").containing(
                SettingGroup("Clear").containing(*clearEvents.toTypedArray()),
                SettingGroup("Normal Mode").containing(*normalFloors.toTypedArray()),
                SettingGroup("Master Mode").containing(*masterFloors.toTypedArray()),
            )
        }

        private fun addPhase(key: String, label: String): PhaseOutputs {
            val outputs = PhaseOutputs(key, label)
            phaseOutputs[key] = outputs
            phaseOutputSettings += listOf(outputs.title, outputs.hud, outputs.chat, outputs.sound)
            return outputs
        }
    }

    companion object {
        private val SHARED_PHASES = listOf("ENTRY", "CLEAR", "BLOOD_OPEN", "BLOOD_CLEAR", "COMPLETE")

        @JvmField
        val INSTANCE = DungeonHelperModule(Settings())

        private const val MAX_STATUS_ROUTE_LENGTH = 64
        private const val MAX_STATUS_LENGTH = 900
        private const val MAX_STATUS_LINE_LENGTH = 1_024
        private const val STATUS_CHANGE_INTERVAL_NANOS = 1_000_000_000L
        private const val STATUS_HEARTBEAT_NANOS = 5_000_000_000L
        private val HUD_PRIMARY = 0xFFFFD24A.toInt()
        private val HUD_SECONDARY = 0xFFB8C5DB.toInt()
        private val PREVIEW_STATUS_LINES = arrayOf(
            "Dungeon Guide · F7 · Find the Relic · 3 left",
            "Auto · detected phase",
        )
        private fun moduleSettings(settings: Settings): Array<Setting> {
            val all = mutableListOf<Setting>(
                settings.labels,
                settings.alwaysDisplaySteps,
                settings.connectors,
                settings.throughWalls,
                settings.range,
                settings.size,
                settings.ringCount,
                settings.ringHeight,
                settings.ringRadius,
                settings.ringSpeed,
                settings.ringWidth,
                settings.debug,
            )
            all += settings.phaseOutputSettings
            all += ModuleAction("Choose Dungeon Floor", "Set the floor used by Dungeon Guide.", Runnable {
                INSTANCE.openFloorPicker()
            })
            all += ModuleAction("Edit Dungeon Guide", "Edit one named, phase-ordered route for each floor.", Runnable {
                INSTANCE.openEditor()
            })
            all += ModuleAction("Edit Guide Stages", "Manage built-in and custom Guide stages and manual stage selection.", Runnable {
                INSTANCE.openStages()
            })
            all += ModuleAction("Enable All Phase Titles", "Turn the title output on for every phase at once.", Runnable {
                settings.phaseOutputs.values.forEach { it.title.setValue(true) }
            })
            all += ModuleAction("Disable All Phase Outputs", "Turn every phase title, HUD hint, chat line and sound off.", Runnable {
                settings.phaseOutputs.values.forEach {
                    it.title.setValue(false); it.hud.setValue(false); it.chat.setValue(false); it.sound.setValue(false)
                }
            })
            return all.toTypedArray()
        }

        private fun sharedPhase(phaseId: String?): Boolean =
            phaseId != null && phaseId in SHARED_PHASES

        private fun phaseOutputKey(floor: DungeonFloor?, phaseId: String?): String? =
            if (floor == null || sharedPhase(phaseId)) phaseId else "${floor.displayName()}_$phaseId"

        private fun drawStatusLines(
            graphics: GuiGraphicsExtractor,
            font: Font,
            status: Array<String>,
            x: Int,
            y: Int,
        ) {
            graphics.text(font, status[0], x, y, HUD_PRIMARY)
            graphics.text(font, status[1], x, y + 12, HUD_SECONDARY)
        }
    }
}
