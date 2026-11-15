package geiler.addons.client.waypoint

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Waypoints: saved groups per island (Skyblocker model: ordered routes, five render types, share strings),
 * managed in the Waypoint Library on this module's settings page. Chat coordinates live in Chat Waypoints.
 */
class WaypointsModule private constructor() : Module(
    "Waypoints",
    "Render saved waypoints and routes. Manage them per island in the Waypoint Library.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val showDistance = spec.toggle("showDistance")
    private val textScale = spec.slider("textScale")
    private val maxDistance = spec.slider("maxDistance")
    private val activationRadius = spec.slider("activationRadius")
    private val allowSkipping = spec.toggle("allowSkipping")
    private val allowBackwards = spec.toggle("allowBackwards")
    private val renderLine = spec.toggle("renderLine")
    private val lineColor = spec.color("lineColor")
    private val lineWidth = spec.slider("lineWidth")

    private var targets: List<WaypointRenderer.Target> = emptyList()
    private var island: Island = Island.NONE
    private var lastLevel: Any? = null
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 360)

    override fun groups(): List<SettingGroup> = listOf(WaypointLibrary.build()) + spec.groups

    override fun settingTooltip(setting: Setting): String? = when (setting.displayName()) {
        "Show Text" -> "Show the name and distance label above this group's waypoints."
        "Ordered" -> "Show waypoints one after another. Reaching one reveals the next."
        "Position (x, y, z)" -> "Three whole numbers, for example 120, 64, -35."
        else -> null
    }

    override fun onEnable() {
        diagnostics.enable("store=${WaypointStore.loadSummary}")
    }

    override fun onDisable() {
        targets = emptyList()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    /** Called every client tick, also while the module is off, so Click GUI edits are saved. */
    fun tick() {
        WaypointLibrary.sync()
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            targets = emptyList()
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            resetRoutes()
        }
        island = HypixelModApi.currentIsland()
        val built = ArrayList<WaypointRenderer.Target>()
        var ordered = 0
        val applicable = WaypointStore.groupsFor(island)
        for (group in applicable) {
            val type = group.waypointType()
            if (group.ordered && group.waypoints.isNotEmpty()) {
                ordered++
                val positions = Array(group.waypoints.size) { i ->
                    val w = group.waypoints[i]
                    intArrayOf(w.x, w.y, w.z)
                }
                val before = group.currentIndex
                group.currentIndex = OrderedWaypointRules.advance(
                    group.currentIndex, player.x, player.y, player.z, positions,
                    activationRadius.value().toDouble(), allowBackwards.value(), allowSkipping.value(),
                )
                if (before != group.currentIndex) {
                    diagnostics.event("ORDERED_ADVANCE group=${group.name.take(24)} from=$before to=${group.currentIndex} of=${positions.size}")
                }
            }
            for ((index, w) in group.waypoints.withIndex()) {
                if (!w.enabled) continue
                var rgb = w.rgb
                var role = OrderedWaypointRules.CURRENT
                if (group.ordered) {
                    role = OrderedWaypointRules.relative(index, group.currentIndex, group.waypoints.size)
                    if (role == OrderedWaypointRules.NONE) continue
                    rgb = when (role) {
                        OrderedWaypointRules.PREVIOUS -> 0xFF0000
                        OrderedWaypointRules.CURRENT -> 0xFFFFFF
                        else -> 0x00FF00
                    }
                }
                built.add(
                    WaypointRenderer.Target(
                        w.x, w.y, w.z, w.name, rgb, w.alpha, type, group.throughWalls,
                        if (group.ordered) role else OrderedWaypointRules.NONE, if (group.ordered) index + 1 else 0,
                        group.showText,
                    ),
                )
            }
        }
        targets = built
        diagnostics.status {
            "enabled=true island=${island.label()} groups=${WaypointStore.all().size} applicable=${applicable.size} " +
                "orderedGroups=$ordered targets=${built.size} store=${WaypointStore.loadSummary} suppressedEvents=$it"
        }
    }

    /** Group progress of ordered routes restarts with each world. */
    fun resetRoutes() {
        WaypointStore.all().forEach { it.currentIndex = 0 }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled()) return
        WaypointRenderer.draw(
            context, targets, maxDistance.value().toDouble(),
            lineColor.argb(), if (renderLine.value()) lineWidth.value() else 0f,
        )
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled()) return
        WaypointRenderer.drawLabels(graphics, targets, maxDistance.value().toDouble(), showDistance.value(), textScale.value())
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Waypoints")

        @JvmField
        val INSTANCE = WaypointsModule()
    }

    init {
        WaypointLibrary.trace = { diagnostics.event(it) }
    }
}
