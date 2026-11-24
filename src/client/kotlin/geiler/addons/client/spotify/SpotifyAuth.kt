package geiler.addons.client.spotify

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spotify login with Authorization Code + PKCE: no client secret is stored. The user's own app Client ID is required
 * (Development Mode apps allow 5 users, so the mod cannot ship a shared one). The browser returns to a one-shot
 * loopback server on `http://127.0.0.1:<port>/callback`. Tokens live in `config/geileraddons/spotify-token.json` and
 * are never logged.
 */
object SpotifyAuth {
    const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
        "user-library-read user-library-modify"
    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"
    private const val LOGIN_TIMEOUT_SECONDS = 300L

    class Token {
        @JvmField var access: String = ""
        @JvmField var refresh: String = ""
        @JvmField var expiresAtMs: Long = 0L
        @JvmField var clientId: String = ""
    }

    @Volatile private var token: Token? = null
    @Volatile private var loaded = false
    @Volatile var status: String = "not logged in"
        private set
    private var server: HttpServer? = null
    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }
    private val random = SecureRandom()

    fun loggedIn(clientId: String): Boolean {
        load()
        val current = token ?: return false
        return current.refresh.isNotBlank() && current.clientId == clientId
    }

    // ---- PKCE ----

    @JvmStatic
    fun newVerifier(): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    @JvmStatic
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    @JvmStatic
    fun redirectUri(port: Int): String = "http://127.0.0.1:$port/callback"

    @JvmStatic
    fun authorizeUrl(clientId: String, port: Int, verifier: String, state: String): String =
        AUTHORIZE + "?response_type=code&client_id=" + enc(clientId) + "&scope=" + enc(SCOPES) +
            "&redirect_uri=" + enc(redirectUri(port)) + "&code_challenge_method=S256&code_challenge=" + enc(challenge(verifier)) +
            "&state=" + enc(state)

    /** Query parameters of a callback request, decoded. */
    @JvmStatic
    fun query(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else URLDecoder.decode(it.substring(0, eq), StandardCharsets.UTF_8) to
                URLDecoder.decode(it.substring(eq + 1), StandardCharsets.UTF_8)
        }.toMap()
    }

    // ---- login ----

    /** Opens the browser login; [opener] opens the URL. Returns false (with [status]) when it cannot start. */
    @Synchronized
    fun beginLogin(clientId: String, port: Int, opener: (String) -> Unit): Boolean {
        if (clientId.isBlank()) {
            status = "enter your Spotify app Client ID first"
            return false
        }
        stopServer()
        val verifier = newVerifier()
        val state = newVerifier().take(24)
        val created = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        } catch (e: Exception) {
            status = "port $port is busy; pick another redirect port"
            return false
        }
        created.executor = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-spotify-login").also { t -> t.isDaemon = true } }
        created.createContext("/callback") { exchange ->
            val params = query(exchange.requestURI.rawQuery)
            val ok = params["state"] == state && params["code"] != null
            val message = when {
                params["error"] != null -> "Spotify login was cancelled (${params["error"]}). You can close this tab."
                !ok -> "This login link is not the one GeilerAddons started. You can close this tab."
                exchangeCode(clientId, port, params.getValue("code"), verifier) -> "GeilerAddons is connected to Spotify. You can close this tab."
                else -> "Spotify did not accept the login ($status). You can close this tab."
            }
            val body = "<html><body style=\"font-family:sans-serif\"><p>$message</p></body></html>".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            Thread { stopServer() }.start()
        }
        created.start()
        server = created
        status = "waiting for the browser login"
        Executors.newSingleThreadScheduledExecutor { Thread(it, "geileraddons-spotify-login-timeout").also { t -> t.isDaemon = true } }
            .schedule({ if (server === created) { stopServer(); status = "login timed out" } }, LOGIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        opener(authorizeUrl(clientId, port, verifier, state))
        return true
    }

    @Synchronized
    private fun stopServer() {
        server?.stop(0)
        server = null
    }

    private fun exchangeCode(clientId: String, port: Int, code: String, verifier: String): Boolean = tokenRequest(clientId,
        "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirectUri(port))}&client_id=${enc(clientId)}&code_verifier=${enc(verifier)}",
        null)

    /** A usable access token, refreshed when it is within a minute of expiring; null when logged out. Blocking. */
    fun accessToken(clientId: String): String? {
        load()
        val current = token ?: return null
        if (current.clientId != clientId || current.refresh.isBlank()) return null
        if (System.currentTimeMillis() < current.expiresAtMs - 60_000L) return current.access
        return if (refresh(clientId)) token?.access else null
    }

    /** Forces a refresh (after a 401). Blocking. */
    fun refresh(clientId: String): Boolean {
        val current = token ?: return false
        return tokenRequest(clientId, "grant_type=refresh_token&refresh_token=${enc(current.refresh)}&client_id=${enc(clientId)}", current.refresh)
    }

    private fun tokenRequest(clientId: String, form: String, previousRefresh: String?): Boolean {
        return try {
            val request = HttpRequest.newBuilder(URI.create(TOKEN)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                status = "token request failed (HTTP ${response.statusCode()})"
                if (response.statusCode() == 400 && previousRefresh != null) logout()
                return false
            }
            val json = JsonParser.parseString(response.body()).asJsonObject
            val next = Token()
            next.access = json.get("access_token").asString
            next.refresh = json.get("refresh_token")?.asString ?: previousRefresh ?: ""
            next.expiresAtMs = System.currentTimeMillis() + (json.get("expires_in")?.asLong ?: 3600L) * 1000L
            next.clientId = clientId
            token = next
            save()
            status = "logged in"
            true
        } catch (e: Exception) {
            status = "token request failed (${e.javaClass.simpleName})"
            false
        }
    }

    fun logout() {
        token = null
        status = "not logged in"
        path()?.let { ClientJsonFile.writeAsync(it, "{}") }
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val saved = path()?.let { ClientJsonFile.read(it, Token::class.java) } ?: return
        if (saved.refresh.isNullOrBlank()) return
        token = saved
        status = "logged in"
    }

    private fun save() {
        val current = token ?: return
        path()?.let { ClientJsonFile.writeAsync(it, ClientJsonFile.GSON.toJson(current)) }
    }

    private fun path(): Path? = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")?.resolve("spotify-token.json")
    } catch (_: NullPointerException) {
        null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
