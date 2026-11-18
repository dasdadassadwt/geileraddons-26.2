package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.kuudra.KuudraData
import geiler.addons.client.kuudra.KuudraPhase
import geiler.addons.client.kuudra.KuudraRules
import geiler.addons.client.kuudra.KuudraState
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import java.util.Locale

private const val LABEL_LINE_WIDTH = 2.0f
private const val OUTLINE_LINE_WIDTH = 2.0f

/** The colour a `§x` health code stands for, so a world label can match the readout. */
private fun healthArgb(health: Int): Int = when (KuudraRules.healthColor(health)) {
    "§a" -> 0xFF55FF55.toInt()
    "§2" -> 0xFF00AA00.toInt()
    "§e" -> 0xFFFFFF55.toInt()
    "§6" -> 0xFFFFAA00.toInt()
    "§c" -> 0xFFFF5555.toInt()
    else -> 0xFFAA0000.toInt()
}

private fun stripColor(text: String): String = text.replace(Regex("§."), "").replace("❤", "")

/** Boxes Kuudra, writes his health over him and on the HUD, after Odin's Kuudra Info. */
class KuudraInfoModule private constructor() : Module(
    "Kuudra Info",
    "Highlights Kuudra and shows his health.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
), HudElement {
    private val highlight = spec.toggle("highlightKuudra")
    private val color = spec.color("kuudraColor")
    private val showHealth = spec.toggle("kuudraHp")
    private val size = spec.slider("healthSize")
    private val scaled = spec.toggle("useScaled")
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var seen = false

    override fun id(): String = "kuudra_health"
    override fun displayName(): String = "Kuudra Health"
    private fun text(): String {
        val boss = KuudraState.boss ?: return "§a100k/100k❤"
        return KuudraRules.healthText(boss.health, KuudraState.tier, scaled.value())
    }
    override fun width(font: Font?): Int = font!!.width(text())
    override fun height(font: Font?): Int = font!!.lineHeight
    override fun visible(): Boolean = isEnabled() && showHealth.value() && KuudraState.inKuudra() && KuudraState.boss != null
    override fun previewMode(): String = if (KuudraState.boss != null) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        graphics.text(font, Component.literal(text()), x, y, 0xFFFFFFFF.toInt())
    }

    override fun onEnable() {
        seen = false
        diagnostics.enable("highlight=${highlight.value()} health=${showHealth.value()} scaled=${scaled.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val boss = KuudraState.boss
        if ((boss != null) != seen) {
            seen = boss != null
            diagnostics.event(if (seen) "KUUDRA found tier=${KuudraState.tier}" else "KUUDRA gone")
        }
        diagnostics.status { "enabled=true inKuudra=${KuudraState.inKuudra()} phase=${KuudraState.phase.displayName} tier=${KuudraState.tier} boss=$seen suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled() || !KuudraState.inKuudra()) return
        val boss = KuudraState.boss ?: return
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val camPos = camera.position()
        val buffers = WorldRenderBuffer.current()
        val box = boss.boundingBox
        if (highlight.value()) {
            EspRenderer.renderBox(
                context.poseStack(), buffers, box.minX - camPos.x, box.minY - camPos.y, box.minZ - camPos.z,
                box.xsize, box.ysize, box.zsize, 0, color.argb(), OUTLINE_LINE_WIDTH, true,
            )
        }
        if (showHealth.value()) {
            val raw = Math.round(boss.health)
            EspRenderer.renderLabel(
                context.poseStack(), buffers, camera.rotation(), boss.x - camPos.x, box.maxY + 0.8 - camPos.y, boss.z - camPos.z,
                stripColor(KuudraRules.healthText(boss.health, KuudraState.tier, scaled.value())), healthArgb(raw), size.value(), LABEL_LINE_WIDTH,
            )
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Kuudra Info")

        @JvmField
        val INSTANCE = KuudraInfoModule()
    }
}

private fun boxAt(context: LevelRenderContext, buffers: WorldRenderBuffer.BufferSource, x: Double, y: Double, z: Double, size: Double, argb: Int, filled: Boolean) {
    val camPos = Minecraft.getInstance().gameRenderer.mainCamera().position()
    val fill = if (filled) (argb and 0x00FFFFFF) or 0x50000000 else 0
    EspRenderer.renderBox(context.poseStack(), buffers, x - size / 2 - camPos.x, y - camPos.y, z - size / 2 - camPos.z,
        size, size, size, fill, argb, OUTLINE_LINE_WIDTH, false)
}

private fun labelAt(context: LevelRenderContext, buffers: WorldRenderBuffer.BufferSource, x: Double, y: Double, z: Double, text: String, argb: Int, height: Float) {
    val camera = Minecraft.getInstance().gameRenderer.mainCamera()
    val camPos = camera.position()
    EspRenderer.renderLabel(context.poseStack(), buffers, camera.rotation(), x - camPos.x, y - camPos.y, z - camPos.z, text, argb, height, LABEL_LINE_WIDTH)
}

/** Draws the timed pearl landings of the lobby area you stand in during the supplies phase, after IQ's Pearl Waypoints (Apache-2.0). */
class PearlWaypointsModule private constructor() : Module(
    "Pearl Waypoints",
    "Shows the pearl landings and when to throw them for the area you stand in during Kuudra supplies.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
) {
    private val labels = spec.toggle("showLabels")
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var drawn = 0
    private var lastArea: String? = null

    override fun onEnable() {
        diagnostics.enable("labels=${labels.value()} areas=${KuudraData.pearlAreas().size}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true phase=${KuudraState.phase.displayName} area=$lastArea drawn=$drawn suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || !KuudraState.inKuudra() || KuudraState.phase != KuudraPhase.SUPPLIES) return
        val player = Minecraft.getInstance().player ?: return
        val area = KuudraData.pearlAreas().firstOrNull { it.contains(player.x, player.z) }
        if (area?.name != lastArea) {
            lastArea = area?.name
            diagnostics.event("AREA ${area?.name ?: "none"}")
        }
        area ?: return
        val buffers = WorldRenderBuffer.current()
        for (spot in area.spots) {
            boxAt(context, buffers, spot.x, spot.y - spot.size / 2, spot.z, spot.size, spot.argb, true)
            if (labels.value() && spot.text.isNotEmpty()) labelAt(context, buffers, spot.x, spot.y + spot.size + 0.4, spot.z, spot.text, spot.argb, 0.6f)
            drawn++
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Pearl Waypoints")

        @JvmField
        val INSTANCE = PearlWaypointsModule()
    }
}

/** Marks the supply crates and the piles to deliver to in the supplies phase and tells how long each pickup took, after IQ's supply waypoints. */
class SupplyHelperModule private constructor() : Module(
    "Supply Helper",
    "Marks Kuudra supply crates and piles in phase 1 and shows when each supply was collected.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
) {
    private val crates = spec.toggle("supplyWaypoints")
    private val pileMarks = spec.toggle("pileWaypoints")
    private val color = spec.color("supplyColor")
    private val sendTime = spec.toggle("sendSupplyTime")
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var drawn = 0
    private var collected = 0

    override fun onEnable() {
        collected = 0
        diagnostics.enable("crates=${crates.value()} piles=${pileMarks.value()} sendTime=${sendTime.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE collected=$collected")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true phase=${KuudraState.phase.displayName} giants=${KuudraState.giants.size} stands=${KuudraState.receivedStands.size} drawn=$drawn collected=$collected suppressedEvents=$it" }
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled() || !sendTime.value() || !KuudraState.inKuudra() || KuudraState.phase != KuudraPhase.SUPPLIES) return
        val match = RECOVERED.find(plain) ?: return
        val start = KuudraState.runStartNanos.takeIf { it > 0 } ?: return
        val seconds = (System.nanoTime() - start) / 1.0E9
        collected++
        diagnostics.event("COLLECTED n=${match.groupValues[2]}/${match.groupValues[3]} seconds=${"%.1f".format(Locale.ROOT, seconds)}")
        PlayerAlerts.chat("§7${match.groupValues[1]} collected a supply at §a${"%.1f".format(Locale.ROOT, seconds)}s §8(${match.groupValues[2]}/${match.groupValues[3]})")
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || !KuudraState.inKuudra() || KuudraState.phase != KuudraPhase.SUPPLIES) return
        val buffers = WorldRenderBuffer.current()
        if (crates.value()) {
            for (giant in KuudraState.giants) {
                val crate = KuudraRules.crateFor(giant.x, giant.z, giant.yRot)
                boxAt(context, buffers, crate[0], crate[1], crate[2], 1.0, color.argb(), true)
                labelAt(context, buffers, crate[0], crate[1] + 1.6, crate[2], "Pick Up!", color.argb(), 0.6f)
                drawn++
            }
        }
        if (pileMarks.value()) {
            for (pile in KuudraData.piles()) {
                if (KuudraState.receivedStands.any { KuudraRules.pileCompleted(pile.x, pile.y, pile.z, it.x, it.y, it.z) }) continue
                boxAt(context, buffers, pile.x, pile.y, pile.z, 1.0, 0xFF55FFFF.toInt(), true)
                labelAt(context, buffers, pile.x, pile.y + 1.4, pile.z, pile.name, 0xFF55FFFF.toInt(), 0.5f)
                drawn++
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val RECOVERED = Regex("^(\\S+) recovered one of Elle's supplies! \\((\\d+)/(\\d+)\\)$")
        private val spec = ModuleSettingsSpec.of("Supply Helper")

        @JvmField
        val INSTANCE = SupplyHelperModule()
    }
}

/** Draws IQ's bundled safe spots and DPS and skip waypoints in the phases each one names (Apache-2.0, see IQ-NOTICE.txt). */
class KuudraWaypointsModule private constructor() : Module(
    "Kuudra Waypoints",
    "Shows safe spots in the supplies phase and the DPS and skip spots in the boss phases.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
) {
    private val safe = spec.toggle("safeSpots")
    private val custom = spec.toggle("dpsSkipSpots")
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var drawn = 0
    private var lastPhase = KuudraPhase.NONE

    override fun onEnable() {
        diagnostics.enable("safe=${safe.value()} custom=${custom.value()} safeWaypoints=${KuudraData.safeSpots().size} customWaypoints=${KuudraData.customWaypoints().size}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (KuudraState.phase != lastPhase) {
            lastPhase = KuudraState.phase
            diagnostics.event("PHASE ${lastPhase.displayName}")
        }
        diagnostics.status { "enabled=true phase=${KuudraState.phase.displayName} drawn=$drawn suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        val phase = KuudraState.phase
        if (!isEnabled() || !KuudraState.inKuudra() || !phase.isInRun()) return
        val player = Minecraft.getInstance().player ?: return
        val buffers = WorldRenderBuffer.current()
        val lists = buildList {
            if (safe.value()) add(KuudraData.safeSpots())
            if (custom.value()) add(KuudraData.customWaypoints())
        }
        for (waypoint in lists.flatten()) {
            if (phase !in waypoint.showIn || phase in waypoint.hideIn) continue
            for (at in waypoint.positions) {
                if (at.size < 3) continue
                if (waypoint.maxDistance > 0 && player.distanceToSqr(at[0], at[1], at[2]) > waypoint.maxDistance * waypoint.maxDistance) continue
                boxAt(context, buffers, at[0], at[1], at[2], 1.0, waypoint.argb, waypoint.filled)
                if (waypoint.text.isNotEmpty()) labelAt(context, buffers, at[0], at[1] + 1.4, at[2], waypoint.text, waypoint.argb, 0.5f)
                drawn++
            }
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Kuudra Waypoints")

        @JvmField
        val INSTANCE = KuudraWaypointsModule()
    }
}

/** Shows the ballista build in phase 2: builders, progress, piles coloured by progress and a stun call, after IQ's build helper and Odin's stun call. */
class BuildHelperModule private constructor() : Module(
    "Build Helper",
    "Shows the Kuudra ballista build: progress, builders, unfinished piles coloured by progress and when to go stun.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
), HudElement {
    private val unfinished = spec.toggle("unfinishedWaypoints")
    private val stunPercent = spec.slider("stunPercent")
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var stunned = false
    private var drawn = 0

    override fun id(): String = "kuudra_build"
    override fun displayName(): String = "Kuudra Build"
    private fun lines(): List<String> {
        val live = KuudraState.inKuudra() && KuudraState.phase == KuudraPhase.BUILD
        val builders = if (live) KuudraState.builders else 3
        val percent = if (live) KuudraState.buildPercent else 67
        return listOf(
            "Builders: ${KuudraRules.buildersColor(builders)}$builders",
            "Build: §f$percent%",
        )
    }
    override fun width(font: Font?): Int = lines().maxOf { font!!.width(it) }
    override fun height(font: Font?): Int = lines().size * (font!!.lineHeight + 1)
    override fun visible(): Boolean = isEnabled() && KuudraState.inKuudra() && KuudraState.phase == KuudraPhase.BUILD
    override fun previewMode(): String = if (visible()) "live" else "sample"
    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        lines().forEachIndexed { index, line ->
            graphics.text(font, Component.literal(line), x, y + index * (font.lineHeight + 1), 0xFFFFFFFF.toInt())
        }
    }

    override fun onEnable() {
        stunned = false
        diagnostics.enable("unfinished=${unfinished.value()} stunPercent=${stunPercent.intValue()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (KuudraState.phase != KuudraPhase.BUILD) stunned = false
        else if (!stunned && KuudraRules.shouldStun(KuudraState.tier, KuudraState.buildPercent, stunPercent.intValue())) {
            stunned = true
            diagnostics.event("STUN tier=${KuudraState.tier} percent=${KuudraState.buildPercent}")
            PlayerAlerts.alert("§cGo to stun")
        }
        diagnostics.status { "enabled=true phase=${KuudraState.phase.displayName} builders=${KuudraState.builders} percent=${KuudraState.buildPercent} piles=${KuudraState.piles.size} drawn=$drawn suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        drawn = 0
        if (!isEnabled() || !unfinished.value() || !KuudraState.inKuudra() || KuudraState.phase != KuudraPhase.BUILD) return
        val buffers = WorldRenderBuffer.current()
        for (pile in KuudraState.piles) {
            val argb = KuudraRules.buildProgressColor(pile.percent)
            boxAt(context, buffers, pile.x, pile.y - 2.0, pile.z, 1.0, argb, true)
            labelAt(context, buffers, pile.x, pile.y + 0.6, pile.z, "${pile.percent}%", argb, 0.5f)
            drawn++
        }
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Build Helper")

        @JvmField
        val INSTANCE = BuildHelperModule()
    }
}

/** Warns when the block under you is a tentacle danger zone (green to red terracotta); yellow and up means jump, after IQ's danger zone alert. */
class DangerZoneAlertModule private constructor() : Module(
    "Danger Zone Alert",
    "Warns in Kuudra's fight phases when you stand on a danger zone that is about to hit.",
    Category.KUUDRA,
    false,
    *spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.KUUDRA, { name() }, 8, 100L, 300)
    private var cooldown = 0
    private var alerts = 0
    private var lastLevel = -1

    override fun onEnable() {
        alerts = 0
        diagnostics.enable("combatPhasesOnly=true")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE alerts=$alerts")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (cooldown > 0) cooldown--
        var level = -1
        if (KuudraState.inKuudra() && KuudraState.phase.isCombat()) {
            val client = Minecraft.getInstance()
            val player = client.player
            val world = client.level
            if (player != null && world != null) {
                level = KuudraRules.dangerLevel(BuiltInRegistries.BLOCK.getKey(world.getBlockState(player.blockPosition().below()).block).toString())
            }
        }
        if (level != lastLevel) {
            lastLevel = level
            if (level >= 0) diagnostics.event("DANGER level=$level phase=${KuudraState.phase.displayName}")
        }
        if (level == 1 && cooldown == 0) {
            cooldown = ALERT_COOLDOWN_TICKS
            alerts++
            PlayerAlerts.alert("§cJump!")
        }
        diagnostics.status { "enabled=true phase=${KuudraState.phase.displayName} dangerLevel=$level alerts=$alerts suppressedEvents=$it" }
    }

    companion object {
        private const val ALERT_COOLDOWN_TICKS = 20
        private val spec = ModuleSettingsSpec.of("Danger Zone Alert")

        @JvmField
        val INSTANCE = DangerZoneAlertModule()
    }
}
