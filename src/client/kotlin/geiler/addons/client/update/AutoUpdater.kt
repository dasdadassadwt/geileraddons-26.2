package geiler.addons.client.update

import com.google.gson.JsonArray
import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

/**
 * Opt-in install of a newer release: downloads the release's own jar for this Minecraft version, checks its SHA-256
 * and its `fabric.mod.json`, stages it next to the running jar as `geileraddons-<version>.jar.update` (never loadable,
 * so two mod jars never exist), and swaps it in when the game closes. Windows keeps the running jar locked, so a small
 * batch file waits for it and does the swap; elsewhere the swap is immediate. Runs on the update thread only.
 */
object AutoUpdater {
    private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
    private val DOWNLOAD_TIMEOUT = Duration.ofMinutes(3)
    private val WINDOWS = System.getProperty("os.name", "").lowercase().contains("win")

    /** Version staged or swapped in this session, for the chat notice; null when nothing was installed. */
    @Volatile
    var installedVersion: String? = null
        private set

    private var hookArmed = false

    /** Re-arms the swap for an update staged by an earlier session that could not finish it, e.g. a killed game. */
    fun resumePending(currentVersion: String) {
        try {
            val running = runningJar() ?: return
            Files.newDirectoryStream(running.parent).use { files ->
                for (file in files) {
                    val version = AutoUpdateRules.pendingVersion(file.fileName.toString()) ?: continue
                    if (!UpdateChecker.isNewer(version, currentVersion)) {
                        Files.deleteIfExists(file)
                        continue
                    }
                    val target = file.resolveSibling(file.fileName.toString().removeSuffix(".update"))
                    if (Files.exists(target)) continue
                    GeilerAddons.LOGGER.info("Auto update: finishing the staged update to v{} when the game closes", version)
                    swapOnExit(running, file, target)
                    installedVersion = version
                    return
                }
            }
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Auto update: could not look for a staged update", error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Auto update: could not look for a staged update", error)
        }
    }

    /** Downloads, verifies and stages [tag]'s jar from the release's [assets]; every refusal is logged and leaves the install alone. */
    fun install(assets: JsonArray?, tag: String) {
        try {
            val running = runningJar() ?: return refuse("the running mod is not a jar file (development environment)")
            val asset = AutoUpdateRules.pickAsset(parseAssets(assets), tag) ?: return refuse("the release has no verifiable jar for v$tag")
            val newJar = running.resolveSibling(asset.name)
            val staged = running.resolveSibling(asset.name.removeSuffix(".jar") + AutoUpdateRules.STAGED_SUFFIX)
            if (Files.exists(newJar) || Files.exists(staged)) return refuse("v$tag is already in the mods folder")
            if (WINDOWS && !(AutoUpdateRules.safePath(running.toString()) && AutoUpdateRules.safePath(staged.toString()) && AutoUpdateRules.safePath(newJar.toString()))) {
                return refuse("the mods folder path has characters a batch file cannot take safely")
            }
            val temp = Files.createTempFile(running.parent, "geileraddons-download-", ".tmp")
            try {
                GeilerAddons.LOGGER.info("Auto update: downloading {} ({} bytes)", asset.name, asset.size)
                val hash = download(asset, temp)
                if (!AutoUpdateRules.digestMatches(asset.digest, hash)) return refuse("the download's SHA-256 does not match the release")
                if (!manifestOk(temp, tag)) return refuse("the jar is not this mod at v$tag for Minecraft ${minecraftVersion()}")
                Files.move(temp, staged, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
            GeilerAddons.LOGGER.info("Auto update: staged v{}; it takes effect after the game restarts", tag)
            if (WINDOWS) swapOnExit(running, staged, newJar) else swapNow(running, staged, newJar)
            installedVersion = tag
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Auto update failed", error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Auto update failed", error)
        }
    }

    private fun refuse(reason: String) {
        GeilerAddons.LOGGER.info("Auto update skipped: {}", reason)
    }

    private fun parseAssets(assets: JsonArray?): List<AutoUpdateRules.Asset> = assets?.mapNotNull { element ->
        val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        fun text(key: String) = asset.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        AutoUpdateRules.Asset(text("name") ?: return@mapNotNull null, text("browser_download_url") ?: return@mapNotNull null,
            asset.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L, text("digest"))
    } ?: emptyList()

    private fun runningJar(): Path? {
        val paths = FabricLoader.getInstance().getModContainer(GeilerAddons.MOD_ID).map { it.origin.paths }.orElse(emptyList())
        val path = paths.singleOrNull() ?: return null
        return path.takeIf { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }?.toAbsolutePath()
    }

    private fun minecraftVersion(): String =
        FabricLoader.getInstance().getModContainer("minecraft").map { it.metadata.version.friendlyString }.orElse("")

    /** Streams the asset into [target] under a size cap and returns its SHA-256 as lowercase hex. */
    private fun download(asset: AutoUpdateRules.Asset, target: Path): String {
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build().use { client ->
            val request = HttpRequest.newBuilder(URI.create(asset.url)).timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", GeilerAddons.MOD_ID).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
                if (response.uri().scheme != "https" || !AutoUpdateRules.trustedHost(response.uri())) throw IOException("redirected to an untrusted host")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                Files.newOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = body.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > AutoUpdateRules.MAX_JAR_BYTES || total > asset.size) throw IOException("the download is larger than the release says")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
                if (total != asset.size) throw IOException("the download is smaller than the release says")
                return digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    private fun manifestOk(jar: Path, tag: String): Boolean = ZipFile(jar.toFile()).use { zip ->
        val entry = zip.getEntry("fabric.mod.json") ?: return false
        if (entry.size !in 1..MAX_MANIFEST_BYTES) return false
        val text = String(zip.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES.toInt()) }, StandardCharsets.UTF_8)
        AutoUpdateRules.manifestMatches(text, GeilerAddons.MOD_ID, tag, minecraftVersion())
    }

    /** Elsewhere than Windows a running jar can be deleted, so the swap happens now; the old jar goes first so no two jars exist. */
    private fun swapNow(old: Path, staged: Path, newJar: Path) {
        Files.delete(old)
        Files.move(staged, newJar)
    }

    /** Windows: a batch file started when the game exits waits for the old jar to unlock, deletes it and moves the staged jar in. */
    private fun swapOnExit(old: Path, staged: Path, newJar: Path) {
        if (!WINDOWS) return swapNow(old, staged, newJar)
        if (hookArmed) return
        val script = Files.createTempFile("geileraddons-update-", ".cmd")
        Files.writeString(script, AutoUpdateRules.swapScript(old.toString(), staged.toString(), newJar.toString()), StandardCharsets.US_ASCII)
        hookArmed = true
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                ProcessBuilder("cmd.exe", "/c", "start", "", "/min", script.toString()).start()
            } catch (_: IOException) {
                // The staged file stays; the next launch finds it and tries again.
            }
        }, "geileraddons-update-swap"))
    }

    private const val MAX_MANIFEST_BYTES = 262_144L
}
