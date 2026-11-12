package geiler.addons.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderType

/**
 * Collects the mod's existing ESP vertices during extraction and submits them through Minecraft's
 * 26.2 render-node API. Vertex positions and normals are copied as they are emitted, so deferred
 * render callbacks never read Minecraft world or entity state.
 */
object WorldRenderBuffer {
    private val active = ThreadLocal<BufferSource?>()

    @JvmStatic
    fun bind(collector: SubmitNodeCollector): Scope {
        val previous = active.get()
        val current = BufferSource(collector)
        active.set(current)
        return Scope(current, previous)
    }

    @JvmStatic
    fun current(): BufferSource = checkNotNull(active.get()) {
        "World ESP geometry can only be emitted during LevelRenderEvents.COLLECT_SUBMITS"
    }

    class Scope internal constructor(
        private val current: BufferSource,
        private val previous: BufferSource?,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                current.endBatch()
            } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }
    }

    class BufferSource internal constructor(
        private val collector: SubmitNodeCollector,
    ) {
        private val batches = LinkedHashMap<RenderType, RecordedBatch>()
        private var nextOrder = 0

        fun getBuffer(renderType: RenderType): VertexConsumer {
            require(isSupported(renderType)) { "Unsupported GeilerAddons world render type: $renderType" }
            return batches.getOrPut(renderType) {
                RecordedBatch(isLineType(renderType))
            }
        }

        fun endBatch(renderType: RenderType) {
            val batch = batches.remove(renderType) ?: return
            if (batch.vertexCount == 0) return

            // The old BufferSource drew each flush in sequence. Give each submitted batch its own
            // collector order so overlapping feature geometry keeps that ordering on 26.2.
            collector.order(nextOrder++).submitCustomGeometry(PoseStack(), renderType) { _, output ->
                batch.replay(output)
            }
        }

        fun endBatch() {
            batches.keys.toList().forEach(::endBatch)
        }

        private fun isSupported(renderType: RenderType): Boolean =
            renderType === GeilerAddonsRenderTypes.ESP_QUADS ||
                renderType === GeilerAddonsRenderTypes.ESP_LINES ||
                renderType === GeilerAddonsRenderTypes.DEPTH_QUADS ||
                renderType === GeilerAddonsRenderTypes.DEPTH_LINES

        private fun isLineType(renderType: RenderType): Boolean =
            renderType === GeilerAddonsRenderTypes.ESP_LINES ||
                renderType === GeilerAddonsRenderTypes.DEPTH_LINES
    }

    private class RecordedBatch(private val lines: Boolean) : VertexConsumer {
        private var positions = FloatArray(INITIAL_VERTEX_CAPACITY * 3)
        private var colors = IntArray(INITIAL_VERTEX_CAPACITY) { DEFAULT_COLOR }
        private var normals = if (lines) FloatArray(INITIAL_VERTEX_CAPACITY * 3) else FloatArray(0)
        private var widths = if (lines) FloatArray(INITIAL_VERTEX_CAPACITY) { 1.0f } else FloatArray(0)
        var vertexCount: Int = 0
            private set

        private var currentColor = DEFAULT_COLOR
        private var currentNormalX = 0.0f
        private var currentNormalY = 0.0f
        private var currentNormalZ = 0.0f
        private var currentLineWidth = 1.0f

        override fun addVertex(x: Float, y: Float, z: Float): VertexConsumer {
            ensureCapacity(vertexCount + 1)
            val vertex = vertexCount++
            val positionIndex = vertex * 3
            positions[positionIndex] = x
            positions[positionIndex + 1] = y
            positions[positionIndex + 2] = z
            colors[vertex] = currentColor
            if (lines) {
                normals[positionIndex] = currentNormalX
                normals[positionIndex + 1] = currentNormalY
                normals[positionIndex + 2] = currentNormalZ
                widths[vertex] = currentLineWidth
            }
            return this
        }

        override fun setColor(red: Int, green: Int, blue: Int, alpha: Int): VertexConsumer =
            setColor((alpha shl 24) or (red shl 16) or (green shl 8) or blue)

        override fun setColor(argb: Int): VertexConsumer {
            currentColor = argb
            if (vertexCount > 0) colors[vertexCount - 1] = argb
            return this
        }

        override fun setUv(u: Float, v: Float): VertexConsumer = this

        override fun setUv1(u: Int, v: Int): VertexConsumer = this

        override fun setUv2(u: Int, v: Int): VertexConsumer = this

        override fun setNormal(x: Float, y: Float, z: Float): VertexConsumer {
            currentNormalX = x
            currentNormalY = y
            currentNormalZ = z
            if (lines && vertexCount > 0) {
                val index = (vertexCount - 1) * 3
                normals[index] = x
                normals[index + 1] = y
                normals[index + 2] = z
            }
            return this
        }

        override fun setLineWidth(width: Float): VertexConsumer {
            currentLineWidth = width
            if (lines && vertexCount > 0) widths[vertexCount - 1] = width
            return this
        }

        fun replay(output: VertexConsumer) {
            for (vertex in 0 until vertexCount) {
                val positionIndex = vertex * 3
                val target = output.addVertex(
                    positions[positionIndex],
                    positions[positionIndex + 1],
                    positions[positionIndex + 2],
                ).setColor(colors[vertex])
                if (lines) {
                    target.setNormal(
                        normals[positionIndex],
                        normals[positionIndex + 1],
                        normals[positionIndex + 2],
                    ).setLineWidth(widths[vertex])
                }
            }
        }

        private fun ensureCapacity(required: Int) {
            if (required <= colors.size) return
            var capacity = colors.size
            while (capacity < required) capacity *= 2
            colors = colors.copyOf(capacity)
            positions = positions.copyOf(capacity * 3)
            if (lines) {
                normals = normals.copyOf(capacity * 3)
                widths = widths.copyOf(capacity)
            }
        }
    }

    private const val INITIAL_VERTEX_CAPACITY = 128
    private const val DEFAULT_COLOR = -1
}
