package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale
import kotlin.collections.Collection

/** Pure identity, filtering, and presentation rules behind the Java-facing alert API. */
object SlayerAlertRulesCore {
    /** Returns a type only when observations resolve to exactly one distinct Slayer boss. */
    fun observeBossNames(rawNames: Collection<String?>?): SlayerAlertRules.BossObservation {
        val observed = EnumSet.noneOf(SlayerAlertRules.BossType::class.java)
        if (rawNames != null) {
            for (rawName in rawNames) {
                val type = classifyBossName(rawName)
                if (type != null) observed.add(type)
            }
        }
        if (observed.size > 1) return SlayerAlertRules.BossObservation(null, true)
        return SlayerAlertRules.BossObservation(if (observed.isEmpty()) null else observed.iterator().next(), false)
    }

    fun classifyBossName(rawName: String?): SlayerAlertRules.BossType? {
        val name = ChatText.stripForMatch(rawName ?: "").lowercase(Locale.ROOT)
        for (type in SlayerAlertRules.BossType.values()) {
            if (name.contains(type.displayName().lowercase(Locale.ROOT))) return type
        }
        return null
    }

    /** The fallback preserves the old generic boss alert if an entity name is not observable yet. */
    fun bossFilterAllows(
        observation: SlayerAlertRules.BossObservation?,
        enabledTypes: Map<SlayerAlertRules.BossType, Boolean>?,
        unclassifiedEnabled: Boolean,
    ): Boolean {
        if (observation == null || observation.ambiguous) return false
        val type = observation.type
        if (type == null) return unclassifiedEnabled
        if (enabledTypes == null || !enabledTypes.containsKey(type)) return true
        return enabledTypes[type]!!
    }

    fun presentation(
        chat: Boolean,
        title: Boolean,
        sound: Boolean,
        durationTicks: Int,
        template: String?,
        bossType: SlayerAlertRules.BossType?,
        bossName: String?,
        minibossName: String?,
    ): SlayerAlertRules.Presentation {
        val boss = safeName(bossName, bossType?.displayName() ?: "Slayer boss")
        val miniboss = safeName(minibossName, "Slayer miniboss")
        var message = if (template == null) "" else stripLikeJava(template)
        if (message.isEmpty()) {
            message = if (minibossName == null) SlayerAlertRules.DEFAULT_BOSS_TEXT
            else SlayerAlertRules.DEFAULT_MINIBOSS_TEXT
        }
        message = message.replace("{boss}", boss).replace("{miniboss}", miniboss)
        val clampedDuration = Math.max(
            SlayerAlertRules.MIN_TITLE_DURATION_TICKS,
            Math.min(SlayerAlertRules.MAX_TITLE_DURATION_TICKS, durationTicks),
        )
        return SlayerAlertRules.Presentation(chat, title, sound, clampedDuration, message)
    }

    private fun safeName(candidate: String?, fallback: String): String {
        val clean = ChatText.stripForMatch(candidate ?: "")
        return if (clean.isEmpty()) fallback else clean
    }

    /** Matches Java String.strip() over Unicode code points using Character.isWhitespace. */
    private fun stripLikeJava(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val codePoint = value.codePointAt(start)
            if (!Character.isWhitespace(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = value.codePointBefore(end)
            if (!Character.isWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return value.substring(start, end)
    }

    /** Settings are stored independently; this helper builds a value snapshot for filtering. */
    fun allEnabledFilters(): EnumMap<SlayerAlertRules.BossType, Boolean> {
        val filters = EnumMap<SlayerAlertRules.BossType, Boolean>(SlayerAlertRules.BossType::class.java)
        for (type in SlayerAlertRules.BossType.values()) {
            filters[type] = SlayerAlertRules.DEFAULT_BOSS_TYPE_FILTER
        }
        return filters
    }
}
