package geiler.addons.client.profileviewer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * NotEnoughUpdates repository constants the Profile Viewer needs (bestiary, minions and accessory upgrades, attributes,
 * HOTM and HOTF layouts). Downloaded once per day from the repository's raw files and cached under
 * `config/geileraddons/neu-constants/`; a stale cache is used when the download fails.
 */
object PvConstants {
    val FILES = listOf("bestiary", "misc", "attribute_shards", "hotmlayout", "hotflayout", "garden", "museum", "collections")

    /** Files that come from Hypixel's keyless resources API instead of the NEU repository. */
    private val URLS = mapOf("collections" to "https://api.hypixel.net/v2/resources/skyblock/collections")
    private const val BASE = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/constants/"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_AGE_MS = 24L * 3_600_000L
    private val DIR: Path by lazy { FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("neu-constants") }
    private val loaded = ConcurrentHashMap<String, JsonObject>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-neu-constants").also { t -> t.isDaemon = true } }

    @Volatile
    var status = "not loaded"
        private set

    /** The file's JSON, or null while it loads (the first call starts loading all files). */
    fun get(name: String): JsonObject? {
        loaded[name]?.let { return it }
        if (requested.add(name)) worker.execute { load(name) }
        return null
    }

    fun requestAll() = FILES.forEach { get(it) }

    private fun load(name: String) {
        val file = DIR.resolve("$name.json")
        try {
            val fresh = Files.isRegularFile(file) && System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < MAX_AGE_MS
            if (!fresh) download(name, file)
        } catch (e: Exception) {
            GeilerAddons.LOGGER.debug("[Profile Viewer] Constants download failed for {} ({})", name, e.javaClass.simpleName)
        }
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
                loaded[name] = JsonParser.parseString(Files.readString(file)).asJsonObject
                status = "loaded ${loaded.size}/${FILES.size}"
            } else {
                status = "missing $name"
                requested.remove(name)
            }
        } catch (e: Exception) {
            status = "unreadable $name"
            requested.remove(name)
        }
    }

    private fun download(name: String, file: Path) {
        val request = HttpRequest.newBuilder(URI.create(URLS[name] ?: "$BASE$name.json")).timeout(Duration.ofSeconds(30))
            .header("User-Agent", GeilerAddons.MOD_ID).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200 || response.body().size > MAX_BYTES) return
        JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject // reject non-JSON before saving
        Files.createDirectories(DIR)
        val temp = file.resolveSibling("$name.json.tmp")
        Files.write(temp, response.body())
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}
