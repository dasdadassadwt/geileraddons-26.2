package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.SimonSaysState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.sim.DeviceSimGeometry
import geiler.addons.client.sim.DeviceSimService
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/**
 * Simon Says device solver after Odin's Simon Says (sequence, SS skip first phase, start limit, progress message). It
 * thinks in Floor 7 coordinates; while `/ga sim ss` runs, world positions are mapped to and from those.
 */
class SimonSaysModule private constructor() : Module(
    "Simon Says",
    "Shows the buttons of the Simon Says device in order in F7 and M7.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val firstColor = spec.color("firstColor")
    private val secondColor = spec.color("secondColor")
    private val thirdColor = spec.color("thirdColor")
    private val style = spec.choice("style")
    private val announceProgress = spec.toggle("announceProgress")
    private val announceReset = spec.toggle("announceReset")
    private val blockWrong = spec.toggle("blockWrong")
    private val invertKey = spec.toggle("invertKey")
    private val blockWrongStart = spec.toggle("blockWrongStart")
    private val maxStartClicks = spec.slider("maxStartClicks")

    private val state = SimonSaysState()
    private var blocked = 0
    /** Frames that drew a step at its button spot because no stone button was there yet (diagnostics only). */
    private var missing = 0
    private var lastLevel: Any? = null
    private var lastServerTicks = -1L
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        state.reset()
        blocked = 0
        missing = 0
        diagnostics.enable(
            "blockWrong=${blockWrong.value()} blockWrongStart=${blockWrongStart.value()} maxStartClicks=${maxStartClicks.intValue()} " +
                "announce=${announceProgress.value()} announceReset=${announceReset.value()} style=${style.value()}",
        )
    }

    override fun onDisable() {
        state.reset()
        diagnostics.event("DISABLE blocked=$blocked")
        diagnostics.disable()
    }

    private fun onFloor7(): Boolean = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7

    private fun sim(): DeviceSimGeometry.Frame? = DeviceSimService.frame(DeviceSimService.Kind.SS)

    private fun active(): Boolean = sim() != null || onFloor7()

    private fun toWorld(real: BlockPos): BlockPos = sim()?.toSim(real) ?: real

    private fun toReal(world: BlockPos): BlockPos = sim()?.toReal(world) ?: world

    private fun nearDevice(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val device = Vec3(110.5, 121.5, 93.5)
        return player.position().distanceToSqr(sim()?.toSim(device) ?: device) <= NEAR_SQUARED
    }

    // ---- read-only view for Auto Simon Says ----

    /** The device is in play (Floor 7 or the sim) and the player is near it. */
    fun deviceInReach(): Boolean = isEnabled() && active() && nearDevice()

    /** The next button to press in Floor 7 coordinates, or null when no solution is known. */
    fun nextButton(): BlockPos? = state.next()?.let { BlockPos.of(it) }

    /** Step of the current round the next button is, and the round length. */
    fun step(): Int = state.size() - state.remaining().size

    fun roundSize(): Int = state.size()

    /** A Floor 7 point in the world the player is in (unchanged on Floor 7, turned onto the sim wall in `/ga sim ss`). */
    fun worldPoint(real: Vec3): Vec3 = sim()?.toSim(real) ?: real

    fun worldBlock(real: BlockPos): BlockPos = toWorld(real)

    fun startButton(): BlockPos = BlockPos(START_X, START_Y, START_Z)

    /** Seen the start button pressed (by anyone) since this device reset; false means the device still waits to be started. */
    private var startSeen = false

    /** The device has not been started yet: no sequence and no start press seen since the last reset. */
    fun awaitingStart(): Boolean = !startSeen && state.size() == 0 && state.firstPhase

    /** A new sim device: nothing solved yet, first phase. */
    fun onSimStart() {
        state.reset()
        startSeen = false
        lastServerTicks = -1L
        if (isEnabled()) diagnostics.event("SIM_ATTACH")
    }

    fun tick() {
        if (!isEnabled()) return
        val level = Minecraft.getInstance().level
        if (level == null || !active()) {
            state.reset()
            startSeen = false
            lastServerTicks = -1L
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            state.reset()
            startSeen = false
            lastServerTicks = -1L
        }
        // Odin runs the grid-reset check on server ticks; replay the ones that passed since the last client tick.
        val now = DungeonAssistModule.INSTANCE.serverTicks()
        // The sim runs on client ticks; there is no server clock to follow.
        val delta = if (sim() != null) 1L
        else if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, MAX_CATCH_UP)
        lastServerTicks = now
        if (state.firstPhase && delta > 0) {
            val buttons = stoneButtonCount(level)
            for (i in 0 until delta) state.serverTick(buttons)
            if (!state.firstPhase) diagnostics.event("FIRST_PHASE end=GRID_RESET sequence=${state.size()}")
        }
        diagnostics.status {
            "enabled=true near=${nearDevice()} firstPhase=${state.firstPhase} sequence=${state.remaining().size}/${state.size()} serverTicks=$now blocked=$blocked buttonMissing=$missing suppressedEvents=$it"
        }
    }

    /** Stone buttons currently on the 4x4 button grid (the device shows them only while it waits for input). */
    private fun stoneButtonCount(level: net.minecraft.world.level.Level): Int {
        var count = 0
        for (y in 120..123) for (z in 92..95) {
            if (level.getBlockState(toWorld(BlockPos(110, y, z))).`is`(Blocks.STONE_BUTTON)) count++
        }
        return count
    }

    /**
     * A client block change, from `LevelChunk.setBlockState` like Odin's `BlockUpdateEvent`: server updates (single and
     * multi-block) and the client's own predicted button press. Only called when the state really changed.
     */
    fun onBlockChange(pos: BlockPos, old: BlockState, new: BlockState) {
        // Every client block change comes through here, so check the module before mapping anything.
        if (!isEnabled()) return
        val real = toReal(pos)
        val x = real.x
        val y = real.y
        val z = real.z
        if (x !in 110..111 || y !in 120..123 || z !in 91..95) return
        if (!active()) return
        if (x == START_X && y == START_Y && z == START_Z) {
            if (new.`is`(Blocks.STONE_BUTTON) && new.getValue(BlockStateProperties.POWERED)) {
                startSeen = true
                val deviceReset = state.startPowered()
                diagnostics.event("START_BUTTON restart deviceReset=$deviceReset")
                // Never from the sim: on your own island it would reach your real party.
                if (deviceReset && announceReset.value() && sim() == null && nearDevice()) {
                    Minecraft.getInstance().player?.connection?.sendCommand("pc SS Reset!")
                }
            }
            return
        }
        if (z !in 92..95) return
        if (x == 111) {
            if (new.`is`(Blocks.OBSIDIAN) && old.`is`(Blocks.SEA_LANTERN)) {
                val before = state.size()
                state.lanternOff(x, y, z)
                diagnostics.event("LANTERN_OFF y=$y z=$z sequence=${state.remaining()} added=${state.size() != before} first=${state.firstPhase}")
            }
            return
        }
        val level = Minecraft.getInstance().level ?: return
        if (new.isAir) {
            val before = state.size()
            state.gridAir(airButtonCount(level))
            if (before > 0 && state.size() == 0) diagnostics.event("GRID_CLEARED dropped=$before")
        } else if (old.`is`(Blocks.STONE_BUTTON) && new.`is`(Blocks.STONE_BUTTON) && new.getValue(BlockStateProperties.POWERED)) {
            val roundSize = state.size()
            state.buttonPowered(x, y, z)
            diagnostics.event("PRESSED y=$y z=$z round=$roundSize left=${state.remaining().size} first=${state.firstPhase}")
        }
    }

    /** Button-grid positions that are air right now; more than eight means the device cleared the grid for a new round. */
    private fun airButtonCount(level: net.minecraft.world.level.Level): Int {
        var count = 0
        for (y in 120..123) for (z in 92..95) {
            if (level.getBlockState(toWorld(BlockPos(110, y, z))).isAir) count++
        }
        return count
    }

    /**
     * A right click on a block (Odin's `BlockInteractEvent`); returns true when it must be cancelled. The pointer itself moves
     * on the button's powered change, which the client predicts for its own press.
     */
    fun onBlockUse(worldPos: BlockPos): Boolean {
        if (!isEnabled() || !active()) return false
        val pos = toReal(worldPos)
        val sneaking = Minecraft.getInstance().player?.isShiftKeyDown == true
        if (pos.x == START_X && pos.y == START_Y && pos.z == START_Z) {
            if (blockWrongStart.value() && state.startClicked(maxStartClicks.intValue(), sneaking)) {
                blocked++
                diagnostics.event("START_BLOCKED blocked=$blocked max=${maxStartClicks.intValue()}")
                return true
            }
            AutoSimonSaysModule.INSTANCE.onStartPressed()
            return false
        }
        if (!SimonSaysState.inButtons(pos.x, pos.y, pos.z)) return false
        // Odin's progress message: clicking the round's last button reports the round length (sent before the block check).
        if (announceProgress.value() && sim() == null && state.size() > 0 && state.isLast(pos.x, pos.y, pos.z)) {
            Minecraft.getInstance().player?.connection?.sendCommand("pc SS ${state.size()}/4")
        }
        // Odin blocks every grid click that is not the next button, also while no solution is known; sneaking overrides.
        if (blockWrong.value() && !(sneaking xor invertKey.value()) && !state.isNext(pos.x, pos.y, pos.z)) {
            blocked++
            diagnostics.event("BLOCKED y=${pos.y} z=${pos.z} next=${state.next()?.let { BlockPos.of(it).toShortString() }} blocked=$blocked")
            return true
        }
        diagnostics.event("CLICK y=${pos.y} z=${pos.z} next=${state.isNext(pos.x, pos.y, pos.z)}")
        return false
    }

    /** Goldor's entry line starts P3: the start-click limit counts from zero again (Odin). */
    fun onChatMessage(message: String) {
        if (!isEnabled() || message != GOLDOR) return
        state.resetStartClicks()
        startSeen = false
        diagnostics.event("GOLDOR startClicks=0")
    }

    /** Breaking a device block would desync the solver, so it is cancelled. */
    fun onBlockAttack(pos: BlockPos): Boolean =
        isEnabled() && onFloor7() &&
            (SimonSaysState.inLights(pos.x, pos.y, pos.z) || SimonSaysState.inButtons(pos.x, pos.y, pos.z))

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !active()) return
        val sequence = state.remaining()
        if (sequence.isEmpty() || !nearDevice()) return
        val level = Minecraft.getInstance().level ?: return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val mode = style.value()
        val fill = mode == "FILLED" || mode == "FILLED_OUTLINE"
        val outline = mode == "OUTLINE" || mode == "FILLED_OUTLINE"
        for ((index, packed) in sequence.withIndex()) {
            val pos = BlockPos.of(packed)
            val color = when (index) {
                0 -> firstColor.argb()
                1 -> secondColor.argb()
                else -> thirdColor.argb()
            }
            // The real button shape (follows the press). While the device still shows the order there is no button yet,
            // so the step is drawn where the button will appear, as Odin does.
            val worldPos = toWorld(pos)
            val block = level.getBlockState(worldPos)
            val shape = if (block.`is`(Blocks.STONE_BUTTON)) block.getShape(level, worldPos) else null
            val box = if (shape == null || shape.isEmpty) {
                missing++
                turned(SimonSaysState.buttonSpotBox(pos.x, pos.y, pos.z))
            } else {
                val b = shape.bounds()
                SimonSaysState.highlightBox(
                    worldPos.x, worldPos.y, worldPos.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                )
            }
            EspRenderer.renderBox(
                context.poseStack(), buffers,
                box[0] - camera.x, box[1] - camera.y, box[2] - camera.z, box[3] - box[0], box[4] - box[1], box[5] - box[2],
                if (fill) color else 0, if (outline) (color or (0xFF shl 24)) else 0, 2.0f, false,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** A Floor 7 box turned onto the sim wall; unchanged on Floor 7. */
    private fun turned(box: DoubleArray): DoubleArray {
        val frame = sim() ?: return box
        val a = frame.toSim(Vec3(box[0], box[1], box[2]))
        val b = frame.toSim(Vec3(box[3], box[4], box[5]))
        return doubleArrayOf(
            minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z),
        )
    }

    companion object {
        private const val NEAR_SQUARED = 225.0
        private const val MAX_CATCH_UP = 40L
        private const val START_X = 110
        private const val START_Y = 121
        private const val START_Z = 91
        private const val GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?"
        private val spec = ModuleSettingsSpec.of("Simon Says")

        @JvmField
        val INSTANCE = SimonSaysModule()
    }
}
