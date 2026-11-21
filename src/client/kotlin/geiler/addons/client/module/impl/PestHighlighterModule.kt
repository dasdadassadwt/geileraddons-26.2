package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.entity.ClientEntitySnapshot
import geiler.addons.client.farming.PestAnimation
import geiler.addons.client.farming.PestDetectionCache
import geiler.addons.client.farming.PestDetector
import geiler.addons.client.farming.PestKind
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.UUID

/** Highlights all known Hypixel Garden pests, which are represented by textured head ArmorStands. */
class PestHighlighterModule private constructor(settings: Settings) : Module(
    "Pest Highlighter",
    "Highlights all known Garden pests by their head texture.",
    Category.FARMING,
    settings.box, settings.circle, settings.tracer, settings.showName, settings.depthCheck, settings.scanInterval,
    settings.outlineColor, settings.outlineWidth, settings.fillColor,
    settings.circleColor, settings.circleWidth, settings.circleRadius, settings.circleSpeed, settings.ringCount,
    settings.tracerColor, settings.tracerWidth, settings.nameColor, settings.labelSize,
) {
    private val box = settings.box
    private val circle = settings.circle
    private val tracer = settings.tracer
    private val showName = settings.showName
    private val depthCheck = settings.depthCheck
    private val scanInterval = settings.scanInterval
    private val outlineColor = settings.outlineColor
    private val outlineWidth = settings.outlineWidth
    private val fillColor = settings.fillColor
    private val circleColor = settings.circleColor
    private val circleWidth = settings.circleWidth
    private val circleRadius = settings.circleRadius
    private val circleSpeed = settings.circleSpeed
    private val ringCount = settings.ringCount
    private val tracerColor = settings.tracerColor
    private val tracerWidth = settings.tracerWidth
    private val nameColor = settings.nameColor
    private val labelSize = settings.labelSize

    /** Rebuilt wholesale by the client-thread scan and consumed by world/HUD rendering. */
    private val sightings = ArrayList<Sighting>()

    /** Render hooks consume an immutable replacement rather than the list the tick mutates. */
    @Volatile
    private var publishedSightings: List<Sighting> = emptyList()

    /** Shared with the plot-border fallback scan: the same heads are classified once per tick. */
    private val detectionCache = PestDetectionCache.SHARED
    private var ticksSinceScan = 0
    private var wasActive = false
    private var lastLevel: ClientLevel? = null

    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0
    private var lastScanRangeBlocks = -1
    private var scansInWindow = 0
    private var scanEntitiesInWindow = 0
    private var scanArmorStandsInWindow = 0
    private var scanPestsInWindow = 0
    private var worldFramesInWindow = 0
    private var worldInactiveFramesInWindow = 0
    private var worldEmptyFramesInWindow = 0
    private var worldDeadSightingsInWindow = 0
    private var worldTargetsInWindow = 0
    private var worldBoxCallsInWindow = 0
    private var worldRingCallsInWindow = 0
    private var worldTracerCallsInWindow = 0
    private var worldFlushesInWindow = 0
    private var hudFramesInWindow = 0
    private var hudInactiveFramesInWindow = 0
    private var hudNamesDisabledFramesInWindow = 0
    private var hudEmptyFramesInWindow = 0
    private var hudUnavailableFramesInWindow = 0
    private var hudHiddenFramesInWindow = 0
    private var hudDeadSightingsInWindow = 0
    private var hudLabelCallsInWindow = 0

    init {
        group(
            SettingGroup("Display", settings.box, settings.circle, settings.tracer, settings.showName,
                settings.depthCheck, settings.scanInterval),
            SettingGroup("Appearance").containing(
                SettingGroup("Box", settings.outlineColor, settings.outlineWidth, settings.fillColor),
                SettingGroup("Ring", settings.circleColor, settings.circleWidth, settings.circleRadius,
                    settings.circleSpeed, settings.ringCount),
                SettingGroup("Tracer", settings.tracerColor, settings.tracerWidth),
                SettingGroup("Name", settings.nameColor, settings.labelSize),
            ),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean = when {
        setting === outlineColor || setting === outlineWidth || setting === fillColor -> box.value()
        setting === circleColor || setting === circleWidth || setting === circleRadius ||
            setting === circleSpeed || setting === ringCount -> circle.value()
        setting === tracerColor || setting === tracerWidth -> tracer.value()
        setting === nameColor || setting === labelSize -> showName.value()
        else -> true
    }

    override fun isActive(): Boolean = isEnabled() && HypixelModApi.onGarden()

    override fun inactiveReason(): String? {
        if (!isEnabled() || isActive()) return null
        return HypixelModApi.reasonNotOn(Island.GARDEN)
    }

    override fun onEnable() {
        diagnosticTick = 0L
        lastDiagnosticTick = Long.MIN_VALUE
        lastDiagnosticState = ""
        suppressedDiagnosticChanges = 0
        lastScanRangeBlocks = -1
        resetDiagnosticWindow()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), true)
        writeDiagnostic("STATUS enabled=true;awaiting=client-tick")
        lastDiagnosticTick = diagnosticTick
    }

    override fun onDisable() {
        val cleared = sightings.size
        reset()
        writeDiagnostic(
            "STATUS enabled=false;active=false;cleared=" + cleared +
                ";sightings=0;" + diagnosticSummary(),
        )
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.FARMING, name(), false)
        lastDiagnosticTick = Long.MIN_VALUE
        lastDiagnosticState = ""
        suppressedDiagnosticChanges = 0
        resetDiagnosticWindow()
    }

    private fun reset() {
        sightings.clear()
        publishSightings()
        // This cache is shared with the plot-border fallback scan. Disabling one consumer must not
        // discard classifications still in use by the other.
        ticksSinceScan = 0
        wasActive = false
        lastLevel = null
    }

    /** Publishes the working list as one immutable replacement for the render hooks to read. */
    private fun publishSightings() {
        publishedSightings = Collections.unmodifiableList(ArrayList(sightings))
    }

    /** Runs on the client tick and never performs network or blocking work. */
    fun tick() {
        if (!isEnabled()) {
            if (wasActive || sightings.isNotEmpty()) reset()
            return
        }

        diagnosticTick++
        val minecraft = Minecraft.getInstance()
        val active = isActive()
        if (!active) {
            if (wasActive || sightings.isNotEmpty()) reset()
            reportDiagnostics(false, minecraft.level != null, minecraft.player != null)
            return
        }
        wasActive = true

        val level = minecraft.level
        val player = minecraft.player
        if (level != lastLevel) {
            lastLevel = level
            sightings.clear()
            publishSightings()
            ticksSinceScan = 0
        }
        if (level == null || player == null) {
            reportDiagnostics(true, level != null, player != null)
            return
        }

        // A pest disappears as soon as it is caught; do not keep its last box until the next sweep.
        if (sightings.removeIf {
                !PestHighlighterBehavior.isCurrentSighting(it.entity.isRemoved, it.entity.isAlive)
            }
        ) {
            publishSightings()
        }
        ticksSinceScan++
        if (!PestHighlighterBehavior.scanDue(ticksSinceScan, scanInterval.intValue())) {
            reportDiagnostics(true, true, true)
            return
        }
        ticksSinceScan = 0
        scan(level, player)
        reportDiagnostics(true, true, true)
    }

    private fun scan(level: ClientLevel, player: LocalPlayer) {
        val minecraft = Minecraft.getInstance()
        val range = PestHighlighterBehavior.scanRangeBlocks(
            minecraft.options.getEffectiveRenderDistance() * 16.0,
        )
        val nearby = ClientEntitySnapshot.nearby(level, player, range)

        val found = ArrayList<Sighting>()
        val visible = HashSet<UUID>()
        var armorStandCandidates = 0
        for (candidate in nearby) {
            val armorStand = candidate as? ArmorStand
            if (!PestHighlighterBehavior.isCandidate(armorStand != null, armorStand?.isAlive == true)) {
                continue
            }
            val entity = armorStand ?: continue
            armorStandCandidates++
            val id = entity.getUUID()
            visible.add(id)
            val head: ItemStack = entity.getItemBySlot(EquipmentSlot.HEAD)
            detectionCache.get(id, head) { PestDetector.detect(entity) }
                .ifPresent { kind -> found.add(Sighting(kind, entity)) }
        }
        detectionCache.retainAll(visible)
        sightings.clear()
        sightings.addAll(found)
        publishSightings()

        lastScanRangeBlocks = range.toInt()
        scansInWindow = incrementBounded(scansInWindow)
        scanEntitiesInWindow = addBounded(scanEntitiesInWindow, nearby.size)
        scanArmorStandsInWindow = addBounded(scanArmorStandsInWindow, armorStandCandidates)
        scanPestsInWindow = addBounded(scanPestsInWindow, found.size)
    }

    // ---- rendering ----------------------------------------------------------------------

    fun render(context: LevelRenderContext) {
        worldFramesInWindow = incrementBounded(worldFramesInWindow)
        val visibleSightings = publishedSightings
        if (!isActive()) {
            worldInactiveFramesInWindow = incrementBounded(worldInactiveFramesInWindow)
            return
        }
        if (visibleSightings.isEmpty()) {
            worldEmptyFramesInWindow = incrementBounded(worldEmptyFramesInWindow)
            return
        }

        val minecraft = Minecraft.getInstance()
        val camera: Camera = minecraft.gameRenderer.mainCamera()
        val cameraPosition = camera.position()
        val poseStack: PoseStack = context.poseStack()
        val bufferSource: WorldRenderBuffer.BufferSource = WorldRenderBuffer.current()
        val forward: Vector3fc = camera.forwardVector()
        val depth = depthCheck.value()
        val partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true)
        val elapsedTicks: Double =
            minecraft.level?.gameTime?.plus(partialTick)?.toDouble() ?: 0.0
        var drew = false
        var targets = 0
        var deadSightings = 0
        var boxCalls = 0
        var ringCalls = 0
        var tracerCalls = 0
        val worldLayers = PestHighlighterBehavior.worldLayerMask(box.value(), circle.value(), tracer.value())

        for (sighting in visibleSightings) {
            val entity = sighting.entity
            if (!PestHighlighterBehavior.isCurrentSighting(entity.isRemoved, entity.isAlive)) {
                deadSightings++
                continue
            }
            targets++
            val bounds: AABB = entity.boundingBox
            val center: Vec3 = bounds.getCenter()

            if ((worldLayers and PestHighlighterBehavior.BOX_LAYER) != 0) {
                EspRenderer.renderBox(
                    poseStack, bufferSource,
                    bounds.minX - cameraPosition.x, bounds.minY - cameraPosition.y, bounds.minZ - cameraPosition.z,
                    bounds.getXsize(), bounds.getYsize(), bounds.getZsize(),
                    fillColor.argb(), outlineColor.argb(), outlineWidth.value(), depth,
                )
                boxCalls++
                drew = true
            }

            var ringHeights: DoubleArray? = null
            if ((worldLayers and PestHighlighterBehavior.RING_LAYER) != 0) {
                val feet = bounds.minY + RING_ENDPOINT_PADDING
                val head = maxOf(feet, bounds.maxY - RING_ENDPOINT_PADDING)
                val positions = PestAnimation.ringPositions(
                    feet, head, elapsedTicks, circleSpeed.value().toDouble(), ringCount.intValue(),
                )
                ringHeights = positions
                for (ringY in positions) {
                    EspRenderer.renderRing(
                        poseStack, bufferSource,
                        center.x - cameraPosition.x, ringY - cameraPosition.y, center.z - cameraPosition.z,
                        circleRadius.value(), circleColor.argb(), circleWidth.value(), depth,
                    )
                    ringCalls++
                }
                drew = true
            }

            if ((worldLayers and PestHighlighterBehavior.TRACER_LAYER) != 0) {
                var targetX = center.x
                var targetY = center.y
                var targetZ = center.z
                if (ringHeights != null) {
                    val target = PestAnimation.closestRingTarget(
                        center.x, center.z,
                        cameraPosition.x, cameraPosition.y, cameraPosition.z,
                        circleRadius.value(), ringHeights,
                    )
                    targetX = target.x
                    targetY = target.y
                    targetZ = target.z
                }
                EspRenderer.renderLine(
                    poseStack, bufferSource,
                    forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE,
                    forward.z() * TRACER_START_DISTANCE,
                    targetX - cameraPosition.x, targetY - cameraPosition.y, targetZ - cameraPosition.z,
                    tracerColor.argb(), tracerWidth.value(), depth,
                )
                tracerCalls++
                drew = true
            }
        }

        worldTargetsInWindow = addBounded(worldTargetsInWindow, targets)
        worldDeadSightingsInWindow = addBounded(worldDeadSightingsInWindow, deadSightings)
        worldBoxCallsInWindow = addBounded(worldBoxCallsInWindow, boxCalls)
        worldRingCallsInWindow = addBounded(worldRingCallsInWindow, ringCalls)
        worldTracerCallsInWindow = addBounded(worldTracerCallsInWindow, tracerCalls)
        if (drew) {
            GeilerAddonsRenderTypes.endBatches(bufferSource)
            worldFlushesInWindow = incrementBounded(worldFlushesInWindow)
        }
    }

    /** Projects pest names onto the HUD because 26.1's reachable level stages cannot draw Font text. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        hudFramesInWindow = incrementBounded(hudFramesInWindow)
        val visibleSightings = publishedSightings
        if (!isActive()) {
            hudInactiveFramesInWindow = incrementBounded(hudInactiveFramesInWindow)
            return
        }
        if (!showName.value()) {
            hudNamesDisabledFramesInWindow = incrementBounded(hudNamesDisabledFramesInWindow)
            return
        }
        if (visibleSightings.isEmpty()) {
            hudEmptyFramesInWindow = incrementBounded(hudEmptyFramesInWindow)
            return
        }
        val minecraft = Minecraft.getInstance()
        if (minecraft.level == null || minecraft.player == null) {
            hudUnavailableFramesInWindow = incrementBounded(hudUnavailableFramesInWindow)
            return
        }
        if (minecraft.gui.hud.isHidden()) {
            hudHiddenFramesInWindow = incrementBounded(hudHiddenFramesInWindow)
            return
        }

        val camera = minecraft.gameRenderer.mainCamera()
        val scale = labelSize.value()
        var deadSightings = 0
        var labelCalls = 0
        for (sighting in visibleSightings) {
            val entity = sighting.entity
            if (!PestHighlighterBehavior.isCurrentSighting(entity.isRemoved, entity.isAlive)) {
                deadSightings++
                continue
            }
            val label = "Pest: " + sighting.kind.displayName()
            val bounds: AABB = ProjectedLabelRenderer.entityBox(entity)
            val world = Vec3(bounds.getCenter().x, bounds.maxY + LABEL_HEIGHT, bounds.getCenter().z)
            ProjectedLabelRenderer.draw(graphics, camera, minecraft.font, world, label, nameColor.argb(), scale)
            labelCalls++
        }
        hudDeadSightingsInWindow = addBounded(hudDeadSightingsInWindow, deadSightings)
        hudLabelCallsInWindow = addBounded(hudLabelCallsInWindow, labelCalls)
    }

    private fun reportDiagnostics(active: Boolean, levelReady: Boolean, playerReady: Boolean) {
        val interval = scanInterval.intValue()
        val ticksUntilScan = (interval - ticksSinceScan).coerceAtLeast(0)
        val state = (
            "enabled=true,active=" + active +
                ",level=" + levelReady +
                ",player=" + playerReady +
                ",scanInterval=" + interval +
                ",ticksUntilScan=" + ticksUntilScan +
                ",lastRangeBlocks=" + lastScanRangeBlocks +
                ",sightings=" + publishedSightings.size +
                ",settings=box:" + box.value() +
                ",ring:" + circle.value() +
                ",tracer:" + tracer.value() +
                ",names:" + showName.value() +
                ",depth:" + depthCheck.value() +
                "," + diagnosticAppearanceSummary() +
                "," + diagnosticSummary()
            ).take(MAX_DIAGNOSTIC_LINE_LENGTH)
        val changed = state != lastDiagnosticState
        val heartbeat = lastDiagnosticTick == Long.MIN_VALUE ||
            diagnosticTick - lastDiagnosticTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat) return
        if (changed && lastDiagnosticTick != Long.MIN_VALUE &&
            diagnosticTick - lastDiagnosticTick < DIAGNOSTIC_MIN_INTERVAL_TICKS
        ) {
            suppressedDiagnosticChanges = incrementBounded(suppressedDiagnosticChanges)
            return
        }

        writeDiagnostic(
            "STATUS " + state +
                ",suppressedChanges=" + suppressedDiagnosticChanges +
                ",lineLimit=" + MAX_DIAGNOSTIC_LINE_LENGTH,
        )
        lastDiagnosticState = state
        lastDiagnosticTick = diagnosticTick
        suppressedDiagnosticChanges = 0
        resetDiagnosticWindow()
    }

    private fun diagnosticAppearanceSummary(): String =
        "appearance{outline=" + outlineColor.hex() + "@" + outlineWidth.value() +
            ",fill=" + fillColor.hex() +
            ",ring=" + circleColor.hex() + "@" + circleWidth.value() +
            "/radius=" + circleRadius.value() +
            "/speed=" + circleSpeed.value() +
            "/count=" + ringCount.intValue() +
            ",tracer=" + tracerColor.hex() + "@" + tracerWidth.value() +
            ",name=" + nameColor.hex() + "@" + labelSize.value() + "}"

    private fun diagnosticSummary(): String =
        "scan{runs=" + scansInWindow +
            ",entities=" + scanEntitiesInWindow +
            ",stands=" + scanArmorStandsInWindow +
            ",pests=" + scanPestsInWindow +
            "},world{frames=" + worldFramesInWindow +
            ",inactive=" + worldInactiveFramesInWindow +
            ",empty=" + worldEmptyFramesInWindow +
            ",dead=" + worldDeadSightingsInWindow +
            ",targets=" + worldTargetsInWindow +
            ",boxes=" + worldBoxCallsInWindow +
            ",rings=" + worldRingCallsInWindow +
            ",tracers=" + worldTracerCallsInWindow +
            ",flushes=" + worldFlushesInWindow +
            "},hud{frames=" + hudFramesInWindow +
            ",inactive=" + hudInactiveFramesInWindow +
            ",namesOff=" + hudNamesDisabledFramesInWindow +
            ",empty=" + hudEmptyFramesInWindow +
            ",unavailable=" + hudUnavailableFramesInWindow +
            ",hidden=" + hudHiddenFramesInWindow +
            ",dead=" + hudDeadSightingsInWindow +
            ",labels=" + hudLabelCallsInWindow + "}"

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.FARMING,
            name(),
            diagnosticTick,
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH),
        )
    }

    private fun resetDiagnosticWindow() {
        scansInWindow = 0
        scanEntitiesInWindow = 0
        scanArmorStandsInWindow = 0
        scanPestsInWindow = 0
        worldFramesInWindow = 0
        worldInactiveFramesInWindow = 0
        worldEmptyFramesInWindow = 0
        worldDeadSightingsInWindow = 0
        worldTargetsInWindow = 0
        worldBoxCallsInWindow = 0
        worldRingCallsInWindow = 0
        worldTracerCallsInWindow = 0
        worldFlushesInWindow = 0
        hudFramesInWindow = 0
        hudInactiveFramesInWindow = 0
        hudNamesDisabledFramesInWindow = 0
        hudEmptyFramesInWindow = 0
        hudUnavailableFramesInWindow = 0
        hudHiddenFramesInWindow = 0
        hudDeadSightingsInWindow = 0
        hudLabelCallsInWindow = 0
    }

    private fun incrementBounded(value: Int): Int =
        if (value >= MAX_DIAGNOSTIC_COUNTER) MAX_DIAGNOSTIC_COUNTER else value + 1

    private fun addBounded(value: Int, amount: Int): Int =
        (value.toLong() + amount.coerceAtLeast(0).toLong())
            .coerceAtMost(MAX_DIAGNOSTIC_COUNTER.toLong())
            .toInt()

    /** Declared in `module-settings.json`; the "Circle" id keeps its persisted key and shows as "Ring Mode". */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Pest Highlighter")
        val box = spec.toggle("box")
        val circle = spec.toggle("circle")
        val tracer = spec.toggle("tracer")
        val showName = spec.toggle("showName")
        val depthCheck = spec.toggle("depthCheck")
        val scanInterval = spec.slider("scanInterval")

        val outlineColor = spec.color("outlineColor")
        val outlineWidth = spec.slider("outlineWidth")
        val fillColor = spec.color("fillColor")

        val circleColor = spec.color("circleColor")
        val circleWidth = spec.slider("circleWidth")
        val circleRadius = spec.slider("circleRadius")
        val circleSpeed = spec.slider("circleSpeed")
        val ringCount = spec.slider("ringCount")

        val tracerColor = spec.color("tracerColor")
        val tracerWidth = spec.slider("tracerWidth")

        val nameColor = spec.color("nameColor")
        val labelSize = spec.slider("labelSize")
    }

    private data class Sighting(val kind: PestKind, val entity: ArmorStand)

    companion object {
        /** Java-facing singleton used by client initialization and the existing PestChecks entrypoint. */
        @JvmField
        val INSTANCE = PestHighlighterModule(Settings())

        /** Beyond this distance a pest is not useful to the player, whatever the view distance is. */
        private const val TRACER_START_DISTANCE = 1.0
        private const val RING_ENDPOINT_PADDING = 0.05
        private const val LABEL_HEIGHT = 0.4
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_COUNTER = 1_000_000
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 2048
    }
}
