package geiler.addons.client.dungeon.helpers

/** Java-callable boundary for the bounded Quiz catalog loader. */
class DungeonQuizCatalog private constructor() {
    companion object {
        private val bundledCatalog: CatalogResult = DungeonQuizCatalogCore.loadBundled()

        @JvmStatic
        fun bundled(): CatalogResult = bundledCatalog

        @JvmStatic
        fun parse(json: String?): CatalogResult = DungeonQuizCatalogCore.parse(json)
    }

    class Match(sourceQuestion: String?, answers: List<String>?) {
        private val sourceQuestionValue = sourceQuestion
        private val answersValue: List<String> = if (answers == null) java.util.List.of() else java.util.List.copyOf(answers)

        fun sourceQuestion(): String? = sourceQuestionValue
        fun answers(): List<String> = answersValue

        override fun equals(other: Any?): Boolean = this === other ||
            other is Match && sourceQuestionValue == other.sourceQuestion() && answersValue == other.answers()

        override fun hashCode(): Int = odinValueHash(sourceQuestionValue, answersValue)

        override fun toString(): String =
            odinValueText("Match", "sourceQuestion" to sourceQuestionValue, "answers" to answersValue)
    }

    class CatalogResult(
        available: Boolean,
        entries: Map<String, List<String>>?,
        sourceQuestions: Map<String, String>?,
        conflictingKeys: Int,
        reason: String?,
    ) {
        private val availableValue = available
        private val entriesValue: Map<String, List<String>> =
            if (entries == null) java.util.Map.of() else java.util.Map.copyOf(entries)
        private val sourceQuestionsValue: Map<String, String> =
            if (sourceQuestions == null) java.util.Map.of() else java.util.Map.copyOf(sourceQuestions)
        private val conflictingKeysValue = conflictingKeys
        private val reasonValue = reason ?: ""

        fun available(): Boolean = availableValue
        fun entries(): Map<String, List<String>> = entriesValue
        fun sourceQuestions(): Map<String, String> = sourceQuestionsValue
        fun conflictingKeys(): Int = conflictingKeysValue
        fun reason(): String = reasonValue

        fun match(question: String?): Match? = DungeonQuizCatalogCore.match(this, question)

        override fun equals(other: Any?): Boolean = this === other ||
            other is CatalogResult && availableValue == other.available() && entriesValue == other.entries() &&
            sourceQuestionsValue == other.sourceQuestions() && conflictingKeysValue == other.conflictingKeys() &&
            reasonValue == other.reason()

        override fun hashCode(): Int =
            odinValueHash(availableValue, entriesValue, sourceQuestionsValue, conflictingKeysValue, reasonValue)

        override fun toString(): String = odinValueText(
            "CatalogResult",
            "available" to availableValue,
            "entries" to entriesValue,
            "sourceQuestions" to sourceQuestionsValue,
            "conflictingKeys" to conflictingKeysValue,
            "reason" to reasonValue,
        )
    }
}
