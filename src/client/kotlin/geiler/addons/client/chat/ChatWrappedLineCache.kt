package geiler.addons.client.chat

import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Objects
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.ToLongFunction

/** One-layout cache that preserves wrapped segments for unchanged messages. */
class ChatWrappedLineCache<T> {
    private var valid = false
    private var channelId: String? = null
    private var viewportWidth = 0
    private var messageRevision = 0L
    private var animationRevision = 0L
    private var segments: MutableMap<Long, Segment<T>> = LinkedHashMap()
    private var animatedMessageIds: MutableSet<Long> = LinkedHashSet()
    private var lines: MutableList<T> = ArrayList()
    private var lineView: List<T> = emptyList()

    fun <M> getOrCompute(
        selectedChannelId: String?,
        width: Int,
        revision: Long,
        messages: Supplier<out List<M>>,
        messageId: ToLongFunction<M>,
        displayRevision: ToLongFunction<M>,
        wrap: Function<M, out List<T>>,
    ): List<T> = getOrCompute(
        selectedChannelId,
        width,
        revision,
        0L,
        messages,
        messageId,
        displayRevision,
        Predicate { false },
        wrap,
    )

    /**
     * Refreshes animated segments on a frame change without enumerating the message history.
     * Animation is cosmetic (color only), so a wrapped segment must keep the same line count.
     */
    fun <M> getOrCompute(
        selectedChannelId: String?,
        width: Int,
        revision: Long,
        nextAnimationRevision: Long,
        messages: Supplier<out List<M>>,
        messageId: ToLongFunction<M>,
        displayRevision: ToLongFunction<M>,
        animatedMessage: Predicate<in M>,
        wrap: Function<M, out List<T>>,
    ): List<T> {
        val sameLayout = valid && Objects.equals(channelId, selectedChannelId) && viewportWidth == width
        if (sameLayout && messageRevision == revision) {
            if (animationRevision == nextAnimationRevision || animatedMessageIds.isEmpty()) {
                animationRevision = nextAnimationRevision
                return lineView
            }
            if (refreshAnimatedSegments(nextAnimationRevision, wrap)) return lineView
            // A changed line count means the renderer's layout assumptions changed; rebuild once safely.
        }

        val nextSegments = LinkedHashMap<Long, Segment<T>>()
        val nextAnimatedMessageIds = LinkedHashSet<Long>()
        val wrapped = ArrayList<T>()
        for (message in messages.get()) {
            val id = messageId.applyAsLong(message)
            val itemRevision = displayRevision.applyAsLong(message)
            val previous = if (sameLayout) segments[id] else null
            val sameContent = previous != null && previous.displayRevision == itemRevision
            val animated = if (sameContent) previous!!.animated else animatedMessage.test(message)
            val reusable = previous != null && previous.displayRevision == itemRevision &&
                previous.animated == animated &&
                (!animated || previous.animationRevision == nextAnimationRevision)
            val segment = if (reusable) previous!!.lines else java.util.List.copyOf(wrap.apply(message))
            nextSegments[id] = Segment(itemRevision, message, segment, animated, nextAnimationRevision, wrapped.size)
            if (animated) nextAnimatedMessageIds.add(id)
            wrapped.addAll(segment)
        }
        channelId = selectedChannelId
        viewportWidth = width
        messageRevision = revision
        animationRevision = nextAnimationRevision
        segments = nextSegments
        animatedMessageIds = nextAnimatedMessageIds
        lines = wrapped
        lineView = Collections.unmodifiableList(lines)
        valid = true
        return lineView
    }

    @Suppress("UNCHECKED_CAST")
    private fun <M> refreshAnimatedSegments(
        nextAnimationRevision: Long,
        wrap: Function<M, out List<T>>,
    ): Boolean {
        val replacements = LinkedHashMap<Long, List<T>>()
        for (id in animatedMessageIds) {
            val previous = segments[id] ?: continue
            if (previous.animationRevision == nextAnimationRevision) continue
            val message = previous.message as M
            val replacement = java.util.List.copyOf(wrap.apply(message))
            if (replacement.size != previous.lines.size) return false
            replacements[id] = replacement
        }
        for ((id, replacement) in replacements) {
            val previous = segments[id] ?: continue
            for (index in replacement.indices) {
                lines[previous.firstLine + index] = replacement[index]
            }
            segments[id] = Segment(previous.displayRevision, previous.message,
                replacement, true, nextAnimationRevision, previous.firstLine)
        }
        animationRevision = nextAnimationRevision
        return true
    }

    fun invalidate() {
        valid = false
        segments = LinkedHashMap()
        animatedMessageIds = LinkedHashSet()
        lines = ArrayList()
        lineView = emptyList()
    }

    private data class Segment<T>(
        val displayRevision: Long,
        val message: Any?,
        val lines: List<T>,
        val animated: Boolean,
        val animationRevision: Long,
        val firstLine: Int,
    )
}
