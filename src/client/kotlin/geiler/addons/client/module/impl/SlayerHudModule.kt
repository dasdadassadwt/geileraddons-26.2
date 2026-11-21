package geiler.addons.client.module.impl

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.slayer.SlayerBossBar
import geiler.addons.client.slayer.SlayerBossPhases
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.slayer.SlayerFight
import geiler.addons.client.slayer.SlayerHudText
import geiler.addons.client.slayer.SlayerPhaseProgress
import geiler.addons.client.slayer.SlayerPhaseRules
import geiler.addons.client.slayer.SlayerPhaseTimes
import geiler.addons.client.slayer.SlayerRules
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.BossEvent

/**
 * Your own Slayer boss: a vanilla-style boss bar in the top-center stack (client side only) titled `33M / 50M · 66%` with
 * notches where the boss's health-triggered phases begin, and a movable HUD under it with the fight time, a checklist
 * of those phases (passed, next with the health left to it, later) and per-phase timers. A chat line gives the spawn and
 * kill time when the sidebar says "Boss slain!". Health comes from the boss label (`☠ Name 12M❤`); the maximum comes from
 * [SlayerBossPhases] once the first read resolves the boss's tier, else the highest health seen. Fed by [SlayerBossTracker].
 */
class SlayerHudModule private constructor(private val s: Settings) : Module(
    "Slayer HUD",
    "Shows your Slayer boss's health as a top-center boss bar with phase marks, a phase list and fight time, and the kill time in chat.",
    Category.SLAYER,
    false,
    s.bar, s.lines, s.phaseList, s.killTime, s.phases,
), HudElement {
    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)
    private val fight = SlayerFight()
    private val phases = SlayerPhaseTimes()
    private var phaseRows: List<SlayerPhaseTimes.Row> = emptyList()
    private var lastPhase: String? = null
    private var family: SlayerRules.Family? = null
    private var tier: SlayerBossPhases.Tier? = null
    private var tierResolved = false
    private var progress: SlayerPhaseProgress? = null
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
        diagnostics.enable(
            "bar=${s.bar.value()} lines=${s.lines.value()} phaseList=${s.phaseList.value()} " +
                "killTime=${s.killTime.value()} phases=${s.phases.value()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        fight.clear()
        phases.clear()
        phaseRows = emptyList()
        lastPhase = null
        clearTier()
        hideBar()
        fights = 0
        kills = 0
    }

    private fun clearTier() {
        family = null
        tier = null
        tierResolved = false
        progress = null
    }

    private fun hideBar() {
        if (SlayerBossBar.hide()) diagnostics.event("BAR_REMOVE")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (!isActive()) {
            fight.clear()
            clearTier()
            hideBar()
            diagnostics.statusOnChange { "idle active=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        val snapshot = SlayerBossTracker.snapshot
        val label = snapshot.ownBoss?.label?.customName?.string?.let { ChatText.plain(it) }
        val was = fight.inBoss()
        val now = System.currentTimeMillis()
        val ownBoss = snapshot.ownBoss
        if (ownBoss != null) family = ownBoss.family
        if (s.phases.value() && ownBoss != null && snapshot.bossActive && !snapshot.bossSlain) {
            val phase = SlayerPhaseRules.phase(ownBoss.family, snapshot.labelTextsNear(ownBoss.body, PHASE_LABEL_RADIUS))
            if (phase != null && phase != lastPhase) {
                diagnostics.event("PHASE family=${ownBoss.family} phase=$phase")
                lastPhase = phase
            }
            phases.update(phase, now)
        }
        val finishedPhases = phases.rows(now)
        phaseRows = finishedPhases
        val result = fight.update(
            snapshot.questActive, snapshot.bossActive, snapshot.bossSlain, label?.let { SlayerHudText.parseHealth(it) },
            now,
        )
        if (!fight.inBoss()) {
            phases.clear()
            lastPhase = null
            phaseRows = emptyList()
            clearTier()
            hideBar()
        } else {
            updateBossState()
        }
        if (!was && fight.inBoss()) {
            fights++
            diagnostics.event("FIGHT_START family=$family labelSeen=${label != null} fights=$fights")
        }
        if (result != null) {
            kills++
            diagnostics.event(
                "FIGHT_END spawn=${result.spawnSeconds} kill=${result.killSeconds} maxHealth=${fight.maxHealth} kills=$kills " +
                    "phases=${SlayerPhaseTimes.summary(finishedPhases).ifEmpty { "none" }}",
            )
            if (s.killTime.value()) {
                val spawn = result.spawnSeconds?.let { ", spawned after ${SlayerHudText.duration(it)}" } ?: ""
                val phaseText = if (s.phases.value() && finishedPhases.isNotEmpty()) " (${SlayerPhaseTimes.summary(finishedPhases)})" else ""
                Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
                    Component.literal("[Slayer] Boss killed in ${SlayerHudText.duration(result.killSeconds)}$spawn$phaseText"),
                )
            }
        } else if (was && !fight.inBoss()) {
            diagnostics.event("FIGHT_LOST lastHealth=${fight.health}")
        }
        diagnostics.statusOnChange {
            "inBoss=${fight.inBoss()} health=${fight.health} maxHealth=${fight.maxHealth} tier=${tier?.number} " +
                "tableMax=${tier?.maxHealth} barShown=${SlayerBossBar.shown()} questActive=${snapshot.questActive} " +
                "bossActive=${snapshot.bossActive} ownBoss=${snapshot.ownBoss != null} fights=$fights kills=$kills"
        }
    }

    /** Resolves the tier from the fight's first health read, latches passed phases and refreshes the top-center bar. */
    private fun updateBossState() {
        val health = fight.health
        if (!tierResolved && health > 0L && family != null) {
            tierResolved = true
            tier = SlayerBossPhases.resolveTier(family, health)
            progress = tier?.let { SlayerPhaseProgress(it) }
            diagnostics.event(
                "TIER_RESOLVED family=$family firstRead=$health tier=${tier?.number} tableMax=${tier?.maxHealth} " +
                    "triggers=${tier?.triggers?.size}",
            )
        }
        val max = barMax()
        progress?.update(health)?.forEach {
            diagnostics.event("PHASE_PASSED name=${it.name} health=$health percent=${if (max > 0) health * 100 / max else 0}")
        }
        if (!s.bar.value() || max <= 0L || health <= 0L) {
            hideBar()
            return
        }
        val fraction = (health.toFloat() / max).coerceIn(0f, 1f)
        val added = SlayerBossBar.show(
            Component.literal(SlayerBossPhases.title(health, max)), fraction, barColor(fraction),
            tier?.let { SlayerBossPhases.notches(it) } ?: SlayerBossPhases.Notches.NONE,
        )
        if (added) diagnostics.event("BAR_ADD max=$max notches=${tier?.let { SlayerBossPhases.notches(it) }}")
    }

    /** The table maximum for the resolved tier, never below the highest health seen. */
    private fun barMax(): Long = maxOf(tier?.maxHealth ?: 0L, fight.maxHealth)

    private fun liveRows(): List<SlayerPhaseProgress.Row> = progress?.rows(fight.health).orEmpty()

    override fun width(font: Font?): Int = WIDTH

    override fun height(font: Font?): Int {
        val live = fight.inBoss()
        val checklist = if (s.phaseList.value()) (if (live) liveRows() else SAMPLE_ROWS).size else 0
        val timers = if (s.lines.value() && s.phases.value()) (if (live) phaseRows else SAMPLE_PHASES).size else 0
        val fightLine = if (s.lines.value()) 1 else 0
        return (fightLine + checklist + timers) * (font!!.lineHeight + 1)
    }

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        if (!fight.inBoss() || barMax() <= 0L) return
        draw(graphics, font, x, y, fight.fightSeconds(System.currentTimeMillis()), liveRows(), phaseRows)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        draw(graphics, font, x, y, 42.5f, SAMPLE_ROWS, SAMPLE_PHASES)
    }

    private fun draw(
        graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, seconds: Float,
        rows: List<SlayerPhaseProgress.Row>, timers: List<SlayerPhaseTimes.Row>,
    ) {
        val step = font.lineHeight + 1
        var top = y
        if (s.lines.value()) {
            graphics.text(font, "Fight ${SlayerHudText.duration(seconds)}", x, top, GuiTheme.TEXT_SECONDARY)
            top += step
        }
        if (s.phaseList.value()) {
            for (row in rows) {
                val color = when (row.status) {
                    SlayerPhaseProgress.Status.PASSED -> GuiTheme.TEXT_SECONDARY
                    SlayerPhaseProgress.Status.NEXT -> GuiTheme.TEXT_WARN
                    SlayerPhaseProgress.Status.UPCOMING -> GuiTheme.TEXT_PRIMARY
                }
                graphics.text(font, SlayerBossPhases.rowText(row), x, top, color)
                top += step
            }
        }
        if (s.lines.value() && s.phases.value()) {
            for (row in timers) {
                graphics.text(font, "${row.name} ${SlayerHudText.duration(row.seconds)}", x, top, GuiTheme.TEXT_SECONDARY)
                top += step
            }
        }
    }

    private fun barColor(fraction: Float): BossEvent.BossBarColor = when {
        fraction > 0.5f -> BossEvent.BossBarColor.GREEN
        fraction > 0.25f -> BossEvent.BossBarColor.YELLOW
        else -> BossEvent.BossBarColor.RED
    }

    class Settings {
        val bar = BooleanSetting("Boss Bar", true)
        val lines = BooleanSetting("Text Lines", true)
        val phaseList = BooleanSetting("Phase List", true)
        val killTime = BooleanSetting("Kill Time Message", true)
        val phases = BooleanSetting("Phase Timer", true)
    }

    companion object {
        private const val PHASE_LABEL_RADIUS = 4.0
        private val SAMPLE_PHASES = listOf(SlayerPhaseTimes.Row("Ashen", 14.2f), SlayerPhaseTimes.Row("Spirit", 9.8f))
        private val SAMPLE_ROWS: List<SlayerPhaseProgress.Row> = run {
            val sample = SlayerBossPhases.resolveTier(SlayerRules.Family.VOIDGLOOM, 210_000_000L)!!
            val progress = SlayerPhaseProgress(sample)
            progress.update(120_000_000L)
            progress.rows(120_000_000L)
        }

        /** Matches the vanilla boss bar width. */
        private const val WIDTH = 182

        @JvmField
        val INSTANCE = SlayerHudModule(Settings())
    }
}
