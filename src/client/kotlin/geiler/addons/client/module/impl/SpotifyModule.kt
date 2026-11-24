package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.hud.HudElement
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.spotify.SpotifyArt
import geiler.addons.client.spotify.SpotifyAuth
import geiler.addons.client.spotify.SpotifyBlocklist
import geiler.addons.client.spotify.SpotifyPlayback
import geiler.addons.client.spotify.SpotifyWebApi
import geiler.addons.client.spotify.WindowsMediaBridge
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Spotify now-playing HUD with controls. Two sources: the Spotify Web API (the user's own app Client ID, PKCE login;
 * controls need Premium) and, on Windows, the system media controls through [WindowsMediaBridge] (no login, play/pause
 * and skip only). Controls are clickable while chat is open. Spotify offers no API for "remove from taste profile" or
 * "don't play this artist/song", so the block buttons keep a mod-side blocklist and skip matching songs.
 */
class SpotifyModule private constructor(private val s: Settings) : Module(
    "Spotify",
    "Now playing HUD for Spotify: cover, title, artist, progress and controls (click them with chat open).",
    Category.MISCELLANEOUS,
    false,
    *s.all(),
), HudElement {
    enum class Action(val label: String, val short: String, val webOnly: Boolean) {
        NONE("None", "", false),
        LIKE("Like / Unlike", "Like", true),
        SHUFFLE("Shuffle", "Shuf", true),
        REPEAT("Repeat", "Rep", true),
        VOLUME_DOWN("Volume -", "Vol-", true),
        VOLUME_UP("Volume +", "Vol+", true),
        RESTART("Restart Song", "Rst", true),
        OPEN("Open in Spotify", "Open", false),
        BLOCK_ARTIST("Block Artist (mod)", "No Art", false),
        BLOCK_SONG("Block Song (mod)", "No Song", false);

        companion object {
            @JvmStatic
            fun of(label: String?): Action = entries.firstOrNull { it.label == label } ?: NONE
        }
    }

    private class Button(val x: Int, val y: Int, val w: Int, val h: Int, val label: String, val run: () -> Unit, val enabled: Boolean)

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private val api = SpotifyWebApi { s.clientId.value().trim() }
    private val bridge = WindowsMediaBridge()
    private val blocklist = LinkedHashSet<String>()
    private var blocklistLoaded = false
    private var pollTicks = 0
    private var lastKey: String? = null
    private var lastSkipped: String? = null
    private var buttons: List<Button> = emptyList()
    private var clicks = 0
    private var autoSkips = 0

    override fun id(): String = "spotify_hud"
    override fun displayName(): String = "Spotify"

    override fun onEnable() {
        loadBlocklist()
        diagnostics.enable("source=${s.source.value()} style=${s.style.value()} loggedIn=${loggedIn()} windows=${WindowsMediaBridge.supported()}")
    }

    override fun onDisable() {
        bridge.stop()
        diagnostics.disable()
    }

    private fun loggedIn(): Boolean = s.clientId.value().isNotBlank() && SpotifyAuth.loggedIn(s.clientId.value().trim())

    /** Which source feeds the HUD: Web API when chosen or (Auto) when logged in, else the Windows bridge. */
    private fun useWeb(): Boolean = when (s.source.value()) {
        "Web API" -> true
        "Windows" -> false
        else -> loggedIn()
    }

    /** In Auto, playback controls fall back to the Windows bridge when the Web API refuses them (no Premium). */
    private fun controlsViaBridge(): Boolean = !useWeb() || (s.source.value() == "Auto" && api.premiumRequired && bridge.running)

    fun playback(): SpotifyPlayback? = if (useWeb()) api.playback else bridge.playback

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val mc = Minecraft.getInstance()
        val wantBridge = WindowsMediaBridge.supported() && (!useWeb() || (s.source.value() == "Auto" && api.premiumRequired))
        if (wantBridge && !bridge.running) bridge.start() else if (!wantBridge && bridge.running) bridge.stop()
        if (useWeb() && loggedIn() && mc.isWindowActive) {
            val interval = if (api.playback?.playing == true) PLAYING_POLL_TICKS else IDLE_POLL_TICKS
            if (++pollTicks >= interval) {
                pollTicks = 0
                api.poll()
            }
        }
        val current = playback()
        if (current != null && current.key != lastKey) {
            lastKey = current.key
            diagnostics.event("TRACK source=${current.source} playing=${current.playing} hasArt=${current.artUrl != null}")
            if (SpotifyBlocklist.blocked(current, blocklist) && lastSkipped != current.key) {
                lastSkipped = current.key
                autoSkips++
                next()
                diagnostics.event("AUTO_SKIP blocked=true")
            }
        }
        diagnostics.statusOnChange(200L) {
            "source=${if (useWeb()) "web" else "windows"} loggedIn=${loggedIn()} auth=${SpotifyAuth.status} bridge=${bridge.status} " +
                "playing=${current?.playing} premiumRequired=${api.premiumRequired} apiError=${api.lastError} " +
                "blocklist=${blocklist.size} clicks=$clicks autoSkips=$autoSkips"
        }
    }

    // ---- actions ----

    private fun playPause() = if (controlsViaBridge()) bridge.send("toggle") else api.playPause()
    private fun next() = if (controlsViaBridge()) bridge.send("next") else api.next()
    private fun previous() = if (controlsViaBridge()) bridge.send("prev") else api.previous()

    private fun run(action: Action) {
        val current = playback() ?: return
        val web = useWeb()
        when (action) {
            Action.NONE -> {}
            Action.LIKE -> if (web) current.trackId?.let { api.setLiked(it, api.liked != true) }
            Action.SHUFFLE -> if (web) api.shuffle(current.shuffle != true)
            Action.REPEAT -> if (web) api.repeat(when (current.repeat) { "off" -> "context"; "context" -> "track"; else -> "off" })
            Action.VOLUME_DOWN -> if (web) current.volume?.let { api.volume(it - 10) }
            Action.VOLUME_UP -> if (web) current.volume?.let { api.volume(it + 10) }
            Action.RESTART -> if (web) api.seek(0)
            Action.OPEN -> Util.getPlatform().openUri(URI.create(current.trackId?.let { "https://open.spotify.com/track/$it" }
                ?: "https://open.spotify.com/search/" + URLEncoder.encode("${current.title} ${current.artistText}", StandardCharsets.UTF_8).replace("+", "%20")))
            Action.BLOCK_ARTIST -> SpotifyBlocklist.artistEntry(current)?.let { block(it, "artist ${current.artists.firstOrNull()}") }
            Action.BLOCK_SONG -> block(SpotifyBlocklist.trackEntry(current), "song ${current.title}")
        }
    }

    private fun block(entry: String, what: String) {
        if (!blocklist.add(entry)) return
        saveBlocklist()
        Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(
            Component.literal("[GeilerAddons] Spotify: blocked $what in this mod (Spotify itself is not changed). Skipping."))
        lastSkipped = playback()?.key
        next()
    }

    private fun login() {
        val started = SpotifyAuth.beginLogin(s.clientId.value().trim(), s.port.value().toInt()) { url ->
            Util.getPlatform().openUri(URI.create(url))
        }
        Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal(
            if (started) "[GeilerAddons] Spotify: finish the login in your browser. Redirect URI to add in your Spotify app: " +
                SpotifyAuth.redirectUri(s.port.value().toInt())
            else "[GeilerAddons] Spotify: ${SpotifyAuth.status}"))
        diagnostics.event("LOGIN_START started=$started")
    }

    // ---- blocklist persistence ----

    class SavedBlocklist {
        @JvmField var entries: MutableList<String> = ArrayList()
    }

    private fun loadBlocklist() {
        if (blocklistLoaded) return
        blocklistLoaded = true
        blocklistPath()?.let { ClientJsonFile.read(it, SavedBlocklist::class.java) }?.entries?.let { blocklist.addAll(it.take(MAX_BLOCKLIST)) }
    }

    private fun saveBlocklist() {
        val saved = SavedBlocklist().also { it.entries.addAll(blocklist) }
        blocklistPath()?.let { ClientJsonFile.writeAsync(it, ClientJsonFile.GSON.toJson(saved)) }
    }

    private fun blocklistPath() = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")?.resolve("spotify-blocklist.json")
    } catch (_: NullPointerException) {
        null
    }

    // ---- HUD ----

    override fun visible(): Boolean {
        if (!isEnabled()) return false
        val current = playback() ?: return false
        return current.playing || !s.hideWhenPaused.value()
    }

    override fun previewMode(): String = if (visible()) "live" else "sample"

    override fun width(font: Font?): Int = s.width.value().toInt()
    override fun height(font: Font?): Int = layoutHeight(font!!)

    override fun render(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val current = playback() ?: return
        draw(graphics, font, x, y, current, System.currentTimeMillis(), live = true)
    }

    override fun renderPreview(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val current = playback()
        if (current != null) draw(graphics, font, x, y, current, System.currentTimeMillis(), live = false)
        else draw(graphics, font, x, y, SAMPLE, SAMPLE.reportedAtMs, live = false)
    }

    override fun mouseClicked(localX: Double, localY: Double, button: Int): Boolean {
        if (button != 0) return false
        val hit = buttons.firstOrNull { localX >= it.x && localX < it.x + it.w && localY >= it.y && localY < it.y + it.h } ?: return false
        if (hit.enabled) {
            clicks++
            hit.run()
            diagnostics.event("CLICK button=${hit.label}")
        }
        return true
    }

    private fun style(): String = s.style.value()

    private fun showArt() = s.showArt.value() && style() != "Minimal"
    private fun showControls() = s.showControls.value() || s.showCustom.value()

    private fun artSize(): Int = if (style() == "Compact") 18 else 40

    private fun layoutHeight(font: Font): Int {
        val line = font.lineHeight + 1
        if (style() == "Compact") {
            val text = line + (if (s.showProgress.value()) 4 else 0)
            return maxOf(if (showArt()) artSize() else 0, text) + 8 + (if (showControls()) 14 else 0)
        }
        var text = 0
        if (s.showTitle.value()) text += line
        if (s.showArtist.value()) text += line
        val top = maxOf(if (showArt()) artSize() else 0, text)
        var height = 4 + top
        if (s.showProgress.value()) height += 6
        if (s.showTime.value()) height += line
        if (showControls()) height += 16
        return height + 4
    }

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, x: Int, y: Int, p: SpotifyPlayback, now: Long, live: Boolean) {
        val w = s.width.value().toInt()
        val h = layoutHeight(font)
        val accent = s.accent.argb()
        if (s.background.value()) GuiTheme.roundedRect(graphics, x, y, w, h, 4, GuiTheme.PANEL_TOP)
        var textX = x + 4
        var top = y + 4
        if (showArt()) {
            val size = artSize()
            val art = art(p)
            if (art != null) graphics.blit(RenderPipelines.GUI_TEXTURED, art, textX, top, 0f, 0f, size, size, size, size)
            else graphics.fill(textX, top, textX + size, top + size, 0xFF1DB954.toInt())
            textX += size + 4
        }
        val textW = maxOf(10, x + w - 4 - textX)
        val line = font.lineHeight + 1
        if (style() == "Compact") {
            val text = listOfNotNull(p.title.takeIf { s.showTitle.value() }, p.artistText.takeIf { s.showArtist.value() && it.isNotBlank() })
                .joinToString(" - ")
            graphics.text(font, GuiTheme.fit(font, text, textW), textX, top + 1, GuiTheme.TEXT_PRIMARY)
            if (s.showProgress.value()) bar(graphics, textX, top + line + 1, textW, 2, p, now, accent)
            top += maxOf(if (showArt()) artSize() else 0, line + 4) + 2
        } else {
            var textY = top
            if (s.showTitle.value()) { graphics.text(font, GuiTheme.fit(font, p.title, textW), textX, textY, GuiTheme.TEXT_PRIMARY); textY += line }
            if (s.showArtist.value()) { graphics.text(font, GuiTheme.fit(font, p.artistText, textW), textX, textY, GuiTheme.TEXT_SECONDARY); textY += line }
            top = maxOf(textY, top + if (showArt()) artSize() else 0) + 2
            if (s.showProgress.value()) {
                bar(graphics, x + 4, top, w - 8, 3, p, now, accent)
                top += 6
            }
            if (s.showTime.value()) {
                val time = "${clock(p.progressAt(now))} / ${clock(p.durationMs)}" + if (!p.playing) "  paused" else ""
                graphics.text(font, time, x + 4, top, GuiTheme.TEXT_SECONDARY)
                top += line
            }
        }
        val built = ArrayList<Button>()
        if (showControls()) {
            var bx = x + 4
            val by = top + 1
            fun add(label: String, enabled: Boolean, run: () -> Unit) {
                val bw = font.width(label) + 6
                if (bx + bw > x + w - 2) return
                val color = if (enabled) GuiTheme.BUTTON_BG else 0x40000000
                GuiTheme.roundedRect(graphics, bx, by, bw, 12, 2, color)
                graphics.text(font, label, bx + 3, by + 2, if (enabled) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_MUTED)
                built.add(Button(bx - x, by - y, bw, 12, label, run, enabled))
                bx += bw + 2
            }
            val web = useWeb()
            if (s.showControls.value()) {
                add("<<", true) { previous() }
                add(if (p.playing) "||" else ">", true) { playPause() }
                add(">>", true) { next() }
            }
            if (s.showCustom.value()) {
                for (slot in s.buttons) {
                    val action = Action.of(slot.value())
                    if (action == Action.NONE) continue
                    val label = when (action) {
                        Action.LIKE -> if (api.liked == true) "Liked" else "Like"
                        Action.SHUFFLE -> if (p.shuffle == true) "Shuf*" else "Shuf"
                        Action.REPEAT -> "Rep:" + (p.repeat ?: "-")
                        else -> action.short
                    }
                    add(label, web || !action.webOnly) { run(action) }
                }
            }
            if (web && api.premiumRequired && live) graphics.text(font, "Premium needed for controls", x + 4, by + 14, GuiTheme.TEXT_WARN)
        }
        if (live) buttons = built
    }

    private fun bar(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, p: SpotifyPlayback, now: Long, accent: Int) {
        graphics.fill(x, y, x + w, y + h, 0x60FFFFFF)
        val fraction = if (p.durationMs > 0) (p.progressAt(now).toDouble() / p.durationMs).coerceIn(0.0, 1.0) else 0.0
        graphics.fill(x, y, x + (w * fraction).toInt(), y + h, accent)
    }

    private fun art(p: SpotifyPlayback): Identifier? = when (p.source) {
        SpotifyPlayback.Source.WEB_API -> p.artUrl?.let { SpotifyArt.fromUrl(p.key, it) }
        SpotifyPlayback.Source.WINDOWS -> {
            val key = "${p.title}|${p.artistText}"
            val bytes = bridge.art
            if (bytes != null && bridge.artKey == key) SpotifyArt.fromBytes(key, bytes) else null
        }
    }

    override fun settingTooltip(setting: Setting): String? = when {
        setting === s.clientId -> "Create an app at developer.spotify.com/dashboard, add the redirect URI shown on Log In, " +
            "and paste its Client ID here. Development Mode apps need the owner to have Premium and allow 5 users."
        setting === s.source -> "Auto: Web API when logged in, else the Windows media controls. Windows: no login or Premium, " +
            "but only play/pause and skip, and no Like/Shuffle/Repeat/Volume."
        s.buttons.contains(setting) -> "Custom buttons appear after the play controls, in slot order. Block buttons are mod-side: " +
            "Spotify has no API for taste profile or 'don't play this'; the mod skips blocked songs itself."
        else -> null
    }

    override fun groups(): List<SettingGroup> = listOf(
        SettingGroup("Account", s.source, s.clientId, s.port, s.login, s.logout),
        SettingGroup("Layout", s.style, s.width, s.accent, s.background, s.hideWhenPaused),
        SettingGroup("Elements", s.showArt, s.showTitle, s.showArtist, s.showProgress, s.showTime, s.showControls),
        SettingGroup("Custom Buttons", s.showCustom, true, s.buttons.toList<Setting>() + s.clearBlocklist, emptyList()),
    )

    private class Settings {
        val source = ChoiceSetting("Source", "Auto", "Auto", "Web API", "Windows")
        val clientId = TextSetting("Client ID", "Spotify Client ID", "", 64)
        val port = NumberSetting("Redirect Port", 1025f, 65535f, 8888f, true)
        val login = ModuleAction("Log In", "Open the Spotify login in your browser.", Runnable { INSTANCE.login() })
        val logout = ModuleAction("Log Out", "Forget the saved Spotify token.", Runnable { SpotifyAuth.logout() })
        val style = ChoiceSetting("Style", "Card", "Card", "Compact", "Minimal")
        val width = NumberSetting("Width", 100f, 320f, 180f, true)
        val accent = ColorSetting("Accent", 29, 185, 84)
        val background = BooleanSetting("Background", true)
        val hideWhenPaused = BooleanSetting("Hide When Paused", false)
        val showArt = BooleanSetting("Cover", true)
        val showTitle = BooleanSetting("Title", true)
        val showArtist = BooleanSetting("Artist", true)
        val showProgress = BooleanSetting("Progress Bar", true)
        val showTime = BooleanSetting("Timestamp", true)
        val showControls = BooleanSetting("Play Controls", true)
        val showCustom = BooleanSetting("Custom Buttons", true)
        val buttons = listOf(
            ChoiceSetting("Button 1", Action.LIKE.label, *Action.entries.map { it.label }.toTypedArray()),
            ChoiceSetting("Button 2", Action.SHUFFLE.label, *Action.entries.map { it.label }.toTypedArray()),
            ChoiceSetting("Button 3", Action.BLOCK_SONG.label, *Action.entries.map { it.label }.toTypedArray()),
            ChoiceSetting("Button 4", Action.NONE.label, *Action.entries.map { it.label }.toTypedArray()),
            ChoiceSetting("Button 5", Action.NONE.label, *Action.entries.map { it.label }.toTypedArray()),
        )
        val clearBlocklist = ModuleAction("Clear Blocklist", "Unblock every song and artist the block buttons added.",
            Runnable { INSTANCE.clearBlocklist() })

        fun all(): Array<Setting> = (listOf(source, clientId, port, login, logout, style, width, accent, background, hideWhenPaused,
            showArt, showTitle, showArtist, showProgress, showTime, showControls, showCustom) + buttons + clearBlocklist).toTypedArray()
    }

    /** Game exit: stop the PowerShell child (it also exits by itself when its stdin closes). */
    fun shutdown() = bridge.stop()

    private fun clearBlocklist() {
        blocklist.clear()
        saveBlocklist()
    }

    companion object {
        private const val PLAYING_POLL_TICKS = 40
        private const val IDLE_POLL_TICKS = 200
        private const val MAX_BLOCKLIST = 500
        private val SAMPLE = SpotifyPlayback(null, "Song Title", listOf("Artist"), emptyList(), "Album", null,
            215_000L, 83_000L, false, false, "off", 60, 0L, SpotifyPlayback.Source.WEB_API)

        @JvmField
        val INSTANCE = SpotifyModule(Settings())

        @JvmStatic
        fun clock(ms: Long): String {
            val seconds = (ms / 1000).coerceAtLeast(0)
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
