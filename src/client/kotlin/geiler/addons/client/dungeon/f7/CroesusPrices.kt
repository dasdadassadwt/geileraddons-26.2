package geiler.addons.client.dungeon.f7

import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.net.BoundedHttpBodyReader
import geiler.addons.client.pricing.BazaarProduct
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * The 7-day average item prices Odin publishes at `lb.odtheking.com/averages/7day`, used for Croesus profit.
 * One bounded request per fetch, off the client thread; nothing about the player is sent.
 */
object CroesusPrices {
    const val URL = "https://lb.odtheking.com/averages/7day"

    /** The Dungeon Chest Key is priced from the shorter 3-day average, which follows the market more closely. */
    const val KEY_URL = "https://lb.odtheking.com/averages/3day"
    const val KEY_ID = "DUNGEON_CHEST_KEY"
    const val MAX_ENTRIES = 20_000
    private const val MAX_BYTES = 2_097_152
    private val TIMEOUT: Duration = Duration.ofSeconds(15)
    private const val STALE_MILLIS = 3_600_000L
    private const val RETRY_MILLIS = 60_000L
    private val BAZAAR_BOOK = Regex("""^ENCHANTMENT_(.+)_(\d+)$""")

    @Volatile
    private var loadedAt = 0L

    @Volatile
    private var attemptedAt = 0L

    /** Downloads again when the averages are an hour old or never arrived; failed attempts wait a minute. */
    @JvmStatic
    fun refreshIfStale(done: (String) -> Unit = {}) {
        val now = System.currentTimeMillis()
        if (now - loadedAt >= STALE_MILLIS && now - attemptedAt >= RETRY_MILLIS) refresh(done)
    }

    /**
     * Live prices keyed like the 7-day file: [base], overlaid with lowest BIN, then with the Bazaar. [sellOffer] takes
     * the Bazaar's buy price (what a sell offer earns), else its sell price (selling instantly). Zero or missing live
     * values keep [base].
     */
    @JvmStatic
    fun live(
        base: Map<String, Double>,
        lowestBin: Map<String, Double>?,
        bazaar: Map<String, BazaarProduct>?,
        sellOffer: Boolean,
    ): Map<String, Double> {
        val result = HashMap(base)
        lowestBin?.forEach { (id, price) -> if (price > 0) result[id] = price }
        bazaar?.forEach { (id, product) ->
            val price = (if (sellOffer) product.buyPrice else product.sellPrice) ?: return@forEach
            if (price <= 0) return@forEach
            result[id] = price
            BAZAAR_BOOK.find(id)?.destructured?.let { (name, level) -> result["ENCHANTED_BOOK-$name-$level"] = price }
        }
        return result
    }

    class Parsed(val prices: Map<String, Double>, val error: String?)

    @Volatile
    var prices: Map<String, Double> = emptyMap()
        private set

    /** 3-day average price of one Dungeon Chest Key; 0 while unknown, so the second chest is then not reduced. */
    @Volatile
    var keyPrice: Double = 0.0
        private set

    @Volatile
    var status: String = "not loaded"
        private set

    @Volatile
    private var fetching = false

    /** The key price of a 3-day file, or null when the file is broken or has no key entry. */
    @JvmStatic
    fun parseKey(json: String?): Double? = parse(json).prices[KEY_ID]

    private fun download(client: HttpClient, url: String): java.net.http.HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("User-Agent", GeilerAddons.MOD_ID)
            .GET()
            .build()
        return BoundedHttpBodyReader.send(client, request, MAX_BYTES, TIMEOUT)
    }

    /** One extra bounded request; a failure leaves the last known key price in place. */
    private fun fetchKey(client: HttpClient): String {
        return try {
            val response = download(client, KEY_URL)
            if (response.statusCode() != 200) return "key HTTP ${response.statusCode()}"
            val key = parseKey(String(response.body(), StandardCharsets.UTF_8)) ?: return "key missing"
            keyPrice = key
            "key ${"%,.0f".format(java.util.Locale.US, key)}"
        } catch (exception: Exception) {
            "key failed: ${exception.javaClass.simpleName}"
        }
    }

    @JvmStatic
    fun parse(json: String?): Parsed {
        if (json.isNullOrBlank()) return Parsed(emptyMap(), "empty response")
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return Parsed(emptyMap(), "not a JSON object")
            val result = HashMap<String, Double>()
            for ((key, value) in root.asJsonObject.entrySet()) {
                if (result.size >= MAX_ENTRIES) return Parsed(emptyMap(), "too many entries")
                if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) result[key] = value.asDouble
            }
            Parsed(result, null)
        } catch (_: RuntimeException) {
            Parsed(emptyMap(), "malformed JSON")
        }
    }

    /** Starts a background fetch unless one is running; [done] gets the status line on the fetch thread. */
    @JvmStatic
    @JvmOverloads
    fun refresh(done: (String) -> Unit = {}) {
        if (fetching) return
        fetching = true
        attemptedAt = System.currentTimeMillis()
        status = "loading"
        Thread({
            try {
                val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                val response = download(client, URL)
                if (response.statusCode() != 200) {
                    status = "HTTP ${response.statusCode()}"
                } else {
                    val parsed = parse(String(response.body(), StandardCharsets.UTF_8))
                    if (parsed.error == null) {
                        prices = parsed.prices
                        loadedAt = System.currentTimeMillis()
                        status = "${parsed.prices.size} prices, ${fetchKey(client)}"
                    } else {
                        status = "failed: ${parsed.error}"
                    }
                }
            } catch (exception: Exception) {
                status = "failed: ${exception.javaClass.simpleName}"
            } finally {
                fetching = false
                done(status)
            }
        }, "geileraddons-croesus-prices").also { it.isDaemon = true }.start()
    }
}
