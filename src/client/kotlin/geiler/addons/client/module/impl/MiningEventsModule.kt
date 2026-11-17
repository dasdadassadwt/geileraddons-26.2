package geiler.addons.client.module.impl

import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.mining.MiningConditions
import geiler.addons.client.mining.MiningContext
import geiler.addons.client.mining.MineshaftRules
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Mining event and reminder panel. Shows the active mining event from the tab list with its time left and alerts
 * when one starts; reminds you to feed Don Expresso when he is full and you hold Tasty Mithril; and alerts when a
 * Golden or Diamond Goblin spawns.
 */
class MiningEventsModule private constructor(settings: Settings) : MiningHudModule(
    "Mining Events",
    "Shows the active mining event and alerts for new events, goblins and Don Expresso.",
    "mining_events",
    "Mining Events",
    listOf("§bEvent §f2x Powder §7(12:30)"),
    settings.display,
    settings.appearance,
) {
    private val eventPanel = settings.eventPanel
    private val eventAlert = settings.eventAlert
    private val goblinAlert = settings.goblinAlert
    private val donExpresso = settings.donExpresso

    private var activeEvent: String? = null
    private var tickCount = 0L
    private var alerts = 0L

    override fun diagnosticSettings(): String =
        "panel=${eventPanel.value()};eventAlert=${eventAlert.value()};goblinAlert=${goblinAlert.value()};donExpresso=${donExpresso.value()}"

    override fun onEnable() {
        activeEvent = null
        alerts = 0
        super.onEnable()
    }

    fun tick() {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { tick() }
            return
        }
        beginTick()
        tickCount++
        val inMining = mc.level != null && mc.player != null && (MiningContext.on(geiler.addons.client.location.Island.DWARVEN_MINES) ||
            MiningContext.on(geiler.addons.client.location.Island.CRYSTAL_HOLLOWS))
        if (!inMining) {
            lines = emptyList()
            activeEvent = null
            return
        }
        if (tickCount % READ_TICKS != 0L) return
        val event = MiningConditions.miningEvent(MiningContext.tabLines())
        if (event?.name != activeEvent) {
            if (event != null) {
                diagnostics.event("EVENT_STARTED name=${event.name} seconds=${event.secondsLeft}")
                if (eventAlert.value()) alert("mining_event", "§b${MiningConditions.eventTitle(event.name)}", 100)
            } else {
                diagnostics.event("EVENT_ENDED name=$activeEvent")
            }
            activeEvent = event?.name
        }
        lines = if (event == null || !eventPanel.value()) emptyList() else {
            val time = event.secondsLeft?.let { " §7(${MineshaftRules.clock(it * 1_000_000_000L)})" }.orEmpty()
            listOf("§bEvent §f${MiningConditions.eventTitle(event.name)}$time")
        }
        diagnostics.statusOnChange { "event=${event?.name} seconds=${event?.secondsLeft} alerts=$alerts island=${HypixelModApi.currentIsland()}" }
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            mc.execute { onChatMessage(plain) }
            return
        }
        if (goblinAlert.value()) {
            when {
                MiningConditions.isGoldenGoblinSpawn(plain) -> alert("goblin", "§6Golden Goblin", 80)
                MiningConditions.isDiamondGoblinSpawn(plain) -> alert("goblin", "§bDiamond Goblin", 80)
            }
        }
        if (donExpresso.value() && MiningConditions.isDonExpressoFull(plain) && holdsTastyMithril(mc)) {
            alert("don_expresso", "§6Don Expresso is full!", 100)
            mc.player?.sendSystemMessage(Component.literal("§6Don Expresso §eis full! Go feed him your Tasty Mithril."))
        }
    }

    private fun holdsTastyMithril(mc: Minecraft): Boolean {
        val inventory = mc.player?.inventory ?: return false
        for (slot in 0 until inventory.containerSize) {
            if (SkyblockItems.skyBlockId(inventory.getItem(slot)) == TASTY_MITHRIL) return true
        }
        return false
    }

    private fun alert(source: String, text: String, ticks: Int) {
        alerts++
        TitleAlertStack.submit("mining_$source", text, ticks, WHITE)
        diagnostics.event("ALERT source=$source alerts=$alerts")
    }

    private class Settings {
        val eventPanel = BooleanSetting("Show Event Panel", true)
        val eventAlert = BooleanSetting("Alert On New Event", true)
        val goblinAlert = BooleanSetting("Goblin Spawn Alert", true)
        val donExpresso = BooleanSetting("Don Expresso Reminder", true)
        val display: List<Setting> = listOf(eventPanel, eventAlert, goblinAlert, donExpresso)
        val appearance = MiningHudAppearance()
    }

    companion object {
        private const val READ_TICKS = 20L
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val TASTY_MITHRIL = "MITHRIL_GOURMAND"

        @JvmField
        val INSTANCE = MiningEventsModule(Settings())
    }
}
