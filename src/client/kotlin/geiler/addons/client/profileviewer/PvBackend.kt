package geiler.addons.client.profileviewer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Garden and Museum for the Profile Viewer, from the SkyBlockPV backend (it proxies those Hypixel endpoints without a
 * key; the profile proxy does not serve them). Sign-in works like joining a server: the client tells Mojang's session
 * server it joined a random server id (`joinServer`, the access token goes to Mojang only), then the backend's
 * `/authenticate` gets only the player name and that id and asks Mojang. The returned backend token lives in memory
 * only. Nothing is sent before a Garden or Museum tab asks for data, and only `/garden/<id>` and `/museum/<id>`
 * are requested.
 */
object PvBackend {
    private const val BASE = "https://skyblock-pv.thatgravyboat.tech"
    private const val TOKEN_LIFE_MS = 23L * 3_600_000L
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHED = 32
    private val PATH = Regex("/(garden|museum)/[0-9a-f]{32}")

    class Result(val data: JsonObject?, val error: String?)

    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-pv-backend").also { t -> t.isDaemon = true } }
    private val cache = ConcurrentHashMap<String, Result>()
    private val pending = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenAt = 0L

    private fun log(text: String) {
        Minecraft.getInstance().execute { geiler.addons.client.module.impl.ProfileViewerModule.INSTANCE.onBackend(text) }
    }

    @JvmStatic
    fun compact(id: String): String = id.replace("-", "").lowercase()

    @JvmStatic
    fun allowedPath(path: String): Boolean = PATH.matches(path)

    /** Cached result for `/garden/<profile>` or `/museum/<profile>`, or null while it loads (the first call starts it). */
    fun get(kind: String, profileId: String): Result? {
        val path = "/$kind/${compact(profileId)}"
        cache[path]?.let { return it }
        if (!allowedPath(path)) return Result(null, "bad profile id")
        if (pending.add(path)) worker.execute {
            val result = try {
                fetch(path, true)
            } catch (e: Exception) {
                Result(null, e.message ?: e.javaClass.simpleName)
            }
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[path] = result
            pending.remove(path)
            log("BACKEND kind=$kind ok=${result.data != null} error=${result.error ?: "-"}")
        }
        return null
    }

    /** Forgets a failed result so the next view retries. */
    fun retry(kind: String, profileId: String) {
        cache.remove("/$kind/${compact(profileId)}")
    }

    private fun fetch(path: String, retryAuth: Boolean): Result {
        val auth = token() ?: return Result(null, "sign-in failed")
        val request = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", GeilerAddons.MOD_ID).header("Authorization", auth).header("X-Intent", "profile viewer").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return when (response.statusCode()) {
            200 -> {
                if (response.body().size > MAX_BYTES) return Result(null, "answer too large")
                Result(JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject, null)
            }
            401 -> if (retryAuth) {
                token = null
                fetch(path, false)
            } else Result(null, "not signed in")
            404 -> Result(null, "none")
            429 -> Result(null, "too many lookups, try again in a minute")
            else -> Result(null, "HTTP ${response.statusCode()}")
        }
    }

    /** The backend token, signing in first when there is none or it is old. Worker thread only. */
    private fun token(): String? {
        token?.let { if (System.currentTimeMillis() - tokenAt < TOKEN_LIFE_MS) return it }
        val mc = Minecraft.getInstance()
        val user = mc.user
        val server = UUID.randomUUID().toString().replace("-", "")
        try {
            mc.services().sessionService().joinServer(user.profileId, user.accessToken, server)
        } catch (e: Exception) {
            log("SIGNIN mojang_join_failed=${e.javaClass.simpleName}")
            return null
        }
        val request = HttpRequest.newBuilder(URI.create("$BASE/authenticate")).timeout(Duration.ofSeconds(20))
            .header("User-Agent", GeilerAddons.MOD_ID).header("x-minecraft-username", user.name).header("x-minecraft-server", server).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body()?.trim() ?: ""
        log("SIGNIN status=${response.statusCode()}")
        if (response.statusCode() != 200 || body.isEmpty() || body.length > 4096) return null
        token = body
        tokenAt = System.currentTimeMillis()
        return body
    }
}

