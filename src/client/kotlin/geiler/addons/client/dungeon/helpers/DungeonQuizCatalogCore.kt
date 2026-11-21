package geiler.addons.client.dungeon.helpers

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap
import java.util.HashSet

/** Bounded loading, validation, and lookup for the pinned Quiz answer catalog. */
internal object DungeonQuizCatalogCore {
    private const val RESOURCE = "/data/dungeon-quiz-answers.json"
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_ENTRIES = 64
    private const val MAX_ANSWERS_PER_ENTRY = 12

    fun loadBundled(): DungeonQuizCatalog.CatalogResult {
        try {
            val input = DungeonQuizCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: return unavailable("licensed Quiz answer catalog is missing")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES)
                    return unavailable("Quiz answer catalog is empty or exceeds its size limit")
                return parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun parse(json: String?): DungeonQuizCatalog.CatalogResult {
        if (json == null || json.all { Character.isWhitespace(it) } || json.length > MAX_BYTES)
            return unavailable("Quiz answer catalog is empty or exceeds its size limit")
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return unavailable("Quiz answer catalog root is not an object")
            val root = rootElement.asJsonObject
            if (root.size() == 0 || root.size() > MAX_ENTRIES)
                return unavailable("Quiz answer entry count is outside its supported range")
            val entries = HashMap<String, List<String>>()
            val sourceQuestions = HashMap<String, String>()
            val conflicts = HashSet<String>()
            for (entry in root.entrySet()) {
                val question = entry.key?.let(::javaStrip) ?: ""
                val normalized = DungeonQuizRulesCore.normalize(question)
                val value = entry.value
                if (question.isEmpty() || question.length > 180 || normalized.isEmpty() ||
                    value == null || !value.isJsonArray
                ) return unavailable("Quiz answer catalog contains an invalid question")
                val answerArray: JsonArray = value.asJsonArray
                if (answerArray.size() == 0 || answerArray.size() > MAX_ANSWERS_PER_ENTRY)
                    return unavailable("Quiz answer catalog contains an invalid answer list")
                val answers = ArrayList<String>(answerArray.size())
                for (answerElement: JsonElement in answerArray) {
                    if (!answerElement.isJsonPrimitive || !answerElement.asJsonPrimitive.isString)
                        return unavailable("Quiz answer catalog contains a non-text answer")
                    val answer = javaStrip(answerElement.asString)
                    if (answer.isEmpty() || answer.length > 120)
                        return unavailable("Quiz answer catalog contains an invalid answer")
                    if (!answers.contains(answer)) answers.add(answer)
                }
                val immutableAnswers = java.util.List.copyOf(answers)
                val previous = entries.putIfAbsent(normalized, immutableAnswers)
                if (previous != null && previous != immutableAnswers) conflicts.add(normalized)
                sourceQuestions.putIfAbsent(normalized, question)
            }
            for (conflict in conflicts) entries.remove(conflict)
            return DungeonQuizCatalog.CatalogResult(true, entries, sourceQuestions, conflicts.size, "")
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun match(catalog: DungeonQuizCatalog.CatalogResult, question: String?): DungeonQuizCatalog.Match? {
        if (!catalog.available() || question == null) return null
        val normalized = DungeonQuizRulesCore.normalize(question)
        var bestKey: String? = null
        for (key in catalog.entries().keys) {
            if (!normalized.contains(key)) continue
            val previousKey = bestKey
            if (previousKey == null || key.length > previousKey.length) {
                bestKey = key
            } else if (key.length == previousKey.length && catalog.entries()[key] != catalog.entries()[previousKey]) {
                return null
            }
        }
        val selected = bestKey ?: return null
        return DungeonQuizCatalog.Match(
            catalog.sourceQuestions().getOrDefault(selected, selected),
            catalog.entries()[selected],
        )
    }

    private fun unavailable(reason: String): DungeonQuizCatalog.CatalogResult =
        DungeonQuizCatalog.CatalogResult(false, java.util.Map.of(), java.util.Map.of(), 0, reason)

    private fun javaStrip(value: String): String = value.trim { Character.isWhitespace(it) }
}
