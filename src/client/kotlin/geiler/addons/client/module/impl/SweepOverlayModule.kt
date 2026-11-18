package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.farming.PestWidgetReader
import geiler.addons.client.foraging.ForagingRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Boxes the logs the next axe swing will cut. The count comes from your Sweep stat (read from the tab list) and the
 * kind of tree; a thrown axe's landing spot is predicted with gravity and cuts half as many. Behavior follows
 * Skyblocker's Sweep Overlay; the search is local.
 */
class SweepOverlayModule private constructor(settings: Settings) : Module(
    "Sweep Overlay",
    "Boxes the logs your next axe swing will cut, from your Sweep stat.",
    Category.FORAGING,
    settings.thrown, settings.depth, settings.color,
) {
    private val thrown = settings.thrown
    private val color = settings.color
    private val depth = settings.depth
    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 100L, 512)

    private var sweep = 0f
    private var ticksSinceSweepRead = SWEEP_READ_TICKS
    private var targets: List<BlockPos> = emptyList()
    private var lastTarget: BlockPos? = null
    private var lastSweep = -1f
    private var ticksSinceCollect = 0
    private var lastSource = "none"
    private var lastRender = "not-rendered"

    override fun onEnable() {
        clear()
        sweep = 0f
        diagnostics.enable("thrown=${thrown.value()},depth=${depth.value()},colorArgb=${color.argb()}")
    }

    override fun onDisable() {
        clear()
        diagnostics.disable()
    }

    private fun clear() {
        targets = emptyList()
        lastTarget = null
        lastSource = "none"
        ticksSinceSweepRead = SWEEP_READ_TICKS
    }

    private fun validIsland(island: Island): Boolean =
        island == Island.GALATEA || island == Island.TORRHUS_CANYON || island == Island.THE_PARK ||
            island == Island.HUB || island == Island.PRIVATE_ISLAND

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
        val id = if (player == null) "" else SkyblockItems.skyBlockId(player.mainHandItem)
        val throwable = id in THROWABLE_AXES
        val axe = throwable || id in VALID_AXES
        if (level == null || player == null || !axe || !validIsland(island)) {
            if (targets.isNotEmpty()) diagnostics.event("CLEARED axe=$axe island=${island.name}")
            clear()
            diagnostics.status { "active=false axe=$axe island=${island.name} suppressedEvents=$it" }
            return
        }
        if (++ticksSinceSweepRead >= SWEEP_READ_TICKS) {
            ticksSinceSweepRead = 0
            sweep = readSweep()
        }

        var hit: BlockHitResult? = null
        var isThrown = false
        val aimed = minecraft.hitResult
        if (aimed is BlockHitResult && aimed.type == HitResult.Type.BLOCK) {
            hit = aimed
        } else if (throwable && thrown.value()) {
            hit = thrownHit(level, player.getEyePosition(1.0f), player.getViewVector(1.0f), player.xRot.toDouble(), island)
            isThrown = hit != null
        }
        val start = hit?.blockPos
        val startState = start?.let { level.getBlockState(it) }
        if (sweep <= 0f || start == null || startState == null || !isLog(startState, island)) {
            targets = emptyList()
            lastTarget = null
            lastSource = "none"
            ticksSinceCollect = COLLECT_INTERVAL_TICKS
        } else {
            // The search walks up to 26 neighbours per log, so it runs on a new target or sweep and then every few ticks.
            val sameTarget = start == lastTarget && sweep == lastSweep && (lastSource == "thrown") == isThrown
            if (ForagingRules.recollect(sameTarget, ++ticksSinceCollect, COLLECT_INTERVAL_TICKS)) {
                ticksSinceCollect = 0
                val max = maxLogs(startState, island).let { if (isThrown) ForagingRules.thrownMaxLogs(it) else it }
                targets = collect(level, start, startState, max, island)
                if (!sameTarget) diagnostics.event("TARGET pos=${start.x},${start.y},${start.z} max=$max found=${targets.size} thrown=$isThrown")
            }
            lastTarget = start
            lastSweep = sweep
            lastSource = if (isThrown) "thrown" else "aimed"
        }
        diagnostics.statusOnChangeBy(
            20L,
            { "island=${island.name},axe=$id,target=${lastTarget != null},boxes=${targets.size},sweep=$sweep" },
        ) {
            "active=true island=${island.name} axe=$id sweep=$sweep source=$lastSource boxes=${targets.size} " +
                "thrownSetting=${thrown.value()} lastRender={$lastRender}"
        }
    }

    // ---- reading the stat and the trees -------------------------------------------------------

    private fun readSweep(): Float {
        for (line in PestWidgetReader.currentTabList()) {
            val plain = FORMAT_CODE.replace(line, "")
            val value = SWEEP_LINE.find(plain)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            return value
        }
        return 0f
    }

    private fun isFig(state: BlockState) = state.`is`(Blocks.STRIPPED_SPRUCE_LOG) || state.`is`(Blocks.STRIPPED_SPRUCE_WOOD)

    private fun isMangrove(state: BlockState) = state.`is`(Blocks.MANGROVE_LOG) || state.`is`(Blocks.MANGROVE_WOOD)

    private fun isHelixBirch(state: BlockState) = state.`is`(Blocks.STRIPPED_BIRCH_LOG) || state.`is`(Blocks.STRIPPED_BIRCH_WOOD)

    private fun isHelixMangrove(state: BlockState) =
        state.`is`(Blocks.STRIPPED_MANGROVE_LOG) || state.`is`(Blocks.STRIPPED_MANGROVE_WOOD)

    private fun isLog(state: BlockState, island: Island): Boolean = when (island) {
        Island.GALATEA -> isFig(state) || isMangrove(state)
        Island.TORRHUS_CANYON -> isHelixBirch(state) || isHelixMangrove(state)
        Island.HUB -> state.`is`(Blocks.OAK_LOG) || state.`is`(Blocks.OAK_WOOD)
        else -> state.`is`(BlockTags.LOGS)
    }

    /** Helix trees are cut one colour at a time. */
    private fun sameTree(source: BlockState, other: BlockState, island: Island): Boolean =
        island != Island.TORRHUS_CANYON ||
            (isHelixBirch(source) && isHelixBirch(other)) || (isHelixMangrove(source) && isHelixMangrove(other))

    private fun maxLogs(state: BlockState, island: Island): Int = when (island) {
        Island.GALATEA -> when {
            isFig(state) -> ForagingRules.maxLogs(sweep, ForagingRules.FIG_SWEEP)
            else -> ForagingRules.maxLogs(sweep, ForagingRules.MANGROVE_SWEEP)
        }
        Island.TORRHUS_CANYON -> ForagingRules.maxLogs(sweep, ForagingRules.HELIX_SWEEP)
        else -> ForagingRules.basicMaxLogs(sweep)
    }

    /** Breadth-first over the 26 neighbours until [max] logs are found. */
    private fun collect(level: ClientLevel, start: BlockPos, source: BlockState, max: Int, island: Island): List<BlockPos> {
        val found = ArrayList<BlockPos>()
        val visited = HashSet<BlockPos>()
        val queue = ArrayDeque<BlockPos>()
        queue.add(start)
        visited.add(start)
        while (found.size < max && queue.isNotEmpty()) {
            val pos = queue.removeFirst()
            val state = level.getBlockState(pos)
            if (!isLog(state, island)) continue
            found += pos
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val next = pos.offset(dx, dy, dz)
                if (!visited.add(next)) continue
                val nextState = level.getBlockState(next)
                if (isLog(nextState, island) && sameTree(state, nextState, island)) queue.add(next)
            }
        }
        return found
    }

    /** Where a thrown axe lands: a straight ray that sinks with distance, more the flatter it is thrown. */
    private fun thrownHit(level: ClientLevel, from: Vec3, look: Vec3, pitch: Double, island: Island): BlockHitResult? {
        val angle = Math.abs(pitch / 90.0)
        val gravity = ((1 - angle) * (2.65 + 2.51827 * angle - 2.43475 * angle * angle + 1.22748 * angle * angle * angle)) / 1000
        for (step in 0 until THROWN_RANGE) {
            val point = from.add(look.scale(step.toDouble())).subtract(0.0, gravity * step * step, 0.0)
            val pos = BlockPos.containing(point)
            if (isLog(level.getBlockState(pos), island)) {
                return BlockHitResult(point, net.minecraft.core.Direction.DOWN, pos, false)
            }
        }
        return null
    }

    // ---- rendering ----------------------------------------------------------------------------

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || targets.isEmpty()) {
            lastRender = "skipped"
            return
        }
        val camera = Minecraft.getInstance().gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        // A thrown axe's boxes are dimmer, the same cue Skyblocker gives.
        val base = color.argb()
        val fill = if (lastSource == "thrown") dim(base) else base
        val line = fill or OPAQUE
        for (pos in targets) {
            EspRenderer.renderBox(
                poseStack, buffer, pos.x - camera.x, pos.y - camera.y, pos.z - camera.z, 1.0, 1.0, 1.0, fill, line, 2.0f,
                depth.value(),
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
        lastRender = "boxes=${targets.size},source=$lastSource,depth=${depth.value()}"
    }

    private fun dim(argb: Int): Int {
        val r = (((argb shr 16) and 0xFF) * 0.7f).toInt()
        val g = (((argb shr 8) and 0xFF) * 0.7f).toInt()
        val b = ((argb and 0xFF) * 0.7f).toInt()
        return (argb and OPAQUE) or (r shl 16) or (g shl 8) or b
    }

    class Settings {
        val thrown = BooleanSetting("Thrown Axe Overlay", true)
        val depth = BooleanSetting("Depth Check", true)
        val color = ColorSetting("Color", 255, 150, 0, 24)
    }

    companion object {
        private const val SWEEP_READ_TICKS = 20
        private const val COLLECT_INTERVAL_TICKS = 10
        private const val THROWN_RANGE = 50
        private const val OPAQUE = 0xFF000000.toInt()
        private val FORMAT_CODE = Regex("§.")
        private val SWEEP_LINE = Regex("Sweep:\\D*(\\d+)")
        private val VALID_AXES = setOf(
            "JUNGLE_AXE", "TREECAPITATOR_AXE", "FIG_AXE", "FIGSTONE_AXE", "HELIX_CHOPPER",
            "ROOKIE_AXE", "PROMISING_AXE", "SWEET_AXE", "EFFICIENT_AXE",
        )
        private val THROWABLE_AXES = setOf("JUNGLE_AXE", "TREECAPITATOR_AXE", "FIG_AXE", "FIGSTONE_AXE", "HELIX_CHOPPER")

        @JvmField
        val INSTANCE = SweepOverlayModule(Settings())
    }
}
