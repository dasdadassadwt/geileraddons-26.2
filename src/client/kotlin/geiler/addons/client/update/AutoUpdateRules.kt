package geiler.addons.client.update

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.util.Locale

/** Pure rules of the opt-in auto update: which release asset to take, how to verify it, and how it is swapped in. */
object AutoUpdateRules {
    const val MAX_JAR_BYTES = 64L * 1024 * 1024
    const val STAGED_SUFFIX = ".jar.update"

    class Asset(val name: String, val url: String, val size: Long, val digest: String?)

    private val HOSTS = listOf("github.com", "githubusercontent.com")
    private val STAGED = Regex("^geileraddons-([0-9A-Za-z.+-]+)\\.jar\\.update$")
    private val UNSAFE_PATH = Regex("[%\"^&|<>!]")

    /** The release's own mod jar (not the sources jar), or null unless it can be verified: https from GitHub, a sha256 digest, a sane size. */
    @JvmStatic
    fun pickAsset(assets: List<Asset>, version: String): Asset? = assets.firstOrNull { asset ->
        asset.name == "geileraddons-$version.jar" && asset.digest != null && asset.digest.startsWith("sha256:", ignoreCase = true) &&
            asset.size in 1..MAX_JAR_BYTES && trustedUrl(asset.url)
    }

    private fun trustedUrl(url: String): Boolean = try {
        val uri = URI.create(url)
        uri.scheme == "https" && trustedHost(uri)
    } catch (_: IllegalArgumentException) {
        false
    }

    /** github.com or a subdomain of it or of githubusercontent.com, matched as whole labels. */
    @JvmStatic
    fun trustedHost(uri: URI): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    @JvmStatic
    fun digestMatches(expected: String?, actualHex: String): Boolean {
        val wanted = expected?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7) ?: return false
        return wanted.equals(actualHex, ignoreCase = true)
    }

    /**
     * Whether `fabric.mod.json` is this mod at the release's [version] and depends on the running Minecraft release
     * (its major.minor, so 26.2.1 matches `~26.2`).
     */
    @JvmStatic
    fun manifestMatches(json: String, modId: String, version: String, minecraft: String): Boolean = try {
        val root = JsonParser.parseString(json).asJsonObject
        val depends = root.getAsJsonObject("depends")?.get("minecraft")
        root.get("id")?.asString == modId && root.get("version")?.asString == version && depends != null &&
            names(depends).flatMap { it.split(Regex("\\s+|\\|\\|")) }.any { allows(it, releaseOf(minecraft)) }
    } catch (_: RuntimeException) {
        false
    }

    private fun names(element: JsonElement): List<String> =
        if (element.isJsonArray) element.asJsonArray.map { it.asString } else listOf(element.asString)

    /** A version token (`~26.2`, `>=26.2`, `26.2.x`) that names [release]; upper bounds such as `<26.3` never do. */
    private fun allows(token: String, release: String): Boolean {
        if (token.isEmpty() || token.startsWith("<")) return false
        val bare = token.trimStart('~', '^', '=', '>')
        return bare.startsWith(release) && !bare.getOrElse(release.length) { '.' }.isDigit()
    }

    private fun releaseOf(minecraft: String): String = minecraft.split('.').take(2).joinToString(".")

    /** The version in a staged `geileraddons-<version>.jar.update` file name, or null for any other name. */
    @JvmStatic
    fun pendingVersion(fileName: String): String? = STAGED.matchEntire(fileName)?.groupValues?.get(1)

    /** Paths go into a batch file, so anything cmd treats specially is refused. */
    @JvmStatic
    fun safePath(path: String): Boolean = path.isNotEmpty() && !UNSAFE_PATH.containsMatchIn(path)

    /** A Windows batch file that waits for the old jar to unlock, deletes it, and moves the staged jar into place. */
    @JvmStatic
    fun swapScript(oldJar: String, staged: String, newJar: String): String = listOf(
        "@echo off",
        "set /a tries=0",
        ":retry",
        "del /f /q \"$oldJar\" >nul 2>&1",
        "if exist \"$oldJar\" (",
        "  set /a tries+=1",
        "  if %tries% GEQ 60 exit /b 1",
        "  ping -n 3 127.0.0.1 >nul",
        "  goto retry",
        ")",
        "move /y \"$staged\" \"$newJar\" >nul",
        "del \"%~f0\"",
        "",
    ).joinToString("\r\n")
}
