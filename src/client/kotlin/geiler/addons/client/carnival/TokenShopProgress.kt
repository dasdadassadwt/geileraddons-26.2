package geiler.addons.client.carnival

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Event token shop upgrade costs, from the NEU repository's `constants/carnivalshops.json` (the same data SkyHanni's
 * `CarnivalShopHelper` reads), and the remaining-cost arithmetic. The file is fetched once and cached on disk.
 */
object TokenShopProgress {
    class Upgrade(val name: String, val costs: List<Int>)
    class Shop(val name: String, val upgrades: List<Upgrade>)
    class UpgradeStatus(val name: String, val currentLevel: Int, val maxLevel: Int, val remainingCosts: List<Int>)
    class Progress(val upgrades: List<UpgradeStatus>) {
        val remainingSum: Int get() = upgrades.sumOf { it.remainingCosts.sum() }
    }

    private const val URL = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/carnivalshops.json"
    private const val MAX_BYTES = 262_144
    private const val RETRY_MS = 10 * 60_000L

    @Volatile private var shops: List<Shop> = emptyList()
    @Volatile private var fetching = false
    @Volatile private var lastAttempt = 0L

    @JvmStatic
    fun shops(): List<Shop> = shops

    /** Parses `carnivalTokenShops`; shop keys use underscores for spaces ("Fishing_Festival"). */
    @JvmStatic
    fun parse(json: String): List<Shop> {
        val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return emptyList() }
        val table = root.getAsJsonObject("carnivalTokenShops") ?: return emptyList()
        return table.entrySet().map { (key, value) ->
            Shop(key.replace('_', ' '), value.asJsonObject.entrySet().map { (_, upgrade) ->
                val obj = upgrade.asJsonObject
                Upgrade(obj.get("name").asString, obj.getAsJsonArray("costs").map { it.asInt })
            })
        }
    }

    @JvmStatic
    fun progress(shop: Shop, purchased: Map<String, Int>?): Progress = Progress(shop.upgrades.map {
        val bought = (purchased?.entries?.firstOrNull { e -> e.key.equals(it.name, true) }?.value ?: 0).coerceIn(0, it.costs.size)
        UpgradeStatus(it.name, bought, it.costs.size, it.costs.drop(bought))
    })

    /** Loads the cached copy, or fetches it off-thread; retried at most every ten minutes. */
    @JvmStatic
    fun requestShops() {
        if (shops.isNotEmpty() || fetching) return
        val cache = cachePath()
        if (cache != null && Files.isRegularFile(cache)) {
            val cached = parse(Files.readString(cache))
            if (cached.isNotEmpty()) {
                shops = cached
                return
            }
        }
        val now = System.currentTimeMillis()
        if (now - lastAttempt < RETRY_MS) return
        lastAttempt = now
        fetching = true
        Thread({
            try {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                val request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(20)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                val body = response.body()
                if (response.statusCode() == 200 && body.size <= MAX_BYTES) {
                    val text = String(body, Charsets.UTF_8)
                    val parsed = parse(text)
                    if (parsed.isNotEmpty()) {
                        shops = parsed
                        cache?.let { Files.createDirectories(it.parent); Files.writeString(it, text) }
                    }
                }
            } catch (_: Exception) {
                // Retried on a later shop visit.
            } finally {
                fetching = false
            }
        }, "geileraddons-carnival-shops").also { it.isDaemon = true }.start()
    }

    private fun cachePath(): Path? = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")?.resolve("carnival-shops.json")
    } catch (_: NullPointerException) {
        null
    }
}
