package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModulePreview
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.presentation.PlayerNameGradientRenderState
import geiler.addons.client.presentation.PlayerNameTextTransform
import geiler.addons.client.presentation.PlayerPresentationProfileStore
import geiler.addons.client.presentation.PlayerPresentationProfiles
import geiler.addons.client.presentation.WeakIdentityCache
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.util.FormattedCharSequence
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.WeakHashMap

/** Remote name profiles plus local-only presentation controls for the current player. */
class PlayerNamesModule private constructor(settings: Settings) : Module(
    "Player Names",
    "Applies hosted name profiles and local alias and style settings for your own name.",
    Category.VISUAL,
    false,
    *settings.allSettings.toTypedArray(),
), ModulePreview {
    private val replaceNames = settings.replaceNames
    private val applyNameStyles = settings.applyNameStyles
    private val ownAlias = settings.ownAlias
    private val ownSolidColorEnabled = settings.ownSolidColorEnabled
    private val ownSolidColor = settings.ownSolidColor
    private val ownGradientEnabled = settings.ownGradientEnabled
    private val ownGradientStart = settings.ownGradientStart
    private val ownGradientEnd = settings.ownGradientEnd
    private val ownGradientMode = settings.ownGradientMode
    private val ownGradientStops = settings.ownGradientStops
    private val ownGradientStopColors = settings.ownGradientStopColors
    private val ownGradientAnimated = settings.ownGradientAnimated
    private val ownGradientSpeed = settings.ownGradientSpeed

    private val transformedComponents = WeakIdentityCache<Component, CachedComponent>(MAX_TRANSFORM_CACHE_ENTRIES)
    private val transformedStrings = WeakHashMap<String, CachedString>()
    private val transformCalls = LongArray(TRANSFORM_PATHS.size)
    private val matchedInputs = LongArray(TRANSFORM_PATHS.size)
    private val noMatchInputs = LongArray(TRANSFORM_PATHS.size)
    private val cacheHits = LongArray(TRANSFORM_PATHS.size)
    private val timingSamples = LongArray(TRANSFORM_PATHS.size)
    private val sampledNanos = LongArray(TRANSFORM_PATHS.size)
    private val maxSampledNanos = LongArray(TRANSFORM_PATHS.size)
    private val timingOrdinals = LongArray(TRANSFORM_PATHS.size)
    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastCacheRevision = Long.MIN_VALUE
    private var visibilityRevision = Long.MIN_VALUE
    private var cachedVisibleNames = false
    private var cachedVisibleAliases = false
    private var profileSnapshot: Map<String, PlayerPresentationProfiles.Entry> = emptyMap()
    private var presentationNameKeys: Set<String> = emptySet()
    private var aliasNameKeys: Set<String> = emptySet()

    init {
        group(
            SettingGroup("Name Profiles", replaceNames, applyNameStyles),
            SettingGroup(
                "Your Name",
                ownAlias,
                ownSolidColorEnabled,
                ownSolidColor,
                ownGradientEnabled,
                ownGradientMode,
                ownGradientStart,
                ownGradientEnd,
                *ownGradientStopColors.toTypedArray(),
                ownGradientAnimated,
                ownGradientSpeed,
            ),
        )
    }

    private class Settings {
        val replaceNames = BooleanSetting("Replace Names", true)
        val applyNameStyles = BooleanSetting("Apply Name Styles", true)
        val ownAlias = TextSetting("Own Alias", "Your Alias", "", 64)
        val ownSolidColorEnabled = BooleanSetting("Own Color Enabled", "Use Own Color", false)
        val ownSolidColor = ColorSetting("Own Color", "Own Name Color", 255, 255, 85)
        val ownGradientEnabled = BooleanSetting("Own Gradient Enabled", "Use Own Gradient", false)
        val ownGradientStart = ColorSetting("Gradient Start", 255, 85, 85)
        val ownGradientEnd = ColorSetting("Gradient End", 85, 170, 255)
        val ownGradientStopColors = RAINBOW_DEFAULTS.mapIndexed { index, color ->
            ColorSetting("Gradient Stop ${index + 1}", (color ushr 16) and 0xFF, (color ushr 8) and 0xFF, color and 0xFF)
        }
        val ownGradientMode = ChoiceSetting(
            "Gradient Mode",
            "TWO_COLOR",
            mapOf(
                "TWO_COLOR" to "Two color",
                "REVERSE" to "Reverse two color",
                "RAINBOW" to "Rainbow",
                "MULTI_STOP" to "Custom color stops",
            ),
            "TWO_COLOR",
            "REVERSE",
            "RAINBOW",
            "MULTI_STOP",
        )
        val ownGradientStops = TextSetting(
            "Gradient Stops",
            "Gradient Stops",
            DEFAULT_PLAYER_NAME_GRADIENT_STOPS,
            80,
        )
        val ownGradientAnimated = BooleanSetting("Animated Gradient", false)
        val ownGradientSpeed = NumberSetting("Gradient Speed", "Gradient Speed", 0.05f, 2.0f, 0.25f)
        val allSettings: List<Setting> = listOf(
            replaceNames,
            applyNameStyles,
            ownAlias,
            ownSolidColorEnabled,
            ownSolidColor,
            ownGradientEnabled,
            ownGradientStart,
            ownGradientEnd,
            ownGradientMode,
            ownGradientStops,
            *ownGradientStopColors.toTypedArray(),
            ownGradientAnimated,
            ownGradientSpeed,
        )
    }

    override fun configName(): String = "Player Names"

    override fun isWorkInProgress(): Boolean = true

    override fun isSettingVisible(setting: Setting): Boolean {
        if (setting === ownSolidColor) return ownSolidColorEnabled.value() && !ownGradientEnabled.value()
        if (setting === ownGradientMode || setting === ownGradientAnimated) return ownGradientEnabled.value()
        if (setting === ownGradientStart || setting === ownGradientEnd) {
            return ownGradientEnabled.value() &&
                ("TWO_COLOR" == ownGradientMode.value() || "REVERSE" == ownGradientMode.value())
        }
        if (setting === ownGradientStops) {
            return false
        }
        if (setting in ownGradientStopColors) {
            return ownGradientEnabled.value() && "MULTI_STOP" == ownGradientMode.value()
        }
        if (setting === ownGradientSpeed) return ownGradientEnabled.value() && ownGradientAnimated.value()
        return true
    }

    override fun onEnable() {
        PlayerPresentationProfileStore.INSTANCE.refreshIfDue()
    }

    fun tick() {
        val tick = ++diagnosticTick
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), isEnabled())
        migrateLegacyGradientStops()
        if (!isEnabled()) {
            clearTransformCache()
            return
        }
        PlayerPresentationProfileStore.INSTANCE.refreshIfDue()
        if (lastDiagnosticTick == Long.MIN_VALUE || tick - lastDiagnosticTick >= 100L) {
            val profiles = PlayerPresentationProfileStore.INSTANCE.current()
            GeilerAddonsLog.write(
                Category.VISUAL,
                name(),
                tick,
                "STATUS replaceNames=${replaceNames.value()},applyStyles=${applyNameStyles.value()}" +
                    ",profileEntries=${profiles.entries().size},transformCache=${transformedComponents.size}",
            )
            val glyphs = PlayerNameGradientRenderState.drainCounts()
            if (glyphs[0] > 0L || glyphs[1] > 0L) {
                GeilerAddonsLog.write(
                    Category.VISUAL, name(), tick,
                    "GRADIENT glyphs=${glyphs[0]},unresolvedMarkers=${glyphs[1]},replacedVertexColors=${glyphs[2]}",
                )
            }
            for (index in TRANSFORM_PATHS.indices) {
                val calls = transformCalls[index]
                if (calls > 0L) {
                    GeilerAddonsLog.write(
                        Category.VISUAL,
                        name(),
                        tick,
                        "TRANSFORM path=${TRANSFORM_PATHS[index]},calls=$calls" +
                            ",matchedInputs=${matchedInputs[index]},noMatchInputs=${noMatchInputs[index]}" +
                            ",cacheHits=${cacheHits[index]},timingSamples=${timingSamples[index]}" +
                            ",sampledMicros=${sampledNanos[index] / 1_000L}" +
                            ",maxSampledMicros=${maxSampledNanos[index] / 1_000L}",
                    )
                }
                transformCalls[index] = 0L
                matchedInputs[index] = 0L
                noMatchInputs[index] = 0L
                cacheHits[index] = 0L
                timingSamples[index] = 0L
                sampledNanos[index] = 0L
                maxSampledNanos[index] = 0L
            }
            lastDiagnosticTick = tick
        }
    }

    fun replaceRenderedName(playerName: String?, renderedName: Component?): Component? {
        if (renderedName == null) return null
        val startedAtNanos = beginFontTransformTiming(0)
        val source = PlayerNameTextTransform.restorePresentedLeaves(renderedName)
        return transformComponent(source, 0, playerName, startedAtNanos)
    }

    fun replaceKnownRenderedName(renderedName: Component?): Component? {
        if (renderedName == null) return null
        val startedAtNanos = beginFontTransformTiming(0)
        val source = PlayerNameTextTransform.restorePresentedLeaves(renderedName)
        return transformComponent(source, 0, null, startedAtNanos)
    }

    /** Shared Font-boundary entry point used by text preparation, width, and wrapping hooks. */
    fun replaceFontComponent(source: Component?, path: Int): Component? {
        if (source == null) return null
        return replaceFontComponent(source, path, beginFontTransformTiming(path))
    }

    /** Variant used by adapters to include conversion and candidate-scan cost in sampled timing. */
    fun replaceFontComponent(source: Component?, path: Int, startedAtNanos: Long): Component? {
        if (source == null) return null
        return replaceFontComponent(source, path, startedAtNanos, false)
    }

    fun replaceFontComponent(
        source: Component?,
        path: Int,
        startedAtNanos: Long,
        candidateAlreadyChecked: Boolean,
    ): Component? {
        if (source == null) return null
        return transformComponent(
            PlayerNameTextTransform.restorePresentedRoot(source),
            path,
            null,
            startedAtNanos,
            candidateAlreadyChecked,
        )
    }

    /** Applies aliases to raw String inputs without changing their stored or submitted value. */
    fun replaceFontString(source: String?, path: Int): String? {
        if (source == null || path < 0 || path >= TRANSFORM_PATHS.size) return source
        val startedAtNanos = beginFontTransformTiming(path)
        if (!shouldTransformFontInput(path)) {
            recordFontTransform(path, false, startedAtNanos, false, true)
            return source
        }
        val revision = presentationRevision(0L)
        if (revision != lastCacheRevision) {
            transformedComponents.clear()
            transformedStrings.clear()
            lastCacheRevision = revision
        }
        val cached = transformedStrings[source]
        if (cached != null && cached.revision == revision) {
            val cachedValue = cached.value ?: source
            recordFontTransform(path, cached.value != null, startedAtNanos, true, cached.value == null)
            return cachedValue
        }
        if (!PlayerNameTextTransform.mayContainAnyLegacyName(source, aliasNameKeys)) {
            transformedStrings[source] = CachedString(revision, null)
            trimTransformCaches()
            recordFontTransform(path, false, startedAtNanos, false, true)
            return source
        }
        val result = PlayerNameTextTransform.replaceKnownLegacyText(source) { name ->
            val entry = profileForCached(name)
            entry?.alias()
        }
        transformedStrings[source] = CachedString(revision, if (result === source) null else result)
        trimTransformCaches()
        recordFontTransform(path, result != source, startedAtNanos, false, result == source)
        return result
    }

    fun replaceFormattedText(source: FormattedText?, path: Int): FormattedText? =
        geiler.addons.client.presentation.PlayerNameFontTransform.transform(source, path)

    fun replaceFormattedSequence(source: FormattedCharSequence?, path: Int): FormattedCharSequence? =
        geiler.addons.client.presentation.PlayerNameFontTransform.transform(source, path)

    fun markPresentedSequences(sequences: List<FormattedCharSequence>?, path: Int) {
        geiler.addons.client.presentation.PlayerNameFontTransform.markPresentedSequences(sequences, path)
    }

    private fun transformComponent(
        source: Component,
        pathIndex: Int,
        playerName: String?,
        startedAtNanos: Long,
    ): Component = transformComponent(source, pathIndex, playerName, startedAtNanos, false)

    private fun transformComponent(
        source: Component,
        pathIndex: Int,
        playerName: String?,
        startedAtNanos: Long,
        candidateAlreadyChecked: Boolean,
    ): Component {
        if (pathIndex < 0 || pathIndex >= TRANSFORM_PATHS.size) return source
        if (!shouldTransformFontInput(pathIndex)) {
            recordFontTransform(pathIndex, false, startedAtNanos, false, true)
            return source
        }
        val revision = presentationRevision(0L)
        if (revision != lastCacheRevision) {
            transformedComponents.clear()
            transformedStrings.clear()
            lastCacheRevision = revision
        }
        val cached = transformedComponents[source]
        if (cached != null && cached.revision == revision && cached.playerName == playerName) {
            val cachedValue = cached.value ?: source
            recordFontTransform(pathIndex, cachedValue !== source, startedAtNanos, true, cachedValue === source)
            return cachedValue
        }
        if (!candidateAlreadyChecked) {
            val candidateNames = candidateNames(playerName)
            if (!PlayerNameTextTransform.mayContainAnyName(source, candidateNames)) {
                transformedComponents[source] = CachedComponent(revision, playerName, null)
                trimTransformCaches()
                recordFontTransform(pathIndex, false, startedAtNanos, false, true)
                return source
            }
        }
        val entry = if (playerName == null) null else profileFor(playerName)
        val result = if (entry == null) {
            PlayerNameTextTransform.applyKnownProfiles(
                source,
                this::profileForCached,
                replaceNames.value(),
                applyNameStyles.value(),
            )
        } else {
            PlayerNameTextTransform.applyProfile(
                source,
                playerName,
                entry,
                replaceNames.value(),
                applyNameStyles.value(),
            )
        }
        transformedComponents[source] = CachedComponent(
            revision,
            playerName,
            if (result === source) null else result,
        )
        trimTransformCaches()
        recordFontTransform(pathIndex, result !== source, startedAtNanos, false, result === source)
        return result
    }

    private fun candidateNames(playerName: String?): Set<String> {
        refreshVisibilityCache()
        if (playerName == null) return presentationNameKeys
        val entry = profileSnapshot[normalizeName(playerName)]
        if (!isVisible(entry)) return emptySet()
        return setOf(normalizeName(playerName))
    }

    private fun trimTransformCaches() {
        trimTransformCache(transformedStrings)
    }

    private fun trimTransformCache(cache: MutableMap<*, *>) {
        while (cache.size > MAX_TRANSFORM_CACHE_ENTRIES) {
            val iterator = cache.keys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    fun recordFontTransform(path: Int, matched: Boolean) {
        recordFontTransform(path, matched, 0L, false, !matched)
    }

    fun beginFontTransformTiming(path: Int): Long {
        if (!isEnabled() || path < 0 || path >= TRANSFORM_PATHS.size) return 0L
        val ordinal = ++timingOrdinals[path]
        return if ((ordinal and TIMING_SAMPLE_MASK.toLong()) == 0L) System.nanoTime() else 0L
    }

    fun recordFontTransform(
        path: Int,
        matched: Boolean,
        startedAtNanos: Long,
        cacheHit: Boolean,
        noMatch: Boolean,
    ) {
        if (path < 0 || path >= TRANSFORM_PATHS.size) return
        recordTransform(path, matched)
        if (!isEnabled()) return
        if (noMatch) incrementDiagnostic(noMatchInputs, path)
        if (cacheHit) incrementDiagnostic(cacheHits, path)
        if (startedAtNanos > 0L) {
            val elapsed = (System.nanoTime() - startedAtNanos).coerceAtLeast(0L)
            incrementDiagnostic(timingSamples, path)
            sampledNanos[path] = saturatingAdd(sampledNanos[path], elapsed)
            maxSampledNanos[path] = maxOf(maxSampledNanos[path], elapsed)
        }
    }

    private fun recordTransform(path: Int, matched: Boolean) {
        if (!isEnabled()) return
        incrementDiagnostic(transformCalls, path)
        if (matched) incrementDiagnostic(matchedInputs, path)
    }

    private fun incrementDiagnostic(values: LongArray, index: Int) {
        if (values[index] < MAX_DIAGNOSTIC_COUNT) values[index]++
    }

    private fun saturatingAdd(value: Long, amount: Long): Long =
        if (value > Long.MAX_VALUE - amount) Long.MAX_VALUE else value + amount

    private fun clearTransformCache() {
        transformedComponents.clear()
        transformedStrings.clear()
        lastCacheRevision = Long.MIN_VALUE
        transformCalls.fill(0L)
        matchedInputs.fill(0L)
        noMatchInputs.fill(0L)
        cacheHits.fill(0L)
        timingSamples.fill(0L)
        sampledNanos.fill(0L)
        maxSampledNanos.fill(0L)
        timingOrdinals.fill(0L)
    }

    private data class CachedComponent(val revision: Long, val playerName: String?, val value: Component?)

    private data class CachedString(val revision: Long, val value: String?)

    fun hasEnabledNameStyle(playerName: String?): Boolean {
        if (!isEnabled() || !applyNameStyles.value() || playerName == null) return false
        val entry = profileFor(playerName)
        return entry != null && entry.hasNameStyle()
    }

    fun chatLayoutPresentationRevision(): Long = presentationRevision(0L)

    fun chatComponentPresentationRevision(): Long = presentationRevision(0L)

    fun chatAnimationFrameRevision(): Long = 0L

    fun chatMessagePresentationRevision(message: Component?): Long = presentationRevision(0L)

    fun fontPresentationRevision(): Long = presentationRevision(0L)

    /** Fast guard so the Font hooks skip component/sequence reconstruction when no visible profile can match. */
    fun shouldTransformFontInput(path: Int): Boolean {
        if (path < 0 || path >= TRANSFORM_PATHS.size || !isEnabled()) return false
        if (path == PATH_WIDTH_STRING) return hasActiveNameAliasProfiles()
        return (replaceNames.value() || applyNameStyles.value()) && hasActiveNamePresentationProfiles()
    }

    fun hasFontNameCandidate(source: String?, path: Int): Boolean {
        if (!shouldTransformFontInput(path)) return false
        refreshVisibilityCache()
        return PlayerNameTextTransform.mayContainAnyLegacyName(source, presentationNameKeys)
    }

    fun hasFontNameCandidate(source: FormattedText?): Boolean {
        if (!shouldTransformFontInput(PATH_WIDTH_TEXT)) return false
        refreshVisibilityCache()
        return PlayerNameTextTransform.mayContainAnyName(source, presentationNameKeys)
    }

    fun hasFontNameCandidate(source: FormattedCharSequence?): Boolean {
        if (!shouldTransformFontInput(PATH_WIDTH_SEQUENCE)) return false
        refreshVisibilityCache()
        return PlayerNameTextTransform.mayContainAnyName(source, presentationNameKeys)
    }

    fun chatMessageHasNamePresentation(message: Component?): Boolean {
        if (!isEnabled() || (!replaceNames.value() && !applyNameStyles.value())) return false
        refreshVisibilityCache()
        return PlayerNameTextTransform.hasNamePresentation(message) { name ->
            val entry = profileForCached(name)
            if (entry == null) null
            else if (replaceNames.value() && entry.alias() != null ||
                applyNameStyles.value() && entry.hasNameStyle()
            ) entry else null
        }
    }

    fun chatMessageHasAnimatedNameStyle(message: Component?): Boolean =
        hasAnimatedProfiles() && PlayerNameTextTransform.hasAnimatedProfile(
            PlayerNameTextTransform.restorePresentedLeaves(message),
            this::profileForCached,
        )

    fun hasActiveNamePresentationProfiles(): Boolean {
        refreshVisibilityCache()
        return cachedVisibleNames
    }

    private fun hasActiveNameAliasProfiles(): Boolean {
        refreshVisibilityCache()
        return cachedVisibleAliases
    }

    private fun refreshVisibilityCache() {
        val revision = presentationRevision(0L)
        if (revision == visibilityRevision) return
        visibilityRevision = revision
        cachedVisibleNames = false
        cachedVisibleAliases = false
        val profiles = PlayerPresentationProfileStore.INSTANCE.current()
        val ownName = ownName()
        val resolved = HashMap<String, PlayerPresentationProfiles.Entry>(profiles.entries().size + 1)
        val presentationNames = HashSet<String>()
        val aliasNames = HashSet<String>()
        for (candidate in profiles.entries().values) {
            val playerName = requireNotNull(candidate.playerName()) { "Parsed player profile entry has no player name" }
            val entry = resolveProfile(playerName, candidate, ownName)!!
            val key = normalizeName(playerName)
            resolved[key] = entry
            if (!isEnabled()) continue
            if (isVisible(entry)) {
                cachedVisibleNames = true
                presentationNames.add(key)
            }
            if (replaceNames.value() && entry != null && entry.alias() != null) {
                cachedVisibleAliases = true
                aliasNames.add(key)
            }
        }
        if (ownName != null) {
            val key = normalizeName(ownName)
            var own = resolved[key]
            if (own == null) {
                own = resolveProfile(ownName, null, ownName)
                if (own != null) resolved[key] = own
            }
            if (!isEnabled()) {
                if (own == null) resolved.remove(key)
            } else {
                if (isVisible(own)) {
                    cachedVisibleNames = true
                    presentationNames.add(key)
                }
                if (replaceNames.value() && own != null && own.alias() != null) {
                    cachedVisibleAliases = true
                    aliasNames.add(key)
                }
            }
        }
        profileSnapshot = java.util.Map.copyOf(resolved)
        presentationNameKeys = java.util.Set.copyOf(presentationNames)
        aliasNameKeys = java.util.Set.copyOf(aliasNames)
    }

    fun effectMetadata(playerName: String?): String? {
        val entry = profileFor(playerName)
        return entry?.effectMetadata()
    }

    private fun profileFor(playerName: String?): PlayerPresentationProfiles.Entry? {
        if (playerName == null) return null
        refreshVisibilityCache()
        return profileForCached(playerName)
    }

    private fun profileForCached(playerName: String?): PlayerPresentationProfiles.Entry? {
        if (playerName == null) return null
        return profileSnapshot[normalizeName(playerName)]
    }

    private fun resolveProfile(
        playerName: String?,
        remote: PlayerPresentationProfiles.Entry?,
        ownName: String?,
    ): PlayerPresentationProfiles.Entry? {
        if (playerName == null || ownName == null || !ownName.equals(playerName, ignoreCase = true)) return remote

        var alias: String? = ownAlias.value().trim { it.code <= 0x20 }
        if (isBlank(alias ?: "")) alias = if (remote == null) null else remote.alias()
        var color = if (remote == null) null else remote.color()
        var gradient = if (remote == null) emptyList() else remote.gradient()
        var animation = if (remote == null) null else remote.animation()
        if (ownGradientEnabled.value()) {
            color = null
            gradient = ownGradientColors()
            animation = if (ownGradientAnimated.value()) {
                PlayerPresentationProfiles.Animation(ownGradientSpeed.value())
            } else null
        } else if (ownSolidColorEnabled.value()) {
            color = ownSolidColor.opaqueArgb() and 0x00FF_FFFF
            gradient = emptyList()
            animation = null
        }
        val localChange = !isBlank(ownAlias.value()) || ownGradientEnabled.value() || ownSolidColorEnabled.value()
        if (!localChange) return remote
        return PlayerPresentationProfiles.Entry(
            ownName,
            alias,
            if (remote == null) null else remote.effectMetadata(),
            if (remote == null) null else remote.sizeX(),
            if (remote == null) null else remote.sizeY(),
            if (remote == null) null else remote.sizeZ(),
            color,
            gradient,
            animation,
        )
    }

    private fun hasAnimatedProfiles(): Boolean {
        if (!isEnabled() || !applyNameStyles.value()) return false
        refreshVisibilityCache()
        for (entry in profileSnapshot.values) {
            if (entry != null && entry.animation() != null && entry.hasNameStyle()) return true
        }
        return false
    }

    private fun isVisible(entry: PlayerPresentationProfiles.Entry?): Boolean =
        entry != null &&
            (replaceNames.value() && entry.alias() != null || applyNameStyles.value() && entry.hasNameStyle())

    private fun ownName(): String? {
        // The Java baseline also tolerated its client singleton being unset in offline callers.
        val client: Minecraft? = Minecraft.getInstance()
        return if (client == null || client.user == null) null else client.user.name
    }

    private fun presentationRevision(frame: Long): Long {
        val profiles = PlayerPresentationProfileStore.INSTANCE.current()
        val settings = (if (isEnabled()) 1L else 0L) or
            (if (replaceNames.value()) 2L else 0L) or
            (if (applyNameStyles.value()) 4L else 0L)
        var state = (System.identityHashCode(profiles).toLong() shl 3) or settings
        state = state * 31 + ownAlias.value().hashCode().toLong()
        state = state * 31 + (if (ownSolidColorEnabled.value()) ownSolidColor.argb() else 0).toLong()
        state = state * 31 + (if (ownGradientEnabled.value()) ownGradientStart.argb() else 0).toLong()
        state = state * 31 + (if (ownGradientEnabled.value()) ownGradientEnd.argb() else 0).toLong()
        state = state * 31 + (if (ownGradientEnabled.value()) ownGradientMode.value().hashCode() else 0).toLong()
        if (ownGradientEnabled.value()) {
            for (stop in ownGradientStopColors) state = state * 31 + stop.opaqueArgb().toLong()
        }
        state = state * 31 +
            (if (ownGradientAnimated.value()) java.lang.Float.floatToIntBits(ownGradientSpeed.value()) else 0).toLong()
        val ownName = ownName()
        state = state * 31 + (if (ownName == null) 0 else normalizeName(ownName).hashCode()).toLong()
        return (state shl 32) or (frame and 0xFFFF_FFFFL)
    }

    private fun ownGradientColors(): List<Int> {
        val start = ownGradientStart.opaqueArgb() and 0x00FF_FFFF
        val end = ownGradientEnd.opaqueArgb() and 0x00FF_FFFF
        return when (ownGradientMode.value()) {
            "REVERSE" -> listOf(end, start)
            "RAINBOW" -> RAINBOW_COLORS
            "MULTI_STOP" -> ownGradientStopColors.map { it.opaqueArgb() and 0x00FF_FFFF }
            else -> listOf(start, end)
        }
    }

    private fun previewName(): Component {
        val name = ownName()?.takeIf { !isBlank(it) } ?: "YourName"
        val alias = ownAlias.value().trim { it.code <= 0x20 }.takeIf { replaceNames.value() && it.isNotEmpty() }
        val gradient = if (ownGradientEnabled.value()) ownGradientColors() else emptyList()
        val color = if (!ownGradientEnabled.value() && ownSolidColorEnabled.value()) {
            ownSolidColor.opaqueArgb() and 0x00FF_FFFF
        } else null
        val animation = if (ownGradientEnabled.value() && ownGradientAnimated.value()) {
            PlayerPresentationProfiles.Animation(ownGradientSpeed.value())
        } else null
        val sample = PlayerPresentationProfiles.Entry(
            name,
            alias,
            null,
            null,
            null,
            null,
            color,
            gradient,
            animation,
        )
        return PlayerNameTextTransform.applyProfile(
            Component.literal(name),
            name,
            sample,
            replaceNames.value(),
            applyNameStyles.value(),
        )
    }

    override fun previewHeight(width: Int, availableHeight: Int): Int = minOf(58, availableHeight)

    override fun previewLines(font: Font, width: Int): List<Component> = listOf(
        previewHeading(),
        previewName(),
        Component.literal("Chat · Tab · Name tags · Scoreboard"),
    )

    private fun previewHeading(): Component = Component.literal(
        when {
            ownGradientEnabled.value() -> "Gradient · ${ownGradientMode.displayValue()}"
            ownSolidColorEnabled.value() -> "Solid color"
            else -> "Name preview"
        },
    )

    /** Uses the same transformed component as chat, tab, nametag, and scoreboard hooks. */
    override fun drawPreview(
        graphics: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val lineHeight = font.lineHeight
        graphics.text(
            font,
            previewHeading(),
            x,
            y,
            0xFFE5E7EB.toInt(),
        )
        graphics.text(font, previewName(), x, y + lineHeight + 3, 0xFFFFFFFF.toInt())
        graphics.text(font, Component.literal("Chat · Tab · Name tags · Scoreboard"), x, y + lineHeight * 2 + 6, 0xFFB8C0CC.toInt())
        return true
    }

    fun replaceNamesSetting(): BooleanSetting = replaceNames

    fun applyNameStylesSetting(): BooleanSetting = applyNameStyles

    private fun migrateLegacyGradientStops() {
        val legacy = ownGradientStops.value()
        if (legacy.isEmpty() || legacy == DEFAULT_PLAYER_NAME_GRADIENT_STOPS) return
        if (ownGradientStopColors.indices.any { index ->
                (ownGradientStopColors[index].opaqueArgb() and 0x00FF_FFFF) !=
                    (RAINBOW_DEFAULTS[index] and 0x00FF_FFFF)
            }
        ) {
            ownGradientStops.setValue(DEFAULT_PLAYER_NAME_GRADIENT_STOPS)
            ModConfig.markDirty()
            return
        }
        val fallbackStart = ownGradientStart.opaqueArgb() and 0x00FF_FFFF
        val fallbackEnd = ownGradientEnd.opaqueArgb() and 0x00FF_FFFF
        val colors = parseGradientStops(legacy, fallbackStart, fallbackEnd)
        for (index in ownGradientStopColors.indices) {
            val rgb = colors.getOrElse(index) { RAINBOW_DEFAULTS[index] } and 0x00FF_FFFF
            ownGradientStopColors[index].set((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF, 255)
        }
        ownGradientStops.setValue(DEFAULT_PLAYER_NAME_GRADIENT_STOPS)
        ModConfig.markDirty()
    }

    companion object {
        private const val MAX_DIAGNOSTIC_COUNT = 1_000_000
        private const val MAX_TRANSFORM_CACHE_ENTRIES = 4096
        private const val TIMING_SAMPLE_MASK = 15
        private const val DEFAULT_PLAYER_NAME_GRADIENT_STOPS =
            "#FF5555,#FFFF55,#55FF55,#55FFFF,#5555FF,#FF55FF"
        private val RAINBOW_DEFAULTS = listOf(
            0xFFFF5555.toInt(),
            0xFFFFFF55.toInt(),
            0xFF55FF55.toInt(),
            0xFF55FFFF.toInt(),
            0xFF5555FF.toInt(),
            0xFFFF55FF.toInt(),
        )
        private val TRANSFORM_PATHS = arrayOf(
            "component-targeted",
            "prepare-string",
            "prepare-sequence",
            "draw-outline",
            "width-string",
            "width-text",
            "width-sequence",
            "split-text",
            "split-output",
            "chat-split-output",
            "visual-order",
        )
        private val RAINBOW_COLORS = listOf(
            *RAINBOW_DEFAULTS.toTypedArray(),
        )

        const val PATH_PREPARE_STRING = 1
        const val PATH_PREPARE_SEQUENCE = 2
        const val PATH_DRAW_OUTLINE = 3
        const val PATH_WIDTH_STRING = 4
        const val PATH_WIDTH_TEXT = 5
        const val PATH_WIDTH_SEQUENCE = 6
        const val PATH_SPLIT_TEXT = 7
        const val PATH_SPLIT_OUTPUT = 8
        const val PATH_CHAT_SPLIT_OUTPUT = 9
        const val PATH_VISUAL_ORDER = 10

        @JvmField
        val INSTANCE = PlayerNamesModule(Settings())

        private fun normalizeName(name: String): String = name.lowercase(Locale.ROOT)

        private fun isBlank(value: String): Boolean =
            value.codePoints().allMatch { java.lang.Character.isWhitespace(it) }

        private fun parseGradientStops(raw: String?, fallbackStart: Int, fallbackEnd: Int): List<Int> {
            if (raw == null || isBlank(raw)) return listOf(fallbackStart, fallbackEnd)
            val colors = ArrayList<Int>(8)
            for (token in raw.split(Regex(","), limit = 9)) {
                var value = token.trim { it.code <= 0x20 }
                if (value.startsWith("#")) value = value.substring(1)
                if (value.length != 6) continue
                try {
                    colors.add(Integer.parseInt(value, 16) and 0x00FF_FFFF)
                } catch (_: NumberFormatException) {
                    // Ignore malformed stops and retain the valid colors around them.
                }
                if (colors.size == 8) break
            }
            return if (colors.size >= 2) java.util.List.copyOf(colors) else listOf(fallbackStart, fallbackEnd)
        }
    }
}
