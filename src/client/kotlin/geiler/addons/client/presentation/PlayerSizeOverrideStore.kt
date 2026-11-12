package geiler.addons.client.presentation

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord

/** Local, editable per-player size overrides; remote profile data is stored separately. */
class PlayerSizeOverrideStore private constructor(private val storagePath: Path?) {
    @Volatile
    private var entries: Map<String, Entry> = emptyMap()
    private var loaded = false

    @Synchronized
    fun entries(): List<Entry> {
        loadIfNeeded()
        return entries.values.sortedBy { normalize(it.playerName) }
    }

    @Synchronized
    fun find(playerName: String?): Entry? {
        loadIfNeeded()
        return if (playerName == null) null else entries[normalize(playerName.trim { it <= ' ' })]
    }

    @Synchronized
    fun put(playerName: String?, size: Float, enabled: Boolean): Boolean =
        put(playerName, size, size, size, enabled)

    @Synchronized
    fun put(playerName: String?, sizeX: Float, sizeY: Float, sizeZ: Float, enabled: Boolean): Boolean {
        loadIfNeeded()
        val name = playerName?.trim { it <= ' ' } ?: ""
        if (!USERNAME.matcher(name).matches()
            || !validSize(sizeX.toDouble())
            || !validSize(sizeY.toDouble())
            || !validSize(sizeZ.toDouble())
        ) return false

        val key = normalize(name)
        if (!entries.containsKey(key) && entries.size >= MAX_ENTRIES) return false
        val updated = LinkedHashMap(entries)
        updated[key] = Entry(name, sizeX, sizeY, sizeZ, enabled)
        commit(updated)
        return true
    }

    @Synchronized
    fun remove(playerName: String?): Boolean {
        loadIfNeeded()
        if (playerName == null) return false
        val key = normalize(playerName.trim { it <= ' ' })
        if (!entries.containsKey(key)) return false
        val updated = LinkedHashMap(entries)
        updated.remove(key)
        commit(updated)
        return true
    }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val file = storagePath ?: path()
        try {
            if (!Files.isRegularFile(file)) return
            val size = Files.size(file)
            if (size <= 0 || size > MAX_BYTES) throw IOException("size override file exceeds the limit")
            val parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            if (!parsed.isJsonObject) throw IOException("root must be an object")

            val root = parsed.asJsonObject
            val schemaElement = root.get("schemaVersion")
            val playersElement = root.get("players")
            if (root.size() != 2 || !root.has("schemaVersion") || !root.has("players")
                || !schemaElement.isJsonPrimitive || !schemaElement.asJsonPrimitive.isNumber
                || (schemaElement.asInt != 1 && schemaElement.asInt != 2)
                || (schemaElement.asDouble != 1.0 && schemaElement.asDouble != 2.0)
                || !playersElement.isJsonArray
            ) throw IOException("unsupported size override format")

            val schemaVersion = schemaElement.asInt
            val array = root.getAsJsonArray("players")
            if (array.size() > MAX_ENTRIES) throw IOException("too many player overrides")
            val loadedEntries = LinkedHashMap<String, Entry>()
            for (value in array) {
                if (!value.isJsonObject) throw IOException("player override must be an object")
                val objectValue = value.asJsonObject
                val scalar = schemaVersion == 1 && objectValue.size() == 3
                    && objectValue.has("name") && objectValue.has("size") && objectValue.has("enabled")
                val axes = schemaVersion == 2 && objectValue.size() == 5
                    && objectValue.has("name") && objectValue.has("sizeX") && objectValue.has("sizeY")
                    && objectValue.has("sizeZ") && objectValue.has("enabled")
                if (!scalar && !axes) throw IOException("player override fields are invalid")

                val nameValue = objectValue.get("name")
                val sizeXValue = objectValue.get(if (scalar) "size" else "sizeX")
                val sizeYValue = if (scalar) sizeXValue else objectValue.get("sizeY")
                val sizeZValue = if (scalar) sizeXValue else objectValue.get("sizeZ")
                val enabledValue = objectValue.get("enabled")
                if (!nameValue.isJsonPrimitive || !nameValue.asJsonPrimitive.isString
                    || !isNumber(sizeXValue) || !isNumber(sizeYValue) || !isNumber(sizeZValue)
                    || !enabledValue.isJsonPrimitive || !enabledValue.asJsonPrimitive.isBoolean
                ) throw IOException("player override value types are invalid")

                val name = nameValue.asString
                val sizeXParsed = sizeXValue!!.asDouble
                val sizeYParsed = sizeYValue!!.asDouble
                val sizeZParsed = sizeZValue!!.asDouble
                if (!USERNAME.matcher(name).matches() || !validSize(sizeXParsed)
                    || !validSize(sizeYParsed) || !validSize(sizeZParsed)
                ) throw IOException("player override value is outside the allowed range")

                val key = normalize(name)
                val entry = Entry(
                    name,
                    sizeXParsed.toFloat(),
                    sizeYParsed.toFloat(),
                    sizeZParsed.toFloat(),
                    enabledValue.asBoolean,
                )
                if (loadedEntries.putIfAbsent(key, entry) != null) {
                    throw IOException("duplicate player override")
                }
            }
            entries = java.util.Map.copyOf(loadedEntries)
        } catch (error: IOException) {
            warnInvalid(error)
        } catch (error: RuntimeException) {
            warnInvalid(error)
        }
    }

    private fun commit(updated: Map<String, Entry>) {
        entries = java.util.Map.copyOf(updated)
        val root = JsonObject()
        root.addProperty("schemaVersion", 2)
        val players = JsonArray()
        updated.values.sortedBy { normalize(it.playerName) }.forEach { entry ->
            val objectValue = JsonObject()
            objectValue.addProperty("name", entry.playerName)
            objectValue.addProperty("sizeX", entry.sizeX)
            objectValue.addProperty("sizeY", entry.sizeY)
            objectValue.addProperty("sizeZ", entry.sizeZ)
            objectValue.addProperty("enabled", entry.enabled)
            players.add(objectValue)
        }
        root.add("players", players)
        ClientJsonFile.writeAsync(storagePath ?: path(), ClientJsonFile.GSON.toJson(root))
    }

    private fun warnInvalid(error: Exception) {
        GeilerAddons.LOGGER.warn("Ignoring invalid local player size overrides: {}", error.message)
    }

    @JvmRecord
    data class Entry(
        val playerName: String,
        val sizeX: Float,
        val sizeY: Float,
        val sizeZ: Float,
        val enabled: Boolean,
    ) {
        constructor(playerName: String, size: Float, enabled: Boolean) :
            this(playerName, size, size, size, enabled)

        /** Compatibility accessor for pre-axis callers. */
        fun size(): Float = sizeX
    }

    companion object {
        @JvmField
        val INSTANCE = PlayerSizeOverrideStore(null)

        const val MAX_ENTRIES = 128
        private const val MAX_BYTES = 32 * 1024
        private val USERNAME = ChatStringSemantics.MINECRAFT_USERNAME

        private fun path(): Path =
            FabricLoader.getInstance().configDir.resolve("geileraddons/player-sizes.json")

        private fun normalize(name: String): String = name.lowercase(Locale.ROOT)

        private fun validSize(size: Double): Boolean =
            java.lang.Double.isFinite(size) && size >= 0.1 && size <= 4.0

        private fun isNumber(value: JsonElement?): Boolean =
            value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber
    }
}
