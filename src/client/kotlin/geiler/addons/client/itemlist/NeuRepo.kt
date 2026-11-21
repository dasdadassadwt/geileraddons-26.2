package geiler.addons.client.itemlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import geiler.addons.GeilerAddons
import geiler.addons.client.pricing.NeuRecipeStore
import net.fabricmc.loader.api.FabricLoader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

/**
 * The NotEnoughUpdates item repository compiled once: every item file under `items/` plus the essence costs, streamed out of the
 * repository zip and kept as one gzip-compressed JSON file so the next session starts without a download. The cache is
 * larger than the 1 MB limit of [geiler.addons.client.dungeon.ClientJsonFile], so it is written here directly.
 */
object NeuRepo {
    const val VERSION = 1
    const val MAX_ITEMS = 60_000
    private const val MAX_ZIP_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 1 shl 20
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    private const val COMMIT_URL = "https://api.github.com/repos/NotEnoughUpdates/NotEnoughUpdates-REPO/commits/master"
    private val PATH: Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("neu-item-list.json.gz")
    private val COMMIT_PATH: Path = PATH.resolveSibling("neu-item-list.commit")
    private val SHA = Regex("[0-9a-f]{40}")

    class Repo(val entries: List<Entry>, val recipes: List<ItemRecipe>)

    /** The saved repository, how old it is and the commit it was built from (null when unknown). */
    class Loaded(val repo: Repo, val ageMillis: Long, val commit: String?)

    /** Streams the repository zip in [stream]; broken item files are skipped. */
    @JvmStatic
    fun parse(stream: InputStream): Repo {
        val entries = ArrayList<Entry>()
        val recipes = ArrayList<ItemRecipe>()
        ZipInputStream(NeuRecipeStore.CappedInputStream(stream, MAX_ZIP_BYTES)).use { zip ->
            while (true) {
                val file = zip.nextEntry ?: break
                val name = file.name
                if (file.isDirectory || !name.endsWith(".json")) continue
                val item = name.contains("/items/")
                if (!item && !name.endsWith("/constants/essencecosts.json")) continue
                val text = String(zip.readNBytes(MAX_ENTRY_BYTES), Charsets.UTF_8)
                if (item) {
                    if (entries.size >= MAX_ITEMS) continue
                    ItemListData.parseItem(text)?.let {
                        entries.add(it.entry)
                        recipes.addAll(it.recipes)
                    }
                } else {
                    recipes.addAll(ItemListData.parseEssence(text))
                }
            }
        }
        return Repo(entries, recipes)
    }

    /** A commit id from [text], or null when it is not one. */
    @JvmStatic
    fun parseSha(text: String): String? = text.trim().lowercase().takeIf { SHA.matches(it) }

    /** The commit the repository's master branch is at now, from one tiny request; null when it cannot be read. */
    fun latestCommit(client: HttpClient): String? = try {
        val request = HttpRequest.newBuilder(URI.create(COMMIT_URL))
            .timeout(Duration.ofSeconds(10)).header("User-Agent", GeilerAddons.MOD_ID).header("Accept", "application/vnd.github.sha").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) parseSha(response.body()) else null
    } catch (_: IOException) {
        null
    }

    /** Downloads and compiles the repository. Runs on a worker thread; throws on any failure. */
    fun download(client: HttpClient): Repo {
        val request = HttpRequest.newBuilder(URI.create(NeuRecipeStore.URL))
            .timeout(Duration.ofMinutes(3)).header("User-Agent", GeilerAddons.MOD_ID).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()}")
        return response.body().use { parse(it) }
    }

    @JvmStatic
    fun encode(repo: Repo): JsonObject {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val items = JsonArray()
        for (e in repo.entries) {
            val o = JsonObject()
            o.addProperty("id", e.id)
            o.addProperty("name", e.name)
            o.add("lore", JsonArray().also { a -> e.lore.forEach { a.add(it) } })
            o.addProperty("mc", e.mcId)
            if (e.damage != 0) o.addProperty("dmg", e.damage)
            if (e.nbt.isNotEmpty()) o.addProperty("nbt", e.nbt)
            o.addProperty("rar", e.rarity)
            o.addProperty("type", e.type)
            o.addProperty("cat", e.category)
            e.wikiUrl?.let { o.addProperty("wiki", it) }
            items.add(o)
        }
        root.add("items", items)
        val recipes = JsonArray()
        for (r in repo.recipes) {
            val o = JsonObject()
            o.addProperty("t", r.type)
            o.add("in", ingredients(r.inputs))
            o.add("out", ingredients(r.outputs))
            if (r.extra.isNotEmpty()) o.add("x", JsonObject().also { x -> r.extra.forEach { (k, v) -> x.addProperty(k, v) } })
            recipes.add(o)
        }
        root.add("recipes", recipes)
        return root
    }

    private fun ingredients(list: List<Ingredient>): JsonArray {
        val array = JsonArray()
        for (i in list) array.add(JsonArray().also { it.add(i.id); it.add(i.amount) })
        return array
    }

    @JvmStatic
    fun decode(root: JsonObject): Repo? {
        if (root.get("version")?.asInt != VERSION) return null
        val items = root.getAsJsonArray("items") ?: return null
        val recipes = root.getAsJsonArray("recipes") ?: return null
        if (items.size() > MAX_ITEMS || recipes.size() > MAX_ITEMS * 8) return null
        val entries = items.map { v ->
            val o = v.asJsonObject
            Entry(
                o.get("id").asString, o.get("name").asString, o.getAsJsonArray("lore").map { it.asString }, o.get("mc").asString,
                o.get("dmg")?.asInt ?: 0, o.get("nbt")?.asString ?: "", o.get("rar").asString, o.get("type").asString,
                o.get("cat").asString, o.get("wiki")?.asString,
            )
        }
        val list = recipes.map { v ->
            val o = v.asJsonObject
            ItemRecipe(
                o.get("t").asString, readIngredients(o.getAsJsonArray("in")), readIngredients(o.getAsJsonArray("out")),
                o.getAsJsonObject("x")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap(),
            )
        }
        return Repo(entries, list)
    }

    private fun readIngredients(array: JsonArray): List<Ingredient> =
        array.map { val pair = it.asJsonArray; Ingredient(pair[0].asString, pair[1].asDouble) }

    /** The saved repository and its age, or null when there is none or it is unreadable. */
    fun readCache(): Loaded? {
        try {
            if (!Files.isRegularFile(PATH)) return null
            val age = System.currentTimeMillis() - Files.getLastModifiedTime(PATH).toMillis()
            val bytes = GZIPInputStream(Files.newInputStream(PATH)).use { it.readNBytes(MAX_CACHE_BYTES.toInt() + 1) }
            if (bytes.size > MAX_CACHE_BYTES) return null
            val root = JsonParser.parseReader(InputStreamReader(bytes.inputStream(), Charsets.UTF_8)).asJsonObject
            return decode(root)?.let { Loaded(it, age, readCommit()) }
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
    }

    private fun readCommit(): String? = try {
        if (Files.isRegularFile(COMMIT_PATH)) parseSha(Files.readString(COMMIT_PATH)) else null
    } catch (_: IOException) {
        null
    }

    /** Marks the saved cache as current for [commit] and as freshly checked. */
    fun saveCommit(commit: String?) {
        try {
            if (commit == null) Files.deleteIfExists(COMMIT_PATH) else Files.writeString(COMMIT_PATH, commit)
            if (Files.isRegularFile(PATH)) Files.setLastModifiedTime(PATH, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
        } catch (_: IOException) {
            // Only the next start's update check suffers.
        }
    }

    fun writeCache(repo: Repo) {
        try {
            Files.createDirectories(PATH.parent)
            val temp = PATH.resolveSibling(PATH.fileName.toString() + ".tmp")
            GZIPOutputStream(Files.newOutputStream(temp)).use { it.write(encode(repo).toString().toByteArray(Charsets.UTF_8)) }
            Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // The next session downloads again.
        }
    }
}
