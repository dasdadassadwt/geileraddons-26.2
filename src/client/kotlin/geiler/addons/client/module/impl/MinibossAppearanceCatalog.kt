package geiler.addons.client.module.impl

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.HashSet

/** Strict, local-only catalog of explicitly reviewed player-skin signatures for dungeon minibosses. */
class MinibossAppearanceCatalog private constructor(reviewedLabels: Map<Identity, String>) {
    companion object {
        const val SCHEMA = "geileraddons-miniboss-appearance-catalog"
        const val VERSION = 1
        const val MAX_BYTES = 64 * 1024
        const val MAX_ENTRIES = 128

        private const val MAX_SOURCE_CHARS = 80
        private const val MAX_REFERENCE_CHARS = 200
        private const val MAX_LICENSE_CHARS = 80
        private const val MAX_EVIDENCE_CHARS = 240
        private val MINIBOSS_LABELS = mapOf(
            "Shadow Assassin" to "Shadow Assassin",
            "Lost Adventurer" to "Lost Adventurer",
            "Diamond Guy" to "Diamond Guy",
            "Angry Archaeologist" to "Angry Archaeologist",
            "Frozen Adventurer" to "Frozen Adventurer",
            "King Midas" to "King Midas",
        )
        private val EMPTY = MinibossAppearanceCatalog(emptyMap())

        @JvmStatic
        fun empty(): MinibossAppearanceCatalog = EMPTY

        @JvmStatic
        fun parse(json: String?): ParseResult {
            if (json == null) return ParseResult.invalid("catalog text is missing")
            if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES) {
                return ParseResult.invalid("catalog exceeds the 64 KiB limit")
            }
            val reviewed = HashMap<Identity, String>()
            val seen = HashSet<Identity>()
            var entryCount = 0
            var unreviewedCount = 0
            try {
                JsonReader(StringReader(json)).use { reader ->
                    reader.setStrictness(Strictness.STRICT)
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return ParseResult.invalid("root must be an object")
                    reader.beginObject()
                    val rootKeys = HashSet<String>()
                    var schema: String? = null
                    var version: Int? = null
                    var profilesSeen = false
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (!rootKeys.add(key)) return ParseResult.invalid("duplicate root field: $key")
                        when (key) {
                            "schema" -> schema = readString(reader, 80)
                            "version" -> {
                                if (reader.peek() != JsonToken.NUMBER) {
                                    return ParseResult.invalid("version must be an integer")
                                }
                                version = reader.nextInt()
                            }
                            "profiles" -> {
                                profilesSeen = true
                                if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                                    return ParseResult.invalid("profiles must be an array")
                                }
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    if (entryCount >= MAX_ENTRIES) {
                                        return ParseResult.invalid("catalog has more than 128 profiles")
                                    }
                                    val profile = readProfile(reader)
                                        ?: return ParseResult.invalid("profile entry is malformed or incomplete")
                                    entryCount++
                                    if (!seen.add(profile.identity)) {
                                        return ParseResult.invalid("duplicate signature and skinModel pair")
                                    }
                                    if (profile.reviewed) reviewed[profile.identity] = profile.label
                                    else unreviewedCount++
                                }
                                reader.endArray()
                            }
                            else -> return ParseResult.invalid("unknown root field: $key")
                        }
                    }
                    reader.endObject()
                    if (reader.peek() != JsonToken.END_DOCUMENT) {
                        return ParseResult.invalid("trailing data after catalog")
                    }
                    if (SCHEMA != schema) return ParseResult.invalid("unsupported catalog schema")
                    if (version == null || version != VERSION) {
                        return ParseResult.invalid("unsupported catalog version")
                    }
                    if (!profilesSeen) return ParseResult.invalid("profiles array is missing")
                }
            } catch (error: IOException) {
                return ParseResult.invalid("invalid JSON: ${safeError(error)}")
            } catch (error: IllegalStateException) {
                return ParseResult.invalid("invalid JSON: ${safeError(error)}")
            } catch (error: NumberFormatException) {
                return ParseResult.invalid("invalid JSON: ${safeError(error)}")
            }
            return ParseResult(true, "", MinibossAppearanceCatalog(java.util.Map.copyOf(reviewed)),
                entryCount, unreviewedCount)
        }

        @JvmStatic
        fun emptyTemplate(): String = "{\n" +
            "  \"schema\": \"$SCHEMA\",\n" +
            "  \"version\": $VERSION,\n" +
            "  \"profiles\": []\n" +
            "}\n"

        private fun readProfile(reader: JsonReader): ParsedProfile? {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
            reader.beginObject()
            val keys = HashSet<String>()
            var signature: String? = null
            var skinModel: String? = null
            var label: String? = null
            var source: String? = null
            var sourceReference: String? = null
            var license: String? = null
            var evidence: String? = null
            var reviewed: Boolean? = null
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (!keys.add(key)) return null
                when (key) {
                    "signature" -> signature = readString(reader, 71)
                    "skinModel" -> skinModel = readString(reader, 16)
                    "label" -> label = readString(reader, 40)
                    "source" -> source = readString(reader, MAX_SOURCE_CHARS)
                    "sourceReference" -> sourceReference = readString(reader, MAX_REFERENCE_CHARS)
                    "license" -> license = readString(reader, MAX_LICENSE_CHARS)
                    "evidence" -> evidence = readString(reader, MAX_EVIDENCE_CHARS)
                    "reviewed" -> {
                        if (reader.peek() != JsonToken.BOOLEAN) return null
                        reviewed = reader.nextBoolean()
                    }
                    else -> return null
                }
            }
            reader.endObject()
            if (signature == null || !signature.matches(Regex("^sha256:[0-9a-f]{64}$"))
                || !isModel(skinModel) || label == null || !MINIBOSS_LABELS.containsKey(label)
                || !boundedText(source, MAX_SOURCE_CHARS) || !boundedText(sourceReference, MAX_REFERENCE_CHARS)
                || !boundedText(license, MAX_LICENSE_CHARS) || !boundedText(evidence, MAX_EVIDENCE_CHARS)
                || reviewed == null
            ) return null
            return ParsedProfile(Identity(signature, skinModel!!), MINIBOSS_LABELS.getValue(label), reviewed)
        }

        @Throws(IOException::class)
        private fun readString(reader: JsonReader, maximum: Int): String? {
            if (reader.peek() != JsonToken.STRING) throw IllegalStateException("expected a string")
            val value = reader.nextString()
            return if (value.length <= maximum) value else null
        }

        private fun boundedText(value: String?, maximum: Int): Boolean {
            if (value == null || value.isBlank() || value.length > maximum || value != value.trim()) return false
            for (character in value) if (character.code.let { Character.isISOControl(it) }) return false
            return true
        }

        private fun isModel(value: String?): Boolean = value == "classic" || value == "slim" || value == "unknown"

        private fun safeError(error: Exception): String {
            val message = error.message
            return if (message.isNullOrBlank()) error.javaClass.simpleName else message
        }
    }

    private val reviewedLabels: Map<Identity, String> = java.util.Map.copyOf(reviewedLabels)

    fun labelFor(appearance: MinibossAppearanceCapture.AppearanceIdentity?): String? {
        if (appearance == null) return null
        return reviewedLabels[Identity(appearance.textureSignature, appearance.model)]
    }

    fun reviewedEntryCount(): Int = reviewedLabels.size

    @JvmRecord
    data class Identity(val signature: String, val skinModel: String)

    private data class ParsedProfile(val identity: Identity, val label: String, val reviewed: Boolean)

    @JvmRecord
    data class ParseResult(
        val valid: Boolean,
        val error: String,
        val catalog: MinibossAppearanceCatalog,
        val entryCount: Int,
        val unreviewedCount: Int,
    ) {
        companion object {
            @JvmStatic
            fun invalid(error: String?): ParseResult = ParseResult(
                false, error ?: "invalid catalog", empty(), 0, 0,
            )
        }
    }
}
