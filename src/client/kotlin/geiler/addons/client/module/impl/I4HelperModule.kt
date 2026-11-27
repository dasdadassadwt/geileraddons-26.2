package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.sim.DeviceSimGeometry
import geiler.addons.client.sim.DeviceSimService
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Highlights the nine-block device panel on the Catacombs F7/M7 4th-device puzzle. Its rules are in Floor 7
 * coordinates; while `/ga sim i4` runs, world positions are mapped to and from those.
 */
class I4HelperModule private constructor(settings: Settings) : Module(
    "i4 helper",
    "Highlights the F7/M7 device panel and marks the next block to click.",
    Category.F7,
    settings.completedColor,
    settings.activeColor,
    settings.aimColor,
    settings.prefireColor,
    settings.aimSphereSize,
    settings.prefireSphereSize,
) {
    private val completedColor = settings.completedColor
    private val activeColor = settings.activeColor
    private val aimColor = settings.aimColor
    private val prefireColor = settings.prefireColor
    private val aimSphereSize = settings.aimSphereSize
    private val prefireSphereSize = settings.prefireSphereSize

    private val highlighted = IntArray(I4HelperRules.panelSize())
    private val delayedSounds = mutableListOf<DelayedSound>()

    private var onDevice = false
    private var notified = false
    private var contextLevel: ClientLevel? = null
    private var contextFloor: DungeonFloor? = null

    private var diagnosticTick = 0L
    private var lastStatusTick = 0L
    private var eventBudgetTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0
    private var acceptedBlockChanges = 0
    private var rejectedCompletions = 0
    private var suppressedSubtitles = 0
    private var lastRender = "not-rendered"

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    override fun onEnable() {
        clearDeviceState()
        delayedSounds.clear()
        contextLevel = null
        contextFloor = null
        diagnosticTick = 0
        lastStatusTick = 0
        eventBudgetTick = Long.MIN_VALUE
        eventsThisTick = 0
        suppressedEvents = 0
        acceptedBlockChanges = 0
        rejectedCompletions = 0
        suppressedSubtitles = 0
        lastRender = "not-rendered"
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), true)
        writeDiagnostic("EVENT FEATURE_ENABLED awaiting=client-tick")
    }

    override fun onDisable() {
        writeDiagnostic("EVENT FEATURE_DISABLED " + diagnosticState())
        clearDeviceState()
        delayedSounds.clear()
        contextLevel = null
        contextFloor = null
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), false)
    }

    /** Called on the client tick; Minecraft state remains on its owning thread. */
    fun tick() {
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        if (!isEnabled()) return
        try {
            tickEnabled()
        } finally {
            reportStatusIfDue()
        }
    }

    private fun tickEnabled() {
        val client = Minecraft.getInstance()
        val floor = DungeonContextTracker.currentFloor()
        val level = client.level
        val player = client.player
        // Before the context check: finishing the sim ends its context, but the done sounds still play out.
        tickDelayedSounds()
        val sim = sim()
        if ((sim == null && !I4HelperRules.supportsDungeonContext(DungeonContextTracker.inDungeon(), floor))
            || level == null || player == null
        ) {
            val hadContext = contextLevel != null || onDevice
            clearDeviceState()
            contextLevel = null
            contextFloor = null
            if (hadContext) {
                recordEvent("CONTEXT_CLEARED dungeon=" + DungeonContextTracker.inDungeon()
                    + " floor=" + (floor?.name ?: "unknown"))
            }
            return
        }

        if (contextLevel !== level || contextFloor != floor) {
            val changed = contextLevel != null || onDevice
            clearDeviceState()
            contextLevel = level
            contextFloor = floor
            if (changed) recordEvent("CONTEXT_CHANGED floor=" + floor?.name)
        }

        val feet = sim?.toReal(player.position()) ?: player.position()
        val nowOnDevice = I4HelperRules.isOnDevice(feet.x, Mth.floor(feet.y), feet.z)
        if (nowOnDevice != onDevice) {
            onDevice = nowOnDevice
            if (onDevice) {
                // Untouched blocks have no server update, so start as remaining and correct the
                // current emerald from the loaded level.
                highlighted.fill(I4HelperRules.REMAINING)
                syncActiveFromLevel(reset = true)
                recordEvent("DEVICE_ENTERED floor=" + floor?.name + " active=" + activeCount())
            } else {
                notified = false
                highlighted.fill(I4HelperRules.NONE)
                recordEvent("DEVICE_EXITED")
            }
        } else if (onDevice) {
            // Recovers the emerald after a chunk reload or missed packet without reopening
            // positions already known complete.
            syncActiveFromLevel(reset = false)
        }
    }

    private fun sim(): DeviceSimGeometry.Frame? = DeviceSimService.frame(DeviceSimService.Kind.I4)

    private fun toWorld(real: BlockPos): BlockPos = sim()?.toSim(real) ?: real

    /** For Auto i4: the panel index of the current emerald while standing on the device, or -1. */
    fun activeTargetIndex(): Int {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) return -1
        return highlighted.indexOfFirst { it == I4HelperRules.ACTIVE }
    }

    /** For Auto i4: the first aim spot of the current emerald in world coordinates (turned onto the sim), or null. */
    fun activeAimPoint(): Vec3? {
        val active = activeTargetIndex()
        if (active < 0) return null
        val done = highlighted.indices.filter { highlighted[it] == I4HelperRules.COMPLETED }
        val spot = I4HelperRules.aimSpots(active, done).firstOrNull() ?: return null
        return sim()?.toSim(spot) ?: spot
    }

    /**
     * For Auto i4 prefire: the orange spheres, the aim spots of every target not lit yet, as (panel index, world point),
     * without the spots of the current emerald. Empty off the device.
     */
    fun prefireAimPoints(): List<Pair<Int, Vec3>> {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) return emptyList()
        val done = highlighted.indices.filter { highlighted[it] == I4HelperRules.COMPLETED }
        val active = highlighted.indexOfFirst { it == I4HelperRules.ACTIVE }
        val activeSpots = if (active >= 0) I4HelperRules.aimSpots(active, done) else emptyList()
        val out = ArrayList<Pair<Int, Vec3>>()
        for (i in highlighted.indices) {
            if (highlighted[i] != I4HelperRules.REMAINING) continue
            for (spot in I4HelperRules.aimSpots(i, done)) {
                if (I4HelperRules.matchesAny(spot, activeSpots)) continue
                out.add(i to (sim()?.toSim(spot) ?: spot))
            }
        }
        return out
    }

    /** A new sim device: drop what was seen of the last one. */
    fun onSimStart() {
        if (!isEnabled()) return
        clearDeviceState()
        contextLevel = null
        recordEvent("SIM_ATTACH")
    }

    /** The sim was finished, which on Floor 7 a chat line would say. */
    fun onSimDone() {
        if (isEnabled() && onDevice) notifyDone()
    }

    private fun hasCurrentDeviceContext(): Boolean {
        val client = Minecraft.getInstance()
        val floor = DungeonContextTracker.currentFloor()
        return client.level != null && client.player != null && client.level === contextLevel
            && floor == contextFloor
            && (sim() != null || I4HelperRules.supportsDungeonContext(DungeonContextTracker.inDungeon(), floor))
    }

    private fun clearDeviceState() {
        onDevice = false
        notified = false
        highlighted.fill(I4HelperRules.NONE)
    }

    /**
     * Only the emerald is recoverable: completed and untouched blocks both revert to blue
     * terracotta, so past progress is kept only while its block update was observed.
     */
    private fun syncActiveFromLevel(reset: Boolean) {
        val level = Minecraft.getInstance().level ?: return
        for (i in highlighted.indices) {
            val pos = toWorld(I4HelperRules.panelPosition(i))
            if (!level.isLoaded(pos)) continue
            val block = level.getBlockState(pos).block
            if (block === Blocks.EMERALD_BLOCK) {
                highlighted[i] = I4HelperRules.ACTIVE
            } else if (reset) {
                highlighted[i] = I4HelperRules.REMAINING
            }
        }
    }

    fun onBlockChange(pos: BlockPos, state: BlockState) {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) return
        val index = I4HelperRules.findPanelIndex(sim()?.toReal(pos) ?: pos)
        if (index == -1) return

        val block = state.block
        val previous = highlighted[index]
        val next = I4HelperRules.afterBlockChange(
            previous,
            block === Blocks.DYED_TERRACOTTA.pick(DyeColor.BLUE),
            block === Blocks.EMERALD_BLOCK,
        )
        if (next != previous) {
            highlighted[index] = next
            acceptedBlockChanges = incrementBounded(acceptedBlockChanges)
            recordEvent("BLOCK_STATE index=" + index + " from=" + I4HelperRules.stateName(previous)
                + " to=" + I4HelperRules.stateName(next))
        }
    }

    fun onChatMessage(message: String?) {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) return
        val completedName = I4HelperRules.completedDeviceUsername(message) ?: return
        val player = Minecraft.getInstance().player ?: return
        // Some Hypixel lines embed legacy codes; normalize names on both sides before comparing.
        if (ChatText.stripForMatch(completedName) != player.gameProfile.name()) {
            rejectedCompletions = incrementBounded(rejectedCompletions)
            recordEvent("DEVICE_COMPLETION otherPlayer=true")
            return
        }
        notifyDone()
    }

    /** @return true if the subtitle should be suppressed */
    fun onSubtitle(message: String?): Boolean {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) return false
        if (!I4HelperRules.shouldSuppressSubtitle(message)) return false
        suppressedSubtitles = incrementBounded(suppressedSubtitles)
        recordEvent("SUBTITLE_SUPPRESSED matchedKnownDeviceLine=true")
        return true
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !onDevice || !hasCurrentDeviceContext()) {
            lastRender = "skipped-inactive"
            return
        }

        val cameraPosition = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val poseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()

        // Favor whichever side remains open when a neighboring target is complete.
        val done = mutableListOf<Int>()
        var activeIndex = -1
        for (i in highlighted.indices) {
            if (highlighted[i] == I4HelperRules.COMPLETED) done.add(i)
            if (highlighted[i] == I4HelperRules.ACTIVE) activeIndex = i
        }

        var submittedBoxes = 0
        var submittedCrosses = 0
        for (i in highlighted.indices) {
            val state = highlighted[i]
            if (state != I4HelperRules.COMPLETED && state != I4HelperRules.ACTIVE) continue

            val pos = toWorld(I4HelperRules.panelPosition(i))
            val color = if (state == I4HelperRules.COMPLETED) completedColor.argb() else activeColor.argb()
            poseStack.pushPose()
            try {
                poseStack.translate(
                    pos.x - cameraPosition.x,
                    pos.y - cameraPosition.y,
                    pos.z - cameraPosition.z,
                )
                EspRenderer.renderBox(poseStack, bufferSource, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, color, color, 2.0f)
            } finally {
                poseStack.popPose()
            }
            submittedBoxes++

            if (state == I4HelperRules.ACTIVE) {
                // The cross gives the active target a shape cue in addition to its green color.
                val x = pos.x - cameraPosition.x
                val y = pos.y + 1.015 - cameraPosition.y
                val z = pos.z - cameraPosition.z
                EspRenderer.renderLine(poseStack, bufferSource, x + 0.18, y, z + 0.18,
                    x + 0.82, y, z + 0.82, 0xFFFFFFFF.toInt(), 2.0f)
                EspRenderer.renderLine(poseStack, bufferSource, x + 0.18, y, z + 0.82,
                    x + 0.82, y, z + 0.18, 0xFFFFFFFF.toInt(), 2.0f)
                submittedCrosses++
            }
        }

        // Yellow: aim spots for the current emerald target.
        val activeSpots = if (activeIndex != -1) I4HelperRules.aimSpots(activeIndex, done) else emptyList()
        var submittedAimSpheres = 0
        for (spot in activeSpots) {
            sphereAt(poseStack, bufferSource, cameraPosition, spot, aimSphereSize.value(), aimColor.argb())
            submittedAimSpheres++
        }

        // Orange: future targets, excluding positions already highlighted yellow.
        var submittedPrefireSpheres = 0
        for (i in highlighted.indices) {
            if (highlighted[i] != I4HelperRules.REMAINING) continue
            for (spot in I4HelperRules.aimSpots(i, done)) {
                if (I4HelperRules.matchesAny(spot, activeSpots)) continue
                sphereAt(poseStack, bufferSource, cameraPosition, spot, prefireSphereSize.value(),
                    prefireColor.argb())
                submittedPrefireSpheres++
            }
        }

        bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_QUADS)
        bufferSource.endBatch(GeilerAddonsRenderTypes.ESP_LINES)
        lastRender = "boxes=$submittedBoxes,crosses=$submittedCrosses,aimSpheres=$submittedAimSpheres," +
            "prefireSpheres=$submittedPrefireSpheres"
    }

    private fun sphereAt(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        spot: Vec3,
        radius: Float,
        color: Int,
    ) {
        val at = sim()?.toSim(spot) ?: spot
        EspRenderer.renderSphere(
            poseStack,
            bufferSource,
            at.x - cameraPosition.x,
            at.y - cameraPosition.y,
            at.z - cameraPosition.z,
            radius,
            color,
        )
    }

    private fun notifyDone() {
        if (notified) return
        notified = true

        val client = Minecraft.getInstance()
        client.gui.hud.setTimes(0, I4HelperRules.DONE_TITLE_DURATION_TICKS, 0)
        client.gui.hud.setTitle(Component.literal(""))
        client.gui.hud.setSubtitle(Component.literal(I4HelperRules.DONE_TITLE).withStyle(ChatFormatting.GREEN))

        val player = client.player
        if (player != null) {
            player.level().playLocalSound(
                player,
                SoundEvents.NOTE_BLOCK_PLING.value(),
                SoundSource.PLAYERS,
                1.0f,
                I4HelperRules.IMMEDIATE_SOUND_PITCH,
            )
        }
        delayedSounds.add(DelayedSound(I4HelperRules.FIRST_DELAYED_SOUND_TICKS, I4HelperRules.FIRST_DELAYED_SOUND_PITCH))
        delayedSounds.add(DelayedSound(I4HelperRules.SECOND_DELAYED_SOUND_TICKS, I4HelperRules.SECOND_DELAYED_SOUND_PITCH))
        recordEvent("DEVICE_COMPLETED localPlayer=true delayedSounds=2")
    }

    private fun tickDelayedSounds() {
        if (delayedSounds.isEmpty()) return
        val player = Minecraft.getInstance().player
        val iterator = delayedSounds.iterator()
        while (iterator.hasNext()) {
            val sound = iterator.next()
            sound.ticksRemaining--
            if (sound.ticksRemaining <= 0) {
                if (player != null) {
                    player.level().playLocalSound(
                        player,
                        SoundEvents.NOTE_BLOCK_PLING.value(),
                        SoundSource.PLAYERS,
                        1.0f,
                        sound.pitch,
                    )
                }
                iterator.remove()
                recordEvent("DELAYED_SOUND played=true pitch=" + sound.pitch)
            }
        }
    }

    private fun reportStatusIfDue() {
        if (diagnosticTick - lastStatusTick < STATUS_INTERVAL_TICKS) return
        writeDiagnostic("STATUS " + diagnosticState() + " suppressedEvents=" + suppressedEvents)
        lastStatusTick = diagnosticTick
        acceptedBlockChanges = 0
        rejectedCompletions = 0
        suppressedSubtitles = 0
        suppressedEvents = 0
    }

    private fun diagnosticState(): String {
        val client = Minecraft.getInstance()
        val level = client.level
        val player = client.player
        val remaining = highlighted.count { it == I4HelperRules.REMAINING }
        val active = activeCount()
        val completed = highlighted.count { it == I4HelperRules.COMPLETED }
        val panelStates = highlighted.indices.joinToString(",") { index ->
            index.toString() + ":" + I4HelperRules.stateName(highlighted[index])
        }
        val pending = delayedSounds.joinToString(",") { it.ticksRemaining.toString() }.ifEmpty { "none" }
        val location = if (player == null) "none" else
            Mth.floor(player.x).toString() + "," + Mth.floor(player.y) + "," + Mth.floor(player.z)
        return "enabled=${isEnabled()}," +
            "inDungeon=${DungeonContextTracker.inDungeon()}," +
            "floor=${DungeonContextTracker.currentFloor()?.name ?: "unknown"}," +
            "level=${level != null}," +
            "contextMatches=${level != null && level === contextLevel}," +
            "onDevice=$onDevice," +
            "playerBlock=$location," +
            "blocks{remaining=$remaining,active=$active,completed=$completed}," +
            "panelStates=[$panelStates]," +
            "notified=$notified," +
            "delayedSounds=$pending," +
            "settings{completedArgb=${completedColor.argb()}," +
            "activeArgb=${activeColor.argb()}," +
            "aimArgb=${aimColor.argb()}," +
            "prefireArgb=${prefireColor.argb()}," +
            "aimSize=${aimSphereSize.value()}," +
            "prefireSize=${prefireSphereSize.value()}}," +
            "events{blockChanges=$acceptedBlockChanges," +
            "otherCompletions=$rejectedCompletions," +
            "suppressedSubtitles=$suppressedSubtitles}," +
            "lastRender=$lastRender"
    }

    private fun activeCount(): Int = highlighted.count { it == I4HelperRules.ACTIVE }

    private fun incrementBounded(value: Int): Int = (value + 1).coerceAtMost(MAX_DIAGNOSTIC_COUNTER)

    private fun recordEvent(event: String) {
        if (eventBudgetTick != diagnosticTick) {
            eventBudgetTick = diagnosticTick
            eventsThisTick = 0
        }
        if (eventsThisTick >= MAX_DIAGNOSTIC_EVENTS_PER_TICK) {
            suppressedEvents = incrementBounded(suppressedEvents)
            return
        }
        eventsThisTick++
        writeDiagnostic("EVENT " + event + " " + diagnosticState())
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            diagnosticTick,
            line.replace('\r', ' ').replace('\n', ' ').take(MAX_DIAGNOSTIC_LINE_CHARS),
        )
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("i4 helper")
        val completedColor = spec.color("completedColor")
        val activeColor = spec.color("activeColor")
        val aimColor = spec.color("aimColor")
        val prefireColor = spec.color("prefireColor")
        val aimSphereSize = spec.slider("aimSphereSize")
        val prefireSphereSize = spec.slider("prefireSphereSize")
    }

    private data class DelayedSound(var ticksRemaining: Int, val pitch: Float)

    companion object {
        @JvmField
        val INSTANCE = I4HelperModule(Settings())

        private const val STATUS_INTERVAL_TICKS = 100L
        private const val MAX_DIAGNOSTIC_EVENTS_PER_TICK = 8
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 1024
        private const val MAX_DIAGNOSTIC_COUNTER = 1_000_000

        /** Retained as a Java static method for the existing context check. */
        @JvmStatic
        fun supportsDungeonContext(inDungeon: Boolean, floor: DungeonFloor?): Boolean =
            I4HelperRules.supportsDungeonContext(inDungeon, floor)
    }
}

/** Deterministic context, matching, state, and geometry rules used by I4 and its direct checks. */
object I4HelperRules {
    const val NONE = 0
    const val REMAINING = 1
    const val ACTIVE = 2
    const val COMPLETED = 3
    const val DONE_TITLE = "i4 Done!"
    const val DONE_TITLE_DURATION_TICKS = 30
    const val IMMEDIATE_SOUND_PITCH = 1.414f
    const val FIRST_DELAYED_SOUND_TICKS = 3
    const val FIRST_DELAYED_SOUND_PITCH = 1.587f
    const val SECOND_DELAYED_SOUND_TICKS = 6
    const val SECOND_DELAYED_SOUND_PITCH = 1.782f

    private val positions = arrayOf(
        BlockPos(68, 130, 50), BlockPos(66, 130, 50), BlockPos(64, 130, 50),
        BlockPos(68, 128, 50), BlockPos(66, 128, 50), BlockPos(64, 128, 50),
        BlockPos(68, 126, 50), BlockPos(66, 126, 50), BlockPos(64, 126, 50),
    )
    private val completedDevice =
        Regex("""^(?:\[[^]]*]\s*)?([A-Za-z0-9_]{1,16}) completed a device! \(\d/\d\)$""")
    private val suppressedSubtitles = listOf(
        Regex("""^.* completed a device! \(\d/\d\)$"""),
        Regex("""^.* activated a terminal! \(\d/\d\)$"""),
        Regex("""^.* activated a lever! \(\d/\d\)$"""),
        Regex("""^The gate will open in 5 seconds!$"""),
        Regex("""^The gate has been destroyed!$"""),
    )

    @JvmStatic
    fun panelSize(): Int = positions.size

    @JvmStatic
    fun panelPosition(index: Int): BlockPos = positions[index]

    @JvmStatic
    fun findPanelIndex(pos: BlockPos?): Int = if (pos == null) -1 else positions.indexOfFirst { it == pos }

    @JvmStatic
    fun supportsDungeonContext(inDungeon: Boolean, floor: DungeonFloor?): Boolean =
        inDungeon && (floor == DungeonFloor.F7 || floor == DungeonFloor.M7)

    /** Strict edges and exact feet height match the live pressure plate's active area. */
    @JvmStatic
    fun isOnDevice(x: Double, feetY: Int, z: Double): Boolean =
        x > 62.0 && x < 65.0 && feetY == 127 && z > 33.0 && z < 37.0

    @JvmStatic
    fun afterBlockChange(current: Int, isBlueTerracotta: Boolean, isEmerald: Boolean): Int = when {
        isBlueTerracotta -> if (current == ACTIVE) COMPLETED else REMAINING
        isEmerald -> ACTIVE
        else -> current
    }

    @JvmStatic
    fun completedDeviceUsername(message: String?): String? {
        val normalized = ChatText.plain(message ?: "").trim()
        return completedDevice.matchEntire(normalized)?.groupValues?.get(1)
    }

    @JvmStatic
    fun shouldSuppressSubtitle(message: String?): Boolean {
        val normalized = ChatText.plain(message ?: "").trim()
        return suppressedSubtitles.any { it.matches(normalized) }
    }

    /** Each returned vector is an aim sphere's center in world coordinates. */
    @JvmStatic
    fun aimSpots(index: Int, completedIndices: List<Int>): List<Vec3> {
        val pos = positions[index]
        val blockX = pos.x.toDouble()
        val blockY = pos.y + 1.0
        val blockZ = pos.z.toDouble()
        return when (index % 3) {
            2 -> listOf(Vec3(blockX + 1.5, blockY, blockZ))
            0 -> listOf(Vec3(blockX - 0.5, blockY, blockZ))
            else -> {
                val leftDone = completedIndices.contains(index + 1)
                val rightDone = completedIndices.contains(index - 1)
                when {
                    leftDone && !rightDone -> listOf(Vec3(blockX + 1.5, blockY, blockZ))
                    rightDone && !leftDone -> listOf(Vec3(blockX - 0.5, blockY, blockZ))
                    else -> listOf(
                        Vec3(blockX - 0.5, blockY, blockZ),
                        Vec3(blockX + 1.5, blockY, blockZ),
                    )
                }
            }
        }
    }

    @JvmStatic
    fun matchesAny(spot: Vec3, others: List<Vec3>): Boolean = others.any { other ->
        kotlin.math.abs(spot.x - other.x) < 1e-6
            && kotlin.math.abs(spot.y - other.y) < 1e-6
            && kotlin.math.abs(spot.z - other.z) < 1e-6
    }

    @JvmStatic
    fun stateName(state: Int): String = when (state) {
        NONE -> "none"
        REMAINING -> "remaining"
        ACTIVE -> "active"
        COMPLETED -> "completed"
        else -> "unknown"
    }
}
