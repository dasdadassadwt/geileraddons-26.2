package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.foraging.ForagingRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StainedGlassBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3

/**
 * Solves the Foraging island temple puzzles. Forest Temple (Moonglade Marsh): shows how many clicks each floor
 * terracotta needs to match the wall. Desert Temple (Torrhus Canyon): numbers the colour buttons in the order to press
 * them and draws the statue arrows. Behavior and block coordinates follow Skyblocker's temple solvers.
 */
class TemplePuzzlesModule private constructor(settings: Settings) : Module(
    "Temple Puzzles",
    "Solves the Forest Temple and Desert Temple puzzles on the Foraging islands.",
    Category.FORAGING,
    settings.forest, settings.desert, settings.depth,
) {
    private val forest = settings.forest
    private val desert = settings.desert
    private val depth = settings.depth
    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 100L, 512)

    private var buttonOrder: List<DyeColor> = emptyList()
    private var ticksSinceSolve = DESERT_SOLVE_TICKS
    private var forestLabels = 0
    private var desertLabels = 0
    private var arrows = 0
    private var lastLevel: ClientLevel? = null
    private var lastRender = "not-rendered"

    override fun onEnable() {
        reset()
        diagnostics.enable("forest=${forest.value()},desert=${desert.value()},depth=${depth.value()},range=$TEMPLE_RANGE")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        buttonOrder = emptyList()
        ticksSinceSolve = DESERT_SOLVE_TICKS
        forestLabels = 0
        desertLabels = 0
        arrows = 0
        lastLevel = null
        lastRender = "not-rendered"
    }

    private fun nearForest(player: Vec3, island: Island): Boolean =
        forest.value() && island == Island.GALATEA && near(player, FOREST_ANCHORS)

    private fun nearDesert(player: Vec3, island: Island): Boolean =
        desert.value() && island == Island.TORRHUS_CANYON && near(player, DESERT_ANCHORS)

    /** Within [TEMPLE_RANGE] blocks of any puzzle point, so the puzzle is only drawn when you stand at it. */
    private fun near(player: Vec3, anchors: Array<DoubleArray>): Boolean =
        ForagingRules.withinAny(player.x, player.y, player.z, anchors, TEMPLE_RANGE)

    /** Depth Check hides a label whose spot is behind terrain; with it off, labels show through walls. */
    private fun visible(pos: BlockPos): Boolean = !depth.value() || geiler.addons.client.hunting.SafariVisibility.canSee(pos)

    /** Called once per client tick on the client thread. */
    fun tick() {
        if (!isEnabled()) return
        val minecraft = Minecraft.getInstance()
        if (!minecraft.isSameThread) {
            minecraft.execute { tick() }
            return
        }
        diagnostics.advance()
        val level = minecraft.level
        val player = minecraft.player
        val island = HypixelModApi.currentIsland()
        if (level !== lastLevel) {
            lastLevel = level
            buttonOrder = emptyList()
            ticksSinceSolve = DESERT_SOLVE_TICKS
        }
        val position = player?.position()
        val inForest = position != null && nearForest(position, island)
        val inDesert = position != null && nearDesert(position, island)
        if (level != null && inDesert && ++ticksSinceSolve >= DESERT_SOLVE_TICKS) {
            ticksSinceSolve = 0
            val order = solveDesert(level)
            if (order != buttonOrder) diagnostics.event("DESERT_ORDER ${order.joinToString(",") { it.getName() }}")
            buttonOrder = order
        } else if (!inDesert && buttonOrder.isNotEmpty()) {
            buttonOrder = emptyList()
        }
        diagnostics.statusOnChangeBy(
            20L,
            { "island=${island.name},forest=$inForest,desert=$inDesert,order=${buttonOrder.size}" },
        ) {
            "island=${island.name},nearForest=$inForest,nearDesert=$inDesert,desertButtons=${buttonOrder.size}," +
                "forestLabels=$forestLabels,desertLabels=$desertLabels,arrows=$arrows,forest=${forest.value()}," +
                "desert=${desert.value()},lastRender={$lastRender}"
        }
    }

    // ---- Desert Temple ------------------------------------------------------------------------

    /** Flood-fills the stained glass floor and orders the colours from the fewest blocks to the most. */
    private fun solveDesert(level: ClientLevel): List<DyeColor> {
        val counts = HashMap<Int, Int>()
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue.add(GLASS_FLOOR_CENTRE)
        visited.add(GLASS_FLOOR_CENTRE)
        while (queue.isNotEmpty() && visited.size <= MAX_FLOOD_BLOCKS) {
            val current = queue.removeFirst()
            val block = level.getBlockState(current).block
            if (block is StainedGlassBlock) counts.merge(block.getColor().id, 1, Int::plus)
            for (direction in HORIZONTAL) {
                val next = current.relative(direction)
                if (next in visited || level.getBlockState(next).block !is StainedGlassBlock) continue
                visited.add(next)
                queue.add(next)
            }
        }
        return ForagingRules.buttonOrder(counts).map { DyeColor.byId(it) }
    }

    // ---- rendering ----------------------------------------------------------------------------

    /** The statue arrows; they are lines, so they belong to the world pass. */
    fun render(context: LevelRenderContext) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val player = minecraft.player
        if (!isEnabled() || level == null || player == null || !nearDesert(player.position(), HypixelModApi.currentIsland())) {
            arrows = 0
            return
        }
        val camera = minecraft.gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        var drawn = 0
        for ((start, end) in STATUE_ARROWS) {
            line(poseStack, buffer, camera, Vec3.atCenterOf(start), Vec3.atCenterOf(end), depth.value())
            drawn++
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
        arrows = drawn
        lastRender = "arrows=$drawn,desertLabels=$desertLabels,forestLabels=$forestLabels"
    }

    private fun line(poseStack: PoseStack, buffer: WorldRenderBuffer.BufferSource, camera: Vec3, from: Vec3, to: Vec3, depthTested: Boolean) {
        EspRenderer.renderLine(
            poseStack, buffer, from.x - camera.x, from.y - camera.y, from.z - camera.z,
            to.x - camera.x, to.y - camera.y, to.z - camera.z, WHITE, LINE_WIDTH, depthTested,
        )
        // Arrow head: two short strokes swept back from the tip.
        val back = from.subtract(to).normalize().scale(0.4)
        val side = Vec3(-back.z, 0.0, back.x).scale(0.6)
        for (sign in doubleArrayOf(1.0, -1.0)) {
            val tail = to.add(back).add(side.scale(sign))
            EspRenderer.renderLine(
                poseStack, buffer, to.x - camera.x, to.y - camera.y, to.z - camera.z,
                tail.x - camera.x, tail.y - camera.y, tail.z - camera.z, WHITE, LINE_WIDTH, depthTested,
            )
        }
    }

    /** Click counts above the floor terracotta and the press order above the desert buttons. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled()) return
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val island = HypixelModApi.currentIsland()
        val camera = minecraft.gameRenderer.mainCamera()
        forestLabels = 0
        desertLabels = 0
        if (nearForest(player.position(), island)) {
            val wall = wallFacings(level)
            for (index in wall.indices) {
                val floorPos = floorBlock(index)
                val floorState = level.getBlockState(floorPos)
                if (!floorState.`is`(Blocks.GLAZED_TERRACOTTA.orange())) continue
                val floorFacing = floorState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                val clicks = ForagingRules.rotations(clockwiseIndex(floorFacing), ForagingRules.forestFloorTarget(wall[index]))
                if (clicks == 0 || !visible(floorPos)) continue
                val text = if (clicks > 0) "+$clicks" else clicks.toString()
                // Right clicks (positive) are red, the single left click (negative) green; the size never changes with distance.
                val color = if (clicks > 0) RIGHT_CLICK_COLOR else LEFT_CLICK_COLOR
                if (ProjectedLabelRenderer.drawFloatingText(
                        graphics, camera, minecraft.font, Vec3.atCenterOf(floorPos).add(0.0, 1.0, 0.0), text,
                        color, LABEL_SCALE, minScale = LABEL_SCALE, maxScale = LABEL_SCALE,
                    )
                ) forestLabels++
            }
        }
        if (nearDesert(player.position(), island) && buttonOrder.isNotEmpty() &&
            !level.getBlockState(GLASS_WHEEL_CENTRE).isAir
        ) {
            for ((index, color) in buttonOrder.withIndex()) {
                val button = BUTTON_LOCATIONS[color] ?: continue
                if (!visible(button)) continue
                if (ProjectedLabelRenderer.drawFloatingText(
                        graphics, camera, minecraft.font, Vec3.atBottomCenterOf(button).add(0.0, 0.4, 0.2),
                        (index + 1).toString(), WHITE_TEXT, LABEL_SCALE, minScale = LABEL_SCALE, maxScale = LABEL_SCALE,
                    )
                ) desertLabels++
            }
        }
    }

    /** Facing (as a clockwise index) of each wall terracotta, two per column: top, bottom, then the next column. */
    private fun wallFacings(level: ClientLevel): List<Int> {
        val facings = ArrayList<Int>(16)
        for (x in WALL_TOP_LEFT.x downTo WALL_BOTTOM_RIGHT.x) {
            for (y in WALL_TOP_LEFT.y downTo WALL_BOTTOM_RIGHT.y) {
                val state = level.getBlockState(BlockPos(x, y, WALL_TOP_LEFT.z))
                if (!state.`is`(Blocks.GLAZED_TERRACOTTA.orange())) continue
                facings += clockwiseIndex(state.getValue(BlockStateProperties.HORIZONTAL_FACING))
            }
        }
        return facings
    }

    private fun floorBlock(index: Int): BlockPos {
        val column = index / 2
        return if (index % 2 == 0) FLOOR_TOP_LEFT.offset(-column, 0, 0) else FLOOR_BOTTOM_LEFT.offset(-column, 0, 0)
    }

    private fun clockwiseIndex(direction: Direction): Int = when (direction) {
        Direction.NORTH -> 0
        Direction.EAST -> 1
        Direction.SOUTH -> 2
        else -> 3
    }

    class Settings {
        val forest = BooleanSetting("Forest Temple", true)
        val desert = BooleanSetting("Desert Temple", true)
        val depth = BooleanSetting("Depth Check", true)
    }

    companion object {
        private const val TEMPLE_RANGE = 10.0
        private const val LABEL_SCALE = 1.6f
        private const val DESERT_SOLVE_TICKS = 100
        private const val MAX_FLOOD_BLOCKS = 4096
        private const val LINE_WIDTH = 5.0f
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val WHITE_TEXT = 0xFFFFFFFF.toInt()
        private const val RIGHT_CLICK_COLOR = 0xFFFF5555.toInt()
        private const val LEFT_CLICK_COLOR = 0xFF55FF55.toInt()
        private val HORIZONTAL = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

        private val FOREST_CENTER = Vec3(-636.0, 62.0, 80.0)
        private val DESERT_CENTER = Vec3(-612.0, 45.0, 235.0)
        private val WALL_TOP_LEFT = BlockPos(-633, 66, 85)
        private val WALL_BOTTOM_RIGHT = BlockPos(-640, 65, 85)
        private val FLOOR_TOP_LEFT = BlockPos(-633, 59, 76)
        private val FLOOR_BOTTOM_LEFT = BlockPos(-633, 59, 75)

        private val GLASS_FLOOR_CENTRE = BlockPos(-612, 42, 227)
        private val GLASS_WHEEL_CENTRE = BlockPos(-612, 47, 233)
        private val BUTTON_LOCATIONS = mapOf(
            DyeColor.BROWN to BlockPos(-611, 49, 232), DyeColor.RED to BlockPos(-612, 49, 232),
            DyeColor.ORANGE to BlockPos(-613, 49, 232), DyeColor.YELLOW to BlockPos(-614, 48, 232),
            DyeColor.LIME to BlockPos(-614, 47, 232), DyeColor.GREEN to BlockPos(-614, 46, 232),
            DyeColor.CYAN to BlockPos(-613, 45, 232), DyeColor.LIGHT_BLUE to BlockPos(-612, 45, 232),
            DyeColor.BLUE to BlockPos(-611, 45, 232), DyeColor.PURPLE to BlockPos(-610, 46, 232),
            DyeColor.MAGENTA to BlockPos(-610, 47, 232), DyeColor.PINK to BlockPos(-610, 48, 232),
        )

        /** Statue (bird, snake, frog, fly) to the spot it is pushed to. */
        private val STATUE_ARROWS = listOf(
            BlockPos(-618, 46, 248) to BlockPos(-616, 46, 248),
            BlockPos(-607, 46, 249) to BlockPos(-607, 46, 247),
            BlockPos(-606, 46, 240) to BlockPos(-608, 46, 240),
            BlockPos(-617, 46, 241) to BlockPos(-617, 46, 239),
        )

        /** Every spot a puzzle is drawn at; being within the range of any of them counts as being at the temple. */
        private val FOREST_ANCHORS: Array<DoubleArray> = arrayOf(
            doubleArrayOf(FOREST_CENTER.x, FOREST_CENTER.y, FOREST_CENTER.z),
            doubleArrayOf(WALL_TOP_LEFT.x + 0.5, WALL_TOP_LEFT.y + 0.5, WALL_TOP_LEFT.z + 0.5),
            doubleArrayOf(WALL_BOTTOM_RIGHT.x + 0.5, WALL_BOTTOM_RIGHT.y + 0.5, WALL_BOTTOM_RIGHT.z + 0.5),
            doubleArrayOf(FLOOR_TOP_LEFT.x + 0.5, FLOOR_TOP_LEFT.y + 0.5, FLOOR_TOP_LEFT.z + 0.5),
            doubleArrayOf(FLOOR_TOP_LEFT.x - 7 + 0.5, FLOOR_TOP_LEFT.y + 0.5, FLOOR_TOP_LEFT.z + 0.5),
        )
        private val DESERT_ANCHORS: Array<DoubleArray> =
            (listOf(DESERT_CENTER) + BUTTON_LOCATIONS.values.map { Vec3.atCenterOf(it) } + STATUE_ARROWS.flatMap { listOf(Vec3.atCenterOf(it.first), Vec3.atCenterOf(it.second)) })
                .map { doubleArrayOf(it.x, it.y, it.z) }.toTypedArray()

        @JvmField
        val INSTANCE = TemplePuzzlesModule(Settings())
    }
}
