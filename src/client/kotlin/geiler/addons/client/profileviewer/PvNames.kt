package geiler.addons.client.profileviewer

import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Co-op member names for collection hovers, from Mojang's public session server; cached for the game session. */
object PvNames {
    private const val MAX_CACHED = 256
    private val ID = Regex("[0-9a-f]{32}")
    private val names = ConcurrentHashMap<String, String>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-pv-names").also { t -> t.isDaemon = true } }

    /** The name for a compact [uuid], or a short id while it loads (the first call starts the lookup). */
    fun get(uuid: String): String {
        val id = uuid.replace("-", "").lowercase()
        names[id]?.let { return it }
        if (ID.matches(id) && requested.size < MAX_CACHED && requested.add(id)) worker.execute {
            try {
                val request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/$id"))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", GeilerAddons.MOD_ID).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) JsonParser.parseString(response.body()).asJsonObject.get("name")?.asString?.let { names[id] = it }
            } catch (_: Exception) {
                requested.remove(id)
            }
        }
        return id.take(8)
    }

    fun put(uuid: String, name: String) {
        names[uuid.replace("-", "").lowercase()] = name
    }
}
