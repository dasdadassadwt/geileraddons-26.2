package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.gui.HuntingMethodsScreen
import geiler.addons.client.hunting.HuntingMethods
import geiler.addons.client.hunting.ShardPriceFeed
import geiler.addons.client.hunting.ShardPriceHistory
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.SettingGroup
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Best Hunting Method: `/ga hunt` ranks huntable shards by coins per hour from the kill rates in
 * `config/geileraddons/hunting-methods.json` (filled in by the player), Hunting Fortune and live bazaar prices.
 * While enabled it keeps the shard prices fresh and records them per day for the 3-day and 7-day averages.
 */
class HuntingMethodsModule private constructor(
    private val fortune: NumberSetting,
    private val priceMode: ChoiceSetting,
    show: ModuleAction,
    reload: ModuleAction,
) : Module(
    "Hunting Methods",
    "Ranks huntable shards by coins per hour from your kill rates and Hunting Fortune. Open it with /ga hunt or the shard button.",
    Category.HUNTING,
    false,
    fortune, priceMode, show, reload,
) {
    private val diagnostics = ModuleDiagnostics(Category.HUNTING, { name() }, 8, 200L, 400)
    private var methods: List<HuntingMethods.Method>? = null
    private var groups: List<HuntingMethods.Group> = emptyList()
    private var history: ShardPriceHistory? = null
    private var lastRecordedFetch: Map<*, *>? = null
    private var shows = 0

    init {
        group(SettingGroup("Ranking", fortune, priceMode, show, reload))
    }

    override fun onEnable() {
        diagnostics.enable("fortune=${fortune.value()} price=${priceMode.value()}")
    }

    override fun onDisable() {
        PriceDisplayModule.INSTANCE.setHuntingDemand(false)
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val now = System.currentTimeMillis()
        ShardPriceFeed.extraWanted(methods().map { it.shardId })
        ShardPriceFeed.tick(now, true)
        PriceDisplayModule.INSTANCE.setHuntingDemand(true)
        val prices = ShardPriceFeed.prices()
        if (prices.isNotEmpty() && prices !== lastRecordedFetch) {
            lastRecordedFetch = prices
            history().record(prices, now)
            saveHistory()
        }
        diagnostics.status {
            "methods=${methods().size} rated=${methods().count { (it.killsPerHour ?: 0.0) > 0.0 }} prices=${ShardPriceFeed.status(now)} " +
                "historyShards=${history().shards.size} fortune=${fortune.value()} price=${priceMode.value()} shows=$shows suppressedEvents=$it"
        }
    }

    /** Opens the Hunting Methods screen; [fortune] (from `/ga hunt <fortune>`) is saved as the setting first. */
    fun openScreen(fortune: Int?) {
        if (fortune != null) fortune.toFloat().let { this.fortune.setValue(it) }
        ShardPriceFeed.extraWanted(methods().map { it.shardId })
        if (!ShardPriceFeed.hasPrices()) ShardPriceFeed.tick(System.currentTimeMillis(), true, force = true)
        shows++
        diagnostics.event("OPEN fortune=${this.fortune.value()} price=${priceMode.value()} methods=${methods().size}")
        val mc = Minecraft.getInstance()
        mc.execute { mc.gui.setScreen(HuntingMethodsScreen(mc.gui.screen())) }
    }

    /**
     * Shard prices for the table: the Hypixel order-book feed, else the shared Bazaar data (instant sell = sellPrice,
     * sell offer = buyPrice), so the table is never empty while either source works.
     */
    fun shardPrices(): Map<String, geiler.addons.client.hunting.ShardPrice> {
        val feed = ShardPriceFeed.prices()
        val bazaar = PriceDisplayModule.INSTANCE.prices().bazaar ?: return feed
        val out = HashMap(feed)
        for (m in methods()) {
            if (out.containsKey(m.shardId)) continue
            val p = bazaar[m.shardId] ?: continue
            val instant = p.sellPrice ?: 0.0
            val offer = p.buyPrice ?: 0.0
            if (instant > 0.0 || offer > 0.0) out[m.shardId] = geiler.addons.client.hunting.ShardPrice(instant, offer)
        }
        return out
    }

    fun priceStatus(now: Long): String = when {
        ShardPriceFeed.hasPrices() -> ShardPriceFeed.status(now).substringBefore(" products")
        PriceDisplayModule.INSTANCE.prices().bazaar != null -> "bazaar data"
        else -> ShardPriceFeed.status(now).substringBefore(" products")
    }

    fun fortuneSetting(): NumberSetting = fortune
    fun priceSetting(): ChoiceSetting = priceMode
    fun offers(): Boolean = priceMode.value() == "Sell Offer"
    fun methodList(): List<HuntingMethods.Method> = methods()
    fun groupList(): List<HuntingMethods.Group> = methods().let { groups }

    /** 3-day or 7-day average of the price column in use; NaN without data. */
    fun average(shardId: String, days: Int): Double = history().average(shardId, days, System.currentTimeMillis(), offers())

    /** Saves new rates for one shard from the screen; null clears the kill rate. */
    fun setRates(shardId: String, killsPerHour: Double?, shardsPerKill: Double) {
        val list = methods().map {
            if (it.shardId != shardId) it
            else it.copy(killsPerHour = killsPerHour?.takeIf { k -> k > 0.0 }, shardsPerKill = shardsPerKill.takeIf { k -> k > 0.0 } ?: 1.0)
        }
        methods = list
        saveMethods(list)
        diagnostics.event("RATE_EDIT rated=${list.count { (it.killsPerHour ?: 0.0) > 0.0 }}")
    }

    fun note(text: String) = diagnostics.event(text)

    private fun say(text: String) = Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal(text))

    // ---- data file ----

    class SavedMethods {
        /** 2: the table holds only shards the player added (older files listed every critter shard). */
        @JvmField var version: Int = 0
        @JvmField var methods: MutableList<SavedMethod> = ArrayList()
        @JvmField var groups: MutableList<SavedGroup> = ArrayList()
    }

    class SavedGroup {
        @JvmField var id: String = ""
        @JvmField var name: String = ""
        /** Total kills per hour of the whole group; members without their own rate share it evenly. */
        @JvmField var killsPerHour: Double? = null
    }

    class SavedMethod {
        @JvmField var shard: String = ""
        @JvmField var name: String = ""
        @JvmField var mob: String = ""
        @JvmField var location: String = ""
        @JvmField var killsPerHour: Double? = null
        @JvmField var shardsPerKill: Double = 1.0
        @JvmField var group: String? = null
    }

    private fun methods(): List<HuntingMethods.Method> = methods ?: loadMethods().also { methods = it }

    /** Reads the data file; writes the default table there first when it does not exist yet. */
    private fun loadMethods(): List<HuntingMethods.Method> {
        groups = emptyList()
        val path = dataPath() ?: return HuntingMethods.defaults()
        if (!Files.exists(path)) {
            val defaults = HuntingMethods.defaults()
            saveMethods(defaults)
            diagnostics.event("DATA_FILE created=true entries=${defaults.size}")
            return defaults
        }
        val read = ClientJsonFile.readResult(path, SavedMethods::class.java)
        if (read.valid() && (read.value?.version ?: 0) < DATA_VERSION) {
            // The old default table (every critter shard) is dropped once; shards are added from the picker now.
            saveMethods(emptyList())
            diagnostics.event("DATA_FILE migrated=true dropped=${read.value?.methods?.size ?: 0}")
            return emptyList()
        }
        val list = read.value?.methods.orEmpty().filter { it.shard.isNotBlank() }.take(MAX_METHODS).map {
            HuntingMethods.Method(it.shard.trim(), it.name.ifBlank { it.shard }, it.mob, it.location, it.killsPerHour, it.shardsPerKill,
                it.group?.takeIf { g -> g.isNotBlank() })
        }
        groups = read.value?.groups.orEmpty().filter { it.id.isNotBlank() }.take(MAX_METHODS)
            .map { HuntingMethods.Group(it.id, it.name.ifBlank { "Group" }, it.killsPerHour?.takeIf { k -> k > 0.0 }) }
        diagnostics.event("DATA_FILE loaded=${read.valid()} entries=${list.size} groups=${groups.size} error=${read.error}")
        return list
    }

    /** Writes the table with null rates kept, so empty `killsPerHour` fields stay visible for editing. */
    private fun saveMethods(list: List<HuntingMethods.Method>) {
        val path = dataPath() ?: return
        val saved = SavedMethods()
        saved.version = DATA_VERSION
        list.forEach { m ->
            saved.methods.add(SavedMethod().also {
                it.shard = m.shardId; it.name = m.name; it.mob = m.mob; it.location = m.location
                it.killsPerHour = m.killsPerHour; it.shardsPerKill = m.shardsPerKill; it.group = m.group
            })
        }
        groups.filter { g -> list.any { it.group == g.id } }.forEach { g ->
            saved.groups.add(SavedGroup().also { it.id = g.id; it.name = g.name; it.killsPerHour = g.killsPerHour })
        }
        ClientJsonFile.writeAsync(path, com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(saved))
    }

    /** Adds a shard picked on the screen (no rate yet). */
    fun addMethod(shardId: String, name: String) {
        val list = HuntingMethods.add(methods(), shardId, name).take(MAX_METHODS)
        if (list.size == methods().size) return
        methods = list
        saveMethods(list)
        diagnostics.event("ADD entries=${list.size}")
    }

    /** Removes a shard from the table (right click on its row). */
    fun removeMethod(shardId: String) {
        val list = HuntingMethods.remove(methods(), shardId)
        commit(list, "REMOVE entries=${list.size}")
    }

    /** Puts [shardIds] (two or more) into one new group named after their mobs; they leave any group they were in. */
    fun groupMethods(shardIds: Collection<String>) {
        val members = methods().filter { it.shardId in shardIds }
        if (members.size < 2) return
        val id = "g" + System.currentTimeMillis().toString(36)
        val name = members.joinToString(" + ") { (it.mob.ifBlank { it.name }).removeSuffix(" Shard") }
        groups = groups + HuntingMethods.Group(id, name.take(64), null)
        commit(methods().map { if (it.shardId in shardIds) it.copy(group = id) else it }, "GROUP members=${members.size}")
    }

    /** Dissolves group [groupId]; its members keep their own rates. */
    fun ungroup(groupId: String) {
        groups = groups.filter { it.id != groupId }
        commit(methods().map { if (it.group == groupId) it.copy(group = null) else it }, "UNGROUP")
    }

    /** Sets a group's total kills per hour; null clears it. */
    fun setGroupRate(groupId: String, killsPerHour: Double?) {
        groups = groups.map { if (it.id == groupId) it.copy(killsPerHour = killsPerHour?.takeIf { k -> k > 0.0 }) else it }
        commit(methods(), "GROUP_RATE set=${killsPerHour != null}")
    }

    /** Stores [list], drops groups left with fewer than two members, and saves. */
    private fun commit(list: List<HuntingMethods.Method>, event: String) {
        val alive = groups.filter { g -> list.count { it.group == g.id } >= 2 }.map { it.id }.toSet()
        groups = groups.filter { it.id in alive }
        val cleaned = list.map { if (it.group != null && it.group !in alive) it.copy(group = null) else it }
        methods = cleaned
        saveMethods(cleaned)
        diagnostics.event("$event groups=${groups.size}")
    }

    fun reloadMethods() {
        methods = null
        say("§6[Hunt] §7Reloaded ${methods().size} shard method(s).")
    }

    private fun history(): ShardPriceHistory = history
        ?: (historyPath()?.let { ClientJsonFile.read(it, ShardPriceHistory::class.java) } ?: ShardPriceHistory()).also { history = it }

    private fun saveHistory() {
        val path = historyPath() ?: return
        ClientJsonFile.writeAsync(path, ClientJsonFile.GSON.toJson(history()))
    }

    private fun configDir(): Path? = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")
    } catch (_: NullPointerException) {
        null
    }

    private fun dataPath(): Path? = configDir()?.resolve("hunting-methods.json")
    private fun historyPath(): Path? = configDir()?.resolve("shard-price-history.json")

    companion object {
        private const val MAX_ROWS = 15
        private const val MAX_METHODS = 400
        private const val DATA_VERSION = 2

        @JvmField
        val INSTANCE: HuntingMethodsModule = HuntingMethodsModule(
            NumberSetting("Hunting Fortune", 0f, 1000f, 0f, true),
            ChoiceSetting("Price", "Sell Offer", "Sell Offer", "Insta-Sell"),
            ModuleAction("Open Hunting Methods", "Open the ranking screen (same as /ga hunt or the shard button in the top bar).", Runnable { INSTANCE.openScreen(null) }),
            ModuleAction("Reload Data File", "Read hunting-methods.json again after editing it.", Runnable { INSTANCE.reloadMethods() }),
        )
    }
}
