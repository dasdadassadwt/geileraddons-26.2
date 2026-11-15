package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.TerminalEspRules
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

    private class Tracked(val section: Int, var lastHitMillis: Long = 0L)

    private val terminals = HashMap<Int, Tracked>()
    private var lastLevel: Any? = null
    private var section = -1
    private var drawn = 0
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        terminals.clear()
        diagnostics.enable("style=${style.value()} phase=${phase.value()} flash=${flashOnHit.value()}")
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
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            terminals.clear()
        }
        section = TerminalEspRules.playerSection(player.x, player.y, player.z)
        val seen = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            val stand = entity as? ArmorStand ?: continue
            val name = stand.customName?.string ?: continue
            if (name != "Inactive Terminal") continue
            val s = TerminalEspRules.terminalSection(stand.x, stand.y, stand.z)
            if (s < 0) continue
            seen.add(stand.id)
            if (terminals.putIfAbsent(stand.id, Tracked(s)) == null) {
                diagnostics.event("TERMINAL_FOUND section=${s + 1} total=${terminals.size}")
            }
        }
        if (terminals.keys.removeAll { it !in seen }) diagnostics.event("TERMINAL_DONE remaining=${terminals.size}")
        diagnostics.status { "enabled=true section=${section + 1} terminals=${terminals.size} drawn=$drawn style=${style.value()} suppressedEvents=$it" }
    }

    /** An interact or attack aimed at [entity]; flashes the matching terminal box. */
    fun onEntityClick(entity: Entity) {
        if (!isEnabled() || !flashOnHit.value()) return
        terminals[entity.id]?.lastHitMillis = System.currentTimeMillis()
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || terminals.isEmpty() || !active()) return
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val camera = client.gameRenderer.mainCamera().position()
        val buffers = WorldRenderBuffer.current()
        val mode = style.value()
        val wantFill = mode == "FILLED" || mode == "FILLED_OUTLINE"
        val wantOutline = mode == "OUTLINE" || mode == "FILLED_OUTLINE"
        val now = System.currentTimeMillis()
        for ((id, tracked) in terminals) {
            if (tracked.section != section) continue
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
        private val spec = ModuleSettingsSpec.of("Terminal ESP")

        @JvmField
        val INSTANCE = TerminalEspModule()
    }
}
