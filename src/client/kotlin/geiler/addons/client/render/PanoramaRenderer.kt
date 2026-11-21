package geiler.addons.client.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import geiler.addons.GeilerAddons
import geiler.addons.client.visual.PanoramaImage
import geiler.addons.client.visual.PanoramaMath
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPU side of Custom Main Menu. The desert is one full-screen raymarch pass into a reduced-size target,
 * one edge-smoothing pass into a second target, then one GUI blit of that; the photo is one GUI blit of
 * an uploaded texture. All calls run on
 * the render thread; only photo decoding runs on a worker.
 */
object PanoramaRenderer {
    private val PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(GeilerAddons.id("pipeline/panorama_desert"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("geileraddons", "core/panorama_desert"))
            .withBindGroupLayout(
                BindGroupLayout.builder()
                    .withSampler("NoiseSampler")
                    .withUniform("PanoramaParams", UniformType.UNIFORM_BUFFER)
                    .build(),
            )
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build(),
    )
    /** Edge smoothing over the finished desert, so silhouettes are not stair-stepped at any resolution. */
    private val FXAA_PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(GeilerAddons.id("pipeline/panorama_fxaa"))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("geileraddons", "core/panorama_fxaa"))
            .withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").build())
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build(),
    )
    private const val PHOTO_SCRIM = 0x30000000

    private var target: TextureTarget? = null
    private var smoothTarget: TextureTarget? = null
    private var noise: DynamicTexture? = null
    private var params: GpuBuffer? = null
    private val paramFloats = FloatArray(PanoramaMath.PARAM_FLOATS)
    private val paramBytes: ByteBuffer = ByteBuffer.allocateDirect(PanoramaMath.PARAM_FLOATS * 4).order(ByteOrder.nativeOrder())

    private var photo: DynamicTexture? = null
    private var photoWidth = 0
    private var photoHeight = 0
    private var photoLoad: CompletableFuture<PhotoResult>? = null
    private var photoGeneration = 0

    /** Last failure, latched so a broken shader falls back to vanilla instead of failing every frame. */
    var failure: String? = null
        private set
    var lastTargetWidth = 0
        private set
    var lastTargetHeight = 0
        private set
    var lastCameraHeight = 0f
        private set
    /** Camera height above the sand straight below it on the last desert frame. */
    var lastClearance = 0f
        private set
    var photoState = "not-loaded"
        private set

    private class PhotoResult(val image: NativeImage?, val width: Int, val height: Int, val reason: String, val generation: Int)

    /** True when the desert was drawn into [graphics]; false means draw the vanilla panorama instead. */
    fun drawDesert(
        graphics: GuiGraphicsExtractor, guiWidth: Int, guiHeight: Int, time: Float, scale: Float,
        look: PanoramaMath.DesertLook = PanoramaMath.DesertLook.DEFAULT,
    ): Boolean {
        if (failure != null) return false
        val client = Minecraft.getInstance()
        val fbWidth = maxOf(1, client.window.width)
        val fbHeight = maxOf(1, client.window.height)
        return try {
            val w = PanoramaMath.targetSize(fbWidth, scale)
            val h = PanoramaMath.targetSize(fbHeight, scale)
            val tgt = target ?: TextureTarget("GeilerAddons panorama", w, h, false, GpuFormat.RGBA8_UNORM).also { target = it }
            if (tgt.width != w || tgt.height != h) tgt.resize(w, h)
            val smooth = smoothTarget ?: TextureTarget("GeilerAddons panorama smooth", w, h, false, GpuFormat.RGBA8_UNORM).also { smoothTarget = it }
            if (smooth.width != w || smooth.height != h) smooth.resize(w, h)
            lastTargetWidth = w
            lastTargetHeight = h
            val noiseTexture = noise ?: createNoise().also { noise = it }
            val device = RenderSystem.getDevice()
            val buffer = params ?: device.createBuffer(
                { "GeilerAddons panorama params" },
                GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
                (PanoramaMath.PARAM_FLOATS * 4).toLong(),
            ).also { params = it }

            val theme = geiler.addons.client.gui.GuiTheme
            val camera = PanoramaMath.camera(time.toDouble(), look.style)
            lastCameraHeight = camera[5]
            lastClearance = camera[5] - PanoramaMath.terrainHeight(camera[0].toDouble(), camera[1].toDouble(), look.style).toFloat()
            PanoramaMath.writeParams(
                paramFloats, time, fbWidth.toFloat() / fbHeight, PanoramaMath.MARCH_STEPS, h,
                camera, theme.SWITCH_ON, theme.PANEL_TOP, theme.TEXT_PRIMARY, look,
            )
            paramBytes.clear()
            for (value in paramFloats) paramBytes.putFloat(value)
            paramBytes.flip()

            val encoder = device.createCommandEncoder()
            encoder.writeToBuffer(buffer.slice(), paramBytes)
            val timing = startGpuTiming()
            encoder.createRenderPass({ "GeilerAddons panorama desert" }, tgt.getColorTextureView()!!, Optional.empty()).use { pass ->
                if (timing != null) pass.writeTimestamp(timing, 0)
                pass.setPipeline(PIPELINE)
                pass.bindTexture("NoiseSampler", noiseTexture.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                pass.setUniform("PanoramaParams", buffer)
                pass.draw(3, 1, 0, 0)
            }
            encoder.createRenderPass({ "GeilerAddons panorama smoothing" }, smooth.getColorTextureView()!!, Optional.empty()).use { pass ->
                pass.setPipeline(FXAA_PIPELINE)
                pass.bindTexture("InSampler", tgt.getColorTextureView()!!, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR))
                pass.draw(3, 1, 0, 0)
                if (timing != null) pass.writeTimestamp(timing, 1)
            }
            // Render targets store the bottom row first, so the GUI samples them upside down.
            graphics.blit(
                smooth.getColorTextureView()!!, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                0, 0, guiWidth, guiHeight, 0f, 1f, 1f, 0f,
            )
            true
        } catch (error: RuntimeException) {
            failure = "${error.javaClass.simpleName}: ${error.message}".take(200)
            release()
            false
        }
    }

    /** Draws the photo with a slow drift; false while it is loading or when it could not be used. */
    fun drawPhoto(graphics: GuiGraphicsExtractor, guiWidth: Int, guiHeight: Int, path: Path, time: Float, animate: Boolean): Boolean {
        acceptLoadedPhoto()
        val texture = photo
        if (texture == null) {
            if (photoLoad == null && photoState == "not-loaded") startPhotoLoad(path)
            return false
        }
        val zoom = if (animate) 1.06f + 0.03f * sin(time * 0.05f) else 1.04f
        val panX = if (animate) sin(time * 0.031f) else 0f
        val panY = if (animate) 0.6f * cos(time * 0.023f) else 0f
        val uv = PanoramaMath.coverUv(photoWidth, photoHeight, guiWidth, guiHeight, zoom, panX, panY)
        graphics.blit(
            texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
            0, 0, guiWidth, guiHeight, uv[0], uv[1], uv[2], uv[3],
        )
        graphics.fill(0, 0, guiWidth, guiHeight, PHOTO_SCRIM)
        return true
    }

    /** True while a decode is still running, so the caller can keep showing the desert meanwhile. */
    fun photoLoading(): Boolean = photoLoad != null

    /** Forget the photo so the next title screen reads the file again. */
    fun reloadPhoto() {
        photoGeneration++
        photoLoad = null
        photo?.close()
        photo = null
        photoState = "not-loaded"
    }

    private fun startPhotoLoad(path: Path) {
        val generation = photoGeneration
        photoState = "loading"
        photoLoad = CompletableFuture.supplyAsync {
            val result = PanoramaImage.load(path)
            val pixels = result.pixels ?: return@supplyAsync PhotoResult(null, 0, 0, result.reason, generation)
            // NativeImage is off-heap memory, so it can be filled here; only the upload needs the render thread.
            val image = NativeImage(result.width, result.height, false)
            try {
                for (y in 0 until result.height) {
                    val row = y * result.width
                    for (x in 0 until result.width) image.setPixel(x, y, pixels[row + x])
                }
            } catch (error: RuntimeException) {
                image.close()
                return@supplyAsync PhotoResult(null, 0, 0, "unreadable", generation)
            }
            PhotoResult(image, result.width, result.height, "ok", generation)
        }
    }

    private fun acceptLoadedPhoto() {
        val pending = photoLoad ?: return
        if (!pending.isDone) return
        photoLoad = null
        val result = try {
            pending.join()
        } catch (error: RuntimeException) {
            photoState = "failed(unreadable)"
            return
        }
        if (result.generation != photoGeneration) {
            result.image?.close()
            return
        }
        val image = result.image
        if (image == null) {
            photoState = "failed(${result.reason})"
            return
        }
        try {
            photo = DynamicTexture({ "GeilerAddons panorama photo" }, image)
            photoWidth = result.width
            photoHeight = result.height
            photoState = "ok(${result.width}x${result.height})"
        } catch (error: RuntimeException) {
            image.close()
            photoState = "failed(upload)"
        }
    }

    /**
     * GPU time of the desert pass in milliseconds, sampled about once a second, or -1 when unknown.
     * One query pair is in flight at a time; its result is read frames later, so nothing stalls.
     */
    var gpuMillis = -1.0
        private set
    private var timingPool: GpuQueryPool? = null
    private var timingPending = false
    private var timingUnsupported = false
    private var lastTimingNanos = 0L

    private fun startGpuTiming(): GpuQueryPool? {
        if (timingUnsupported) return null
        return try {
            val pool = timingPool ?: RenderSystem.getDevice().createTimestampQueryPool(2).also { timingPool = it }
            if (timingPending) {
                val values = pool.getValues(0, 2)
                if (values[0].isPresent && values[1].isPresent) {
                    gpuMillis = (values[1].asLong - values[0].asLong) / 1e6
                    timingPending = false
                }
                return null
            }
            val now = System.nanoTime()
            if (now - lastTimingNanos < 1_000_000_000L) return null
            lastTimingNanos = now
            timingPending = true
            pool
        } catch (_: RuntimeException) {
            timingUnsupported = true
            null
        }
    }

    /** The same texels [PanoramaMath.terrainHeight] reads, so the camera flies over the sand the shader draws. */
    private fun createNoise(): DynamicTexture {
        val size = PanoramaMath.NOISE_SIZE
        val texels = PanoramaMath.noiseTexels()
        val image = NativeImage(size, size, false)
        for (y in 0 until size) {
            for (x in 0 until size) image.setPixel(x, y, texels[y * size + x])
        }
        return DynamicTexture({ "GeilerAddons panorama noise" }, image)
    }

    /** Frees the desert's GPU memory; the photo is kept unless [includePhoto]. */
    fun release(includePhoto: Boolean = false) {
        target?.destroyBuffers()
        target = null
        smoothTarget?.destroyBuffers()
        smoothTarget = null
        noise?.close()
        noise = null
        params?.close()
        params = null
        timingPool?.close()
        timingPool = null
        timingPending = false
        if (includePhoto) reloadPhoto()
    }

    fun clearFailure() {
        failure = null
    }

    fun hasGpuResources(): Boolean = target != null || smoothTarget != null || noise != null || params != null || photo != null
}
