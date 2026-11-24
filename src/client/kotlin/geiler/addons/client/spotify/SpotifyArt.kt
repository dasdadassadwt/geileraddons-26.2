package geiler.addons.client.spotify

import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.GeilerAddons
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * Album covers as 64×64 GUI textures, at most [MAX_TEXTURES] kept. Spotify serves JPEG, which the game's PNG-only
 * `NativeImage.read` cannot decode, so images go through ImageIO and are copied pixel by pixel. Loads run off-thread;
 * textures are registered on the client thread.
 */
object SpotifyArt {
    private const val SIZE = 64
    private const val MAX_TEXTURES = 8
    private const val MAX_BYTES = 2_000_000

    private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "geileraddons-spotify-art").also { t -> t.isDaemon = true } }
    private val textures = LinkedHashMap<String, Identifier>()
    private val pending = HashSet<String>()

    /** The texture for [key] if loaded; otherwise starts loading it from [url] and returns null. Client thread. */
    fun fromUrl(key: String, url: String): Identifier? = textures[key] ?: run {
        if (pending.add(key)) worker.execute {
            val bytes = try {
                val response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray())
                if (response.statusCode() == 200 && response.body().size <= MAX_BYTES) response.body() else null
            } catch (_: Exception) {
                null
            }
            publish(key, bytes)
        }
        null
    }

    /** Same for raw image bytes (the Windows thumbnail). */
    fun fromBytes(key: String, bytes: ByteArray): Identifier? = textures[key] ?: run {
        if (pending.add(key)) worker.execute { publish(key, bytes) }
        null
    }

    private fun publish(key: String, bytes: ByteArray?) {
        val image = bytes?.let { decode(it) }
        Minecraft.getInstance().execute {
            pending.remove(key)
            if (image == null) return@execute
            val id = GeilerAddons.id("spotify_art/${Integer.toHexString(key.hashCode())}_${textures.size}")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "GeilerAddons Spotify cover" }, image))
            textures[key] = id
            while (textures.size > MAX_TEXTURES) {
                val oldest = textures.keys.first()
                textures.remove(oldest)?.let { Minecraft.getInstance().textureManager.release(it) }
            }
        }
    }

    /** Decodes JPEG/PNG and scales to 64×64 (nearest sample; the HUD draws it at most 64 px). */
    @JvmStatic
    fun decode(bytes: ByteArray): NativeImage? {
        val source = try { ImageIO.read(ByteArrayInputStream(bytes)) } catch (_: Exception) { null } ?: return null
        if (source.width <= 0 || source.height <= 0) return null
        val image = NativeImage(SIZE, SIZE, false)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val argb = source.getRGB(x * source.width / SIZE, y * source.height / SIZE)
            image.setPixel(x, y, argb or (0xFF shl 24))
        }
        return image
    }
}
