package geiler.addons.client.spotify

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What is playing, from either source. Progress is interpolated locally between reports, so the bar moves smoothly
 * without polling every frame.
 */
class SpotifyPlayback(
    val trackId: String?,
    val title: String,
    val artists: List<String>,
    val artistIds: List<String>,
    val album: String,
    val artUrl: String?,
    val durationMs: Long,
    val progressMs: Long,
    val playing: Boolean,
    val shuffle: Boolean?,
    val repeat: String?,
    val volume: Int?,
    val reportedAtMs: Long,
    val source: Source,
) {
    enum class Source { WEB_API, WINDOWS }

    val artistText: String get() = artists.joinToString(", ")

    /** A stable key for "the same song", for art caching and the start-of-track blocklist check. */
    val key: String get() = trackId ?: "$title|$artistText"

    fun progressAt(nowMs: Long): Long {
        val base = if (playing) progressMs + (nowMs - reportedAtMs).coerceAtLeast(0L) else progressMs
        return if (durationMs > 0) base.coerceIn(0L, durationMs) else base.coerceAtLeast(0L)
    }

    companion object {
        /**
         * Parses `GET /v1/me/player` (playback state). Null for an empty body, an ad or an episode without a track
         * object, or anything unreadable.
         */
        @JvmStatic
        fun parseWebApi(json: String?, nowMs: Long): SpotifyPlayback? {
            if (json.isNullOrBlank()) return null
            val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return null }
            val item = root.obj("item") ?: return null
            val artists = item.getAsJsonArray("artists")?.mapNotNull { it.asJsonObject } ?: emptyList()
            val images = item.obj("album")?.getAsJsonArray("images")?.mapNotNull { it.asJsonObject } ?: emptyList()
            // Spotify lists images largest first; the smallest one at least 64 px wide is enough for the HUD.
            val art = images.filter { (it.int("width") ?: 0) >= 64 }.minByOrNull { it.int("width") ?: Int.MAX_VALUE }
                ?: images.lastOrNull()
            return SpotifyPlayback(
                trackId = item.str("id"),
                title = item.str("name") ?: return null,
                artists = artists.mapNotNull { it.str("name") },
                artistIds = artists.mapNotNull { it.str("id") },
                album = item.obj("album")?.str("name") ?: "",
                artUrl = art?.str("url"),
                durationMs = item.long("duration_ms") ?: 0L,
                progressMs = root.long("progress_ms") ?: 0L,
                playing = root.get("is_playing")?.asBoolean ?: false,
                shuffle = root.get("shuffle_state")?.takeIf { !it.isJsonNull }?.asBoolean,
                repeat = root.str("repeat_state"),
                volume = root.obj("device")?.int("volume_percent"),
                reportedAtMs = nowMs,
                source = Source.WEB_API,
            )
        }

        /** One JSON line from the Windows media bridge; see [WindowsMediaBridge]. */
        @JvmStatic
        fun parseBridge(line: String?, nowMs: Long): SpotifyPlayback? {
            if (line.isNullOrBlank()) return null
            val root = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { return null }
            if (root.str("type") != "state") return null
            val title = root.str("title")?.takeIf { it.isNotBlank() } ?: return null
            val artist = root.str("artist") ?: ""
            return SpotifyPlayback(
                trackId = null, title = title,
                artists = if (artist.isBlank()) emptyList() else listOf(artist), artistIds = emptyList(),
                album = root.str("album") ?: "", artUrl = null,
                durationMs = root.long("dur") ?: 0L, progressMs = root.long("pos") ?: 0L,
                playing = root.get("playing")?.asBoolean ?: false, shuffle = null, repeat = null, volume = null,
                reportedAtMs = nowMs, source = Source.WINDOWS,
            )
        }

        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
        private fun JsonObject.long(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive }?.asLong
        private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    }
}

/** The mod-side blocklist: Spotify has no API to stop recommending a song or artist, so the mod skips them itself. */
object SpotifyBlocklist {
    /** Whether [playback] matches a blocked song or artist; entries are `track:<id or title|artist>` / `artist:<id or name>`. */
    @JvmStatic
    fun blocked(playback: SpotifyPlayback, entries: Collection<String>): Boolean {
        if (entries.isEmpty()) return false
        if ("track:${playback.key}" in entries) return true
        if (playback.artistIds.any { "artist:$it" in entries }) return true
        return playback.artists.any { "artist:${it.lowercase()}" in entries }
    }

    @JvmStatic
    fun trackEntry(playback: SpotifyPlayback): String = "track:${playback.key}"

    /** Artist ids from the Web API; with only a name (Windows), the lowercase name. */
    @JvmStatic
    fun artistEntry(playback: SpotifyPlayback): String? =
        playback.artistIds.firstOrNull()?.let { "artist:$it" } ?: playback.artists.firstOrNull()?.let { "artist:${it.lowercase()}" }
}
