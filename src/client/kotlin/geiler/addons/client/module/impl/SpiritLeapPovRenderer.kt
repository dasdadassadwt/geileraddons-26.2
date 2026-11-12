package geiler.addons.client.module.impl

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.resource.GraphicsResourceAllocator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuSampler
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.serialization.JsonOps
import geiler.addons.client.dungeon.odin.SpiritLeapLayout
import geiler.addons.client.dungeon.odin.SpiritLeapRoster
import geiler.addons.client.gui.access.GuiGraphicsExtractorScissorAccess
import geiler.addons.client.mixin.FabricRenderingContextSupport
import geiler.addons.client.mixin.GameRenderStateSpiritLeapAccessor
import geiler.addons.client.mixin.LevelRenderStateSpiritLeapAccessor
import geiler.addons.client.mixin.LevelExtractorSpiritLeapContextAccessor
import geiler.addons.client.mixin.Matrix4fStackAccess
import geiler.addons.client.mixin.SkyblockerSpiritLeapSupport
import geiler.addons.client.mixin.SodiumWorldRendererSpiritLeapSupport
import geiler.addons.client.render.CameraSpiritLeapAccess
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.impl.client.rendering.level.LevelExtractionContextImpl
import net.minecraft.client.Camera
import net.minecraft.client.CameraType
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.TextureFilteringMethod
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.GlobalSettingsUniform
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostChainConfig
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.client.renderer.fog.FogData
import net.minecraft.client.renderer.fog.FogRenderer
import net.minecraft.client.renderer.state.GameRenderState
import net.minecraft.client.renderer.state.LightmapRenderState
import net.minecraft.client.renderer.state.OptionsRenderState
import net.minecraft.client.renderer.state.WindowRenderState
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.state.level.LevelRenderState
import net.minecraft.client.resources.model.EquipmentAssetManager
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.Mth
import net.minecraft.util.StrictJsonParser
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3x2f
import org.joml.Matrix4f
import org.joml.Matrix4fStack
import org.joml.Vector3f
import org.joml.Vector4f
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Four cached teammate views rendered sequentially through the active world renderer. */
object SpiritLeapPovRenderer {
    private val CAMERA_PANEL_PIPELINE: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("geileraddons", "pipeline/spirit_leap_camera"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("geileraddons", "core/spirit_leap_camera"))
            .withColorTargetState(
                ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)
            )
            .build(),
    )

    const val FEED_COUNT = 4
    const val VIEW_NEIGHBORHOOD_CHUNK_COUNT = 9
    const val MAX_UPDATES_PER_FEED_PER_SECOND = 4
    const val PROBE_MINECRAFT_VERSION = "26.2"
    const val PROBE_FABRIC_RENDERING_VERSION = FabricRenderingContextSupport.SUPPORTED_VERSION
    const val PROBE_SODIUM_VERSION = SodiumWorldRendererSpiritLeapSupport.SUPPORTED_VERSION
    const val PROBE_NVIDIUM_VERSION = "0.4.4-beta6-26.2"
    private const val NVIDIUM_CAPABILITIES_VERIFIED_FOR_26_2 = true
    const val FIXED_CAMERA_PROBE_PROPERTY = "geileraddons.spiritLeapPovProbe"
    const val FIXED_CAMERA_PROBE_VALUE = "fixed-camera"
    const val FIXED_CAMERA_TARGET_NAME = "fixed-camera-local"
    const val SODIUM_TERRAIN_RENDER_PASS =
        "net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass"
    private val UNVERIFIED_RENDER_MODIFIERS = listOf("iris", "canvas", "distanthorizons")
    private const val MAX_DIAGNOSTIC_VERSION_CHARS = 48
    private const val MAX_TOTAL_FEED_TARGET_PIXELS = 10_485_760L
    const val MAX_CACHED_FRAME_AGE_NANOS = 2_000_000_000L
    private const val CHUNK_PROBE_INTERVAL_NANOS = 250_000_000L
    private const val MIN_SELECTION_RETRY_NANOS = 500_000_000L
    private const val DEFERRED_TARGET_CLEANUP_RETRY_NANOS = 1_000_000_000L
    private const val PIXEL_SAMPLE_INTERVAL_NANOS = 1_000_000_000L
    private const val PIXEL_SAMPLE_SIZE = 16
    private const val PIXEL_SAMPLE_BYTES = PIXEL_SAMPLE_SIZE * PIXEL_SAMPLE_SIZE * 4
    private val TRANSPARENCY_CHAIN = Identifier.withDefaultNamespace("transparency")
    private val TRANSPARENCY_CONFIG = Identifier.withDefaultNamespace("post_effect/transparency.json")
    private val RENDER_STATE: RenderStateAccess = object : RenderStateAccess {
        override fun outputColorTextureOverride(): Any? = RenderSystem.outputColorTextureOverride
        override fun outputDepthTextureOverride(): Any? = RenderSystem.outputDepthTextureOverride
        override fun globalSettingsUniform(): Any? = RenderSystem.getGlobalSettingsUniform()
        override fun shaderFog(): Any? = RenderSystem.getShaderFog()
        override fun projectionMatrixBuffer(): Any? = RenderSystem.getProjectionMatrixBuffer()
        override fun projectionType(): Any? = RenderSystem.getProjectionType()

        override fun outputTextureOverrides(color: Any?, depth: Any?) {
            RenderSystem.outputColorTextureOverride = color as GpuTextureView?
            RenderSystem.outputDepthTextureOverride = depth as GpuTextureView?
        }

        override fun globalSettingsUniform(uniform: Any?) {
            RenderSystem.setGlobalSettingsUniform(Optional.ofNullable(uniform as GpuBuffer?).orElse(null))
        }

        override fun shaderFog(fog: Any?) {
            RenderSystem.setShaderFog(Optional.ofNullable(fog as GpuBufferSlice?).orElse(null))
        }

        override fun projection(buffer: Any?, type: Any?) {
            RenderSystem.setProjectionMatrix(
                Optional.ofNullable(buffer as GpuBufferSlice?).orElse(null), type as ProjectionType,
            )
        }

        override fun modelViewMatrix(): Matrix4f = RenderSystem.getModelViewMatrixCopy()

        override fun modelViewStackDepth(): Int =
            (RenderSystem.getModelViewStack() as Matrix4fStackAccess).`geiler$getStackDepth`()

        override fun modelViewStack(depth: Int, matrix: Matrix4f) {
            val stack = RenderSystem.getModelViewStack()
            val access = stack as Matrix4fStackAccess
            access.`geiler$setStackDepth`(depth)
            stack.set(matrix)
        }
    }

    private val renderState = GameRenderState()
    private val modelViewSnapshot = Matrix4f()
    private val blockTint = Vector3f()
    private val skyColor = Vector3f()
    private val ambientColor = Vector3f()
    private val nightVisionColor = Vector3f()
    private var renderer: LevelRenderer? = null
    private var fogRenderer: FogRenderer? = null
    private val feeds: Array<FeedState> = Array(FEED_COUNT) { FeedState(it) }
    private var scratchTarget: RenderTarget? = null
    private var povTranslucentTarget: RenderTarget? = null
    private var activeRenderTarget: RenderTarget? = null
    private var renderingFeed: FeedState? = null
    private var scopedGameRenderState: GameRenderState? = null
    private var activePovPass = false
    private var clientTickSequence = 0L
    private var attachedLevel: ClientLevel? = null
    private var transparencyChain: PostChain? = null
    private var transparencyProjection: Projection? = null
    private var transparencyProjectionBuffer: ProjectionMatrixBuffer? = null
    private var povProjection: Projection? = null
    private var povProjectionBuffer: ProjectionMatrixBuffer? = null
    private var povGlobalSettingsUniform: GlobalSettingsUniform? = null
    private val feedScheduler = FeedScheduler()
    private val renderAttempt = SpiritLeapPovRenderAttempt<GpuBufferSlice>()
    private val pendingRenderStateRestore = PendingRenderStateRestore()
    private val renderStateRecoveryState = RenderStateRecoveryState()
    private val pendingResourceReload = PendingResourceReload<ResourceManager>()
    private val lifecycleGeneration = AtomicLong()
    private val closeRequestGeneration = AtomicLong()
    private var povToggleStateObserved = false
    private var previousModuleEnabled = false
    private var previousPovsEnabled = false
    private var lifecycleResetPending = false
    private var rendererCleanupFailed = false
    private var rendererProfile: RendererProfile? = null
    private var sodiumAdapterVerificationAttempted = false
    private val sodiumTerrainPassAccessor = SodiumTerrainPassAccessor()
    private var sodiumMainTargetRouteChecks = 0L
    private var sodiumTranslucentTargetRouteChecks = 0L
    private var sodiumMainTargetRoutesVerified = 0L
    private var sodiumTranslucentTargetRoutesVerified = 0L
    private var sodiumTargetRouteStatus = "not-checked"
    private var pixelSampleCopyPending = false
    private var pendingPixelSample: PendingPixelSample? = null
    private val povSessionState = PovSessionState()
    private val guiFramePublication = GuiFramePublication()
    private var pendingPublicationTarget: RenderTarget? = null
    private var probeFailureReason = "none"
    private var probeFailureStage = "none"
    private var probeFailureTopSite = "none"
    private var probeFailureDiagnosticRecorded = false
    private var feedTargetCleanupPending = false
    private var clearCachedFramesOnCleanup = false
    private var rendererCleanupPending = false
    private var feedTargetCleanupDiagnosticReported = false
    private var feedTargetCleanupReason = "none"
    private var lastDeferredTargetCleanupTryNanos = 0L
    @Volatile private var lastPendingStateRestoreTryNanos = 0L
    private var pendingRenderStateRestoreContext: RenderStateRestoreContext? = null
    private var loadedPlayerLookupLevel: ClientLevel? = null
    private var loadedPlayerLookupTick = Long.MIN_VALUE
    private var loadedPlayersByName: Map<String, Player> = java.util.Map.of<String, Player>()
    private var loadedPlayerLookupBuilds = 0L
    private var loadedPlayerLookupSize = 0
    private var warmupQuadrant = -1
    private var status = "idle(reason=menu-closed)"

    @JvmStatic
    fun isFixedCameraProbeRequested(value: String?): Boolean = FIXED_CAMERA_PROBE_VALUE == value

    @JvmStatic
    fun fixedCameraProbeEnabled(): Boolean = try {
        isFixedCameraProbeRequested(System.getProperty(FIXED_CAMERA_PROBE_PROPERTY))
    } catch (_: SecurityException) {
        false
    }

    @JvmStatic
    fun shouldRenderFixedCameraProbeQuadrant(probeEnabled: Boolean, quadrant: Int): Boolean =
        !probeEnabled || quadrant == 0

    @JvmStatic
    fun shouldDisplayFixedCameraProbeFrame(
        probeEnabled: Boolean,
        feedIsProbe: Boolean,
        quadrant: Int,
        cachedTargetName: String?,
    ): Boolean = probeEnabled && feedIsProbe && quadrant == 0 && FIXED_CAMERA_TARGET_NAME == cachedTargetName

    @JvmStatic
    fun fixedCameraProbeDiagnosticState(requested: Boolean, q0ProbeAssigned: Boolean): String {
        if (!requested) return "off"
        return if (q0ProbeAssigned) "fixed-camera-q0-yaw90-feed-assigned" else "fixed-camera-q0-yaw90-requested"
    }

    @JvmStatic
    fun diagnosticMaxFeeds(fixedCameraProbeRequested: Boolean, compatibleMaxFeeds: Int): Int {
        val compatibleFeedLimit = maxOf(0, minOf(FEED_COUNT, compatibleMaxFeeds))
        return if (fixedCameraProbeRequested) minOf(1, compatibleFeedLimit) else compatibleFeedLimit
    }

    /** Advances each owned camera once per client tick, independently of the staggered render cadence. */
    fun tickCameras(client: Minecraft?) {
        if (client == null || !client.isSameThread) return
        clientTickSequence = if (clientTickSequence == Long.MAX_VALUE) 0L else clientTickSequence + 1L
        val active = isActive(client) && client.level != null
        for (feed in feeds) {
            val player = feed.player
            if (!active || feed.targetName == null || player == null || !player.isAlive || player.level() !== client.level) {
                resetFeedCamera(feed)
                continue
            }
            prepareFeedCamera(feed, client.level, player)
            feed.cameraHistory.advance(clientTickSequence) { feed.camera.tick() }
        }
    }

    private fun prepareFeedCamera(feed: FeedState, level: ClientLevel?, player: Player?) {
        if (level == null || player == null) {
            feed.cameraHistory.bind(level, player, Runnable {})
            return
        }
        feed.cameraHistory.bind(level, player) {
            feed.camera.setLevel(null)
            clearCameraEntity(feed.camera)
            feed.camera.reset()
            feed.camera.setLevel(level)
            feed.camera.setEntity(player)
            (feed.camera as CameraSpiritLeapAccess).`geiler$primeEyeHeightHistory`(player.eyeHeight)
        }
    }

    private fun resetFeedCamera(feed: FeedState) {
        feed.cameraHistory.clear {
            feed.camera.setLevel(null)
            clearCameraEntity(feed.camera)
            feed.camera.reset()
        }
    }

    /** Preserve the Java renderer's explicit null clear despite Camera's non-null Kotlin annotation. */
    private fun clearCameraEntity(camera: Camera) {
        camera.setEntity(Optional.empty<net.minecraft.world.entity.Entity>().orElse(null))
    }

    private fun resetAllFeedCameras() {
        for (feed in feeds) resetFeedCamera(feed)
    }

    fun ownsRenderer(candidate: LevelRenderer?): Boolean = activePovPass && renderer != null && renderer === candidate

    fun targetFor(candidate: LevelRenderer?): RenderTarget? {
        if (!ownsRenderer(candidate)) return null
        val mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val routedTarget = activeRenderTarget ?: mainTarget
        val feed = renderingFeed
        if (feed != null) {
            val route = renderTargetRouteState(routedTarget, scratchTarget, mainTarget)
            feed.lastLevelTargetRoute = route
            feed.levelTargetRouteObserved = true
            feed.levelTargetRouteMismatch = feed.levelTargetRouteMismatch || route != "private-scratch"
            if (shouldAbortPrivateTargetRoute(true, routedTarget, scratchTarget, mainTarget)) {
                feed.stage = "target-routing"
                feed.reason = route
                throw IllegalStateException("private-level-target-route-$route")
            }
        }
        return activeRenderTarget
    }

    fun clearTargetFor(candidate: LevelRenderer?, mainTarget: RenderTarget): RenderTarget {
        val privateRenderer = ownsRenderer(candidate)
        val privateTarget = if (privateRenderer) activeRenderTarget else null
        val routedTarget = if (shouldRoutePrivateClearTarget(privateRenderer, privateTarget != null)) privateTarget else mainTarget
        val feed = if (privateRenderer) renderingFeed else null
        if (feed != null) {
            val route = renderTargetRouteState(routedTarget, scratchTarget, mainTarget)
            feed.lastFrameGraphClearRoute = route
            feed.frameGraphClearRouteObserved = true
            feed.frameGraphClearRouteMismatch = feed.frameGraphClearRouteMismatch || route != "private-scratch"
            if (shouldAbortPrivateTargetRoute(true, routedTarget, scratchTarget, mainTarget)) {
                feed.stage = "target-routing"
                feed.reason = route
                throw IllegalStateException("private-frame-clear-route-$route")
            }
        }
        return routedTarget!!
    }

    fun isPrivateRenderTargetActive(): Boolean = activePovPass && activeRenderTarget != null

    fun noteSodiumTerrainTargetRoute(translucentPass: Boolean, routedTarget: RenderTarget?) {
        if (!activePovPass || activeRenderTarget == null) return
        val privateTarget = sodiumTerrainTarget(translucentPass)
        val mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val verified = isPrivateTargetRoute(routedTarget, privateTarget, mainTarget)
        val activeFeed = activeFeed()
        if (translucentPass) {
            sodiumTranslucentTargetRouteChecks = incrementSaturated(sodiumTranslucentTargetRouteChecks)
            if (verified) sodiumTranslucentTargetRoutesVerified = incrementSaturated(sodiumTranslucentTargetRoutesVerified)
            if (activeFeed != null) {
                activeFeed.sodiumTranslucentTargetRouteObserved = true
                activeFeed.sodiumTranslucentTargetRouteVerified = verified
            }
        } else {
            sodiumMainTargetRouteChecks = incrementSaturated(sodiumMainTargetRouteChecks)
            if (verified) sodiumMainTargetRoutesVerified = incrementSaturated(sodiumMainTargetRoutesVerified)
            if (activeFeed != null) {
                activeFeed.sodiumMainTargetRouteObserved = true
                activeFeed.sodiumMainTargetRouteVerified = verified
            }
        }
    }

    fun routeLayerGroupOutput(group: ChunkSectionLayerGroup, originalTarget: RenderTarget): RenderTarget {
        if (!activePovPass || activeRenderTarget == null) return originalTarget
        val translucent = group === ChunkSectionLayerGroup.TRANSLUCENT
        val selection = layerGroupTargetSelection(
            true, translucent, Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency(),
        )
        val privateTarget = if (selection == SodiumTerrainTargetSelection.PRIVATE_TRANSLUCENT) povTranslucentTarget else activeRenderTarget
        val mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val feed = activeFeed()
        val verified = isPrivateTargetRoute(privateTarget, privateTarget, mainTarget)
        if (feed != null) {
            if (translucent) {
                feed.layerGroupTranslucentObserved = true
                feed.layerGroupTranslucentVerified = verified
            } else {
                feed.layerGroupOpaqueObserved = true
                feed.layerGroupOpaqueVerified = verified
            }
        }
        if (!verified) {
            if (feed != null) {
                feed.stage = "terrain-routing"
                feed.reason = if (translucent) "private-translucent-target-unavailable" else "private-layer-target-unavailable"
            }
            throw IllegalStateException(
                if (translucent) "private-translucent-target-unavailable" else "private-layer-target-unavailable",
            )
        }
        return privateTarget!!
    }

    fun noteVanillaTerrainBatch(group: ChunkSectionLayerGroup) {
        val feed = activeFeed()
        if (feed != null) feed.vanillaTerrainBatches = incrementSaturated(feed.vanillaTerrainBatches)
    }

    fun noteSodiumLayerCompleted(visibleSections: Int) {
        val feed = activeFeed() ?: return
        feed.sodiumLayerCalls = incrementSaturated(feed.sodiumLayerCalls)
        feed.visibleTerrainSections = maxOf(feed.visibleTerrainSections, maxOf(0, visibleSections))
    }

    fun noteNvidiumTerrainPassCompleted(translucent: Boolean) {
        val feed = activeFeed() ?: return
        if (translucent) feed.nvidiumTranslucentPasses = incrementSaturated(feed.nvidiumTranslucentPasses)
        else feed.nvidiumWorldPasses = incrementSaturated(feed.nvidiumWorldPasses)
    }

    private fun activeFeed(): FeedState? =
        if (activePovPass && activeRenderTarget != null && activeRenderTarget === scratchTarget) renderingFeed else null

    fun sodiumTerrainTarget(translucentPass: Boolean): RenderTarget? {
        if (!activePovPass || activeRenderTarget == null) return null
        val selection = sodiumTerrainTargetSelection(
            true, translucentPass, Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency(),
        )
        if (selection == SodiumTerrainTargetSelection.PRIVATE_TRANSLUCENT) return povTranslucentTarget
        return if (selection == SodiumTerrainTargetSelection.FEED) activeRenderTarget else null
    }

    /** Uses only the accessor validated by exact-version Sodium preflight while a private render is active. */
    fun isSodiumTranslucentPass(terrainPass: Any?): Boolean {
        if (!activePovPass || activeRenderTarget == null) throw IllegalStateException("sodium-private-render-not-active")
        return sodiumTerrainPassAccessor.isTranslucent(terrainPass)
    }

    fun isSodiumTranslucentBranch(terrainPass: Any?, shaderTransparency: Boolean): Boolean {
        if (!activePovPass || activeRenderTarget == null) throw IllegalStateException("sodium-private-render-not-active")
        return shaderTransparency && isSodiumTranslucentPass(terrainPass)
    }

    fun isPovCamera(candidate: Camera?): Boolean {
        if (candidate == null) return false
        for (feed in feeds) if (feed.camera === candidate && feed.cameraHistory.isBound()) return true
        return false
    }

    @JvmStatic fun shouldApplyFreeLook(povCamera: Boolean): Boolean = !povCamera

    @JvmStatic
    fun cameraTypeFor(povCamera: Boolean, configured: CameraType): CameraType =
        if (povCamera) CameraType.FIRST_PERSON else configured

    @JvmStatic
    fun isToggleActivation(wasEnabled: Boolean, wasPovsEnabled: Boolean, enabled: Boolean, povsEnabled: Boolean): Boolean =
        enabled && povsEnabled && (!wasEnabled || !wasPovsEnabled)

    @JvmStatic
    fun shouldPublishCompletedFrame(privateRenderSucceeded: Boolean, stateRestoreSucceeded: Boolean, failureLatched: Boolean): Boolean =
        privateRenderSucceeded && stateRestoreSucceeded && !failureLatched

    @JvmRecord
    data class PixelSampleRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
        override fun toString() = "PixelSampleRegion[x=$x, y=$y, width=$width, height=$height]"
    }

    @JvmRecord
    data class PixelSampleSummary(val pixels: Int, val nonBlackPixels: Int, val distinctColors: Int) {
        override fun toString() =
            "PixelSampleSummary[pixels=$pixels, nonBlackPixels=$nonBlackPixels, distinctColors=$distinctColors]"
    }

    private class PendingPixelSample(
        val feed: FeedState,
        val frameSerial: Long,
        val lifecycleGeneration: Long,
        var awaitingScreenSample: Boolean = false,
    )

    class GuiFramePublication {
        private var pendingQuadrant = -1

        fun queue(quadrant: Int): Boolean {
            if (quadrant < 0 || quadrant >= SpiritLeapPovRenderer.FEED_COUNT || pendingQuadrant >= 0) return false
            pendingQuadrant = quadrant
            return true
        }

        fun takeAfterGuiRender(): Int {
            val quadrant = pendingQuadrant
            pendingQuadrant = -1
            return quadrant
        }

        fun discard(): Int {
            val quadrant = pendingQuadrant
            pendingQuadrant = -1
            return quadrant
        }

        fun pending(): Boolean = pendingQuadrant >= 0
        fun pendingQuadrant(): Int = pendingQuadrant
    }

    @JvmStatic
    fun hasObservedWrongSodiumRoute(
        opaqueObserved: Boolean,
        opaqueVerified: Boolean,
        translucentObserved: Boolean,
        translucentVerified: Boolean,
    ): Boolean = opaqueObserved && !opaqueVerified || translucentObserved && !translucentVerified

    @JvmStatic
    fun isFeedEligible(targetName: String?, targetQuadrant: Int, cards: List<SpiritLeapRoster.Card>?): Boolean =
        findEligibleCard(targetName, targetQuadrant, cards) != null

    @JvmStatic
    fun shouldDisplayCachedFrameIdentity(
        cachedTargetName: String?,
        feedQuadrant: Int,
        cards: List<SpiritLeapRoster.Card>?,
        identityInvalidated: Boolean,
        hasObservedCardMapping: Boolean,
        lastObservedCardTargetName: String?,
    ): Boolean {
        if (cachedTargetName.isNullOrBlank() || identityInvalidated) return false
        val expectedTargetName = if (cards.isNullOrEmpty()) {
            if (!hasObservedCardMapping) return false
            lastObservedCardTargetName
        } else {
            findEligibleCardForQuadrant(feedQuadrant, cards)?.member()?.name()
        }
        return expectedTargetName != null && cachedTargetName.equals(expectedTargetName, ignoreCase = true)
    }

    @JvmStatic
    fun shouldReleaseForInactiveMenu(menuActive: Boolean, resourcesAllocated: Boolean): Boolean =
        !menuActive && resourcesAllocated

    @JvmStatic
    fun isCachedFrameFresh(hasFrame: Boolean, lastSuccessfulFrameNanos: Long, nowNanos: Long): Boolean {
        if (!hasFrame) return false
        val ageNanos = nowNanos - lastSuccessfulFrameNanos
        return ageNanos >= 0L && ageNanos < MAX_CACHED_FRAME_AGE_NANOS
    }

    @JvmStatic
    fun cachedFrameAgeMillis(hasFrame: Boolean, lastSuccessfulFrameNanos: Long, nowNanos: Long): Long {
        if (!hasFrame) return -1L
        return maxOf(0L, nowNanos - lastSuccessfulFrameNanos) / 1_000_000L
    }

    @JvmStatic
    fun shouldDisplayQuadrant(
        povsEnabled: Boolean,
        frameAvailable: Boolean,
        lastSuccessfulFrameNanos: Long,
        nowNanos: Long,
        hasTarget: Boolean,
        feedQuadrant: Int,
        requestedQuadrant: Int,
    ): Boolean = povsEnabled && isCachedFrameFresh(frameAvailable, lastSuccessfulFrameNanos, nowNanos) &&
        hasTarget && feedQuadrant == requestedQuadrant && requestedQuadrant in 0 until FEED_COUNT

    @JvmStatic
    fun shouldRenderFeed(hasTarget: Boolean, chunkNeighborhoodLoaded: Boolean, rendererCleanupFailed: Boolean): Boolean =
        hasTarget && chunkNeighborhoodLoaded && !rendererCleanupFailed

    @JvmStatic
    fun shouldUpdateFeed(nowNanos: Long, lastUpdateNanos: Long, hasUpdated: Boolean): Boolean =
        shouldUpdateFeed(nowNanos, lastUpdateNanos, hasUpdated, MAX_UPDATES_PER_FEED_PER_SECOND)

    @JvmStatic
    fun updateIntervalNanos(fps: Int): Long = 1_000_000_000L / maxOf(1, minOf(30, fps))

    @JvmStatic
    fun minimumRenderGapNanos(fps: Int): Long = updateIntervalNanos(fps) / FEED_COUNT

    @JvmStatic
    fun shouldUpdateFeed(nowNanos: Long, lastUpdateNanos: Long, hasUpdated: Boolean, fps: Int): Boolean =
        !hasUpdated || nowNanos - lastUpdateNanos >= updateIntervalNanos(fps)

    class PendingRenderStateRestore {
        private var snapshot: RenderStateSnapshot? = null

        fun retain(snapshot: RenderStateSnapshot) { this.snapshot = snapshot }
        fun clear() { snapshot = null }
        fun pending(): Boolean = snapshot != null

        fun retry(access: RenderStateAccess): Boolean {
            val current = snapshot ?: return true
            return try {
                SpiritLeapPovRenderer.restoreRenderState(access, current)
                snapshot = null
                true
            } catch (_: RuntimeException) {
                false
            } catch (_: Error) {
                false
            }
        }
    }

    @JvmStatic
    fun shouldDeferResourceReload(stateRestorePending: Boolean, privateRenderActive: Boolean, cleanupPending: Boolean): Boolean =
        stateRestorePending || privateRenderActive || cleanupPending

    class PendingResourceReload<T> {
        private var latestResources: T? = null

        fun retain(resources: T?) {
            if (resources != null) latestResources = resources
        }

        fun pending(): Boolean = latestResources != null

        fun takeIfReady(stateRestorePending: Boolean, privateRenderActive: Boolean, cleanupPending: Boolean): T? {
            if (SpiritLeapPovRenderer.shouldDeferResourceReload(stateRestorePending, privateRenderActive, cleanupPending)) return null
            val resources = latestResources
            latestResources = null
            return resources
        }

        fun clear() { latestResources = null }
    }

    @JvmStatic
    fun retainFailedRenderStateRestore(pending: PendingRenderStateRestore?, snapshot: RenderStateSnapshot?) {
        if (pending != null && snapshot != null) pending.retain(snapshot)
    }

    @JvmStatic
    fun retryPendingRenderStateRestoreBeforeClose(pending: PendingRenderStateRestore?, access: RenderStateAccess): Boolean =
        pending == null || pending.retry(access)

    @JvmStatic
    fun releaseTargetsAfterStateRestore(access: RenderStateAccess, snapshot: RenderStateSnapshot, releaseTargets: Runnable): Boolean {
        try {
            restoreRenderState(access, snapshot)
        } catch (_: RuntimeException) {
            return false
        } catch (_: Error) {
            return false
        }
        releaseTargets.run()
        return true
    }

    @JvmStatic
    fun shouldAttemptDeferredTargetCleanup(cleanupPending: Boolean, laterMainPassCompleted: Boolean): Boolean =
        cleanupPending && laterMainPassCompleted

    @JvmStatic
    fun shouldRetryDeferredCleanup(nowNanos: Long, lastTryNanos: Long): Boolean =
        lastTryNanos == 0L || nowNanos - lastTryNanos >= DEFERRED_TARGET_CLEANUP_RETRY_NANOS

    @JvmStatic
    fun isSameRenderStateRestoreContext(
        savedLevel: Any?, savedMainTarget: Any?, savedWindowWidth: Int, savedWindowHeight: Int,
        currentLevel: Any?, currentMainTarget: Any?, currentWindowWidth: Int, currentWindowHeight: Int,
    ): Boolean = savedMainTarget != null && savedLevel === currentLevel && savedMainTarget === currentMainTarget &&
        savedWindowWidth == currentWindowWidth && savedWindowHeight == currentWindowHeight

    @JvmStatic
    fun firstUnverifiedRenderModifier(loadedModIds: Set<String>?): String? {
        if (loadedModIds == null) return null
        for (modId in UNVERIFIED_RENDER_MODIFIERS) if (loadedModIds.contains(modId)) return modId
        return null
    }

    private fun firstLoadedUnverifiedRenderModifier(loader: FabricLoader): String? {
        for (modId in UNVERIFIED_RENDER_MODIFIERS) if (loader.isModLoaded(modId)) return modId
        return null
    }

    @JvmStatic
    fun unverifiedRenderModifierReason(modId: String?): String? =
        if (modId != null && UNVERIFIED_RENDER_MODIFIERS.contains(modId)) "unverified-render-modifier-$modId" else null

    @JvmStatic
    fun shouldRoutePrivateClearTarget(secondaryRenderer: Boolean, privateTargetAvailable: Boolean): Boolean =
        secondaryRenderer && privateTargetAvailable

    @JvmStatic
    fun sodiumTerrainTargetSelection(
        privateRenderActive: Boolean, translucentPass: Boolean, shaderTransparency: Boolean,
    ): SodiumTerrainTargetSelection {
        if (!privateRenderActive) return SodiumTerrainTargetSelection.ORIGINAL
        return if (translucentPass && shaderTransparency) SodiumTerrainTargetSelection.PRIVATE_TRANSLUCENT
        else SodiumTerrainTargetSelection.FEED
    }

    @JvmStatic
    fun layerGroupTargetSelection(
        privateRenderActive: Boolean, translucentGroup: Boolean, shaderTransparency: Boolean,
    ): SodiumTerrainTargetSelection = sodiumTerrainTargetSelection(privateRenderActive, translucentGroup, shaderTransparency)

    @JvmStatic
    fun pixelSampleRegion(textureWidth: Int, textureHeight: Int, centerX: Int, centerY: Int, sampleSize: Int): PixelSampleRegion? {
        if (sampleSize <= 0 || textureWidth < sampleSize || textureHeight < sampleSize) return null
        val x = maxOf(0, minOf(textureWidth - sampleSize, centerX - sampleSize / 2))
        val y = maxOf(0, minOf(textureHeight - sampleSize, centerY - sampleSize / 2))
        return PixelSampleRegion(x, y, sampleSize, sampleSize)
    }

    @JvmStatic
    fun screenPixelSampleRegion(
        quadrant: Int, guiWidth: Int, guiHeight: Int, textureWidth: Int, textureHeight: Int, sampleSize: Int,
    ): PixelSampleRegion? = screenPixelSampleRegion(quadrant, guiWidth, guiHeight, textureWidth, textureHeight, sampleSize, 8.5F)

    @JvmStatic
    fun screenPixelSampleRegion(
        quadrant: Int, guiWidth: Int, guiHeight: Int, textureWidth: Int, textureHeight: Int,
        sampleSize: Int, cardDistance: Float,
    ): PixelSampleRegion? {
        if (guiWidth <= 0 || guiHeight <= 0 || textureWidth <= 0 || textureHeight <= 0 || sampleSize <= 0) return null
        val margin = maxOf(1, kotlin.math.ceil(sampleSize / 2.0 * maxOf(guiWidth / textureWidth.toDouble(), guiHeight / textureHeight.toDouble())).toInt())
        val point = SpiritLeapCameraHud.samplePoint(quadrant, guiWidth, guiHeight, cardDistance, margin) ?: return null
        val x = (point.x.toLong() * textureWidth / guiWidth).toInt()
        val y = textureHeight - (point.y.toLong() * textureHeight / guiHeight).toInt()
        return pixelSampleRegion(textureWidth, textureHeight, x, y, sampleSize)
    }

    @JvmStatic
    fun unobscuredPixelSampleRegion(
        quadrant: Int, guiWidth: Int, guiHeight: Int, textureWidth: Int, textureHeight: Int,
        sampleSize: Int, cardDistance: Float,
    ): PixelSampleRegion? {
        if (quadrant !in 0 until FEED_COUNT || guiWidth <= 0 || guiHeight <= 0 || textureWidth <= 0 || textureHeight <= 0 || sampleSize <= 0) return null
        val margin = maxOf(1, kotlin.math.ceil(sampleSize / 2.0 * guiWidth / textureWidth.toDouble()).toInt())
        val point = SpiritLeapCameraHud.samplePoint(quadrant, guiWidth, guiHeight, cardDistance, margin)
        val panel = SpiritLeapCameraHud.quadrantBounds(quadrant, guiWidth, guiHeight)
        if (point == null || panel == null || panel.width() <= 0 || panel.height() <= 0) return null
        val localX = point.x - panel.x()
        val localY = point.y - panel.y()
        val textureCenterX = (localX.toLong() * textureWidth / panel.width()).toInt()
        val textureCenterY = textureHeight - (localY.toLong() * textureHeight / panel.height()).toInt()
        return pixelSampleRegion(textureWidth, textureHeight, textureCenterX, textureCenterY, sampleSize)
    }

    @JvmStatic
    fun summarizePixelBytes(source: ByteBuffer?, expectedPixels: Int): PixelSampleSummary? {
        if (source == null || expectedPixels <= 0 || source.remaining() < expectedPixels * 4) return null
        val pixels = source.duplicate()
        val colors: MutableSet<Int> = HashSet()
        var nonBlackPixels = 0
        for (index in 0 until expectedPixels) {
            val red = pixels.get().toInt() and 0xff
            val green = pixels.get().toInt() and 0xff
            val blue = pixels.get().toInt() and 0xff
            val alpha = pixels.get().toInt() and 0xff
            if ((red or green or blue) > 8) nonBlackPixels++
            colors.add(red shl 24 or (green shl 16) or (blue shl 8) or alpha)
        }
        return PixelSampleSummary(expectedPixels, nonBlackPixels, colors.size)
    }

    @JvmStatic
    fun shouldAwaitScreenPixelSample(screenSample: Boolean, result: String?): Boolean =
        !screenSample && result != null && result.startsWith("frame=")

    @JvmStatic
    fun shouldSamplePixel(lastSampleNanos: Long, nowNanos: Long): Boolean =
        lastSampleNanos == 0L || nowNanos - lastSampleNanos >= PIXEL_SAMPLE_INTERVAL_NANOS

    @JvmStatic
    fun shouldUsePrivateTransparencyChain(privateRenderer: Boolean, shaderTransparency: Boolean): Boolean =
        privateRenderer && shaderTransparency

    @JvmStatic
    fun sodiumPreflightReadyForAttempt(withinPixelBudget: Boolean, privateTargetReady: Boolean): Boolean =
        withinPixelBudget && privateTargetReady

    @JvmStatic
    fun attemptCleanupAndClearAfterSuccess(priorFailure: RuntimeException?, cleanup: Runnable, clearHandle: Runnable): RuntimeException? {
        try {
            cleanup.run()
            clearHandle.run()
        } catch (failure: RuntimeException) {
            if (priorFailure == null) return failure
            SpiritLeapPovRenderAttempt.combineFailure(priorFailure, failure)
        }
        return priorFailure
    }

    @JvmStatic
    fun isPrivateTargetRoute(routedTarget: Any?, privateTarget: Any?, mainTarget: Any?): Boolean =
        routedTarget != null && privateTarget != null && routedTarget === privateTarget && routedTarget !== mainTarget

    @JvmStatic
    fun renderTargetRouteState(routedTarget: Any?, privateTarget: Any?, mainTarget: Any?): String {
        if (routedTarget == null) return "none"
        if (privateTarget != null && routedTarget === privateTarget && routedTarget !== mainTarget) return "private-scratch"
        if (routedTarget === mainTarget) return "main-target"
        return "unexpected-target"
    }

    @JvmStatic
    fun shouldAbortPrivateTargetRoute(privateFeedActive: Boolean, routedTarget: Any?, privateTarget: Any?, mainTarget: Any?): Boolean =
        privateFeedActive && !isPrivateTargetRoute(routedTarget, privateTarget, mainTarget)

    @JvmStatic
    fun privateRenderTargetRouteFailure(
        levelObserved: Boolean, levelMismatch: Boolean, frameClearObserved: Boolean, frameClearMismatch: Boolean,
    ): String? {
        if (!levelObserved) return "level-target-not-observed"
        if (levelMismatch) return "level-target-mismatch"
        if (!frameClearObserved) return "frame-clear-not-observed"
        if (frameClearMismatch) return "frame-clear-mismatch"
        return null
    }

    @JvmStatic
    fun isFeedTargetAllocationWithinBudget(targetWidth: Int, targetHeight: Int, feedCount: Int): Boolean =
        isFeedTargetAllocationWithinBudget(targetWidth, targetHeight, feedCount, 1)

    @JvmStatic
    fun isFeedTargetAllocationWithinBudget(targetWidth: Int, targetHeight: Int, feedCount: Int, sharedTargetCount: Int): Boolean {
        if (targetWidth <= 0 || targetHeight <= 0 || feedCount <= 0 || feedCount > FEED_COUNT || sharedTargetCount <= 0) return false
        val pixelsPerFeed = targetWidth.toLong() * targetHeight
        return pixelsPerFeed <= MAX_TOTAL_FEED_TARGET_PIXELS / (feedCount + sharedTargetCount.toLong())
    }

    private fun incrementSaturated(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    enum class SodiumTerrainTargetSelection { ORIGINAL, FEED, PRIVATE_TRANSLUCENT }

    interface RenderStateAccess {
        fun outputColorTextureOverride(): Any?
        fun outputDepthTextureOverride(): Any?
        fun globalSettingsUniform(): Any?
        fun shaderFog(): Any?
        fun projectionMatrixBuffer(): Any?
        fun projectionType(): Any?
        fun outputTextureOverrides(color: Any?, depth: Any?)
        fun globalSettingsUniform(uniform: Any?)
        fun shaderFog(fog: Any?)
        fun projection(buffer: Any?, type: Any?)
        fun modelViewMatrix(): Matrix4f
        fun modelViewStackDepth(): Int
        fun modelViewStack(depth: Int, matrix: Matrix4f)
    }

    data class RenderStateSnapshot(
        val outputColorTextureOverride: Any?,
        val outputDepthTextureOverride: Any?,
        val globalSettingsUniform: Any?,
        val shaderFog: Any?,
        val projectionMatrixBuffer: Any?,
        val projectionType: Any?,
        val modelViewMatrix: Matrix4f,
        val modelViewStackDepth: Int,
    ) {
        fun outputColorTextureOverride(): Any? = outputColorTextureOverride
        fun outputDepthTextureOverride(): Any? = outputDepthTextureOverride
        fun globalSettingsUniform(): Any? = globalSettingsUniform
        fun shaderFog(): Any? = shaderFog
        fun projectionMatrixBuffer(): Any? = projectionMatrixBuffer
        fun projectionType(): Any? = projectionType
        fun modelViewMatrix(): Matrix4f = modelViewMatrix
        fun modelViewStackDepth(): Int = modelViewStackDepth
    }

    private data class RenderStateRestoreContext(
        val level: ClientLevel?,
        val mainTarget: RenderTarget?,
        val windowWidth: Int,
        val windowHeight: Int,
    )

    @JvmStatic
    fun captureRenderState(access: RenderStateAccess): RenderStateSnapshot = RenderStateSnapshot(
        access.outputColorTextureOverride(), access.outputDepthTextureOverride(), access.globalSettingsUniform(),
        access.shaderFog(), access.projectionMatrixBuffer(), access.projectionType(),
        Matrix4f(access.modelViewMatrix()), access.modelViewStackDepth(),
    )

    @JvmStatic
    fun restoreRenderState(access: RenderStateAccess, snapshot: RenderStateSnapshot) {
        var failure: Throwable? = null
        try { access.outputTextureOverrides(snapshot.outputColorTextureOverride(), snapshot.outputDepthTextureOverride()) }
        catch (restoreFailure: RuntimeException) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        catch (restoreFailure: Error) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        try { access.globalSettingsUniform(snapshot.globalSettingsUniform()) }
        catch (restoreFailure: RuntimeException) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        catch (restoreFailure: Error) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        try { access.shaderFog(snapshot.shaderFog()) }
        catch (restoreFailure: RuntimeException) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        catch (restoreFailure: Error) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        try { access.projection(snapshot.projectionMatrixBuffer(), snapshot.projectionType()) }
        catch (restoreFailure: RuntimeException) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        catch (restoreFailure: Error) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        try { access.modelViewStack(snapshot.modelViewStackDepth(), snapshot.modelViewMatrix()) }
        catch (restoreFailure: RuntimeException) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        catch (restoreFailure: Error) { failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure) }
        if (failure is Error) throw failure
        if (failure is RuntimeException) throw failure
        if (access.outputColorTextureOverride() !== snapshot.outputColorTextureOverride() ||
            access.outputDepthTextureOverride() !== snapshot.outputDepthTextureOverride() ||
            access.globalSettingsUniform() !== snapshot.globalSettingsUniform() ||
            access.shaderFog() !== snapshot.shaderFog() ||
            access.projectionMatrixBuffer() !== snapshot.projectionMatrixBuffer() ||
            access.projectionType() !== snapshot.projectionType() ||
            access.modelViewStackDepth() != snapshot.modelViewStackDepth() ||
            !access.modelViewMatrix().equals(snapshot.modelViewMatrix())
        ) {
            throw IllegalStateException("render-state-restore-verification-failed")
        }
    }

    private fun retainPendingRenderStateRestore(snapshot: RenderStateSnapshot?) {
        if (snapshot == null) return
        pendingRenderStateRestore.retain(snapshot)
        try {
            val client = Minecraft.getInstance()
            pendingRenderStateRestoreContext = RenderStateRestoreContext(
                client.level, client.gameRenderer.mainRenderTarget(), client.window.width, client.window.height,
            )
        } catch (_: RuntimeException) {
            pendingRenderStateRestoreContext = null
        }
        lastPendingStateRestoreTryNanos = 0L
    }

    private fun retryPendingRenderStateRestore(): Boolean {
        if (!pendingRenderStateRestore.pending()) {
            pendingRenderStateRestoreContext = null
            return true
        }
        if (!matchesCurrentRenderStateRestoreContext(pendingRenderStateRestoreContext)) {
            // Captured GPU outputs can be replaced when the level or window changes; wait for a completed frame baseline.
            pendingRenderStateRestore.clear()
            pendingRenderStateRestoreContext = null
            lastPendingStateRestoreTryNanos = 0L
            renderStateRecoveryState.obsoleteSnapshotDiscarded()
            feedTargetCleanupReason = "obsolete-state-snapshot-discarded-after-context-change"
            return false
        }
        val now = System.nanoTime()
        if (!shouldRetryDeferredCleanup(now, lastPendingStateRestoreTryNanos)) return false
        lastPendingStateRestoreTryNanos = now
        if (!pendingRenderStateRestore.retry(RENDER_STATE)) return false
        pendingRenderStateRestoreContext = null
        lastPendingStateRestoreTryNanos = 0L
        return true
    }

    private fun establishFreshRenderStateBaselineAfterCompletedFrame(): Boolean {
        if (!renderStateRecoveryState.awaitingCompletedFrameBaseline()) return true
        var baseline: RenderStateSnapshot? = null
        try {
            baseline = captureRenderState(RENDER_STATE)
            restoreRenderState(RENDER_STATE, baseline)
            return renderStateRecoveryState.acceptCompletedFrameBaseline(true, true)
        } catch (stateFailure: RuntimeException) {
            if (baseline != null) retainPendingRenderStateRestore(baseline)
            deferFullResourceCleanup("fresh-frame-state-baseline-failed-${boundedFailureType(stateFailure.javaClass.simpleName)}")
            return false
        } catch (stateFailure: Error) {
            if (baseline != null) retainPendingRenderStateRestore(baseline)
            deferFullResourceCleanup("fresh-frame-state-baseline-failed-${boundedFailureType(stateFailure.javaClass.simpleName)}")
            return false
        }
    }

    private fun matchesCurrentRenderStateRestoreContext(context: RenderStateRestoreContext?): Boolean {
        if (context == null) return false
        return try {
            val client = Minecraft.getInstance()
            isSameRenderStateRestoreContext(
                context.level, context.mainTarget, context.windowWidth, context.windowHeight,
                client.level, client.gameRenderer.mainRenderTarget(), client.window.width, client.window.height,
            )
        } catch (_: RuntimeException) {
            false
        }
    }

    @JvmStatic
    fun classifyRendererCompatibility(
        minecraftVersion: String?, fabricLoaderVersion: String?, fabricApiVersion: String?, backendName: String?,
        sodiumVersion: String?, nvidiumVersion: String?, sodiumTargetAdapterVerified: Boolean,
    ): CompatibilityDecision = classifyRendererCompatibility(
        minecraftVersion, fabricLoaderVersion, fabricApiVersion, backendName, sodiumVersion, nvidiumVersion,
        null, sodiumTargetAdapterVerified,
    )

    @JvmStatic
    fun classifyRendererCompatibility(
        minecraftVersion: String?, fabricLoaderVersion: String?, fabricApiVersion: String?, backendName: String?,
        sodiumVersion: String?, nvidiumVersion: String?, unverifiedRenderModifier: String?,
        sodiumTargetAdapterVerified: Boolean,
    ): CompatibilityDecision {
        val modifierFailure = unverifiedRenderModifierReason(unverifiedRenderModifier)
        if (modifierFailure != null) return CompatibilityDecision(0, "closed", modifierFailure)
        val hasSodium = !sodiumVersion.isNullOrBlank()
        val hasNvidium = !nvidiumVersion.isNullOrBlank()
        if (PROBE_MINECRAFT_VERSION != minecraftVersion) return CompatibilityDecision(0, "closed", "minecraft-version-unverified")
        if (!hasInstalledCapabilityVersion(fabricLoaderVersion)) return CompatibilityDecision(0, "closed", "fabric-loader-unavailable")
        if (!hasInstalledCapabilityVersion(fabricApiVersion)) return CompatibilityDecision(0, "closed", "fabric-api-unavailable")
        if (!isSupportedGpuBackend(backendName)) return CompatibilityDecision(0, "closed", "gpu-backend-unverified")
        if (hasNvidium && !"OpenGL".equals(backendName, ignoreCase = true)) {
            return CompatibilityDecision(0, "closed", "nvidium-backend-unverified")
        }
        if (!hasSodium && hasNvidium) return CompatibilityDecision(0, "closed", "nvidium-without-sodium")
        if (hasSodium && !SodiumWorldRendererSpiritLeapSupport.supportsVersion(sodiumVersion)) {
            return CompatibilityDecision(0, "closed", "sodium-version-unverified")
        }
        if (hasNvidium && PROBE_SODIUM_VERSION != sodiumVersion) {
            return CompatibilityDecision(0, "closed", "sodium-version-unverified")
        }
        if (hasNvidium && (!NVIDIUM_CAPABILITIES_VERIFIED_FOR_26_2 || PROBE_NVIDIUM_VERSION != nvidiumVersion)) {
            return CompatibilityDecision(0, "closed", "nvidium-version-unverified")
        }
        if (hasSodium && !sodiumTargetAdapterVerified) return CompatibilityDecision(0, "closed", "sodium-target-adapter-unverified")
        return CompatibilityDecision(
            FEED_COUNT,
            "four-feed-automatic",
            if (hasNvidium) "pinned-sodium-nvidium-capabilities"
            else if (hasSodium) "pinned-sodium-capabilities" else "vanilla-gpu-backend-capabilities",
        )
    }

    private fun isSupportedGpuBackend(backendName: String?): Boolean =
        "OpenGL".equals(backendName, ignoreCase = true) || "Vulkan".equals(backendName, ignoreCase = true)

    private fun hasInstalledCapabilityVersion(version: String?): Boolean =
        !version.isNullOrBlank() && !"unknown".equals(version, ignoreCase = true)

    @JvmStatic
    fun isFeedEnabled(decision: CompatibilityDecision?, quadrant: Int): Boolean =
        decision != null && quadrant >= 0 && quadrant < decision.maxFeeds

    @JvmStatic
    fun eligibleFeedQuadrants(decision: CompatibilityDecision?, cards: List<SpiritLeapRoster.Card>?, fixedCameraProbe: Boolean): BooleanArray {
        val enabled = BooleanArray(FEED_COUNT)
        if (decision == null || decision.maxFeeds <= 0) return enabled
        if (fixedCameraProbe) {
            enabled[0] = true
            return enabled
        }
        val limit = minOf(FEED_COUNT, decision.maxFeeds)
        var selected = 0
        for (quadrant in 0 until FEED_COUNT) {
            if (selected >= limit) break
            if (findEligibleCardForQuadrant(quadrant, cards) == null) continue
            enabled[quadrant] = true
            selected++
        }
        return enabled
    }

    @JvmStatic
    fun selectedFeedQuadrants(activeFeedCount: Int, feeds: Array<FeedState>?): BooleanArray {
        val selected = BooleanArray(FEED_COUNT)
        if (feeds == null) return selected
        val limit = maxOf(0, minOf(FEED_COUNT, activeFeedCount))
        var selectedCount = 0
        for (quadrant in 0 until FEED_COUNT) {
            if (quadrant >= feeds.size || selectedCount >= limit) break
            val feed = nullableLegacyValue(feeds[quadrant]) ?: continue
            if (feed.targetName.isNullOrBlank()) continue
            selected[quadrant] = true
            selectedCount++
        }
        return selected
    }

    @JvmStatic
    fun diagnosticVersion(version: String?): String {
        if (version.isNullOrBlank()) return "absent"
        return SpiritLeapMenuModule.boundedDiagnosticText(version, MAX_DIAGNOSTIC_VERSION_CHARS)
    }

    @JvmStatic
    fun formatRendererProfile(
        minecraftVersion: String?, fabricLoaderVersion: String?, fabricApiVersion: String?, backendName: String?,
        sodiumVersion: String?, nvidiumVersion: String?, sodiumTargetAdapterVerified: Boolean,
        decision: CompatibilityDecision,
    ): String = formatRendererProfile(
        minecraftVersion, fabricLoaderVersion, fabricApiVersion, backendName, sodiumVersion, nvidiumVersion,
        null, null, sodiumTargetAdapterVerified, decision,
    )

    @JvmStatic
    fun formatRendererProfile(
        minecraftVersion: String?, fabricLoaderVersion: String?, fabricApiVersion: String?, backendName: String?,
        sodiumVersion: String?, nvidiumVersion: String?, unverifiedRenderModifier: String?,
        unverifiedRenderModifierVersion: String?, sodiumTargetAdapterVerified: Boolean, decision: CompatibilityDecision,
    ): String = "minecraft=${diagnosticVersion(minecraftVersion)}" +
        " fabricloader=${diagnosticVersion(fabricLoaderVersion)}" +
        " fabricApi=${diagnosticVersion(fabricApiVersion)}" +
        " backend=${diagnosticVersion(backendName)}" +
        " modVersions[sodium=${diagnosticVersion(sodiumVersion)},nvidium=${diagnosticVersion(nvidiumVersion)}]" +
        " unverifiedRenderModifier=${if (unverifiedRenderModifier == null) "none" else diagnosticVersion(unverifiedRenderModifier) + "@" + diagnosticVersion(unverifiedRenderModifierVersion)}" +
        " sodiumTargetAdapter=${if (sodiumVersion == null) "not-needed" else if (sodiumTargetAdapterVerified) "verified" else "unverified"}" +
        " gate=${decision.mode}(${decision.reason})" +
        " maxFeeds=${decision.maxFeeds}" +
        " renderer=active-world" +
        " resourceRisk=active-world-pass-cost-unmeasured"

    @JvmStatic
    fun gateFabricExtractionContext(decision: CompatibilityDecision, renderingVersion: String?, accessorApplied: Boolean): CompatibilityDecision {
        if (!FabricRenderingContextSupport.supportsVersion(renderingVersion)) {
            return CompatibilityDecision(0, "closed", "fabric-rendering-context-version-unverified")
        }
        if (!accessorApplied) return CompatibilityDecision(0, "closed", "fabric-rendering-context-accessor-unavailable")
        return decision
    }

    @JvmStatic
    fun gateSkyblockerCompatibility(
        decision: CompatibilityDecision, skyblockerLoaded: Boolean, skyblockerVersion: String?, hookApplied: Boolean,
    ): CompatibilityDecision {
        if (!skyblockerLoaded) return decision
        if (!SkyblockerSpiritLeapSupport.supportsVersion(skyblockerVersion)) {
            return CompatibilityDecision(0, "closed", "skyblocker-callback-version-unverified")
        }
        if (!hookApplied) return CompatibilityDecision(0, "closed", "skyblocker-private-render-hook-unavailable")
        return decision
    }

    @JvmStatic
    fun shouldApplyFabricContextAccessor(mixinClassName: String?, renderingVersion: String?): Boolean =
        FabricRenderingContextSupport.shouldApplyAccessor(mixinClassName, renderingVersion)

    @JvmStatic
    fun prepareFabricExtractionContext(
        context: LevelExtractionContextImpl, gameRenderer: GameRenderer, renderer: LevelRenderer,
        levelState: LevelRenderState, level: ClientLevel, deltaTracker: DeltaTracker, camera: Camera,
    ) {
        context.prepare(gameRenderer, renderer, levelState, level, deltaTracker, camera)
    }

    @JvmStatic
    fun formatRendererProfileWithExtractionContext(baseProfile: String, renderingVersion: String?, accessorStatus: String?): String =
        "$baseProfile fabricRenderingV1=${diagnosticVersion(renderingVersion)} extractionContextAccessor=${diagnosticVersion(accessorStatus)}"

    @JvmRecord
    data class CompatibilityDecision(val maxFeeds: Int, val mode: String?, val reason: String?) {
        override fun toString() = "CompatibilityDecision[maxFeeds=$maxFeeds, mode=$mode, reason=$reason]"
    }

    private data class RendererProfile(
        val minecraftVersion: String?, val fabricLoaderVersion: String?, val fabricApiVersion: String?,
        val backendName: String?, val sodiumVersion: String?, val nvidiumVersion: String?,
        val unverifiedRenderModifier: String?, val unverifiedRenderModifierVersion: String?,
        val sodiumTargetAdapterVerified: Boolean, val fabricRenderingVersion: String?,
        val extractionContextAccessorStatus: String?, val decision: CompatibilityDecision,
    ) {
        fun diagnostic(): String {
            val baseProfile = SpiritLeapPovRenderer.formatRendererProfile(
                minecraftVersion, fabricLoaderVersion, fabricApiVersion, backendName, sodiumVersion, nvidiumVersion,
                unverifiedRenderModifier, unverifiedRenderModifierVersion, sodiumTargetAdapterVerified, decision,
            )
            return SpiritLeapPovRenderer.formatRendererProfileWithExtractionContext(baseProfile, fabricRenderingVersion,
                extractionContextAccessorStatus) + " skyblockerHook=" +
                SpiritLeapPovRenderer.diagnosticVersion(SkyblockerSpiritLeapSupport.diagnosticStatus()) +
                " sodiumWorldRendererHook=" +
                SpiritLeapPovRenderer.diagnosticVersion(SodiumWorldRendererSpiritLeapSupport.diagnosticStatus())
        }
    }

    @JvmStatic fun shouldUseMainCrosshair(secondaryRenderer: Boolean): Boolean = !secondaryRenderer

    @JvmStatic
    fun cameraFor(secondaryRenderer: Boolean, povCamera: Camera, mainCamera: Camera): Camera =
        if (secondaryRenderer) povCamera else mainCamera

    @JvmStatic
    fun shouldProbeChunks(
        nowNanos: Long, lastProbeNanos: Long, hasProbe: Boolean, chunkX: Int, chunkZ: Int, lastChunkX: Int, lastChunkZ: Int,
    ): Boolean = !hasProbe || chunkX != lastChunkX || chunkZ != lastChunkZ || nowNanos - lastProbeNanos >= CHUNK_PROBE_INTERVAL_NANOS

    class FeedScheduler {
        private var nextQuadrant = 0
        private var hasCompletedRenderAttempt = false
        private var lastRenderCompletionNanos = 0L

        fun canStartRender(nowNanos: Long): Boolean =
            canStartRender(nowNanos, SpiritLeapPovRenderer.MAX_UPDATES_PER_FEED_PER_SECOND)

        fun canStartRender(nowNanos: Long, fps: Int): Boolean =
            !hasCompletedRenderAttempt || nowNanos - lastRenderCompletionNanos >=
                SpiritLeapPovRenderer.minimumRenderGapNanos(fps)

        fun renderAttemptCompleted(nowNanos: Long) {
            hasCompletedRenderAttempt = true
            lastRenderCompletionNanos = nowNanos
        }

        fun nextQuadrant(): Int {
            val scheduled = nextQuadrant
            nextQuadrant = (nextQuadrant + 1) % SpiritLeapPovRenderer.FEED_COUNT
            return scheduled
        }

        fun nextQuadrantIndex(): Int = nextQuadrant

        fun nextDueQuadrant(activeFeedCount: Int, feeds: Array<FeedState>?, nowNanos: Long): Int =
            nextDueQuadrant(activeFeedCount, feeds, nowNanos, SpiritLeapPovRenderer.MAX_UPDATES_PER_FEED_PER_SECOND)

        fun nextDueQuadrant(activeFeedCount: Int, feeds: Array<FeedState>?, nowNanos: Long, fps: Int): Int {
            val enabledQuadrants = SpiritLeapPovRenderer.selectedFeedQuadrants(activeFeedCount, feeds)
            for (checked in 0 until SpiritLeapPovRenderer.FEED_COUNT) {
                val quadrant = nextQuadrant()
                if (!enabledQuadrants[quadrant]) continue
                val feed = feeds?.getOrNull(quadrant)
                if (feed != null && feed.targetName != null && feed.chunkNeighborhoodLoaded &&
                    SpiritLeapPovRenderer.shouldUpdateFeed(nowNanos, feed.lastUpdateNanos, feed.hasUpdated, fps)
                ) return quadrant
            }
            return -1
        }

        fun reset() {
            nextQuadrant = 0
            hasCompletedRenderAttempt = false
            lastRenderCompletionNanos = 0L
        }
    }

    class PovSessionState {
        private var failed = false
        private var warmupPromoted = false

        fun failureLatched(): Boolean = failed
        fun warmupPromoted(): Boolean = warmupPromoted
        fun latchFailure() { failed = true }

        fun publishAfterGuiPass(privateRenderSucceeded: Boolean, stateRestoreSucceeded: Boolean, guiPassCompleted: Boolean): Boolean {
            if (!guiPassCompleted || !SpiritLeapPovRenderer.shouldPublishCompletedFrame(
                    privateRenderSucceeded, stateRestoreSucceeded, failed)) return false
            warmupPromoted = true
            return true
        }

        fun menuClosed() { warmupPromoted = false }
        fun resourceReloadSucceeded() { resetAtLifecycleBoundary() }

        fun resetAtLifecycleBoundary() {
            failed = false
            warmupPromoted = false
        }
    }

    class RenderStateRecoveryState {
        private var awaitingCompletedFrameBaseline = false

        fun awaitingCompletedFrameBaseline(): Boolean = awaitingCompletedFrameBaseline
        fun obsoleteSnapshotDiscarded() { awaitingCompletedFrameBaseline = true }

        fun acceptCompletedFrameBaseline(completedClientFrame: Boolean, baselineVerified: Boolean): Boolean {
            if (!awaitingCompletedFrameBaseline) return true
            if (!completedClientFrame || !baselineVerified) return false
            awaitingCompletedFrameBaseline = false
            return true
        }
    }

    private fun observeToggleState() {
        val moduleEnabled = SpiritLeapMenuModule.INSTANCE.isEnabled()
        val povsEnabled = SpiritLeapMenuModule.povsEnabled()
        if (!povToggleStateObserved) {
            povToggleStateObserved = true
            previousModuleEnabled = moduleEnabled
            previousPovsEnabled = povsEnabled
            if (moduleEnabled && povsEnabled) beginLifecycleBoundary()
            return
        }
        if (isToggleActivation(previousModuleEnabled, previousPovsEnabled, moduleEnabled, povsEnabled)) {
            beginLifecycleBoundary()
        } else if (previousModuleEnabled && !moduleEnabled || previousPovsEnabled && !povsEnabled) {
            close()
        }
        previousModuleEnabled = moduleEnabled
        previousPovsEnabled = povsEnabled
    }

    fun onFeatureEnabled() {
        if (RenderSystem.isOnRenderThread()) {
            beginLifecycleBoundary()
            return
        }
        val generation = lifecycleGeneration.incrementAndGet()
        closeRequestGeneration.incrementAndGet()
        executeOnMinecraftRenderThread {
            if (lifecycleGeneration.get() != generation || !SpiritLeapMenuModule.INSTANCE.isEnabled()) return@executeOnMinecraftRenderThread
            requestLifecycleResetOnRenderThread()
        }
    }

    fun onWorldChanged(previousLevel: ClientLevel?, nextLevel: ClientLevel?) {
        if (previousLevel === nextLevel) return
        if (!RenderSystem.isOnRenderThread()) {
            val generation = lifecycleGeneration.incrementAndGet()
            closeRequestGeneration.incrementAndGet()
            executeOnMinecraftRenderThread {
                if (lifecycleGeneration.get() != generation) return@executeOnMinecraftRenderThread
                onWorldChangedOnRenderThread()
            }
            return
        }
        onWorldChangedOnRenderThread()
    }

    private fun onWorldChangedOnRenderThread() {
        lifecycleGeneration.incrementAndGet()
        closeRequestGeneration.incrementAndGet()
        clearLoadedPlayerLookup()
        resetAllFeedCameras()
        lifecycleResetPending = true
        closeNow()
        applyPendingLifecycleReset()
    }

    private fun beginLifecycleBoundary() {
        if (!RenderSystem.isOnRenderThread()) {
            onFeatureEnabled()
            return
        }
        requestLifecycleResetOnRenderThread()
    }

    private fun requestLifecycleResetOnRenderThread() {
        lifecycleGeneration.incrementAndGet()
        closeRequestGeneration.incrementAndGet()
        clearLoadedPlayerLookup()
        lifecycleResetPending = true
        if (hasAllocatedResources()) closeNow()
        applyPendingLifecycleReset()
    }

    private fun executeOnMinecraftRenderThread(action: Runnable) {
        val client: Minecraft? = try {
            Minecraft.getInstance()
        } catch (_: RuntimeException) {
            // Before Minecraft exists there can be no live Leap targets to release.
            if (!hasAllocatedResources()) return
            return
        }
        if (client == null) {
            if (!hasAllocatedResources()) return
            return
        }
        client.execute {
            if (!RenderSystem.isOnRenderThread()) return@execute
            action.run()
        }
    }

    private fun applyPendingLifecycleReset() {
        if (!lifecycleResetPending || pendingRenderStateRestore.pending() || activeRenderTarget != null ||
            feedTargetCleanupPending || rendererCleanupPending
        ) return
        povSessionState.resetAtLifecycleBoundary()
        probeFailureReason = "none"
        probeFailureStage = "none"
        probeFailureTopSite = "none"
        probeFailureDiagnosticRecorded = false
        rendererCleanupFailed = false
        rendererProfile = null
        sodiumAdapterVerificationAttempted = false
        sodiumTerrainPassAccessor.clear()
        sodiumTargetRouteStatus = "not-checked"
        warmupQuadrant = -1
        feedScheduler.reset()
        resetAllFeedCameras()
        for (feed in feeds) feed.resetForLifecycleBoundary()
        lifecycleResetPending = false
    }

    private fun resetSessionAfterSuccessfulReload() {
        povSessionState.resourceReloadSucceeded()
        probeFailureReason = "none"
        probeFailureStage = "none"
        probeFailureTopSite = "none"
        probeFailureDiagnosticRecorded = false
        rendererProfile = null
        sodiumAdapterVerificationAttempted = false
        sodiumTerrainPassAccessor.clear()
        resetAllFeedCameras()
        sodiumTargetRouteStatus = "not-checked"
        warmupQuadrant = -1
        feedScheduler.reset()
        for (feed in feeds) {
            feed.stage = "resource-reload"
            feed.reason = "reloaded"
            feed.status = "waiting(reason=resource-reload)"
        }
    }

    private fun findEligibleCard(
        targetName: String?, targetQuadrant: Int, cards: List<SpiritLeapRoster.Card?>?,
    ): SpiritLeapRoster.Card? {
        if (targetName.isNullOrBlank() || cards == null) return null
        for (card in cards) {
            if (card == null) continue
            val member = nullableLegacyValue(card.member()) ?: continue
            if (card.quadrant() != targetQuadrant) continue
            if (!member.empty() && member.online() && !member.dead() && member.name().equals(targetName, ignoreCase = true)) return card
        }
        return null
    }

    private fun findEligibleCardForQuadrant(
        targetQuadrant: Int, cards: List<SpiritLeapRoster.Card?>?,
    ): SpiritLeapRoster.Card? {
        if (cards == null) return null
        for (card in cards) {
            if (card == null || card.quadrant() != targetQuadrant) continue
            val member = nullableLegacyValue(card.member()) ?: continue
            // Head-derived fallback cards use online=false until a live loaded-player check confirms them.
            if (!member.empty() && !member.dead()) return card
        }
        return null
    }

    @JvmStatic
    fun firstEligibleLoadedQuadrant(cards: List<SpiritLeapRoster.Card>?, loadedNames: Set<String>?): Int {
        if (cards == null || loadedNames.isNullOrEmpty()) return -1
        for (quadrant in 0 until FEED_COUNT) {
            val card = findEligibleCardForQuadrant(quadrant, cards)
            if (card != null && loadedNames.contains(normalizedPlayerName(card.member().name()))) return quadrant
        }
        return -1
    }

    @JvmStatic
    fun firstReadyQuadrant(activeFeedCount: Int, feeds: Array<FeedState>?): Int {
        val enabledQuadrants = selectedFeedQuadrants(activeFeedCount, feeds)
        for (quadrant in 0 until FEED_COUNT) {
            if (!enabledQuadrants[quadrant]) continue
            val feed = feeds?.getOrNull(quadrant)
            if (feed != null && feed.targetName != null && feed.chunkNeighborhoodLoaded) return quadrant
        }
        return -1
    }

    private fun normalizedPlayerName(name: String?): String = name?.lowercase(Locale.ROOT) ?: ""

    /** Preserve Java-era null guards when Kotlin metadata marks a runtime value non-null. */
    private fun <T : Any> nullableLegacyValue(value: T?): T? = value

    private fun refreshLoadedPlayerLookup(level: ClientLevel) {
        val tick = level.gameTime
        if (loadedPlayerLookupLevel === level && loadedPlayerLookupTick == tick) return
        val players: MutableMap<String, Player> = HashMap()
        val levelPlayers: List<Any?> = level.players()
        for (player in levelPlayers) {
            if (player !is AbstractClientPlayer) continue
            val name = player.gameProfile.name
            if (!name.isNullOrBlank()) players.putIfAbsent(normalizedPlayerName(name), player)
        }
        loadedPlayerLookupLevel = level
        loadedPlayerLookupTick = tick
        loadedPlayersByName = java.util.Map.copyOf<String, Player>(players)
        loadedPlayerLookupSize = loadedPlayersByName.size
        loadedPlayerLookupBuilds = incrementSaturated(loadedPlayerLookupBuilds)
    }

    private fun clearLoadedPlayerLookup() {
        loadedPlayerLookupLevel = null
        loadedPlayerLookupTick = Long.MIN_VALUE
        loadedPlayersByName = java.util.Map.of<String, Player>()
        loadedPlayerLookupSize = 0
    }

    private fun invalidateLoadedPlayer(name: String?) {
        if (name.isNullOrBlank() || !loadedPlayersByName.containsKey(normalizedPlayerName(name))) return
        val players = HashMap<String, Player>(loadedPlayersByName)
        players.remove(normalizedPlayerName(name))
        loadedPlayersByName = java.util.Map.copyOf<String, Player>(players)
        loadedPlayerLookupSize = loadedPlayersByName.size
    }

    private fun hasPrivateRendererResources(): Boolean =
        fogRenderer != null || transparencyChain != null || transparencyProjectionBuffer != null ||
            povProjectionBuffer != null || povGlobalSettingsUniform != null

    private fun hasAllocatedResources(): Boolean = hasPrivateRendererResources() || hasFeedTargets()

    fun diagnosticState(): String {
        val profile = rendererProfile()
        val client = Minecraft.getInstance()
        val screen = client.gui.screen()
        val surfaceOwner = if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            SpiritLeapSurfaceOwner.diagnosticState(screen)
        } else "none"
        var selectedFeeds = 0
        for (feed in feeds) if (feed.targetName != null && feed.player != null) selectedFeeds++
        val probeRequested = fixedCameraProbeEnabled()
        val q0 = feeds[0]
        val q0ProbeAssigned = q0.fixedCameraProbe && q0.player != null && FIXED_CAMERA_TARGET_NAME == q0.targetName
        val diagnosticFeedLimit = diagnosticMaxFeeds(probeRequested, profile.decision.maxFeeds)
        val updateFps = SpiritLeapMenuModule.povUpdatesPerSecond()
        val diagnostic = StringBuilder("quadrants=4,maxFeeds=")
            .append(profile.decision.maxFeeds)
            .append(",selectedFeeds=").append(selectedFeeds)
            .append(",cameraProbe=").append(fixedCameraProbeDiagnosticState(probeRequested, q0ProbeAssigned))
            .append(",surfaceOwner=").append(surfaceOwner)
            .append(",schedule=round-robin,capFpsPerFeed=").append(updateFps)
            .append(",minRenderGapMs=").append(minimumRenderGapNanos(updateFps) / 1_000_000.0)
            .append(",maxRendersPerSecond=").append(diagnosticFeedLimit * updateFps)
            .append(",cacheMaxAgeMs=").append(MAX_CACHED_FRAME_AGE_NANOS / 1_000_000L)
            .append(",renderTargets=per-feed-cache+shared-scratch")
            .append(",panelComposition=opaque-rgb")
            .append(",guiPublication=").append(if (guiFramePublication.pending()) "pending(q${guiFramePublication.pendingQuadrant()})" else "idle")
            .append(",schedulerNext=q").append(feedScheduler.nextQuadrantIndex())
            .append(",warmup=").append(if (povSessionState.warmupPromoted()) "promoted" else "q$warmupQuadrant")
            .append(",playerLookup[builds=").append(loadedPlayerLookupBuilds)
            .append(",tick=").append(loadedPlayerLookupTick)
            .append(",players=").append(loadedPlayerLookupSize).append("]")
            .append(",targetPixelBudget=").append(MAX_TOTAL_FEED_TARGET_PIXELS)
            .append(",sodiumRoutes[mainChecks=").append(sodiumMainTargetRouteChecks)
            .append(",mainVerified=").append(sodiumMainTargetRoutesVerified)
            .append(",translucentChecks=").append(sodiumTranslucentTargetRouteChecks)
            .append(",translucentVerified=").append(sodiumTranslucentTargetRoutesVerified)
            .append(",status=").append(sodiumTargetRouteStatus).append("],renderer=")
            .append(if (rendererCleanupFailed) "cleanup-failed" else "shared")
            .append(",failureLatch=").append(if (povSessionState.failureLatched()) "$probeFailureReason(stage=$probeFailureStage,top=$probeFailureTopSite)" else "clear")
            .append(",targetCleanup=").append(if (feedTargetCleanupPending) "deferred($feedTargetCleanupReason)" else "clear")
            .append(",stateRestore=").append(if (pendingRenderStateRestore.pending()) "deferred" else "clear")
            .append(",resourceReload=").append(if (pendingResourceReload.pending()) "deferred" else "clear")
            .append(",rendererCleanup=").append(if (rendererCleanupPending) "deferred" else "clear")
            .append(" runtime[").append(profile.diagnostic()).append(']')
        for (feed in feeds) diagnostic.append(" q").append(feed.quadrant).append('[').append(feedDiagnosticState(feed)).append(']')
        return diagnostic.toString()
    }

    private fun feedDiagnosticState(feed: FeedState): String {
        val now = System.nanoTime()
        val frameAgeMillis = feed.frameAgeMillis(now)
        val client = Minecraft.getInstance()
        val cameraBinding = if (feed.player == null || client.level == null) "no-target"
        else if (feed.cameraHistory.isBoundTo(client.level, feed.player)) "matched"
        else if (feed.cameraHistory.isBound()) "mismatch" else "unbound"
        val visibleFrame = isCachedFrameFresh(feed.frameAvailable, feed.lastSuccessfulFrameNanos, now) && feed.frameTarget != null
        val frameState = if (visibleFrame) "cached" else if (feed.frameAvailable) "expired" else "hidden"
        val sodiumLoaded = FabricLoader.getInstance().isModLoaded("sodium")
        val opaqueRoute = if (sodiumLoaded) routeDiagnostic(feed.sodiumMainTargetRouteObserved, feed.sodiumMainTargetRouteVerified) else "not-required"
        val translucentRoute = if (sodiumLoaded) routeDiagnostic(feed.sodiumTranslucentTargetRouteObserved, feed.sodiumTranslucentTargetRouteVerified) else "not-required"
        val targetSize = feed.frameTarget?.let { "${it.width}x${it.height}" } ?: "none"
        return "stage=${feed.stage},reason=${feed.reason}" +
            ",fabricContext[preparations=${feed.contextPreparationCount},prepared=${feed.contextPreparationSuccessCount},last=${feed.contextPreparationStatus}]" +
            ",levelExtraction[successes=${feed.levelExtractionSuccessCount},last=${feed.levelExtractionStatus}]" +
            ",timingUs[select=${feed.selectionDurationMicros},chunks=${feed.chunkCheckDurationMicros},stateSnapshot=${feed.renderStateSnapshotDurationMicros},stateRestore=${feed.renderStateRestoreDurationMicros},renderer=${feed.rendererInitDurationMicros},target=${feed.targetAllocationDurationMicros},attempt=${feed.attemptDurationMicros},render=${feed.privateRenderDurationMicros}]" +
            ",terrainRoutes[opaque=$opaqueRoute,translucent=$translucentRoute]" +
            ",layerGroupRoutes[opaque=${routeDiagnostic(feed.layerGroupOpaqueObserved, feed.layerGroupOpaqueVerified)},translucent=${routeDiagnostic(feed.layerGroupTranslucentObserved, feed.layerGroupTranslucentVerified)}]" +
            ",terrain[batches=${feed.vanillaTerrainBatches},sodiumLayers=${feed.sodiumLayerCalls},nvidium=${feed.nvidiumWorldPasses}/${feed.nvidiumTranslucentPasses},visibleSections=${feed.visibleTerrainSections}]" +
            ",globals=${feed.globalSettingsUniformState},projection=${feed.projectionState}" +
            ",pixelSample[source=${feed.sourcePixelSample},screen=${feed.screenPixelSample}]" +
            ",camera[${cameraDiagnosticState(feed, cameraBinding)},ticks=${feed.cameraHistory.advancedTicks()}]" +
            ",targetRoute[level=${feed.lastLevelTargetRoute},frameClear=${feed.lastFrameGraphClearRoute},levelObserved=${feed.levelTargetRouteObserved},clearObserved=${feed.frameGraphClearRouteObserved},verified=${privateRenderTargetRouteFailure(feed.levelTargetRouteObserved, feed.levelTargetRouteMismatch, feed.frameGraphClearRouteObserved, feed.frameGraphClearRouteMismatch) == null}]" +
            ",state=${feed.status}(target=${if (feed.targetName == null) "none" else "loaded"},targetSize=$targetSize,chunks=${feed.loadedViewChunkCount}/$VIEW_NEIGHBORHOOD_CHUNK_COUNT,frame=$frameState,frames=${feed.successfulFrameCount},ageMs=$frameAgeMillis)" +
            ",publication=${feed.publicationStatus}" +
            ",cacheIdentity=${if (feed.cachedFrameTargetName == null) "unbound" else if (feed.cachedFrameIdentityInvalidated) "invalidated" else "bound"}" +
            ",cardMapping=${if (!feed.hasObservedCardMapping) "unobserved" else if (feed.lastObservedCardTargetName == null) "absent" else "present"}" +
            ",guiDraw[state=${feed.lastGuiDrawState},queuedBlits=${feed.guiBlitsQueued},lastFrame=${feed.lastGuiBlitFrameCount}]"
    }

    private fun cameraDiagnosticState(feed: FeedState, cameraBinding: String): String {
        val player = feed.player ?: return "target=$cameraBinding,subject=none,entity=none"
        val position = nullableLegacyValue(feed.camera.position())
        if (position == null) return "target=$cameraBinding,subject=uninitialized,entity=unknown"
        val cameraEntityMatches = feed.camera.entity() === player
        val subject = if (feed.fixedCameraProbe) "local-player-probe" else "teammate"
        val entity = if (cameraEntityMatches) "target-match" else "target-mismatch"
        return "target=$cameraBinding,subject=$subject,entity=$entity,id=${player.uuid}" +
            String.format(Locale.ROOT, ",offset=[%.2f,%.2f,%.2f],yaw=%.1f,pitch=%.1f,fov=%.1f",
                position.x - player.x, position.y - player.y, position.z - player.z,
                feed.camera.yRot(), feed.camera.xRot(), feed.camera.getFov())
    }

    private fun routeDiagnostic(observed: Boolean, verified: Boolean): String =
        if (!observed) "not-observed" else if (verified) "verified" else "mismatch"

    fun draw(graphics: GuiGraphicsExtractor, screenWidth: Int, screenHeight: Int, cards: List<SpiritLeapRoster.Card>) {
        SpiritLeapCameraHud.INSTANCE.draw(graphics, screenWidth, screenHeight, cards)
    }

    @JvmStatic
    fun panelDrawBounds(quadrant: Int, screenWidth: Int, screenHeight: Int): SpiritLeapLayout.Bounds? =
        SpiritLeapCameraHud.quadrantBounds(quadrant, screenWidth, screenHeight)

    @JvmRecord
    data class PanelBlit(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val u0: Float, val u1: Float, val v0: Float, val v1: Float) {
        override fun toString() =
            "PanelBlit[x0=$x0, y0=$y0, x1=$x1, y1=$y1, u0=$u0, u1=$u1, v0=$v0, v1=$v1]"
    }

    @JvmStatic fun cameraPanelPipeline(): RenderPipeline = CAMERA_PANEL_PIPELINE

    @JvmStatic
    fun panelBlit(quadrant: Int, screenWidth: Int, screenHeight: Int): PanelBlit? {
        val bounds = panelDrawBounds(quadrant, screenWidth, screenHeight) ?: return null
        return PanelBlit(bounds.x(), bounds.y(), bounds.x() + bounds.width(), bounds.y() + bounds.height(), 0.0F, 1.0F, 1.0F, 0.0F)
    }

    @JvmStatic
    fun panelOpacityAlpha(transparencyPercent: Int): Int {
        val bounded = Mth.clamp(transparencyPercent, 0, 100)
        return Math.round((100 - bounded) * 255.0F / 100.0F)
    }

    fun drawPanel(
        graphics: GuiGraphicsExtractor, screenWidth: Int, screenHeight: Int,
        cards: List<SpiritLeapRoster.Card>, quadrant: Int, boundPlayerId: UUID?,
    ) {
        if (quadrant < 0 || quadrant >= FEED_COUNT) return
        val now = System.nanoTime()
        if (pendingRenderStateRestore.pending() || renderStateRecoveryState.awaitingCompletedFrameBaseline()) return
        val feed = feeds[quadrant]
        val probeEnabled = fixedCameraProbeEnabled()
        val povsEnabled = SpiritLeapMenuModule.povsEnabled()
        if (!probeEnabled) feed.observeCardMapping(cards)
        val bounds = panelDrawBounds(quadrant, screenWidth, screenHeight)
        val identityAllows = !probeEnabled && boundPlayerId != null && boundPlayerId == feed.cachedFrameTargetId &&
            shouldDisplayCachedFrameIdentity(feed.cachedFrameTargetName, quadrant, cards,
                feed.cachedFrameIdentityInvalidated, feed.hasObservedCardMapping, feed.lastObservedCardTargetName)
        val ageAndTargetAllow = shouldDisplayQuadrant(
            povsEnabled, feed.frameAvailable, feed.lastSuccessfulFrameNanos, now,
            feed.frameTarget != null, feed.quadrant, quadrant,
        )
        feed.lastGuiDrawState = if (!povsEnabled) "disabled"
        else if (!feed.frameAvailable || feed.frameTarget == null) "no-frame"
        else if (!isCachedFrameFresh(feed.frameAvailable, feed.lastSuccessfulFrameNanos, now)) "expired"
        else if (boundPlayerId == null) "identity-unbound"
        else if (!identityAllows) if (feed.cachedFrameIdentityInvalidated) "identity-invalidated" else "identity-mismatch"
        else if (ageAndTargetAllow && bounds != null) "queued" else "hidden"
        if (!ageAndTargetAllow || !identityAllows || bounds == null) return
        val blit = panelBlit(quadrant, screenWidth, screenHeight) ?: return
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        // Framebuffer alpha is not coverage: sky may carry zero alpha. Apply only the user opacity.
        val color = (panelOpacityAlpha(SpiritLeapMenuModule.povTransparencyPercent()) shl 24) or 0x00FFFFFF
        graphics.guiRenderState.addGuiElement(
            BlitRenderState(
                CAMERA_PANEL_PIPELINE,
                TextureSetup.singleTexture(feed.frameTarget!!.getColorTextureView()!!, sampler),
                Matrix3x2f(graphics.pose()), blit.x0, blit.y0, blit.x1, blit.y1,
                blit.u0, blit.u1, blit.v0, blit.v1, color,
                (graphics as GuiGraphicsExtractorScissorAccess).`geileraddons$getScissorArea`(),
            ),
        )
        feed.recordGuiBlit()
    }

    fun draw(graphics: GuiGraphicsExtractor, screenWidth: Int, screenHeight: Int) {
        val client = Minecraft.getInstance()
        val screen = client.gui.screen()
        val cards = if (screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) {
            DungeonOdinAssistModule.INSTANCE.leapCards(screen)
        } else emptyList()
        SpiritLeapCameraHud.INSTANCE.draw(graphics, screenWidth, screenHeight, cards)
    }

    /** Publishes the private target only after the deferred GUI blits have been consumed. */
    private fun publishPendingFrameAfterGuiPass() {
        if (!RenderSystem.isOnRenderThread()) return
        val quadrant = guiFramePublication.takeAfterGuiRender()
        if (quadrant < 0) return
        val completedTarget = pendingPublicationTarget
        pendingPublicationTarget = null
        val feed = feeds[quadrant]
        val client = Minecraft.getInstance()
        if (!isActive(client)) {
            clearPublishedFrameState("cleared-menu-close")
            povSessionState.menuClosed()
            warmupQuadrant = -1
            feed.publicationStatus = "discarded-menu-closed"
            return
        }
        if (completedTarget == null || completedTarget !== scratchTarget || activeRenderTarget != null ||
            pendingRenderStateRestore.pending() || renderStateRecoveryState.awaitingCompletedFrameBaseline() ||
            !povSessionState.publishAfterGuiPass(true, true, true)
        ) {
            feed.stage = "gui-publication"
            feed.reason = "discarded-incomplete-gui-publication"
            feed.publicationStatus = "discarded-incomplete"
            return
        }
        val previousFrameTarget = feed.frameTarget
        feed.frameTarget = completedTarget
        scratchTarget = previousFrameTarget
        feed.frameAvailable = true
        feed.lastSuccessfulFrameNanos = System.nanoTime()
        feed.cachedFrameTargetName = feed.targetName
        feed.cachedFrameTargetId = feed.player?.uuid
        feed.cachedFrameIdentityInvalidated = !feed.fixedCameraProbe && !feed.matchesObservedCardTarget(feed.targetName)
        feed.lastGuiDrawState = "awaiting-draw"
        feed.stage = "gui-publication"
        feed.reason = "published-after-gui-pass"
        feed.status = "ready"
        feed.publicationStatus = "published-after-gui-pass"
        if (feed.successfulFrameCount < Long.MAX_VALUE) feed.successfulFrameCount++
    }

    private fun discardPendingGuiPublication(reason: String) {
        val quadrant = guiFramePublication.discard()
        pendingPublicationTarget = null
        if (quadrant < 0) return
        val feed = feeds[quadrant]
        feed.stage = "gui-publication"
        feed.reason = reason
        feed.publicationStatus = reason
    }

    /** Called before the main camera extraction; no work is done outside the active menu. */
    fun beforeMainCameraExtraction(allocator: GraphicsResourceAllocator, deltaTracker: DeltaTracker, renderSky: Boolean) {
        if (!RenderSystem.isOnRenderThread()) return
        observeToggleState()
        // A failed restore keeps the original safe snapshot and blocks another render or cleanup attempt.
        if (!retryPendingRenderStateRestore()) {
            feedTargetCleanupReason = "original-state-restore-pending"
            clearLoadedPlayerLookup()
            return
        }
        if (renderStateRecoveryState.awaitingCompletedFrameBaseline()) {
            feedTargetCleanupReason = "waiting-for-completed-frame-state-baseline"
            clearLoadedPlayerLookup()
            return
        }
        val client = Minecraft.getInstance()
        val active = isActive(client)
        if (guiFramePublication.pending()) {
            if (active) return
            discardPendingGuiPublication("discarded-menu-closed")
        }
        val screen = client.gui.screen()
        val cards: List<SpiritLeapRoster.Card> = if (active && client.level != null && client.player != null &&
            screen is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
        ) DungeonOdinAssistModule.INSTANCE.leapCards(screen) else emptyList()
        if (active && client.level != null) refreshLoadedPlayerLookup(client.level!!) else clearLoadedPlayerLookup()
        if ((feedTargetCleanupPending || rendererCleanupPending) && shouldAttemptDeferredTargetCleanup(true, true)) {
            tryDeferredFeedTargetCleanup()
            if (pendingRenderStateRestore.pending() || feedTargetCleanupPending || rendererCleanupPending) return
        }
        // Resource reload only touches shared renderer state after state restoration and cleanup.
        replayPendingResourceReload()
        if (pendingRenderStateRestore.pending() || feedTargetCleanupPending || rendererCleanupPending) return
        applyPendingLifecycleReset()
        if (active && povSessionState.failureLatched()) {
            markProbeFailureFeeds()
            return
        }
        var now = System.nanoTime()
        val profile = if (active) rendererProfile(deltaTracker, cards) else null
        if (pendingRenderStateRestore.pending() || feedTargetCleanupPending || rendererCleanupPending) return
        val decision = profile?.decision
        var unsupported = if (decision != null && decision.maxFeeds == 0) decision.reason else null
        val enabledQuadrants = eligibleFeedQuadrants(decision, cards, fixedCameraProbeEnabled())
        if (decision != null && decision.maxFeeds > 0) {
            val targetWidth = maxOf(1, client.window.width / 2)
            val targetHeight = maxOf(1, client.window.height / 2)
            if (!isFeedTargetAllocationWithinBudget(targetWidth, targetHeight, decision.maxFeeds)) {
                unsupported = "feed-target-pixel-budget-exceeded"
                releaseRendererIfAllocated()
                if (pendingRenderStateRestore.pending() || feedTargetCleanupPending || rendererCleanupPending) return
            }
        }
        for (feed in feeds) {
            if (decision != null && decision.maxFeeds > 0 && !enabledQuadrants[feed.quadrant]) {
                clearFeedSelection(feed)
                feed.stage = "gated"
                feed.reason = if (fixedCameraProbeEnabled()) "fixed-camera-probe-q0-only"
                else if (findEligibleCardForQuadrant(feed.quadrant, cards) == null) "no-eligible-card"
                else "feed-limit-${decision.maxFeeds}"
                feed.status = "unavailable(reason=${feed.reason})"
                continue
            }
            prepareFeed(feed, client, active, unsupported, cards, now)
            if (pendingRenderStateRestore.pending() || feedTargetCleanupPending || rendererCleanupPending) return
        }
        if (!active || decision == null || decision.maxFeeds == 0 || client.level == null || client.player == null) return
        if (povSessionState.failureLatched()) {
            markProbeFailureFeeds()
            return
        }
        now = System.nanoTime()
        val updateFps = SpiritLeapMenuModule.povUpdatesPerSecond()
        if (!feedScheduler.canStartRender(now, updateFps)) {
            markRenderScheduleWait(decision.maxFeeds, now, updateFps)
            return
        }
        val quadrant: Int
        if (!povSessionState.warmupPromoted()) {
            warmupQuadrant = firstReadyQuadrant(decision.maxFeeds, feeds)
            quadrant = warmupQuadrant
            if (quadrant < 0) {
                val selectedQuadrants = selectedFeedQuadrants(decision.maxFeeds, feeds)
                for (feedIndex in 0 until FEED_COUNT) {
                    if (!selectedQuadrants[feedIndex]) continue
                    val feed = feeds[feedIndex]
                    if (feed.targetName == null) continue
                    feed.stage = "warmup-wait"
                    feed.reason = "waiting-for-first-loaded-view"
                }
                return
            }
        } else {
            warmupQuadrant = -1
            quadrant = feedScheduler.nextDueQuadrant(decision.maxFeeds, feeds, now, updateFps)
        }
        if (quadrant >= 0) {
            renderFeed(feeds[quadrant], allocator, deltaTracker, renderSky, client, now, decision.maxFeeds, updateFps)
            return
        }
        val selectedQuadrants = selectedFeedQuadrants(decision.maxFeeds, feeds)
        for (feedIndex in 0 until FEED_COUNT) {
            if (!selectedQuadrants[feedIndex]) continue
            val feed = feeds[feedIndex]
            if (feed.targetName == null || !feed.chunkNeighborhoodLoaded) continue
            feed.stage = "render-schedule"
            feed.reason = if (feed.hasUpdated && !shouldUpdateFeed(now, feed.lastUpdateNanos, true, updateFps)) {
                "per-feed-fps-cap"
            } else "staggered-wait"
        }
    }

    private fun markRenderScheduleWait(activeFeedCount: Int, nowNanos: Long, updateFps: Int) {
        val selectedQuadrants = selectedFeedQuadrants(activeFeedCount, feeds)
        for (feedIndex in 0 until FEED_COUNT) {
            if (!selectedQuadrants[feedIndex]) continue
            val feed = feeds[feedIndex]
            if (feed.targetName == null || !feed.chunkNeighborhoodLoaded) continue
            feed.stage = "render-schedule"
            feed.reason = if (feed.hasUpdated && !shouldUpdateFeed(nowNanos, feed.lastUpdateNanos, true, updateFps)) {
                "per-feed-fps-cap"
            } else "global-render-gap"
        }
    }

    /** Runs after every client frame so deferred cleanup can progress from title screens too. */
    fun maintainDeferredCleanupAfterFrame() {
        if (!RenderSystem.isOnRenderThread() || activeRenderTarget != null) return
        publishPendingFrameAfterGuiPass()
        queuePublishedScreenPixelSample()
        if (!pendingRenderStateRestore.pending() && !feedTargetCleanupPending && !rendererCleanupPending &&
            !lifecycleResetPending && !pendingResourceReload.pending() && !renderStateRecoveryState.awaitingCompletedFrameBaseline()
        ) return
        if (pendingRenderStateRestore.pending() && !retryPendingRenderStateRestore()) return
        if (!establishFreshRenderStateBaselineAfterCompletedFrame()) return
        if (feedTargetCleanupPending || rendererCleanupPending) tryDeferredFeedTargetCleanup()
        applyPendingLifecycleReset()
        replayPendingResourceReload()
    }

    private fun requestSourcePixelSample(feed: FeedState, target: RenderTarget?, frameSerial: Long, nowNanos: Long) {
        if (pixelSampleCopyPending || pendingPixelSample != null || !shouldSamplePixel(feed.lastPixelSampleNanos, nowNanos)) return
        feed.lastPixelSampleNanos = nowNanos
        val client = Minecraft.getInstance()
        val guiWidth = client.window.guiScaledWidth
        val guiHeight = client.window.guiScaledHeight
        val region = if (target == null) null else unobscuredPixelSampleRegion(
            feed.quadrant, guiWidth, guiHeight, target.width, target.height, PIXEL_SAMPLE_SIZE,
            SpiritLeapMenuModule.cardDistance.value(),
        )
        if (region == null) {
            feed.sourcePixelSample = "unavailable(no-exposed-sample-region)"
            return
        }
        val sample = PendingPixelSample(feed, frameSerial, lifecycleGeneration.get())
        pendingPixelSample = sample
        feed.sourcePixelSample = "pending(frame=$frameSerial)"
        pixelSampleCopyPending = true
        queuePixelSampleCopy(target, sample, false, region)
    }

    private fun queuePublishedScreenPixelSample() {
        val sample = pendingPixelSample ?: return
        if (!sample.awaitingScreenSample || pixelSampleCopyPending) return
        val client = Minecraft.getInstance()
        if (sample.lifecycleGeneration != lifecycleGeneration.get() || !isActive(client)) {
            pendingPixelSample = null
            return
        }
        val feed = sample.feed
        if (feed.successfulFrameCount < sample.frameSerial || feed.lastGuiBlitFrameCount < sample.frameSerial) return
        if (feed.lastGuiBlitFrameCount != sample.frameSerial) {
            feed.screenPixelSample = "skipped(frame-outpaced=${sample.frameSerial})"
            pendingPixelSample = null
            return
        }
        val mainTarget = nullableLegacyValue(client.gameRenderer.mainRenderTarget())
        if (mainTarget == null) {
            feed.screenPixelSample = "unavailable(main-target)"
            pendingPixelSample = null
            return
        }
        val guiWidth = client.window.guiScaledWidth
        val guiHeight = client.window.guiScaledHeight
        val region = screenPixelSampleRegion(
            feed.quadrant, guiWidth, guiHeight, mainTarget.width, mainTarget.height, PIXEL_SAMPLE_SIZE,
            SpiritLeapMenuModule.cardDistance.value(),
        )
        if (region == null) {
            feed.screenPixelSample = "unavailable(size)"
            pendingPixelSample = null
            return
        }
        feed.screenPixelSample = "pending(frame=${sample.frameSerial})"
        pixelSampleCopyPending = true
        queuePixelSampleCopy(mainTarget, sample, true, region)
    }

    private fun queuePixelSampleCopy(target: RenderTarget?, sample: PendingPixelSample, screenSample: Boolean): Boolean {
        val sampleCenterX = target?.width?.div(4) ?: 0
        val sampleCenterY = target?.height?.div(4) ?: 0
        val region = if (target == null) null else pixelSampleRegion(
            target.width, target.height, sampleCenterX, sampleCenterY, PIXEL_SAMPLE_SIZE,
        )
        return queuePixelSampleCopy(target, sample, screenSample, region)
    }

    private fun queuePixelSampleCopy(
        target: RenderTarget?, sample: PendingPixelSample, screenSample: Boolean, region: PixelSampleRegion?,
    ): Boolean {
        val feed = sample.feed
        val texture = target?.getColorTexture()
        if (texture == null || texture.isClosed() || texture.usage() and GpuTexture.USAGE_COPY_SRC == 0 ||
            region == null || region.width != PIXEL_SAMPLE_SIZE || region.height != PIXEL_SAMPLE_SIZE
        ) {
            setPixelSampleState(feed, screenSample, "unavailable(copy-source)")
            pixelSampleCopyPending = false
            if (pendingPixelSample === sample) pendingPixelSample = null
            return false
        }
        var readback: GpuBuffer? = null
        var stateSnapshot: RenderStateSnapshot? = null
        var queued = false
        var submitFailure: Throwable? = null
        try {
            val readbackBuffer = RenderSystem.getDevice().createBuffer(
                { "Spirit Leap POV pixel sample" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ,
                PIXEL_SAMPLE_BYTES.toLong(),
            )
            readback = readbackBuffer
            stateSnapshot = captureRenderState(RENDER_STATE)
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                texture, readbackBuffer, 0L, { completePixelSample(readbackBuffer, sample, screenSample) }, 0,
                region.x, region.y, region.width, region.height,
            )
            queued = true
        } catch (failure: RuntimeException) {
            submitFailure = failure
        } catch (failure: Error) {
            submitFailure = failure
        } finally {
            val snapshot = stateSnapshot
            if (snapshot != null) {
                try {
                    restoreRenderState(RENDER_STATE, snapshot)
                } catch (restoreFailure: RuntimeException) {
                    retainPendingRenderStateRestore(snapshot)
                    deferFullResourceCleanup("pixel-sample-state-restore-failed")
                    if (submitFailure == null) submitFailure = restoreFailure
                    else SpiritLeapPovRenderAttempt.combineFailure(submitFailure, restoreFailure)
                } catch (restoreFailure: Error) {
                    retainPendingRenderStateRestore(snapshot)
                    deferFullResourceCleanup("pixel-sample-state-restore-failed")
                    if (submitFailure == null) submitFailure = restoreFailure
                    else SpiritLeapPovRenderAttempt.combineFailure(submitFailure, restoreFailure)
                }
            }
            if (!queued && readback != null) {
                try {
                    readback.close()
                } catch (closeFailure: RuntimeException) {
                    if (submitFailure == null) submitFailure = closeFailure
                    else SpiritLeapPovRenderAttempt.combineFailure(submitFailure, closeFailure)
                } catch (closeFailure: Error) {
                    if (submitFailure == null) submitFailure = closeFailure
                    else SpiritLeapPovRenderAttempt.combineFailure(submitFailure, closeFailure)
                }
            }
        }
        val failure = submitFailure
        if (failure != null) {
            setPixelSampleState(feed, screenSample, "failed(${boundedFailureType(failure.javaClass.simpleName)})")
            pixelSampleCopyPending = false
            if (pendingPixelSample === sample) pendingPixelSample = null
            if (failure is Error) throw failure
            return false
        }
        return queued
    }

    private fun completePixelSample(buffer: GpuBuffer, sample: PendingPixelSample, screenSample: Boolean) {
        val complete = Runnable {
            var result: String
            try {
                buffer.slice().map(true, false).use { mapped ->
                    val summary = summarizePixelBytes(mapped.data(), PIXEL_SAMPLE_SIZE * PIXEL_SAMPLE_SIZE)
                    result = if (summary == null) "failed(short-read)" else
                        "frame=${sample.frameSerial}/pixels=${summary.pixels}/nonBlack=${summary.nonBlackPixels}/colors=${summary.distinctColors}"
                }
            } catch (failure: RuntimeException) {
                result = "failed(${boundedFailureType(failure.javaClass.simpleName)})"
            } catch (failure: Error) {
                result = "failed(${boundedFailureType(failure.javaClass.simpleName)})"
            } finally {
                try { buffer.close() } catch (_: RuntimeException) { } catch (_: Error) { }
                pixelSampleCopyPending = false
            }
            if (pendingPixelSample !== sample) return@Runnable
            if (sample.lifecycleGeneration != lifecycleGeneration.get()) {
                pendingPixelSample = null
                return@Runnable
            }
            setPixelSampleState(sample.feed, screenSample, result)
            if (shouldAwaitScreenPixelSample(screenSample, result)) sample.awaitingScreenSample = true
            else pendingPixelSample = null
        }
        if (RenderSystem.isOnRenderThread()) complete.run() else Minecraft.getInstance().execute(complete)
    }

    private fun setPixelSampleState(feed: FeedState, screenSample: Boolean, state: String) {
        if (screenSample) feed.screenPixelSample = state else feed.sourcePixelSample = state
    }

    private fun prepareFeed(
        feed: FeedState, client: Minecraft, active: Boolean, unsupported: String?,
        cards: List<SpiritLeapRoster.Card>, now: Long,
    ) {
        val probeEnabled = fixedCameraProbeEnabled()
        if (!probeEnabled) feed.fixedCameraProbe = false
        if (!active) {
            if (shouldReleaseForInactiveMenu(false, hasAllocatedResources())) closeNow()
            resetFeedCamera(feed)
            feed.fixedCameraProbe = false
            povSessionState.menuClosed()
            warmupQuadrant = -1
            feed.frameAvailable = false
            feed.lastSuccessfulFrameNanos = 0L
            clearCachedFrameIdentity(feed)
            feed.stage = "idle"
            feed.reason = "menu-closed"
            feed.status = if (SpiritLeapMenuModule.INSTANCE.isEnabled() && SpiritLeapMenuModule.povsEnabled()) {
                "idle(reason=menu-closed)"
            } else "disabled"
            return
        }
        if (unsupported != null) {
            releaseRendererIfAllocated()
            resetFeedCamera(feed)
            feed.fixedCameraProbe = false
            feed.stage = "compatibility-gate"
            feed.reason = unsupported
            feed.status = "unavailable(reason=$unsupported)"
            return
        }
        val level = client.level
        if (level == null || client.player == null) {
            releaseIfAllocated()
            resetFeedCamera(feed)
            feed.fixedCameraProbe = false
            feed.frameAvailable = false
            feed.lastSuccessfulFrameNanos = 0L
            clearCachedFrameIdentity(feed)
            feed.stage = "world-check"
            feed.reason = "world-not-ready"
            feed.status = "unavailable(reason=world-not-ready)"
            return
        }
        if (attachedLevel != null && attachedLevel !== level) {
            closeNow()
            for (resetFeed in feeds) resetFeed.lastSelectionTryNanos = 0L
        }
        if (rendererCleanupFailed) {
            feed.stage = "cleanup"
            feed.reason = "renderer-cleanup-failed"
            feed.status = "unavailable(reason=renderer-cleanup-failed)"
            return
        }
        if (probeEnabled) {
            prepareFixedCameraProbeFeed(feed, client, now)
            return
        }
        val selectionStarted = System.nanoTime()
        feed.stage = "teammate-selection"
        feed.reason = "checking"
        if (!selectLoadedFeed(feed, level, now, cards)) {
            feed.selectionDurationMicros = elapsedMicros(selectionStarted)
            return
        }
        feed.selectionDurationMicros = elapsedMicros(selectionStarted)
        feed.stage = "loaded-chunk-check"
        feed.reason = "checking-near-field"
        val chunkCheckStarted = System.nanoTime()
        if (!hasLoadedViewNeighborhood(feed, level, now)) {
            feed.chunkCheckDurationMicros = elapsedMicros(chunkCheckStarted)
            feed.reason = "target-chunks-not-loaded"
            feed.status = "unavailable(reason=target-chunks-not-loaded)"
            return
        }
        feed.chunkCheckDurationMicros = elapsedMicros(chunkCheckStarted)
        feed.reason = "near-field-loaded"
        if (feed.status == "unavailable(reason=target-chunks-not-loaded)") feed.status = "waiting(reason=target-chunks-ready)"
        if (!feed.frameAvailable && feed.status.startsWith("idle(")) feed.status = "waiting(reason=first-frame)"
    }

    private fun prepareFixedCameraProbeFeed(feed: FeedState, client: Minecraft, now: Long) {
        feed.stage = "fixed-camera-probe"
        if (!shouldRenderFixedCameraProbeQuadrant(true, feed.quadrant)) {
            clearFeedSelection(feed)
            clearCachedFrameForProbe(feed, "fixed-camera-probe-hidden")
            feed.stage = "fixed-camera-probe"
            feed.reason = "single-feed-q0-only"
            feed.status = "gated(reason=fixed-camera-probe-q0-only)"
            feed.lastGuiDrawState = "fixed-probe-hidden"
            return
        }
        val selectionStarted = System.nanoTime()
        val targetChanged = FIXED_CAMERA_TARGET_NAME != feed.targetName || feed.player !== client.player
        if (targetChanged) {
            clearFeedSelection(feed)
            clearCachedFrameForProbe(feed, "fixed-camera-probe-target-changed")
            feed.targetName = FIXED_CAMERA_TARGET_NAME
            feed.member = null
            feed.player = client.player
            feed.hasUpdated = false
            feed.lastUpdateNanos = 0L
            feed.status = "waiting(reason=first-fixed-camera-frame)"
        }
        feed.fixedCameraProbe = true
        feed.selectionDurationMicros = elapsedMicros(selectionStarted)
        feed.reason = "local-player-offset-view"
        val chunkCheckStarted = System.nanoTime()
        val level = client.level ?: return
        if (!hasLoadedViewNeighborhood(feed, level, now)) {
            feed.chunkCheckDurationMicros = elapsedMicros(chunkCheckStarted)
            feed.reason = "local-view-chunks-not-loaded"
            feed.status = "unavailable(reason=probe-chunks-not-loaded)"
            return
        }
        feed.chunkCheckDurationMicros = elapsedMicros(chunkCheckStarted)
        feed.reason = "loaded-local-view"
        if (!feed.frameAvailable && feed.status.startsWith("idle(")) feed.status = "waiting(reason=first-fixed-camera-frame)"
    }

    private fun clearCachedFrameForProbe(feed: FeedState, publicationStatus: String) {
        feed.frameAvailable = false
        feed.lastSuccessfulFrameNanos = 0L
        feed.cachedFrameTargetName = null
        feed.cachedFrameTargetId = null
        feed.cachedFrameIdentityInvalidated = false
        feed.lastGuiDrawState = "hidden"
        feed.publicationStatus = publicationStatus
    }

    private fun renderFeed(
        feed: FeedState, allocator: GraphicsResourceAllocator, deltaTracker: DeltaTracker, renderSky: Boolean,
        client: Minecraft, now: Long, activeFeedCount: Int, updateFps: Int,
    ) {
        if (!shouldRenderFeed(feed.targetName != null, feed.chunkNeighborhoodLoaded, rendererCleanupFailed)) return
        if (povSessionState.failureLatched()) return
        if (!shouldUpdateFeed(now, feed.lastUpdateNanos, feed.hasUpdated, updateFps)) {
            feed.stage = "render-schedule"
            feed.reason = "per-feed-fps-cap"
            return
        }
        feed.lastUpdateNanos = now
        feed.hasUpdated = true
        feed.lastLevelTargetRoute = "not-observed"
        feed.lastFrameGraphClearRoute = "not-observed"
        feed.levelTargetRouteObserved = false
        feed.levelTargetRouteMismatch = false
        feed.frameGraphClearRouteObserved = false
        feed.frameGraphClearRouteMismatch = false
        feed.layerGroupOpaqueObserved = false
        feed.layerGroupOpaqueVerified = false
        feed.layerGroupTranslucentObserved = false
        feed.layerGroupTranslucentVerified = false
        feed.vanillaTerrainBatches = 0L
        feed.sodiumLayerCalls = 0L
        feed.nvidiumWorldPasses = 0L
        feed.nvidiumTranslucentPasses = 0L
        feed.visibleTerrainSections = 0
        feed.globalSettingsUniformState = "not-applied"
        feed.rendererInitDurationMicros = 0L
        feed.targetAllocationDurationMicros = 0L
        feed.privateRenderDurationMicros = 0L
        feed.renderStateSnapshotDurationMicros = 0L
        feed.renderStateRestoreDurationMicros = 0L
        val attemptStarted = System.nanoTime()
        var stateSnapshot: RenderStateSnapshot? = null
        var attemptFailure: Throwable? = null
        var attemptFailureStage = "none"
        var stateRestorationSucceeded = false
        var privateRenderSucceeded = false
        val previousRenderingFeed = renderingFeed
        renderingFeed = feed
        try {
            feed.stage = "render-state-snapshot"
            feed.reason = "saving-render-state"
            val stateStarted = System.nanoTime()
            try {
                stateSnapshot = captureRenderState(RENDER_STATE)
            } finally {
                feed.renderStateSnapshotDurationMicros = elapsedMicros(stateStarted)
            }
            feed.stage = SpiritLeapPovRenderAttempt.CAMERA_STAGE
            feed.reason = "priming-camera"
            prepareFeedCamera(feed, client.level, feed.player)
            feed.camera.update(deltaTracker)
            feed.stage = "private-renderer-initialization"
            feed.reason = "initializing"
            val rendererInitStarted = System.nanoTime()
            try {
                ensureRenderer(client, feed, deltaTracker)
            } finally {
                feed.rendererInitDurationMicros = elapsedMicros(rendererInitStarted)
            }
            feed.stage = "target-allocation"
            feed.reason = "allocating"
            val targetAllocationStarted = System.nanoTime()
            try {
                if (!ensureTarget(feed, client, activeFeedCount)) return
            } finally {
                feed.targetAllocationDurationMicros = elapsedMicros(targetAllocationStarted)
            }
            feed.reason = "ready"
            renderAttemptOperations.configure(feed, client, allocator, deltaTracker, renderSky)
            val gameRenderState = client.gameRenderer.gameRenderState()
            val activeRenderer = renderer!!
            val previousCameraType = gameRenderState.optionsRenderState.cameraType
            val previousScopedGameRenderState = scopedGameRenderState
            val previousPovPass = activePovPass
            val previousActiveTarget = activeRenderTarget
            val gameLevelState = object : MutableStateRef<LevelRenderState> {
                override fun get(): LevelRenderState? =
                    (gameRenderState as GameRenderStateSpiritLeapAccessor).`geiler$getLevelRenderState`()
                override fun set(state: LevelRenderState?) {
                    (gameRenderState as GameRenderStateSpiritLeapAccessor).`geiler$setLevelRenderState`(state)
                }
            }
            val rendererLevelState = object : MutableStateRef<LevelRenderState> {
                override fun get(): LevelRenderState? =
                    (activeRenderer as LevelRenderStateSpiritLeapAccessor).`geiler$getLevelRenderState`()
                override fun set(state: LevelRenderState?) {
                    (activeRenderer as LevelRenderStateSpiritLeapAccessor).`geiler$setLevelRenderState`(state)
                }
            }
            var scopedPassFailure: Throwable? = null
            try {
                withTemporaryState(gameLevelState, rendererLevelState, feed.levelRenderState) {
                    scopedGameRenderState = gameRenderState
                    activePovPass = true
                    activeRenderTarget = scratchTarget
                    gameRenderState.optionsRenderState.cameraType = CameraType.FIRST_PERSON
                    try {
                        renderAttempt.execute(renderAttemptOperations)
                    } finally {
                        gameRenderState.optionsRenderState.cameraType = previousCameraType
                        activeRenderTarget = previousActiveTarget
                        activePovPass = previousPovPass
                        scopedGameRenderState = previousScopedGameRenderState
                    }
                }
            } catch (failure: RuntimeException) {
                scopedPassFailure = failure
            } catch (failure: Error) {
                scopedPassFailure = failure
            } finally {
                renderAttemptOperations.clear()
            }
            if (scopedPassFailure is Error) throw scopedPassFailure
            if (scopedPassFailure is RuntimeException) throw scopedPassFailure
            attemptFailure = renderAttempt.failure()
            val routeFailure = privateRenderTargetRouteFailure(
                feed.levelTargetRouteObserved, feed.levelTargetRouteMismatch,
                feed.frameGraphClearRouteObserved, feed.frameGraphClearRouteMismatch,
            )
            var routeRejectedAttempt = false
            if (routeFailure != null && attemptFailure == null) {
                attemptFailure = IllegalStateException("private-render-target-route-$routeFailure")
                routeRejectedAttempt = true
            }
            if (attemptFailure != null) {
                val observedRouteMismatch = feed.levelTargetRouteMismatch || feed.frameGraphClearRouteMismatch
                if (routeRejectedAttempt || observedRouteMismatch) {
                    attemptFailureStage = "target-routing"
                    feed.reason = routeFailure!!
                } else {
                    attemptFailureStage = renderAttempt.failureStage()
                }
                feed.stage = attemptFailureStage
            } else {
                privateRenderSucceeded = true
            }
            if (attemptFailure == null) {
                if (FabricLoader.getInstance().isModLoaded("sodium")) {
                    if (hasObservedWrongSodiumRoute(
                            feed.sodiumMainTargetRouteObserved, feed.sodiumMainTargetRouteVerified,
                            feed.sodiumTranslucentTargetRouteObserved, feed.sodiumTranslucentTargetRouteVerified,
                        )
                    ) throw IllegalStateException("observed-private-terrain-route-mismatch")
                    val observed = feed.sodiumMainTargetRouteObserved || feed.sodiumTranslucentTargetRouteObserved
                    sodiumTargetRouteStatus = if (observed) "private-route-observed" else "routes-not-observed-after-capability-preflight"
                }
                feed.stage = "render-result"
                feed.reason = "awaiting-state-restore"
            }
        } catch (failure: RuntimeException) {
            attemptFailure = failure
            attemptFailureStage = feed.stage
        } catch (failure: Error) {
            attemptFailure = failure
            attemptFailureStage = feed.stage
        } finally {
            if (attemptFailure != null) povSessionState.latchFailure()
            val snapshot = stateSnapshot
            if (snapshot != null) {
                if (attemptFailure == null) {
                    feed.stage = "state-restoration"
                    feed.reason = "restoring-render-state"
                }
                val restoreStarted = System.nanoTime()
                try {
                    restoreRenderState(RENDER_STATE, snapshot)
                    stateRestorationSucceeded = true
                    if (attemptFailure == null) {
                        feed.stage = "render-result"
                        feed.reason = "awaiting-state-restore"
                    }
                } catch (restoreFailure: RuntimeException) {
                    if (attemptFailure == null) attemptFailureStage = "state-restoration"
                    attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, restoreFailure)
                    povSessionState.latchFailure()
                    try {
                        restoreRenderState(RENDER_STATE, snapshot)
                        stateRestorationSucceeded = true
                    } catch (finalRestoreFailure: RuntimeException) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, finalRestoreFailure)
                    } catch (finalRestoreFailure: Error) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, finalRestoreFailure)
                    }
                } catch (restoreFailure: Error) {
                    if (attemptFailure == null) attemptFailureStage = "state-restoration"
                    attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, restoreFailure)
                    povSessionState.latchFailure()
                    try {
                        restoreRenderState(RENDER_STATE, snapshot)
                        stateRestorationSucceeded = true
                    } catch (finalRestoreFailure: RuntimeException) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, finalRestoreFailure)
                    } catch (finalRestoreFailure: Error) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, finalRestoreFailure)
                    }
                } finally {
                    feed.renderStateRestoreDurationMicros = elapsedMicros(restoreStarted)
                }
            }
            if (attemptFailure != null) {
                if (stateRestorationSucceeded) {
                    try {
                        closeNow(false)
                    } catch (cleanupFailure: RuntimeException) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, cleanupFailure)
                        failClosedOnCleanupFailure(cleanupFailure)
                        deferFullResourceCleanup("render-cleanup-failed-${boundedFailureType(cleanupFailure.javaClass.simpleName)}")
                    } catch (cleanupFailure: Error) {
                        attemptFailure = SpiritLeapPovRenderAttempt.combineFailure(attemptFailure, cleanupFailure)
                        failClosedOnCleanupFailure(IllegalStateException("renderer-cleanup-failed"))
                        deferFullResourceCleanup("render-cleanup-failed-${boundedFailureType(cleanupFailure.javaClass.simpleName)}")
                    }
                } else {
                    retainPendingRenderStateRestore(stateSnapshot)
                    deferFullResourceCleanup(if (stateSnapshot == null) "render-state-snapshot-unavailable" else "render-state-restore-failed")
                }
            }
            if (attemptFailure != null) {
                latchProbeFailureSafely(feed, client.level?.gameTime ?: 0L, attemptFailure!!, attemptFailure!!, attemptFailureStage)
            }
            feed.attemptDurationMicros = elapsedMicros(attemptStarted)
            feedScheduler.renderAttemptCompleted(System.nanoTime())
            renderingFeed = previousRenderingFeed
        }
        val failure = attemptFailure
        if (failure == null && shouldPublishCompletedFrame(privateRenderSucceeded, stateRestorationSucceeded, povSessionState.failureLatched())) {
            requestSourcePixelSample(feed, scratchTarget, incrementSaturated(feed.successfulFrameCount), System.nanoTime())
            if (guiFramePublication.queue(feed.quadrant)) {
                pendingPublicationTarget = scratchTarget
                feed.stage = "gui-publication"
                feed.reason = "awaiting-gui-pass"
                feed.status = "ready(publication=awaiting-gui-pass)"
                feed.publicationStatus = "awaiting-gui-pass"
            } else {
                feed.stage = "gui-publication"
                feed.reason = "another-gui-publication-pending"
                feed.publicationStatus = "discarded-pending-publication"
            }
        }
        if (failure is Error) throw failure
    }


    /** Mirrors resource reloads without registering a second listener on the shared resource manager. */
    fun onMainResourceReload(resources: ResourceManager) {
        if (RenderSystem.isOnRenderThread()) reloadFeedResources(resources)
        else executeOnMinecraftRenderThread(Runnable { reloadFeedResources(resources) })
    }

    private fun reloadFeedResources(resources: ResourceManager?) {
        // The active renderer owns its reload lifecycle; only this feature's chain is rebuilt here.
        if (resources == null || !RenderSystem.isOnRenderThread()) return
        if (renderStateRecoveryState.awaitingCompletedFrameBaseline()) {
            pendingResourceReload.retain(resources)
            return
        }
        if (shouldDeferResourceReload(pendingRenderStateRestore.pending(), activeRenderTarget != null,
                feedTargetCleanupPending || rendererCleanupPending)) {
            pendingResourceReload.retain(resources)
            return
        }
        try {
            for (feed in feeds) {
                feed.stage = "resource-reload"
                feed.reason = "reloaded"
            }
            rebuildTransparencyChain(Minecraft.getInstance())
            status = "idle(reason=resource-reload)"
            resetSessionAfterSuccessfulReload()
        } catch (failure: RuntimeException) {
            val gameTick = Minecraft.getInstance().level?.gameTime ?: 0L
            val failureType = boundedFailureType(failure.javaClass.simpleName)
            for (feed in feeds) {
                feed.stage = "resource-reload"
                feed.reason = "reload-failed-" + failureType
                feed.status = "unavailable(reason=resource-reload-failed,type=" + failureType + ")"
            }
            latchProbeFailure(feeds[0], gameTick, failure)
            deferFullResourceCleanup("resource-reload-failed")
        }
    }

    private fun replayPendingResourceReload() {
        if (renderStateRecoveryState.awaitingCompletedFrameBaseline()) return
        val resources = pendingResourceReload.takeIfReady(
            pendingRenderStateRestore.pending(),
            activeRenderTarget != null,
            feedTargetCleanupPending || rendererCleanupPending,
        )
        if (resources != null) reloadFeedResources(resources)
    }

    private fun isActive(client: Minecraft): Boolean {
        if (!SpiritLeapMenuModule.INSTANCE.isEnabled() || !SpiritLeapMenuModule.povsEnabled() ||
            client.gui.screen() !is net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>) return false
        val screen = client.gui.screen() as net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
        val validatedSurface = DungeonOdinAssistModule.INSTANCE.shouldRenderSpiritLeapScreen(screen)
        if (validatedSurface) SpiritLeapSurfaceOwner.acquireAfterValidatedRender(screen)
        return validatedSurface || SpiritLeapSurfaceOwner.owns(screen)
    }

    private fun rendererProfile(): RendererProfile = rendererProfile(null, emptyList())

    private fun rendererProfile(
        preflightDeltaTracker: DeltaTracker?,
        cards: List<SpiritLeapRoster.Card>,
    ): RendererProfile {
        rendererProfile?.let { return it }
        val client = Minecraft.getInstance()
        val loader = FabricLoader.getInstance()
        val sodiumLoaded = loader.isModLoaded("sodium")
        val nvidiumLoaded = loader.isModLoaded("nvidium")
        val sodiumVersion = if (sodiumLoaded) installedModVersion(loader, "sodium") else null
        val nvidiumVersion = if (nvidiumLoaded) installedModVersion(loader, "nvidium") else null
        val fabricLoaderVersion = installedModVersion(loader, "fabricloader")
        val fabricApiVersion = installedModVersion(loader, "fabric-api")
        val fabricRenderingVersion = FabricRenderingContextSupport.installedVersion()
        val extractionContextAccessorApplied = FabricRenderingContextSupport.isAccessorAvailable()
        val extractionContextAccessorStatus = FabricRenderingContextSupport.diagnosticStatus()
        val unverifiedRenderModifier = firstLoadedUnverifiedRenderModifier(loader)
        val unverifiedRenderModifierVersion = if (unverifiedRenderModifier == null) null
            else installedModVersion(loader, unverifiedRenderModifier)
        val backendName = currentBackendName()
        val rendererReady = nullableLegacyValue(RenderSystem.tryGetDevice()) != null &&
            nullableLegacyValue(client.levelRenderer) != null &&
            nullableLegacyValue(client.gameRenderer.mainRenderTarget()) != null
        val preflightEligible = extractionContextAccessorApplied && sodiumLoaded &&
            isSodiumAdapterPreflightEligible(
                client.getLaunchedVersion(),
                fabricLoaderVersion,
                fabricApiVersion,
                backendName,
                sodiumVersion,
                nvidiumVersion,
                unverifiedRenderModifier,
            )
        var sodiumTargetAdapterVerified = !sodiumLoaded
        var cacheProfile = unverifiedRenderModifier != null || !sodiumLoaded || !preflightEligible
        if (unverifiedRenderModifier != null) {
            sodiumTargetRouteStatus = "blocked-by-unverified-render-modifier-" + unverifiedRenderModifier
        } else if (sodiumLoaded && !preflightEligible) {
            sodiumTargetRouteStatus = "pinned-capability-version-unavailable"
        } else if (sodiumLoaded && !sodiumAdapterVerificationAttempted) {
            val candidateQuadrant = firstEligibleLoadedQuadrant(cards, loadedPlayersByName.keys.toSet())
            val candidateCard = if (candidateQuadrant < 0) null else findEligibleCardForQuadrant(candidateQuadrant, cards)
            val candidatePlayer = if (candidateCard == null) null else findLoadedPlayer(candidateCard.member().name())
            if (candidateCard == null || candidatePlayer == null || preflightDeltaTracker == null || !rendererReady ||
                client.level == null || povSessionState.failureLatched()) {
                sodiumTargetRouteStatus = "waiting-for-loaded-teammate"
            } else {
                val candidateFeed = feeds[candidateQuadrant]
                candidateFeed.targetName = candidateCard.member().name()
                candidateFeed.member = candidateCard.member()
                candidateFeed.player = candidatePlayer
                sodiumTargetAdapterVerified = verifySodiumTerrainTargetAdapter(
                    client,
                    candidateFeed,
                    candidatePlayer,
                    preflightDeltaTracker,
                )
                cacheProfile = sodiumTargetAdapterVerified || povSessionState.failureLatched()
            }
        } else if (sodiumLoaded && sodiumAdapterVerificationAttempted &&
            sodiumTargetRouteStatus == "verified-private-route-hook") {
            sodiumTargetAdapterVerified = true
            cacheProfile = true
        }
        var decision = gateFabricExtractionContext(
            classifyRendererCompatibility(
                client.getLaunchedVersion(),
                fabricLoaderVersion,
                fabricApiVersion,
                backendName,
                sodiumVersion,
                nvidiumVersion,
                unverifiedRenderModifier,
                sodiumTargetAdapterVerified,
            ),
            fabricRenderingVersion,
            extractionContextAccessorApplied,
        )
        decision = gateSkyblockerCompatibility(
            decision,
            loader.isModLoaded(SkyblockerSpiritLeapSupport.MOD_ID),
            SkyblockerSpiritLeapSupport.installedVersion(),
            SkyblockerSpiritLeapSupport.isHookApplied(),
        )
        val profile = RendererProfile(
            client.getLaunchedVersion(),
            fabricLoaderVersion,
            fabricApiVersion,
            backendName,
            sodiumVersion,
            nvidiumVersion,
            unverifiedRenderModifier,
            unverifiedRenderModifierVersion,
            sodiumTargetAdapterVerified,
            fabricRenderingVersion,
            extractionContextAccessorStatus,
            decision,
        )
        if (rendererReady && cacheProfile) rendererProfile = profile
        return profile
    }

    @JvmStatic
    fun isSodiumAdapterPreflightEligible(
        minecraftVersion: String?,
        fabricLoaderVersion: String?,
        fabricApiVersion: String?,
        backendName: String?,
        sodiumVersion: String?,
        nvidiumVersion: String?,
    ): Boolean = isSodiumAdapterPreflightEligible(
        minecraftVersion, fabricLoaderVersion, fabricApiVersion, backendName, sodiumVersion, nvidiumVersion, null,
    )

    @JvmStatic
    fun isSodiumAdapterPreflightEligible(
        minecraftVersion: String?,
        fabricLoaderVersion: String?,
        fabricApiVersion: String?,
        backendName: String?,
        sodiumVersion: String?,
        nvidiumVersion: String?,
        unverifiedRenderModifier: String?,
    ): Boolean =
        PROBE_MINECRAFT_VERSION == minecraftVersion &&
            unverifiedRenderModifierReason(unverifiedRenderModifier) == null &&
            hasInstalledCapabilityVersion(fabricLoaderVersion) &&
            hasInstalledCapabilityVersion(fabricApiVersion) &&
            isSupportedGpuBackend(backendName) &&
            SodiumWorldRendererSpiritLeapSupport.supportsVersion(sodiumVersion) &&
            (nvidiumVersion.isNullOrBlank() || PROBE_SODIUM_VERSION == sodiumVersion) &&
            (nvidiumVersion.isNullOrBlank() || "OpenGL".equals(backendName, ignoreCase = true)) &&
            (nvidiumVersion.isNullOrBlank() ||
                NVIDIUM_CAPABILITIES_VERIFIED_FOR_26_2 && PROBE_NVIDIUM_VERSION == nvidiumVersion)

    private fun currentBackendName(): String {
        return try {
            val device = RenderSystem.tryGetDevice() ?: return "unavailable"
            val deviceInfo = nullableLegacyValue(device.getDeviceInfo()) ?: return "unavailable"
            val backendName = deviceInfo.backendName()
            if (backendName.isNullOrBlank()) "unavailable" else backendName
        } catch (_: RuntimeException) {
            "unavailable"
        } catch (_: LinkageError) {
            "unavailable"
        }
    }

    private fun verifySodiumTerrainTargetAdapter(
        client: Minecraft,
        feed: FeedState,
        povPlayer: Player,
        deltaTracker: DeltaTracker,
    ): Boolean {
        if (!RenderSystem.isOnRenderThread() || client.level == null) return false
        sodiumTerrainPassAccessor.clear()
        val width = maxOf(1, client.window.width / 2)
        val height = maxOf(1, client.window.height / 2)
        val withinPixelBudget = isFeedTargetAllocationWithinBudget(width, height, 1)
        if (!sodiumPreflightReadyForAttempt(withinPixelBudget, true)) {
            sodiumTargetRouteStatus = "target-pixel-budget-exceeded"
            return false
        }
        var stateSnapshot: RenderStateSnapshot? = null
        var restored = false
        var verified = false
        var failureReason = "private-route-preflight-failed"
        val previousRenderingFeed = renderingFeed
        val previousPovPass = activePovPass
        renderingFeed = feed
        try {
            stateSnapshot = captureRenderState(RENDER_STATE)
            ensureRenderer(client, feed, deltaTracker)
            val privateTargetReady = ensureTarget(feed, client, 1) && scratchTarget != null && renderer != null
            if (!sodiumPreflightReadyForAttempt(withinPixelBudget, privateTargetReady)) {
                failureReason = "private-target-unavailable"
            } else {
                sodiumAdapterVerificationAttempted = true
                feed.sodiumMainTargetRouteVerified = false
                feed.sodiumTranslucentTargetRouteVerified = false
                activeRenderTarget = scratchTarget
                activePovPass = true
                var classLoader = Thread.currentThread().contextClassLoader
                if (classLoader == null) classLoader = SpiritLeapPovRenderer::class.java.classLoader
                val terrainPassClass = Class.forName(SODIUM_TERRAIN_RENDER_PASS, true, classLoader)
                var constructor: Constructor<*>? = null
                for (candidate in terrainPassClass.constructors) {
                    val parameters = candidate.parameterTypes
                    if (parameters.size == 3 &&
                        parameters[0].name == "net.minecraft.client.renderer.chunk.ChunkSectionLayer" &&
                        parameters[1] == Boolean::class.javaPrimitiveType &&
                        parameters[2] == Boolean::class.javaPrimitiveType) {
                        constructor = candidate
                        break
                    }
                }
                if (constructor == null) {
                    failureReason = "terrain-pass-constructor-unavailable"
                } else {
                    val syntheticOpaquePass = constructor!!.newInstance(null, false, false)
                    verified = sodiumTerrainPassAccessor.verifyOpaqueProbeRoute(
                        terrainPassClass,
                        syntheticOpaquePass,
                        scratchTarget,
                        client.gameRenderer.mainRenderTarget(),
                    )
                    failureReason = if (verified) "verified-private-route-hook" else "main-route-target-identity-mismatch"
                }
            }
        } catch (failure: ReflectiveOperationException) {
            failureReason = "preflight-failed-" + boundedFailureType(failure.javaClass.simpleName)
        } catch (failure: RuntimeException) {
            failureReason = "preflight-failed-" + boundedFailureType(failure.javaClass.simpleName)
        } catch (failure: LinkageError) {
            failureReason = "preflight-failed-" + boundedFailureType(failure.javaClass.simpleName)
        } finally {
            activePovPass = previousPovPass
            activeRenderTarget = null
            renderingFeed = previousRenderingFeed
            if (stateSnapshot != null) {
                try {
                    restoreRenderState(RENDER_STATE, stateSnapshot!!)
                    restored = true
                } catch (restoreFailure: RuntimeException) {
                    retainPendingRenderStateRestore(stateSnapshot)
                    failureReason = "state-restore-failed-" + boundedFailureType(restoreFailure.javaClass.simpleName)
                } catch (restoreFailure: Error) {
                    retainPendingRenderStateRestore(stateSnapshot)
                    failureReason = "state-restore-failed-" + boundedFailureType(restoreFailure.javaClass.simpleName)
                }
            }
        }
        if (!restored) {
            sodiumTerrainPassAccessor.clear()
            latchProbeFailure(feed, client.level?.gameTime ?: 0L, IllegalStateException("preflight-state-restore-failed"))
            deferFullResourceCleanup(failureReason)
            return false
        }
        if (!verified) {
            sodiumTerrainPassAccessor.clear()
            latchProbeFailure(feed, client.level?.gameTime ?: 0L, IllegalStateException(failureReason))
            try {
                closeNow(false)
                destroyScratchTargetAfterRestore()
            } catch (cleanupFailure: RuntimeException) {
                failureReason += "-cleanup-" + boundedFailureType(cleanupFailure.javaClass.simpleName)
            } catch (cleanupFailure: Error) {
                failureReason += "-cleanup-" + boundedFailureType(cleanupFailure.javaClass.simpleName)
            }
        }
        sodiumTargetRouteStatus = failureReason
        return verified
    }

    private fun installedModVersion(loader: FabricLoader, modId: String): String {
        return try {
            loader.getModContainer(modId)
                .map { container -> container.metadata.version.friendlyString }
                .orElse("unknown")
        } catch (_: RuntimeException) {
            "unknown"
        }
    }

    private fun elapsedMicros(startedNanos: Long): Long =
        boundedDurationMicros(System.nanoTime() - startedNanos)

    @JvmStatic
    fun boundedDurationMicros(elapsedNanos: Long): Long =
        minOf(999_999_999L, maxOf(0L, elapsedNanos) / 1_000L)

    private fun selectLoadedFeed(feed: FeedState, level: ClientLevel, now: Long,
        cards: List<SpiritLeapRoster.Card>): Boolean {
        val card = findEligibleCardForQuadrant(feed.quadrant, cards)
        if (card == null) {
            clearFeedSelection(feed)
            feed.reason = "no-eligible-card"
            feed.status = "unavailable(reason=no-eligible-card)"
            return false
        }
        val member = card.member()
        val replacingTarget = feed.targetName != null && !feed.targetName!!.equals(member.name(), ignoreCase = true)
        if (replacingTarget) clearFeedSelection(feed)
        if (feed.targetName != null && feed.targetName!!.equals(member.name(), ignoreCase = true)) {
            val currentPlayer = findLoadedPlayer(member.name())
            if (currentPlayer != null && currentPlayer.isAlive) {
                feed.member = member
                feed.player = currentPlayer
                feed.reason = "selected"
                return true
            }
            invalidateLoadedPlayer(member.name())
            clearFeedSelection(feed)
            feed.lastSelectionTryNanos = now
            feed.reason = "target-not-loaded"
            feed.status = "unavailable(reason=target-not-loaded)"
            return false
        }
        if (!replacingTarget && now - feed.lastSelectionTryNanos < MIN_SELECTION_RETRY_NANOS) {
            feed.reason = "target-selection-backoff"
            feed.status = "unavailable(reason=target-selection-backoff)"
            return false
        }
        feed.lastSelectionTryNanos = now
        val player = findLoadedPlayer(member.name())
        if (player == null || !player.isAlive) {
            feed.reason = "target-not-loaded"
            feed.status = "unavailable(reason=target-not-loaded)"
            return false
        }
        feed.targetName = member.name()
        feed.member = member
        feed.player = player
        feed.reason = "target-changed"
        feed.status = "waiting(reason=target-changed)"
        return true
    }

    private fun findLoadedPlayer(name: String): Player? = loadedPlayersByName[normalizedPlayerName(name)]

    private fun hasLoadedViewNeighborhood(feed: FeedState, level: ClientLevel, nowNanos: Long): Boolean {
        val player = feed.player ?: return false
        val chunkX = Mth.floor(player.x) shr 4
        val chunkZ = Mth.floor(player.z) shr 4
        if (!shouldProbeChunks(nowNanos, feed.lastChunkProbeNanos, feed.hasChunkProbe,
                chunkX, chunkZ, feed.lastChunkX, feed.lastChunkZ)) return feed.chunkNeighborhoodLoaded
        feed.lastChunkProbeNanos = nowNanos
        feed.lastChunkX = chunkX
        feed.lastChunkZ = chunkZ
        feed.hasChunkProbe = true
        feed.loadedViewChunkCount = 0
        for (dz in -1..1) for (dx in -1..1) {
            if (level.chunkSource.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false) != null) {
                feed.loadedViewChunkCount++
            }
        }
        feed.chunkNeighborhoodLoaded = isCompleteLoadedViewNeighborhood(feed.loadedViewChunkCount)
        return feed.chunkNeighborhoodLoaded
    }

    @JvmStatic
    fun isCompleteLoadedViewNeighborhood(loadedChunkCount: Int): Boolean =
        loadedChunkCount == VIEW_NEIGHBORHOOD_CHUNK_COUNT

    private fun clearFeedSelection(feed: FeedState) {
        feed.targetName = null
        feed.member = null
        feed.player = null
        feed.fixedCameraProbe = false
        feed.hasChunkProbe = false
        feed.loadedViewChunkCount = 0
        feed.chunkNeighborhoodLoaded = false
        resetFeedCamera(feed)
    }

    private fun ensureRenderer(client: Minecraft, feed: FeedState, deltaTracker: DeltaTracker) {
        val level = client.level
        val player = feed.player
        val levelRenderer = if (level == null) null else nullableLegacyValue(client.levelRenderer)
        if (level == null || levelRenderer == null) {
            throw IllegalStateException("active-level-renderer-unavailable")
        }
        prepareFeedCamera(feed, level, player)
        renderer = levelRenderer
        if (feed.levelRenderer !== renderer || feed.levelExtractor == null) {
            feed.levelRenderer = renderer
            feed.levelExtractor = LevelExtractor(client, feed.levelRenderState, feed.levelRenderer!!)
            feed.levelExtractor!!.setLevel(level)
        }
        attachedLevel = level
        if (fogRenderer == null) fogRenderer = FogRenderer()
        if (povProjection == null) povProjection = Projection()
        if (povProjectionBuffer == null) povProjectionBuffer = ProjectionMatrixBuffer("Spirit Leap POV perspective")
        val width = maxOf(1, client.window.width / 2)
        val height = maxOf(1, client.window.height / 2)
        setWindowSize(width, height)
        if (Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency() && transparencyChain == null) {
            rebuildTransparencyChain(client)
        }
    }

    private fun ensureTarget(feed: FeedState, client: Minecraft, activeFeedCount: Int): Boolean {
        val width = maxOf(1, client.window.width / 2)
        val height = maxOf(1, client.window.height / 2)
        val sharedTargets = if (Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()) 2 else 1
        if (!isFeedTargetAllocationWithinBudget(width, height, activeFeedCount, sharedTargets)) {
            feed.stage = "target-allocation"
            feed.reason = "feed-target-pixel-budget-exceeded"
            feed.status = "unavailable(reason=feed-target-pixel-budget-exceeded)"
            return false
        }
        setWindowSize(width, height)
        if (scratchTarget == null) {
            scratchTarget = TextureTarget("Spirit Leap POV render scratch", width, height, true, GpuFormat.RGBA8_UNORM)
        } else if (scratchTarget!!.width != width || scratchTarget!!.height != height) {
            scratchTarget!!.resize(width, height)
        }
        if (Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()) {
            if (povTranslucentTarget == null) {
                povTranslucentTarget = TextureTarget("Spirit Leap POV translucent", width, height, true, GpuFormat.RGBA8_UNORM)
            } else if (povTranslucentTarget!!.width != width || povTranslucentTarget!!.height != height) {
                povTranslucentTarget!!.resize(width, height)
            }
        }
        return true
    }

    private fun setWindowSize(width: Int, height: Int) {
        val window = renderState.windowRenderState
        window.width = width
        window.height = height
        window.guiScale = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.guiScale
        window.appropriateLineWidth = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth
        window.isMinimized = width <= 1 || height <= 1
    }

    private fun copyMainRenderOptions(client: Minecraft, target: RenderTarget) {
        val main = client.gameRenderer.gameRenderState()
        copyOptions(main.optionsRenderState, renderState.optionsRenderState)
        copyLightmap(main.lightmapRenderState, renderState.lightmapRenderState)
        setWindowSize(target.width, target.height)
    }

    private val renderAttemptOperations = object : SpiritLeapPovRenderAttempt.Operations<GpuBufferSlice> {
        private var feed: FeedState? = null
        private var client: Minecraft? = null
        private var allocator: GraphicsResourceAllocator? = null
        private var deltaTracker: DeltaTracker? = null
        private var renderSky = false
        private var camera: Camera? = null
        private var cameraState: CameraRenderState? = null
        private var fogData: FogData? = null
        private var partialTick = 0.0f

        fun configure(feed: FeedState, client: Minecraft, allocator: GraphicsResourceAllocator,
            deltaTracker: DeltaTracker, renderSky: Boolean) {
            this.feed = feed
            this.client = client
            this.allocator = allocator
            this.deltaTracker = deltaTracker
            this.renderSky = renderSky
        }

        override fun stage(stage: String) {
            val currentFeed = feed ?: return
            currentFeed.stage = stage
            currentFeed.reason = when (stage) {
                SpiritLeapPovRenderAttempt.CAMERA_STAGE -> "updating-camera"
                SpiritLeapPovRenderAttempt.FOG_SETUP_STAGE -> "setting-up-fog"
                SpiritLeapPovRenderAttempt.FOG_BUFFER_STAGE -> "updating-fog-buffer"
                SpiritLeapPovRenderAttempt.EXTRACTION_STAGE -> "extracting-level"
                SpiritLeapPovRenderAttempt.CONTEXT_PREPARATION_STAGE -> "preparing-fabric-context"
                SpiritLeapPovRenderAttempt.RENDER_STAGE -> "rendering"
                SpiritLeapPovRenderAttempt.FOG_CLEANUP_STAGE -> "ending-fog-buffer"
                else -> "rendering"
            }
        }

        override fun updateCamera() {
            val currentFeed = feed ?: error("render-attempt-feed-unavailable")
            val currentClient = client ?: error("render-attempt-client-unavailable")
            val currentDeltaTracker = deltaTracker ?: error("render-attempt-delta-tracker-unavailable")
            val target = scratchTarget ?: error("scratch-target-unavailable")
            copyMainRenderOptions(currentClient, target)
            val level = currentClient.level ?: error("active-level-unavailable")
            val player = currentFeed.player ?: error("pov-player-unavailable")
            prepareFeedCamera(currentFeed, level, player)
            camera = currentFeed.camera
            currentFeed.camera.update(currentDeltaTracker)
            partialTick = currentDeltaTracker.getGameTimeDeltaPartialTick(false)
            cameraState = nullableLegacyValue(currentFeed.levelRenderState.cameraRenderState)
                ?: error("camera-render-state-unavailable")
        }

        override fun extractCamera() {
            val currentCamera = camera ?: error("pov-camera-unavailable")
            val currentCameraState = cameraState ?: error("camera-render-state-unavailable")
            currentCamera.extractRenderState(currentCameraState, partialTick)
            currentCameraState.fogType = currentCamera.getFluidInCamera()
        }

        override fun setupFog() {
            val currentClient = client ?: error("render-attempt-client-unavailable")
            val currentCamera = camera ?: error("pov-camera-unavailable")
            val currentDeltaTracker = deltaTracker ?: error("render-attempt-delta-tracker-unavailable")
            val currentCameraState = cameraState ?: error("camera-render-state-unavailable")
            val level = currentClient.level ?: error("active-level-unavailable")
            val currentFogRenderer = fogRenderer ?: error("fog-renderer-unavailable")
            val value = currentFogRenderer.setupFog(currentCamera,
                currentClient.options.getEffectiveRenderDistance(), currentDeltaTracker, 0.0f, level)
            fogData = value
            currentCameraState.fogData = value
        }

        override fun updateFogBuffer() {
            (fogRenderer ?: error("fog-renderer-unavailable")).updateBuffer(
                fogData ?: error("fog-data-unavailable"))
        }

        override fun readFogBuffer(): GpuBufferSlice =
            (fogRenderer ?: error("fog-renderer-unavailable")).getBuffer(FogRenderer.FogMode.WORLD)

        override fun updateRenderer() {
            val currentCamera = camera ?: error("pov-camera-unavailable")
            val currentCameraState = cameraState ?: error("camera-render-state-unavailable")
            val target = scratchTarget ?: error("scratch-target-unavailable")
            val currentFeed = feed ?: error("render-attempt-feed-unavailable")
            val client = Minecraft.getInstance()
            transparencyProjection?.setupPerspective(0.05f, currentCameraState.depthFar,
                currentCamera.getFov(), target.width.toFloat(), target.height.toFloat())
            if (client.gameRenderer.gameRenderState().useShaderTransparency() && transparencyChain == null) {
                throw IllegalStateException("transparency-chain-unavailable")
            }
            if (currentFeed.levelRenderer !== renderer || currentFeed.levelExtractor == null) {
                throw IllegalStateException("private-level-extractor-unavailable")
            }
        }

        override fun prepareExtractionContext() {
            val currentFeed = feed ?: error("render-attempt-feed-unavailable")
            val currentClient = client ?: error("render-attempt-client-unavailable")
            val currentDeltaTracker = deltaTracker ?: error("render-attempt-delta-tracker-unavailable")
            val currentCamera = camera ?: error("pov-camera-unavailable")
            currentFeed.contextPreparationCount = incrementSaturated(currentFeed.contextPreparationCount)
            currentFeed.contextPreparationStatus = "preparing"
            currentFeed.levelExtractionStatus = "pending"
            try {
                if (!FabricRenderingContextSupport.isAccessorAvailable()) {
                    throw IllegalStateException("fabric-rendering-context-accessor-unavailable")
                }
                val activeRenderer = renderer ?: error("active-level-renderer-unavailable")
                val levelExtractor = currentFeed.levelExtractor ?: error("private-level-extractor-unavailable")
                val context = (levelExtractor as LevelExtractorSpiritLeapContextAccessor)
                    .`geiler$getExtractionContext`()
                    ?: throw IllegalStateException("fabric-rendering-context-unavailable")
                val level = currentClient.level ?: error("active-level-unavailable")
                prepareFabricExtractionContext(context, currentClient.gameRenderer, activeRenderer,
                    currentFeed.levelRenderState, level, currentDeltaTracker, currentCamera)
                currentFeed.contextPreparationSuccessCount =
                    incrementSaturated(currentFeed.contextPreparationSuccessCount)
                currentFeed.contextPreparationStatus = "prepared"
            } catch (failure: RuntimeException) {
                currentFeed.contextPreparationStatus = "failed"
                currentFeed.levelExtractionStatus = "not-reached"
                throw failure
            } catch (failure: Error) {
                currentFeed.contextPreparationStatus = "failed"
                currentFeed.levelExtractionStatus = "not-reached"
                throw failure
            }
        }

        override fun extractLevel() {
            val currentFeed = feed ?: error("render-attempt-feed-unavailable")
            val currentDeltaTracker = deltaTracker ?: error("render-attempt-delta-tracker-unavailable")
            val currentCamera = camera ?: error("pov-camera-unavailable")
            try {
                (currentFeed.levelExtractor ?: error("private-level-extractor-unavailable"))
                    .extract(currentDeltaTracker, currentCamera, partialTick)
                // The client's hit result belongs to the local camera, never this teammate view.
                currentFeed.levelRenderState.blockOutlineRenderState = null
                currentFeed.levelExtractionSuccessCount = incrementSaturated(currentFeed.levelExtractionSuccessCount)
                currentFeed.levelExtractionStatus = "success"
            } catch (failure: RuntimeException) {
                currentFeed.levelExtractionStatus = "failed"
                throw failure
            } catch (failure: Error) {
                currentFeed.levelExtractionStatus = "failed"
                throw failure
            }
        }

        override fun render(fogBuffer: GpuBufferSlice) {
            val currentFeed = feed ?: error("render-attempt-feed-unavailable")
            currentFeed.sodiumMainTargetRouteObserved = false
            currentFeed.sodiumMainTargetRouteVerified = false
            currentFeed.sodiumTranslucentTargetRouteObserved = false
            currentFeed.sodiumTranslucentTargetRouteVerified = false
            val privateRenderStarted = System.nanoTime()
            try {
                val target = scratchTarget ?: error("scratch-target-unavailable")
                val currentAllocator = allocator ?: error("render-attempt-allocator-unavailable")
                val currentDeltaTracker = deltaTracker ?: error("render-attempt-delta-tracker-unavailable")
                val currentCameraState = cameraState ?: error("camera-render-state-unavailable")
                val currentFogData = fogData ?: error("fog-data-unavailable")
                renderIsolated(target, currentAllocator, currentDeltaTracker, currentCameraState,
                    fogBuffer, Vector4f(currentFogData.color), renderSky)
            } finally {
                currentFeed.privateRenderDurationMicros = elapsedMicros(privateRenderStarted)
            }
        }

        override fun endFogFrame() {
            (fogRenderer ?: error("fog-renderer-unavailable")).endFrame()
        }

        fun clear() {
            feed = null
            client = null
            allocator = null
            deltaTracker = null
            camera = null
            cameraState = null
            fogData = null
            partialTick = 0.0f
            renderSky = false
        }
    }

    private fun renderIsolated(target: RenderTarget, allocator: GraphicsResourceAllocator,
        deltaTracker: DeltaTracker, cameraState: CameraRenderState, fogBuffer: GpuBufferSlice,
        fogColor: Vector4f, renderSky: Boolean) {
        val stack = RenderSystem.getModelViewStack()
        val access = stack as Matrix4fStackAccess
        val originalDepth = access.`geiler$getStackDepth`()
        modelViewSnapshot.set(stack)
        val originalOutputColorTextureOverride = RenderSystem.outputColorTextureOverride
        val originalOutputDepthTextureOverride = RenderSystem.outputDepthTextureOverride
        val originalGlobalSettingsUniform = RenderSystem.getGlobalSettingsUniform()
        val originalShaderFog = RenderSystem.getShaderFog()
        val originalProjectionBuffer = RenderSystem.getProjectionMatrixBuffer()
        val originalProjectionType = RenderSystem.getProjectionType()
        var renderFailure: Throwable? = null
        try {
            try {
                val projection = povProjection ?: throw IllegalStateException("pov-projection-unavailable")
                val projectionBuffer = povProjectionBuffer ?: throw IllegalStateException("pov-projection-unavailable")
                val currentFeed = renderingFeed ?: throw IllegalStateException("rendering-feed-unavailable")
                val povCamera = currentFeed.camera
                projection.setupPerspective(
                    0.05f, cameraState.depthFar, povCamera.getFov(), target.width.toFloat(), target.height.toFloat(),
                )
                RenderSystem.setProjectionMatrix(projectionBuffer.getBuffer(projection), ProjectionType.PERSPECTIVE)
                currentFeed.projectionState = "perspective-bound"
                RenderSystem.setShaderFog(fogBuffer)
                RenderSystem.outputColorTextureOverride = target.colorTextureView
                RenderSystem.outputDepthTextureOverride = target.depthTextureView
                stack.identity()
                bindPovGlobalSettingsUniform(target, deltaTracker, cameraState)
                currentFeed.globalSettingsUniformState = "pov-camera-bound"
                val activeRenderer = renderer ?: throw IllegalStateException("active-level-renderer-unavailable")
                activeRenderer.render(allocator, deltaTracker, false, cameraState,
                    cameraState.viewRotationMatrix, fogBuffer, fogColor, renderSky)
            } catch (failure: RuntimeException) {
                renderFailure = failure
            } catch (failure: Error) {
                renderFailure = failure
            }
        } finally {
            try {
                RenderSystem.setShaderFog(Optional.ofNullable(originalShaderFog).orElse(null))
            } catch (restoreFailure: RuntimeException) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            } catch (restoreFailure: Error) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            }
            try {
                RenderSystem.setProjectionMatrix(
                    Optional.ofNullable(originalProjectionBuffer).orElse(null), originalProjectionType,
                )
                renderingFeed?.projectionState = "perspective/main-restored"
            } catch (restoreFailure: RuntimeException) {
                renderingFeed?.projectionState = "main-restore-failed"
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            } catch (restoreFailure: Error) {
                renderingFeed?.projectionState = "main-restore-failed"
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            }
            try {
                RenderSystem.setGlobalSettingsUniform(Optional.ofNullable(originalGlobalSettingsUniform).orElse(null))
                renderingFeed?.globalSettingsUniformState = "main-camera-restored"
            } catch (restoreFailure: RuntimeException) {
                renderingFeed?.globalSettingsUniformState = "main-camera-restore-failed"
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            } catch (restoreFailure: Error) {
                renderingFeed?.globalSettingsUniformState = "main-camera-restore-failed"
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            }
            try {
                restoreOutputTextureOverrides(originalOutputColorTextureOverride, originalOutputDepthTextureOverride)
            } catch (restoreFailure: RuntimeException) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            } catch (restoreFailure: Error) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            }
            try {
                restoreModelViewStack(stack, access, originalDepth, modelViewSnapshot)
            } catch (restoreFailure: RuntimeException) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            } catch (restoreFailure: Error) {
                renderFailure = SpiritLeapPovRenderAttempt.combineFailure(renderFailure, restoreFailure)
            }
        }
        if (renderFailure is Error) throw renderFailure
        if (renderFailure is RuntimeException) throw renderFailure
    }

    private fun bindPovGlobalSettingsUniform(target: RenderTarget, deltaTracker: DeltaTracker,
        cameraState: CameraRenderState) {
        val client = Minecraft.getInstance()
        val options = client.gameRenderer.gameRenderState().optionsRenderState
        if (povGlobalSettingsUniform == null) povGlobalSettingsUniform = GlobalSettingsUniform()
        povGlobalSettingsUniform!!.update(target.width, target.height, options.glintStrength,
            client.level?.gameTime ?: 0L, deltaTracker, options.menuBackgroundBlurriness,
            cameraState.pos, options.textureFiltering == TextureFilteringMethod.RGSS)
    }

    @JvmStatic
    fun restoreOutputTextureOverrides(colorTexture: GpuTextureView?, depthTexture: GpuTextureView?) {
        RenderSystem.outputColorTextureOverride = colorTexture
        RenderSystem.outputDepthTextureOverride = depthTexture
    }

    @JvmStatic
    fun restoreModelViewStack(stack: Matrix4fStack, access: Matrix4fStackAccess, depth: Int, matrix: Matrix4f) {
        access.`geiler$setStackDepth`(depth)
        stack.set(matrix)
    }

    interface MutableStateRef<T> {
        fun get(): T?
        fun set(value: T?)
    }

    @JvmStatic
    fun <T> withTemporaryState(gameStateRef: MutableStateRef<T>, rendererStateRef: MutableStateRef<T>,
        temporaryState: T, action: Runnable) {
        val originalGameState = gameStateRef.get()
        val originalRendererState = rendererStateRef.get()
        var failure: Throwable? = null
        try {
            gameStateRef.set(temporaryState)
            rendererStateRef.set(temporaryState)
            action.run()
        } catch (primaryFailure: RuntimeException) {
            failure = primaryFailure
        } catch (primaryFailure: Error) {
            failure = primaryFailure
        }
        try {
            rendererStateRef.set(originalRendererState)
        } catch (restoreFailure: RuntimeException) {
            failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure)
        } catch (restoreFailure: Error) {
            failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure)
        }
        try {
            gameStateRef.set(originalGameState)
        } catch (restoreFailure: RuntimeException) {
            failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure)
        } catch (restoreFailure: Error) {
            failure = SpiritLeapPovRenderAttempt.combineFailure(failure, restoreFailure)
        }
        if (failure is Error) throw failure
        if (failure is RuntimeException) throw failure
    }

    private fun copyOptions(from: OptionsRenderState, to: OptionsRenderState) {
        to.cloudRange = from.cloudRange
        to.cutoutLeaves = from.cutoutLeaves
        to.improvedTransparency = from.improvedTransparency
        to.ambientOcclusion = from.ambientOcclusion
        to.menuBackgroundBlurriness = from.menuBackgroundBlurriness
        to.panoramaSpeed = from.panoramaSpeed
        to.maxAnisotropyValue = from.maxAnisotropyValue
        to.textureFiltering = from.textureFiltering
        // View bob made stationary teammate POVs shake; the feed never wants it.
        to.bobView = false
        to.screenEffectScale = from.screenEffectScale
        to.glintSpeed = from.glintSpeed
        to.glintStrength = from.glintStrength
        to.damageTiltStrength = from.damageTiltStrength
        to.backgroundForChatOnly = from.backgroundForChatOnly
        to.textBackgroundOpacity = from.textBackgroundOpacity
        to.cloudStatus = from.cloudStatus
        to.cameraType = CameraType.FIRST_PERSON
        to.renderDistance = from.renderDistance
    }

    private fun copyLightmap(from: LightmapRenderState, to: LightmapRenderState) {
        blockTint.set(from.blockLightTint)
        skyColor.set(from.skyLightColor)
        ambientColor.set(from.ambientColor)
        nightVisionColor.set(from.nightVisionColor)
        to.needsUpdate = from.needsUpdate
        to.blockFactor = from.blockFactor
        to.blockLightTint = blockTint
        to.skyFactor = from.skyFactor
        to.skyLightColor = skyColor
        to.ambientColor = ambientColor
        to.brightness = from.brightness
        to.darknessEffectScale = from.darknessEffectScale
        to.nightVisionEffectIntensity = from.nightVisionEffectIntensity
        to.nightVisionColor = nightVisionColor
        to.bossOverlayWorldDarkening = from.bossOverlayWorldDarkening
    }

    private fun rebuildTransparencyChain(client: Minecraft) {
        closeTransparencyChain()
        if (!Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()) return
        try {
            val config = client.getResourceManager().getResourceOrThrow(TRANSPARENCY_CONFIG).openAsReader().use { reader ->
                PostChainConfig.CODEC.parse(JsonOps.INSTANCE, StrictJsonParser.parse(reader)).getOrThrow()
            }
            transparencyProjection = Projection()
            transparencyProjectionBuffer = ProjectionMatrixBuffer("Spirit Leap POV transparency")
            transparencyChain = PostChain.load(config, client.textureManager, LevelTargetBundle.SORTING_TARGETS,
                TRANSPARENCY_CHAIN, transparencyProjection!!, transparencyProjectionBuffer!!)
        } catch (failure: Exception) {
            try {
                closeTransparencyChain()
            } catch (cleanupFailure: RuntimeException) {
                SpiritLeapPovRenderAttempt.combineFailure(failure, cleanupFailure)
            }
            status = "unavailable(reason=transparency-chain-build-failed)"
            val failureType = boundedFailureType(failure.javaClass.simpleName)
            for (feed in feeds) {
                feed.stage = "renderer-initialization"
                feed.reason = "transparency-chain-build-failed-$failureType"
                feed.status = "unavailable(reason=transparency-chain-build-failed,type=$failureType)"
            }
            SpiritLeapMenuModule.recordEvent(client.level?.gameTime ?: 0L,
                "POV_TRANSPARENCY_UNAVAILABLE type=$failureType")
            throw IllegalStateException("transparency-chain-build-failed", failure)
        }
    }

    fun transparencyChainFor(candidate: LevelRenderer?, mainChain: PostChain?): PostChain? {
        val privateRenderer = ownsRenderer(candidate)
        return if (shouldUsePrivateTransparencyChain(privateRenderer,
                privateRenderer && Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency())) {
            transparencyChain
        } else mainChain
    }

    fun isSecondary(candidate: LevelRenderer?): Boolean = ownsRenderer(candidate)

    fun cameraFor(candidate: LevelRenderer?, mainCamera: Camera): Camera {
        if (!ownsRenderer(candidate)) return mainCamera
        val selectedFeed = renderingFeed ?: activeFeed()
        return if (selectedFeed != null && selectedFeed.cameraHistory.isBound()) selectedFeed.camera else mainCamera
    }

    private fun closeTransparencyChain() {
        val oldChain = transparencyChain
        var cleanupFailure: RuntimeException? = null
        if (oldChain != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure, oldChain::close) {
                if (transparencyChain === oldChain) transparencyChain = null
            }
        }
        val oldProjectionBuffer = transparencyProjectionBuffer
        if (oldProjectionBuffer != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure, oldProjectionBuffer::close) {
                if (transparencyProjectionBuffer === oldProjectionBuffer) transparencyProjectionBuffer = null
            }
        }
        if (transparencyChain == null && transparencyProjectionBuffer == null) transparencyProjection = null
        cleanupFailure?.let {
            failClosedOnCleanupFailure(it)
            throw it
        }
    }

    fun close() {
        val request = closeRequestGeneration.incrementAndGet()
        val generation = lifecycleGeneration.get()
        val closeOnRenderThread = Runnable {
            if (closeRequestGeneration.get() != request || lifecycleGeneration.get() != generation) return@Runnable
            if (activeRenderTarget != null) {
                clearCachedFramesOnCleanup = true
                clearPublishedFrameState("cleared-menu-close")
                deferFullResourceCleanup("menu-closed-during-private-render")
            } else closeNow()
        }
        if (RenderSystem.isOnRenderThread()) closeOnRenderThread.run()
        else executeOnMinecraftRenderThread(closeOnRenderThread)
    }

    private fun releaseIfAllocated() {
        if (hasAllocatedResources()) closeNow()
    }

    private fun releaseRendererIfAllocated() {
        if (hasPrivateRendererResources()) closeNow(false)
    }

    private fun latchProbeFailure(feed: FeedState, gameTick: Long, failure: Throwable) =
        latchProbeFailure(feed, gameTick, failure, feed.stage)

    private fun latchProbeFailure(feed: FeedState, gameTick: Long, failure: Throwable, failureStage: String) {
        povSessionState.latchFailure()
        val recordDiagnostic = !probeFailureDiagnosticRecorded
        if (recordDiagnostic) {
            probeFailureDiagnosticRecorded = true
            probeFailureReason = boundedFailureType(SpiritLeapPovRenderAttempt.failureType(failure))
            probeFailureStage = SpiritLeapMenuModule.boundedDiagnosticText(failureStage, 32)
        }
        // Set the fail-closed feed state before any stack formatting or log I/O can fail.
        markProbeFailureFeeds()
        if (!recordDiagnostic) return
        probeFailureTopSite = SpiritLeapPovRenderAttempt.failureTopSite(failure)
        SpiritLeapMenuModule.recordEvent(gameTick, "POV_PROBE_LATCHED quadrant=" + feed.quadrant +
            " type=" + probeFailureReason + " stage=" + probeFailureStage + " top=" + probeFailureTopSite + " " +
            SpiritLeapPovRenderAttempt.failureSummary(failureStage, failure))
    }

    private fun latchProbeFailureSafely(feed: FeedState, gameTick: Long, failure: Throwable,
        primary: Throwable, failureStage: String) {
        try {
            latchProbeFailure(feed, gameTick, failure, failureStage)
        } catch (diagnosticFailure: RuntimeException) {
            SpiritLeapPovRenderAttempt.combineFailure(primary, diagnosticFailure)
        } catch (diagnosticFailure: Error) {
            SpiritLeapPovRenderAttempt.combineFailure(primary, diagnosticFailure)
        }
    }

    private fun deferFeedTargetCleanup(reason: String) {
        val hasTargets = scratchTarget != null || povTranslucentTarget != null ||
            clearCachedFramesOnCleanup && hasPublishedFrameTargets()
        if (!hasTargets) return
        feedTargetCleanupPending = true
        feedTargetCleanupReason = boundedFailureType(reason)
        if (!feedTargetCleanupDiagnosticReported) {
            feedTargetCleanupDiagnosticReported = true
            try {
                val client = Minecraft.getInstance()
                val gameTick = client.level?.gameTime ?: 0L
                SpiritLeapMenuModule.recordEvent(gameTick,
                    "POV_TARGET_CLEANUP_DEFERRED reason=" + feedTargetCleanupReason)
            } catch (_: RuntimeException) {
                // Keep cleanup diagnostics from turning a fail-closed render path into another failure.
            }
        }
    }

    private fun deferFullResourceCleanup(reason: String) {
        // Also retain one retry when state restoration failed after resources were already detached.
        rendererCleanupPending = true
        deferFeedTargetCleanup(reason)
    }

    private fun tryDeferredFeedTargetCleanup() {
        if (pendingRenderStateRestore.pending()) return
        val now = System.nanoTime()
        if (lastDeferredTargetCleanupTryNanos != 0L &&
            now - lastDeferredTargetCleanupTryNanos < DEFERRED_TARGET_CLEANUP_RETRY_NANOS) return
        lastDeferredTargetCleanupTryNanos = now
        var mainPassState: RenderStateSnapshot? = null
        try {
            // This callback runs after a later complete main LevelRenderer pass, so the old POV target
            // is no longer part of the active main render. Restore and verify before releasing anything.
            mainPassState = captureRenderState(RENDER_STATE)
            restoreRenderState(RENDER_STATE, mainPassState!!)
        } catch (failure: RuntimeException) {
            mainPassState?.let { retainPendingRenderStateRestore(it) }
            feedTargetCleanupReason = "later-main-state-restore-failed-" + boundedFailureType(failure.javaClass.simpleName)
            return
        } catch (failure: Error) {
            mainPassState?.let { retainPendingRenderStateRestore(it) }
            feedTargetCleanupReason = "later-main-state-restore-failed-" + boundedFailureType(failure.javaClass.simpleName)
            return
        }
        var rendererReleaseFailed = false
        if (rendererCleanupPending) {
            try {
                releaseRendererResources()
                rendererCleanupPending = false
            } catch (failure: RuntimeException) {
                rendererReleaseFailed = true
                failClosedOnCleanupFailure(failure)
            }
            try {
                restoreRenderState(RENDER_STATE, mainPassState!!)
            } catch (failure: RuntimeException) {
                retainPendingRenderStateRestore(mainPassState)
                deferFullResourceCleanup("post-renderer-release-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
                return
            } catch (failure: Error) {
                retainPendingRenderStateRestore(mainPassState)
                deferFullResourceCleanup("post-renderer-release-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
                return
            }
        }
        if (rendererReleaseFailed) return
        if (feedTargetCleanupPending) destroyTargetsAfterRestore(clearCachedFramesOnCleanup)
        if (!feedTargetCleanupPending && !rendererCleanupPending) {
            feedTargetCleanupDiagnosticReported = false
            feedTargetCleanupReason = "none"
            lastDeferredTargetCleanupTryNanos = 0L
        }
    }

    private fun destroyFeedTargetsAfterRestore(): Boolean = destroyTargetsAfterRestore(true)

    private fun destroyScratchTargetAfterRestore(): Boolean = destroyTargetsAfterRestore(false)

    private fun destroyTargetsAfterRestore(includePublishedFrames: Boolean): Boolean {
        if (pendingRenderStateRestore.pending()) return false
        var stateSnapshot: RenderStateSnapshot? = null
        try {
            stateSnapshot = captureRenderState(RENDER_STATE)
            restoreRenderState(RENDER_STATE, stateSnapshot!!)
        } catch (failure: RuntimeException) {
            stateSnapshot?.let { retainPendingRenderStateRestore(it) }
            deferFullResourceCleanup("target-state-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
            return false
        } catch (failure: Error) {
            stateSnapshot?.let { retainPendingRenderStateRestore(it) }
            deferFullResourceCleanup("target-state-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
            return false
        }
        var cleanupFailure: RuntimeException? = null
        val workingTarget = scratchTarget
        if (workingTarget != null) {
            try {
                workingTarget.destroyBuffers()
                scratchTarget = null
            } catch (failure: RuntimeException) {
                cleanupFailure = attemptCleanup(cleanupFailure) { throw failure }
            }
        }
        val translucentTarget = povTranslucentTarget
        if (translucentTarget != null) {
            try {
                translucentTarget.destroyBuffers()
                povTranslucentTarget = null
            } catch (failure: RuntimeException) {
                cleanupFailure = attemptCleanup(cleanupFailure) { throw failure }
            }
        }
        if (includePublishedFrames) {
            for (feed in feeds) {
                val frameTarget = feed.frameTarget ?: continue
                try {
                    frameTarget.destroyBuffers()
                    feed.frameTarget = null
                    feed.frameAvailable = false
                    feed.lastSuccessfulFrameNanos = 0L
                    clearCachedFrameIdentity(feed)
                } catch (failure: RuntimeException) {
                    cleanupFailure = attemptCleanup(cleanupFailure) { throw failure }
                }
            }
        }
        if (cleanupFailure != null) {
            deferFeedTargetCleanup("target-destroy-failed-" + boundedFailureType(cleanupFailure!!.javaClass.simpleName))
            failClosedOnCleanupFailure(cleanupFailure!!)
            return false
        }
        try {
            restoreRenderState(RENDER_STATE, stateSnapshot!!)
        } catch (failure: RuntimeException) {
            retainPendingRenderStateRestore(stateSnapshot)
            deferFullResourceCleanup("target-post-release-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
            return false
        } catch (failure: Error) {
            retainPendingRenderStateRestore(stateSnapshot)
            deferFullResourceCleanup("target-post-release-restore-failed-" + boundedFailureType(failure.javaClass.simpleName))
            return false
        }
        feedTargetCleanupPending = false
        if (includePublishedFrames) clearCachedFramesOnCleanup = false
        return true
    }

    private fun hasFeedTargets(): Boolean = scratchTarget != null || povTranslucentTarget != null || hasPublishedFrameTargets()

    private fun hasPublishedFrameTargets(): Boolean = feeds.any { it.frameTarget != null }

    private fun clearPublishedFrameState(publicationStatus: String) {
        discardPendingGuiPublication(publicationStatus)
        pendingPixelSample = null
        for (feed in feeds) {
            feed.frameAvailable = false
            feed.lastSuccessfulFrameNanos = 0L
            clearCachedFrameIdentity(feed)
            feed.clearObservedCardMapping()
            feed.publicationStatus = publicationStatus
            feed.sourcePixelSample = "hidden"
            feed.screenPixelSample = "hidden"
        }
    }

    private fun clearCachedFrameIdentity(feed: FeedState) {
        feed.cachedFrameTargetName = null
        feed.cachedFrameTargetId = null
        feed.cachedFrameIdentityInvalidated = false
        feed.lastGuiDrawState = "hidden"
    }

    private fun markProbeFailureFeeds() {
        val reason = "probe-failure-latched-" + probeFailureReason
        for (feed in feeds) {
            feed.stage = "probe-failure-latched"
            feed.reason = reason
            feed.status = "unavailable(reason=" + reason + ")"
        }
    }

    private fun closeNow() = closeNow(true)

    private fun closeNow(clearFeed: Boolean) {
        if (!RenderSystem.isOnRenderThread()) {
            close()
            return
        }
        if (clearFeed) {
            SpiritLeapCameraHud.INSTANCE.clear()
            clearCachedFramesOnCleanup = true
            clearPublishedFrameState("cleared-menu-close")
        }
        if (activeRenderTarget != null) {
            deferFullResourceCleanup("cleanup-requested-during-private-render")
            return
        }
        if (!retryPendingRenderStateRestore()) {
            deferFullResourceCleanup("waiting-for-original-state-restore")
            return
        }
        if (renderStateRecoveryState.awaitingCompletedFrameBaseline()) {
            deferFullResourceCleanup("waiting-for-completed-frame-state-baseline")
            return
        }
        var stateSnapshot: RenderStateSnapshot? = null
        try {
            stateSnapshot = captureRenderState(RENDER_STATE)
            restoreRenderState(RENDER_STATE, stateSnapshot!!)
        } catch (stateFailure: RuntimeException) {
            stateSnapshot?.let { retainPendingRenderStateRestore(it) }
            deferFullResourceCleanup("pre-release-state-restore-failed-" + boundedFailureType(stateFailure.javaClass.simpleName))
            return
        } catch (stateFailure: Error) {
            stateSnapshot?.let { retainPendingRenderStateRestore(it) }
            deferFullResourceCleanup("pre-release-state-restore-failed-" + boundedFailureType(stateFailure.javaClass.simpleName))
            return
        }
        var cleanupFailure: RuntimeException? = null
        try {
            if (!releaseTargetsAfterStateRestore(RENDER_STATE, stateSnapshot!!, Runnable { releaseRendererResources() })) {
                retainPendingRenderStateRestore(stateSnapshot)
                deferFullResourceCleanup("pre-release-state-restore-failed")
                return
            }
        } catch (failure: RuntimeException) {
            cleanupFailure = failure
        }
        try {
            restoreRenderState(RENDER_STATE, stateSnapshot!!)
            rendererCleanupPending = false
        } catch (stateFailure: RuntimeException) {
            retainPendingRenderStateRestore(stateSnapshot)
            deferFullResourceCleanup("post-release-state-restore-failed-" + boundedFailureType(stateFailure.javaClass.simpleName))
            if (cleanupFailure != null) SpiritLeapPovRenderAttempt.combineFailure(cleanupFailure, stateFailure)
            else cleanupFailure = stateFailure
        } catch (stateFailure: Error) {
            retainPendingRenderStateRestore(stateSnapshot)
            deferFullResourceCleanup("post-release-state-restore-failed-" + boundedFailureType(stateFailure.javaClass.simpleName))
            if (cleanupFailure != null) SpiritLeapPovRenderAttempt.combineFailure(cleanupFailure, stateFailure)
            else cleanupFailure = IllegalStateException("post-release-state-restore-failed", stateFailure)
        }
        if (cleanupFailure != null) {
            deferFullResourceCleanup("renderer-release-failed-" + boundedFailureType(cleanupFailure!!.javaClass.simpleName))
            failClosedOnCleanupFailure(cleanupFailure!!)
        }
        if (rendererCleanupPending) return
        // The active world renderer is Minecraft-owned; only discard the queued reload reference
        // after all independently owned POV resources have been released.
        if (renderer == null) pendingResourceReload.clear()
        if (clearFeed) {
            val targetsReleased = destroyFeedTargetsAfterRestore()
            for (feed in feeds) {
                resetFeedCamera(feed)
                feed.resetForClosedMenu()
            }
            povSessionState.menuClosed()
            warmupQuadrant = -1
            feedScheduler.reset()
            status = if (targetsReleased) "idle(reason=resources-released)" else "idle(reason=resource-cleanup-deferred)"
        }
        cleanupFailure?.let { failClosedOnCleanupFailure(it) }
    }

    private fun releaseRendererResources() {
        if (activePovPass || activeRenderTarget != null) throw IllegalStateException("pov-render-still-active")
        activeRenderTarget = null
        var cleanupFailure: RuntimeException? = null
        renderer = null
        attachedLevel = null
        val oldFogRenderer = fogRenderer
        if (oldFogRenderer != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure, Runnable { oldFogRenderer.close() }) {
                if (fogRenderer === oldFogRenderer) fogRenderer = null
            }
        }
        val oldTransparencyChain = transparencyChain
        if (oldTransparencyChain != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure, Runnable { oldTransparencyChain.close() }) {
                if (transparencyChain === oldTransparencyChain) transparencyChain = null
            }
        }
        val oldTransparencyProjectionBuffer = transparencyProjectionBuffer
        if (oldTransparencyProjectionBuffer != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure,
                Runnable { oldTransparencyProjectionBuffer.close() }) {
                if (transparencyProjectionBuffer === oldTransparencyProjectionBuffer) transparencyProjectionBuffer = null
            }
        }
        val oldPovGlobalSettingsUniform = povGlobalSettingsUniform
        if (oldPovGlobalSettingsUniform != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure,
                Runnable { oldPovGlobalSettingsUniform.close() }) {
                if (povGlobalSettingsUniform === oldPovGlobalSettingsUniform) povGlobalSettingsUniform = null
            }
        }
        if (transparencyChain == null && transparencyProjectionBuffer == null) transparencyProjection = null
        val oldPovProjectionBuffer = povProjectionBuffer
        if (oldPovProjectionBuffer != null) {
            cleanupFailure = attemptCleanupAndClearAfterSuccess(cleanupFailure,
                Runnable { oldPovProjectionBuffer.close() }) {
                if (povProjectionBuffer === oldPovProjectionBuffer) {
                    povProjectionBuffer = null
                    povProjection = null
                }
            }
        }
        for (feed in feeds) {
            feed.levelExtractor = null
            feed.levelRenderer = null
            cleanupFailure = attemptCleanup(cleanupFailure) {
                feed.cameraHistory.clear {
                    feed.camera.setLevel(null)
                    clearCameraEntity(feed.camera)
                    feed.camera.reset()
                }
            }
            cleanupFailure = attemptCleanup(cleanupFailure) { feed.levelRenderState.reset() }
        }
        cleanupFailure?.let {
            failClosedOnCleanupFailure(it)
            throw it
        }
    }

    private fun failClosedOnCleanupFailure(failure: RuntimeException) {
        val firstFailure = !rendererCleanupFailed
        rendererCleanupFailed = true
        for (feed in feeds) {
            feed.stage = "cleanup"
            feed.reason = "renderer-cleanup-failed"
            feed.status = "unavailable(reason=renderer-cleanup-failed)"
        }
        if (firstFailure) {
            try {
                val client = Minecraft.getInstance()
                val gameTick = client.level?.gameTime ?: 0L
                SpiritLeapMenuModule.recordEvent(gameTick,
                    "POV_RENDERER_CLEANUP_FAILED type=" + boundedFailureType(failure.javaClass.simpleName))
            } catch (_: RuntimeException) {
                // Keep best-effort diagnostics from turning renderer cleanup into a main-render failure.
            }
        }
    }

    @JvmStatic
    fun boundedFailureType(type: String?): String {
        val bounded = SpiritLeapMenuModule.boundedDiagnosticText(type, 32)
        return if (bounded.isEmpty()) "Unknown" else bounded
    }

    private fun attemptCleanup(priorFailure: RuntimeException?, cleanup: Runnable): RuntimeException? {
        return try {
            cleanup.run()
            priorFailure
        } catch (failure: RuntimeException) {
            if (priorFailure == null) failure else {
                SpiritLeapPovRenderAttempt.combineFailure(priorFailure, failure)
                priorFailure
            }
        }
    }

    class FeedState(quadrant: Int) {
        @JvmField val quadrant: Int = quadrant
        @JvmField val camera = Camera()
        @JvmField val levelRenderState = LevelRenderState()
        @JvmField var levelRenderer: LevelRenderer? = null
        @JvmField var levelExtractor: LevelExtractor? = null
        @JvmField val cameraHistory = FeedCameraHistory()
        @JvmField var frameTarget: RenderTarget? = null
        @JvmField var targetName: String? = null
        @JvmField var cachedFrameTargetName: String? = null
        @JvmField var cachedFrameTargetId: UUID? = null
        @JvmField var cachedFrameIdentityInvalidated = false
        @JvmField var fixedCameraProbe = false
        @JvmField var lastObservedCardTargetName: String? = null
        @JvmField var hasObservedCardMapping = false
        @JvmField var lastGuiDrawState = "not-queued"
        @JvmField var member: SpiritLeapRoster.Member? = null
        @JvmField var player: Player? = null
        @JvmField var lastUpdateNanos = 0L
        @JvmField var lastPixelSampleNanos = 0L
        @JvmField var lastSuccessfulFrameNanos = 0L
        @JvmField var hasUpdated = false
        @JvmField var lastSelectionTryNanos = 0L
        @JvmField var lastChunkProbeNanos = 0L
        @JvmField var lastChunkX = 0
        @JvmField var lastChunkZ = 0
        @JvmField var hasChunkProbe = false
        @JvmField var chunkNeighborhoodLoaded = false
        @JvmField var loadedViewChunkCount = 0
        @JvmField var lastLevelTargetRoute = "not-observed"
        @JvmField var lastFrameGraphClearRoute = "not-observed"
        @JvmField var levelTargetRouteObserved = false
        @JvmField var levelTargetRouteMismatch = false
        @JvmField var frameGraphClearRouteObserved = false
        @JvmField var frameGraphClearRouteMismatch = false
        @JvmField var layerGroupOpaqueObserved = false
        @JvmField var layerGroupOpaqueVerified = false
        @JvmField var layerGroupTranslucentObserved = false
        @JvmField var layerGroupTranslucentVerified = false
        @JvmField var frameAvailable = false
        @JvmField var sodiumMainTargetRouteObserved = false
        @JvmField var sodiumMainTargetRouteVerified = false
        @JvmField var sodiumTranslucentTargetRouteObserved = false
        @JvmField var sodiumTranslucentTargetRouteVerified = false
        @JvmField var vanillaTerrainBatches = 0L
        @JvmField var sodiumLayerCalls = 0L
        @JvmField var nvidiumWorldPasses = 0L
        @JvmField var nvidiumTranslucentPasses = 0L
        @JvmField var visibleTerrainSections = 0
        @JvmField var globalSettingsUniformState = "not-applied"
        @JvmField var projectionState = "not-applied"
        @JvmField var sourcePixelSample = "not-sampled"
        @JvmField var screenPixelSample = "not-sampled"
        @JvmField var successfulFrameCount = 0L
        @JvmField var guiBlitsQueued = 0L
        @JvmField var lastGuiBlitFrameCount = 0L
        @JvmField var publicationStatus = "idle"
        @JvmField var stage = "idle"
        @JvmField var reason = "menu-closed"
        @JvmField var selectionDurationMicros = 0L
        @JvmField var chunkCheckDurationMicros = 0L
        @JvmField var renderStateSnapshotDurationMicros = 0L
        @JvmField var renderStateRestoreDurationMicros = 0L
        @JvmField var rendererInitDurationMicros = 0L
        @JvmField var targetAllocationDurationMicros = 0L
        @JvmField var attemptDurationMicros = 0L
        @JvmField var privateRenderDurationMicros = 0L
        @JvmField var contextPreparationCount = 0L
        @JvmField var contextPreparationSuccessCount = 0L
        @JvmField var levelExtractionSuccessCount = 0L
        @JvmField var contextPreparationStatus = "not-attempted"
        @JvmField var levelExtractionStatus = "not-attempted"
        @JvmField var status = "idle(reason=menu-closed)"

        fun frameAgeMillis(nowNanos: Long): Long =
            SpiritLeapPovRenderer.cachedFrameAgeMillis(frameAvailable, lastSuccessfulFrameNanos, nowNanos)

        fun recordGuiBlit() {
            if (guiBlitsQueued < Long.MAX_VALUE) guiBlitsQueued++
            lastGuiBlitFrameCount = successfulFrameCount
        }

        fun observeCardMapping(cards: List<SpiritLeapRoster.Card>?) {
            if (cards.isNullOrEmpty()) return
            val card = SpiritLeapPovRenderer.findEligibleCardForQuadrant(quadrant, cards)
            lastObservedCardTargetName = card?.member()?.name()
            hasObservedCardMapping = true
            if (frameAvailable && cachedFrameTargetName != null &&
                !sameCardTarget(cachedFrameTargetName, lastObservedCardTargetName)) {
                cachedFrameIdentityInvalidated = true
            }
        }

        fun matchesObservedCardTarget(target: String?): Boolean =
            hasObservedCardMapping && sameCardTarget(target, lastObservedCardTargetName)

        fun clearObservedCardMapping() {
            lastObservedCardTargetName = null
            hasObservedCardMapping = false
        }

        private fun sameCardTarget(first: String?, second: String?): Boolean =
            first != null && second != null && first.equals(second, ignoreCase = true)

        fun resetForClosedMenu() {
            targetName = null
            member = null
            player = null
            fixedCameraProbe = false
            lastUpdateNanos = 0L
            lastPixelSampleNanos = 0L
            lastSuccessfulFrameNanos = 0L
            hasUpdated = false
            lastSelectionTryNanos = 0L
            lastChunkProbeNanos = 0L
            lastChunkX = 0
            lastChunkZ = 0
            hasChunkProbe = false
            chunkNeighborhoodLoaded = false
            loadedViewChunkCount = 0
            lastLevelTargetRoute = "not-observed"
            lastFrameGraphClearRoute = "not-observed"
            levelTargetRouteObserved = false
            levelTargetRouteMismatch = false
            frameGraphClearRouteObserved = false
            frameGraphClearRouteMismatch = false
            layerGroupOpaqueObserved = false
            layerGroupOpaqueVerified = false
            layerGroupTranslucentObserved = false
            layerGroupTranslucentVerified = false
            frameAvailable = false
            cachedFrameTargetName = null
            cachedFrameTargetId = null
            cachedFrameIdentityInvalidated = false
            clearObservedCardMapping()
            lastGuiDrawState = "hidden"
            sodiumMainTargetRouteObserved = false
            sodiumMainTargetRouteVerified = false
            sodiumTranslucentTargetRouteObserved = false
            sodiumTranslucentTargetRouteVerified = false
            vanillaTerrainBatches = 0L
            sodiumLayerCalls = 0L
            nvidiumWorldPasses = 0L
            nvidiumTranslucentPasses = 0L
            visibleTerrainSections = 0
            globalSettingsUniformState = "not-applied"
            projectionState = "not-applied"
            sourcePixelSample = "not-sampled"
            screenPixelSample = "not-sampled"
            successfulFrameCount = 0L
            guiBlitsQueued = 0L
            lastGuiBlitFrameCount = 0L
            publicationStatus = "idle"
            stage = "idle"
            reason = "menu-closed"
            selectionDurationMicros = 0L
            chunkCheckDurationMicros = 0L
            renderStateSnapshotDurationMicros = 0L
            renderStateRestoreDurationMicros = 0L
            rendererInitDurationMicros = 0L
            targetAllocationDurationMicros = 0L
            attemptDurationMicros = 0L
            privateRenderDurationMicros = 0L
            contextPreparationCount = 0L
            contextPreparationSuccessCount = 0L
            levelExtractionSuccessCount = 0L
            contextPreparationStatus = "not-attempted"
            levelExtractionStatus = "not-attempted"
            status = "idle(reason=menu-closed)"
        }

        fun resetForLifecycleBoundary() {
            frameAvailable = false
            fixedCameraProbe = false
            lastPixelSampleNanos = 0L
            lastSuccessfulFrameNanos = 0L
            cachedFrameTargetName = null
            cachedFrameTargetId = null
            cachedFrameIdentityInvalidated = false
            clearObservedCardMapping()
            lastGuiDrawState = "hidden"
            sodiumMainTargetRouteObserved = false
            sodiumMainTargetRouteVerified = false
            sodiumTranslucentTargetRouteObserved = false
            sodiumTranslucentTargetRouteVerified = false
            lastSelectionTryNanos = 0L
            lastChunkProbeNanos = 0L
            hasChunkProbe = false
            chunkNeighborhoodLoaded = false
            loadedViewChunkCount = 0
            lastLevelTargetRoute = "not-observed"
            lastFrameGraphClearRoute = "not-observed"
            levelTargetRouteObserved = false
            levelTargetRouteMismatch = false
            frameGraphClearRouteObserved = false
            frameGraphClearRouteMismatch = false
            layerGroupOpaqueObserved = false
            layerGroupOpaqueVerified = false
            layerGroupTranslucentObserved = false
            layerGroupTranslucentVerified = false
            vanillaTerrainBatches = 0L
            sodiumLayerCalls = 0L
            nvidiumWorldPasses = 0L
            nvidiumTranslucentPasses = 0L
            visibleTerrainSections = 0
            globalSettingsUniformState = "not-applied"
            sourcePixelSample = "not-sampled"
            screenPixelSample = "not-sampled"
            contextPreparationCount = 0L
            contextPreparationSuccessCount = 0L
            levelExtractionSuccessCount = 0L
            contextPreparationStatus = "not-attempted"
            levelExtractionStatus = "not-attempted"
            reason = "lifecycle-reset"
            status = "waiting(reason=lifecycle-reset)"
        }
    }

    /** Identity-bound interpolation history; its tick gate is shared by runtime and lifecycle checks. */
    class FeedCameraHistory {
        private var level: Any? = null
        private var player: Any? = null
        private var lastTick = Long.MIN_VALUE
        private var advancedTickCount = 0L

        fun bind(nextLevel: Any?, nextPlayer: Any?, resetAndPrime: Runnable): Boolean {
            if (nextLevel == null || nextPlayer == null) {
                clear()
                return false
            }
            if (level === nextLevel && player === nextPlayer) return false
            clear()
            resetAndPrime.run()
            level = nextLevel
            player = nextPlayer
            return true
        }

        fun advance(tick: Long, cameraTick: Runnable): Boolean {
            if (!isBound() || tick == lastTick) return false
            cameraTick.run()
            lastTick = tick
            if (advancedTickCount < Long.MAX_VALUE) advancedTickCount++
            return true
        }

        fun isBound(): Boolean = level != null && player != null

        fun isBoundTo(expectedLevel: Any?, expectedPlayer: Any?): Boolean =
            expectedLevel != null && expectedPlayer != null && level === expectedLevel && player === expectedPlayer

        fun advancedTicks(): Long = advancedTickCount

        fun clear() {
            level = null
            player = null
            lastTick = Long.MIN_VALUE
            advancedTickCount = 0L
        }

        fun clear(resetCamera: Runnable) {
            val wasBound = isBound()
            clear()
            if (wasBound) resetCamera.run()
        }
    }

    /** Public Sodium member lookup is deferred until the exact-version capability preflight. */
    class SodiumTerrainPassAccessor {
        private var passClass: Class<*>? = null
        private var translucentMethod: Method? = null

        fun initialize(candidateClass: Class<*>?, candidateMethod: Method?) {
            clear()
            if (candidateClass == null || candidateMethod == null ||
                candidateMethod.returnType != java.lang.Boolean.TYPE ||
                candidateMethod.name != "isTranslucent" || candidateMethod.parameterCount != 0) {
                throw IllegalArgumentException("invalid-sodium-translucency-accessor")
            }
            passClass = candidateClass
            translucentMethod = candidateMethod
        }

        @Throws(ReflectiveOperationException::class)
        fun prepareOpaqueProbe(candidateClass: Class<*>?, syntheticOpaquePass: Any?) {
            clear()
            if (candidateClass == null || syntheticOpaquePass == null || !candidateClass.isInstance(syntheticOpaquePass)) {
                throw IllegalArgumentException("invalid-sodium-opaque-probe")
            }
            val accessor = candidateClass.getMethod("isTranslucent")
            if (accessor.returnType != java.lang.Boolean.TYPE) throw NoSuchMethodException("isTranslucent-boolean")
            initialize(candidateClass, accessor)
            if (isTranslucent(syntheticOpaquePass)) {
                clear()
                throw IllegalStateException("synthetic-opaque-pass-reported-translucent")
            }
        }

        @Throws(ReflectiveOperationException::class)
        fun verifyOpaqueProbeRoute(candidateClass: Class<*>?, syntheticOpaquePass: Any?,
            expectedPrivateTarget: Any?, mainTarget: Any?): Boolean {
            prepareOpaqueProbe(candidateClass, syntheticOpaquePass)
            val activeClass = candidateClass ?: throw IllegalArgumentException("invalid-sodium-opaque-probe")
            val getTarget = activeClass.getMethod("getTarget")
            val route = getTarget.invoke(syntheticOpaquePass)
            return SpiritLeapPovRenderer.isPrivateTargetRoute(route, expectedPrivateTarget, mainTarget)
        }

        fun isTranslucent(terrainPass: Any?): Boolean {
            val activeClass = passClass
            val accessor = translucentMethod
            if (activeClass == null || accessor == null || terrainPass == null || !activeClass.isInstance(terrainPass)) {
                throw IllegalStateException("sodium-translucency-accessor-unavailable")
            }
            try {
                val value = accessor.invoke(terrainPass)
                if (value is Boolean) return value
            } catch (failure: ReflectiveOperationException) {
                throw IllegalStateException("sodium-translucency-accessor-failed", failure)
            } catch (failure: RuntimeException) {
                throw IllegalStateException("sodium-translucency-accessor-failed", failure)
            }
            throw IllegalStateException("sodium-translucency-accessor-invalid-result")
        }

        fun isTranslucentBranch(terrainPass: Any?, shaderTransparency: Boolean): Boolean =
            shaderTransparency && isTranslucent(terrainPass)

        fun clear() {
            passClass = null
            translucentMethod = null
        }
    }
}

/** Java callers retain their historical singleton field; Kotlin callers can keep the same spelling. */
val SpiritLeapPovRenderer.INSTANCE: SpiritLeapPovRenderer
    get() = this
