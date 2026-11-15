package geiler.addons.client.dungeon.f7

import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.net.BoundedHttpBodyReader
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
    const val MAX_ENTRIES = 20_000
    private const val MAX_BYTES = 2_097_152
    private val TIMEOUT: Duration = Duration.ofSeconds(15)

    class Parsed(val prices: Map<String, Double>, val error: String?)

    @Volatile
    var prices: Map<String, Double> = emptyMap()
        private set

    @Volatile
    var status: String = "not loaded"
        private set

    @Volatile
    private var fetching = false

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
        status = "loading"
        Thread({
            try {
                val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                val request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(TIMEOUT)
                    .header("User-Agent", GeilerAddons.MOD_ID)
                    .GET()
                    .build()
                val response = BoundedHttpBodyReader.send(client, request, MAX_BYTES, TIMEOUT)
                if (response.statusCode() != 200) {
                    status = "HTTP ${response.statusCode()}"
                } else {
                    val parsed = parse(String(response.body(), StandardCharsets.UTF_8))
                    if (parsed.error == null) {
                        prices = parsed.prices
                        status = "${parsed.prices.size} prices"
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
