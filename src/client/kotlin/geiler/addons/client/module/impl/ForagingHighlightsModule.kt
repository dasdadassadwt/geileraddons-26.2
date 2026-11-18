package geiler.addons.client.module.impl

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.foraging.ForagingRules
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.BeehiveBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SeaPickleBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Boxes the Foraging island pickups through walls: Sea Lumies (clustered sea pickles in Moonglade Marsh) and, in
 * Torrhus Canyon, Ruby Veilshrooms and full Honeyhives, which also get a labelled waypoint. Behavior follows
 * Skyblocker's Foraging highlighters; the scan is local and works from loaded chunk sections.
 */
class ForagingHighlightsModule private constructor(settings: Settings) : Module(
    "Foraging Highlights",
    "Boxes Sea Lumies, Ruby Veilshrooms and Honeyhives, with waypoints for Honeyhives.",
    Category.FORAGING,
    settings.seaLumies, settings.minPickles, settings.seaColor,
    settings.veilshroom, settings.veilColor,
    settings.honeyhive, settings.honeyLabels, settings.honeyColor,
    settings.depth,
) {
    private val seaLumies = settings.seaLumies
    private val minPickles = settings.minPickles
    private val seaColor = settings.seaColor
    private val veilshroom = settings.veilshroom
    private val veilColor = settings.veilColor
    private val honeyhive = settings.honeyhive
    private val honeyLabels = settings.honeyLabels
    private val honeyColor = settings.honeyColor
    private val depth = settings.depth

    private val diagnostics = ModuleDiagnostics(Category.FORAGING, { name() }, 8, 100L, 512)
    private val lumies = ArrayList<BlockPos>()
    private val shrooms = ArrayList<BlockPos>()
    private val hives = ArrayList<BlockPos>()
    private var ticksSinceScan = SCAN_INTERVAL_TICKS
    private var lastLevel: ClientLevel? = null
    private var lastIsland: Island? = null
    private var scans = 0L
    private var lastScan = "not-run"
    private var lastRender = "not-rendered"

    init {
        group(
            SettingGroup("Sea Lumies", seaLumies, minPickles, seaColor),
            SettingGroup("Ruby Veilshroom", veilshroom, veilColor),
            SettingGroup("Honeyhive", honeyhive, honeyLabels, honeyColor),
            SettingGroup("Rendering", depth),
        )
    }

    private fun settingsText(): String =
        "seaLumies=${seaLumies.value()},minPickles=${minPickles.intValue()},veilshroom=${veilshroom.value()}," +
            "honeyhive=${honeyhive.value()},honeyLabels=${honeyLabels.value()},depth=${depth.value()}"

    override fun onEnable() {
        clear()
        scans = 0
        lastScan = "not-run"
        lastRender = "not-rendered"
        diagnostics.enable(settingsText())
    }

    override fun onDisable() {
        clear()
        diagnostics.disable()
    }

    private fun clear() {
        lumies.clear()
        shrooms.clear()
        hives.clear()
        ticksSinceScan = SCAN_INTERVAL_TICKS
        lastLevel = null
        lastIsland = null
    }

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
        if (level == null || player == null || (island != Island.GALATEA && island != Island.TORRHUS_CANYON)) {
            if (lumies.isNotEmpty() || shrooms.isNotEmpty() || hives.isNotEmpty()) clear()
            statusLine(island, level != null)
            return
        }
        if (level !== lastLevel || island != lastIsland) {
            clear()
            lastLevel = level
            lastIsland = island
        }
        if (++ticksSinceScan >= SCAN_INTERVAL_TICKS) {
            ticksSinceScan = 0
            scan(level, player, player.blockPosition(), island)
        }
        statusLine(island, true)
    }

    private fun statusLine(island: Island, inLevel: Boolean) {
        diagnostics.statusOnChangeBy(
            20L,
            { "island=${island.name},level=$inLevel,sea=${lumies.size},veil=${shrooms.size},hive=${hives.size}" },
        ) {
            "island=${island.name},level=$inLevel,lumies=${lumies.size},veilshrooms=${shrooms.size}," +
                "honeyhives=${hives.size},scans=$scans,settings{${settingsText()}},lastScan={$lastScan}," +
                "lastRender={$lastRender}"
        }
    }

    private fun scan(level: ClientLevel, player: LocalPlayer, center: BlockPos, island: Island) {
        val wantSea = island == Island.GALATEA && ForagingRules.seaLumiesActive(seaLumies.value(), player.isInWater)
        val wantVeil = island == Island.TORRHUS_CANYON && veilshroom.value()
        val wantHive = island == Island.TORRHUS_CANYON && honeyhive.value()
        val foundSea = ArrayList<BlockPos>()
        val foundVeil = ArrayList<BlockPos>()
        val foundHive = ArrayList<BlockPos>()
        var sections = 0
        var inspected = 0
        if (wantSea || wantVeil || wantHive) {
            val radius = Minecraft.getInstance().options.getEffectiveRenderDistance().coerceIn(2, MAX_CHUNK_RADIUS)
            val centerChunkX = center.x shr 4
            val centerChunkZ = center.z shr 4
            val minimum = minPickles.intValue()
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    val chunk = level.chunkSource.getChunk(centerChunkX + dx, centerChunkZ + dz, false) ?: continue
                    val chunkSections = chunk.sections
                    for (index in chunkSections.indices) {
                        val section = chunkSections[index] ?: continue
                        if (!section.maybeHas { isCandidate(it, wantSea, wantVeil, wantHive) }) continue
                        sections++
                        val baseX = chunk.pos.minBlockX
                        val baseY = chunk.minY + index * 16
                        val baseZ = chunk.pos.minBlockZ
                        for (x in 0 until 16) for (y in 0 until 16) for (z in 0 until 16) {
                            val state = section.getBlockState(x, y, z)
                            if (!isCandidate(state, wantSea, wantVeil, wantHive)) continue
                            inspected++
                            val pos = BlockPos(baseX + x, baseY + y, baseZ + z)
                            when {
                                state.`is`(Blocks.SEA_PICKLE) -> if (ForagingRules.seaPickleQualifies(
                                        state.getValue(SeaPickleBlock.PICKLES), minimum,
                                    )
                                ) foundSea += pos
                                state.`is`(Blocks.CRIMSON_FUNGUS) -> foundVeil += pos
                                state.`is`(Blocks.BEE_NEST) -> if (ForagingRules.honeyhiveQualifies(
                                        state.getValue(BeehiveBlock.HONEY_LEVEL),
                                        level.getBlockState(pos.above()).`is`(Blocks.BIRCH_FENCE),
                                    )
                                ) foundHive += pos
                            }
                        }
                    }
                }
            }
        }
        replaceNearest(lumies, foundSea, center)
        replaceNearest(shrooms, foundVeil, center)
        replaceNearest(hives, foundHive, center)
        scans++
        lastScan = "island=${island.name},sections=$sections,inspected=$inspected,lumies=${lumies.size}," +
            "veilshrooms=${shrooms.size},honeyhives=${hives.size},cap=$MAX_PER_KIND"
        diagnostics.event("SCAN $lastScan")
    }

    private fun isCandidate(state: BlockState, sea: Boolean, veil: Boolean, hive: Boolean): Boolean =
        (sea && state.`is`(Blocks.SEA_PICKLE)) ||
            (veil && state.`is`(Blocks.CRIMSON_FUNGUS)) ||
            (hive && state.`is`(Blocks.BEE_NEST))

    private fun replaceNearest(target: ArrayList<BlockPos>, found: ArrayList<BlockPos>, center: BlockPos) {
        found.sortBy { it.distSqr(center) }
        target.clear()
        target.addAll(found.take(MAX_PER_KIND))
    }

    // ---- rendering ----------------------------------------------------------------------------

    /** Drawn through walls unless Depth Check is on: these pickups are scattered through foliage. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled() || (lumies.isEmpty() && shrooms.isEmpty() && hives.isEmpty())) {
            lastRender = "skipped"
            return
        }
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val camera = minecraft.gameRenderer.mainCamera().position()
        val poseStack: PoseStack = context.poseStack()
        val buffer = WorldRenderBuffer.current()
        // Lumies drop out the moment you leave the water; the other two follow only their own switch.
        val lumiesShown = ForagingRules.seaLumiesActive(seaLumies.value(), minecraft.player?.isInWater == true)
        if (lumiesShown) boxes(poseStack, buffer, camera, level, lumies, seaColor)
        boxes(poseStack, buffer, camera, level, shrooms, veilColor)
        boxes(poseStack, buffer, camera, level, hives, honeyColor)
        GeilerAddonsRenderTypes.endBatches(buffer)
        lastRender = "lumies=${if (lumiesShown) lumies.size else 0},veilshrooms=${shrooms.size},honeyhives=${hives.size},depth=${depth.value()}"
    }

    private fun boxes(
        poseStack: PoseStack,
        buffer: WorldRenderBuffer.BufferSource,
        camera: Vec3,
        level: ClientLevel,
        positions: List<BlockPos>,
        color: ColorSetting,
    ) {
        val fill = color.argb()
        val line = fill or OPAQUE
        for (pos in positions) {
            // Sea pickles and fungus are far smaller than a block, so the box follows the block's own outline.
            val shape = level.getBlockState(pos).getShape(level, pos)
            val box = if (shape.isEmpty) AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else shape.bounds()
            EspRenderer.renderBox(
                poseStack, buffer, pos.x + box.minX - camera.x, pos.y + box.minY - camera.y, pos.z + box.minZ - camera.z,
                box.xsize, box.ysize, box.zsize, fill, line, BOX_LINE_WIDTH, depth.value(),
            )
        }
    }

    /** The Honeyhive waypoints: a label with the distance above each hive. */
    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !honeyhive.value() || !honeyLabels.value() || hives.isEmpty()) return
        val minecraft = Minecraft.getInstance()
        val camera = minecraft.gameRenderer.mainCamera()
        val argb = honeyColor.argb() or OPAQUE
        for (pos in hives.take(MAX_LABELS)) {
            val world = Vec3(pos.x + 0.5, pos.y + 1.2, pos.z + 0.5)
            val distance = String.format(Locale.ROOT, "%.0f", camera.position().distanceTo(world))
            ProjectedLabelRenderer.draw(graphics, camera, minecraft.font, world, "Honeyhive ${distance}m", argb, 1.0f)
        }
    }

    class Settings {
        val seaLumies = BooleanSetting("Sea Lumies", true)
        val minPickles = NumberSetting("Minimum Pickles", 1f, 4f, 3f, true)
        val seaColor = ColorSetting("Sea Lumie Color", 0, 255, 255, 70)
        val veilshroom = BooleanSetting("Ruby Veilshrooms", true)
        val veilColor = ColorSetting("Veilshroom Color", 170, 0, 255, 70)
        val honeyhive = BooleanSetting("Honeyhives", true)
        val honeyLabels = BooleanSetting("Honeyhive Waypoints", true)
        val honeyColor = ColorSetting("Honeyhive Color", 85, 85, 255, 70)
        val depth = BooleanSetting("Depth Check", false)
    }

    companion object {
        private const val SCAN_INTERVAL_TICKS = 20
        private const val MAX_CHUNK_RADIUS = 8
        private const val MAX_PER_KIND = 256
        private const val MAX_LABELS = 16
        private const val BOX_LINE_WIDTH = 2.0f
        private const val OPAQUE = 0xFF000000.toInt()

        @JvmField
        val INSTANCE = ForagingHighlightsModule(Settings())
    }
}
