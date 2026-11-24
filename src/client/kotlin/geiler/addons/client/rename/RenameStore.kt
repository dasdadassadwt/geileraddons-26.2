package geiler.addons.client.rename

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Any Rename overrides: one map by Hypixel item uuid and one by SkyBlock id. Reads are hash lookups on the client thread;
 * writes happen on the client thread too, and saving runs on a single background thread from a JSON snapshot.
 */
class RenameStore {
    private val byUuid = LinkedHashMap<String, RenameOverride>()
    private val byType = LinkedHashMap<String, RenameOverride>()

    /** Cheap gate for the packet hooks: false means no override exists, so nothing is touched. */
    @Volatile
    var any = false
        private set

    fun size(): Int = byUuid.size + byType.size

    /** The override for an item: its own uuid first, then the rule for its SkyBlock id. */
    fun lookup(uuid: String, skyId: String): RenameOverride? {
        if (!any) return null
        if (uuid.isNotEmpty()) byUuid[uuid]?.let { return it }
        if (skyId.isNotEmpty()) byType[skyId]?.let { return it }
        return null
    }

    /** Stores [override] under the uuid, or under the SkyBlock id when it is a type rule. An empty override removes the entry. */
    fun put(uuid: String, skyId: String, override: RenameOverride) {
        val map = if (override.allOfType) byType else byUuid
        val key = if (override.allOfType) skyId else uuid
        if (key.isEmpty()) return
        map.remove(key)
        if (!override.isEmpty()) {
            map[key] = override
            while (size() > MAX_ENTRIES) {
                val victim = if (byUuid.isNotEmpty()) byUuid else byType
                victim.remove(victim.keys.first())
            }
        }
        any = size() > 0
    }

    fun remove(uuid: String, skyId: String, type: Boolean) {
        if (type) byType.remove(skyId) else byUuid.remove(uuid)
        any = size() > 0
    }

    fun clear() {
        byUuid.clear()
        byType.clear()
        any = false
    }

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("version", 1)
        val uuids = JsonObject()
        byUuid.forEach { (k, v) -> uuids.add(k, v.toJson()) }
        val types = JsonObject()
        byType.forEach { (k, v) -> types.add(k, v.toJson()) }
        root.add("uuid", uuids)
        root.add("type", types)
        return GSON.toJson(root)
    }

    /** Replaces the contents from [file]; a missing or corrupt file leaves the store empty. */
    fun load(file: Path) {
        clear()
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return
            val root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
            if (!root.isJsonObject) return
            readMap(root.asJsonObject.get("uuid"), byUuid, false)
            readMap(root.asJsonObject.get("type"), byType, true)
        } catch (_: IOException) {
            clear()
        } catch (_: RuntimeException) {
            clear()
        }
        any = size() > 0
    }

    private fun readMap(element: com.google.gson.JsonElement?, into: MutableMap<String, RenameOverride>, type: Boolean) {
        if (element == null || !element.isJsonObject) return
        for ((key, value) in element.asJsonObject.entrySet()) {
            if (!value.isJsonObject || key.isEmpty() || key.length > 128 || size() >= MAX_ENTRIES) continue
            val o = RenameOverride.fromJson(value.asJsonObject)
            o.allOfType = type
            if (!o.isEmpty()) into[key] = o
        }
    }

    /** Writes the file synchronously (checks and shutdown); the game uses [saveAsync]. */
    fun save(file: Path) = write(file, toJson())

    /** Snapshots now on the calling thread, writes later on the saver thread. */
    fun saveAsync(file: Path) {
        val json = toJson()
        SAVER.execute { write(file, json) }
    }

    companion object {
        const val MAX_ENTRIES = 2048
        const val MAX_SHARE_CHARS = 16384
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val SHARE_PREFIX = "GAR1:"
        private val GSON = GsonBuilder().setPrettyPrinting().create()
        private val SAVER = Executors.newSingleThreadExecutor { r -> Thread(r, "GeilerAddons-AnyRename-Save").also { it.isDaemon = true } }

        /** The live store used by the game. */
        @JvmField
        val INSTANCE = RenameStore()

        @JvmStatic
        fun file(): Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("any-rename.json")

        private fun write(file: Path, json: String) {
            try {
                Files.createDirectories(file.parent)
                val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
                Files.writeString(tmp, json, StandardCharsets.UTF_8)
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                geiler.addons.GeilerAddons.LOGGER.warn("[Any Rename] could not save {}: {}", file, e.toString())
            }
        }

        /** A shareable string for one override. */
        @JvmStatic
        fun export(o: RenameOverride): String =
            SHARE_PREFIX + Base64.getEncoder().encodeToString(o.toJson().toString().toByteArray(StandardCharsets.UTF_8))

        /** The override in a share string, or null when it is not a valid `GAR1:` string. */
        @JvmStatic
        fun importOverride(text: String): RenameOverride? {
            val s = text.trim()
            if (!s.startsWith(SHARE_PREFIX) || s.length > MAX_SHARE_CHARS) return null
            return try {
                val json = JsonParser.parseString(String(Base64.getDecoder().decode(s.substring(SHARE_PREFIX.length)), StandardCharsets.UTF_8))
                if (json.isJsonObject) RenameOverride.fromJson(json.asJsonObject) else null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
