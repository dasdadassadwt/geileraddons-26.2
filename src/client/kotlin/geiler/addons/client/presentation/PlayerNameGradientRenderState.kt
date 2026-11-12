package geiler.addons.client.presentation

import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.ARGB
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet

/** Carries a per-glyph gradient through Minecraft's deferred text render pipeline. */
object PlayerNameGradientRenderState {
    private val staleColors = ReferenceQueue<TextColor>()
    private val gradients = HashMap<IdentityWeakReference, Gradient>()
    private val activeMarkers = HashSet<Int>()
    private val activeGlyphs: ThreadLocal<ArrayDeque<GlyphRenderContext>> =
        ThreadLocal.withInitial { ArrayDeque() }
    private var nextMarker = 0xF00000

    @JvmStatic
    fun gradientStyle(
        source: Style,
        colors: List<Int>,
        glyphIndex: Int,
        glyphCount: Int,
        cyclic: Boolean,
        cyclesPerSecond: Float,
        initialPhase: Double,
        createdNanos: Long,
    ): Style {
        val marker = allocateMarker()
        val gradient = Gradient(
            source.getColor(),
            source.getShadowColor(),
            java.util.List.copyOf(colors),
            glyphIndex,
            glyphCount,
            cyclic,
            cyclesPerSecond,
            initialPhase,
            createdNanos,
        )
        synchronized(gradients) {
            purgeStaleColors()
            gradients[IdentityWeakReference(marker, staleColors, marker.value)] = gradient
        }
        return source.withColor(marker)
    }

    private fun allocateMarker(): TextColor {
        synchronized(gradients) {
            purgeStaleColors()
            repeat(0x1000000) {
                nextMarker = (nextMarker + 1) and 0xFFFFFF
                if (activeMarkers.add(nextMarker)) return TextColor.fromRgb(nextMarker)
            }
        }
        throw IllegalStateException("Player name gradient marker space is exhausted")
    }

    @JvmStatic
    fun beginGlyph(style: Style, shadowColor: Int) {
        val gradient = find(style.getColor())
        val shadowPassCount = if (gradient == null || shadowColor == 0) 0 else if (style.isBold()) 2 else 1
        activeGlyphs.get().push(GlyphRenderContext(gradient, shadowPassCount, System.nanoTime()))
    }

    @JvmStatic
    fun beginRenderPass() {
        val stack = activeGlyphs.get()
        if (!stack.isEmpty()) {
            val context = stack.peek()
            context.passIndex = context.nextPassIndex++
        }
    }

    @JvmStatic
    fun vertexColor(originalColor: Int, rightSide: Boolean): Int {
        val stack = activeGlyphs.get()
        if (stack.isEmpty()) return originalColor
        val context = stack.peek()
        if (context.gradient == null || context.passIndex < context.primaryPassIndex) return originalColor
        val rgb = context.gradient.colorAt(rightSide, context.sampleNanos)
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
        synchronized(gradients) {
            purgeStaleColors()
            return gradients[IdentityWeakReference(color, null, color.value)]
        }
    }

    private fun purgeStaleColors() {
        while (true) {
            val stale = staleColors.poll() as IdentityWeakReference? ?: return
            gradients.remove(stale)
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
