package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.TikiCoords
import geiler.addons.client.config.TikiDebugLog
import geiler.addons.client.gui.TikiCoordManagerScreen
import geiler.addons.client.location.TorrhusPresence
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.tiki.TikiSolver
import geiler.addons.client.tiki.TikiStacks
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.ChatFormatting
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.AbstractSkullBlock
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.WallSkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Vector3fc
import java.util.Arrays
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Waypoints, shortest click solver, and opt-in puzzle research logging for Sneaky Tikis.
 * A tiki is three head slots above a saved coordinate; unreadable slots still rotate and count.
 */
class TikiHelperModule private constructor(settings: Settings) : Module(
    "Tiki Helper",
    "Waypoints, click solver and research logging for Sneaky Tikis.",
    Category.HUNTING,
    settings.waypoints, settings.validColor, settings.invalidColor, settings.scanInterval,
    settings.tracer, settings.tracerColor, settings.tracerWidth, settings.solver,
    settings.leftColor, settings.rightColor, settings.lockedColor, settings.range,
    settings.labelHeight, settings.textSize, settings.showLocked, settings.showUnknown,
    settings.worldLabels, settings.hypixelRule, settings.showFuturePlan, settings.futureColor,
    settings.debugLogging, settings.trackRadius, settings.trackSounds, settings.trackChat,
    settings.verifySolver, settings.echoToChat, settings.manageCoords,
) {
    private val waypoints = settings.waypoints
    private val solver = settings.solver
    private val debugLogging = settings.debugLogging

    private val validColor = settings.validColor
    private val invalidColor = settings.invalidColor
    private val tracerColor = settings.tracerColor
    private val scanInterval = settings.scanInterval
    private val tracerWidth = settings.tracerWidth
    private val tracer = settings.tracer

    private val leftColor = settings.leftColor
    private val rightColor = settings.rightColor
    private val lockedColor = settings.lockedColor
    private val range = settings.range
    private val labelHeight = settings.labelHeight
    private val textSize = settings.textSize
    private val showLocked = settings.showLocked
    private val showUnknown = settings.showUnknown
    private val worldLabels = settings.worldLabels
    private val hypixelRule = settings.hypixelRule
    private val showFuturePlan = settings.showFuturePlan
    private val futureColor = settings.futureColor

    private val trackRadius = settings.trackRadius
    private val trackSounds = settings.trackSounds
    private val trackChat = settings.trackChat
    private val verifySolver = settings.verifySolver
    private val echoToChat = settings.echoToChat

    init {
        // The flat settings passed to Module remain the persisted source of truth; groups only
        // preserve the established Click GUI order and toggle placement.
        group(
            SettingGroup("Waypoints", settings.waypoints, settings.validColor, settings.invalidColor,
                settings.scanInterval, settings.tracer, settings.tracerColor, settings.tracerWidth),
            SettingGroup("Solver", settings.solver, settings.hypixelRule, settings.trackRadius,
                settings.trackSounds, settings.trackChat, settings.verifySolver, settings.echoToChat),
            SettingGroup("Appearance", settings.leftColor, settings.rightColor, settings.lockedColor,
                settings.range, settings.labelHeight, settings.textSize, settings.showLocked,
                settings.showUnknown, settings.worldLabels, settings.showFuturePlan, settings.futureColor),
            SettingGroup.debug("Debug Logging", settings.debugLogging),
            SettingGroup("Coordinates", settings.manageCoords),
        )
    }

    private class Settings {
        val waypoints = BooleanSetting("Waypoints", true)
        val solver = BooleanSetting("Solver", true)
        val debugLogging = BooleanSetting.debug("Debug Logging", false)

        val validColor = ColorSetting("Valid Color", 0, 255, 0, 19)
        val invalidColor = ColorSetting("Invalid Color", 255, 0, 0, 20)
        val tracerColor = ColorSetting("Tracer Color", 0, 255, 0, 255)
        val scanInterval = NumberSetting("Scan Interval", 1.0f, 200.0f, 20.0f, true)
        val tracerWidth = NumberSetting("Tracer Width", 0.5f, 10.0f, 2.0f)
        val tracer = BooleanSetting("Tracer Line", true)

        val leftColor = ColorSetting("Left Color", 85, 255, 85, 255)
        val rightColor = ColorSetting("Right Color", 255, 60, 60, 255)
        val lockedColor = ColorSetting("Locked Color", 160, 160, 160, 200)
        val range = NumberSetting("Solver Range", 2.0f, 32.0f, 8.0f, true)
        val labelHeight = NumberSetting("Label Height", 0.1f, 1.5f, 0.35f)
        /** Scales HUD labels only; in-world labels are sized by Label Height. */
        val textSize = NumberSetting("Text Size", 0.5f, 4.0f, 1.0f)
        val showLocked = BooleanSetting("Show Locked", true)
        val showUnknown = BooleanSetting("Show Unknown", true)
        /** False uses the real HUD font; true selects the stroked in-world glyphs. */
        val worldLabels = BooleanSetting("World Labels", false)
        /** Plans against the wiki wording instead of the observed rule. */
        val hypixelRule = BooleanSetting("Hypixel Rule", false)
        val showFuturePlan = BooleanSetting("Show Future Clicks", true)
        val futureColor = ColorSetting("Future Color", 200, 200, 200, 130)

        val trackRadius = NumberSetting("Track Radius", 2.0f, 32.0f, 8.0f, true)
        val trackSounds = BooleanSetting("Track Sounds", true)
        val trackChat = BooleanSetting("Track Chat", true)
        val verifySolver = BooleanSetting("Verify Solver", true)
        val echoToChat = BooleanSetting("Echo To Chat", false)

        val manageCoords = ModuleAction("Manage Tiki Coords", Runnable { TikiHelperModule.openCoordManager() })
    }

    /** Last waypoint state survives chunk unloads. */
    private val remembered = LinkedHashMap<BlockPos, Boolean>()
    /** Woken locations are skipped until a fresh tiki is observed at that coordinate. */
    private val woken = HashSet<BlockPos>()

    private var slotBase: BlockPos? = null
    private var rotations: IntArray? = null
    private var hiddenIndex = -1
    private var plan: TikiSolver.Plan? = null
    private var futurePlans: List<TikiSolver.Plan> = emptyList()

    private val tracked = HashMap<BlockPos, Skull>()
    private var wasActive = false
    private var armed = false
    private var tick = 0L
    private var diagnosticClock = 0L
    private var ticksSinceScan = 0
    private var ticksSinceDebugScan = 0
    private var debugScanCenter: BlockPos? = null
    private var debugScanRadius = 0
    private var debugScanCursor = 0
    private var debugScanTotal = 0
    private var debugScanCurrent: MutableMap<BlockPos, Skull>? = null
    private var debugScanBaseline: Map<BlockPos, Skull>? = null
    private val debugScanChanged = HashSet<BlockPos>()
    private var pending: PendingVerification? = null
    private var lastLevel: ClientLevel? = null

    private var lastWaypointScan = "not-run"
    private var lastRenderSummary = "not-rendered"
    private var lastHudSummary = "not-rendered"
    private var lastAutomaticEvent = "none"
    private var lastDiagnosticState = ""
    private var lastDiagnosticClock = Long.MIN_VALUE

    override fun onEnable() {
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), true)
        resetState()
        tick = 0L
        diagnosticClock = 0L
        ticksSinceScan = 0
        ticksSinceDebugScan = 0
        lastWaypointScan = "not-run"
        lastRenderSummary = "not-rendered"
        lastHudSummary = "not-rendered"
        lastDiagnosticState = ""
        lastDiagnosticClock = Long.MIN_VALUE
        automaticEvent("ENABLED")
    }

    override fun onDisable() {
        resetState()
        lastAutomaticEvent = "DISABLED"
        reportAutomaticDiagnostics(force = true)
        TikiDebugLog.close()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.HUNTING, name(), false)
    }

    /** This module stays user-enabled outside the Torrhus Canyon but does no puzzle work there. */
    override fun isActive(): Boolean = isEnabled() && TorrhusPresence.isPresent()

    override fun inactiveReason(): String? =
        if (!isEnabled() || isActive()) null else TorrhusPresence.reason()

    private fun resetState() {
        if (armed) log("DISARMED")
        remembered.clear()
        woken.clear()
        clearSolver()
        tracked.clear()
        armed = false
        clearDebugScan()
        TikiDebugLog.close()
        pending = null
    }

    /** Re-reads waypoints on the next tick after the list changes. */
    fun requestScan() {
        ticksSinceScan = Int.MAX_VALUE - 1
        remembered.keys.retainAll(TikiCoords.all())
    }

    fun tick() {
        if (!isEnabled()) return
        diagnosticClock++
        if (!isActive()) {
            if (wasActive) {
                wasActive = false
                // Stale markers and plans belong to the region that has just been left.
                resetState()
                automaticEvent("LEFT_TORRHUS")
            }
            reportAutomaticDiagnostics()
            return
        }
        wasActive = true
        tick++

        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player

        if (level !== lastLevel) {
            lastLevel = level
            remembered.clear()
            woken.clear()
            tracked.clear()
            clearSolver()
            setArmed(false, null)
            automaticEvent("WORLD_CHANGED")
        }
        if (level == null || player == null) {
            clearSolver()
            setArmed(false, null)
            reportAutomaticDiagnostics()
            return
        }

        if (waypoints.value() && ++ticksSinceScan >= scanInterval.intValue()) {
            ticksSinceScan = 0
            scanWaypoints(level, player)
        }
        if (solver.value()) updateSolver(level, player) else clearSolver()
        tickDebug(level, player)
        reportAutomaticDiagnostics()
    }

    // ---- waypoints ------------------------------------------------------------------

    /** Re-reads only waypoints in render distance and retains their last loaded observation. */
    private fun scanWaypoints(level: ClientLevel, player: LocalPlayer) {
        val renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()
        val playerChunkX = SectionPos.blockToSectionCoord(player.getBlockX())
        val playerChunkZ = SectionPos.blockToSectionCoord(player.getBlockZ())
        var checked = 0
        var live = 0
        var cleared = 0

        for (coord in TikiCoords.all()) {
            val chunkX = SectionPos.blockToSectionCoord(coord.x)
            val chunkZ = SectionPos.blockToSectionCoord(coord.z)
            if (kotlin.math.abs(chunkX - playerChunkX) > renderDistance ||
                kotlin.math.abs(chunkZ - playerChunkZ) > renderDistance || !level.isLoaded(coord)) continue

            checked++
            var skulls = 0
            for (dy in 1..SCAN_HEIGHT) {
                val above = coord.above(dy)
                if (level.isLoaded(above) && level.getBlockState(above).block is AbstractSkullBlock) skulls++
            }
            if (skulls == 0) {
                if (woken.remove(coord)) cleared++
            }
            val valid = skulls >= REQUIRED_SKULLS && !woken.contains(coord)
            remembered[coord.immutable()] = valid
            if (valid) live++
        }
        lastWaypointScan = "checked=$checked live=$live clearedWoken=$cleared remembered=${remembered.size}"
        automaticEvent("WAYPOINT_SCAN $lastWaypointScan")
    }

    // ---- solver ---------------------------------------------------------------------

    private fun updateSolver(level: ClientLevel, player: LocalPlayer) {
        val base = nearestSlotBase(level, player, range.intValue())
        if (base == null) {
            clearSolver()
            return
        }
        slotBase = base

        val read = IntArray(SCAN_HEIGHT)
        var hidden = -1
        var hiddenCount = 0
        for (index in 0 until SCAN_HEIGHT) {
            read[index] = TikiStacks.readRotation(level, base.above(index))
            if (read[index] < 0) {
                hidden = index
                hiddenCount++
            }
        }
        rotations = read
        hiddenIndex = if (hiddenCount == 1) hidden else -1
        when (hiddenCount) {
            0 -> {
                // A hidden slot's real rotation cannot be learned, so no future sequence exists.
                val sequence = TikiSolver.solveSequence(read, rule())
                plan = sequence.firstOrNull()
                futurePlans = if (sequence.size > 1) sequence.drop(1) else emptyList()
            }
            1 -> {
                plan = TikiSolver.solveWithHidden(read, hidden)
                futurePlans = emptyList()
            }
            else -> {
                plan = null
                futurePlans = emptyList()
            }
        }
    }

    /** Three slots above the closest stored coordinate that still holds a readable tiki. */
    private fun nearestSlotBase(level: ClientLevel, player: LocalPlayer, maxRange: Int): BlockPos? {
        val rangeSq = maxRange.toDouble() * maxRange
        val position = player.position()
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (coord in TikiCoords.all()) {
            val distance = position.distanceToSqr(Vec3.atCenterOf(coord))
            if (distance > rangeSq || distance >= bestDistance) continue
            val base = coord.above()
            if (TikiStacks.readableSlots(level, base) < REQUIRED_SKULLS) continue
            bestDistance = distance
            best = base
        }
        return best
    }

    /** Selects which rule to use for solver advice and optional click verification. */
    private fun rule(): TikiSolver.Rule = if (hypixelRule.value()) TikiSolver.Rule.WIKI else TikiSolver.Rule.OBSERVED

    private fun clearSolver() {
        slotBase = null
        rotations = null
        hiddenIndex = -1
        plan = null
        futurePlans = emptyList()
    }

    // ---- optional research logging --------------------------------------------------

    private fun tickDebug(level: ClientLevel, player: LocalPlayer) {
        if (!debugLogging.value()) {
            if (armed) setArmed(false, null)
            return
        }
        val nearest = nearestCoord(player, range.intValue())
        if ((nearest != null) != armed) setArmed(nearest != null, nearest)
        if (!armed) return
        if (TikiDebugLog.path() == null) {
            // Reopen if the asynchronous writer failed while the researcher remains in range.
            TikiDebugLog.open()
        }
        if (pending != null && tick - pending!!.tick >= VERIFY_DELAY) runVerification(level)
        if (++ticksSinceDebugScan >= DEBUG_RESCAN_INTERVAL) {
            ticksSinceDebugScan = 0
            beginDebugScan(player)
        }
        advanceDebugScan(level)
    }

    private fun nearestCoord(player: LocalPlayer, maxRange: Int): BlockPos? {
        val rangeSq = maxRange.toDouble() * maxRange
        val position = player.position()
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        for (coord in TikiCoords.all()) {
            val distance = position.distanceToSqr(Vec3.atCenterOf(coord))
            if (distance <= rangeSq && distance < bestDistance) {
                bestDistance = distance
                best = coord
            }
        }
        return best
    }

    private fun setArmed(value: Boolean, near: BlockPos?) {
        if (armed == value) return
        armed = value
        pending = null
        if (!armed) {
            log("DISARMED")
            tracked.clear()
            clearDebugScan()
            TikiDebugLog.close()
            automaticEvent("RESEARCH_DISARMED")
            return
        }
        if (TikiDebugLog.path() == null) {
            TikiDebugLog.open()
            TikiDebugLog.path()?.fileName?.let { chat("Logging to $it") }
        }
        log("ARMED near=${format(near)}")
        val mc = Minecraft.getInstance()
        if (mc.level != null && mc.player != null) beginDebugScan(mc.player!!)
        automaticEvent("RESEARCH_ARMED near=${format(near)}")
    }

    private fun beginDebugScan(player: LocalPlayer?) {
        if (debugScanCurrent != null || player == null) return
        val radius = trackRadius.intValue()
        val side = radius * 2L + 1L
        val total = side * side * side
        if (total > Int.MAX_VALUE) return
        debugScanCenter = player.blockPosition().immutable()
        debugScanRadius = radius
        debugScanCursor = 0
        debugScanTotal = total.toInt()
        debugScanCurrent = HashMap()
        debugScanBaseline = HashMap(tracked)
        debugScanChanged.clear()
    }

    private fun advanceDebugScan(level: ClientLevel?) {
        val current = debugScanCurrent ?: return
        val center = debugScanCenter ?: return
        if (level == null) return
        val side = debugScanRadius * 2 + 1
        val plane = side * side
        val end = minOf(debugScanTotal, debugScanCursor + DEBUG_SCAN_BUDGET)
        while (debugScanCursor < end) {
            val localX = debugScanCursor / plane
            val remainder = debugScanCursor % plane
            val localY = remainder / side
            val localZ = remainder % side
            val pos = center.offset(localX - debugScanRadius, localY - debugScanRadius, localZ - debugScanRadius)
            if (level.isLoaded(pos)) snapshot(level, pos)?.let { current[pos.immutable()] = it }
            debugScanCursor++
        }
        if (debugScanCursor >= debugScanTotal) finishDebugScan(level)
    }

    private fun finishDebugScan(level: ClientLevel) {
        val current = debugScanCurrent ?: return
        val baseline = debugScanBaseline ?: emptyMap()
        for ((pos, skull) in current) {
            if (debugScanChanged.contains(pos)) continue
            val previous = baseline[pos]
            if (previous == null) {
                log("ADD    ${format(pos)} $skull ${stackOf(level, pos)}")
            } else if (previous != skull) {
                log("CHANGE ${format(pos)} ${previous.orientation()} -> ${skull.orientation()} ${stackOf(level, pos)}")
            }
        }
        for ((pos, skull) in baseline) {
            if (!current.containsKey(pos) && !debugScanChanged.contains(pos)) log("REMOVE ${format(pos)} last=$skull")
        }

        val merged = HashMap(current)
        for (changed in debugScanChanged) {
            val eventState = tracked[changed]
            if (eventState == null) merged.remove(changed) else merged[changed] = eventState
        }
        tracked.clear()
        tracked.putAll(merged)
        clearDebugScan()
    }

    private fun clearDebugScan() {
        debugScanCenter = null
        debugScanRadius = 0
        debugScanCursor = 0
        debugScanTotal = 0
        debugScanCurrent = null
        debugScanBaseline = null
        debugScanChanged.clear()
    }

    // ---- events ---------------------------------------------------------------------

    fun onBlockChange(pos: BlockPos, state: BlockState) {
        if (!isActive() || !debugLogging.value() || !armed) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!player.blockPosition().closerThan(pos, trackRadius.intValue().toDouble())) return

        val key = pos.immutable()
        if (debugScanCurrent != null) debugScanChanged.add(key)
        val previous = tracked[key]
        val now = if (state.block is AbstractSkullBlock) snapshot(level, key) else null
        if (now == null) {
            if (previous != null) {
                tracked.remove(key)
                log("REMOVE ${format(key)} last=$previous (became ${state.block.name.string})")
            }
            return
        }
        if (previous == null) {
            tracked[key] = now
            log("ADD    ${format(key)} $now ${stackOf(level, key)}")
        } else if (previous != now) {
            tracked[key] = now
            log("CHANGE ${format(key)} ${previous.orientation()} -> ${now.orientation()} ${stackOf(level, key)}")
        }
    }

    fun onClick(rightClick: Boolean) {
        if (!isActive()) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!debugLogging.value() || !armed) return

        val kind = if (rightClick) "RCLICK" else "LCLICK"
        val held: ItemStack = player.mainHandItem
        val heldName = " held=" + if (held.isEmpty) "empty" else held.hoverName.string
        val hit = mc.hitResult
        if (hit == null || hit.type == HitResult.Type.MISS) {
            log("$kind target=nothing$heldName")
            return
        }
        if (hit is EntityHitResult) {
            val entity: Entity = hit.entity
            log("$kind target=entity ${entity.javaClass.simpleName} \"${entity.name.string}\" at=${format(entity.blockPosition())}$heldName")
            return
        }
        if (hit !is BlockHitResult) return

        val pos = hit.blockPos
        val skull = snapshot(level, pos)
        if (skull == null) {
            log("$kind target=block ${format(pos)} ${level.getBlockState(pos).block.name.string}$heldName")
            return
        }
        log("$kind ${format(pos)} $skull face=${hit.direction.name}$heldName ${stackOf(level, pos)}")
        if (verifySolver.value()) armVerification(level, pos, if (rightClick) -1 else 1)
    }

    fun onSound(sound: Holder<SoundEvent>, x: Double, y: Double, z: Double, volume: Float, pitch: Float) {
        if (!isActive() || !debugLogging.value() || !armed || !trackSounds.value()) return
        val player = Minecraft.getInstance().player ?: return
        val distance = player.position().distanceTo(Vec3(x, y, z))
        if (distance > trackRadius.intValue() + SOUND_SLACK) return

        val name = sound.unwrapKey().map { it.identifier().toString() }
            .orElseGet { sound.value().location().toString() }
        log(String.format("SOUND  %s pitch=%.3f vol=%.2f at=%.1f,%.1f,%.1f d=%.1f", name, pitch, volume, x, y, z, distance))
    }

    /** Fed raw server text before chat-compacting mods can merge/cancel it. */
    fun onChatMessage(message: String?) {
        if (!isActive()) return
        val normalized = ChatText.plain(message ?: "")
        if (normalized.contains(AWAKENED_MESSAGE)) onTikiAwakened()
        if (debugLogging.value() && armed && trackChat.value()) log("CHAT   $normalized")
    }

    /** A woken tiki stops being a target until a fresh one appears in the same slot. */
    private fun onTikiAwakened() {
        val player = Minecraft.getInstance().player ?: return
        val coord = nearestCoord(player, range.intValue()) ?: return
        woken.add(coord.immutable())
        remembered[coord.immutable()] = false
        clearSolver()
        log("AWAKENED ${format(coord)}")
        automaticEvent("AWAKENED at=${format(coord)}")
    }

    // ---- rendering ------------------------------------------------------------------

    /** World geometry: waypoint boxes, tracer, and vector labels when the font path is off. */
    fun render(context: LevelRenderContext) {
        if (!isActive()) return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val camPos = camera.position()
        val poseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        var boxCount = 0
        var solverLabels = 0
        var debugLabels = 0
        var tracerSubmitted = false

        if (waypoints.value() && remembered.isNotEmpty()) {
            for ((pos, valid) in remembered) {
                val color = if (valid) validColor.argb() else invalidColor.argb()
                poseStack.pushPose()
                poseStack.translate(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z)
                EspRenderer.renderBox(poseStack, bufferSource, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
                    color, color, BOX_LINE_WIDTH)
                poseStack.popPose()
                boxCount++
            }
            if (tracer.value()) tracerSubmitted = renderTracer(poseStack, bufferSource, camera, camPos)
        }

        if (solver.value() && worldLabels.value()) {
            eachSolverLabel { index, text, color, height, verticalOffset ->
                EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
                    labelX(index, camPos), labelY(index, height, camPos) + verticalOffset,
                    labelZ(index, camPos), text, color, height, LABEL_LINE_WIDTH)
                solverLabels++
            }
        }

        if (debugLogging.value() && armed && worldLabels.value()) {
            val debugColor = lockedColor.argb()
            for ((pos, skull) in tracked) {
                EspRenderer.renderLabel(poseStack, bufferSource, camera.rotation(),
                    pos.x + 0.5 - camPos.x, pos.y + 0.6 - camPos.y, pos.z + 0.5 - camPos.z,
                    skull.label(), debugColor, DEBUG_LABEL_HEIGHT, LABEL_LINE_WIDTH)
                debugLabels++
            }
        }

        if (boxCount > 0 || tracerSubmitted || solverLabels > 0 || debugLabels > 0) {
            bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_QUADS)
            bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES)
        }
        lastRenderSummary = "boxes=$boxCount tracer=$tracerSubmitted solverLabels=$solverLabels researchLabels=$debugLabels"
    }

    /** HUD-font advice projected from each head into the screen's GUI coordinates. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        val mc = Minecraft.getInstance()
        if (!isActive() || !solver.value() || worldLabels.value() || mc.level == null || mc.player == null || mc.gui.hud.isHidden) {
            lastHudSummary = "hidden"
            return
        }
        val base = slotBase
        if (base == null) {
            lastHudSummary = "no-slot"
            return
        }
        val camera = mc.gameRenderer.mainCamera()
        val scale = textSize.value()
        var count = 0
        eachSolverLabel { index, text, color, _, verticalOffset ->
            val world = Vec3(base.x + 0.5, base.y + index + LABEL_ANCHOR + verticalOffset, base.z + 0.5)
            ProjectedLabelRenderer.draw(graphics, camera, mc.font, world, text, color, scale)
            count++
        }
        lastHudSummary = "labels=$count scale=$scale"
    }

    /** Emits the next run, optional locked heads, and the remaining shortest-plan runs. */
    private fun eachSolverLabel(sink: (Int, String, Int, Float, Double) -> Unit): Boolean {
        if (slotBase == null || rotations == null) return false
        val currentPlan = plan
        if (currentPlan == null) {
            if (!showUnknown.value()) return false
            sink(1, UNKNOWN_LABEL, rightColor.argb(), labelHeight.value(), 0.0)
            return true
        }

        val stack = IntArray(SCAN_HEIGHT)
        var any = false
        for (index in 0 until SCAN_HEIGHT) {
            var height = labelHeight.value()
            val text: String
            val color: Int
            if (currentPlan.index == index) {
                val count = if (currentPlan.clicks == TikiSolver.UNKNOWN_CLICKS) UNKNOWN_LABEL else currentPlan.clicks.toString()
                text = (if (currentPlan.direction > 0) "+" else "-") + count
                color = if (currentPlan.direction > 0) leftColor.argb() else rightColor.argb()
            } else if (showLocked.value() && hiddenIndex < 0 && TikiSolver.isLocked(rotations!!, index, rule())) {
                text = LOCKED_LABEL
                color = lockedColor.argb()
                height *= LOCKED_LABEL_SCALE
            } else {
                continue
            }
            sink(index, text, color, height, 0.0)
            stack[index]++
            any = true
        }

        if (showFuturePlan.value()) {
            for (future in futurePlans) {
                val index = future.index
                val text = (if (future.direction > 0) "+" else "-") + future.clicks
                val verticalOffset = -stack[index] * FUTURE_STACK_STEP
                sink(index, text, futureColor.argb(), labelHeight.value(), verticalOffset)
                stack[index]++
                any = true
            }
        }
        return any
    }

    private fun labelOffsetX(camPos: Vec3): Double {
        val base = slotBase ?: return 0.0
        val dx = camPos.x - (base.x + 0.5)
        val dz = camPos.z - (base.z + 0.5)
        val horizontal = kotlin.math.sqrt(dx * dx + dz * dz)
        return if (horizontal > 1.0e-3) -dz / horizontal * LABEL_OFFSET else 0.0
    }

    private fun labelOffsetZ(camPos: Vec3): Double {
        val base = slotBase ?: return 0.0
        val dx = camPos.x - (base.x + 0.5)
        val dz = camPos.z - (base.z + 0.5)
        val horizontal = kotlin.math.sqrt(dx * dx + dz * dz)
        return if (horizontal > 1.0e-3) dx / horizontal * LABEL_OFFSET else 0.0
    }

    private fun labelX(index: Int, camPos: Vec3): Double {
        val base = slotBase ?: return 0.0
        return base.x + 0.5 + labelOffsetX(camPos) - camPos.x
    }

    private fun labelY(index: Int, height: Float, camPos: Vec3): Double {
        val base = slotBase ?: return 0.0
        return base.y + index + LABEL_ANCHOR + height / 2.0f - camPos.y
    }

    private fun labelZ(index: Int, camPos: Vec3): Double {
        val base = slotBase ?: return 0.0
        return base.z + 0.5 + labelOffsetZ(camPos) - camPos.z
    }

    private fun renderTracer(poseStack: PoseStack, bufferSource: WorldRenderBuffer.BufferSource, camera: Camera, camPos: Vec3): Boolean {
        val target = closestValid(camPos) ?: return false
        val forward: Vector3fc = camera.forwardVector()
        val center = Vec3.atCenterOf(target)
        EspRenderer.renderLine(poseStack, bufferSource,
            forward.x() * TRACER_START_DISTANCE, forward.y() * TRACER_START_DISTANCE, forward.z() * TRACER_START_DISTANCE,
            center.x - camPos.x, center.y - camPos.y, center.z - camPos.z,
            tracerColor.argb(), tracerWidth.value())
        return true
    }

    private fun closestValid(from: Vec3): BlockPos? {
        var closest: BlockPos? = null
        var closestDistance = Double.MAX_VALUE
        for ((pos, valid) in remembered) {
            if (!valid) continue
            val distance = from.distanceToSqr(Vec3.atCenterOf(pos))
            if (distance < closestDistance) {
                closestDistance = distance
                closest = pos
            }
        }
        return closest
    }

    // ---- opt-in click verification --------------------------------------------------

    private fun armVerification(level: ClientLevel, clicked: BlockPos, direction: Int) {
        var base = clicked
        while (TikiStacks.isSkull(level, base.below())) base = base.below()
        val index = clicked.y - base.y
        if (index !in 0 until SCAN_HEIGHT) return
        val before = TikiStacks.readRotations(level, base) ?: return
        pending = PendingVerification(base.immutable(), index, direction, before, tick)
    }

    private fun runVerification(level: ClientLevel) {
        val check = pending ?: return
        pending = null
        val actual = TikiStacks.readRotations(level, check.base)
        if (actual == null) {
            log("VERIFY skipped - stack gone (completed or out of range)")
            return
        }
        if (Arrays.equals(actual, check.before)) {
            log("VERIFY no-op - click did not register (rotation cooldown)")
            return
        }
        val expected = TikiSolver.applyMove(check.before, check.index, check.direction, rule())
        if (Arrays.equals(expected, actual)) {
            log("VERIFY ok ${Arrays.toString(check.before)} -> ${Arrays.toString(actual)}")
        } else {
            log("VERIFY MISMATCH click=idx${check.index} dir=${if (check.direction > 0) "+" else "-"}" +
                " before=${Arrays.toString(check.before)} expected=${Arrays.toString(expected)} actual=${Arrays.toString(actual)}")
        }
    }

    // ---- helpers --------------------------------------------------------------------

    private fun snapshot(level: ClientLevel, pos: BlockPos): Skull? {
        val state = level.getBlockState(pos)
        if (state.block !is AbstractSkullBlock) return null
        val rotation = if (state.hasProperty(SkullBlock.ROTATION)) state.getValue(SkullBlock.ROTATION) else -1
        val facing = if (state.hasProperty(WallSkullBlock.FACING)) state.getValue(WallSkullBlock.FACING).name else ""
        return Skull(state.block.name.string, rotation, facing, ownerOf(level, pos))
    }

    private fun ownerOf(level: ClientLevel, pos: BlockPos): String {
        val skull = level.getBlockEntity(pos) as? SkullBlockEntity ?: return ""
        val profile = skull.ownerProfile ?: return ""
        val name = profile.name().orElse("?")
        // Decorative heads can share a blank-ish name; a short texture hash distinguishes variants.
        val textures = profile.partialProfile().properties().get("textures")
        val skin = if (textures.isEmpty()) "" else Integer.toHexString(textures.iterator().next().value().hashCode())
        return name + if (skin.isEmpty()) "" else "/$skin"
    }

    private fun stackOf(level: ClientLevel, pos: BlockPos): String {
        var base = pos
        while (TikiStacks.isSkull(level, base.below())) base = base.below()
        val labels = ArrayList<String>()
        var cursor = base
        while (TikiStacks.isSkull(level, cursor)) {
            labels.add(snapshot(level, cursor)?.label() ?: "?")
            cursor = cursor.above()
        }
        return "stack@${format(base)}=$labels"
    }

    private fun log(line: String) {
        if (!debugLogging.value()) return
        TikiDebugLog.write(tick, line)
        if (echoToChat.value()) chat(line)
    }

    private fun automaticEvent(event: String) {
        lastAutomaticEvent = event.replace('\r', ' ').replace('\n', ' ').take(MAX_EVENT_LENGTH)
        reportAutomaticDiagnostics()
    }

    /**
     * Automatic, opt-in independent state trace. Changed snapshots are coalesced to one every
     * 20 enabled ticks; unchanged state gets a 100-tick heartbeat. It stores no chat or player names.
     */
    private fun reportAutomaticDiagnostics(force: Boolean = false) {
        val present = TorrhusPresence.isPresent()
        val location = if (present) "Torrhus" else safeDiagnostic(TorrhusPresence.reason() ?: "inactive", 96)
        val valid = remembered.values.count { it }
        val solverState = if (!solver.value()) "off" else {
            val base = slotBase?.let(::format) ?: "none"
            val advice = plan?.let { "${it.index}:${it.direction}:${it.clicks}/${it.remaining}" } ?: "none"
            "base=$base rotations=${rotations?.contentToString() ?: "none"} hidden=$hiddenIndex rule=${rule().name} next=$advice future=${futurePlans.size}"
        }
        val scanState = if (debugScanCurrent == null) "idle" else
            "${debugScanCursor}/${debugScanTotal},changed=${debugScanChanged.size}"
        val state = "enabled=${isEnabled()} active=${isActive()} location=$location " +
            "waypoints=${waypoints.value()}/${TikiCoords.size()} remembered=$valid/${remembered.size} scan=[$lastWaypointScan] " +
            "solver={$solverState} research=${debugLogging.value()}/armed:$armed/tracked:${tracked.size}/scan:$scanState " +
            "pending=${pending != null} radius=${trackRadius.intValue()} sounds=${trackSounds.value()} chat=${trackChat.value()} " +
            "render={$lastRenderSummary} hud={$lastHudSummary} event=$lastAutomaticEvent"
        val changed = state != lastDiagnosticState
        val elapsed = diagnosticClock - lastDiagnosticClock
        if (force || lastDiagnosticClock == Long.MIN_VALUE || changed && elapsed >= DIAGNOSTIC_CHANGE_INTERVAL || !changed && elapsed >= DIAGNOSTIC_HEARTBEAT) {
            lastDiagnosticState = state
            lastDiagnosticClock = diagnosticClock
            TikiDebugLog.write(tick, "STATUS $state".take(MAX_DIAGNOSTIC_LINE_LENGTH))
        }
    }

    private fun safeDiagnostic(text: String, maxLength: Int): String =
        text.replace(DIAGNOSTIC_SAFE, "_").take(maxLength)

    private fun format(pos: BlockPos?): String = if (pos == null) "none" else "${pos.x},${pos.y},${pos.z}"

    private data class PendingVerification(
        val base: BlockPos,
        val index: Int,
        val direction: Int,
        val before: IntArray,
        val tick: Long,
    )

    private data class Skull(val blockId: String, val rotation: Int, val facing: String, val owner: String) {
        fun orientation(): String = if (rotation >= 0) "rot=$rotation" else "facing=$facing"

        fun label(): String {
            if (rotation >= 0) return rotation.toString()
            return when (facing.lowercase(Locale.ROOT)) {
                "north" -> "N"
                "east" -> "E"
                "south" -> "S"
                "west" -> "W"
                else -> "?"
            }
        }

        override fun toString(): String = orientation() + " block=$blockId" + if (owner.isEmpty()) "" else " owner=$owner"
    }

    companion object {
        private const val SCAN_HEIGHT = TikiStacks.STACK_HEIGHT
        private const val REQUIRED_SKULLS = 2
        private const val TRACER_START_DISTANCE = 0.5
        private const val BOX_LINE_WIDTH = 2.0f
        private const val LOCKED_LABEL = "."
        private const val UNKNOWN_LABEL = "?"
        private const val LABEL_LINE_WIDTH = 4.0f
        private const val LOCKED_LABEL_SCALE = 0.6f
        private const val LABEL_OFFSET = 0.8
        private const val LABEL_ANCHOR = 0.25
        private const val FUTURE_STACK_STEP = 0.18
        private const val DEBUG_RESCAN_INTERVAL = 20
        private const val DEBUG_SCAN_BUDGET = 4_096
        private const val SOUND_SLACK = 8.0
        private const val VERIFY_DELAY = 8
        private const val DEBUG_LABEL_HEIGHT = 0.3f
        private const val DIAGNOSTIC_CHANGE_INTERVAL = 20L
        private const val DIAGNOSTIC_HEARTBEAT = 100L
        private const val MAX_EVENT_LENGTH = 160
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 4_096
        private const val AWAKENED_MESSAGE = "Looks like you woke the Tiki up!"
        private val DIAGNOSTIC_SAFE = Regex("[^A-Za-z0-9 _.:=-]")

        @JvmField
        val INSTANCE = TikiHelperModule(Settings())

        private fun openCoordManager() {
            val gui = Minecraft.getInstance().gui
            gui.setScreen(TikiCoordManagerScreen(gui.screen()))
        }

        private fun chat(message: String) {
            val mc = Minecraft.getInstance()
            if (mc.gui == null) return
            mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[Tiki] ").withStyle(ChatFormatting.AQUA)
                    .append(Component.literal(message).withStyle(ChatFormatting.GRAY)),
            )
        }
    }
}
