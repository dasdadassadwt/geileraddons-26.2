package geiler.addons.client.dungeon.odin

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashMap

/** Bounded loading and validation for the pinned Boulder solution catalog. */
internal object DungeonBoulderCatalogCore {
    private const val RESOURCE = "/data/dungeon-boulder-solutions.json"
    private const val MAX_BYTES = 16 * 1024
    private const val SIGNATURE_LENGTH = 42
    private const val MAX_PATTERNS = 64
    private const val MAX_STEPS = 32

    fun loadBundled(): DungeonBoulderCatalog.CatalogResult {
        try {
            val input = DungeonBoulderCatalog::class.java.getResourceAsStream(RESOURCE)
                ?: return unavailable("Built-in Boulder signatures are missing")
            input.use {
                val bytes = it.readNBytes(MAX_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > MAX_BYTES)
                    return unavailable("Boulder catalog is empty or exceeds its size limit")
                return parse(String(bytes, StandardCharsets.UTF_8))
            }
        } catch (error: IOException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun parse(json: String?): DungeonBoulderCatalog.CatalogResult {
        if (json == null || json.all { Character.isWhitespace(it) } || json.length > MAX_BYTES)
            return unavailable("Boulder catalog is empty or exceeds its size limit")
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return unavailable("Boulder catalog root is not an object")
            val root = rootElement.asJsonObject
            if (root.size() == 0 || root.size() > MAX_PATTERNS)
                return unavailable("Boulder pattern count is outside its supported range")
            val solutions = HashMap<String, List<DungeonBoulderCatalog.Target>>()
            for (entry in root.entrySet()) {
                val signature = entry.key
                val value = entry.value
                if (!validSignature(signature) || value == null || !value.isJsonArray)
                    return unavailable("Boulder catalog contains an invalid signature")
                val sourceTargets = value.asJsonArray
                if (sourceTargets.isEmpty || sourceTargets.size() > MAX_STEPS)
                    return unavailable("Boulder catalog contains an invalid target list")
                val targets = ArrayList<DungeonBoulderCatalog.Target>(sourceTargets.size())
                for (targetElement in sourceTargets) {
                    if (!targetElement.isJsonArray || targetElement.asJsonArray.size() != 4)
                        return unavailable("Boulder target does not contain four coordinates")
                    val values = targetElement.asJsonArray
                    val renderX = coordinate(values[0])
                    val renderZ = coordinate(values[1])
                    val clickX = coordinate(values[2])
                    val clickZ = coordinate(values[3])
                    if (renderX == null || renderZ == null || clickX == null || clickZ == null ||
                        renderX < 0 || renderX >= 31 || renderZ < 0 || renderZ >= 31 ||
                        clickX < 0 || clickX >= 31 || clickZ < 0 || clickZ >= 31
                    ) return unavailable("Boulder target coordinate is outside the room")
                    targets.add(DungeonBoulderCatalog.Target(renderX, renderZ, clickX, clickZ))
                }
                if (solutions.putIfAbsent(signature, java.util.List.copyOf(targets)) != null)
                    return unavailable("Boulder catalog contains a duplicate signature")
            }
            return DungeonBoulderCatalog.CatalogResult(true, solutions, "")
        } catch (error: RuntimeException) {
            return unavailable(error.message ?: error.javaClass.simpleName)
        }
    }

    fun targets(
        catalog: DungeonBoulderCatalog.CatalogResult,
        signature: String?,
    ): List<DungeonBoulderCatalog.Target> =
        if (catalog.available() && signature != null) catalog.solutions().getOrDefault(signature, java.util.List.of())
        else java.util.List.of()

    private fun validSignature(value: String?): Boolean =
        value != null && value.length == SIGNATURE_LENGTH && value.all { it == '0' || it == '1' }

    private fun coordinate(value: JsonElement?): Int? {
        if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        return try {
            value.asBigDecimal.intValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun unavailable(reason: String): DungeonBoulderCatalog.CatalogResult =
        DungeonBoulderCatalog.CatalogResult(false, java.util.Map.of(), reason)
}
