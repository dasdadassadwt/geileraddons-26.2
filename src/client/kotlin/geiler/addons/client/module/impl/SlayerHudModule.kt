package geiler.addons.client.module.impl

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.slayer.SlayerFight
import geiler.addons.client.slayer.SlayerHudText
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * Your own Slayer boss on the HUD: a health bar, name / health / fight-time lines, and a chat line with the spawn and
 * kill time when the sidebar says "Boss slain!". Health comes from the boss label (`☠ Name 12M❤`); the bar fills
 * against the highest health seen in the fight, since the label never shows the maximum. Fed by [SlayerBossTracker].
 */
class SlayerHudModule private constructor(private val s: Settings) : Module(
    "Slayer HUD",
    "Shows your Slayer boss's health bar, health and fight time, and the kill time in chat.",
    Category.SLAYER,
    false,
    s.bar, s.lines, s.killTime,
), HudElement {
    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)
    private val fight = SlayerFight()
    private var bossName = ""
    private var fights = 0
    private var kills = 0

    override fun id(): String = "slayer_hud"

    override fun displayName(): String = "Slayer HUD"

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    override fun visible(): Boolean = isActive()

    override fun previewMode(): String = if (fight.inBoss()) "live" else "sample"

    override fun onEnable() {
        reset()
        diagnostics.enable("bar=${s.bar.value()} lines=${s.lines.value()} killTime=${s.killTime.value()}")
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        fight.clear()
        bossName = ""
        fights = 0
        kills = 0
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (!isActive()) {
            fight.clear()
            diagnostics.statusOnChange { "idle active=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        val snapshot = SlayerBossTracker.snapshot
        val label = snapshot.ownBoss?.label?.customName?.string?.let { ChatText.plain(it) }
        if (label != null) bossName = SlayerHudText.bossName(label)
        val was = fight.inBoss()
        val result = fight.update(
            snapshot.questActive, snapshot.bossActive, snapshot.bossSlain, label?.let { SlayerHudText.parseHealth(it) },
            System.currentTimeMillis(),
        )
        if (!was && fight.inBoss()) {
            fights++
            diagnostics.event("FIGHT_START boss=${bossName.take(40)} labelSeen=${label != null} fights=$fights")
        }
        if (result != null) {
            kills++
            diagnostics.event("FIGHT_END spawn=${result.spawnSeconds} kill=${result.killSeconds} maxHealth=${fight.maxHealth} kills=$kills")
            if (s.killTime.value()) {
                val spawn = result.spawnSeconds?.let { ", spawned after ${SlayerHudText.duration(it)}" } ?: ""
                Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
                    Component.literal("[Slayer] Boss killed in ${SlayerHudText.duration(result.killSeconds)}$spawn"),
                )
            }
        } else if (was && !fight.inBoss()) {
            diagnostics.event("FIGHT_LOST lastHealth=${fight.health}")
        }
        diagnostics.statusOnChange {
            "inBoss=${fight.inBoss()} health=${fight.health} maxHealth=${fight.maxHealth} questActive=${snapshot.questActive} " +
                "bossActive=${snapshot.bossActive} ownBoss=${snapshot.ownBoss != null} fights=$fights kills=$kills"
        }
    }

    override fun width(font: Font?): Int = WIDTH

    override fun height(font: Font?): Int {
        val bar = if (s.bar.value()) BAR_HEIGHT + 3 else 0
        val text = if (s.lines.value()) LINE_COUNT * (font!!.lineHeight + 1) else 0
        return bar + text
    }

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (!fight.inBoss() || fight.maxHealth <= 0L) return
        draw(graphics, font, x, y, bossName.ifEmpty { "Slayer boss" }, fight.health, fight.maxHealth, fight.fightSeconds(System.currentTimeMillis()))
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        draw(graphics, font, x, y, "Voidgloom Seraph", 8_400_000L, 12_000_000L, 42.5f)
    }

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, name: String, health: Long, max: Long, seconds: Float) {
        val fraction = (health.toFloat() / max).coerceIn(0f, 1f)
        var top = y
        if (s.bar.value()) {
            graphics.fill(x, top, x + WIDTH, top + BAR_HEIGHT, BAR_BACK)
            graphics.fill(x, top, x + (WIDTH * fraction).toInt(), top + BAR_HEIGHT, barColor(fraction))
            top += BAR_HEIGHT + 3
        }
        if (s.lines.value()) {
            val step = font.lineHeight + 1
            graphics.text(font, name, x, top, GuiTheme.TEXT_PRIMARY)
            graphics.text(
                font, "HP ${SlayerHudText.formatHealth(health)} / ${SlayerHudText.formatHealth(max)} (${(fraction * 100).toInt()}%)",
                x, top + step, GuiTheme.TEXT_PRIMARY,
            )
            graphics.text(font, "Fight ${SlayerHudText.duration(seconds)}", x, top + 2 * step, GuiTheme.TEXT_SECONDARY)
        }
    }

    private fun barColor(fraction: Float): Int = when {
        fraction > 0.5f -> GREEN
        fraction > 0.25f -> YELLOW
        else -> RED
    }

    class Settings {
        val bar = BooleanSetting("Boss Bar", true)
        val lines = BooleanSetting("Text Lines", true)
        val killTime = BooleanSetting("Kill Time Message", true)
    }

    companion object {
        private const val WIDTH = 180
        private const val BAR_HEIGHT = 8
        private const val LINE_COUNT = 3
        private const val BAR_BACK = 0xAA000000.toInt()
        private const val GREEN = 0xFF55FF55.toInt()
        private const val YELLOW = 0xFFFFFF55.toInt()
        private const val RED = 0xFFFF5555.toInt()

        @JvmField
        val INSTANCE = SlayerHudModule(Settings())
    }
}
