package geiler.addons.client.presentation

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.contents.NbtContents
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.chat.contents.ScoreContents
import net.minecraft.network.chat.contents.SelectorContents
import net.minecraft.network.chat.contents.TranslatableContents
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Optional
import java.util.function.Function
import java.util.function.Predicate

/** Replaces profile usernames at token boundaries while preserving component styles and metadata. */
object PlayerNameTextTransform {
    private const val MAX_REMEMBERED_COMPONENTS = 4096
    private val STALE_COMPONENTS = ReferenceQueue<Component>()
    private val PRESENTATION_ORIGINALS: MutableMap<IdentityWeakReference, Component> = HashMap()

    @JvmStatic
    fun replace(source: Component?, playerName: String?, alias: String?): Component? {
        if (source == null || playerName == null || alias == null || isJavaBlank(alias)
            || playerName.equals(alias, ignoreCase = true)
        ) return source
        return replaceKnown(source) { text -> if (text.equals(playerName, ignoreCase = true)) alias else null }
    }

    /** Applies an optional alias and the profile's static or animated text style to username tokens. */
    @JvmStatic
    fun applyProfile(
        source: Component?,
        playerName: String?,
        entry: PlayerPresentationProfiles.Entry?,
        replaceName: Boolean,
        applyStyle: Boolean,
    ): Component? {
        if (source == null || playerName == null || entry == null) return source
        val nowNanos = if (applyStyle && entry.animation() != null) System.nanoTime() else 0L
        return rewriteForDisplay(source, { text ->
            if (!text.equals(playerName, ignoreCase = true)) null
            else profileReplacement(text, entry, replaceName, applyStyle)
        }, nowNanos)
    }

    // Kotlin module callers pass guarded, non-null components; keep their result type non-null while
    // the nullable @JvmStatic overloads retain the original Java null behavior.
    @JvmName("applyProfileForNonNullSource")
    @JvmSynthetic
    fun applyProfile(
        source: Component,
        playerName: String?,
        entry: PlayerPresentationProfiles.Entry?,
        replaceName: Boolean,
        applyStyle: Boolean,
    ): Component = applyProfile(source as Component?, playerName, entry, replaceName, applyStyle)!!

    /** Applies known profiles to username tokens in team-formatted components. */
    @JvmStatic
    fun applyKnownProfiles(
        source: Component?,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>?,
        replaceNames: Boolean,
        applyStyles: Boolean,
    ): Component? {
        if (source == null || entryForText == null) return source
        val nowNanos = if (applyStyles) System.nanoTime() else 0L
        return rewriteForDisplay(source, { text ->
            val entry = entryForText.apply(text)
            if (entry == null) null else profileReplacement(text, entry, replaceNames, applyStyles)
        }, nowNanos)
    }

    @JvmName("applyKnownProfilesForNonNullSource")
    @JvmSynthetic
    fun applyKnownProfiles(
        source: Component,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>?,
        replaceNames: Boolean,
        applyStyles: Boolean,
    ): Component = applyKnownProfiles(source as Component?, entryForText, replaceNames, applyStyles)!!

    /** Reports whether a component or exposed translation argument contains an animated profile username. */
    @JvmStatic
    fun hasAnimatedProfile(
        source: Component?,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>?,
    ): Boolean = hasMatchingName(source, entryForText, Predicate { entry ->
        entry.animation() != null && entry.hasNameStyle()
    })

    /** Reports whether a component contains a profile username token with any text presentation. */
    @JvmStatic
    fun hasNamePresentation(
        source: Component?,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>?,
    ): Boolean = hasMatchingName(source, entryForText, Predicate { entry ->
        entry.alias() != null || entry.hasNameStyle()
    })

    /** Restores components transformed earlier so settings or profile changes can be reapplied cleanly. */
    @JvmStatic
    fun restorePresentedLeaves(source: Component?): Component? {
        if (source == null) return null
        val original = rememberedOriginal(source)
        if (original != null) return original

        val rewrittenContents = restoreTranslatableArguments(source)
        val rewrittenSiblings = ArrayList<Component>(source.siblings.size)
        var childChanged = false
        for (sibling in source.siblings) {
            val rewritten = restorePresentedLeaves(sibling)
            rewrittenSiblings.add(rewritten)
            childChanged = childChanged || rewritten !== sibling
        }
        val restoredStyle = restoreStyle(source.style)
        val styleChanged = restoredStyle !== source.style
        if (rewrittenContents === source && !childChanged && !styleChanged) return source

        val result = if (rewrittenContents === source) source.plainCopy() else rewrittenContents as MutableComponent
        result.setStyle(restoredStyle)
        for (sibling in rewrittenSiblings) result.append(sibling)
        return result
    }

    @JvmName("restorePresentedLeavesForNonNullSource")
    @JvmSynthetic
    fun restorePresentedLeaves(source: Component): Component = restorePresentedLeaves(source as Component?)!!

    /** O(1) restoration for hot hooks that only receive a directly transformed root component. */
    @JvmStatic
    fun restorePresentedRoot(source: Component?): Component? {
        if (source == null) return null
        val original = rememberedOriginal(source)
        return original ?: source
    }

    @JvmName("restorePresentedRootForNonNullSource")
    @JvmSynthetic
    fun restorePresentedRoot(source: Component): Component = restorePresentedRoot(source as Component?)!!

    /** Rewrites username tokens across adjacent plain-text leaves without flattening their styles. */
    @JvmStatic
    fun replaceKnown(source: Component?, aliasForText: Function<String, String?>?): Component? {
        if (source == null || aliasForText == null) return source
        return rewriteForDisplay(source, { text ->
            val alias = aliasForText.apply(text)
            if (alias == null || isJavaBlank(alias) || text.equals(alias, ignoreCase = true)) null
            else Replacement(alias, null)
        }, 0L)
    }

    /**
     * Replaces aliases in a legacy-formatted String while retaining formatting pairs at their source
     * positions. Minecraft's String font overloads do not carry component styles, so this path only
     * changes the visible alias text; component and formatted-sequence paths retain rich styles.
     */
    @JvmStatic
    fun replaceKnownLegacyText(source: String?, aliasForText: Function<String, String?>?): String? {
        if (source == null || source.isEmpty() || aliasForText == null) return source
        val visible = StringBuilder(source.length)
        val points = ArrayList<VisibleCodePoint>()
        var raw = 0
        while (raw < source.length) {
            val formattingLength = legacyFormattingLength(source, raw)
            if (formattingLength > 0) {
                raw += formattingLength
                continue
            }
            val codePoint = source.codePointAt(raw)
            val end = raw + Character.charCount(codePoint)
            points.add(VisibleCodePoint(raw, end))
            visible.appendCodePoint(codePoint)
            raw = end
        }
        val text = visible.toString()
        if (points.isEmpty()) return source
        val result = StringBuilder(source.length)
        var copiedRaw = 0
        var offset = 0
        while (offset < text.length) {
            val start = nextTokenStart(text, offset)
            if (start < 0) break
            val end = tokenEnd(text, start)
            val token = text.substring(start, end)
            val alias = aliasForText.apply(token)
            if (alias != null && !isJavaBlank(alias) && !token.equals(alias, ignoreCase = true)) {
                val firstPoint = codePointIndexAt(text, start)
                val afterLastPoint = codePointIndexAt(text, end)
                val sourcePointCount = afterLastPoint - firstPoint
                val aliasOffsets = codePointOffsets(alias, 0, alias.length)
                if (sourcePointCount > 0 && aliasOffsets.isNotEmpty()) {
                    var rawCursor = points[firstPoint].start
                    result.append(source, copiedRaw, rawCursor)
                    for (index in 0 until sourcePointCount) {
                        val point = points[firstPoint + index]
                        if (rawCursor < point.start) result.append(source, rawCursor, point.start)
                        val aliasStart = (index.toLong() * aliasOffsets.size / sourcePointCount).toInt()
                        val aliasEnd = ((index + 1L) * aliasOffsets.size / sourcePointCount).toInt()
                        if (aliasEnd > aliasStart) {
                            val from = aliasOffsets[aliasStart]
                            val to = if (aliasEnd < aliasOffsets.size) aliasOffsets[aliasEnd] else alias.length
                            result.append(alias, from, to)
                        }
                        rawCursor = point.end
                    }
                    copiedRaw = rawCursor
                }
            }
            offset = end
        }
        if (copiedRaw == 0) return source
        result.append(source, copiedRaw, source.length)
        return result.toString()
    }

    private fun codePointIndexAt(text: String, offset: Int): Int = text.codePointCount(0, offset)

    private fun isJavaBlank(text: String): Boolean = text.codePoints().allMatch { Character.isWhitespace(it) }

    private fun legacyFormattingLength(text: String, offset: Int): Int {
        if (offset + 1 >= text.length || text[offset] != '\u00a7') return 0
        val code = Character.toLowerCase(text[offset + 1])
        if (code == 'x') {
            var cursor = offset + 2
            for (index in 0 until 6) {
                if (cursor + 1 >= text.length || text[cursor] != '\u00a7'
                    || Character.digit(text[cursor + 1], 16) < 0
                ) return 0
                cursor += 2
            }
            return cursor - offset
        }
        return if ("0123456789abcdefklmnor".indexOf(code) >= 0) 2 else 0
    }

    /** Cheap candidate check before allocating the rich-text rewrite structures. Names are normalized lowercase keys. */
    @JvmStatic
    fun mayContainAnyName(source: Component?, candidateNames: Set<String>?): Boolean {
        if (source == null || candidateNames == null || candidateNames.isEmpty()) return false
        return mayContainVisibleName(source, candidateNames)
    }

    @JvmStatic
    fun mayContainAnyName(source: net.minecraft.network.chat.FormattedText?, candidateNames: Set<String>?): Boolean {
        if (source is Component) return mayContainAnyName(source, candidateNames)
        if (source == null || candidateNames == null || candidateNames.isEmpty()) return false
        val visible = StringBuilder()
        val hoverComponents = ArrayList<Component>()
        val queuedHovers = IdentityHashMap<Component, Boolean>()
        try {
            source.visit({ style, text ->
                visible.append(text)
                collectHoverComponents(style, hoverComponents, queuedHovers)
                Optional.empty<Void>()
            }, Style.EMPTY)
        } catch (_: RuntimeException) {
            return true
        }
        return containsCandidateToken(visible, candidateNames)
            || mayContainHoverName(hoverComponents, candidateNames, queuedHovers)
    }

    @JvmStatic
    fun mayContainAnyName(source: net.minecraft.util.FormattedCharSequence?, candidateNames: Set<String>?): Boolean {
        if (source == null || candidateNames == null || candidateNames.isEmpty()) return false
        val visible = StringBuilder()
        val hoverComponents = ArrayList<Component>()
        val queuedHovers = IdentityHashMap<Component, Boolean>()
        val previousStyle = arrayOfNulls<Style>(1)
        source.accept { _, style, codePoint ->
            visible.appendCodePoint(codePoint)
            val previous = previousStyle[0]
            if (previous == null || !previous.equals(style)) {
                collectHoverComponents(style, hoverComponents, queuedHovers)
                previousStyle[0] = style
            }
            true
        }
        return containsCandidateToken(visible, candidateNames)
            || mayContainHoverName(hoverComponents, candidateNames, queuedHovers)
    }

    /** Checks visible legacy-formatted text without first constructing styled runs or a Component tree. */
    @JvmStatic
    fun mayContainAnyLegacyName(source: String?, candidateNames: Set<String>?): Boolean {
        if (source == null || source.isEmpty() || candidateNames == null || candidateNames.isEmpty()) return false
        val visible = StringBuilder(source.length)
        var raw = 0
        while (raw < source.length) {
            val formattingLength = legacyFormattingLength(source, raw)
            if (formattingLength > 0) {
                raw += formattingLength
                continue
            }
            val codePoint = source.codePointAt(raw)
            visible.appendCodePoint(codePoint)
            raw += Character.charCount(codePoint)
        }
        return containsCandidateToken(visible, candidateNames)
    }

    private fun mayContainVisibleName(source: Component, candidateNames: Set<String>): Boolean {
        val pending = ArrayList<Component>()
        val queuedHovers = IdentityHashMap<Component, Boolean>()
        pending.add(source)
        queuedHovers[source] = true
        for (index in pending.indices) {
            val visible = StringBuilder()
            try {
                pending[index].visit({ style, text ->
                    visible.append(text)
                    collectHoverComponents(style, pending, queuedHovers)
                    Optional.empty<Void>()
                }, Style.EMPTY)
            } catch (_: RuntimeException) {
                // Fall through to the established transformer, which preserves unresolved server-side contents.
                return true
            }
            if (containsCandidateToken(visible, candidateNames)) return true
        }
        return false
    }

    private fun mayContainHoverName(
        pending: MutableList<Component>,
        candidateNames: Set<String>,
        queuedHovers: IdentityHashMap<Component, Boolean>,
    ): Boolean {
        for (index in pending.indices) {
            val visible = StringBuilder()
            try {
                pending[index].visit({ style, text ->
                    visible.append(text)
                    collectHoverComponents(style, pending, queuedHovers)
                    Optional.empty<Void>()
                }, Style.EMPTY)
            } catch (_: RuntimeException) {
                return true
            }
            if (containsCandidateToken(visible, candidateNames)) return true
        }
        return false
    }

    private fun collectHoverComponents(
        style: Style,
        pending: MutableList<Component>,
        queuedHovers: IdentityHashMap<Component, Boolean>,
    ) {
        val event = style.hoverEvent
        if (event is HoverEvent.ShowText) {
            addHoverComponent(event.value(), pending, queuedHovers)
        } else if (event is HoverEvent.ShowEntity) {
            event.entity().name.ifPresent { name -> addHoverComponent(name, pending, queuedHovers) }
        }
    }

    private fun addHoverComponent(
        component: Component,
        pending: MutableList<Component>,
        queuedHovers: IdentityHashMap<Component, Boolean>,
    ) {
        if (queuedHovers.put(component, true) == null) pending.add(component)
    }

    private fun containsCandidateToken(text: CharSequence, candidateNames: Set<String>): Boolean {
        var offset = 0
        while (offset < text.length) {
            val start = nextTokenStart(text, offset)
            if (start < 0) return false
            val end = tokenEnd(text, start)
            if (candidateNames.contains(text.subSequence(start, end).toString().lowercase(Locale.ROOT))) return true
            offset = end
        }
        return false
    }

    private fun profileReplacement(
        original: String,
        entry: PlayerPresentationProfiles.Entry,
        replaceName: Boolean,
        applyStyle: Boolean,
    ): Replacement? {
        var alias = if (replaceName) entry.alias() else null
        if (alias != null && (isJavaBlank(alias) || original.equals(alias, ignoreCase = true))) alias = null
        val style = if (applyStyle && entry.hasNameStyle()) entry else null
        return if (alias == null && style == null) null else Replacement(alias ?: original, style)
    }

    private fun rewrite(
        source: Component,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val root = unwrapped(source)
        val runs = collectPlainRuns(root)
        val replacementByLeaf = IdentityHashMap<Component, ArrayDeque<Component>>()
        for (run in runs) rewritePlainRun(run, replacementForText, nowNanos, replacementByLeaf)
        return rebuild(root, replacementByLeaf, replacementForText, nowNanos)
    }

    /**
     * Rewrites component structure and currently visible literal text. The visible-text pass catches
     * localized templates and dynamic components that another supported path has already materialized
     * into ordinary text; it deliberately does not resolve server-context score, selector, or NBT data.
     */
    private fun rewriteForDisplay(
        source: Component,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val root = unwrapped(source)
        if (containsUnresolvedServerContent(root)) {
            return rewriteAroundUnresolvedServerContent(root, replacementForText, nowNanos)
        }
        val structured = rewrite(root, replacementForText, nowNanos)
        val visible = rewriteResolvedVisibleText(root, replacementForText, nowNanos)
        if (visible !== root && (structured === root || visible.string != structured.string)) {
            rememberOriginal(visible, root)
            return visible
        }
        return structured
    }

    /**
     * Keeps server-context contents opaque while still presenting independent visible branches. A
     * selector's source, score owner, or NBT path is lookup input rather than rendered identity; the
     * client cannot resolve those values. Safe siblings and translatable branches without such input
     * can still be localized and transformed for display.
     */
    private fun rewriteAroundUnresolvedServerContent(
        source: Component,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val contentOnly = MutableComponent.create(source.contents).withStyle(source.style)
        val contents = if (containsUnresolvedServerContent(contentOnly)) {
            rewrite(contentOnly, replacementForText, nowNanos)
        } else {
            rewriteForDisplay(contentOnly, replacementForText, nowNanos)
        }

        val siblings = ArrayList<Component>(source.siblings.size)
        var siblingChanged = false
        for (sibling in source.siblings) {
            val rewritten = rewriteForDisplay(sibling, replacementForText, nowNanos)
            siblings.add(rewritten)
            siblingChanged = siblingChanged || rewritten !== sibling
        }
        if (contents === contentOnly && !siblingChanged) return source

        val result = contents as MutableComponent
        for (sibling in siblings) result.append(sibling)
        rememberOriginal(result, source)
        return result
    }

    /** Server-only component contents cannot be resolved accurately from the client render hook. */
    private fun containsUnresolvedServerContent(source: Component): Boolean {
        val contents = source.contents
        if (contents is ScoreContents || contents is SelectorContents || contents is NbtContents) return true
        if (contents is TranslatableContents) {
            for (argument in contents.args) {
                if (argument is Component && containsUnresolvedServerContent(unwrapped(argument))) return true
            }
        }
        for (sibling in source.siblings) {
            if (containsUnresolvedServerContent(unwrapped(sibling))) return true
        }
        return false
    }

    /** Flattens only a transformed display copy, retaining the resolved style on every text run. */
    private fun rewriteResolvedVisibleText(
        source: Component,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val leaves = ArrayList<VisibleLeaf>()
        try {
            source.visit({ style, text ->
                if (text.isNotEmpty()) leaves.add(VisibleLeaf(text, style))
                Optional.empty<Void>()
            }, Style.EMPTY)
        } catch (_: RuntimeException) {
            // An unresolved component is left for its normal Minecraft resolution path.
            return source
        }
        if (leaves.isEmpty()) return source

        val positioned = positionVisibleLeaves(leaves)
        val text = joinVisibleText(positioned)
        val output = ArrayList<StyledText>()
        var copiedThrough = 0
        var changed = false
        var offset = 0
        while (offset < text.length) {
            val start = nextTokenStart(text, offset)
            if (start < 0) break
            val end = tokenEnd(text, start)
            val replacement = replacementForText.apply(text.substring(start, end))
            if (replacement != null) {
                appendVisibleOriginalRange(positioned, output, text, copiedThrough, start,
                    replacementForText, nowNanos)
                appendVisibleReplacement(positioned, output, text, start, end, replacement,
                    replacementForText, nowNanos)
                copiedThrough = end
                changed = true
            }
            offset = end
        }
        if (!changed) return source
        appendVisibleOriginalRange(positioned, output, text, copiedThrough, text.length,
            replacementForText, nowNanos)

        val result = Component.empty()
        for (piece in output) {
            if (piece.text.isNotEmpty()) result.append(Component.literal(piece.text).withStyle(piece.style))
        }
        rememberOriginal(result, source)
        return result
    }

    private fun positionVisibleLeaves(leaves: List<VisibleLeaf>): List<VisibleLeaf> {
        val positioned = ArrayList<VisibleLeaf>(leaves.size)
        var offset = 0
        for (leaf in leaves) {
            val end = offset + leaf.text.length
            positioned.add(VisibleLeaf(leaf.text, leaf.style, offset, end))
            offset = end
        }
        return positioned
    }

    private fun joinVisibleText(leaves: List<VisibleLeaf>): String {
        val joined = StringBuilder()
        for (leaf in leaves) joined.append(leaf.text)
        return joined.toString()
    }

    private fun appendVisibleOriginalRange(
        leaves: List<VisibleLeaf>,
        output: MutableList<StyledText>,
        joinedText: String,
        start: Int,
        end: Int,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ) {
        if (start >= end) return
        for (leaf in leaves) {
            val from = maxOf(start, leaf.start)
            val to = minOf(end, leaf.end)
            if (from < to) {
                appendStyled(output, joinedText.substring(from, to),
                    rewriteStyle(leaf.style, replacementForText, nowNanos))
            }
        }
    }

    private fun appendVisibleReplacement(
        leaves: List<VisibleLeaf>,
        output: MutableList<StyledText>,
        sourceText: String,
        start: Int,
        end: Int,
        replacement: Replacement,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ) {
        val sourceOffsets = codePointOffsets(sourceText, start, end)
        val aliasOffsets = codePointOffsets(replacement.text, 0, replacement.text.length)
        if (sourceOffsets.isEmpty() || aliasOffsets.isEmpty()) return
        var leafIndex = 0
        val profile = replacement.style
        val animation = profile?.animation()
        val phase = if (animation == null) 0.0 else cyclePhase(nowNanos, animation.cyclesPerSecond)
        for (index in aliasOffsets.indices) {
            val sourceIndex = minOf(sourceOffsets.size - 1,
                (index.toLong() * sourceOffsets.size / aliasOffsets.size).toInt())
            val sourceOffset = sourceOffsets[sourceIndex]
            while (leafIndex + 1 < leaves.size && leaves[leafIndex].end <= sourceOffset) leafIndex++
            val sourceLeaf = leaves[leafIndex]
            val sourceStyle = rewriteStyle(sourceLeaf.style, replacementForText, nowNanos)
            val endOffset = nextCodePointOffset(aliasOffsets, index, replacement.text.length)
            val outputStyle = styleForReplacement(sourceStyle, profile, index, aliasOffsets.size, phase, nowNanos)
            appendStyled(output, replacement.text.substring(aliasOffsets[index], endOffset), outputStyle)
        }
    }

    private fun appendStyled(output: MutableList<StyledText>, text: String, style: Style) {
        if (text.isEmpty()) return
        val last = output.size - 1
        if (last >= 0 && output[last].style == style) {
            val previous = output[last]
            output[last] = StyledText(previous.text + text, style)
        } else {
            output.add(StyledText(text, style))
        }
    }

    private fun rewriteTranslatableArguments(
        source: Component,
        translated: TranslatableContents,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val sourceArguments = translated.args
        if (sourceArguments.isEmpty()) return source
        val rewrittenArguments = sourceArguments.clone()
        var changed = false
        for (index in sourceArguments.indices) {
            val argument = sourceArguments[index]
            if (argument is Component) {
                val rewritten = rewriteForDisplay(argument, replacementForText, nowNanos)
                if (rewritten !== argument) {
                    rewrittenArguments[index] = rewritten
                    changed = true
                }
            } else if (argument is String) {
                val stringArgument = Component.literal(argument).withStyle(source.style)
                val rewritten = rewriteForDisplay(stringArgument, replacementForText, nowNanos)
                if (rewritten !== stringArgument) {
                    rewrittenArguments[index] = rewritten
                    changed = true
                }
            }
        }
        if (!changed) return source

        val result = MutableComponent.create(
            TranslatableContents(translated.key, translated.fallback, rewrittenArguments),
        )
        result.setStyle(source.style)
        rememberOriginal(result, source)
        return result
    }

    private fun restoreTranslatableArguments(source: Component): Component {
        val contents = source.contents
        if (contents !is TranslatableContents) return source
        val sourceArguments = contents.args
        val restoredArguments = sourceArguments.clone()
        var changed = false
        for (index in sourceArguments.indices) {
            val argument = sourceArguments[index]
            if (argument !is Component) continue
            val restored = restorePresentedLeaves(argument)
            if (restored !== argument) {
                restoredArguments[index] = restored
                changed = true
            }
        }
        if (!changed) return source
        val result = MutableComponent.create(
            TranslatableContents(contents.key, contents.fallback, restoredArguments),
        )
        result.setStyle(source.style)
        return result
    }

    private fun restoreStyle(style: Style): Style {
        val event = style.hoverEvent
        if (event is HoverEvent.ShowText) {
            val restored = restorePresentedLeaves(event.value())
            if (restored !== event.value()) return style.withHoverEvent(HoverEvent.ShowText(restored))
        } else if (event is HoverEvent.ShowEntity) {
            val info = event.entity()
            val name = info.name.orElse(null)
            if (name != null) {
                val restored = restorePresentedLeaves(name)
                if (restored !== name) {
                    return style.withHoverEvent(HoverEvent.ShowEntity(
                        HoverEvent.EntityTooltipInfo(info.type, info.uuid, restored),
                    ))
                }
            }
        }
        return style
    }

    private fun rewriteStyle(
        style: Style,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Style {
        val event = style.hoverEvent
        if (event is HoverEvent.ShowText) {
            val rewritten = rewriteForDisplay(event.value(), replacementForText, nowNanos)
            if (rewritten !== event.value()) return style.withHoverEvent(HoverEvent.ShowText(rewritten))
        } else if (event is HoverEvent.ShowEntity) {
            val info = event.entity()
            val name = info.name.orElse(null)
            if (name != null) {
                val rewritten = rewriteForDisplay(name, replacementForText, nowNanos)
                if (rewritten !== name) {
                    return style.withHoverEvent(HoverEvent.ShowEntity(
                        HoverEvent.EntityTooltipInfo(info.type, info.uuid, rewritten),
                    ))
                }
            }
        }
        return style
    }

    private fun hasMatchingName(
        source: Component?,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>?,
        predicate: Predicate<PlayerPresentationProfiles.Entry>?,
    ): Boolean {
        if (source == null || entryForText == null || predicate == null) return false
        val root = unwrapped(source)
        for (run in collectPlainRuns(root)) {
            if (containsMatchingToken(joinText(run), entryForText, predicate)) return true
        }
        return hasMatchingVisibleText(root, entryForText, predicate)
            || hasMatchingTranslationArgument(root, entryForText, predicate)
            || hasMatchingHoverText(root, entryForText, predicate)
    }

    private fun hasMatchingTranslationArgument(
        source: Component,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>,
        predicate: Predicate<PlayerPresentationProfiles.Entry>,
    ): Boolean {
        val contents = source.contents
        if (contents is TranslatableContents) {
            for (argument in contents.args) {
                if (argument is Component && hasMatchingName(argument, entryForText, predicate)) return true
                if (argument is String && containsMatchingToken(argument, entryForText, predicate)) return true
            }
        }
        for (sibling in source.siblings) {
            if (hasMatchingTranslationArgument(unwrapped(sibling), entryForText, predicate)) return true
        }
        return false
    }

    private fun hasMatchingVisibleText(
        source: Component,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>,
        predicate: Predicate<PlayerPresentationProfiles.Entry>,
    ): Boolean {
        val contentOnly = MutableComponent.create(source.contents).withStyle(source.style)
        if (!containsUnresolvedServerContent(contentOnly)) {
            val leaves = ArrayList<VisibleLeaf>()
            try {
                contentOnly.visit({ style, text ->
                    if (text.isNotEmpty()) leaves.add(VisibleLeaf(text, style))
                    Optional.empty<Void>()
                }, Style.EMPTY)
            } catch (_: RuntimeException) {
                leaves.clear()
            }
            if (containsMatchingToken(joinVisibleText(leaves), entryForText, predicate)) return true
        }
        for (sibling in source.siblings) {
            if (hasMatchingVisibleText(unwrapped(sibling), entryForText, predicate)) return true
        }
        return false
    }

    private fun hasMatchingHoverText(
        source: Component,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>,
        predicate: Predicate<PlayerPresentationProfiles.Entry>,
    ): Boolean {
        val event = source.style.hoverEvent
        if (event is HoverEvent.ShowText && hasMatchingName(event.value(), entryForText, predicate)) return true
        if (event is HoverEvent.ShowEntity) {
            val name = event.entity().name.orElse(null)
            if (name != null && hasMatchingName(name, entryForText, predicate)) return true
        }
        for (sibling in source.siblings) {
            if (hasMatchingHoverText(unwrapped(sibling), entryForText, predicate)) return true
        }
        val contents = source.contents
        if (contents is TranslatableContents) {
            for (argument in contents.args) {
                if (argument is Component && hasMatchingHoverText(unwrapped(argument), entryForText, predicate)) return true
            }
        }
        return false
    }

    private fun collectPlainRuns(source: Component): List<List<PlainLeaf>> {
        val runs = ArrayList<List<PlainLeaf>>()
        val active = ArrayList<PlainLeaf>()
        collectPlainRuns(source, active, runs)
        if (active.isNotEmpty()) runs.add(java.util.List.copyOf(active))
        return runs
    }

    private fun collectPlainRuns(
        component: Component,
        active: MutableList<PlainLeaf>,
        runs: MutableList<List<PlainLeaf>>,
    ) {
        val source = unwrapped(component)
        val contents = source.contents
        if (contents is PlainTextContents) {
            active.add(PlainLeaf(source, contents.text()))
        } else if (active.isNotEmpty()) {
            runs.add(java.util.List.copyOf(active))
            active.clear()
        }
        for (sibling in source.siblings) collectPlainRuns(sibling, active, runs)
    }

    private fun rewritePlainRun(
        runInput: List<PlainLeaf>,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
        replacementsByLeaf: IdentityHashMap<Component, ArrayDeque<Component>>,
    ) {
        val run = withOffsets(runInput)
        val text = joinText(run)
        val pieces = ArrayList<MutableList<StyledText>>(run.size)
        val changed = BooleanArray(run.size)
        for (index in run.indices) pieces.add(ArrayList())

        var copiedThrough = 0
        var offset = 0
        while (offset < text.length) {
            val start = nextTokenStart(text, offset)
            if (start < 0) break
            val end = tokenEnd(text, start)
            val replacement = replacementForText.apply(text.substring(start, end))
            if (replacement != null) {
                appendOriginalRange(run, pieces, text, copiedThrough, start)
                for (index in run.indices) {
                    val leaf = run[index]
                    changed[index] = changed[index] || (leaf.end > start && leaf.start < end)
                }
                appendReplacement(run, pieces, text, start, end, replacement, nowNanos)
                copiedThrough = end
            }
            offset = end
        }
        appendOriginalRange(run, pieces, text, copiedThrough, text.length)

        for (index in run.indices) {
            val leaf = run[index]
            val output = if (changed[index]) renderLeaf(leaf, pieces[index]) else leaf.component
            val queue = replacementsByLeaf.computeIfAbsent(leaf.component) { ArrayDeque() }
            queue.addLast(output)
        }
    }

    private fun appendOriginalRange(
        run: List<PlainLeaf>,
        pieces: List<MutableList<StyledText>>,
        joinedText: String,
        start: Int,
        end: Int,
    ) {
        if (start >= end) return
        for (index in run.indices) {
            val leaf = run[index]
            val from = maxOf(start, leaf.start)
            val to = minOf(end, leaf.end)
            if (from < to) pieces[index].add(StyledText(joinedText.substring(from, to), leaf.component.style))
        }
    }

    private fun appendReplacement(
        run: List<PlainLeaf>,
        pieces: List<MutableList<StyledText>>,
        sourceText: String,
        start: Int,
        end: Int,
        replacement: Replacement,
        nowNanos: Long,
    ) {
        val sourceCodePointOffsets = codePointOffsets(sourceText, start, end)
        val replacementCodePointOffsets = codePointOffsets(replacement.text, 0, replacement.text.length)
        if (replacementCodePointOffsets.isEmpty() || sourceCodePointOffsets.isEmpty()) return
        var leafIndex = 0
        val profile = replacement.style
        val animation = profile?.animation()
        val phase = if (animation == null) 0.0 else cyclePhase(nowNanos, animation.cyclesPerSecond)
        for (index in replacementCodePointOffsets.indices) {
            val replacementOffset = replacementCodePointOffsets[index]
            val replacementEnd = nextCodePointOffset(replacementCodePointOffsets, index, replacement.text.length)
            val sourceIndex = minOf(sourceCodePointOffsets.size - 1,
                (index.toLong() * sourceCodePointOffsets.size / replacementCodePointOffsets.size).toInt())
            val sourceOffset = sourceCodePointOffsets[sourceIndex]
            while (leafIndex + 1 < run.size && run[leafIndex].end <= sourceOffset) leafIndex++
            val sourceLeaf = run[leafIndex]
            val outputStyle = styleForReplacement(sourceLeaf.component.style, profile, index,
                replacementCodePointOffsets.size, phase, nowNanos)
            pieces[leafIndex].add(StyledText(
                replacement.text.substring(replacementOffset, replacementEnd), outputStyle,
            ))
        }
    }

    private fun styleForReplacement(
        sourceStyle: Style,
        entry: PlayerPresentationProfiles.Entry?,
        characterIndex: Int,
        characterCount: Int,
        phase: Double,
        nowNanos: Long,
    ): Style {
        if (entry == null) return sourceStyle
        val color = entry.color()
        if (color != null) return sourceStyle.withColor(TextColor.fromRgb(color))
        val gradient = entry.gradient()
        if (gradient.isEmpty()) return sourceStyle
        val animation = entry.animation()
        return PlayerNameGradientRenderState.gradientStyle(sourceStyle, gradient, characterIndex,
            characterCount, animation != null,
            if (animation == null) 0.0f else animation.cyclesPerSecond, phase, nowNanos)
    }

    private fun codePointOffsets(text: String, start: Int, end: Int): IntArray {
        val count = text.codePointCount(start, end)
        val offsets = IntArray(count)
        var position = start
        for (index in 0 until count) {
            offsets[index] = position
            position += Character.charCount(text.codePointAt(position))
        }
        return offsets
    }

    private fun nextCodePointOffset(offsets: IntArray, index: Int, end: Int): Int =
        if (index + 1 < offsets.size) offsets[index + 1] else end

    private fun renderLeaf(source: PlainLeaf, pieces: List<StyledText>): Component {
        val result = Component.empty().withStyle(source.component.style)
        val text = StringBuilder()
        var style: Style? = null
        for (piece in pieces) {
            if (piece.text.isEmpty()) continue
            if (style != null && style != piece.style) {
                result.append(Component.literal(text.toString()).withStyle(style))
                text.setLength(0)
            }
            text.append(piece.text)
            style = piece.style
        }
        if (style != null) result.append(Component.literal(text.toString()).withStyle(style))
        rememberOriginal(result, source.component)
        return result
    }

    private fun rebuild(
        component: Component,
        replacementsByLeaf: IdentityHashMap<Component, ArrayDeque<Component>>,
        replacementForText: Function<String, Replacement?>,
        nowNanos: Long,
    ): Component {
        val source = unwrapped(component)
        val contents = source.contents
        val rewrittenContents = if (contents is PlainTextContents) {
            val replacements = replacementsByLeaf[source]
            if (replacements == null || replacements.isEmpty()) source else replacements.removeFirst()
        } else if (contents is TranslatableContents) {
            rewriteTranslatableArguments(source, contents, replacementForText, nowNanos)
        } else {
            source
        }

        val rewrittenSiblings = ArrayList<Component>(source.siblings.size)
        var childChanged = false
        for (sibling in source.siblings) {
            val rewritten = rebuild(sibling, replacementsByLeaf, replacementForText, nowNanos)
            rewrittenSiblings.add(rewritten)
            childChanged = childChanged || rewritten !== sibling
        }
        val rewrittenStyle = rewriteStyle(source.style, replacementForText, nowNanos)
        val styleChanged = rewrittenStyle !== source.style
        if (rewrittenContents === source && !childChanged && !styleChanged) return source

        val result = if (rewrittenContents === source) source.plainCopy() else rewrittenContents as MutableComponent
        result.setStyle(rewrittenStyle)
        for (sibling in rewrittenSiblings) result.append(sibling)
        rememberOriginal(result, source)
        return result
    }

    private fun unwrapped(source: Component): Component = rememberedOriginal(source) ?: source

    private fun joinText(run: List<PlainLeaf>): String {
        val joined = StringBuilder()
        for (leaf in run) joined.append(leaf.text)
        return joined.toString()
    }

    private fun withOffsets(run: List<PlainLeaf>): List<PlainLeaf> {
        val positioned = ArrayList<PlainLeaf>(run.size)
        var offset = 0
        for (leaf in run) {
            val end = offset + leaf.text.length
            positioned.add(PlainLeaf(leaf.component, leaf.text, offset, end))
            offset = end
        }
        return positioned
    }

    private fun containsMatchingToken(
        text: String,
        entryForText: Function<String, PlayerPresentationProfiles.Entry?>,
        predicate: Predicate<PlayerPresentationProfiles.Entry>,
    ): Boolean {
        var offset = 0
        while (offset < text.length) {
            val start = nextTokenStart(text, offset)
            if (start < 0) return false
            val end = tokenEnd(text, start)
            val entry = entryForText.apply(text.substring(start, end))
            if (entry != null && predicate.test(entry)) return true
            offset = end
        }
        return false
    }

    private fun nextTokenStart(text: CharSequence, from: Int): Int {
        var offset = from
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (isTokenCharacter(codePoint)) return offset
            offset += Character.charCount(codePoint)
        }
        return -1
    }

    private fun tokenEnd(text: CharSequence, start: Int): Int {
        var offset = start
        while (offset < text.length) {
            val codePoint = Character.codePointAt(text, offset)
            if (!isTokenCharacter(codePoint)) break
            offset += Character.charCount(codePoint)
        }
        return offset
    }

    private fun isTokenCharacter(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isLetterOrDigit(codePoint) || type == Character.NON_SPACING_MARK.toInt()
            || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()
            || codePoint == '_'.code || codePoint == '\u00a7'.code
    }

    private fun rememberOriginal(transformed: Component, original: Component) {
        synchronized(PRESENTATION_ORIGINALS) {
            purgeStaleComponents()
            PRESENTATION_ORIGINALS[IdentityWeakReference(transformed, STALE_COMPONENTS)] = original
            while (PRESENTATION_ORIGINALS.size > MAX_REMEMBERED_COMPONENTS) {
                val iterator = PRESENTATION_ORIGINALS.keys.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun rememberedOriginal(transformed: Component): Component? {
        synchronized(PRESENTATION_ORIGINALS) {
            purgeStaleComponents()
            return PRESENTATION_ORIGINALS[IdentityWeakReference(transformed, null)]
        }
    }

    private fun purgeStaleComponents() {
        while (true) {
            val stale = STALE_COMPONENTS.poll() as? IdentityWeakReference ?: break
            PRESENTATION_ORIGINALS.remove(stale)
        }
    }

    private fun cyclePhase(nowNanos: Long, cyclesPerSecond: Float): Double {
        val cycle = nowNanos / 1_000_000_000.0 * cyclesPerSecond
        return cycle - Math.floor(cycle)
    }

    private data class PlainLeaf(val component: Component, val text: String, val start: Int = 0, val end: Int = 0)
    private data class VisibleLeaf(val text: String, val style: Style, val start: Int = 0, val end: Int = 0)
    private data class StyledText(val text: String, val style: Style)
    private data class Replacement(val text: String, val style: PlayerPresentationProfiles.Entry?)
    private data class VisibleCodePoint(val start: Int, val end: Int)

    private class IdentityWeakReference(
        referent: Component,
        queue: ReferenceQueue<Component>?,
    ) : WeakReference<Component>(referent, queue) {
        private val identityHash = System.identityHashCode(referent)

        override fun hashCode(): Int = identityHash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityWeakReference) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}
