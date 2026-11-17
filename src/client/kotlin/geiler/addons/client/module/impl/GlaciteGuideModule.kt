package geiler.addons.client.module.impl

import geiler.addons.client.location.Island
import geiler.addons.client.mining.GlaciteGraph
import geiler.addons.client.mining.GlaciteRoute
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MiningTabText
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.ProjectedLabelRenderer
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3

/**
 * Draws a route through the Glacite Tunnels to the next commission or gemstone, after SkyHanni's Tunnel Maps. In
 * Auto the target is your first unfinished collector commission; pick a gemstone, ore, fairy soul or the campfire in
 * Target to route there instead. Left-clicking the Royal Pigeon jumps to the next spot of the target.
 */
class GlaciteGuideModule private constructor(settings: Settings) : Module(
    "Glacite Guide",
    "Shows a route to your next commission or gemstone in the Glacite Tunnels.",
    Category.MINING,
    false,
    settings.target, settings.pigeon, settings.showDistance, settings.pathColor, settings.pathWidth,
) {
    private val target = settings.target
    private val pigeon = settings.pigeon
    private val showDistance = settings.showDistance
    private val pathColor = settings.pathColor
    private val pathWidth = settings.pathWidth

    private val diagnostics = ModuleDiagnostics(Category.MINING, { name() }, 8, 100L, 400)
    private var graph: GlaciteGraph? = null
    private var route: GlaciteRoute? = null
    private var tick = 0L
    private var inArea = false
    private var areaTick = Long.MIN_VALUE
    private var wasAttacking = false
    private var lastLevelId = 0
    private var routesStarted = 0L
    private var reached = 0L
    private var commissionTarget: String? = null

    init {
        group(SettingGroup("Route", target, pigeon, showDistance, pathColor, pathWidth))
    }

    private fun settingsText(): String =
        "target=${target.value()};pigeon=${pigeon.value()};distance=${showDistance.value()};width=${pathWidth.intValue()}"

    override fun onEnable() {
        ensureLoaded()
        route?.clear()
        routesStarted = 0
        reached = 0
        diagnostics.enable(settingsText() + ";nodes=${graph?.size() ?: 0}")
    }

    override fun onDisable() {
        route?.clear()
        diagnostics.disable()
    }

    private fun ensureLoaded() {
        if (graph != null) return
        try {
            GlaciteGuideModule::class.java.getResourceAsStream(DATA)!!.use {
                graph = GlaciteGraph.parse(String(it.readAllBytes(), Charsets.UTF_8))
            }
            route = GlaciteRoute(graph!!)
        } catch (failure: Exception) {
            diagnostics.record("DATA_LOAD_FAILED ${failure.javaClass.simpleName}")
        }
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        diagnostics.advance()
        tick++
        val active = route ?: return
        val map = graph ?: return
        val level = mc.level
        val player = mc.player
        val levelId = System.identityHashCode(level)
        if (levelId != lastLevelId) {
            lastLevelId = levelId
            active.clear()
            active.clearCooldowns()
        }
        if (level == null || player == null || !inGlacite(mc)) {
            if (active.target != null) {
                active.clear()
                diagnostics.event("ROUTE_CLEARED reason=left-area")
            }
            report("outside-area")
            return
        }
        val now = System.nanoTime()
        val closest = map.nearest(player.x, player.y, player.z)
        handlePigeon(mc, active, closest, now)
        if (tick % TARGET_TICKS == 0L) refreshTarget(active, closest, now, map)
        if (active.update(player.x, player.y, player.z, now)) {
            reached++
            diagnostics.event("GOAL_REACHED target=${active.target} reached=$reached")
        }
        report("ready")
    }

    /** A left click with the Royal Pigeon jumps to the next spot of the target. */
    private fun handlePigeon(mc: Minecraft, active: GlaciteRoute, closest: GlaciteGraph.Node?, now: Long) {
        val attacking = mc.options.keyAttack.isDown() && mc.gui.screen() == null
        val held = mc.player?.let { SkyblockItems.skyBlockId(it.mainHandItem) } == PIGEON_ID
        if (attacking && !wasAttacking && held && pigeon.value()) {
            if (active.target == null) refreshTarget(active, closest, now, graph!!)
            val moved = active.next(closest, now)
            diagnostics.event("PIGEON_CLICK target=${active.target} moved=$moved")
        }
        wasAttacking = attacking
    }

    /** Keeps the route pointed at the chosen target: Auto reads the first unfinished collector commission. */
    private fun refreshTarget(active: GlaciteRoute, closest: GlaciteGraph.Node?, now: Long, map: GlaciteGraph) {
        val wanted = if (target.value() == AUTO) autoTarget(map) else target.value()
        commissionTarget = wanted
        if (wanted == null) {
            if (active.target != null) active.clear()
            return
        }
        if (wanted != active.target) {
            active.setTarget(wanted, closest, now)
            routesStarted++
            diagnostics.event("ROUTE_STARTED target=$wanted goalSet=${active.goal != null}")
        } else if (active.goal == null) {
            active.next(closest, now)
        }
    }

    private fun autoTarget(map: GlaciteGraph): String? {
        val commissions = MiningTabText.commissions(MiningContext.tabLines())
        for (commission in commissions) {
            if (commission.done) continue
            GlaciteGraph.targetForCommission(commission.name, map.targetNames())?.let { return it }
        }
        return null
    }

    private fun inGlacite(mc: Minecraft): Boolean {
        if (!MiningContext.on(Island.DWARVEN_MINES)) return false
        if (tick - areaTick >= AREA_TICKS || areaTick == Long.MIN_VALUE) {
            areaTick = tick
            inArea = MiningContext.sidebarLines().any { line -> AREAS.any { line.contains(it) } }
        }
        return inArea
    }

    private fun report(state: String) {
        diagnostics.statusOnChange {
            "state=$state target=${route?.target} auto=${commissionTarget} goal=${route?.goal?.id} " +
                "pathNodes=${route?.path?.nodes?.size ?: 0} started=$routesStarted reached=$reached"
        }
    }

    /** The route as line segments through walls. */
    fun render(context: LevelRenderContext) {
        if (!isEnabled()) return
        val path = route?.path ?: return
        if (path.nodes.size < 2 && route?.goal == null) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val cam = client.gameRenderer.mainCamera().position()
        val buffer = WorldRenderBuffer.current()
        val pose = context.poseStack()
        val color = pathColor.opaqueArgb()
        val width = pathWidth.intValue().toFloat()
        var fromX = player.x - cam.x
        var fromY = player.y + 0.2 - cam.y
        var fromZ = player.z - cam.z
        for (node in path.nodes) {
            val toX = node.x + 0.5 - cam.x
            val toY = node.y + 0.5 - cam.y
            val toZ = node.z + 0.5 - cam.z
            EspRenderer.renderLine(pose, buffer, fromX, fromY, fromZ, toX, toY, toZ, color, width)
            fromX = toX
            fromY = toY
            fromZ = toZ
        }
        GeilerAddonsRenderTypes.endBatches(buffer)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled() || !showDistance.value()) return
        val active = route ?: return
        val goal = active.goal ?: return
        val path = active.path ?: return
        val client = Minecraft.getInstance()
        ProjectedLabelRenderer.draw(
            graphics, client.gameRenderer.mainCamera(), client.font, Vec3(goal.x + 0.5, goal.y + 2.0, goal.z + 0.5),
            "${goal.name ?: "Goal"} §e${Math.round(path.length)}m", pathColor.opaqueArgb(), 1.5f,
        )
    }

    private class Settings {
        val target = ChoiceSetting(
            "Target", AUTO, AUTO, "Campfire", "Ruby Gemstone", "Amethyst Gemstone", "Jade Gemstone", "Sapphire Gemstone",
            "Amber Gemstone", "Topaz Gemstone", "Onyx Gemstone", "Aquamarine Gemstone", "Citrine Gemstone",
            "Peridot Gemstone", "Tungsten", "Umber", "Mithril", "Fairy Soul Lake", "Fairy Soul Dr. Stone", "Fairy Soul Citrine",
        )
        val pigeon = BooleanSetting("Left Click Royal Pigeon", true)
        val showDistance = BooleanSetting("Show Distance", true)
        val pathColor = ColorSetting("Path Color", 85, 255, 255, 255)
        val pathWidth = NumberSetting("Path Width", 1f, 8f, 3f, true)
    }

    companion object {
        private const val AUTO = "Auto (commission)"
        private const val DATA = "/geileraddons/mining/glacite_tunnels_graph.json"
        private const val PIGEON_ID = "ROYAL_PIGEON"
        private const val TARGET_TICKS = 20L
        private const val AREA_TICKS = 20L
        private val AREAS = listOf("Glacite Tunnels", "Dwarven Base Camp", "Great Glacite Lake", "Fossil Research Center")

        @JvmField
        val INSTANCE = GlaciteGuideModule(Settings())
    }
}
