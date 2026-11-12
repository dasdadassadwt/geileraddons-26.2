package geiler.addons.client.config

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Instant
import java.util.ArrayDeque
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Pure, bounded archive codec for portable GeilerAddons profiles. */
object ConfigProfileCodecSupport {
    const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_ICON_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    const val MAX_JSON_DEPTH = 160
    const val MAX_JSON_NODES = 100_000
    const val MAX_JSON_TOKENS = 200_000

    private const val MAX_JSON_STRING_CHARS = 1_048_576
    private const val FORMAT = "geileraddons-config"
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val localOnlyFiles: Set<String> = setOf(
        "chat-channels-local.json",
        "pest-hourly-stats.json",
        "slayer-hourly-stats.json",
        "player-presentations-cache.json",
        "player-sizes.json",
        "dungeon-split-personal-bests.json",
        "dungeon-puzzle-personal-bests.json",
        "lobby-sniper-targets.json",
        "miniboss-appearance-signatures.json",
    )
    private val transferableFiles: Set<String> = setOf(
        "scoreboard/layout.json",
        "inventory-controls/controls.json",
        "secret-waypoints.json",
    )

    /** Client-local credentials, counters, caches, and user fonts stay outside portable profiles. */
    @JvmStatic
    fun isLocalOnlyPath(path: String?): Boolean =
        path != null && (localOnlyFiles.contains(path) || path.startsWith("scoreboard/fonts/"))

    /** True only for a validated archive path that is allowed to move between client profiles. */
    @JvmStatic
    fun isTransferablePath(path: String?): Boolean = safePath(path) && !isLocalOnlyPath(path)

    @JvmStatic
    @Throws(IOException::class)
    fun encode(files: Map<String?, ByteArray?>?): ByteArray {
        if (files == null || files.isEmpty() || !files.containsKey("config.json")) {
            throw IOException("Profile payload is missing config.json or exceeds the entry limit.")
        }
        val portableFiles = LinkedHashMap<String?, ByteArray?>()
        for ((path, data) in files) {
            if (isLocalOnlyPath(path)) {
                if (!safePath(path)) throw IOException("Profile contains an unsafe local-only path.")
            } else {
                portableFiles[path] = data
            }
        }
        if (portableFiles.size > MAX_ENTRIES) throw IOException("Profile payload exceeds the entry limit.")

        val manifest = ProfileManifest().apply {
            format = FORMAT
            version = 1
            createdAt = Instant.now().toString()
            entries = ArrayList()
        }
        val foldedPaths = HashSet<String>()
        for ((nullablePath, nullableData) in portableFiles) {
            val path = nullablePath ?: throw IOException("Profile contains an unsafe, duplicate, or oversized entry.")
            val data = nullableData
            if (!safePath(path) || !foldedPaths.add(path.lowercase(Locale.ROOT)) || data == null || data.size > MAX_ENTRY_BYTES) {
                throw IOException("Profile contains an unsafe, duplicate, or oversized entry.")
            }
            if (isIconPath(path)) validateIconPng(data)
            val entryMeta = ProfileEntry().apply {
                this.path = path
                size = data.size.toLong()
                sha256 = sha256(data)
            }
            manifest.entries!!.add(entryMeta)
            manifest.totalBytes += data.size
            if (manifest.totalBytes > MAX_TOTAL_BYTES) throw IOException("Profile exceeds the expanded-size limit.")
        }

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            put(zip, "manifest.json", gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8))
            for ((nullablePath, data) in portableFiles) {
                val path = nullablePath ?: throw IOException("Profile contains an unsafe, duplicate, or oversized entry.")
                put(zip, path, data ?: throw IOException("Profile contains a missing entry body."))
            }
        }
        val result = bytes.toByteArray()
        if (result.size > MAX_ARCHIVE_BYTES) throw IOException("Profile archive exceeds the compressed-size limit.")
        return result
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(archive: ByteArray?): ConfigProfileCodec.Archive {
        if (archive == null || archive.isEmpty() || archive.size > MAX_ARCHIVE_BYTES) {
            throw IOException("Archive size is outside the limit.")
        }
        val files = LinkedHashMap<String, ByteArray>()
        var manifestBytes: ByteArray? = null
        var total = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !safeName(name)) throw IOException("Unsafe archive path: $name")
                val contents = readBounded(zip, MAX_ENTRY_BYTES)
                if (name == "manifest.json") {
                    if (manifestBytes != null) throw IOException("Duplicate archive manifest.")
                    manifestBytes = contents
                } else {
                    if (!safePath(name)) throw IOException("Unrecognized persisted profile path: $name")
                    if (isIconPath(name)) validateIconPng(contents)
                    if (files.putIfAbsent(name, contents) != null) throw IOException("Duplicate archive member: $name")
                    total += contents.size
                    if (total > MAX_TOTAL_BYTES || files.size > MAX_ENTRIES) {
                        throw IOException("Archive exceeds entry or expanded-size limits.")
                    }
                }
            }
        }
        val checkedManifestBytes = manifestBytes ?: throw IOException("Archive manifest is missing.")
        validateJsonBounds(checkedManifestBytes)
        val manifest = try {
            gson.fromJson(String(checkedManifestBytes, StandardCharsets.UTF_8), ProfileManifest::class.java)
        } catch (invalid: RuntimeException) {
            throw IOException("Archive manifest is invalid.", invalid)
        } catch (invalid: StackOverflowError) {
            throw IOException("Archive manifest is invalid.", invalid)
        }
        if (manifest == null || FORMAT != manifest.format || manifest.version != 1 || manifest.createdAt == null ||
            manifest.entries == null || manifest.entries!!.size != files.size || manifest.entries!!.size > MAX_ENTRIES ||
            manifest.totalBytes != total || !files.containsKey("config.json")
        ) {
            throw IOException("Unsupported or incomplete profile manifest.")
        }
        try {
            Instant.parse(manifest.createdAt)
        } catch (invalid: RuntimeException) {
            throw IOException("Profile timestamp is invalid.", invalid)
        }

        val listed = HashSet<String>()
        val foldedPaths = HashSet<String>()
        for (nullableMeta in manifest.entries!!) {
            val meta = nullableMeta ?: throw IOException("Manifest has an invalid or duplicate entry.")
            val path = meta.path ?: throw IOException("Manifest has an invalid or duplicate entry.")
            if (!safePath(path) || !listed.add(path) ||
                !foldedPaths.add(path.lowercase(Locale.ROOT)) || meta.sha256 == null ||
                !SHA_256_PATTERN.matches(meta.sha256!!)
            ) {
                throw IOException("Manifest has an invalid or duplicate entry.")
            }
            val contents = files[path]
            if (contents == null || meta.size != contents.size.toLong() || sha256(contents) != meta.sha256) {
                throw IOException("Entry checksum or size mismatch: $path")
            }
        }
        if (listed != files.keys) throw IOException("Archive has unlisted or omitted payload members.")
        for ((path, contents) in files) if (path.endsWith(".json")) validateJsonBounds(contents)
        val portableFiles = LinkedHashMap(files)
        portableFiles.keys.removeIf(::isLocalOnlyPath)
        val portableTotal = portableFiles.values.sumOf { it.size.toLong() }
        return ConfigProfileCodec.Archive(portableFiles, manifest.createdAt!!, portableTotal)
    }

    /** Bounded preflight before Gson's recursive tree parser sees untrusted JSON. */
    @JvmStatic
    @Throws(IOException::class)
    fun validateJsonBounds(json: ByteArray?) {
        if (json == null || json.isEmpty() || json.size > MAX_ENTRY_BYTES) {
            throw IOException("JSON member is empty or too large.")
        }
        var depth = 0
        var maxDepth = 0
        var stringChars = 0
        var inString = false
        var escaped = false
        for (raw in json) {
            val ch = raw.toInt() and 0xff
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\'.code) {
                    escaped = true
                    continue
                }
                if (ch == '"'.code) {
                    inString = false
                    continue
                }
                if (++stringChars > MAX_JSON_STRING_CHARS) throw IOException("JSON string exceeds the supported limit.")
                continue
            }
            if (ch == '"'.code) {
                inString = true
                stringChars = 0
            } else if (ch == '{'.code || ch == '['.code) {
                maxDepth = maxOf(maxDepth, ++depth)
                if (maxDepth > MAX_JSON_DEPTH) throw IOException("JSON nesting exceeds the supported limit.")
            } else if (ch == '}'.code || ch == ']'.code) {
                if (--depth < 0) throw IOException("JSON nesting is malformed.")
            }
        }
        if (inString || depth != 0) throw IOException("JSON string or nesting is unterminated.")
        validateJsonStructure(json)
    }

    /** Streams each token before any Gson tree allocation and enforces explicit structural budgets. */
    @Throws(IOException::class)
    private fun validateJsonStructure(json: ByteArray) {
        try {
            JsonReader(InputStreamReader(ByteArrayInputStream(json), StandardCharsets.UTF_8)).use { reader ->
                reader.isLenient = false
                val closing = ArrayDeque<JsonToken>()
                var rootStarted = false
                var rootComplete = false
                var nodes = 0
                var tokens = 0
                while (true) {
                    val token = reader.peek()
                    if (token == JsonToken.END_DOCUMENT) {
                        if (++tokens > MAX_JSON_TOKENS) throw IOException("JSON exceeds the supported token limit.")
                        if (!rootComplete || closing.isNotEmpty()) throw IOException("JSON has no complete root value.")
                        return
                    }
                    if (++tokens > MAX_JSON_TOKENS) throw IOException("JSON exceeds the supported token limit.")
                    val atRoot = closing.isEmpty()
                    when (token) {
                        JsonToken.BEGIN_ARRAY -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.beginArray()
                            closing.push(JsonToken.END_ARRAY)
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.beginObject()
                            closing.push(JsonToken.END_OBJECT)
                        }
                        JsonToken.END_ARRAY, JsonToken.END_OBJECT -> {
                            if (closing.isEmpty() || closing.peek() != token) throw IOException("JSON nesting is malformed.")
                            if (token == JsonToken.END_ARRAY) reader.endArray() else reader.endObject()
                            closing.pop()
                            if (closing.isEmpty()) rootComplete = true
                        }
                        JsonToken.NAME -> {
                            if (closing.isEmpty() || closing.peek() != JsonToken.END_OBJECT) {
                                throw IOException("JSON object name is outside an object.")
                            }
                            reader.nextName()
                        }
                        JsonToken.STRING -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                                rootComplete = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.nextString()
                        }
                        JsonToken.NUMBER -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                                rootComplete = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.nextString()
                        }
                        JsonToken.BOOLEAN -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                                rootComplete = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.nextBoolean()
                        }
                        JsonToken.NULL -> {
                            if (atRoot) {
                                if (rootStarted) throw IOException("JSON contains more than one root value.")
                                rootStarted = true
                                rootComplete = true
                            }
                            if (++nodes > MAX_JSON_NODES) throw IOException("JSON exceeds the supported node limit.")
                            reader.nextNull()
                        }
                        else -> throw IOException("JSON contains an unsupported token.")
                    }
                }
            }
        } catch (invalid: IllegalStateException) {
            throw IOException("JSON structure is invalid.", invalid)
        } catch (invalid: NumberFormatException) {
            throw IOException("JSON structure is invalid.", invalid)
        }
    }

    /** Checks PNG allocation bounds from the fixed header before any decoder sees imported icon data. */
    @JvmStatic
    @Throws(IOException::class)
    fun validateIconPng(png: ByteArray?) {
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        if (png == null || png.size < 24 || png.size > MAX_ICON_BYTES) {
            throw IOException("PNG icon is empty, truncated, or exceeds the 4 MiB limit.")
        }
        for (index in signature.indices) if (png[index] != signature[index]) throw IOException("Icon does not have a PNG signature.")
        if (unsignedInt(png, 8) != 13L || png[12] != 'I'.code.toByte() || png[13] != 'H'.code.toByte() ||
            png[14] != 'D'.code.toByte() || png[15] != 'R'.code.toByte()
        ) {
            throw IOException("PNG icon is missing the required IHDR header.")
        }
        val width = unsignedInt(png, 16)
        val height = unsignedInt(png, 20)
        if (width < 1 || height < 1 || width > 1024 || height > 1024) {
            throw IOException("PNG icon dimensions exceed the 1024×1024 limit.")
        }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)

    @JvmStatic
    fun safeName(name: String?): Boolean {
        if (name == null || name.isBlank() || name.length > 240 || name.startsWith("/") || '\\' in name || ':' in name) return false
        var start = 0
        while (true) {
            val slash = name.indexOf('/', start)
            val part = if (slash < 0) name.substring(start) else name.substring(start, slash)
            if (part.isBlank() || part == "." || part == "..") return false
            if (slash < 0) return true
            start = slash + 1
        }
    }

    /** Exact allowlist for active config, owned split data, and known migration archives. */
    @JvmStatic
    fun safePath(path: String?): Boolean {
        if (!safeName(path) || path!!.startsWith(".")) return false
        if (path == "config.json" || path == "config.before-split.json" || path == "macro-index.json" ||
            isLocalOnlyPath(path) || transferableFiles.contains(path) || path == "dungeon-guides/index.json" ||
            path == "dungeon-guides.json" || path == "dungeon-guides/archive/legacy-v1/index.json" ||
            path == "dungeon-guides/archive/legacy-v1/dungeon-guides.json"
        ) return true
        if (path.startsWith("macros/")) return MACRO_PATH.matches(path)
        if (path.startsWith("functions/")) return FUNCTION_PATH.matches(path)
        if (DUNGEON_ROUTE_PATH.matches(path)) return true
        if (path.startsWith("dungeon-guides/archive/legacy-v1/routes/")) return LEGACY_DUNGEON_ROUTE_PATH.matches(path)
        return isIconPath(path)
    }

    private fun isIconPath(path: String?): Boolean {
        if (path == null || !path.startsWith("inventory-button-icons/")) return false
        val filename = path.substring("inventory-button-icons/".length)
        return filename.isNotBlank() && filename.length <= 216 && '/' !in filename &&
            filename.lowercase(Locale.ROOT).endsWith(".png") && safeName(filename)
    }

    @Throws(IOException::class)
    private fun readBounded(input: ZipInputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            if (size > limit) throw IOException("Archive entry exceeds the size limit.")
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }

    @Throws(IOException::class)
    private fun put(zip: ZipOutputStream, name: String, value: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value)
        zip.closeEntry()
    }

    private fun sha256(value: ByteArray): String = try {
        java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value))
    } catch (impossible: NoSuchAlgorithmException) {
        throw IllegalStateException(impossible)
    }

    private class ProfileManifest {
        var format: String? = null
        var version: Int = 0
        var createdAt: String? = null
        var totalBytes: Long = 0
        var entries: MutableList<ProfileEntry?>? = null
    }

    private class ProfileEntry {
        var path: String? = null
        var size: Long = 0
        var sha256: String? = null
    }

    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    private val MACRO_PATH = Regex("macros/[A-Za-z0-9._-]{1,48}-[0-9]+\\.json")
    private val FUNCTION_PATH = Regex("functions/[A-Za-z0-9._-]{1,48}-[A-Za-z0-9._-]{1,48}-[0-9a-z]+\\.json")
    private val DUNGEON_ROUTE_PATH = Regex("dungeon-guides/(?:Entrance|F[1-7]|M[1-7])/[A-Z][A-Z0-9_]{0,31}/[a-z0-9._-]{1,48}-[0-9a-z]+\\.json")
    private val LEGACY_DUNGEON_ROUTE_PATH = Regex("dungeon-guides/archive/legacy-v1/routes/(?:Entrance|F[1-7]|M[1-7])/[A-Z][A-Z0-9_]{0,31}/[a-z0-9._-]{1,48}-[0-9a-z]+\\.json")
}
