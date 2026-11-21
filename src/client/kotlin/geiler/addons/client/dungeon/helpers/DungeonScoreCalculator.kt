package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonFloor
import java.util.OptionalInt

/** Stable Java API for the local Odin score parser and calculator. */
class DungeonScoreCalculator private constructor() {
    companion object {
        /** Reads vanilla tab-list rows and deliberately ignores every score-total row. */
        @JvmStatic
        fun readTabLines(lines: Iterable<String?>?): TabStats = DungeonScoreLogic.readTabLines(lines)

        /** Merges explicit tab rows over client-observed signals, keeping unknown values unknown. */
        @JvmStatic
        fun withBonusSignals(tab: TabStats?, signals: BonusSnapshot?): TabStats =
            DungeonScoreLogic.withBonusSignals(tab, signals)

        /** Prefers the freshest observed team-prefix clear percentage over a missing or stale tab row. */
        @JvmStatic
        fun withTeamSignals(tab: TabStats?, team: TeamSnapshot?): TabStats =
            DungeonScoreLogic.withTeamSignals(tab, team)

        /** Calculates known score components and returns a range while puzzle or bonus inputs are missing. */
        @JvmStatic
        fun calculate(
            floor: DungeonFloor?,
            stats: TabStats?,
            bloodDone: Boolean?,
            inBoss: Boolean
        ): ScoreEstimate = DungeonScoreLogic.calculate(floor, stats, bloodDone, inBoss)

        @JvmStatic
        fun requiredSecretPercentage(floor: DungeonFloor?): Double =
            DungeonScoreLogic.requiredSecretPercentage(floor)
    }

    enum class Input {
        FLOOR, SECRETS_FOUND, SECRETS_PERCENT, COMPLETED_ROOMS,
        PUZZLE_COUNT, PUZZLE_STATUS_ROWS, DEATHS, BLOOD_STATUS, CRYPTS, MIMIC, PRINCE, BAT_KILLERS, PAUL,
        INVALID_TAB_VALUE
    }

    /** Normalizes and snapshots unknown inputs while retaining the old Java record accessor surface. */
    class ScoreEstimate(minimum: Int?, maximum: Int?, unknownInputs: Set<Input>?) {
        private val minimumValue = minimum
        private val maximumValue = maximum
        private val unknownInputsValue: Set<Input> = java.util.Set.copyOf(unknownInputs.orEmpty())

        fun minimum(): Int? = minimumValue
        fun maximum(): Int? = maximumValue
        fun unknownInputs(): Set<Input> = unknownInputsValue

        fun available(): Boolean = minimumValue != null && maximumValue != null
        fun exact(): Boolean = available() && minimumValue == maximumValue
        fun exactScore(): OptionalInt =
            if (exact()) OptionalInt.of(minimumValue!!) else OptionalInt.empty()

        override fun equals(other: Any?): Boolean = this === other ||
            other is ScoreEstimate && minimumValue == other.minimumValue &&
            maximumValue == other.maximumValue && unknownInputsValue == other.unknownInputsValue

        override fun hashCode(): Int {
            var result = minimumValue?.hashCode() ?: 0
            result = 31 * result + (maximumValue?.hashCode() ?: 0)
            result = 31 * result + unknownInputsValue.hashCode()
            return result
        }

        override fun toString(): String =
            "ScoreEstimate[minimum=$minimumValue, maximum=$maximumValue, unknownInputs=$unknownInputsValue]"
    }

    @JvmRecord
    data class TabStats(
        val secretsFound: Int?,
        val secretsPercent: Double?,
        val completedRooms: Int?,
        val clearedPercent: Double?,
        val puzzleCount: Int?,
        val completedPuzzles: Int,
        val observedPuzzleStatusRows: Int,
        val deaths: Int?,
        val crypts: Int?,
        val mimic: Boolean?,
        val prince: Boolean?,
        val batKillers: Int?,
        val paul: Boolean?
    ) {
        override fun toString(): String =
            "TabStats[secretsFound=$secretsFound, secretsPercent=$secretsPercent, " +
                "completedRooms=$completedRooms, clearedPercent=$clearedPercent, puzzleCount=$puzzleCount, " +
                "completedPuzzles=$completedPuzzles, observedPuzzleStatusRows=$observedPuzzleStatusRows, " +
                "deaths=$deaths, crypts=$crypts, mimic=$mimic, prince=$prince, " +
                "batKillers=$batKillers, paul=$paul]"
    }

    @JvmRecord
    data class TeamSnapshot(val floor: DungeonFloor?, val clearedPercent: Double?) {
        override fun toString(): String = "TeamSnapshot[floor=$floor, clearedPercent=$clearedPercent]"
    }

    @JvmRecord
    data class BonusSnapshot(
        val mimic: Boolean?,
        val prince: Boolean?,
        val batKillers: Int?,
        val paul: Boolean?
    ) {
        override fun toString(): String =
            "BonusSnapshot[mimic=$mimic, prince=$prince, batKillers=$batKillers, paul=$paul]"

        companion object {
            @JvmField
            val UNKNOWN = BonusSnapshot(null, null, null, null)
        }
    }

    /** Run-local Odin signals. Paul stays unknown until its explicit event is observed. */
    class RunSignals {
        private val core = DungeonRunSignalsCore()

        fun resetUnknown() = core.resetUnknown()
        fun beginRun() = core.beginRun()
        fun observeTeamUpdate(text: String?): Boolean = core.observeTeamUpdate(text)
        fun observePartyMessage(line: String?, floor: DungeonFloor?): Boolean =
            core.observePartyMessage(line, floor)

        fun observePaul(active: Boolean?) = core.observePaul(active)
        fun teamSnapshot(): TeamSnapshot = core.teamSnapshot()
        fun snapshot(): BonusSnapshot = core.snapshot()
    }
}
