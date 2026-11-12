package geiler.addons.client.presentation

import geiler.addons.client.module.impl.PlayerNamesModule
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.StringDecomposer
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayList
import java.util.HashMap
import java.util.Optional
import java.util.WeakHashMap

/** Adapts Minecraft's non-component text inputs to the shared player-name display transform. */
object PlayerNameFontTransform {
    private const val MAX_CACHE_ENTRIES = 4096
    private val FORMATTED_TEXT_CACHE: MutableMap<FormattedText, CachedText> = WeakHashMap()
    private val SEQUENCE_CACHE: MutableMap<FormattedCharSequence, CachedSequence> = WeakHashMap()
    private val LEGACY_STRING_CACHE: MutableMap<String, CachedLegacySequence> = WeakHashMap()
    private val STALE_SEQUENCE_ORIGINS = ReferenceQueue<FormattedCharSequence>()
    private val SEQUENCE_ORIGINS: MutableMap<SequenceReference, SequenceOrigin> = HashMap()
    private val STALE_PRESENTED_SEQUENCES = ReferenceQueue<FormattedCharSequence>()
    private val PRESENTED_SEQUENCES: MutableMap<SequenceReference, PresentedSequence> = HashMap()

    /** Builds a styled sequence for legacy-formatted String rendering when aliases or styles match. */
    @JvmStatic
    fun transformLegacyString(source: String?, path: Int): FormattedCharSequence? {
        if (source == null) return null
        val module = PlayerNamesModule.INSTANCE
        val startedAtNanos = module.beginFontTransformTiming(path)
        if (!module.shouldTransformFontInput(path)) {
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return null
        }
        val revision = module.fontPresentationRevision()
        synchronized(LEGACY_STRING_CACHE) {
            val cached = LEGACY_STRING_CACHE[source]
            if (cached != null && cached.revision == revision) {
                module.recordFontTransform(path, cached.matched, startedAtNanos, true, !cached.matched)
                return cached.value
            }
        }
        if (!module.hasFontNameCandidate(source, path)) {
            synchronized(LEGACY_STRING_CACHE) {
                LEGACY_STRING_CACHE[source] = CachedLegacySequence(revision, false, null)
                trimCache(LEGACY_STRING_CACHE)
            }
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return null
        }

        val runs = ArrayList<StyledRun>()
        val activeText = StringBuilder()
        val activeStyle = arrayOfNulls<Style>(1)
        StringDecomposer.iterateFormatted(source, Style.EMPTY) { _, style, codePoint ->
            val previous = activeStyle[0]
            if (previous != null && !previous.equals(style)) {
                runs.add(StyledRun(activeText.toString(), previous))
                activeText.setLength(0)
            }
            activeStyle[0] = style
            activeText.appendCodePoint(codePoint)
            true
        }
        if (activeText.isNotEmpty()) runs.add(StyledRun(activeText.toString(), activeStyle[0]!!))
        val component = componentOf(runs)
        val transformed: Component = module.replaceFontComponent(component, path, startedAtNanos, true)!!
        val matched = transformed !== component
        val sequence = if (matched) sequenceOf(transformed) else null
        if (sequence != null) rememberPresentedSequence(sequence, revision)
        synchronized(LEGACY_STRING_CACHE) {
            LEGACY_STRING_CACHE[source] = CachedLegacySequence(revision, matched, sequence)
            trimCache(LEGACY_STRING_CACHE)
        }
        return sequence
    }

    @JvmStatic
    fun transform(source: FormattedText?, path: Int): FormattedText? {
        if (source == null) return null
        val module = PlayerNamesModule.INSTANCE
        val startedAtNanos = module.beginFontTransformTiming(path)
        var component = if (source is Component) PlayerNameTextTransform.restorePresentedRoot(source) else null
        val candidateSource: FormattedText = component ?: source
        if (!module.shouldTransformFontInput(path)) {
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return candidateSource
        }
        val revision = module.fontPresentationRevision()
        synchronized(FORMATTED_TEXT_CACHE) {
            val cached = FORMATTED_TEXT_CACHE[source]
            if (cached != null && cached.revision == revision) {
                module.recordFontTransform(path, cached.changed, startedAtNanos, true, !cached.changed)
                return if (cached.changed) cached.value else source
            }
        }
        if (!module.hasFontNameCandidate(candidateSource)) {
            synchronized(FORMATTED_TEXT_CACHE) {
                FORMATTED_TEXT_CACHE[source] = CachedText(revision, candidateSource !== source,
                    if (candidateSource === source) null else candidateSource)
                trimCache(FORMATTED_TEXT_CACHE)
            }
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return candidateSource
        }
        if (component == null) component = componentOf(source)
        val transformed: Component = module.replaceFontComponent(component, path, startedAtNanos, true)!!
        val result: FormattedText = if (transformed === component) candidateSource else transformed
        synchronized(FORMATTED_TEXT_CACHE) {
            FORMATTED_TEXT_CACHE[source] = CachedText(revision, result !== source,
                if (result === source) null else result)
            trimCache(FORMATTED_TEXT_CACHE)
        }
        return result
    }

    @JvmStatic
    fun transform(source: FormattedCharSequence?, path: Int): FormattedCharSequence? {
        if (source == null) return null
        val module = PlayerNamesModule.INSTANCE
        val startedAtNanos = module.beginFontTransformTiming(path)
        val origin = sequenceOrigin(source)
        if (!module.shouldTransformFontInput(path)) {
            if (origin != null) {
                val original = origin.original.get()
                if (original != null) {
                    module.recordFontTransform(path, false, startedAtNanos, true, false)
                    return original
                }
            }
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return source
        }
        val revision = module.fontPresentationRevision()
        val presented = presentedSequence(source)
        if (presented != null && presented.revision == revision) {
            module.recordFontTransform(path, false, startedAtNanos, true, false)
            return source
        }
        if (origin != null && origin.revision == revision) {
            module.recordFontTransform(path, false, startedAtNanos, true, false)
            return source
        }
        var candidate = source
        if (presented != null || origin != null) {
            if (origin == null) {
                module.recordFontTransform(path, false, startedAtNanos, true, false)
                return source
            }
            val original = origin.original.get()
            if (original == null) {
                module.recordFontTransform(path, false, startedAtNanos, true, false)
                return source
            }
            candidate = original
        }
        synchronized(SEQUENCE_CACHE) {
            val cached = SEQUENCE_CACHE[candidate]
            if (cached != null && cached.revision == revision) {
                module.recordFontTransform(path, cached.changed, startedAtNanos, true, !cached.changed)
                return if (cached.changed) cached.value else candidate
            }
        }
        if (!module.hasFontNameCandidate(candidate)) {
            synchronized(SEQUENCE_CACHE) {
                SEQUENCE_CACHE[candidate] = CachedSequence(revision, false, null)
                trimCache(SEQUENCE_CACHE)
            }
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return candidate
        }
        val component = componentOf(candidate)
        val transformed: Component = module.replaceFontComponent(component, path, startedAtNanos, true)!!
        val result = if (transformed === component) candidate else sequenceOf(transformed)
        if (result !== candidate) rememberSequenceOrigin(result, candidate, revision)
        synchronized(SEQUENCE_CACHE) {
            SEQUENCE_CACHE[candidate] = CachedSequence(revision, result !== candidate,
                if (result === candidate) null else result)
            trimCache(SEQUENCE_CACHE)
        }
        return result
    }

    /** Marks line sequences whose source text was transformed before Minecraft wrapped it. */
    @JvmStatic
    fun markPresentedSequences(sequences: List<out FormattedCharSequence?>?, path: Int) {
        if (sequences == null || sequences.isEmpty()) return
        val module = PlayerNamesModule.INSTANCE
        if (!module.shouldTransformFontInput(path)) {
            val startedAtNanos = module.beginFontTransformTiming(path)
            module.recordFontTransform(path, false, startedAtNanos, false, true)
            return
        }
        val revision = module.fontPresentationRevision()
        synchronized(PRESENTED_SEQUENCES) {
            purgeStalePresentedSequences()
            for (sequence in sequences) {
                if (sequence == null) continue
                val startedAtNanos = module.beginFontTransformTiming(path)
                rememberPresentedSequence(sequence, revision)
                module.recordFontTransform(path, false, startedAtNanos, true, false)
            }
        }
    }

    private fun rememberPresentedSequence(sequence: FormattedCharSequence, revision: Long) {
        synchronized(PRESENTED_SEQUENCES) {
            purgeStalePresentedSequences()
            PRESENTED_SEQUENCES[SequenceReference(sequence, STALE_PRESENTED_SEQUENCES)] = PresentedSequence(revision)
            trimCache(PRESENTED_SEQUENCES)
        }
    }

    private fun presentedSequence(source: FormattedCharSequence): PresentedSequence? {
        synchronized(PRESENTED_SEQUENCES) {
            purgeStalePresentedSequences()
            return PRESENTED_SEQUENCES[SequenceReference(source, null)]
        }
    }

    private fun purgeStalePresentedSequences() {
        while (true) {
            val stale = STALE_PRESENTED_SEQUENCES.poll() as? SequenceReference ?: break
            PRESENTED_SEQUENCES.remove(stale)
        }
    }

    private fun componentOf(source: FormattedText): Component {
        val runs = ArrayList<StyledRun>()
        source.visit({ style, text ->
            if (text.isNotEmpty()) runs.add(StyledRun(text, style))
            Optional.empty<Void>()
        }, Style.EMPTY)
        return componentOf(runs)
    }

    private fun componentOf(source: FormattedCharSequence): Component {
        val runs = ArrayList<StyledRun>()
        val text = StringBuilder()
        val activeStyle = arrayOfNulls<Style>(1)
        source.accept { _, style, codePoint ->
            val previous = activeStyle[0]
            if (previous != null && !previous.equals(style)) {
                runs.add(StyledRun(text.toString(), previous))
                text.setLength(0)
            }
            activeStyle[0] = style
            text.appendCodePoint(codePoint)
            true
        }
        if (text.isNotEmpty()) runs.add(StyledRun(text.toString(), activeStyle[0]!!))
        return componentOf(runs)
    }

    private fun componentOf(runs: List<StyledRun>): Component {
        val component = Component.empty()
        for (run in runs) component.append(Component.literal(run.text).withStyle(run.style))
        return component
    }

    private fun sequenceOf(source: Component): FormattedCharSequence {
        val runs = ArrayList<FormattedCharSequence>()
        source.visit({ style, text ->
            if (text.isNotEmpty()) runs.add(FormattedCharSequence.forward(text, style))
            Optional.empty<Void>()
        }, Style.EMPTY)
        return FormattedCharSequence.composite(runs)
    }

    private fun sequenceOrigin(source: FormattedCharSequence): SequenceOrigin? {
        synchronized(SEQUENCE_ORIGINS) {
            purgeStaleSequenceOrigins()
            return SEQUENCE_ORIGINS[SequenceReference(source, null)]
        }
    }

    private fun rememberSequenceOrigin(
        transformed: FormattedCharSequence,
        original: FormattedCharSequence,
        revision: Long,
    ) {
        synchronized(SEQUENCE_ORIGINS) {
            purgeStaleSequenceOrigins()
            SEQUENCE_ORIGINS[SequenceReference(transformed, STALE_SEQUENCE_ORIGINS)] =
                SequenceOrigin(revision, WeakReference(original))
            trimCache(SEQUENCE_ORIGINS)
        }
    }

    private fun trimCache(cache: MutableMap<*, *>) {
        while (cache.size > MAX_CACHE_ENTRIES) {
            val iterator = cache.keys.iterator() as java.util.Iterator<*>
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun purgeStaleSequenceOrigins() {
        while (true) {
            val stale = STALE_SEQUENCE_ORIGINS.poll() as? SequenceReference ?: break
            SEQUENCE_ORIGINS.remove(stale)
        }
    }

    private data class StyledRun(val text: String, val style: Style)
    private data class CachedText(val revision: Long, val changed: Boolean, val value: FormattedText?)
    private data class CachedSequence(val revision: Long, val changed: Boolean, val value: FormattedCharSequence?)
    private data class CachedLegacySequence(val revision: Long, val matched: Boolean, val value: FormattedCharSequence?)
    private data class SequenceOrigin(val revision: Long, val original: WeakReference<FormattedCharSequence>)
    private data class PresentedSequence(val revision: Long)

    private class SequenceReference(
        referent: FormattedCharSequence,
        queue: ReferenceQueue<FormattedCharSequence>?,
    ) : WeakReference<FormattedCharSequence>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SequenceReference) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}
