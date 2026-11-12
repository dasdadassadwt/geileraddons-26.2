package geiler.addons.client.dungeon.odin

import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.CreeperPairVisual
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.PuzzleVisualSnapshot
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.QuizOptionVisual
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.WeirdoNpcVisual
import geiler.addons.client.dungeon.odin.DungeonOdinPuzzleObserver.WaterBoardVisual
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.BoxStyle
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.CreeperSettings
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.QuizSettings
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.Settings
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.VisibleCreeperPair
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.WaterSettings
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.WaterTracerSegment
import geiler.addons.client.dungeon.odin.DungeonPuzzleVisualRenderer.WeirdosSettings
import geiler.addons.client.render.EspRenderer
import geiler.addons.client.render.GeilerAddonsRenderTypes
import geiler.addons.client.render.WorldRenderBuffer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BeaconRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.Locale

/** Draws source-confirmed visual markers for dungeon puzzle snapshots. */
object DungeonPuzzleVisualRendererLogic {
    private const val SOURCE_LINE_WIDTH = 3.0f
    private val CREEPER_PALETTE = listOf(
        0xFFFFAA00.toInt(), // Minecraft gold
        0xFF55FF55.toInt(), // Minecraft green
        0xFFFF55FF.toInt(), // Minecraft light purple
        0xFF00AAAA.toInt(), // Minecraft dark aqua
        0xFFFFFF55.toInt(), // Minecraft yellow
        0xFFAA0000.toInt(), // Minecraft dark red
        0xFFFFFFFF.toInt(), // White
        0xFFAA00AA.toInt(), // Minecraft dark purple
    )

    /**
     * Renders one immutable observation frame. tracerStart is the interpolated main-camera
     * position plus a short offset along its forward vector, keeping tracers on the crosshair ray.
     * Snapshot positions are world-space block coordinates supplied by the observer; this class
     * never infers puzzle coordinates.
     */
    @JvmStatic
    fun render(
        poseStack: PoseStack?,
        buffers: WorldRenderBuffer.BufferSource?,
        nodeCollector: SubmitNodeCollector?,
        cameraPosition: Vec3?,
        tracerStart: Vec3?,
        snapshot: PuzzleVisualSnapshot?,
        settings: Settings?,
    ) {
        if (poseStack == null || buffers == null || nodeCollector == null || cameraPosition == null ||
            snapshot == null || settings == null
        ) {
            return
        }

        renderWater(poseStack, buffers, cameraPosition, tracerStart, snapshot.waterBoard(), settings.water()!!)
        renderCreeper(poseStack, buffers, cameraPosition, snapshot.creeperPairs(), settings.creeper()!!)
        renderWeirdos(poseStack, buffers, cameraPosition, snapshot.weirdos(), settings.weirdos()!!)
        renderQuiz(poseStack, buffers, nodeCollector, cameraPosition, snapshot.quizOptions(), settings.quiz()!!)
        GeilerAddonsRenderTypes.endBatches(buffers)
    }

    private fun renderWater(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        tracerStart: Vec3?,
        water: WaterBoardVisual?,
        settings: WaterSettings?,
    ) {
        for (segment in waterTracerSegmentsInternal(water, tracerStart, settings)) {
            renderLine(
                poseStack,
                buffers,
                segment.from()!!,
                segment.to()!!,
                cameraPosition,
                segment.color(),
                SOURCE_LINE_WIDTH,
                false,
            )
        }
    }

    private fun renderCreeper(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        pairs: List<CreeperPairVisual?>?,
        settings: CreeperSettings,
    ) {
        for (visible in visibleCreeperPairsInternal(pairs, settings)) {
            val pair = visible.pair()!!
            val color = visible.color()
            val firstEndpoint = pair.first() ?: continue
            val secondEndpoint = pair.second() ?: continue
            val first = blockPos(firstEndpoint)
            val second = blockPos(secondEndpoint)
            renderStyledBlock(poseStack, buffers, first, cameraPosition, color, settings.style(), SOURCE_LINE_WIDTH, true)
            renderStyledBlock(poseStack, buffers, second, cameraPosition, color, settings.style(), SOURCE_LINE_WIDTH, true)
            if (settings.showTracer()) {
                renderLine(
                    poseStack,
                    buffers,
                    Vec3.atCenterOf(first),
                    Vec3.atCenterOf(second),
                    cameraPosition,
                    color,
                    SOURCE_LINE_WIDTH,
                    false,
                )
            }
        }
    }

    private fun renderWeirdos(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        weirdos: List<WeirdoNpcVisual?>?,
        settings: WeirdosSettings,
    ) {
        for (marker in weirdos!!) {
            if (marker == null) continue
            val markerPosition = marker.position()
            if (markerPosition == null) continue
            val color = if (marker.correctChest()) settings.correctColor() else settings.wrongColor()
            renderStyledBlock(
                poseStack,
                buffers,
                blockPos(markerPosition),
                cameraPosition,
                color,
                settings.style(),
                SOURCE_LINE_WIDTH,
                true,
            )
        }
    }

    private fun renderQuiz(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        nodeCollector: SubmitNodeCollector,
        cameraPosition: Vec3,
        options: List<QuizOptionVisual?>?,
        settings: QuizSettings,
    ) {
        val client = Minecraft.getInstance()
        val level = client.level ?: return
        val animationTime = (level.gameTime % 40L).toFloat() +
            client.deltaTracker.getGameTimeDeltaPartialTick(false)
        val player = client.player
        for (option in options!!) {
            if (option == null || !option.correct()) continue
            val optionPosition = option.position()
            if (optionPosition == null) continue
            val position = blockPos(optionPosition)
            EspRenderer.renderBox(
                poseStack,
                buffers,
                position.getX() - cameraPosition.x,
                position.getY() - cameraPosition.y,
                position.getZ() - cameraPosition.z,
                1.0,
                1.0,
                1.0,
                settings.color(),
                0,
                0.0f,
                settings.depthTested(),
            )
            val radiusScale = if (player != null && player.isScoping()) {
                1.0f
            } else {
                Math.max(1.0f, cameraPosition.subtract(Vec3.atCenterOf(position)).horizontalDistance().toFloat() / 96.0f)
            }
            poseStack.pushPose()
            poseStack.translate(
                position.getX() - cameraPosition.x,
                position.getY() - cameraPosition.y,
                position.getZ() - cameraPosition.z,
            )
            BeaconRenderer.submitBeaconBeam(
                poseStack,
                nodeCollector,
                BeaconRenderer.BEAM_LOCATION,
                1.0f,
                animationTime,
                0,
                BeaconRenderer.MAX_RENDER_Y,
                settings.color(),
                BeaconRenderer.SOLID_BEAM_RADIUS * radiusScale,
                BeaconRenderer.BEAM_GLOW_RADIUS * radiusScale,
            )
            poseStack.popPose()
        }
    }

    private fun renderStyledBlock(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        position: BlockPos,
        cameraPosition: Vec3,
        color: Int,
        style: BoxStyle?,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        val fillColor = styledFillColor(color, style)
        val outlineColor = if (style == BoxStyle.OUTLINE || style == BoxStyle.FILLED_OUTLINE) color else 0
        EspRenderer.renderBox(
            poseStack,
            buffers,
            position.getX() - cameraPosition.x,
            position.getY() - cameraPosition.y,
            position.getZ() - cameraPosition.z,
            1.0,
            1.0,
            1.0,
            fillColor,
            outlineColor,
            lineWidth,
            depthTested,
        )
    }

    private fun renderLine(
        poseStack: PoseStack,
        buffers: WorldRenderBuffer.BufferSource,
        from: Vec3,
        to: Vec3,
        cameraPosition: Vec3,
        color: Int,
        width: Float,
        depthTested: Boolean,
    ) {
        EspRenderer.renderLine(
            poseStack,
            buffers,
            from.x - cameraPosition.x,
            from.y - cameraPosition.y,
            from.z - cameraPosition.z,
            to.x - cameraPosition.x,
            to.y - cameraPosition.y,
            to.z - cameraPosition.z,
            color,
            width,
            depthTested,
        )
    }

    private fun center(position: DungeonOdinPuzzleObserver.WorldBlock): Vec3 =
        Vec3.atCenterOf(blockPos(position))

    private fun waterTracerSegmentsInternal(
        water: WaterBoardVisual?,
        tracerStart: Vec3?,
        settings: WaterSettings?,
    ): List<WaterTracerSegment> {
        if (water == null || settings == null || !settings.showTracer() || tracerStart == null ||
            !water.routeConfirmed() || water.orderedUpcomingLevers().isEmpty()
        ) {
            return java.util.List.of()
        }
        val upcoming = water.orderedUpcomingLevers()
        val first = center(upcoming.first())
        if (upcoming.size < 2) {
            return java.util.List.of(WaterTracerSegment(tracerStart, first, settings.firstColor()))
        }
        val second = center(upcoming[1])
        return java.util.List.of(
            WaterTracerSegment(tracerStart, first, settings.firstColor()),
            WaterTracerSegment(first, second, settings.secondColor()),
        )
    }

    @JvmStatic
    fun waterTracerSegments(
        water: WaterBoardVisual?,
        tracerStart: Vec3?,
        settings: WaterSettings?,
    ): List<*> = waterTracerSegmentsInternal(water, tracerStart, settings)

    private fun visibleCreeperPairsInternal(
        pairs: List<CreeperPairVisual?>?,
        settings: CreeperSettings?,
    ): List<VisibleCreeperPair> {
        if (pairs == null || settings == null) return java.util.List.of()
        val visible = ArrayList<VisibleCreeperPair>()
        for (pair in pairs) {
            if (pair == null || pair.progressed() || pair.first() == null || pair.second() == null) continue
            visible.add(
                VisibleCreeperPair(
                    pair.catalogIndex(),
                    pair,
                    creeperColor(pair.catalogIndex(), settings.alpha()),
                ),
            )
        }
        return java.util.List.copyOf(visible)
    }

    @JvmStatic
    fun visibleCreeperPairs(
        pairs: List<CreeperPairVisual?>?,
        settings: CreeperSettings?,
    ): List<*> = visibleCreeperPairsInternal(pairs, settings)

    @JvmStatic
    fun creeperColor(catalogIndex: Int, alpha: Float): Int {
        val index = Math.floorMod(catalogIndex, CREEPER_PALETTE.size)
        return replaceAlpha(CREEPER_PALETTE[index], alpha)
    }

    @JvmStatic
    fun styledFillColor(argb: Int, style: BoxStyle?): Int =
        if (style == BoxStyle.FILLED_OUTLINE) scaleAlpha(argb, 0.5f)
        else if (style == BoxStyle.FILLED) argb else 0

    @JvmStatic
    fun rgbaHexToArgb(rgba: Int): Int = ((rgba and 0xFF) shl 24) or ((rgba ushr 8) and 0x00FFFFFF)

    @JvmStatic
    fun boxStyle(value: String?): BoxStyle {
        val normalized = value?.trim()?.replace(' ', '_')?.uppercase(Locale.ROOT) ?: ""
        return when (normalized) {
            "FILLED" -> BoxStyle.FILLED
            "OUTLINE" -> BoxStyle.OUTLINE
            "FILLED_OUTLINE" -> BoxStyle.FILLED_OUTLINE
            else -> BoxStyle.FILLED_OUTLINE
        }
    }

    private fun blockPos(position: DungeonOdinPuzzleObserver.WorldBlock): BlockPos =
        BlockPos(position.x(), position.y(), position.z())

    private fun replaceAlpha(argb: Int, alpha: Float): Int {
        val boundedAlpha = (clamp(alpha, 0.0f, 1.0f) * 255.0f).toInt()
        return (argb and 0x00FFFFFF) or (boundedAlpha shl 24)
    }

    private fun scaleAlpha(argb: Int, factor: Float): Int {
        val alpha = argb ushr 24
        val scaledAlpha = (alpha * clamp(factor, 0.0f, 1.0f)).toInt()
        return (argb and 0x00FFFFFF) or (scaledAlpha shl 24)
    }

    @JvmStatic
    fun defaults(): Settings = Settings(
        WaterSettings(true, rgbaHexToArgb(0x55FF55FF), rgbaHexToArgb(0xFFAA00FF.toInt())),
        CreeperSettings(BoxStyle.FILLED_OUTLINE, false, 0.70f),
        WeirdosSettings(
            BoxStyle.FILLED_OUTLINE,
            rgbaHexToArgb(0x55FF55B2),
            rgbaHexToArgb(0xFF5555B2.toInt()),
        ),
        QuizSettings(rgbaHexToArgb(0x55FF55BF), false),
    )

    private fun clamp(value: Float, min: Float, max: Float): Float =
        Math.max(min, Math.min(max, value))
}
