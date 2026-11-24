package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.SimonSaysState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/** Simon Says device solver after Odin's Simon Says (sequence, SS skip first phase, start limit, progress message). */
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

    private fun nearDevice(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        val dx = player.x - 110.5
        val dy = player.y - 121.5
        val dz = player.z - 93.5
        return dx * dx + dy * dy + dz * dz <= NEAR_SQUARED
    }

    fun tick() {
        if (!isEnabled()) return
        val level = Minecraft.getInstance().level
        if (level == null || !onFloor7()) {
            state.reset()
            lastServerTicks = -1L
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            state.reset()
            lastServerTicks = -1L
        }
        // Odin runs the grid-reset check on server ticks; replay the ones that passed since the last client tick.
        val now = DungeonAssistModule.INSTANCE.serverTicks()
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, MAX_CATCH_UP)
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
        val pos = BlockPos.MutableBlockPos()
        for (y in 120..123) for (z in 92..95) {
            pos.set(110, y, z)
            if (level.getBlockState(pos).`is`(Blocks.STONE_BUTTON)) count++
        }
        return count
    }

    /**
     * A client block change, from `LevelChunk.setBlockState` like Odin's `BlockUpdateEvent`: server updates (single and
     * multi-block) and the client's own predicted button press. Only called when the state really changed.
     */
    fun onBlockChange(pos: BlockPos, old: BlockState, new: BlockState) {
        val x = pos.x
        val y = pos.y
        val z = pos.z
        if (x !in 110..111 || y !in 120..123 || z !in 91..95) return
        if (!isEnabled() || !onFloor7()) return
        if (x == START_X && y == START_Y && z == START_Z) {
            if (new.`is`(Blocks.STONE_BUTTON) && new.getValue(BlockStateProperties.POWERED)) {
                val deviceReset = state.startPowered()
                diagnostics.event("START_BUTTON restart deviceReset=$deviceReset")
                if (deviceReset && announceReset.value() && nearDevice()) {
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
        val pos = BlockPos.MutableBlockPos()
        for (y in 120..123) for (z in 92..95) {
            pos.set(110, y, z)
            if (level.getBlockState(pos).isAir) count++
        }
        return count
    }

    /**
     * A right click on a block (Odin's `BlockInteractEvent`); returns true when it must be cancelled. The pointer itself moves
     * on the button's powered change, which the client predicts for its own press.
     */
    fun onBlockUse(pos: BlockPos): Boolean {
        if (!isEnabled() || !onFloor7()) return false
        val sneaking = Minecraft.getInstance().player?.isShiftKeyDown == true
        if (pos.x == START_X && pos.y == START_Y && pos.z == START_Z) {
            if (blockWrongStart.value() && state.startClicked(maxStartClicks.intValue(), sneaking)) {
                blocked++
                diagnostics.event("START_BLOCKED blocked=$blocked max=${maxStartClicks.intValue()}")
                return true
            }
            return false
        }
        if (!SimonSaysState.inButtons(pos.x, pos.y, pos.z)) return false
        // Odin's progress message: clicking the round's last button reports the round length (sent before the block check).
        if (announceProgress.value() && state.size() > 0 && state.isLast(pos.x, pos.y, pos.z)) {
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
        diagnostics.event("GOLDOR startClicks=0")
    }

    /** Breaking a device block would desync the solver, so it is cancelled. */
    fun onBlockAttack(pos: BlockPos): Boolean =
        isEnabled() && onFloor7() &&
            (SimonSaysState.inLights(pos.x, pos.y, pos.z) || SimonSaysState.inButtons(pos.x, pos.y, pos.z))

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !onFloor7()) return
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
            val block = level.getBlockState(pos)
            val shape = if (block.`is`(Blocks.STONE_BUTTON)) block.getShape(level, pos) else null
            val box = if (shape == null || shape.isEmpty) {
                missing++
                SimonSaysState.buttonSpotBox(pos.x, pos.y, pos.z)
            } else {
                val b = shape.bounds()
                SimonSaysState.highlightBox(pos.x, pos.y, pos.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
            }
            EspRenderer.renderBox(
                context.poseStack(), buffers,
                box[0] - camera.x, box[1] - camera.y, box[2] - camera.z, box[3] - box[0], box[4] - box[1], box[5] - box[2],
                if (fill) color else 0, if (outline) (color or (0xFF shl 24)) else 0, 2.0f, false,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
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
