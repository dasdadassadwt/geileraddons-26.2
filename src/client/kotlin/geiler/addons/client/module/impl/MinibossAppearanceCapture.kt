package geiler.addons.client.module.impl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Base64
import java.util.HexFormat
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

/** Bounded, opt-in local observations for human-reviewed dungeon miniboss appearance profiles. */
class MinibossAppearanceCapture {
    companion object {
        const val MAX_RECORDS_PER_CLIENT_SESSION = 64
        private const val MAX_PROFILE_PROPERTY_CHARS = 32_768
        private const val MAX_JSON_DEPTH = 16
        private const val MAX_JSON_TOKENS = 2_048
        private const val MAX_JSON_STRING_CHARS = 4_096
        private const val MAX_JSON_LINE_BYTES = 4_096
        private const val MAX_FIELD_CHARS = 80

        @JvmStatic
        fun appearanceIdentity(profile: GameProfile?): AppearanceIdentity? {
            if (profile == null) return null
            for (property in profile.properties().get("textures")) {
                if (property == null) continue
                val identity = appearanceIdentityFromTexturePropertyValue(property.value())
                if (identity != null) return identity
            }
            return null
        }

        @JvmStatic
        fun skinSignature(profile: GameProfile?): String? = appearanceIdentity(profile)?.textureSignature

        /** Hashes only the validated stable texture URL identity; signed payloads and URLs are never emitted. */
        @JvmStatic
        fun skinSignatureFromTexturePropertyValue(encodedValue: String?): String? =
            appearanceIdentityFromTexturePropertyValue(encodedValue)?.textureSignature

        @JvmStatic
        fun appearanceIdentityFromTexturePropertyValue(encodedValue: String?): AppearanceIdentity? {
            if (encodedValue == null || DungeonMobEspSupport.javaStringIsBlank(encodedValue)
                || encodedValue.length > MAX_PROFILE_PROPERTY_CHARS
            ) return null
            try {
                val decoded = Base64.getDecoder().decode(encodedValue)
                if (decoded.size > MAX_PROFILE_PROPERTY_CHARS) return null
                val json = String(decoded, StandardCharsets.UTF_8)
                if (!hasBoundedJsonStructure(json)) return null
                val root = JsonParser.parseString(json)
                if (!root.isJsonObject) return null
                val textureSet = objectMember(root.asJsonObject, "textures")
                val skin = objectMember(textureSet, "SKIN")
                val urlElement = skin?.get("url")
                if (urlElement == null || !urlElement.isJsonPrimitive || !urlElement.asJsonPrimitive.isString) return null
                val uri = URI.create(urlElement.asString)
                val path = uri.rawPath
                if (!(("http".equals(uri.scheme, ignoreCase = true) || "https".equals(uri.scheme, ignoreCase = true))
                        && "textures.minecraft.net".equals(uri.host, ignoreCase = true)
                        && uri.rawUserInfo == null && uri.port == -1
                        && uri.rawQuery == null && uri.rawFragment == null
                        && path != null && path.matches(Regex("(?i)^/texture/[0-9a-f]{32,64}$")))
                ) return null
                val canonicalIdentity = "textures.minecraft.net" + path!!.lowercase(Locale.ROOT)
                val signature = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalIdentity.toByteArray(StandardCharsets.UTF_8)),
                )
                return AppearanceIdentity(signature, skinModel(skin))
            } catch (_: NoSuchAlgorithmException) {
                return null
            } catch (_: RuntimeException) {
                return null
            }
        }

        private fun skinModel(skin: JsonObject): String {
            val metadata = objectMember(skin, "metadata")
            val modelElement = metadata?.get("model")
            if (modelElement == null || modelElement.isJsonNull) return "classic"
            if (!modelElement.isJsonPrimitive || !modelElement.asJsonPrimitive.isString) return "unknown"
            return when (DungeonMobEspSupport.javaStringTrim(modelElement.asString).lowercase(Locale.ROOT)) {
                "slim" -> "slim"
                "", "classic", "default" -> "classic"
                else -> "unknown"
            }
        }

        /** Bounds parser depth, values, property names, and scalar lengths before building a Gson tree. */
        private fun hasBoundedJsonStructure(json: String): Boolean {
            val reader = JsonReader(StringReader(json))
            reader.isLenient = false
            val budget = JsonBudget()
            try {
                readBoundedValue(reader, 0, budget)
                return reader.peek() == JsonToken.END_DOCUMENT
            } catch (_: IOException) {
                return false
            } catch (_: RuntimeException) {
                return false
            }
        }

        @Throws(IOException::class)
        private fun readBoundedValue(reader: JsonReader, depth: Int, budget: JsonBudget) {
            budget.consume()
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    if (depth >= MAX_JSON_DEPTH) throw InvalidProfileJson()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        budget.consume()
                        val name = reader.nextName()
                        if (name.length > MAX_JSON_STRING_CHARS) throw InvalidProfileJson()
                        readBoundedValue(reader, depth + 1, budget)
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    if (depth >= MAX_JSON_DEPTH) throw InvalidProfileJson()
                    reader.beginArray()
                    while (reader.hasNext()) readBoundedValue(reader, depth + 1, budget)
                    reader.endArray()
                }
                JsonToken.STRING -> if (reader.nextString().length > MAX_JSON_STRING_CHARS) throw InvalidProfileJson()
                JsonToken.NUMBER -> if (reader.nextString().length > MAX_JSON_STRING_CHARS) throw InvalidProfileJson()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> throw InvalidProfileJson()
            }
        }

        @JvmStatic
        fun isExcludedPlayerProfile(
            localPlayerId: UUID?, targetId: UUID?, targetName: String?, listedOnline: Boolean,
            partyIds: Set<UUID?>?, partyNames: Set<String?>?,
        ): Boolean {
            if (targetId == null || listedOnline || targetId == localPlayerId) return true
            if (partyIds != null && partyIds.contains(targetId)) return true
            val normalized = normalizeName(targetName)
            if (normalized.isEmpty() || partyNames == null) return false
            for (partyName in partyNames) if (normalized == normalizeName(partyName)) return true
            return false
        }

        private fun normalizeName(value: String?): String =
            value?.let { DungeonMobEspSupport.javaStringTrim(it).lowercase(Locale.ROOT) } ?: ""

        @JvmStatic
        fun shouldMarkAutomaticallyInspected(result: RecordResult?): Boolean =
            result == RecordResult.QUEUED || result == RecordResult.DUPLICATE || result == RecordResult.LIMIT_REACHED

        private fun jsonLine(
            source: Source,
            label: String,
            profileName: String,
            entityId: Int,
            skinSignature: String,
            skinModel: String,
            gameTick: Long,
        ): String = "{\"schema\":\"geileraddons-miniboss-appearance-v2\",\"source\":\"${source.name.lowercase(Locale.ROOT)}\"," +
            "\"label\":\"${escape(label)}\",\"profileName\":\"${escape(profileName)}\"," +
            "\"entityId\":$entityId,\"skinSignature\":\"$skinSignature\"," +
            "\"skinModel\":\"$skinModel\",\"gameTick\":${gameTick.coerceAtLeast(0L)}}"

        private fun recordKey(profileId: UUID, label: String, skinSignature: String, skinModel: String): String =
            "$profileId|${label.lowercase(Locale.ROOT)}|$skinSignature|$skinModel"

        private fun objectMember(parent: JsonObject?, name: String): JsonObject? {
            if (parent == null) return null
            val child = parent.get(name)
            return if (child != null && child.isJsonObject) child.asJsonObject else null
        }

        private fun boundedText(value: String?, maxChars: Int): String {
            if (value == null) return ""
            val trimmed = DungeonMobEspSupport.javaStringTrim(value)
            return if (trimmed.length <= maxChars) trimmed else trimmed.substring(0, maxChars)
        }

        private fun escape(value: String): String {
            val result = StringBuilder(value.length + 8)
            for (character in value) {
                when (character) {
                    '"' -> result.append("\\\"")
                    '\\' -> result.append("\\\\")
                    '\b' -> result.append("\\b")
                    '\u000c' -> result.append("\\f")
                    '\n' -> result.append("\\n")
                    '\r' -> result.append("\\r")
                    '\t' -> result.append("\\t")
                    else -> if (character.code < 0x20) {
                        result.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                    } else {
                        result.append(character)
                    }
                }
            }
            return result.toString()
        }

        private fun isValidSignature(value: String?): Boolean =
            value != null && value.matches(Regex("^sha256:[0-9a-f]{64}$"))

        private fun isValidSkinModel(value: String?): Boolean = value == "classic" || value == "slim" || value == "unknown"
    }

    private val recordedKeys = LinkedHashSet<String>()
    private val inspectedAutomaticProfiles = LinkedHashSet<UUID>()

    fun record(
        source: Source?,
        label: String?,
        profile: GameProfile?,
        entityId: Int,
        skinSignature: String?,
        skinModel: String?,
        gameTick: Long,
        jsonLineSink: JsonLineSink?,
    ): RecordResult {
        if (source == null || profile == null || profile.id() == null || jsonLineSink == null
            || !isValidSignature(skinSignature) || !isValidSkinModel(skinModel)
        ) return RecordResult.INVALID
        val profileId = profile.id()!!
        val cleanLabel = boundedText(label, MAX_FIELD_CHARS)
        val profileName = boundedText(profile.name(), MAX_FIELD_CHARS)
        if (DungeonMobEspSupport.javaStringIsBlank(cleanLabel)
            || DungeonMobEspSupport.javaStringIsBlank(profileName)
        ) return RecordResult.INVALID
        val key = recordKey(profileId, cleanLabel, skinSignature!!, skinModel!!)
        if (recordedKeys.contains(key)) return RecordResult.DUPLICATE
        if (recordedKeys.size >= MAX_RECORDS_PER_CLIENT_SESSION) return RecordResult.LIMIT_REACHED
        val line = jsonLine(source, cleanLabel, profileName, entityId, skinSignature, skinModel, gameTick)
        if (line.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_LINE_BYTES) return RecordResult.INVALID
        if (!jsonLineSink.enqueue(line)) return RecordResult.QUEUE_REJECTED
        recordedKeys.add(key)
        return RecordResult.QUEUED
    }

    fun recordedCount(): Int = recordedKeys.size

    /** Releases an accepted queue reservation when the asynchronous log writer cannot flush it. */
    fun forgetUnflushedRecord(profile: GameProfile?, label: String?, skinSignature: String?, skinModel: String?): Boolean {
        if (profile == null || profile.id() == null || !isValidSignature(skinSignature)
            || !isValidSkinModel(skinModel)
        ) return false
        val cleanLabel = boundedText(label, MAX_FIELD_CHARS)
        if (DungeonMobEspSupport.javaStringIsBlank(cleanLabel)) return false
        return recordedKeys.remove(recordKey(profile.id()!!, cleanLabel, skinSignature!!, skinModel!!))
    }

    fun completeAsyncWrite(
        profile: GameProfile?, label: String?, skinSignature: String?, skinModel: String?,
        automaticCapture: Boolean, flushed: Boolean,
    ) {
        if (flushed) return
        forgetUnflushedRecord(profile, label, skinSignature, skinModel)
        if (automaticCapture && profile != null) forgetAutomaticallyInspected(profile.id())
    }

    fun wasAutomaticallyInspected(profileId: UUID?): Boolean =
        profileId != null && inspectedAutomaticProfiles.contains(profileId)

    fun markAutomaticallyInspected(profileId: UUID?) {
        if (profileId == null) return
        inspectedAutomaticProfiles.add(profileId)
        while (inspectedAutomaticProfiles.size > MAX_RECORDS_PER_CLIENT_SESSION * 4) {
            val iterator = inspectedAutomaticProfiles.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    fun forgetAutomaticallyInspected(profileId: UUID?): Boolean =
        profileId != null && inspectedAutomaticProfiles.remove(profileId)

    @JvmRecord
    data class AppearanceIdentity(val textureSignature: String, val model: String)

    enum class Source {
        CONFIRMED_PROFILE,
        CONFIRMED_NAMEPLATE,
        MANUAL_CONFIRMATION,
    }

    enum class RecordResult { QUEUED, QUEUE_REJECTED, DUPLICATE, LIMIT_REACHED, INVALID }

    fun interface JsonLineSink {
        fun enqueue(line: String): Boolean
    }

    private class JsonBudget {
        private var tokens = 0
        fun consume() {
            tokens++
            if (tokens > MAX_JSON_TOKENS) throw InvalidProfileJson()
        }
    }

    private class InvalidProfileJson : RuntimeException()
}
