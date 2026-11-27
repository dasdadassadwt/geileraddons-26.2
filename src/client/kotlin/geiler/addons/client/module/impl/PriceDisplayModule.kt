package geiler.addons.client.module.impl

import geiler.addons.GeilerAddons
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.net.BoundedHttpBodyReader
import geiler.addons.client.pricing.NetworthSupport
import geiler.addons.client.pricing.NeuRecipes
import geiler.addons.client.pricing.PriceLines
import geiler.addons.client.pricing.PriceItem
import geiler.addons.client.pricing.PriceOptions
import geiler.addons.client.pricing.PriceParsing
import geiler.addons.client.pricing.PriceSnapshot
import geiler.addons.client.pricing.Recipe
import geiler.addons.client.pricing.SkyblockItemIds
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Price lines in item tooltips: NPC, George, Bazaar, Lowest BIN, average BIN and Motes prices (Skyblocker's Item
 * Tooltip, LGPL-3.0). Settings, defaults, line wording and the hysky.de data sets follow Skyblocker; the code is
 * written independently. Crafting Cost reads recipes from the NotEnoughUpdates item repository. Estimated Item Value
 * is not part of this module yet.
 */
class PriceDisplayModule private constructor(private val s: Settings) : Module(
    "Price Display",
    "Shows NPC, George, Motes, Bazaar and auction house prices in item tooltips.",
    Category.VISUAL,
    false,
    s.npcPrice, s.georgePrice, s.motesPrice, s.averageBin, s.averageType, s.lowestBin, s.bazaarPrice,
    s.craftingCost, s.essenceCost, s.estimatedValue, s.refresh,
) {
    private enum class Dataset(val url: String, val cacheable: Boolean) {
        NPC("https://hysky.de/api/npcprice", true),
        BAZAAR("https://hysky.de/api/bazaar", false),
        LOWEST_BIN("https://hysky.de/api/auctions/lowestbins", false),
        ONE_DAY("https://hysky.de/api/auctions/lowestbins/average?days=1", false),
        THREE_DAY("https://hysky.de/api/auctions/lowestbins/average?days=3", false),
        MOTES("https://hysky.de/api/motesprice", true),
        GEORGE("https://hysky.de/api/georgeprices", true),
        ITEMS(NetworthSupport.ITEMS_URL, true),
        SHARDS("https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/attribute_shards.json", true),
    }

    @Volatile
    private var data = PriceSnapshot()

    /** Attribute shard display name -> Bazaar id; the Bazaar lists each shard under its own id, not `ATTRIBUTE_SHARD`. */
    @Volatile
    private var shardNames: Map<String, String> = emptyMap()
    private var shardsResolved = 0L
    private var shardsUnresolved = 0L

    @Volatile
    private var recipes: Map<String, Recipe> = emptyMap()

    /** Set by the Item List while it is enabled: it needs Bazaar, Lowest BIN and NPC prices even with this module off. */
    @Volatile
    private var itemListDemand = false

    private var calculator: NeuRecipes.Calculator? = null
    private var calculatorMode: String? = null
    private var recipeStatus = "not loaded"
    private val fetching = HashSet<Dataset>()
    private val loadedAt = HashMap<Dataset, Long>()
    private val status = HashMap<Dataset, String>()
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "geileraddons-price-display").also { it.isDaemon = true }
    }
    private var ticksSinceRefresh = REFRESH_TICKS
    private var linesAdded = 0L
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 320)

    override fun isSettingVisible(setting: Setting): Boolean = setting !== s.averageType || s.averageBin.value()

    override fun onEnable() {
        ticksSinceRefresh = REFRESH_TICKS
        diagnostics.enable(
            "npc=${s.npcPrice.value()} george=${s.georgePrice.value()} motes=${s.motesPrice.value()} " +
                "avg=${s.averageBin.value()}/${s.averageType.value()} lbin=${s.lowestBin.value()} bz=${s.bazaarPrice.value()}",
        )
    }

    override fun onDisable() {
        diagnostics.disable()
    }

    private fun onSkyblock(): Boolean {
        val island = HypixelModApi.currentIsland()
        return island != Island.NONE && island != Island.OTHER
    }

    /** The price data downloaded so far (empty maps until a data set arrives). */
    fun prices(): PriceSnapshot = data

    fun setItemListDemand(on: Boolean) {
        itemListDemand = on
    }

    /** Set by the Visitor Shopping List while it is enabled: it prices what is short from the Bazaar, even with this module off. */
    @Volatile
    private var bazaarDemand = false

    fun setBazaarDemand(on: Boolean) {
        bazaarDemand = on
    }

    /** Set by the Collection Tracker while a tracker shows profit: it values items from the Bazaar and lowest BIN. */
    @Volatile
    private var collectionDemand = false

    fun setCollectionDemand(on: Boolean) {
        collectionDemand = on
    }

    /** Set by Croesus while its price source is live: it values chest loot from the Bazaar and lowest BIN. */
    @Volatile
    private var croesusDemand = false

    fun setCroesusDemand(on: Boolean) {
        if (on && !croesusDemand) ticksSinceRefresh = REFRESH_TICKS
        croesusDemand = on
    }

    /** Set by Hunting Methods while it is enabled: shard prices fall back to this Bazaar data. */
    @Volatile
    private var huntingDemand = false

    fun setHuntingDemand(on: Boolean) {
        if (on && !huntingDemand) ticksSinceRefresh = REFRESH_TICKS
        huntingDemand = on
    }

    /** Milliseconds since the Bazaar data last arrived, or null before it has. */
    @Synchronized
    fun bazaarAgeMillis(): Long? = loadedAt[Dataset.BAZAAR]?.let { System.currentTimeMillis() - it }

    /** Milliseconds since the lowest BIN data last arrived, or null before it has. */
    @Synchronized
    fun lowestBinAgeMillis(): Long? = loadedAt[Dataset.LOWEST_BIN]?.let { System.currentTimeMillis() - it }

    private fun wanted(dataset: Dataset): Boolean = when (dataset) {
        Dataset.NPC -> s.npcPrice.value() || itemListDemand
        Dataset.BAZAAR -> s.bazaarPrice.value() || itemListDemand || bazaarDemand || huntingDemand || collectionDemand || croesusDemand || craftingOn() || s.essenceCost.value() || s.estimatedValue.value()
        Dataset.LOWEST_BIN -> s.lowestBin.value() || itemListDemand || collectionDemand || croesusDemand || craftingOn() || s.estimatedValue.value()
        Dataset.ONE_DAY -> s.averageBin.value() && s.averageType.value() != "THREE_DAY"
        Dataset.THREE_DAY -> (s.averageBin.value() && s.averageType.value() != "ONE_DAY") || s.estimatedValue.value()
        Dataset.MOTES -> s.motesPrice.value() && HypixelModApi.currentIsland() == Island.THE_RIFT
        Dataset.GEORGE -> s.georgePrice.value()
        Dataset.ITEMS -> s.estimatedValue.value()
        Dataset.SHARDS -> s.bazaarPrice.value() || s.estimatedValue.value()
    }

    private fun craftingOn() = s.craftingCost.value() != "OFF"

    fun tick() {
        val enabled = isEnabled()
        if (!enabled && !itemListDemand && !bazaarDemand && !collectionDemand && !croesusDemand && !huntingDemand) return
        if (enabled) diagnostics.advance()
        if (!onSkyblock()) return
        if (++ticksSinceRefresh >= REFRESH_TICKS) {
            ticksSinceRefresh = 0
            calculator = null
            refresh(force = false)
        }
        if (craftingOn() && recipes.isEmpty()) {
            ItemListRepository.craftTable()?.let { recipes = it; recipeStatus = "ok shared ${it.size}" } ?: ItemListRepository.request()
        }
        diagnostics.status {
            "island=${HypixelModApi.currentIsland()} loaded=${status.entries.joinToString(",") { "${it.key}:${it.value}" }} " +
                "recipes=${recipes.size}($recipeStatus) shards=${shardNames.size} shardsResolved=$shardsResolved " +
                "shardsUnresolved=$shardsUnresolved linesAdded=$linesAdded suppressedEvents=$it"
        }
    }

    /** Starts a download for every wanted data set that is stale; [force] also refreshes the cacheable ones. */
    @Synchronized
    private fun refresh(force: Boolean) {
        val now = System.currentTimeMillis()
        for (dataset in Dataset.entries) {
            if (!wanted(dataset) || dataset in fetching) continue
            val age = now - (loadedAt[dataset] ?: 0L)
            if (!force && dataset.cacheable && loadedAt.containsKey(dataset) && age < CACHEABLE_MILLIS) continue
            fetching.add(dataset)
            executor.execute { download(dataset) }
        }
    }

    private fun download(dataset: Dataset) {
        var outcome: String
        try {
            val request = HttpRequest.newBuilder(URI.create(dataset.url))
                .timeout(Duration.ofSeconds(20)).header("User-Agent", GeilerAddons.MOD_ID).GET().build()
            val response = BoundedHttpBodyReader.send(client, request, MAX_BYTES, Duration.ofSeconds(25))
            outcome = if (response.statusCode() != 200) {
                "HTTP ${response.statusCode()}"
            } else {
                apply(dataset, String(response.body(), StandardCharsets.UTF_8))
            }
        } catch (exception: Exception) {
            outcome = "failed ${exception.javaClass.simpleName}"
        }
        synchronized(this) {
            fetching.remove(dataset)
            status[dataset] = outcome
            if (outcome.startsWith("ok")) loadedAt[dataset] = System.currentTimeMillis()
        }
        Minecraft.getInstance().execute { diagnostics.event("DOWNLOAD ${dataset.name} $outcome") }
    }

    @Synchronized
    private fun apply(dataset: Dataset, body: String): String {
        val current = data
        fun doubles() = PriceParsing.doubleMap(body)
        return when (dataset) {
            Dataset.NPC -> doubles()?.let { data = copy(current, npc = it); "ok ${it.size}" }
            Dataset.GEORGE -> doubles()?.let { data = copy(current, george = it); "ok ${it.size}" }
            Dataset.LOWEST_BIN -> doubles()?.let { data = copy(current, lowestBin = it); "ok ${it.size}" }
            Dataset.ONE_DAY -> doubles()?.let { data = copy(current, oneDay = it); "ok ${it.size}" }
            Dataset.THREE_DAY -> doubles()?.let { data = copy(current, threeDay = it); "ok ${it.size}" }
            Dataset.MOTES -> PriceParsing.intMap(body)?.let { data = copy(current, motes = it); "ok ${it.size}" }
            Dataset.BAZAAR -> PriceParsing.bazaar(body)?.let { data = copy(current, bazaar = it); "ok ${it.size}" }
            Dataset.ITEMS -> NetworthSupport.load(body)?.let { "ok $it" }
            Dataset.SHARDS -> PriceParsing.shardNames(body)?.takeIf { it.isNotEmpty() }?.let { shardNames = it; "ok ${it.size}" }
        } ?: "invalid data"
    }

    private fun copy(
        c: PriceSnapshot,
        npc: Map<String, Double>? = c.npc,
        george: Map<String, Double>? = c.george,
        motes: Map<String, Int>? = c.motes,
        bazaar: Map<String, geiler.addons.client.pricing.BazaarProduct>? = c.bazaar,
        lowestBin: Map<String, Double>? = c.lowestBin,
        oneDay: Map<String, Double>? = c.oneDayAverage,
        threeDay: Map<String, Double>? = c.threeDayAverage,
    ) = PriceSnapshot(npc, george, motes, bazaar, lowestBin, oneDay, threeDay)

    /** Appends the price lines for [stack] to a tooltip being built. */
    fun addToTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || !onSkyblock() || lines.isEmpty()) return
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return
        val id = SkyblockItemIds.id(tag)
        if (id.isEmpty()) return
        val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList()
        val count = maxOf(PriceLines.displayedCount(geiler.addons.client.utility.SkyblockItems.realName(stack), lore, lines.first().string) ?: stack.count, 1)
        val options = PriceOptions(
            s.npcPrice.value(), s.georgePrice.value(), s.motesPrice.value(), s.averageBin.value(),
            s.averageType.value(), s.lowestBin.value(), s.bazaarPrice.value(),
        )
        var apiId = SkyblockItemIds.apiId(tag)
        if (id == "ATTRIBUTE_SHARD") {
            val shard = SkyblockItemIds.shardApiId(geiler.addons.client.utility.SkyblockItems.realName(stack), shardNames)
            if (shard != null) {
                apiId = shard
                shardsResolved++
            } else shardsUnresolved++
        }
        val built = ArrayList(PriceLines.build(data, options, PriceItem(id, apiId, count, SkyblockItemIds.pet(tag))))
        val extras = ArrayList<String>()
        if (s.essenceCost.value() && inEssenceShop()) {
            val sell = HashMap<String, Long>()
            for (type in PriceLines.ESSENCE_TYPES) {
                data.bazaar?.get("ESSENCE_$type")?.sellPrice?.let { sell[type] = it.toLong() }
            }
            PriceLines.essenceLine(lines.joinToString(" ") { it.string }, sell)?.let { extras.add(it) }
        }
        if (craftingOn()) craftCost(SkyblockItemIds.neuName(tag))?.let { cost -> PriceLines.craftLine(cost, count)?.let { extras.add(it) } }
        if (s.estimatedValue.value() && data.bazaar?.containsKey(apiId) != true) {
            val snapshot = data
            val value = NetworthSupport.estimate(tag, count) { NetworthSupport.priceOf(snapshot, it) }
            if (value > 0) extras.add("§6Est. Item Value: §r" + PriceLines.coins(value, count, true))
        }
        if (extras.isNotEmpty()) {
            val at = built.indexOfFirst { it.startsWith("§dMotes") }.let { if (it < 0) built.size else it }
            built.addAll(at, extras)
        }
        if (built.isEmpty()) return
        linesAdded += built.size
        for (text in built) lines.add(Component.literal(text))
    }

    private fun inEssenceShop(): Boolean =
        Minecraft.getInstance().gui.screen()?.title?.string?.let { ESSENCE_SHOP.matches(it) } == true

    /** The crafting cost of one item, or null while data is missing or there is no recipe. */
    private fun craftCost(neuId: String): Double? {
        val bazaar = data.bazaar ?: return null
        val lowestBin = data.lowestBin ?: return null
        if (recipes.isEmpty()) return null
        val mode = s.craftingCost.value()
        var calc = calculator
        if (calc == null || calculatorMode != mode) {
            calc = NeuRecipes.Calculator(recipes, bazaar, lowestBin, mode == "BUY_ORDER")
            calculator = calc
            calculatorMode = mode
        }
        return calc.itemCost(neuId).takeIf { it > 0 }
    }

    class Settings {
        val npcPrice = BooleanSetting("Enable NPC Price", true)
        val georgePrice = BooleanSetting("Enable George Pet Sell Price", true)
        val motesPrice = BooleanSetting("Enable Motes Price", true)
        val averageBin = BooleanSetting("Enable Avg. BIN Price", true)
        val averageType = ChoiceSetting(
            "Average Type", "THREE_DAY",
            mapOf("ONE_DAY" to "1-day price", "THREE_DAY" to "3-day price", "BOTH" to "Both"),
            "ONE_DAY", "THREE_DAY", "BOTH",
        )
        val lowestBin = BooleanSetting("Enable Lowest BIN Price", true)
        val bazaarPrice = BooleanSetting("Enable Bazaar buy/sell Price", true)
        val craftingCost = ChoiceSetting(
            "Crafting Cost", "OFF",
            mapOf("SELL_ORDER" to "Buy Order", "BUY_ORDER" to "Buy Instantly", "OFF" to "Off"),
            "SELL_ORDER", "BUY_ORDER", "OFF",
        )
        val essenceCost = BooleanSetting("Show Essence Cost", true)
        val estimatedValue = BooleanSetting("Enable Estimated Item Value", true)
        val refresh = ModuleAction("Refresh Prices", "Downloads the price data again.", Runnable { INSTANCE.refresh(true) })
    }

    companion object {
        private const val REFRESH_TICKS = 1200
        private const val CACHEABLE_MILLIS = 3_600_000L
        private const val MAX_BYTES = 16_000_000
        private val ESSENCE_SHOP = Regex("\\S+ Essence Shop")

        @JvmField
        val INSTANCE = PriceDisplayModule(Settings())
    }
}
