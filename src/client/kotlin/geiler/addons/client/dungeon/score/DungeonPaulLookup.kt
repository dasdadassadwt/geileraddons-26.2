package geiler.addons.client.dungeon.score

import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.net.BoundedHttpBodyReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Whether Paul with the EZPZ perk is the current mayor (+10 score), as Odin's `hasBonusPaulScore` reads it from the
 * public SkyBlock election endpoint. One bounded request off the client thread; nothing about the player is sent.
 */
object DungeonPaulLookup {
    const val URL = "https://api.hypixel.net/resources/skyblock/election"
    private const val MAX_BYTES = 262_144
    private val TIMEOUT: Duration = Duration.ofSeconds(10)

    /** True only for a well-formed response whose mayor is Paul and who has the EZPZ perk. */
    @JvmStatic
    fun parse(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonObject) return false
            val mayor = root.asJsonObject.getAsJsonObject("mayor") ?: return false
            if (mayor.get("name")?.asString != "Paul") return false
            mayor.getAsJsonArray("perks")?.any { it.isJsonObject && it.asJsonObject.get("name")?.asString == "EZPZ" } == true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Starts a background request; [done] gets true, false, or null when the request failed, on that thread. */
    @JvmStatic
    fun fetch(done: (Boolean?) -> Unit) {
        Thread({
            val result: Boolean? = try {
                val client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()
                val request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(TIMEOUT)
                    .header("User-Agent", GeilerAddons.MOD_ID)
                    .GET()
                    .build()
                val response = BoundedHttpBodyReader.send(client, request, MAX_BYTES, TIMEOUT)
                if (response.statusCode() == 200) parse(String(response.body(), StandardCharsets.UTF_8)) else null
            } catch (_: Exception) {
                null
            }
            done(result)
        }, "geileraddons-paul-lookup").apply { isDaemon = true }.start()
    }
}
