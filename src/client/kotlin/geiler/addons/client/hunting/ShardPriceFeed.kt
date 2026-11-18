package geiler.addons.client.hunting

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the 37 shard products out of Hypixel's bazaar response, streaming so the other ~2,000 products are
 * skipped without being built. Both prices come off the top of the order book, not `quick_status`: that is a
 * volume-weighted average over the whole book, so one huge junk order at one coin drags a shard's price to nothing.
 * Instant sell is the best standing buy order (`sell_summary[0]`); sell offer is the cheapest standing sell
 * offer (`buy_summary[0]`).
 */
object ShardPriceParsing {
    @JvmStatic
    fun parse(reader: Reader, wanted: Set<String>): Map<String, ShardPrice>? = try {
        JsonReader(reader).use { json ->
            val out = HashMap<String, ShardPrice>()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "products") {
                    json.skipValue()
                    continue
                }
                json.beginObject()
                while (json.hasNext()) {
                    val id = json.nextName()
                    if (id in wanted) out[id] = product(json) else json.skipValue()
                }
                json.endObject()
            }
            json.endObject()
            out
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    private fun product(json: JsonReader): ShardPrice {
        var instant = 0.0
        var offer = 0.0
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "sell_summary" -> instant = top(json)
                "buy_summary" -> offer = top(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        return ShardPrice(instant, offer)
    }

    /** `pricePerUnit` of the first order, or 0 when the book is empty. */
    private fun top(json: JsonReader): Double {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            return 0.0
        }
        var price = 0.0
        json.beginArray()
        var first = true
        while (json.hasNext()) {
            if (!first) {
                json.skipValue()
                continue
            }
            first = false
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "pricePerUnit") price = json.nextDouble() else json.skipValue()
            }
            json.endObject()
        }
        json.endArray()
        return if (price.isFinite() && price > 0.0) price else 0.0
    }
}

/**
 * What the shards are worth right now. The only outbound request this feature makes: a GET of Hypixel's public
 * bazaar endpoint, with no key, no account and nothing about the player in it. It runs off the client thread, is
 * rate limited (2 minutes between refreshes, a quick retry after a failure, a long back-off after repeated ones),
 * and only when the caller says a price is wanted.
 */
object ShardPriceFeed {
    private val ENDPOINT = URI.create("https://api.hypixel.net/v2/skyblock/bazaar")
    private const val REFRESH_MILLIS = 120_000L
    private const val RETRY_MILLIS = 15_000L
    private const val BACK_OFF_MILLIS = 900_000L
    private const val QUIET_FAILURES = 3
    private const val MAX_BYTES = 24L * 1024 * 1024

    private val wanted: Set<String> = CritterSpecies.all().map { it.bazaarId() }.toSet()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "geileraddons-shard-prices").also { it.isDaemon = true } }
    private val fetching = AtomicBoolean()
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    @Volatile private var prices: Map<String, ShardPrice> = emptyMap()
    @Volatile private var fetchedAt = 0L
    @Volatile private var nextAt = 0L
    @Volatile private var failures = 0
    @Volatile private var lastError: String? = null

    @JvmStatic
    fun prices(): Map<String, ShardPrice> = prices

    @JvmStatic
    fun hasPrices(): Boolean = prices.isNotEmpty()

    /** Bounded, player-free text for diagnostics and `/critters prices`. */
    @JvmStatic
    fun status(nowMillis: Long): String = when {
        fetching.get() -> "fetching"
        prices.isNotEmpty() -> "ok age=${(nowMillis - fetchedAt) / 1000}s products=${prices.size}"
        lastError != null -> "failed failures=$failures"
        else -> "idle"
    }

    /** Call from the client tick: starts a refresh when one is due and [wantedNow]; [force] ignores the schedule. */
    @JvmStatic
    fun tick(nowMillis: Long, wantedNow: Boolean, force: Boolean = false) {
        if (!wantedNow && !force) return
        if (!force && nowMillis < nextAt) return
        if (!fetching.compareAndSet(false, true)) return
        if (force) failures = 0
        worker.execute { fetch() }
    }

    private fun fetch() {
        try {
            val request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(20)).header("User-Agent", "GeilerAddons").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
            val parsed = response.body().use { stream ->
                ShardPriceParsing.parse(InputStreamReader(Bounded(stream, MAX_BYTES), Charsets.UTF_8), wanted)
            } ?: throw IOException("unreadable response")
            prices = parsed
            fetchedAt = System.currentTimeMillis()
            failures = 0
            lastError = null
            nextAt = fetchedAt + REFRESH_MILLIS
        } catch (e: Exception) {
            failures++
            lastError = e.javaClass.simpleName
            nextAt = System.currentTimeMillis() + if (failures > QUIET_FAILURES) BACK_OFF_MILLIS else RETRY_MILLIS
        } finally {
            fetching.set(false)
        }
    }

    /** Ends the stream after [limit] bytes so a hostile or broken response cannot be read without bound. */
    private class Bounded(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = super.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val n = super.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }
}
