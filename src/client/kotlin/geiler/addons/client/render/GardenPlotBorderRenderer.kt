package geiler.addons.client.render

import com.mojang.blaze3d.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import geiler.addons.client.farming.GardenPlotGeometry
import geiler.addons.client.farming.GardenPlotGrid
import geiler.addons.client.farming.GardenPlotState
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.Projection
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3
import java.util.ArrayList
import java.util.LinkedHashSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Renders Garden plot outlines and screen-facing status pills without owning plot state. */
object GardenPlotBorderRenderer {
    private const val UNKNOWN_DASH_LENGTH = 8.0
    private const val UNKNOWN_DASH_GAP = 6.0
    /** SkyBlock plot bounds are inclusive/exclusive grid edges, so the outline follows them exactly. */
    private const val BORDER_INSET = 0.0
    private const val MIN_WALL_HEIGHT = 0.5
    private const val MAX_WALL_HEIGHT = 16.0
    /** Clear and unknown borders retain the original local range to keep their cost bounded. */
    const val MAX_PLOT_DISTANCE = 384.0
    /** The Garden diagonal plus 25 blocks covers every plot from anywhere in its 5 by 5 grid. */
    @JvmField
    val MAX_INFESTED_PLOT_DISTANCE =
        GardenPlotGrid.PLOT_SIZE * GardenPlotGrid.GRID_SIZE * kotlin.math.sqrt(2.0) + 25.0
    /** Covers the Garden's farthest box corners, including a raised border and normal camera height. */
    private const val EXTENDED_PLOT_FAR_PLANE = 1_024.0f
    private const val MAX_GARDEN_PLOT_COUNT = GardenPlotGrid.GRID_SIZE * GardenPlotGrid.GRID_SIZE
    private const val MAX_SUBMITTED_SEGMENTS = MAX_GARDEN_PLOT_COUNT * 12
    /** Most dashes one edge may contribute, keeping unknown borders within the same segment budget. */
    private const val MAX_DASHES_PER_EDGE = 1

    private var extendedPlotProjection: Projection? = null
    private var extendedPlotProjectionBuffer: ProjectionMatrixBuffer? = null
    @Volatile
    private var releaseExtendedProjectionRequested = false

    private val skippedWorldRender = worldSummary(false, 0, 0, 0, emptyList(), 0, 0, 0, false)
    private val skippedLabelRender = labelSummary(false, 0, 0, emptyList(), 0, 0, 0, 0)

    @JvmStatic
    fun renderWorld(
        context: LevelRenderContext?,
        plots: List<GardenPlotState.PlotStatus?>?,
        borderY: Double,
        wallHeight: Double,
        lineWidth: Float,
        depthTested: Boolean,
        showClear: Boolean,
        showUnknown: Boolean,
        fillEnabled: Boolean,
        fillColor: Int,
        infestedColor: Int,
        clearColor: Int,
        unknownColor: Int,
    ): WorldRenderSummary {
        if (context == null || plots.isNullOrEmpty() || !borderY.isFinite() || !wallHeight.isFinite()) {
            return skippedWorldRender
        }
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread() || !RenderSystem.isOnRenderThread() || mc.level == null || mc.player == null ||
            mc.gameRenderer == null
        ) {
            return skippedWorldRender
        }
        if (releaseExtendedProjectionRequested) releaseExtendedProjection()

        val camera = mc.gameRenderer.mainCamera()
        val cameraPosition = camera.position()
        val poseStack = context.poseStack()
        val bufferSource = WorldRenderBuffer.current()
        val width = max(0.5f, min(5.0f, lineWidth))
        val top = borderY + max(MIN_WALL_HEIGHT, min(MAX_WALL_HEIGHT, wallHeight))
        val inRange = worldCandidates(plots, cameraPosition)
        val invalidCount = invalidPlotCount(plots)
        val culledCount = max(0, boundedStatusCount(plots) - invalidCount - inRange.size)
        val currentFarPlane = currentFarPlane(mc)
        val normalProjection = ArrayList<GardenPlotState.PlotStatus>(inRange.size)
        val extendedProjection = ArrayList<GardenPlotState.PlotStatus>()
        for (status in inRange) {
            if (status.status == GardenPlotState.Status.INFESTED &&
                plotFarthestCornerDistanceSquared(status.plotId, cameraPosition, borderY, top) >
                currentFarPlane.toDouble() * currentFarPlane
            ) {
                extendedProjection.add(status)
            } else {
                normalProjection.add(status)
            }
        }

        val counts = RenderCounts()
        renderCandidates(
            normalProjection,
            poseStack,
            bufferSource,
            cameraPosition,
            borderY,
            top,
            width,
            depthTested,
            showClear,
            showUnknown,
            fillEnabled,
            fillColor,
            infestedColor,
            clearColor,
            unknownColor,
            counts,
        )

        var projectionWasExtended = false
        if (extendedProjection.isNotEmpty()) {
            var backedUp = false
            try {
                val projectionBuffer = extendedProjectionBuffer()
                val fieldOfView = camera.fov
                if (RenderSystem.getProjectionType() == ProjectionType.PERSPECTIVE && fieldOfView.isFinite()) {
                    val projection = extendedPlotProjection!!
                    val window = mc.window
                    val frameWidth = max(1, window.width)
                    val frameHeight = max(1, window.height)
                    projection.setupPerspective(
                        Camera.PROJECTION_Z_NEAR,
                        max(EXTENDED_PLOT_FAR_PLANE, currentFarPlane + 64.0f),
                        fieldOfView,
                        frameWidth.toFloat(),
                        frameHeight.toFloat(),
                    )
                    RenderSystem.backupProjectionMatrix()
                    backedUp = true
                    RenderSystem.setProjectionMatrix(
                        projectionBuffer.getBuffer(projection),
                        ProjectionType.PERSPECTIVE,
                    )
                    renderCandidates(
                        extendedProjection,
                        poseStack,
                        bufferSource,
                        cameraPosition,
                        borderY,
                        top,
                        width,
                        depthTested,
                        showClear,
                        showUnknown,
                        fillEnabled,
                        fillColor,
                        infestedColor,
                        clearColor,
                        unknownColor,
                        counts,
                    )
                    projectionWasExtended = true
                }
            } finally {
                if (backedUp) RenderSystem.restoreProjectionMatrix()
            }
        }
        if (!projectionWasExtended && extendedProjection.isNotEmpty()) {
            renderCandidates(
                extendedProjection,
                poseStack,
                bufferSource,
                cameraPosition,
                borderY,
                top,
                width,
                depthTested,
                showClear,
                showUnknown,
                fillEnabled,
                fillColor,
                infestedColor,
                clearColor,
                unknownColor,
                counts,
            )
        }

        return worldSummary(
            true,
            inRange.size,
            counts.submittedPlotCount,
            counts.submittedInfestedPlotIds.size,
            ArrayList(counts.submittedInfestedPlotIds),
            culledCount,
            invalidCount,
            counts.submittedSegmentCount,
            projectionWasExtended,
        )
    }

    /** Releases the cached GPU projection on the render thread, deferring when disabled elsewhere. */
    @JvmStatic
    fun releaseExtendedProjection() {
        if (!RenderSystem.isOnRenderThread()) {
            releaseExtendedProjectionRequested = true
            return
        }
        val buffer = extendedPlotProjectionBuffer
        extendedPlotProjectionBuffer = null
        extendedPlotProjection = null
        releaseExtendedProjectionRequested = false
        buffer?.close()
    }

    private fun extendedProjectionBuffer(): ProjectionMatrixBuffer {
        if (extendedPlotProjection == null) extendedPlotProjection = Projection()
        if (extendedPlotProjectionBuffer == null) {
            extendedPlotProjectionBuffer = ProjectionMatrixBuffer("Infested plot extended far plane")
        }
        return extendedPlotProjectionBuffer!!
    }

    private fun currentFarPlane(mc: Minecraft): Float {
        val cameraState: CameraRenderState? =
            mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState
        return if (cameraState != null && cameraState.depthFar.isFinite() && cameraState.depthFar > 0.0f) {
            cameraState.depthFar
        } else {
            128.0f
        }
    }

    /** Farthest box corner from the camera, used to decide whether a larger projection is needed. */
    @JvmStatic
    fun plotFarthestCornerDistanceSquared(plotId: Int, camera: Vec3?, bottom: Double, top: Double): Double {
        val plot = GardenPlotGrid.plotById(plotId).orElse(null)
        if (plot == null || camera == null) return Double.MAX_VALUE
        val dx = max(abs(plot.minX - camera.x), abs(plot.maxX() - camera.x))
        val dz = max(abs(plot.minZ - camera.z), abs(plot.maxZ() - camera.z))
        val dy = max(abs(bottom - camera.y), abs(top - camera.y))
        return dx * dx + dy * dy + dz * dz
    }

    private fun renderCandidates(
        candidates: List<GardenPlotState.PlotStatus>,
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        borderY: Double,
        top: Double,
        width: Float,
        depthTested: Boolean,
        showClear: Boolean,
        showUnknown: Boolean,
        fillEnabled: Boolean,
        fillColor: Int,
        infestedColor: Int,
        clearColor: Int,
        unknownColor: Int,
        counts: RenderCounts,
    ) {
        var drew = false
        for (status in candidates) {
            val plot = GardenPlotGrid.plotById(status.plotId).orElse(null) ?: continue
            val color = when (status.status) {
                GardenPlotState.Status.INFESTED -> infestedColor
                GardenPlotState.Status.CLEAR -> if (showClear) clearColor else continue
                GardenPlotState.Status.UNKNOWN -> if (showUnknown) unknownColor else continue
                null -> throw IllegalStateException("Unhandled plot state null")
            }

            val border = if (status.status == GardenPlotState.Status.UNKNOWN) {
                GardenPlotGeometry.dashedSegments(
                    GardenPlotGeometry.boxEdges(plot, borderY, top, BORDER_INSET),
                    UNKNOWN_DASH_LENGTH,
                    UNKNOWN_DASH_GAP,
                    MAX_DASHES_PER_EDGE,
                )
            } else {
                GardenPlotGeometry.boxEdges(plot, borderY, top, BORDER_INSET)
            }
            if (fillEnabled) {
                EspRenderer.renderBox(
                    poseStack,
                    bufferSource,
                    plot.minX - cameraPosition.x,
                    borderY - cameraPosition.y,
                    plot.minZ - cameraPosition.z,
                    plot.maxX() - plot.minX,
                    top - borderY,
                    plot.maxZ() - plot.minZ,
                    fillColor,
                    0,
                    0.5f,
                    depthTested,
                )
                drew = true
            }

            // The border is intentionally opaque at every visible distance. Alpha belongs to the
            // optional fill only; translucent outlines become hard to read against Garden crops.
            val opaqueColor = color or -16777216
            val renderedSegments = renderSegments(
                poseStack,
                bufferSource,
                cameraPosition,
                border,
                opaqueColor,
                if (status.status == GardenPlotState.Status.CLEAR) min(width, 1.8f) else width,
                depthTested,
            )
            if (renderedSegments > 0) {
                drew = true
                counts.submittedSegmentCount =
                    min(MAX_SUBMITTED_SEGMENTS, counts.submittedSegmentCount + renderedSegments)
            }
            if (fillEnabled || renderedSegments > 0) counts.submittedPlotCount++
            if (renderedSegments > 0 && status.status == GardenPlotState.Status.INFESTED) {
                counts.submittedInfestedPlotIds.add(status.plotId)
            }
        }
        if (drew) GeilerAddonsRenderTypes.endBatches(bufferSource)
    }

    private class RenderCounts {
        var submittedPlotCount = 0
        var submittedSegmentCount = 0
        val submittedInfestedPlotIds = LinkedHashSet<Int>()
    }

    @JvmStatic
    fun renderLabels(
        graphics: GuiGraphicsExtractor?,
        plots: List<GardenPlotState.PlotStatus?>?,
        borderY: Double,
        wallHeight: Double,
        scale: Float,
        infestedColor: Int,
        clearColor: Int,
        unknownColor: Int,
    ): LabelRenderSummary {
        if (graphics == null || plots.isNullOrEmpty() || !borderY.isFinite() || !wallHeight.isFinite()) {
            return skippedLabelRender
        }
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread() || mc.level == null || mc.player == null || mc.gui.hud.isHidden() ||
            mc.gameRenderer == null
        ) {
            return skippedLabelRender
        }

        val camera = mc.gameRenderer.mainCamera()
        val safeScale = max(0.55f, min(2.0f, scale))
        val labelY = borderY + max(MIN_WALL_HEIGHT, min(MAX_WALL_HEIGHT, wallHeight)) / 2.0
        val candidates = labelCandidates(plots, camera.position())
        val missingPestCount = candidates.count { it.pestCount == null }
        val validInfestedCount = validInfestedPlotCount(plots)
        val invalidInfestedCount = invalidInfestedPlotCount(plots)
        val culledInfestedCount = max(0, validInfestedCount - candidates.size)
        var projectionSkippedCount = 0
        val submittedInfestedPlotIds = LinkedHashSet<Int>()

        for (status in candidates) {
            val plot = GardenPlotGrid.plotById(status.plotId).orElse(null) ?: continue
            val label = labelText(status) ?: continue
            val world = Vec3(plot.centerX(), labelY, plot.centerZ())
            if (ProjectedLabelRenderer.drawFloatingText(
                    graphics,
                    camera,
                    mc.font,
                    world,
                    label,
                    infestedColor or -16777216,
                    safeScale,
                )
            ) {
                submittedInfestedPlotIds.add(status.plotId)
            } else {
                projectionSkippedCount++
            }
        }
        return labelSummary(
            true,
            candidates.size,
            submittedInfestedPlotIds.size,
            ArrayList(submittedInfestedPlotIds),
            missingPestCount,
            culledInfestedCount,
            invalidInfestedCount,
            projectionSkippedCount,
        )
    }

    /**
     * Distance is measured to the plot edge rather than its centre, and the incoming order is kept.
     * This helper has no Minecraft dependency so its cut-off can be checked offline.
     */
    @JvmStatic
    fun withinRange(
        plots: List<GardenPlotState.PlotStatus?>?,
        cameraPosition: Vec3?,
        maxDistance: Double,
    ): List<GardenPlotState.PlotStatus> {
        if (plots.isNullOrEmpty() || cameraPosition == null || !cameraPosition.x.isFinite() ||
            !cameraPosition.z.isFinite() || !maxDistance.isFinite() || maxDistance < 0.0
        ) {
            return emptyList()
        }
        val limit = maxDistance * maxDistance
        val kept = ArrayList<GardenPlotState.PlotStatus>(min(plots.size, MAX_GARDEN_PLOT_COUNT))
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots[index] ?: continue
            if (status.status == null || !GardenPlotGrid.isValidId(status.plotId) ||
                edgeDistanceSquared(status.plotId, cameraPosition) > limit
            ) {
                continue
            }
            kept.add(status)
        }
        return java.util.List.copyOf(kept)
    }

    /** Applies the wide Garden range to confirmed infestations and the legacy range to other states. */
    @JvmStatic
    fun worldCandidates(
        plots: List<GardenPlotState.PlotStatus?>?,
        cameraPosition: Vec3?,
    ): List<GardenPlotState.PlotStatus> {
        if (plots.isNullOrEmpty() || cameraPosition == null || !cameraPosition.x.isFinite() ||
            !cameraPosition.z.isFinite()
        ) {
            return emptyList()
        }
        val kept = ArrayList<GardenPlotState.PlotStatus>(min(plots.size, MAX_GARDEN_PLOT_COUNT))
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots[index] ?: continue
            if (status.status == null || !GardenPlotGrid.isValidId(status.plotId)) continue
            val maxDistance = if (status.status == GardenPlotState.Status.INFESTED) {
                MAX_INFESTED_PLOT_DISTANCE
            } else {
                MAX_PLOT_DISTANCE
            }
            if (edgeDistanceSquared(status.plotId, cameraPosition) > maxDistance * maxDistance) continue
            kept.add(status)
        }
        return java.util.List.copyOf(kept)
    }

    /**
     * Every confirmed infested plot inside the Garden-wide range, in snapshot order. Pest counts are
     * optional evidence and do not decide whether the plot receives a label.
     */
    @JvmStatic
    fun labelCandidates(
        plots: List<GardenPlotState.PlotStatus?>?,
        cameraPosition: Vec3?,
    ): List<GardenPlotState.PlotStatus> {
        if (plots.isNullOrEmpty() || cameraPosition == null) return emptyList()
        val infested = ArrayList<GardenPlotState.PlotStatus>(MAX_GARDEN_PLOT_COUNT)
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots[index]
            if (status != null && status.status == GardenPlotState.Status.INFESTED &&
                GardenPlotGrid.isValidId(status.plotId)
            ) {
                infested.add(status)
            }
        }
        return withinRange(infested, cameraPosition, MAX_INFESTED_PLOT_DISTANCE)
    }

    /** Exact label text for a confirmed infested plot; unknown pest counts are omitted. */
    @JvmStatic
    fun labelText(status: GardenPlotState.PlotStatus?): String? {
        if (status == null || status.status != GardenPlotState.Status.INFESTED ||
            !GardenPlotGrid.isValidId(status.plotId)
        ) {
            return null
        }
        val label = "Plot ${status.plotId}"
        val pestCount = status.pestCount ?: return label
        return label + ": " + pestCount + if (pestCount == 1) " pest" else " pests"
    }

    /** Squared distance from the camera to the nearest point of a plot in the horizontal plane. */
    private fun edgeDistanceSquared(plotId: Int, cameraPosition: Vec3): Double {
        val plot = GardenPlotGrid.plotById(plotId).orElse(null) ?: return Double.MAX_VALUE
        val dx = max(0.0, max(plot.minX - cameraPosition.x, cameraPosition.x - plot.maxX()))
        val dz = max(0.0, max(plot.minZ - cameraPosition.z, cameraPosition.z - plot.maxZ()))
        return dx * dx + dz * dz
    }

    private fun boundedStatusCount(plots: List<GardenPlotState.PlotStatus?>?): Int =
        if (plots == null) 0 else min(plots.size, MAX_GARDEN_PLOT_COUNT)

    private fun invalidPlotCount(plots: List<GardenPlotState.PlotStatus?>?): Int {
        var invalid = 0
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots!![index]
            if (status == null || status.status == null || !GardenPlotGrid.isValidId(status.plotId)) invalid++
        }
        return invalid
    }

    private fun validInfestedPlotCount(plots: List<GardenPlotState.PlotStatus?>?): Int {
        var valid = 0
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots!![index]
            if (status != null && status.status == GardenPlotState.Status.INFESTED &&
                GardenPlotGrid.isValidId(status.plotId)
            ) {
                valid++
            }
        }
        return valid
    }

    private fun invalidInfestedPlotCount(plots: List<GardenPlotState.PlotStatus?>?): Int {
        var invalid = 0
        for (index in 0 until boundedStatusCount(plots)) {
            val status = plots!![index]
            if (status != null && status.status == GardenPlotState.Status.INFESTED &&
                !GardenPlotGrid.isValidId(status.plotId)
            ) {
                invalid++
            }
        }
        return invalid
    }

    private fun boundedPlotIds(plotIds: List<Int?>?): List<Int> {
        if (plotIds.isNullOrEmpty()) return emptyList()
        val bounded = LinkedHashSet<Int>()
        for (index in 0 until min(plotIds.size, MAX_GARDEN_PLOT_COUNT)) {
            val plotId = plotIds[index]
            if (plotId != null && GardenPlotGrid.isValidId(plotId)) bounded.add(plotId)
            if (bounded.size >= MAX_GARDEN_PLOT_COUNT) break
        }
        return java.util.List.copyOf(bounded)
    }

    private fun boundedCount(value: Int, maximum: Int): Int = max(0, min(maximum, value))

    private fun worldSummary(
        completed: Boolean,
        inRangePlotCount: Int,
        submittedPlotCount: Int,
        submittedInfestedBorderCount: Int,
        submittedInfestedPlotIds: List<Int?>?,
        culledPlotCount: Int,
        invalidPlotCount: Int,
        submittedSegmentCount: Int,
        extendedProjection: Boolean,
    ): WorldRenderSummary {
        val ids = boundedPlotIds(submittedInfestedPlotIds)
        return WorldRenderSummary(
            completed,
            boundedCount(inRangePlotCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(submittedPlotCount, MAX_GARDEN_PLOT_COUNT),
            ids.size,
            ids,
            boundedCount(culledPlotCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(invalidPlotCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(submittedSegmentCount, MAX_SUBMITTED_SEGMENTS),
            extendedProjection,
        )
    }

    private fun labelSummary(
        completed: Boolean,
        eligibleInfestedPlotCount: Int,
        submittedLabelCount: Int,
        submittedInfestedPlotIds: List<Int?>?,
        missingPestCount: Int,
        culledInfestedPlotCount: Int,
        invalidInfestedPlotCount: Int,
        projectionSkippedCount: Int,
    ): LabelRenderSummary {
        val ids = boundedPlotIds(submittedInfestedPlotIds)
        return LabelRenderSummary(
            completed,
            boundedCount(eligibleInfestedPlotCount, MAX_GARDEN_PLOT_COUNT),
            ids.size,
            ids,
            boundedCount(missingPestCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(culledInfestedPlotCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(invalidInfestedPlotCount, MAX_GARDEN_PLOT_COUNT),
            boundedCount(projectionSkippedCount, MAX_GARDEN_PLOT_COUNT),
        )
    }

    private fun renderSegments(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cameraPosition: Vec3,
        segments: List<GardenPlotGeometry.Segment>,
        color: Int,
        width: Float,
        depthTested: Boolean,
    ): Int {
        for (segment in segments) {
            val from = segment.from
            val to = segment.to
            EspRenderer.renderLine(
                poseStack,
                bufferSource,
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
        return segments.size
    }

    @JvmRecord
    data class WorldRenderSummary(
        val completed: Boolean,
        val inRangePlotCount: Int,
        val submittedPlotCount: Int,
        val submittedInfestedBorderCount: Int,
        val submittedInfestedPlotIds: List<Int>,
        val culledPlotCount: Int,
        val invalidPlotCount: Int,
        val submittedSegmentCount: Int,
        val extendedProjection: Boolean,
    ) {
        override fun toString(): String =
            "WorldRenderSummary[completed=$completed, inRangePlotCount=$inRangePlotCount, " +
                "submittedPlotCount=$submittedPlotCount, submittedInfestedBorderCount=$submittedInfestedBorderCount, " +
                "submittedInfestedPlotIds=$submittedInfestedPlotIds, culledPlotCount=$culledPlotCount, " +
                "invalidPlotCount=$invalidPlotCount, submittedSegmentCount=$submittedSegmentCount, " +
                "extendedProjection=$extendedProjection]"
    }

    @JvmRecord
    data class LabelRenderSummary(
        val completed: Boolean,
        val eligibleInfestedPlotCount: Int,
        val submittedLabelCount: Int,
        val submittedInfestedPlotIds: List<Int>,
        val missingPestCount: Int,
        val culledInfestedPlotCount: Int,
        val invalidInfestedPlotCount: Int,
        val projectionSkippedCount: Int,
    ) {
        override fun toString(): String =
            "LabelRenderSummary[completed=$completed, eligibleInfestedPlotCount=$eligibleInfestedPlotCount, " +
                "submittedLabelCount=$submittedLabelCount, submittedInfestedPlotIds=$submittedInfestedPlotIds, " +
                "missingPestCount=$missingPestCount, culledInfestedPlotCount=$culledInfestedPlotCount, " +
                "invalidInfestedPlotCount=$invalidInfestedPlotCount, projectionSkippedCount=$projectionSkippedCount]"
    }
}
