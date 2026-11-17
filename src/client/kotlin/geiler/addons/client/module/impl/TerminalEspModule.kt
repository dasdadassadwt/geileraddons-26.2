package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.TerminalEspRules
import geiler.addons.client.dungeon.f7.TerminalMarkerBoard
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand

/** Outlines the clickable hitbox of every inactive F7 Phase 3 terminal (NoammAddons' Terminal ESP, CC0). */
class TerminalEspModule private constructor() : Module(
    "Terminal ESP",
    "Highlights the hitboxes of the terminals in F7 and M7.",
    Category.F7,
    false,
    *spec.settings.toTypedArray(),
) {
    private val style = spec.choice("style")
    private val phase = spec.toggle("phase")
    private val fillColor = spec.color("fillColor")
    private val outlineColor = spec.color("outlineColor")
    private val lineWidth = spec.slider("lineWidth")
    private val flashOnHit = spec.toggle("flashOnHit")
    private val flashColor = spec.color("flashColor")
    private val showAll = spec.toggle("showAll")
    private val farFillColor = spec.color("farFillColor")
    private val farOutlineColor = spec.color("farOutlineColor")

    private class Tracked(val section: Int, val anchor: Int, var lastHitMillis: Long = 0L)

    private val terminals = HashMap<Int, Tracked>()
    private val board = TerminalMarkerBoard()
    private var phaseThree = false
    private var lastLevel: Any? = null
    private var section = -1
    private var drawn = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        terminals.clear()
        board.reset()
        phaseThree = false
        diagnostics.enable("style=${style.value()} phase=${phase.value()} flash=${flashOnHit.value()} showAll=${showAll.value()}")
    }

    override fun onDisable() {
        terminals.clear()
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    private fun active(): Boolean = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7

    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null || !active()) {
            terminals.clear()
            board.reset()
            phaseThree = false
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            terminals.clear()
            board.reset()
            phaseThree = false
        }
        section = TerminalEspRules.playerSection(player.x, player.y, player.z)
        val seen = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            val stand = entity as? ArmorStand ?: continue
            val name = stand.customName?.string ?: continue
            if (name != "Inactive Terminal") continue
            val anchor = board.anchorAt(stand.x, stand.y, stand.z)
            if (anchor < 0) continue
            seen.add(stand.id)
            if (terminals.putIfAbsent(stand.id, Tracked(board.markers[anchor].section, anchor)) == null) {
                diagnostics.event("TERMINAL_FOUND section=${board.markers[anchor].section + 1} total=${terminals.size}")
                phaseThree = true
            }
        }
        // A stand that vanished while the player was near it is a finished terminal; far away it only left tracking range.
        val gone = terminals.filter { it.key !in seen }
        for ((id, tracked) in gone) {
            terminals.remove(id)
            val marker = board.markers[tracked.anchor]
            if (!marker.done && nearAnchor(player.x, player.y, player.z, tracked.anchor)) {
                marker.done = true
                diagnostics.event("TERMINAL_DONE source=stand section=${marker.section + 1} remaining=${remaining()}")
            }
        }
        board.markers.forEach { it.loaded = false }
        terminals.values.forEach { board.markers[it.anchor].loaded = true }
        diagnostics.status {
            "enabled=true section=${section + 1} loaded=${terminals.size} remaining=${remaining()} phase3=$phaseThree " +
                "drawn=$drawn style=${style.value()} showAll=${showAll.value()} suppressedEvents=$it"
        }
    }

    private fun remaining() = board.markers.count { !it.done }

    private fun nearAnchor(x: Double, y: Double, z: Double, anchor: Int): Boolean {
        val m = board.markers[anchor]
        val dx = x - m.x
        val dy = y - m.y
        val dz = z - m.z
        return dx * dx + dy * dy + dz * dz <= LOST_RANGE * LOST_RANGE
    }

    /** Boss-room chat: Goldor starts Phase 3, the Core line ends it, a terminal line retires the nearest marker. */
    fun onChatMessage(plain: String) {
        if (!isEnabled() || !active()) return
        when {
            GOLDOR.matches(plain) -> {
                board.reset()
                terminals.clear()
                phaseThree = true
                diagnostics.event("PHASE3_START markers=${board.markers.size}")
            }
            CORE.matches(plain) -> {
                phaseThree = false
                diagnostics.event("PHASE3_END remaining=${remaining()}")
            }
            else -> {
                val name = TerminalMarkerBoard.terminalPlayer(plain) ?: return
                val client = Minecraft.getInstance()
                val who = client.level?.players()?.firstOrNull { it.name.string.equals(name, ignoreCase = true) }
                if (who == null) {
                    diagnostics.event("TERMINAL_DONE source=chat player=unloaded remaining=${remaining()}")
                    return
                }
                val index = board.markDoneNear(who.x, who.y, who.z)
                diagnostics.event(
                    "TERMINAL_DONE source=chat section=${if (index < 0) 0 else board.markers[index].section + 1} " +
                        "marked=${index >= 0} remaining=${remaining()}",
                )
            }
        }
    }

    /** An interact or attack aimed at [entity]; flashes the matching terminal box. */
    fun onEntityClick(entity: Entity) {
        if (!isEnabled() || !flashOnHit.value()) return
        terminals[entity.id]?.lastHitMillis = System.currentTimeMillis()
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || !active() || (terminals.isEmpty() && !(showAll.value() && phaseThree))) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val mode = style.value()
        val wantFill = mode == "FILLED" || mode == "FILLED_OUTLINE"
        val wantOutline = mode == "OUTLINE" || mode == "FILLED_OUTLINE"
        val now = System.currentTimeMillis()
        val everything = showAll.value()
        for (marker in board.markers) {
            if (marker.done || marker.loaded || !everything || !phaseThree) continue
            EspRenderer.renderBox(
                context.poseStack(), buffers, marker.x + 0.5 - STATIC_HALF - camera.x, marker.y - camera.y,
                marker.z + 0.5 - STATIC_HALF - camera.z, 2 * STATIC_HALF, STATIC_HEIGHT, 2 * STATIC_HALF,
                if (wantFill) farFillColor.argb() else 0, if (wantOutline) farOutlineColor.argb() else 0,
                lineWidth.value(), !phase.value(),
            )
            drawn++
        }
        for ((id, tracked) in terminals) {
            if (!everything && tracked.section != section) continue
            if (board.markers[tracked.anchor].done) continue
            val stand = level.getEntity(id) ?: continue
            val box = stand.boundingBox
            val flash = flashOnHit.value() && now - tracked.lastHitMillis < FLASH_MILLIS
            val fill = if (flash) withAlphaOf(flashColor.argb(), fillColor.argb()) else fillColor.argb()
            val outline = if (flash) withAlphaOf(flashColor.argb(), outlineColor.argb()) else outlineColor.argb()
            EspRenderer.renderBox(
                context.poseStack(), buffers, box.minX - camera.x, box.minY - camera.y, box.minZ - camera.z,
                box.xsize, box.ysize, box.zsize,
                if (wantFill) fill else 0, if (wantOutline) outline else 0, lineWidth.value(), !phase.value(),
            )
            drawn++
        }
        if (drawn > 0) GeilerAddonsRenderTypes.endBatches(buffers)
    }

    /** [rgbSource]'s colour with [alphaSource]'s alpha. */
    private fun withAlphaOf(rgbSource: Int, alphaSource: Int): Int = (rgbSource and 0xFFFFFF) or (alphaSource and (0xFF shl 24))

    companion object {
        private const val FLASH_MILLIS = 250L
        private const val LOST_RANGE = 48.0
        private const val STATIC_HALF = 0.3
        private const val STATIC_HEIGHT = 2.0
        private val GOLDOR = Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")
        private val CORE = Regex("^The Core entrance is opening!$")
        private val spec = ModuleSettingsSpec.of("Terminal ESP")

        @JvmField
        val INSTANCE = TerminalEspModule()
    }
}
