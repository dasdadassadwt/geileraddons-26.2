package geiler.addons.client.farming

import kotlin.collections.Collection
import kotlin.jvm.JvmRecord

/** Java API facade over the pure Kotlin Slayer alert rules. */
class SlayerAlertRules private constructor() {
    enum class BossType(private val displayName: String, private val settingName: String) {
        REVENANT_HORROR("Revenant Horror", "Revenant Horror"),
        ATONED_HORROR("Atoned Horror", "Atoned Horror"),
        TARANTULA_BROODFATHER("Tarantula Broodfather", "Tarantula Broodfather"),
        SVEN_PACKMASTER("Sven Packmaster", "Sven Packmaster"),
        VOIDGLOOM_SERAPH("Voidgloom Seraph", "Voidgloom Seraph"),
        INFERNO_DEMONLORD("Inferno Demonlord", "Inferno Demonlord"),
        RIFTSTALKER_BLOODFIEND("Riftstalker Bloodfiend", "Riftstalker Bloodfiend");

        fun displayName(): String = displayName

        fun settingName(): String = settingName
    }

    /** A null type with ambiguous=false means no recognized boss name was observed. */
    @JvmRecord
    data class BossObservation(val type: BossType?, val ambiguous: Boolean) {
        fun recognized(): Boolean = type != null && !ambiguous

        override fun toString(): String = "BossObservation[type=$type, ambiguous=$ambiguous]"
    }

    @JvmRecord
    data class Presentation(
        val showChat: Boolean,
        val showTitle: Boolean,
        val playSound: Boolean,
        val durationTicks: Int,
        val message: String?,
    ) {
        override fun toString(): String =
            "Presentation[showChat=$showChat, showTitle=$showTitle, playSound=$playSound, durationTicks=$durationTicks, message=$message]"
    }

    companion object {
        const val DEFAULT_BOSS_ALERT: Boolean = false
        const val DEFAULT_MINIBOSS_ALERT: Boolean = false
        const val DEFAULT_BOSS_TYPE_FILTER: Boolean = true
        const val DEFAULT_UNCLASSIFIED_BOSS_ALERT: Boolean = true
        const val DEFAULT_CHAT_ALERT: Boolean = true
        const val DEFAULT_TITLE_ALERT: Boolean = false
        const val DEFAULT_SOUND_ALERT: Boolean = false
        const val DEFAULT_TITLE_DURATION_TICKS: Int = 40
        const val DEFAULT_BOSS_TEXT: String = "Slayer boss spawned."
        const val DEFAULT_MINIBOSS_TEXT: String = "Slayer miniboss: {miniboss}."
        const val MIN_TITLE_DURATION_TICKS: Int = 1
        const val MAX_TITLE_DURATION_TICKS: Int = 600

        @JvmStatic
        fun observeBossNames(rawNames: Collection<String?>?): BossObservation =
            SlayerAlertRulesCore.observeBossNames(rawNames)

        @JvmStatic
        fun classifyBossName(rawName: String?): BossType? =
            SlayerAlertRulesCore.classifyBossName(rawName)

        @JvmStatic
        fun bossFilterAllows(
            observation: BossObservation?,
            enabledTypes: Map<BossType, Boolean>?,
            unclassifiedEnabled: Boolean,
        ): Boolean = SlayerAlertRulesCore.bossFilterAllows(observation, enabledTypes, unclassifiedEnabled)

        @JvmStatic
        fun presentation(
            chat: Boolean,
            title: Boolean,
            sound: Boolean,
            durationTicks: Int,
            template: String?,
            bossType: BossType?,
            bossName: String?,
            minibossName: String?,
        ): Presentation = SlayerAlertRulesCore.presentation(
            chat,
            title,
            sound,
            durationTicks,
            template,
            bossType,
            bossName,
            minibossName,
        )

        @JvmStatic
        fun allEnabledFilters(): java.util.EnumMap<BossType, Boolean> =
            SlayerAlertRulesCore.allEnabledFilters()
    }
}
