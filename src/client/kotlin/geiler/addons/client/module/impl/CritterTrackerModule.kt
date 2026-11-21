package geiler.addons.client.module.impl

import geiler.addons.client.hud.HudElement
import geiler.addons.client.hunting.BiomeStyle
import geiler.addons.client.hunting.CritterReports
import geiler.addons.client.hunting.CritterSpecies
import geiler.addons.client.hunting.PriceMode
import geiler.addons.client.hunting.SafariRun
import geiler.addons.client.hunting.ShardPriceFeed
import geiler.addons.client.hunting.ShardValue
import geiler.addons.client.location.Island
import geiler.addons.client.location.SafariBiome
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/** One line of a Critter HUD panel. */
class CritterHudLine(val text: String, val argb: Int)

/**
 * Tracks a Critter Safari run from chat and shows it on movable HUD panels: dex progress for the party and for you,
 * a bar per biome, what is still missing, the Hunter trades found, and what the run is worth - time per run, coins
 * per run and coins per hour at live bazaar prices. The tracking itself lives in [CritterSafari].
 * Behaviour follows Critter Mod (MrCloudy2, MIT); the code is written independently.
 */
class CritterTrackerModule private constructor(private val settings: Settings) : Module(
    "Critter Tracker",
    "Tracks a Critter Safari run: dex progress, what is missing, Hunter trades, and shard value per run and per hour.",
    Category.HUNTING,
    settings.visibility, settings.progressPanel, settings.missingPanel, settings.tradePanel, settings.showHotspot,
    settings.showCounts, settings.showNearby, settings.nearbyThroughWalls, settings.uniqueOnly, settings.shardValue, settings.priceMode,
    settings.textColor,
) {
    override fun isActive(): Boolean = isEnabled() && CritterSafari.inSafari()

    override fun inactiveReason(): String? = if (isEnabled() && !isActive()) "Not in the Critter Safari" else null

    fun uniqueOnly(): Boolean = settings.uniqueOnly.value()
    fun shardValueOn(): Boolean = settings.shardValue.value()
    fun countsOn(): Boolean = settings.showCounts.value()
    fun nearbyOn(): Boolean = settings.showNearby.value() && isEnabled()
    fun nearbyThroughWalls(): Boolean = settings.nearbyThroughWalls.value()

    /** Flips the Progress Panel setting and returns its new value (the `/critters panel` quick toggle). */
    fun toggleProgressPanel(): Boolean {
        settings.progressPanel.toggle()
        return settings.progressPanel.value()
    }

    fun priceMode(): PriceMode = when (settings.priceMode.value()) {
        "Sell Offer" -> PriceMode.SELL_OFFER
        "Both" -> PriceMode.BOTH
        else -> PriceMode.INSTANT_SELL
    }

    /** Whether the panels belong on screen right now, given the Visibility setting. */
    fun showsHere(): Boolean = settings.visibility.value() == "Everywhere" || CritterSafari.inSafari()

    fun islands(): Set<Island> = if (settings.visibility.value() == "Everywhere") emptySet() else setOf(Island.SAFARI)

    // ---- value ----

    /** Coins for the run's shards at one price column; the bool is false while no price data has arrived. */
    fun value(run: SafariRun, offers: Boolean): Pair<Double, Boolean> =
        ShardValue.value(run.shardCounts(), ShardPriceFeed.prices(), offers) to ShardPriceFeed.hasPrices()

    /** `Value: 1.23M` or `Value: 1.2M / 1.5M` (instant / offers), plus coins per hour. */
    private fun valueLines(run: SafariRun, now: Long): List<CritterHudLine> {
        if (!settings.shardValue.value()) return emptyList()
        val color = settings.textColor.argb()
        val elapsed = run.elapsedMillis(now)
        val (instant, have) = value(run, false)
        val (offers, _) = value(run, true)
        val mode = priceMode()
        val shown = when (mode) {
            PriceMode.INSTANT_SELL -> CritterReports.valueText(instant, have)
            PriceMode.SELL_OFFER -> CritterReports.valueText(offers, have)
            PriceMode.BOTH -> "${CritterReports.valueText(instant, have)} / ${CritterReports.valueText(offers, have)}"
        }
        val perHour = when (mode) {
            PriceMode.SELL_OFFER -> ShardValue.perHour(offers, elapsed)
            else -> ShardValue.perHour(instant, elapsed)
        }
        val rate = if (!have) "-" else if (perHour > 0.0) "${ShardValue.coins(perHour)}/h" else "-"
        return listOf(CritterHudLine("Value: $shown  Per hour: $rate", color))
    }

    // ---- panels ----

    fun progressLines(): List<CritterHudLine> {
        val run = CritterSafari.currentOrLast() ?: return emptyList()
        val now = System.currentTimeMillis()
        val color = settings.textColor.argb()
        val live = CritterSafari.run === run
        val lines = ArrayList<CritterHudLine>()
        lines.add(CritterHudLine("${if (live) "Run" else "Last run"}: ${ShardValue.duration(run.elapsedMillis(now))}", color))
        lines.add(CritterHudLine("Party ${run.partyUnique()}/${CritterSpecies.count()}  You ${run.ownUnique()}/${CritterSpecies.count()}", color))
        for (biome in SafariBiome.values()) {
            val total = CritterSpecies.inBiome(biome).size
            lines.add(CritterHudLine("${bar(run.partyUnique(biome), total)} ${biome.displayName()} ${run.partyUnique(biome)}/$total", BiomeStyle.argb(biome)))
        }
        lines.addAll(valueLines(run, now))
        if (live && settings.showHotspot.value()) {
            CritterSafari.hotspot?.let { lines.add(CritterHudLine("Hotspot: ${it.displayName()}", BiomeStyle.argb(it))) }
        }
        if (live && settings.showCounts.value()) {
            val w = CritterSafariWorld
            lines.add(CritterHudLine("Walls left: Snooper ${w.snooper.remaining()}/${w.snooper.pos.size}, Troodon ${w.troodon.remaining()}/${w.troodon.pos.size}", color))
            lines.add(CritterHudLine("Mounds: ${w.moundCount()} known, ${CritterSafari.moundsBroken} broken (${CritterSafari.moundRockmites} Rockmite)", color))
        }
        if (live && settings.showNearby.value()) {
            val nearby = CritterSpecies.all().filter { run.nearby(it) > 0 }
            if (nearby.isNotEmpty()) lines.add(CritterHudLine("Nearby: " + nearby.joinToString(", ") { "${it.name} ${run.nearby(it)}" }, color))
        }
        return lines
    }

    fun missingLines(): List<CritterHudLine> {
        val run = CritterSafari.run ?: return emptyList()
        val biome = SafariBiome.current() ?: return emptyList()
        val missing = run.missing(biome)
        val head = CritterHudLine("Missing in ${biome.displayName()} (${missing.size})", BiomeStyle.argb(biome))
        if (missing.isEmpty()) return listOf(CritterHudLine("${biome.displayName()} done", BiomeStyle.argb(biome)))
        return listOf(head) + missing.map { CritterHudLine(CritterReports.missingName(run, it), it.rarity.argb) }
    }

    fun tradeLines(): List<CritterHudLine> {
        if (CritterSafari.run == null) return emptyList()
        val player = Minecraft.getInstance().player ?: return emptyList()
        val found = CritterSafari.trades.nearestFirst(player.x, player.y, player.z)
        if (found.isEmpty()) return emptyList()
        val lines = ArrayList<CritterHudLine>()
        lines.add(CritterHudLine("Hunter trades", settings.textColor.argb()))
        for (trade in found) {
            val where = trade.spot?.let { s ->
                val d = Math.sqrt(trade.distanceSqr(player.x, player.y, player.z)).toInt()
                " (${s.biome?.displayName() ?: "?"}, ${d}m)"
            } ?: ""
            lines.add(CritterHudLine("${trade.offer.npc}$where: ${trade.offer.critter.name} Shard for ${trade.offer.item}", trade.offer.critter.rarity.argb))
        }
        return lines
    }

    private fun bar(done: Int, total: Int): String {
        val cells = 8
        val filled = if (total <= 0) 0 else (done * cells + total - 1) / total
        return "[" + "#".repeat(filled.coerceIn(0, cells)) + "-".repeat((cells - filled).coerceIn(0, cells)) + "]"
    }

    // ---- diagnostics ----

    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 100L, 640)
    private var lastRunOpen = false

    override fun onEnable() {
        lastRunOpen = false
        diagnostics.enable(
            "visibility=${settings.visibility.value()} value=${settings.shardValue.value()} mode=${settings.priceMode.value()} " +
                "uniqueOnly=${settings.uniqueOnly.value()} counts=${settings.showCounts.value()} nearby=${settings.showNearby.value()} throughWalls=${settings.nearbyThroughWalls.value()}",
        )
    }

    override fun onDisable() = diagnostics.disable()

    /** Writes bounded STATUS/EVENT lines; call every client tick while enabled. Counts and states only, no names or coordinates. */
    fun diagnose() {
        if (!isEnabled()) return
        diagnostics.advance()
        val open = CritterSafari.run != null
        if (open != lastRunOpen) {
            lastRunOpen = open
            diagnostics.event(if (open) "RUN_OPEN ${CritterSafari.summary()}" else "RUN_CLOSE ${CritterSafari.lastRun?.let { CritterReports.summaryLine(it, it.elapsedMillis(System.currentTimeMillis())) } ?: "empty"}")
        }
        diagnostics.statusOnChangeBy(20L, { "$open${CritterSafari.run?.partyTotal()}${CritterSafari.trades.all().size}" }) {
            CritterSafari.summary() + " panels=${panelState()}"
        }
    }

    private fun panelState(): String =
        "p${progressLines().size}m${missingLines().size}t${tradeLines().size}"

    class Settings {
        val visibility = ChoiceSetting("Visibility", "Safari Only", "Safari Only", "Everywhere")
        val progressPanel = BooleanSetting("Progress Panel", true)
        val missingPanel = BooleanSetting("Missing Panel", true)
        val tradePanel = BooleanSetting("Hunter Trade Panel", true)
        val showHotspot = BooleanSetting("Show Hotspot", true)
        val showCounts = BooleanSetting("Show Wall And Mound Counts", true)
        val showNearby = BooleanSetting("Show Nearby Counts", false)
        val nearbyThroughWalls = BooleanSetting("Count Through Walls", false)
        val uniqueOnly = BooleanSetting("Unique Catches Only", false)
        val shardValue = BooleanSetting("Track Shard Value", true)
        val priceMode = ChoiceSetting("Price Mode", "Instant Sell", "Instant Sell", "Sell Offer", "Both")
        val textColor = ColorSetting("Text Color", 255, 255, 255)
    }

    /** A movable panel fed by one of this module's line builders. */
    inner class Panel(
        private val panelId: String,
        private val title: String,
        private val wanted: () -> Boolean,
        private val content: () -> List<CritterHudLine>,
        private val sample: List<CritterHudLine>,
    ) : HudElement {
        override fun id(): String = panelId
        override fun displayName(): String = title
        override fun module(): Module = this@CritterTrackerModule
        override fun hudIslands(): Set<Island> = islands()
        override fun visible(): Boolean = isEnabled() && wanted() && showsHere() && content().isNotEmpty()
        override fun width(font: Font?): Int = widthOf(font!!, content())
        override fun height(font: Font?): Int = heightOf(font!!, content())
        override fun previewWidth(font: Font?): Int = widthOf(font!!, sample)
        override fun previewHeight(font: Font?): Int = heightOf(font!!, sample)
        override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, content())
        override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) = draw(graphics, font, x, y, sample)

        private fun widthOf(font: Font, lines: List<CritterHudLine>): Int = lines.maxOfOrNull { font.width(it.text) } ?: 0
        private fun heightOf(font: Font, lines: List<CritterHudLine>): Int = lines.size * (font.lineHeight + 1)
        private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, lines: List<CritterHudLine>) {
            var row = y
            for (line in lines) {
                graphics.text(font, line.text, x, row, line.argb)
                row += font.lineHeight + 1
            }
        }
    }

    val progressHud = Panel(
        "critter_progress", "Critter Progress", { settings.progressPanel.value() }, ::progressLines,
        listOf(
            CritterHudLine("Run: 12m 05s", 0xFFFFFFFF.toInt()),
            CritterHudLine("Party 23/37  You 15/37", 0xFFFFFFFF.toInt()),
            CritterHudLine("[######--] Forest 7/9", BiomeStyle.argb(SafariBiome.FOREST)),
            CritterHudLine("[####----] Cavern 4/9", BiomeStyle.argb(SafariBiome.CAVERN)),
            CritterHudLine("Value: 1.23M  Per hour: 6.1M/h", 0xFFFFFFFF.toInt()),
        ),
    )
    val missingHud = Panel(
        "critter_missing", "Critter Missing", { settings.missingPanel.value() }, ::missingLines,
        listOf(
            CritterHudLine("Missing in Cavern (2)", BiomeStyle.argb(SafariBiome.CAVERN)),
            CritterHudLine("Snoozle", 0xFF5555FF.toInt()),
            CritterHudLine("Gemzie x2", 0xFFAA00AA.toInt()),
        ),
    )
    val tradesHud = Panel(
        "critter_trades", "Critter Hunter Trades", { settings.tradePanel.value() }, ::tradeLines,
        listOf(
            CritterHudLine("Hunter trades", 0xFFFFFFFF.toInt()),
            CritterHudLine("Hunter Dennis (Icy, 42m): Gimmiegold Shard for Purple Gem", 0xFF5555FF.toInt()),
        ),
    )

    companion object {
        @JvmField
        val INSTANCE = CritterTrackerModule(Settings())
    }
}
