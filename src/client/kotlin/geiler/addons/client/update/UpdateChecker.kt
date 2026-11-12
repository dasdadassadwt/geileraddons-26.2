package geiler.addons.client.update

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.config.ModConfig
import geiler.addons.client.net.BoundedHttpBodyReader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.regex.Pattern

/**
 * Notices when a newer release has been published on GitHub.
 *
 * Deliberately passive: it reads the releases API, compares tags, and says so once. It never
 * downloads or installs anything, and the whole thing is skipped when the user turns it off in
 * the config. One request per launch runs on a background thread, failing silently so a rate limit
 * or an offline machine never becomes something the player notices.
 */
object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/dasdadassadwt/geileraddons-26.2/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/dasdadassadwt/geileraddons-26.2/releases/latest"
    private val timeout = Duration.ofSeconds(10)
    private const val MAX_RESPONSE_BYTES = 256 * 1024

    /** Non-null only when a strictly newer release exists. Written off-thread, so volatile. */
    @Volatile
    private var newerVersion: String? = null

    /** Plain-text notes for that release; read by the Info panel, never fetched separately. */
    @Volatile
    private var releaseNotes: String? = null

    /** Validated release page for the Download button; the releases page until a release arrives. */
    @Volatile
    private var releaseUrl: String = RELEASES_PAGE

    private var currentVersion = ""
    private var announced = false

    /** Fires the one background request. Safe to call before the player has joined anything. */
    @JvmStatic
    fun start() {
        currentVersion = FabricLoader.getInstance().getModContainer(GeilerAddons.MOD_ID)
            .map { container -> container.metadata.version.friendlyString }
            .orElse("")
        if (!ModConfig.checkForUpdates()) return

        val thread = Thread({ check() }, "GeilerAddons update check")
        // A hung request must never hold the game open on exit.
        thread.isDaemon = true
        thread.start()
    }

    /** Called each client tick; announces in chat once, as soon as there is a player to tell. */
    @JvmStatic
    fun tick() {
        if (announced) return
        val version = newerVersion ?: return
        val minecraft = Minecraft.getInstance()
        if (minecraft.player == null || minecraft.gui == null) return

        announced = true
        val message = Component.literal("[GeilerAddons] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("v$version is available").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" (you have v$currentVersion) ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("[open releases]").withStyle { style ->
                style.withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(ClickEvent.OpenUrl(URI.create(RELEASES_PAGE)))
            })
        // This came from the mod, not the server; keep it marked as a client system message.
        minecraft.gui.hud.getChat().addClientSystemMessage(message)
    }

    /** Short notice for the Click GUI, or null when there is nothing to report. */
    @JvmStatic
    fun bannerText(): String? = newerVersion?.let { "Update available: v$it" }

    /** Version bundled with this client, exposed to the Click GUI without duplicating metadata lookup. */
    @JvmStatic
    fun currentVersion(): String = currentVersion

    /** Newest release found by the background check, or null while current/unavailable. */
    @JvmStatic
    fun newerVersion(): String? = newerVersion

    @JvmStatic
    fun repositoryPage(): String = "https://github.com/dasdadassadwt/geileraddons-26.2"

    @JvmStatic
    fun releasesPage(): String = RELEASES_PAGE

    /** Sanitized release notes for the Info panel; empty until a newer release is known. */
    @JvmStatic
    fun releaseNotes(): String = releaseNotes ?: ""

    /** Validated release page; untrusted response URLs fall back to the project's release page. */
    @JvmStatic
    fun releaseUrl(): String = releaseUrl

    private fun check() {
        try {
            HttpClient.newBuilder().connectTimeout(timeout).build().use { client ->
                val request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                    .timeout(timeout)
                    // GitHub rejects requests without a User-Agent outright.
                    .header("User-Agent", GeilerAddons.MOD_ID)
                    .header("Accept", "application/vnd.github+json")
                    .GET()
                    .build()

                val response: HttpResponse<ByteArray> =
                    BoundedHttpBodyReader.send(client, request, MAX_RESPONSE_BYTES, timeout)
                if (response.statusCode() != 200) {
                    GeilerAddons.LOGGER.debug("Update check got HTTP {}", response.statusCode())
                    return
                }

                val body = String(response.body(), StandardCharsets.UTF_8)
                val json: JsonObject? = Gson().fromJson(body, JsonObject::class.java)
                if (json == null || !json.has("tag_name")) return
                val tag = stripPrefix(json.get("tag_name").asString)
                if (isNewer(tag, currentVersion)) {
                    // Publish newerVersion last because readers use it as the availability flag.
                    val notes = ReleaseNotes.sanitize(text(json, "body"))
                    val validated = ReleaseNotes.validateReleaseUrl(text(json, "html_url"))
                    releaseNotes = notes
                    releaseUrl = validated ?: RELEASES_PAGE
                    newerVersion = tag
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (exception: Exception) {
            // Offline, rate-limited, DNS-blocked, malformed body - none merits a warning.
            GeilerAddons.LOGGER.debug("Update check failed", exception)
        }
    }

    private fun stripPrefix(tag: String): String {
        val trimmed = tag.trim { it <= ' ' }
        return if (trimmed.startsWith('v') || trimmed.startsWith('V')) trimmed.substring(1) else trimmed
    }

    /** A missing or non-textual field is treated as absent rather than as a parse failure. */
    private fun text(json: JsonObject, name: String): String? {
        val value: JsonElement = json.get(name) ?: return null
        if (!value.isJsonPrimitive) return null
        return try {
            value.asString
        } catch (_: RuntimeException) {
            null
        }
    }

    /** Numeric compare per dot-separated part, so 1.10.0 correctly beats 1.9.0. */
    @JvmStatic
    fun isNewer(candidate: String, current: String): Boolean {
        val leftParts = parse(candidate)
        val rightParts = parse(current)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val left = leftParts.getOrElse(index) { 0 }
            val right = rightParts.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parse(version: String): IntArray {
        // Everything from a pre-release or build suffix on is dropped: "1.4.0-rc1" compares as 1.4.0.
        val cut = version.indexOfAny(charArrayOf('-', '+')).let { if (it < 0) version.length else it }
        val parts = Pattern.compile("\\.").split(version.substring(0, cut))
        return IntArray(parts.size) { index ->
            try {
                Integer.parseInt(parts[index].trim { it <= ' ' })
            } catch (_: NumberFormatException) {
                0
            }
        }
    }
}
