package geiler.addons.client.module.impl

import geiler.addons.client.farming.SlayerAlertRules
import geiler.addons.client.farming.SlayerAlertRules.BossType
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.slayer.SlayerBossTracker
import geiler.addons.client.slayer.SlayerRules
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.EnumMap

/**
 * Chat, title and sound alerts for your own Slayer: one when your boss spawns (the sidebar turns to "Slay the boss!",
 * the boss type read from its label) and one when a miniboss shows up near you while your quest is active. Bosses and
 * minibosses of other players never alert: the boss alert follows your sidebar, and the miniboss alert needs your own
 * active quest, a miniboss within [MINIBOSS_RADIUS] blocks, and ignores the ones already there when the quest began.
 */
class SlayerAlertsModule private constructor(private val s: Settings) : Module(
    "Slayer Alerts",
    "Alerts when your Slayer boss spawns or a miniboss appears near you during your quest.",
    Category.SLAYER,
    false,
    *s.spec.settings.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.SLAYER, { name() }, 8, 100L, 300)
    private val seenMinibosses = HashSet<Int>()
    private var minibossesPrimed = false
    private var bossAlerted = false
    private var pendingSinceTick = -1L
    private var bossAlerts = 0
    private var minibossAlerts = 0
    private var filtered = 0

    init {
        group(*s.spec.groups.toTypedArray())
    }

    override fun isActive(): Boolean = isEnabled() && SlayerBossTracker.eligible()

    override fun inactiveReason(): String? =
        if (isEnabled() && !SlayerBossTracker.eligible()) "Off in dungeons, Kuudra and outside SkyBlock" else null

    override fun onEnable() {
        reset()
        diagnostics.enable(
            "boss=${s.bossAlert.value()} miniboss=${s.minibossAlert.value()} chat=${s.chat.value()} " +
                "title=${s.title.value()} sound=${s.sound.value()} titleTicks=${s.titleTicks.intValue()}",
        )
    }

    override fun onDisable() {
        reset()
        diagnostics.disable()
    }

    private fun reset() {
        seenMinibosses.clear()
        minibossesPrimed = false
        bossAlerted = false
        pendingSinceTick = -1L
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val player = Minecraft.getInstance().player
        if (!isActive() || player == null) {
            reset()
            diagnostics.statusOnChange { "idle active=false eligible=${SlayerBossTracker.eligible()}" }
            return
        }
        val snapshot = SlayerBossTracker.snapshot
        val now = SlayerBossTracker.clock()

        if (s.bossAlert.value()) {
            if (!snapshot.bossActive) {
                bossAlerted = false
                pendingSinceTick = -1L
            } else if (!bossAlerted) {
                if (pendingSinceTick < 0) pendingSinceTick = now
                val label = snapshot.ownBoss?.label?.customName?.string
                val type = SlayerAlertRules.classifyBossName(label)
                // The sidebar changes a moment before the boss entity loads; wait briefly for its label.
                if (type != null || now - pendingSinceTick >= LABEL_WAIT_TICKS) {
                    bossAlerted = true
                    alertBoss(type)
                }
            }
        }

        if (s.minibossAlert.value() && snapshot.questActive) {
            val limit = MINIBOSS_RADIUS * MINIBOSS_RADIUS
            val near = snapshot.minibosses.filter { it.body.distanceToSqr(player) <= limit }
            if (!minibossesPrimed) {
                // Minibosses already standing there when the quest became active were not spawned by this quest.
                minibossesPrimed = true
                near.forEach { seenMinibosses.add(it.body.id) }
            } else {
                for (mini in near) if (seenMinibosses.add(mini.body.id)) alertMiniboss(mini.name)
            }
            seenMinibosses.retainAll(snapshot.minibosses.map { it.body.id }.toSet())
        } else if (!snapshot.questActive) {
            seenMinibosses.clear()
            minibossesPrimed = false
        }

        diagnostics.statusOnChangeBy(20L, { "${snapshot.bossActive}/${snapshot.questActive}/$bossAlerted" }) {
            "bossActive=${snapshot.bossActive} questActive=${snapshot.questActive} bossAlerted=$bossAlerted " +
                "bossAlerts=$bossAlerts minibossAlerts=$minibossAlerts filtered=$filtered " +
                "minibossesSeen=${seenMinibosses.size} scans=${SlayerBossTracker.scans}"
        }
    }

    private fun alertBoss(type: BossType?) {
        val allowed = SlayerAlertRules.bossFilterAllows(
            SlayerAlertRules.BossObservation(type, false), s.filters.mapValues { it.value.value() }, s.unclassified.value(),
        )
        if (!allowed) {
            filtered++
            diagnostics.event("BOSS_ALERT_FILTERED type=${type?.name ?: "none"}")
            return
        }
        bossAlerts++
        diagnostics.event("BOSS_ALERT type=${type?.name ?: "none"}")
        emit(SlayerAlertRules.presentation(
            s.chat.value(), s.title.value(), s.sound.value(), s.titleTicks.intValue(), s.bossText.value(),
            type, type?.displayName(), null,
        ))
    }

    private fun alertMiniboss(label: String) {
        val name = SlayerRules.minibossName(label) ?: "Slayer miniboss"
        minibossAlerts++
        diagnostics.event("MINIBOSS_ALERT name=$name")
        emit(SlayerAlertRules.presentation(
            s.chat.value(), s.title.value(), s.sound.value(), s.titleTicks.intValue(), s.minibossText.value(),
            null, null, name,
        ))
    }

    private fun emit(presentation: SlayerAlertRules.Presentation) {
        val message = presentation.message ?: return
        if (presentation.showChat) {
            Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal("[Slayer] $message"))
        }
        if (presentation.showTitle) TitleAlertStack.submit("slayer_alerts", message, presentation.durationTicks, WHITE)
        if (presentation.playSound) ModuleSounds.play("minecraft:entity.experience_orb.pickup", 1f, 1f)
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Slayer Alerts")
        val bossAlert = spec.toggle("bossAlert")
        val unclassified = spec.toggle("unclassified")
        val filters = EnumMap<BossType, BooleanSetting>(BossType::class.java).also { map ->
            for (type in BossType.values()) map[type] = spec.toggle("filter${type.name}")
        }
        val bossText = spec.text("bossText")
        val minibossAlert = spec.toggle("minibossAlert")
        val minibossText = spec.text("minibossText")
        val chat = spec.toggle("chat")
        val title = spec.toggle("title")
        val sound = spec.toggle("sound")
        val titleTicks = spec.slider("titleTicks")
    }

    companion object {
        private const val MINIBOSS_RADIUS = 24.0
        private const val LABEL_WAIT_TICKS = 40L
        private const val WHITE = 0xFFFFFFFF.toInt()

        @JvmField
        val INSTANCE = SlayerAlertsModule(Settings())
    }
}
