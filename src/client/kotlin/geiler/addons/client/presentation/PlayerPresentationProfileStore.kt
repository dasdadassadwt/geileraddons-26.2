package geiler.addons.client.presentation

import geiler.addons.GeilerAddons
import geiler.addons.client.net.BoundedHttpBodyReader
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.Executors

/** Bounded asynchronous loader for the hosted player presentation profile. */
class PlayerPresentationProfileStore private constructor() {
    @Volatile
    private var currentProfiles = PlayerPresentationProfiles.empty()

    @Volatile
    private var refreshAfterMillis = 0L

    private var localLoaded = false
    private var fetching = false

    fun current(): PlayerPresentationProfiles = currentProfiles

    /** Loads the last valid disk/bundled profile once, then starts a background refresh if due. */
    fun refreshIfDue() {
        val loadLocal = synchronized(this) {
            val now = System.currentTimeMillis()
            if (fetching || now < refreshAfterMillis) return
            fetching = true
            val shouldLoadLocal = !localLoaded
            localLoaded = true
            refreshAfterMillis = now + PlayerPresentationProfileSource.FAILURE_RETRY.toMillis()
            shouldLoadLocal
        }

        LOADER.execute {
            if (loadLocal) {
                try {
                    loadLocalSources()
                } catch (error: RuntimeException) {
                    GeilerAddons.LOGGER.warn("Could not load local player presentation profile sources", error)
                    currentProfiles = PlayerPresentationProfileFallback.local(null, null)
                }
            }
            fetchProfile()
        }
    }

    private fun loadLocalSources() {
        currentProfiles = PlayerPresentationProfileCacheIo.loadLocal(
            cachePath(),
            PlayerPresentationProfileStore::class.java,
            PlayerPresentationProfileSource.BUNDLED_RESOURCE,
        )
    }

    private fun fetchProfile() {
        var nextRefresh = System.currentTimeMillis() + PlayerPresentationProfileSource.FAILURE_RETRY.toMillis()
        try {
            val request = HttpRequest.newBuilder(URI.create(PROFILE_URL))
                .timeout(PlayerPresentationProfileSource.REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "GeilerAddons/1.6.0 player-presentation-profile")
                .GET()
                .build()
            val response: HttpResponse<ByteArray> = BoundedHttpBodyReader.send(
                HTTP,
                request,
                MAX_BODY_BYTES,
                PlayerPresentationProfileSource.RESPONSE_DEADLINE,
            )
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw IOException("Profile server returned HTTP ${response.statusCode()}")
            }
            val json = PlayerPresentationProfiles.decodeUtf8(response.body())
            val parsed = PlayerPresentationProfiles.parse(json)
            currentProfiles = PlayerPresentationProfileFallback.hosted(parsed, currentProfiles)
            PlayerPresentationProfileCacheIo.writeCache(cachePath(), json)
            nextRefresh = System.currentTimeMillis() + PlayerPresentationProfileSource.REFRESH_INTERVAL.toMillis()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            GeilerAddons.LOGGER.debug("Player presentation profile refresh was interrupted")
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn(
                "Could not refresh player presentation profile; keeping the last valid copy: {}",
                error.message,
            )
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn(
                "Could not refresh player presentation profile; keeping the last valid copy: {}",
                error.message,
            )
        } finally {
            synchronized(this) {
                refreshAfterMillis = nextRefresh
                fetching = false
            }
        }
    }

    companion object {
        @JvmField
        val INSTANCE = PlayerPresentationProfileStore()

        @JvmField
        val PROFILE_URL: String = PlayerPresentationProfileSource.RAW_PROFILE_URL

        private val MAX_BODY_BYTES = PlayerPresentationProfileSource.MAX_BODY_BYTES
        private val HTTP = HttpClient.newBuilder()
            .connectTimeout(PlayerPresentationProfileSource.CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        private val LOADER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "GeilerAddons player profile loader").apply { isDaemon = true }
        }

        private fun cachePath(): Path = FabricLoader.getInstance().configDir
            .resolve("geileraddons/player-presentations-cache.json")
    }
}
