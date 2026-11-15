package geiler.addons.client.pricing

import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * The recipe table of the NotEnoughUpdates item repository, downloaded as one zip (streamed, never held whole) and
 * kept as a compact JSON file next to the mod's other data so the next session starts without a download.
 */
object NeuRecipeStore {
    const val URL = "https://codeload.github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/zip/refs/heads/master"
    const val MAX_RECIPES = 60_000
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 1 shl 20
    private val PATH: Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("neu-recipes.json")

    class Loaded(val recipes: Map<String, Recipe>, val ageMillis: Long)

    /** The saved table and how old it is, or null when there is none or it is unreadable. */
    @JvmStatic
    fun readCache(): Loaded? {
        val result = ClientJsonFile.readResult(PATH, JsonObject::class.java)
        val root = result.value ?: return null
        if (!result.valid() || root.get("version")?.asInt != 1) return null
        val recipes = decode(root.getAsJsonObject("recipes") ?: return null) ?: return null
        val age = try {
            System.currentTimeMillis() - Files.getLastModifiedTime(PATH).toMillis()
        } catch (_: IOException) {
            Long.MAX_VALUE
        }
        return Loaded(recipes, age)
    }

    @JvmStatic
    fun decode(json: JsonObject): Map<String, Recipe>? {
        if (json.size() > MAX_RECIPES) return null
        val result = HashMap<String, Recipe>(json.size() * 2)
        for ((id, value) in json.entrySet()) {
            if (!value.isJsonObject) continue
            val entry = value.asJsonObject
            val output = entry.get("o")?.asDouble ?: continue
            val inputs = LinkedHashMap<String, Double>()
            for ((input, amount) in entry.getAsJsonObject("i")?.entrySet() ?: continue) inputs[input] = amount.asDouble
            if (output > 0 && inputs.isNotEmpty()) result[id] = Recipe(output, inputs)
        }
        return result
    }

    @JvmStatic
    fun encode(recipes: Map<String, Recipe>): JsonObject {
        val json = JsonObject()
        for ((id, recipe) in recipes) {
            val entry = JsonObject()
            entry.addProperty("o", recipe.output)
            val inputs = JsonObject()
            for ((input, amount) in recipe.inputs) inputs.addProperty(input, amount)
            entry.add("i", inputs)
            json.add(id, entry)
        }
        return json
    }

    fun writeCache(recipes: Map<String, Recipe>) {
        val root = JsonObject()
        root.addProperty("version", 1)
        root.add("recipes", encode(recipes))
        ClientJsonFile.writeAsync(PATH, root.toString())
    }

    /** Downloads and parses the repository. Runs on a worker thread; throws on any failure. */
    fun download(client: HttpClient): Map<String, Recipe> {
        val request = HttpRequest.newBuilder(URI.create(URL))
            .timeout(Duration.ofMinutes(3)).header("User-Agent", GeilerAddons.MOD_ID).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
        return response.body().use { parse(it) }
    }

    /** Reads every json entry under items/ of the repository zip in [stream]. */
    @JvmStatic
    fun parse(stream: InputStream): Map<String, Recipe> {
        val recipes = HashMap<String, Recipe>()
        ZipInputStream(CappedInputStream(stream, MAX_ZIP_BYTES)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || !name.endsWith(".json") || !name.contains("/items/")) continue
                val bytes = zip.readNBytes(MAX_ENTRY_BYTES)
                NeuRecipes.parseItem(String(bytes, Charsets.UTF_8))?.let { (id, recipe) ->
                    if (recipes.size < MAX_RECIPES) recipes[id] = recipe
                }
            }
        }
        return recipes
    }

    private class CappedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var read = 0L

        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++read > limit) throw IOException("download too large")
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                read += count
                if (read > limit) throw IOException("download too large")
            }
            return count
        }
    }
}
