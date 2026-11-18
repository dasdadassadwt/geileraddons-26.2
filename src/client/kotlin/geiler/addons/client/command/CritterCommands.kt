package geiler.addons.client.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.hunting.CritterReports
import geiler.addons.client.hunting.PriceMode
import geiler.addons.client.hunting.ShardPriceFeed
import geiler.addons.client.hunting.ShardValue
import geiler.addons.client.module.impl.Audience
import geiler.addons.client.module.impl.CritterAlertsModule
import geiler.addons.client.module.impl.CritterMarkersModule
import geiler.addons.client.module.impl.CritterSafari
import geiler.addons.client.module.impl.CritterSafariWorld
import geiler.addons.client.module.impl.CritterTrackerModule
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft

/**
 * `/critters` (alias `/ct`): read-outs of the live run in chat, a copy/share of the missing list, a price refresh and
 * quick toggles. These mirror Critter Mod's commands, minus the History/Stats/log-replay ones and the tabbed run screen.
 */
object CritterCommands {
    @JvmStatic
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = ClientCommands.literal("critters").executes { summary() }
            leaf(root, "missing") { missing() }
            leaf(root, "copy") { copy() }
            leaf(root, "share") { share() }
            leaf(root, "players") { players() }
            leaf(root, "prices") { prices() }
            leaf(root, "text") { summary() }
            leaf(root, "reset") { reset() }
            leaf(root, "waypoints") { waypoints() }
            leaf(root, "debug") { debug() }
            leaf(root, "testalert") { testAlert() }
            leaf(root, "hud") { toggle("Critter Tracker") { CritterTrackerModule.INSTANCE.toggle(); CritterTrackerModule.INSTANCE.isEnabled() } }
            leaf(root, "alerts") { toggle("Critter Alerts") { CritterAlertsModule.INSTANCE.toggle(); CritterAlertsModule.INSTANCE.isEnabled() } }
            leaf(root, "panel") { toggle("Progress panel") { CritterTrackerModule.INSTANCE.toggleProgressPanel() } }
            leaf(root, "biomedone") { toggle("Biome complete alert") { CritterAlertsModule.INSTANCE.toggleBiomeDone() } }
            val registered = dispatcher.register(root)
            dispatcher.register(ClientCommands.literal("ct").executes { summary() }.redirect(registered))
        }
    }

    private fun leaf(root: LiteralArgumentBuilder<FabricClientCommandSource>, name: String, action: () -> Int) {
        root.then(ClientCommands.literal(name).executes { action() })
    }

    private fun say(text: String, style: ChatFormatting = ChatFormatting.WHITE) = CritterSafari.say(text, style)

    private fun needRun(): geiler.addons.client.hunting.SafariRun? {
        val run = CritterSafari.currentOrLast()
        if (run == null) say("No Critter Safari run yet. Enter the Safari first.", ChatFormatting.RED)
        return run
    }

    private fun summary(): Int {
        val run = needRun() ?: return 0
        val now = System.currentTimeMillis()
        say(CritterReports.summaryLine(run, run.elapsedMillis(now)))
        if (run.totalAttempts() > 0) say("Capsules: ${run.totalAttempts()} thrown, ${run.totalFailures()} escaped", ChatFormatting.GRAY)
        valueLine(run, now)?.let { say(it, ChatFormatting.GOLD) }
        return 1
    }

    /** `Shards: 1.23M (instant) | 6.1M/h`, or null when value tracking is off. */
    private fun valueLine(run: geiler.addons.client.hunting.SafariRun, now: Long): String? {
        val tracker = CritterTrackerModule.INSTANCE
        if (!tracker.shardValueOn()) return null
        val (instant, have) = tracker.value(run, false)
        val (offers, _) = tracker.value(run, true)
        if (!have) return "Shards: no prices yet (try /critters prices)"
        val rate = ShardValue.perHour(if (tracker.priceMode() == PriceMode.SELL_OFFER) offers else instant, run.elapsedMillis(now))
        return "Shards: ${ShardValue.coins(instant)} instant / ${ShardValue.coins(offers)} offers | ${ShardValue.coins(rate)}/h"
    }

    private fun missing(): Int {
        val run = needRun() ?: return 0
        val lines = CritterReports.missingLines(run)
        if (lines.isEmpty()) say("Nothing missing.", ChatFormatting.GREEN) else lines.forEach { say(it, ChatFormatting.YELLOW) }
        return 1
    }

    private fun copy(): Int {
        val run = needRun() ?: return 0
        val text = CritterReports.missingLines(run).joinToString("\n").ifEmpty { "Nothing missing." }
        Minecraft.getInstance().keyboardHandler.setClipboard(text)
        say("Copied the missing list.", ChatFormatting.GREEN)
        return 1
    }

    /** Posts the missing list to party chat, one biome per line (queued and spaced out by [CritterSafari.post]). */
    private fun share(): Int {
        val run = needRun() ?: return 0
        val lines = CritterReports.missingLines(run).ifEmpty { listOf("Nothing missing.") }
        lines.forEach { CritterSafari.post(Audience.PARTY, "Missing - $it") }
        say("Posted ${lines.size} line(s) to party chat.", ChatFormatting.GREEN)
        return 1
    }

    private fun players(): Int {
        val run = needRun() ?: return 0
        val lines = CritterReports.playerLines(run)
        if (lines.isEmpty()) say("No catches yet.", ChatFormatting.GRAY) else lines.forEach { say(it) }
        return 1
    }

    private fun prices(): Int {
        ShardPriceFeed.tick(System.currentTimeMillis(), wantedNow = true, force = true)
        say("Refreshing shard prices (${ShardPriceFeed.status(System.currentTimeMillis())}). Run /critters again in a moment.", ChatFormatting.GRAY)
        return 1
    }

    private fun reset(): Int {
        CritterSafari.resetRun()
        say("Run cleared.", ChatFormatting.GREEN)
        return 1
    }

    private fun waypoints(): Int {
        val w = CritterSafariWorld
        val m = CritterMarkersModule.INSTANCE
        say("Markers: ${if (m.isEnabled()) m.collect().size else 0} shown (Critter Markers ${if (m.isEnabled()) "on" else "off"})", ChatFormatting.GRAY)
        say("Snooper walls left ${w.snooper.remaining()}, Troodon ${w.troodon.remaining()}, mounds ${w.moundCount()}, trades ${CritterSafari.trades.all().size}")
        return 1
    }

    private fun debug(): Int {
        say(CritterSafari.summary(), ChatFormatting.GRAY)
        return 1
    }

    private fun testAlert(): Int {
        if (!CritterAlertsModule.INSTANCE.isEnabled()) {
            say("Enable Critter Alerts first.", ChatFormatting.RED)
            return 0
        }
        CritterAlertsModule.INSTANCE.test()
        return 1
    }

    private fun toggle(name: String, flip: () -> Boolean): Int {
        say("$name ${if (flip()) "on" else "off"}", ChatFormatting.GREEN)
        return 1
    }
}
