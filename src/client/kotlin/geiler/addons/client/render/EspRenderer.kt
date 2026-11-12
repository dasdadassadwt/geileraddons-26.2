package geiler.addons.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import geiler.addons.client.render.WorldRenderBuffer
import it.unimi.dsi.fastutil.longs.LongSet
import net.minecraft.core.BlockPos
import org.joml.Quaternionf
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.CancellationException

/** Draws simple translucent ESP shapes: boxes, rings, UV spheres, lines and labels. */
object EspRenderer {
    /** Glyph cell, in the label's own units: y runs downward, matching text layout. */
    private const val GLYPH_WIDTH = 1.0f
    private const val GLYPH_HEIGHT = 2.0f
    private const val GLYPH_SPACING = 0.4f

    /** Seven-segment strokes per character, each entry {x0, y0, x1, y1} in the glyph cell. */
    private val GLYPHS: Map<Char, Array<FloatArray>> = mapOf(
        '0' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f), floatArrayOf(0f, 1f, 0f, 2f), floatArrayOf(0f, 0f, 0f, 1f)),
        '1' to arrayOf(floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f)),
        '2' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(0f, 1f, 0f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        '3' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        '4' to arrayOf(floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f)),
        '5' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        '6' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 1f, 0f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        '7' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f)),
        '8' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f), floatArrayOf(0f, 1f, 0f, 2f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f)),
        '9' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f)),
        // The vertical runs nearly the full cell so plus and minus can't be confused at distance.
        '+' to arrayOf(floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(0.5f, 0.15f, 0.5f, 1.85f)),
        '-' to arrayOf(floatArrayOf(0f, 1f, 1f, 1f)),
        '?' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 1f), floatArrayOf(0.5f, 1f, 1f, 1f), floatArrayOf(0.5f, 1f, 0.5f, 1.4f), floatArrayOf(0.5f, 1.8f, 0.5f, 2f)),
        'N' to arrayOf(floatArrayOf(0f, 0f, 0f, 2f), floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(1f, 0f, 1f, 2f)),
        'E' to arrayOf(floatArrayOf(0f, 0f, 0f, 2f), floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 1f, 0.8f, 1f), floatArrayOf(0f, 2f, 1f, 2f)),
        'S' to arrayOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0f, 1f), floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 1f, 1f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        'W' to arrayOf(floatArrayOf(0f, 0f, 0.25f, 2f), floatArrayOf(0.25f, 2f, 0.5f, 1f), floatArrayOf(0.5f, 1f, 0.75f, 2f), floatArrayOf(0.75f, 2f, 1f, 0f)),
        '.' to arrayOf(floatArrayOf(0.35f, 1f, 0.65f, 1f)),
        'C' to arrayOf(floatArrayOf(1f, 0f, 0f, 0f), floatArrayOf(0f, 0f, 0f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        'L' to arrayOf(floatArrayOf(0f, 0f, 0f, 2f), floatArrayOf(0f, 2f, 1f, 2f)),
        'I' to arrayOf(floatArrayOf(0.5f, 0f, 0.5f, 2f)),
        'K' to arrayOf(floatArrayOf(0f, 0f, 0f, 2f), floatArrayOf(0f, 1f, 1f, 0f), floatArrayOf(0f, 1f, 1f, 2f)),
        'M' to arrayOf(floatArrayOf(0f, 2f, 0f, 0f), floatArrayOf(0f, 0f, 0.5f, 1f), floatArrayOf(0.5f, 1f, 1f, 0f), floatArrayOf(1f, 0f, 1f, 2f)),
        '!' to arrayOf(floatArrayOf(0.5f, 0f, 0.5f, 1.5f), floatArrayOf(0.5f, 1.9f, 0.5f, 2f)),
    )

    /** How far a depth-tested box is grown past what it wraps, in blocks. */
    private const val DEPTH_MARGIN = 0.01
    private const val SPHERE_LATITUDES = 16
    private const val SPHERE_LONGITUDES = 24
    private const val SPHERE_ROW = SPHERE_LONGITUDES + 1
    private const val RING_SEGMENTS = 48

    /** A unit sphere's vertices, built once instead of recalculating trig values per frame. */
    private val SPHERE = buildUnitSphere()

    @JvmStatic
    fun renderBox(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
    ) = renderBox(poseStack, bufferSource, x, y, z, sizeX, sizeY, sizeZ, fillColor, lineColor, lineWidth, false)

    /** `depthTested` lets world geometry hide the box; otherwise the box draws through walls. */
    @JvmStatic
    fun renderBox(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        x: Double,
        y: Double,
        z: Double,
        sizeX: Double,
        sizeY: Double,
        sizeZ: Double,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        val drawFill = hasAlpha(fillColor)
        val drawOutline = lineWidth > 0 && hasAlpha(lineColor)
        if (!drawFill && !drawOutline) return

        val pose = poseStack.last()
        val margin = if (depthTested) DEPTH_MARGIN else 0.0
        val x0 = (x - margin).toFloat()
        val y0 = (y - margin).toFloat()
        val z0 = (z - margin).toFloat()
        val x1 = (x + sizeX + margin).toFloat()
        val y1 = (y + sizeY + margin).toFloat()
        val z1 = (z + sizeZ + margin).toFloat()

        if (drawFill) {
            val quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested))
            quad(quads, pose, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, fillColor)
            quad(quads, pose, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, fillColor)
            quad(quads, pose, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, fillColor)
            quad(quads, pose, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, fillColor)
            quad(quads, pose, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, fillColor)
            quad(quads, pose, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, fillColor)
        }

        // Keep the twelve edges explicit so a per-frame edge table is not allocated per box.
        if (drawOutline) {
            val lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested))
            line(lines, pose, x0, y0, z0, x1, y0, z0, lineColor, lineWidth)
            line(lines, pose, x1, y0, z0, x1, y0, z1, lineColor, lineWidth)
            line(lines, pose, x1, y0, z1, x0, y0, z1, lineColor, lineWidth)
            line(lines, pose, x0, y0, z1, x0, y0, z0, lineColor, lineWidth)
            line(lines, pose, x0, y1, z0, x1, y1, z0, lineColor, lineWidth)
            line(lines, pose, x1, y1, z0, x1, y1, z1, lineColor, lineWidth)
            line(lines, pose, x1, y1, z1, x0, y1, z1, lineColor, lineWidth)
            line(lines, pose, x0, y1, z1, x0, y1, z0, lineColor, lineWidth)
            line(lines, pose, x0, y0, z0, x0, y1, z0, lineColor, lineWidth)
            line(lines, pose, x1, y0, z0, x1, y1, z0, lineColor, lineWidth)
            line(lines, pose, x1, y0, z1, x1, y1, z1, lineColor, lineWidth)
            line(lines, pose, x0, y0, z1, x0, y1, z1, lineColor, lineWidth)
        }
    }

    /** Centered marker box whose yaw affects both its fill and its outline. */
    @JvmStatic
    fun renderRotatedBox(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        centerX: Double,
        baseY: Double,
        centerZ: Double,
        size: Float,
        degrees: Float,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        val drawFill = hasAlpha(fillColor)
        val drawOutline = lineWidth > 0 && hasAlpha(lineColor)
        if (!drawFill && !drawOutline) return
        val pose = poseStack.last()
        val half = size / 2.0f
        val sin = Math.sin(Math.toRadians(degrees.toDouble())).toFloat()
        val cos = Math.cos(Math.toRadians(degrees.toDouble())).toFloat()
        val corners = Array(4) { FloatArray(2) }
        for (i in 0 until 4) {
            val x = if (i == 0 || i == 3) -half else half
            val z = if (i < 2) -half else half
            corners[i][0] = centerX.toFloat() + x * cos - z * sin
            corners[i][1] = centerZ.toFloat() + x * sin + z * cos
        }
        val bottom = baseY.toFloat()
        val top = bottom + size
        val quads = if (drawFill) bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested)) else null
        val lines = if (drawOutline) bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested)) else null
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            if (drawFill) quad(quads!!, pose, a[0], bottom, a[1], b[0], bottom, b[1], b[0], top, b[1], a[0], top, a[1], fillColor)
            if (drawOutline) {
                line(lines!!, pose, a[0], bottom, a[1], b[0], bottom, b[1], lineColor, lineWidth)
                line(lines, pose, a[0], top, a[1], b[0], top, b[1], lineColor, lineWidth)
                line(lines, pose, a[0], bottom, a[1], a[0], top, a[1], lineColor, lineWidth)
            }
        }
        if (drawFill) {
            quad(quads!!, pose, corners[0][0], top, corners[0][1], corners[1][0], top, corners[1][1], corners[2][0], top, corners[2][1], corners[3][0], top, corners[3][1], fillColor)
            quad(quads, pose, corners[3][0], bottom, corners[3][1], corners[2][0], bottom, corners[2][1], corners[1][0], bottom, corners[1][1], corners[0][0], bottom, corners[0][1], fillColor)
        }
    }

    private fun hasAlpha(argb: Int): Boolean = argb ushr 24 != 0

    @JvmStatic
    fun renderSphere(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cx: Double,
        cy: Double,
        cz: Double,
        radius: Float,
        fillColor: Int,
    ) = renderSphere(poseStack, bufferSource, cx, cy, cz, radius, fillColor, false)

    /** Sphere highlight with the same optional world-geometry occlusion used by other ESP shapes. */
    @JvmStatic
    fun renderSphere(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cx: Double,
        cy: Double,
        cz: Double,
        radius: Float,
        fillColor: Int,
        depthTested: Boolean,
    ) {
        val pose = poseStack.last()
        val quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested))
        for (lat in 0 until SPHERE_LATITUDES) {
            for (lon in 0 until SPHERE_LONGITUDES) {
                sphereVertex(quads, pose, index(lat, lon), cx, cy, cz, radius, fillColor)
                sphereVertex(quads, pose, index(lat + 1, lon), cx, cy, cz, radius, fillColor)
                sphereVertex(quads, pose, index(lat + 1, lon + 1), cx, cy, cz, radius, fillColor)
                sphereVertex(quads, pose, index(lat, lon + 1), cx, cy, cz, radius, fillColor)
            }
        }
    }

    /** Single straight line through walls; coordinates are camera-relative like the other shapes. */
    @JvmStatic
    fun renderLine(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        x0: Double,
        y0: Double,
        z0: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        color: Int,
        width: Float,
    ) = renderLine(poseStack, bufferSource, x0, y0, z0, x1, y1, z1, color, width, false)

    /** Single straight line with the same optional depth test used by ESP boxes. */
    @JvmStatic
    fun renderLine(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        x0: Double,
        y0: Double,
        z0: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        color: Int,
        width: Float,
        depthTested: Boolean,
    ) = line(
        bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested)),
        poseStack.last(), x0.toFloat(), y0.toFloat(), z0.toFloat(), x1.toFloat(), y1.toFloat(), z1.toFloat(), color, width,
    )

    /** Draws only the exposed surfaces and outline edges of a connected voxel union. */
    @JvmStatic
    fun renderBlockUnion(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        positions: Iterable<BlockPos?>,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        fillEnabled: Boolean,
        outlineEnabled: Boolean,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        @Suppress("UNCHECKED_CAST")
        val blocks: Set<BlockPos> = if (positions is Set<*>) {
            positions as Set<BlockPos>
        } else {
            HashSet<BlockPos>().apply {
                for (position in positions) if (position != null) add(position.immutable())
            }
        }
        renderBlockUnionInternal(
            poseStack, bufferSource, blocks, null, null, cameraX, cameraY, cameraZ,
            fillEnabled, outlineEnabled, fillColor, lineColor, lineWidth, depthTested,
        )
    }

    /** Draws separated single-block boxes without allocating a set and contour map per block. */
    @JvmStatic
    fun renderIndividualBlocks(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        positions: Iterable<BlockPos?>,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        fillEnabled: Boolean,
        outlineEnabled: Boolean,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        if (!fillEnabled && !outlineEnabled) return
        val pose = poseStack.last()
        val margin = if (depthTested) DEPTH_MARGIN.toFloat() else 0f
        val quads = if (fillEnabled) bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested)) else null
        val lines = if (outlineEnabled) bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested)) else null
        val fill = fillEnabled && fillColor ushr 24 != 0
        for (block in positions) {
            if (block == null) continue
            val x0 = (block.x - cameraX).toFloat()
            val y0 = (block.y - cameraY).toFloat()
            val z0 = (block.z - cameraZ).toFloat()
            val x1 = x0 + 1
            val y1 = y0 + 1
            val z1 = z0 + 1
            if (fill) {
                val buffer = quads!!
                quad(buffer, pose, x0, y0, z0 - margin, x1, y0, z0 - margin, x1, y1, z0 - margin, x0, y1, z0 - margin, fillColor)
                quad(buffer, pose, x1, y0, z1 + margin, x0, y0, z1 + margin, x0, y1, z1 + margin, x1, y1, z1 + margin, fillColor)
                quad(buffer, pose, x0 - margin, y0, z1, x0 - margin, y0, z0, x0 - margin, y1, z0, x0 - margin, y1, z1, fillColor)
                quad(buffer, pose, x1 + margin, y0, z0, x1 + margin, y0, z1, x1 + margin, y1, z1, x1 + margin, y1, z0, fillColor)
                quad(buffer, pose, x0, y1 + margin, z0, x1, y1 + margin, z0, x1, y1 + margin, z1, x0, y1 + margin, z1, fillColor)
                quad(buffer, pose, x0, y0 - margin, z1, x1, y0 - margin, z1, x1, y0 - margin, z0, x0, y0 - margin, z0, fillColor)
            }
            lines?.let { buffer ->
                line(buffer, pose, x0, y0, z0, x1, y0, z0, lineColor, lineWidth)
                line(buffer, pose, x1, y0, z0, x1, y0, z1, lineColor, lineWidth)
                line(buffer, pose, x1, y0, z1, x0, y0, z1, lineColor, lineWidth)
                line(buffer, pose, x0, y0, z1, x0, y0, z0, lineColor, lineWidth)
                line(buffer, pose, x0, y1, z0, x1, y1, z0, lineColor, lineWidth)
                line(buffer, pose, x1, y1, z0, x1, y1, z1, lineColor, lineWidth)
                line(buffer, pose, x1, y1, z1, x0, y1, z1, lineColor, lineWidth)
                line(buffer, pose, x0, y1, z1, x0, y1, z0, lineColor, lineWidth)
                line(buffer, pose, x0, y0, z0, x0, y1, z0, lineColor, lineWidth)
                line(buffer, pose, x1, y0, z0, x1, y1, z0, lineColor, lineWidth)
                line(buffer, pose, x1, y0, z1, x1, y1, z1, lineColor, lineWidth)
                line(buffer, pose, x0, y0, z1, x0, y1, z1, lineColor, lineWidth)
            }
        }
    }

    /** Draws a pre-indexed block union using cached contour edges. */
    @JvmStatic
    fun renderBlockUnionPrepared(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        blocks: Set<BlockPos>,
        packedMembership: LongSet?,
        outlineSegments: List<OutlineSegment>?,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        fillEnabled: Boolean,
        outlineEnabled: Boolean,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) = renderBlockUnionInternal(
        poseStack, bufferSource, blocks, packedMembership, outlineSegments, cameraX, cameraY, cameraZ,
        fillEnabled, outlineEnabled, fillColor, lineColor, lineWidth, depthTested,
    )

    private fun renderBlockUnionInternal(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        blocks: Set<BlockPos>,
        packedMembership: LongSet?,
        preparedOutline: List<OutlineSegment>?,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        fillEnabled: Boolean,
        outlineEnabled: Boolean,
        fillColor: Int,
        lineColor: Int,
        lineWidth: Float,
        depthTested: Boolean,
    ) {
        if (blocks.isEmpty() || (!fillEnabled && !outlineEnabled)) return
        val pose = poseStack.last()
        val margin = if (depthTested) DEPTH_MARGIN.toFloat() else 0f
        val quads = if (fillEnabled) bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested)) else null
        val fill = fillEnabled && fillColor ushr 24 != 0
        for (block in blocks) {
            val x = block.x
            val y = block.y
            val z = block.z
            for (axis in 0 until 3) {
                for (direction in -1..1 step 2) {
                    val dx = if (axis == 0) direction else 0
                    val dy = if (axis == 1) direction else 0
                    val dz = if (axis == 2) direction else 0
                    val adjacent = if (packedMembership == null) {
                        blocks.contains(block.offset(dx, dy, dz))
                    } else {
                        packedMembership.contains(BlockPos.asLong(x + dx, y + dy, z + dz))
                    }
                    if (adjacent) continue
                    val face = face(x, y, z, axis, direction)
                    if (fill) drawFace(quads!!, pose, face, cameraX, cameraY, cameraZ, margin, fillColor)
                }
            }
        }
        if (outlineEnabled) {
            val lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested))
            val segments = preparedOutline ?: blockOutlineSegments(blocks)
            for (segment in segments) {
                line(
                    lines, pose,
                    (segment.x0 - cameraX).toFloat(), (segment.y0 - cameraY).toFloat(), (segment.z0 - cameraZ).toFloat(),
                    (segment.x1 - cameraX).toFloat(), (segment.y1 - cameraY).toFloat(), (segment.z1 - cameraZ).toFloat(),
                    lineColor, lineWidth,
                )
            }
        }
    }

    /** Builds the outside contour, merging adjacent one-block segments into continuous edges. */
    @JvmStatic
    fun blockOutlineSegments(blocks: Set<BlockPos>): List<OutlineSegment> {
        val edgeCounts = HashMap<PlaneEdge, Int>()
        var checked = 0
        for (block in blocks) {
            if ((checked++ and 0x3ff) == 0 && Thread.currentThread().isInterrupted) throw CancellationException()
            val x = block.x
            val y = block.y
            val z = block.z
            for (axis in 0 until 3) {
                for (direction in -1..1 step 2) {
                    val dx = if (axis == 0) direction else 0
                    val dy = if (axis == 1) direction else 0
                    val dz = if (axis == 2) direction else 0
                    if (blocks.contains(block.offset(dx, dy, dz))) continue
                    val face = face(x, y, z, axis, direction)
                    for (edge in 0 until 4) {
                        val gridEdge = GridEdge.of(face.corners[edge], face.corners[(edge + 1) % 4])
                        val planeEdge = PlaneEdge(face.axis, face.plane, gridEdge)
                        edgeCounts[planeEdge] = (edgeCounts[planeEdge] ?: 0) + 1
                    }
                }
            }
        }
        val boundary = HashSet<GridEdge>()
        for ((planeEdge, count) in edgeCounts) if (count == 1) boundary.add(planeEdge.edge)
        return mergeAdjacentEdges(boundary)
    }

    private fun mergeAdjacentEdges(edges: Set<GridEdge>): List<OutlineSegment> {
        val grouped = HashMap<LineKey, MutableList<Interval>>()
        for (edge in edges) {
            val a = edge.a
            val b = edge.b
            val key: LineKey
            val start: Int
            val end: Int
            if (a.x != b.x) {
                key = LineKey(0, a.y, a.z)
                start = a.x
                end = b.x
            } else if (a.y != b.y) {
                key = LineKey(1, a.x, a.z)
                start = a.y
                end = b.y
            } else {
                key = LineKey(2, a.x, a.y)
                start = a.z
                end = b.z
            }
            grouped.getOrPut(key) { ArrayList() }.add(Interval(start, end))
        }
        val merged = ArrayList<OutlineSegment>()
        for ((key, intervals) in grouped) {
            intervals.sortWith(Comparator.comparingInt(Interval::start))
            var start = intervals.first().start
            var end = intervals.first().end
            for (index in 1 until intervals.size) {
                val next = intervals[index]
                if (next.start <= end) {
                    end = maxOf(end, next.end)
                } else {
                    merged.add(segment(key, start, end))
                    start = next.start
                    end = next.end
                }
            }
            merged.add(segment(key, start, end))
        }
        return Collections.unmodifiableList(merged)
    }

    private fun segment(key: LineKey, start: Int, end: Int): OutlineSegment = when (key.axis) {
        0 -> OutlineSegment(start, key.first, key.second, end, key.first, key.second)
        1 -> OutlineSegment(key.first, start, key.second, key.first, end, key.second)
        else -> OutlineSegment(key.first, key.second, start, key.first, key.second, end)
    }

    private fun face(x: Int, y: Int, z: Int, axis: Int, direction: Int): Face {
        val plane: Int
        val corners: Array<GridPoint>
        if (axis == 0 && direction < 0) {
            plane = x
            corners = points(x, y, z, x, y + 1, z, x, y + 1, z + 1, x, y, z + 1)
        } else if (axis == 0) {
            plane = x + 1
            corners = points(x + 1, y, z + 1, x + 1, y + 1, z + 1, x + 1, y + 1, z, x + 1, y, z)
        } else if (axis == 1 && direction < 0) {
            plane = y
            corners = points(x, y, z + 1, x + 1, y, z + 1, x + 1, y, z, x, y, z)
        } else if (axis == 1) {
            plane = y + 1
            corners = points(x, y + 1, z, x + 1, y + 1, z, x + 1, y + 1, z + 1, x, y + 1, z + 1)
        } else if (direction < 0) {
            plane = z
            corners = points(x + 1, y, z, x + 1, y + 1, z, x, y + 1, z, x, y, z)
        } else {
            plane = z + 1
            corners = points(x, y, z + 1, x, y + 1, z + 1, x + 1, y + 1, z + 1, x + 1, y, z + 1)
        }
        return Face(axis, plane, direction, corners)
    }

    private fun points(
        x0: Int, y0: Int, z0: Int,
        x1: Int, y1: Int, z1: Int,
        x2: Int, y2: Int, z2: Int,
        x3: Int, y3: Int, z3: Int,
    ) = arrayOf(GridPoint(x0, y0, z0), GridPoint(x1, y1, z1), GridPoint(x2, y2, z2), GridPoint(x3, y3, z3))

    private fun drawFace(
        quads: VertexConsumer,
        pose: PoseStack.Pose,
        face: Face,
        cameraX: Double,
        cameraY: Double,
        cameraZ: Double,
        margin: Float,
        color: Int,
    ) {
        for (point in face.corners) {
            val x = (point.x - cameraX + if (face.axis == 0) face.direction * margin else 0f).toFloat()
            val y = (point.y - cameraY + if (face.axis == 1) face.direction * margin else 0f).toFloat()
            val z = (point.z - cameraZ + if (face.axis == 2) face.direction * margin else 0f).toFloat()
            quads.addVertex(pose, x, y, z).setColor(color)
        }
    }

    private data class GridPoint(val x: Int, val y: Int, val z: Int) : Comparable<GridPoint> {
        override fun compareTo(other: GridPoint): Int {
            if (x != other.x) return x.compareTo(other.x)
            if (y != other.y) return y.compareTo(other.y)
            return z.compareTo(other.z)
        }
    }

    private class GridEdge(a: GridPoint, b: GridPoint) {
        val a: GridPoint
        val b: GridPoint

        init {
            if (a.compareTo(b) > 0) {
                this.a = b
                this.b = a
            } else {
                this.a = a
                this.b = b
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other || (other is GridEdge && a == other.a && b == other.b)

        override fun hashCode(): Int = 31 * a.hashCode() + b.hashCode()

        companion object {
            fun of(a: GridPoint, b: GridPoint): GridEdge = GridEdge(a, b)
        }
    }

    private data class Face(val axis: Int, val plane: Int, val direction: Int, val corners: Array<GridPoint>)
    private data class PlaneEdge(val axis: Int, val plane: Int, val edge: GridEdge)
    private data class LineKey(val axis: Int, val first: Int, val second: Int)
    private data class Interval(val start: Int, val end: Int)

    @JvmRecord
    data class OutlineSegment(val x0: Int, val y0: Int, val z0: Int, val x1: Int, val y1: Int, val z1: Int)

    /** Draws a horizontal camera-relative ring using the requested line pipeline. */
    @JvmStatic
    fun renderRing(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cx: Double,
        cy: Double,
        cz: Double,
        radius: Float,
        color: Int,
        width: Float,
        depthTested: Boolean,
    ) {
        if (!radius.isFinite() || radius <= 0) return
        val lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.lines(depthTested))
        val pose = poseStack.last()
        val step = Math.PI * 2.0 / RING_SEGMENTS
        var previousX = (cx + radius).toFloat()
        var previousZ = cz.toFloat()
        for (segment in 1..RING_SEGMENTS) {
            val angle = step * segment
            val currentX = (cx + Math.cos(angle) * radius).toFloat()
            val currentZ = (cz + Math.sin(angle) * radius).toFloat()
            line(lines, pose, previousX, cy.toFloat(), previousZ, currentX, cy.toFloat(), currentZ, color, width)
            previousX = currentX
            previousZ = currentZ
        }
    }

    /** Draws a filled square, disc, or annulus parallel to the ground. Coordinates are camera-relative. */
    @JvmStatic
    fun renderFlatArea(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cx: Double,
        cy: Double,
        cz: Double,
        outerRadius: Double,
        innerRadius: Double,
        square: Boolean,
        color: Int,
        depthTested: Boolean,
    ) {
        if (!outerRadius.isFinite() || outerRadius <= 0 || !innerRadius.isFinite() || innerRadius < 0 || innerRadius >= outerRadius) return
        val pose = poseStack.last()
        val quads = bufferSource.getBuffer(GeilerAddonsRenderTypes.quads(depthTested))
        if (square) {
            val x0 = (cx - outerRadius).toFloat()
            val x1 = (cx + outerRadius).toFloat()
            val z0 = (cz - outerRadius).toFloat()
            val z1 = (cz + outerRadius).toFloat()
            quad(quads, pose, x0, cy.toFloat(), z0, x0, cy.toFloat(), z1, x1, cy.toFloat(), z1, x1, cy.toFloat(), z0, color)
            return
        }

        val step = Math.PI * 2.0 / RING_SEGMENTS
        for (segment in 0 until RING_SEGMENTS) {
            val firstAngle = step * segment
            val secondAngle = step * (segment + 1)
            val outerX0 = (cx + Math.cos(firstAngle) * outerRadius).toFloat()
            val outerZ0 = (cz + Math.sin(firstAngle) * outerRadius).toFloat()
            val outerX1 = (cx + Math.cos(secondAngle) * outerRadius).toFloat()
            val outerZ1 = (cz + Math.sin(secondAngle) * outerRadius).toFloat()
            if (innerRadius == 0.0) {
                quad(quads, pose, cx.toFloat(), cy.toFloat(), cz.toFloat(), outerX0, cy.toFloat(), outerZ0, outerX1, cy.toFloat(), outerZ1, outerX1, cy.toFloat(), outerZ1, color)
                continue
            }
            val innerX0 = (cx + Math.cos(firstAngle) * innerRadius).toFloat()
            val innerZ0 = (cz + Math.sin(firstAngle) * innerRadius).toFloat()
            val innerX1 = (cx + Math.cos(secondAngle) * innerRadius).toFloat()
            val innerZ1 = (cz + Math.sin(secondAngle) * innerRadius).toFloat()
            quad(quads, pose, outerX0, cy.toFloat(), outerZ0, outerX1, cy.toFloat(), outerZ1, innerX1, cy.toFloat(), innerZ1, innerX0, cy.toFloat(), innerZ0, color)
        }
    }

    /** Billboarded label at a camera-relative position, drawn through walls. */
    @JvmStatic
    fun renderLabel(
        poseStack: PoseStack,
        bufferSource: WorldRenderBuffer.BufferSource,
        cameraRotation: Quaternionf,
        x: Double,
        y: Double,
        z: Double,
        text: String,
        color: Int,
        height: Float,
        lineWidth: Float,
    ) {
        if (text.isEmpty()) return
        val scale = height / GLYPH_HEIGHT
        poseStack.pushPose()
        poseStack.translate(x, y, z)
        poseStack.mulPose(cameraRotation)
        // Negative on x/y because glyphs are laid out top-left down, opposite of world axes.
        poseStack.scale(-scale, -scale, scale)
        val advance = GLYPH_WIDTH + GLYPH_SPACING
        var cursorX = -(text.length * advance - GLYPH_SPACING) / 2.0f
        val pose = poseStack.last()
        val lines = bufferSource.getBuffer(GeilerAddonsRenderTypes.ESP_LINES)
        for (i in text.indices) {
            val glyph = GLYPHS[text[i]]
            if (glyph != null) {
                for (segment in glyph) {
                    line(lines, pose, cursorX + segment[0], segment[1], 0f, cursorX + segment[2], segment[3], 0f, color, lineWidth)
                }
            }
            cursorX += advance
        }
        poseStack.popPose()
    }

    private fun buildUnitSphere(): FloatArray {
        val vertices = FloatArray((SPHERE_LATITUDES + 1) * SPHERE_ROW * 3)
        var at = 0
        for (lat in 0..SPHERE_LATITUDES) {
            val theta = Math.PI * lat / SPHERE_LATITUDES
            val sinTheta = Math.sin(theta)
            val cosTheta = Math.cos(theta)
            for (lon in 0..SPHERE_LONGITUDES) {
                val phi = 2 * Math.PI * lon / SPHERE_LONGITUDES
                vertices[at++] = (sinTheta * Math.cos(phi)).toFloat()
                vertices[at++] = cosTheta.toFloat()
                vertices[at++] = (sinTheta * Math.sin(phi)).toFloat()
            }
        }
        return vertices
    }

    private fun index(lat: Int, lon: Int): Int = (lat * SPHERE_ROW + lon) * 3

    private fun sphereVertex(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        at: Int,
        cx: Double,
        cy: Double,
        cz: Double,
        radius: Float,
        color: Int,
    ) {
        buffer.addVertex(
            pose,
            (cx + radius * SPHERE[at]).toFloat(),
            (cy + radius * SPHERE[at + 1]).toFloat(),
            (cz + radius * SPHERE[at + 2]).toFloat(),
        ).setColor(color)
    }

    private fun quad(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        color: Int,
    ) {
        buffer.addVertex(pose, x0, y0, z0).setColor(color)
        buffer.addVertex(pose, x1, y1, z1).setColor(color)
        buffer.addVertex(pose, x2, y2, z2).setColor(color)
        buffer.addVertex(pose, x3, y3, z3).setColor(color)
    }

    private fun line(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        ax: Float,
        ay: Float,
        az: Float,
        bx: Float,
        by: Float,
        bz: Float,
        color: Int,
        width: Float,
    ) {
        var dx = bx - ax
        var dy = by - ay
        var dz = bz - az
        val length = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        // A zero-length line normalises to NaN, which corrupts the whole line batch, not just it.
        if (length == 0f) return
        dx /= length
        dy /= length
        dz /= length
        buffer.addVertex(pose, ax, ay, az).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width)
        buffer.addVertex(pose, bx, by, bz).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(width)
    }
}
