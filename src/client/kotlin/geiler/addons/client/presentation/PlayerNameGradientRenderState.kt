package geiler.addons.client.presentation

import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.ARGB
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Carries a per-glyph gradient through Minecraft's deferred text render pipeline. */
object PlayerNameGradientRenderState {
    private val staleColors = ReferenceQueue<TextColor>()
    private val gradients = HashMap<IdentityWeakReference, Gradient>()

    /**
     * Marker color value to gradient, read without a lock for every drawn glyph. Keyed by value rather than
     * TextColor identity so a marker that another mod or a codec round trip rebuilt from its RGB still resolves
     * (an unresolved marker is drawn as a flat red-looking 0xF000nn color).
     */
    private val byValue = ConcurrentHashMap<Int, Gradient>()
    private val resolvedGlyphs = AtomicLong()
    private val unresolvedGlyphs = AtomicLong()
    private val replacedVertices = AtomicLong()
    private const val MARKER_PREFIX = 0xF0
    private val IDLE_CONTEXT = GlyphRenderContext(null, 0, 0L)
    private val activeMarkers = HashSet<Int>()
    private val activeGlyphs: ThreadLocal<ArrayDeque<GlyphRenderContext>> =
        ThreadLocal.withInitial { ArrayDeque() }
    private var nextMarker = 0xF00000
    private val CLOCK_ORIGIN_NANOS = System.nanoTime()

    /** Bounded LRU of live markers keyed by gradient parameters; evicted markers are reclaimed by weak reference. */
    private val markerCache = object : java.util.LinkedHashMap<GradientKey, TextColor>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GradientKey, TextColor>?): Boolean =
            size > MAX_CACHED_MARKERS
    }
    private const val MAX_CACHED_MARKERS = 2048

    /**
     * Returns a style whose color marker is shared by every glyph with identical gradient parameters. Animation
     * is driven by one process-wide clock, so a frame does not need a fresh marker (and a fresh Style, text
     * layout, and map entry) for every glyph of every name.
     */
    @JvmStatic
    fun gradientStyle(
        source: Style,
        colors: List<Int>,
        glyphIndex: Int,
        glyphCount: Int,
        cyclic: Boolean,
        cyclesPerSecond: Float,
        @Suppress("UNUSED_PARAMETER") initialPhase: Double,
        @Suppress("UNUSED_PARAMETER") createdNanos: Long,
    ): Style {
        val key = GradientKey(
            source.getColor(), source.getShadowColor(), colors, glyphIndex, glyphCount, cyclic,
            if (cyclic) cyclesPerSecond else 0.0f,
        )
        synchronized(gradients) {
            markerCache[key]?.let { return source.withColor(it) }
            purgeStaleColors()
            val marker = allocateMarkerLocked()
            val gradient = Gradient(
                source.getColor(),
                source.getShadowColor(),
                java.util.List.copyOf(colors),
                glyphIndex,
                glyphCount,
                cyclic,
                key.cyclesPerSecond,
                0.0,
                CLOCK_ORIGIN_NANOS,
            )
            gradients[IdentityWeakReference(marker, staleColors, marker.value)] = gradient
            byValue[marker.value] = gradient
            markerCache[key] = marker
            return source.withColor(marker)
        }
    }

    private data class GradientKey(
        val sourceColor: TextColor?,
        val sourceShadow: Int?,
        val colors: List<Int>,
        val glyphIndex: Int,
        val glyphCount: Int,
        val cyclic: Boolean,
        val cyclesPerSecond: Float,
    )

    private fun allocateMarkerLocked(): TextColor {
        repeat(0x1000000) {
            nextMarker = (nextMarker + 1) and 0xFFFFFF
            if (activeMarkers.add(nextMarker)) return TextColor.fromRgb(nextMarker)
        }
        throw IllegalStateException("Player name gradient marker space is exhausted")
    }

    @JvmStatic
    fun beginGlyph(style: Style, shadowColor: Int) {
        val gradient = find(style.getColor())
        val stack = activeGlyphs.get()
        if (gradient == null) {
            stack.push(IDLE_CONTEXT)
            return
        }
        resolvedGlyphs.incrementAndGet()
        val shadowPassCount = if (shadowColor == 0) 0 else if (style.isBold()) 2 else 1
        stack.push(GlyphRenderContext(gradient, shadowPassCount, System.nanoTime()))
    }

    @JvmStatic
    fun beginRenderPass() {
        val stack = activeGlyphs.get()
        if (!stack.isEmpty()) {
            val context = stack.peek()
            if (context.gradient != null) context.passIndex = context.nextPassIndex++
        }
    }

    /**
     * `[glyphs drawn with a gradient, glyphs that still carried an unregistered marker color, vertex colors replaced]`
     * since the last call. Glyphs without replaced vertices mean the render hooks never reached a main pass.
     */
    @JvmStatic
    fun drainCounts(): LongArray =
        longArrayOf(resolvedGlyphs.getAndSet(0L), unresolvedGlyphs.getAndSet(0L), replacedVertices.getAndSet(0L))

    @JvmStatic
    fun unresolvedMarkerGlyphs(): Long = unresolvedGlyphs.get()

    @JvmStatic
    fun vertexColor(originalColor: Int, rightSide: Boolean): Int {
        val stack = activeGlyphs.get()
        if (stack.isEmpty()) return originalColor
        val context = stack.peek()
        if (context.gradient == null || context.passIndex < context.primaryPassIndex) return originalColor
        val rgb = context.gradient.colorAt(rightSide, context.sampleNanos)
        replacedVertices.incrementAndGet()
        return (originalColor and 0xFF000000.toInt()) or rgb
    }

    @JvmStatic
    fun endGlyph() {
        val stack = activeGlyphs.get()
        if (!stack.isEmpty()) stack.pop()
        if (stack.isEmpty()) activeGlyphs.remove()
    }

    @JvmStatic
    fun shadowColorFor(style: Style, baseColor: Int, drawShadow: Boolean): Int? {
        val gradient = find(style.getColor()) ?: return null
        val sourceColor = gradient.sourceColor?.let {
            (baseColor and 0xFF000000.toInt()) or it.value
        } ?: baseColor
        val explicitShadow = gradient.sourceShadowColor
        if (explicitShadow != null) {
            val textAlpha = ARGB.alphaFloat(sourceColor)
            val shadowAlpha = ARGB.alphaFloat(explicitShadow)
            if (textAlpha != 1.0f) {
                return ARGB.color(ARGB.as8BitChannel(textAlpha * shadowAlpha), explicitShadow)
            }
            return explicitShadow
        }
        return if (drawShadow) ARGB.scaleRGB(sourceColor, 0.25f) else 0
    }

    private fun find(color: TextColor?): Gradient? {
        if (color == null) return null
        val gradient = byValue[color.value]
        if (gradient == null && color.value ushr 16 == MARKER_PREFIX) unresolvedGlyphs.incrementAndGet()
        return gradient
    }

    private fun purgeStaleColors() {
        while (true) {
            val stale = staleColors.poll() as IdentityWeakReference? ?: return
            gradients.remove(stale)
            byValue.remove(stale.markerValue)
            activeMarkers.remove(stale.markerValue)
        }
    }

    private data class Gradient(
        val sourceColor: TextColor?,
        val sourceShadowColor: Int?,
        val colors: List<Int>,
        val glyphIndex: Int,
        val glyphCount: Int,
        val cyclic: Boolean,
        val cyclesPerSecond: Float,
        val initialPhase: Double,
        val createdNanos: Long,
    ) {
        fun colorAt(rightSide: Boolean, nowNanos: Long): Int {
            val phase = if (cyclic) {
                PlayerNameGradientMath.animatedPhase(initialPhase, createdNanos, nowNanos, cyclesPerSecond)
            } else initialPhase
            return PlayerNameGradientMath.colorAtGlyph(
                colors,
                glyphIndex,
                glyphCount,
                if (rightSide) 1.0 else 0.0,
                phase,
                cyclic,
            )
        }
    }

    private class GlyphRenderContext(
        val gradient: Gradient?,
        val primaryPassIndex: Int,
        val sampleNanos: Long,
    ) {
        var nextPassIndex = 0
        var passIndex = -1
    }

    private class IdentityWeakReference(
        referent: TextColor,
        queue: ReferenceQueue<TextColor>?,
        val markerValue: Int,
    ) : WeakReference<TextColor>(referent, queue) {
        private val hash = System.identityHashCode(referent)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakReference) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}
