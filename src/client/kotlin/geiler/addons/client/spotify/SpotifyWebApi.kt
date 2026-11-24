package geiler.addons.client.spotify

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Spotify Web API calls on one background thread, so the game thread never waits on the network. Playback writes
 * (play, pause, skip, seek, shuffle, repeat, volume) need Spotify Premium; a 403 sets [premiumRequired].
 */
class SpotifyWebApi(private val clientId: () -> String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-spotify-api").also { t -> t.isDaemon = true } }

    @Volatile var playback: SpotifyPlayback? = null
        private set
    @Volatile var liked: Boolean? = null
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var premiumRequired = false
        private set
    @Volatile private var retryAfterMs = 0L
    @Volatile private var polling = false
    private var likedFor: String? = null

    /** Requests a playback refresh unless one is in flight or Spotify asked us to slow down. */
    fun poll() {
        if (polling || System.currentTimeMillis() < retryAfterMs) return
        polling = true
        worker.execute {
            try {
                val response = send("GET", "/me/player", null) ?: return@execute
                when (response.statusCode()) {
                    200 -> {
                        val next = SpotifyPlayback.parseWebApi(response.body(), System.currentTimeMillis())
                        playback = next
                        lastError = null
                        if (next?.trackId != null && next.trackId != likedFor) refreshLiked(next.trackId)
                    }
                    204 -> playback = null
                }
            } finally {
                polling = false
            }
        }
    }

    private fun refreshLiked(trackId: String) {
        likedFor = trackId
        liked = null
        val response = send("GET", "/me/library/contains?uris=" + enc("spotify:track:$trackId"), null) ?: return
        if (response.statusCode() == 200) {
            liked = try { JsonParser.parseString(response.body()).asJsonArray.firstOrNull()?.asBoolean } catch (_: Exception) { null }
        }
    }

    fun playPause() {
        val current = playback ?: return
        write("PUT", if (current.playing) "/me/player/pause" else "/me/player/play")
    }

    fun next() = write("POST", "/me/player/next")
    fun previous() = write("POST", "/me/player/previous")
    fun seek(ms: Long) = write("PUT", "/me/player/seek?position_ms=${ms.coerceAtLeast(0L)}")
    fun shuffle(on: Boolean) = write("PUT", "/me/player/shuffle?state=$on")
    fun repeat(state: String) = write("PUT", "/me/player/repeat?state=$state")
    fun volume(percent: Int) = write("PUT", "/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}")

    /** Adds or removes the current song from Liked Songs (Library endpoints, February 2026 API). */
    fun setLiked(trackId: String, like: Boolean) {
        worker.execute {
            val response = send(if (like) "PUT" else "DELETE", "/me/library?uris=" + enc("spotify:track:$trackId"), "") ?: return@execute
            if (response.statusCode() in 200..299) liked = like
        }
    }

    private fun write(method: String, path: String) {
        worker.execute {
            val response = send(method, path, "") ?: return@execute
            premiumRequired = response.statusCode() == 403
            if (response.statusCode() in 200..299) {
                Thread.sleep(250)
                polling = false
                poll()
            }
        }
    }

    /** One call with a fresh token; a 401 refreshes once and retries. Null when logged out or on a network error. */
    private fun send(method: String, path: String, body: String?): HttpResponse<String>? {
        repeat(2) { attempt ->
            val token = SpotifyAuth.accessToken(clientId()) ?: run {
                lastError = "not logged in"
                return null
            }
            val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
            val request = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token").method(method, publisher).build()
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
                return null
            }
            when (response.statusCode()) {
                401 -> if (attempt == 0 && SpotifyAuth.refresh(clientId())) return@repeat
                429 -> {
                    val seconds = response.headers().firstValue("Retry-After").orElse("5").toLongOrNull() ?: 5L
                    retryAfterMs = System.currentTimeMillis() + seconds * 1000L
                    lastError = "rate limited for ${seconds}s"
                }
            }
            if (response.statusCode() >= 400 && response.statusCode() != 429) lastError = "HTTP ${response.statusCode()}"
            return response
        }
        return null
    }

    fun shutdown() = worker.shutdownNow()

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        private const val API = "https://api.spotify.com/v1"
    }
}
