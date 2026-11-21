package geiler.addons.client.itemlist

import geiler.addons.client.module.impl.PriceDisplayModule
import geiler.addons.client.pricing.Recipe
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The one compiled copy of the NEU repository (items, recipes, search index, item stacks) that the Item List and Price
 * Display share. Everything runs on two daemon threads, never the client thread:
 *  1. the saved cache is read and compiled at once, so the list is ready seconds after the game starts and works offline;
 *  2. every item stack is then built in the background ([LegacyItemStacks.Store.warm]);
 *  3. one tiny request asks GitHub for the repository's current commit; only when it differs from the cached one is the zip
 *     downloaded, compiled and swapped in. The commit is re-checked every [UPDATE_CHECK_MILLIS] while the game runs.
 * This is the idea behind Skyblocker's shallow clone plus fetch, without a git library.
 */
object ItemListRepository {
    private const val RETRY_MILLIS = 5L * 60_000L
    private const val UPDATE_CHECK_MILLIS = 6L * 3_600_000L
    private const val STACK_FORMAT = 1
    private val STACKS_PATH: java.nio.file.Path by lazy {
        net.fabricmc.loader.api.FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("neu-item-stacks.nbt.gz")
    }

    class Compiled(
        val entries: List<Entry>,
        val byId: Map<String, Entry>,
        val index: RecipeIndex,
        val search: ItemSearch,
        val craftTable: Map<String, Recipe>,
        val source: String,
        val buildMillis: Long,
        val stacks: LegacyItemStacks.Store,
    )

    @Volatile
    var compiled: Compiled? = null
        private set

    @Volatile
    var status = "not loaded"
        private set

    /** How many item stacks came from the saved file and how many had to be built this launch. */
    @Volatile
    var stackSource = "pending"
        private set

    private var busy = false
    private var currentCommit: String? = null
    private var nextAttempt = 0L
    private var nextUpdateCheck = 0L
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val executor = Executors.newFixedThreadPool(2) { task -> Thread(task, "geileraddons-item-list").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 } }

    /** The crafting-cost table, or null while the repository is not loaded. */
    fun craftTable(): Map<String, Recipe>? = compiled?.craftTable

    /** Starts loading, or an update check when due, unless one is in flight. Cheap to call every tick. */
    @Synchronized
    fun request() {
        val now = System.currentTimeMillis()
        if (busy || now < nextAttempt) return
        // Early in startup the items have no default components yet and ItemStack(item) cannot be built.
        if (!Items.DIAMOND.builtInRegistryHolder().areComponentsBound()) return
        val have = compiled != null
        if (have && now < nextUpdateCheck) return
        busy = true
        if (!have) status = "loading"
        executor.execute { run(have) }
    }

    private fun run(have: Boolean) {
        try {
            if (!have) {
                val saved = NeuRepo.readCache()
                if (saved != null) {
                    currentCommit = saved.commit
                    publish(saved.repo, "cache")
                }
            }
            val known = compiled != null
            val latest = NeuRepo.latestCommit(client)
            val upToDate = known && latest != null && latest == currentCommit
            when {
                upToDate -> NeuRepo.saveCommit(latest)
                // Cache and no network answer: keep what there is and ask again later.
                known && latest == null -> retryLater()
                else -> {
                    val fresh = NeuRepo.download(client)
                    NeuRepo.writeCache(fresh)
                    NeuRepo.saveCommit(latest)
                    currentCommit = latest
                    publish(fresh, if (latest != null) "download commit $latest" else "download")
                }
            }
            synchronized(this) {
                busy = false
                if (nextUpdateCheck < System.currentTimeMillis()) nextUpdateCheck = System.currentTimeMillis() + UPDATE_CHECK_MILLIS
            }
        } catch (exception: Exception) {
            fail(exception, have)
        }
    }

    @Synchronized
    private fun retryLater() {
        nextUpdateCheck = System.currentTimeMillis() + RETRY_MILLIS
    }

    @Synchronized
    private fun fail(exception: Exception, have: Boolean) {
        busy = false
        nextAttempt = System.currentTimeMillis() + RETRY_MILLIS
        // A failed update leaves the list that is already usable.
        if (!have && compiled == null) status = "failed ${exception.javaClass.simpleName} ${exception.stackTrace.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }}"
    }

    /** Compiles [repo] into the entries, index, search and (not yet warmed) stack store. Any thread. */
    @JvmStatic
    fun compile(repo: NeuRepo.Repo, source: String): Compiled {
        val started = System.currentTimeMillis()
        val entries = ArrayList(repo.entries)
        val known = entries.mapTo(HashSet()) { it.id }
        for (item in BuiltInRegistries.ITEM) {
            if (item === Items.AIR) continue
            val path = BuiltInRegistries.ITEM.getKey(item).path
            val id = path.uppercase()
            if (id in known) continue
            entries.add(Entry(id, ItemStack(item).hoverName.string, emptyList(), "minecraft:$path", 0, "", "", "", ItemListData.CAT_VANILLA, null))
        }
        val index = RecipeIndex(repo.recipes)
        val search = ItemSearch(entries, index, ItemSearch.DEFAULT_ALIASES) { id -> PriceDisplayModule.INSTANCE.prices().bazaar?.containsKey(id) == true }
        val stacks = LegacyItemStacks.Store(LegacyItemStacks.registries())
        return Compiled(
            entries, entries.associateBy { it.id }, index, search, ItemListData.craftTable(repo.recipes), source, System.currentTimeMillis() - started, stacks,
        )
    }

    /** Compiles [repo] on the calling worker thread, makes it current, then builds its item stacks. */
    private fun publish(repo: NeuRepo.Repo, source: String) {
        val result = compile(repo, source)
        val stacks = result.stacks
        val search = result.search
        val entries = result.entries
        compiled?.stacks?.cancel()
        synchronized(this) {
            compiled = result
            status = "ok ${entries.size} items ${repo.recipes.size} recipes ($source) build=${result.buildMillis}ms"
        }
        // Saved stacks first (no legacy fixer needed), then whatever is missing in default list order; the result is saved for the next launch.
        val key = currentCommit?.let { "$STACK_FORMAT:$it" }
        executor.execute {
            val restored = if (key != null) stacks.load(STACKS_PATH, key, entries) else 0
            stacks.warm(search.search(""))
            if (key != null && stacks.warmed && stacks.size > restored) stacks.save(STACKS_PATH, key)
            synchronized(this) { stackSource = "restored=$restored built=${stacks.size - restored}" }
        }
    }
}
