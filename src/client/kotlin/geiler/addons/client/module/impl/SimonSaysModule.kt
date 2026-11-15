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

/** Simon Says device solver (NoFrills' sequence tracking with Odin's start-button reset and progress message). */
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
    private val blockWrong = spec.toggle("blockWrong")
    private val invertKey = spec.toggle("invertKey")

    private val state = SimonSaysState()
    private var clicksThisRound = 0
    private var blocked = 0
    /** Sequence buttons that were not a stone button when drawn (diagnostics only). */
    private var missing = 0
    private var lastLevel: Any? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    override fun onEnable() {
        state.reset()
        clicksThisRound = 0
        blocked = 0
        missing = 0
        diagnostics.enable("blockWrong=${blockWrong.value()} announce=${announceProgress.value()} style=${style.value()}")
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
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            state.reset()
            clicksThisRound = 0
        }
        diagnostics.status { "enabled=true near=${nearDevice()} sequence=${state.remaining().size} blocked=$blocked buttonMissing=$missing suppressedEvents=$it" }
    }

    /** A server block change; [old] is the state before it, if the chunk was loaded. */
    fun onBlockChange(pos: BlockPos, old: BlockState?, new: BlockState) {
        if (!isEnabled() || !onFloor7()) return
        val level = Minecraft.getInstance().level ?: return
        val x = pos.x
        val y = pos.y
        val z = pos.z
        if (x == START_X && y == START_Y && z == START_Z) {
            if (new.`is`(Blocks.STONE_BUTTON) && new.getValue(BlockStateProperties.POWERED)) {
                state.reset()
                clicksThisRound = 0
                diagnostics.event("START_BUTTON reset")
            }
            return
        }
        if (SimonSaysState.inLights(x, y, z)) {
            if (new.`is`(Blocks.SEA_LANTERN) && old?.`is`(Blocks.OBSIDIAN) == true) {
                val before = state.remaining().size
                state.lanternLit(x, y, z)
                if (state.remaining().size != before) diagnostics.event("LANTERN y=$y z=$z sequence=${state.remaining().size}")
            }
            return
        }
        if (SimonSaysState.inButtons(x, y, z)) {
            val eastIsLantern = level.getBlockState(pos.east()).`is`(Blocks.SEA_LANTERN)
            if (old?.isAir == true && new.isAir && eastIsLantern) {
                state.skipHead()
                diagnostics.event("SKIP sequence=${state.remaining().size}")
            } else if (old?.`is`(Blocks.STONE_BUTTON) == true && new.isAir) {
                state.removeButton(x, y, z)
            }
        }
    }

    /** A right click on a block; returns true when it must be cancelled. */
    fun onBlockUse(pos: BlockPos): Boolean {
        if (!isEnabled() || !onFloor7() || !nearDevice()) return false
        if (!SimonSaysState.inButtons(pos.x, pos.y, pos.z)) return false
        when (state.buttonClicked(pos.x, pos.y, pos.z)) {
            SimonSaysState.CORRECT -> {
                clicksThisRound++
                diagnostics.event("CLICK correct left=${state.remaining().size} round=$clicksThisRound")
                if (state.remaining().isEmpty()) {
                    if (announceProgress.value()) Minecraft.getInstance().player?.connection?.sendCommand("pc SS $clicksThisRound/4")
                    clicksThisRound = 0
                }
            }
            SimonSaysState.WRONG -> {
                val sneaking = Minecraft.getInstance().player?.isShiftKeyDown == true
                if (blockWrong.value() && !(sneaking xor invertKey.value())) {
                    blocked++
                    diagnostics.event("BLOCKED blocked=$blocked")
                    return true
                }
                diagnostics.event("CLICK wrong")
            }
        }
        return false
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
            // The real button shape: it follows the facing and shrinks while the button is pressed.
            val block = level.getBlockState(pos)
            val shape = if (block.`is`(Blocks.STONE_BUTTON)) block.getShape(level, pos) else null
            if (shape == null || shape.isEmpty) {
                missing++
                continue
            }
            val b = shape.bounds()
            val box = SimonSaysState.highlightBox(pos.x, pos.y, pos.z, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)
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
        private const val START_X = 110
        private const val START_Y = 121
        private const val START_Z = 91
        private val spec = ModuleSettingsSpec.of("Simon Says")

        @JvmField
        val INSTANCE = SimonSaysModule()
    }
}
