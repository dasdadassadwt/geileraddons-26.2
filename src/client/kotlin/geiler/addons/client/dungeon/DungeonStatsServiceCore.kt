package geiler.addons.client.dungeon

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.GeilerAddons
import geiler.addons.client.net.BoundedHttpBodyReader
import net.minecraft.client.Minecraft
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Base64
import java.util.EnumMap
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import java.util.zip.GZIPInputStream

/** Implementation behind the stable Java facade used by modules and commands. */
internal object DungeonStatsServiceCore {
    private const val API_BASE = "https://api.odtheking.com/hypixel/"
    private const val UUID_BASE = "https://api.minecraftservices.com/minecraft/profile/lookup/name/"
    private const val CACHE_TTL_MILLIS = 5 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 512
    private val TIMEOUT: Duration = Duration.ofSeconds(10)
    private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
    private const val MAX_JSON_DEPTH = 32
    // Active-player profiles reach ~127k nodes (5.3 MB); the 8 MB body cap bounds this.
    private const val MAX_JSON_NODES = 500_000
    private const val MAX_ITEM_TEXT_CHARS = 64 * 1024
    private const val MAX_COMPRESSED_VALUE_CHARS = 256 * 1024
    private const val MAX_NBT_BYTES = 2 * 1024 * 1024
    private const val MAX_NBT_DEPTH = 32
    private const val MAX_NBT_NODES = 20_000
    private const val MAX_NBT_STRING_CHARS = 8 * 1024
    private const val MAX_GEAR_TOOLTIP_ITEMS = 64
    private const val MAX_GEAR_TOOLTIP_LINES = 64
    private const val MAX_GEAR_TOOLTIP_LINE_CHARS = 2_048
    private const val MAX_GEAR_TOOLTIP_CHARS = 64 * 1024
    private const val MAX_GOLDEN_DRAGON_PETS = 32
    private const val MAX_QUEUED_LOOKUPS = 32
    private val LOOKUP_START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(1_000)
    private val HTTP_START_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(800)
    private const val ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election"
    private const val MILLIS_PER_DAY = 86_400_000L
    private const val DAILY_BONUS_RUNS = 5L
    private const val MAX_BAG_DEPTH = 6
    private const val MAX_BAG_BYTES = 4 * 1024 * 1024
    private const val MAX_BAG_NODES = 400_000
    private const val MAX_BAG_ITEMS = 4_000
    private val EXPLORER_KEYS = setOf("CATACOMBS_EXPLORER", "SHARD_BONZO", "BONZO", "E51")
    private val EPIC_SHARD_COSTS = intArrayOf(1, 2, 4, 6, 9, 12, 16, 20, 25, 32)
    private val DUNGEON_XP_LEVELS = longArrayOf(
        0, 50, 125, 235, 395, 625, 955, 1425, 2095, 3045, 4385,
        6275, 8940, 12700, 17960, 25340, 35640, 50040, 70040, 97640,
        135640, 188140, 259640, 356640, 488640, 668640, 911640, 1239640,
        1684640, 2284640, 3084640, 4149640, 5559640, 7459640, 9959640,
        13259640, 17559640, 23159640, 30359640, 39559640, 51559640,
        66559640, 85559640, 109559640, 139559640, 177559640, 225559640,
        285559640, 360559640, 453559640, 569809640,
    )
    private val ITEM_ID_KEYS = setOf(
        "id", "item_id", "itemid", "item_name", "itemname", "internalname", "internal_name",
        "displayname", "display_name", "tag", "nbt",
    )
    private val REQUEST_LIMITER = DungeonRequestLimiter(
        MAX_QUEUED_LOOKUPS + 1,
        LOOKUP_START_INTERVAL_NANOS,
    )
    private val HTTP_REQUEST_PACER = DungeonRequestLimiter(1, HTTP_START_INTERVAL_NANOS)
    private val EXECUTOR: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(MAX_QUEUED_LOOKUPS),
        ThreadFactory { runnable ->
            Thread(runnable, "GeilerAddons dungeon stats").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val HTTP: HttpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val CACHE = ConcurrentHashMap<String, CacheEntry>()
    private val IN_FLIGHT = ConcurrentHashMap<String, CompletableFuture<DungeonStatsService.Result>>()

    fun fetch(name: String?, callback: Consumer<DungeonStatsService.Result>?, forceRefresh: Boolean) {
        if (name == null || name.isBlank()) {
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Ignored blank profile request")
            return
        }
        val key = name.lowercase(Locale.ROOT)
        pruneCache(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val cached = CACHE[key]
        if (!forceRefresh && cached != null && now - cached.timestamp < CACHE_TTL_MILLIS) {
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Cache hit")
            dispatch(callback, DungeonStatsService.Result.success(cached.stats))
            return
        }

        val created = AtomicBoolean()
        val future = IN_FLIGHT.computeIfAbsent(key) {
            created.set(true)
            val ticket = REQUEST_LIMITER.tryAcquire()
            if (ticket == null) {
                GeilerAddons.LOGGER.debug("[Dungeon Stats] Lookup queue is full; rejecting request")
                return@computeIfAbsent CompletableFuture.completedFuture(
                    DungeonStatsService.Result.failure("Stats request queue is full"),
                )
            }
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Fetching profile from allowlisted providers")
            val request = CompletableFuture<DungeonStatsService.Result>()
            try {
                EXECUTOR.execute {
                    try {
                        val delay = REQUEST_LIMITER.reserveStartDelayNanos(System.nanoTime())
                        if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
                        request.complete(load(name))
                    } catch (exception: InterruptedException) {
                        Thread.currentThread().interrupt()
                        request.complete(DungeonStatsService.Result.failure("Profile request was interrupted"))
                    } catch (exception: Throwable) {
                        request.complete(DungeonStatsService.Result.failure(errorText(exception)))
                    } finally {
                        ticket.close()
                    }
                }
            } catch (exception: RejectedExecutionException) {
                ticket.close()
                request.complete(DungeonStatsService.Result.failure("Stats request queue is full"))
            }
            request
        }
        if (!created.get()) GeilerAddons.LOGGER.debug("[Dungeon Stats] Joined in-flight profile request")
        future.whenComplete { _, _ -> IN_FLIGHT.remove(key, future) }
        future.thenAccept { result -> dispatch(callback, result) }
    }

    fun clearCache() {
        CACHE.clear()
    }

    /** Cancels profile work when the client is stopping; callbacks stay detached from Minecraft state. */
    fun close() {
        for (future in IN_FLIGHT.values) future.cancel(true)
        IN_FLIGHT.clear()
        CACHE.clear()
        EXECUTOR.shutdownNow()
    }

    fun parseForChecks(
        name: String,
        uuid: UUID,
        root: JsonObject,
        member: JsonObject,
        endpointSecrets: Long?,
    ): DungeonStats = parseStats(name, uuid, root, member, endpointSecrets)

    fun hasValidatedInventoryData(element: JsonElement?): Boolean = hasInventoryData(element)

    fun endpointAllowedForChecks(uri: URI?): Boolean = allowedUri(uri)

    fun retryAfterForChecks(value: String?): Long = retryAfterMillis(value)

    fun encodePathForChecks(value: String): String = encodePath(value)

    fun maxJsonNodesForChecks(): Int = MAX_JSON_NODES

    /** Total Catacombs XP at a level from 0 to 50. */
    fun catacombsXpAt(level: Int): Long = DUNGEON_XP_LEVELS[level.coerceIn(0, 50)]

    class CataLookup(
        val name: String,
        val profile: CataCalculator.Profile?,
        val mayorBonus: Double?,
        val mayorName: String,
        val error: String?,
    )

    /** One uncached profile lookup for `/ga cata`; shares the stats queue, pacing and endpoint rules. */
    fun fetchCata(name: String, callback: Consumer<CataLookup>) {
        val finish = { result: CataLookup -> Minecraft.getInstance().execute { callback.accept(result) } }
        val fail = { reason: String -> finish(CataLookup(name, null, null, "", reason)) }
        val ticket = REQUEST_LIMITER.tryAcquire()
        if (ticket == null) {
            fail("Stats request queue is full")
            return
        }
        try {
            EXECUTOR.execute {
                try {
                    val delay = REQUEST_LIMITER.reserveStartDelayNanos(System.nanoTime())
                    if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
                    finish(loadCata(name))
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail("Profile request was interrupted")
                } catch (exception: Exception) {
                    GeilerAddons.LOGGER.debug("[Dungeon Stats] Cata lookup failed ({})", exception.javaClass.simpleName)
                    fail(errorText(exception))
                } finally {
                    ticket.close()
                }
            }
        } catch (_: RejectedExecutionException) {
            ticket.close()
            fail("Stats request queue is full")
        }
    }

    class ProfilesLookup(val name: String, val uuid: String?, val root: JsonObject?, val error: String?)

    /** Raw profiles answer for the Profile Viewer; same queue, pacing and endpoint rules. Callback on the client thread. */
    fun fetchProfiles(name: String, callback: Consumer<ProfilesLookup>) = fetchProfiles(name, null, callback)

    /** As [fetchProfiles]; a known [uuid] (your own) skips the Mojang name lookup. */
    fun fetchProfiles(name: String, uuid: String?, callback: Consumer<ProfilesLookup>) {
        val finish = { result: ProfilesLookup -> Minecraft.getInstance().execute { callback.accept(result) } }
        val ticket = REQUEST_LIMITER.tryAcquire()
        if (ticket == null) {
            finish(ProfilesLookup(name, null, null, "Stats request queue is full"))
            return
        }
        try {
            EXECUTOR.execute {
                try {
                    val delay = REQUEST_LIMITER.reserveStartDelayNanos(System.nanoTime())
                    if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay)
                    val identity = if (uuid == null) getJson(UUID_BASE + encodePath(name)) else null
                    val id = uuid ?: string(identity, "id")
                    if (id == null) {
                        finish(ProfilesLookup(name, null, null, "Mojang did not return a UUID"))
                    } else {
                        finish(ProfilesLookup(identity?.let { string(it, "name") } ?: name, id, getJson(API_BASE + "get/" + id), null))
                    }
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    finish(ProfilesLookup(name, null, null, "Profile request was interrupted"))
                } catch (exception: Exception) {
                    finish(ProfilesLookup(name, null, null, errorText(exception)))
                } finally {
                    ticket.close()
                }
            }
        } catch (_: RejectedExecutionException) {
            ticket.close()
            finish(ProfilesLookup(name, null, null, "Stats request queue is full"))
        }
    }

    private fun loadCata(name: String): CataLookup {
        val identity = getJson(UUID_BASE + encodePath(name))
        val id = string(identity, "id") ?: return CataLookup(name, null, null, "", "Mojang did not return a UUID")
        val member = profileMember(getJson(API_BASE + "get/" + id), id)
            ?: return CataLookup(name, null, null, "", "Profile data is unavailable")
        val profile = parseCata(member, System.currentTimeMillis() / MILLIS_PER_DAY)
        val mayor = fetchMayor()
        return CataLookup(string(identity, "name") ?: name, profile, mayor?.first, mayor?.second ?: "", null)
    }

    /** The mayor's Catacombs XP bonus and name, or null when the public election data cannot be read. */
    private fun fetchMayor(): Pair<Double, String>? = try {
        val mayor = jsonObject(getJson(ELECTION_URL), "mayor")
        if (mayor == null) {
            null
        } else {
            val perks = ArrayList<String>()
            mayor.get("perks")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { perk ->
                if (perk.isJsonObject) string(perk.asJsonObject, "name")?.let(perks::add)
            }
            string(jsonObject(jsonObject(mayor, "minister"), "perk"), "name")?.let(perks::add)
            CataCalculator.mayorBonus(string(mayor, "key") ?: "", perks) to (string(mayor, "name") ?: "Mayor")
        }
    } catch (exception: IOException) {
        GeilerAddons.LOGGER.debug("[Dungeon Stats] Election lookup failed ({})", exception.javaClass.simpleName)
        null
    } catch (exception: RuntimeException) {
        GeilerAddons.LOGGER.debug("[Dungeon Stats] Election lookup failed ({})", exception.javaClass.simpleName)
        null
    }

    fun parseCataForChecks(member: JsonObject, today: Long): CataCalculator.Profile = parseCata(member, today)

    private fun parseCata(member: JsonObject, today: Long): CataCalculator.Profile {
        val dungeons = jsonObject(member, "dungeons")
        val types = jsonObject(dungeons, "dungeon_types")
        val catacombs = jsonObject(types, "catacombs")
        val completions = EnumMap<CataCalculator.Floor, Int>(CataCalculator.Floor::class.java)
        for (floor in CataCalculator.Floor.entries) {
            val source = if (floor.master()) jsonObject(types, "master_catacombs") else catacombs
            completions[floor] = number(jsonObject(source, "tier_completions"), floor.tier().toString()).toInt()
        }
        val daily = jsonObject(dungeons, "daily_runs")
        val usedToday = if (number(daily, "current_day_stamp") == today) number(daily, "completed_runs_count") else 0L

        // Without the inventory API there are no item bags, and a missing ring is then unknown rather than absent.
        val inventory = jsonObject(member, "inventory")
        var ring: Boolean? = null
        var hecatomb: Int? = null
        if (inventory != null) {
            // The ring only works carried: in the accessory bag or the inventory.
            val carried = ArrayList<Map<*, *>>()
            itemAttributes(jsonObject(jsonObject(inventory, "bag_contents"), "talisman_bag"), carried, 0)
            itemAttributes(jsonObject(inventory, "inv_contents"), carried, 0)
            ring = carried.any { it["id"] == "CATACOMBS_EXPERT_RING" }
            val all = ArrayList<Map<*, *>>()
            itemAttributes(inventory, all, 0)
            itemAttributes(member.get("loadout"), all, 0)
            hecatomb = all.maxOfOrNull {
                ((it["enchantments"] as? Map<*, *>)?.get("hecatomb") as? Number)?.toInt() ?: 0
            } ?: 0
        }

        // Shard attributes are saved as the number of shards used; the Explorer shard is epic.
        val stacks = jsonObject(jsonObject(member, "attributes"), "stacks")
        var explorer: Int? = null
        if (stacks != null) {
            val shards = stacks.entrySet().filter { it.key.uppercase(Locale.ROOT) in EXPLORER_KEYS }
                .maxOfOrNull { number(stacks, it.key) } ?: 0L
            explorer = EPIC_SHARD_COSTS.count { shards >= it }
        }
        return CataCalculator.Profile(
            maxOf(0L, number(catacombs, "experience")), completions,
            (DAILY_BONUS_RUNS - usedToday).coerceIn(0L, DAILY_BONUS_RUNS).toInt(), ring, hecatomb, explorer,
        )
    }

    /** Collects every item's ExtraAttributes from the base64 gzip `data` fields under [element]. */
    private fun itemAttributes(element: JsonElement?, output: MutableList<Map<*, *>>, depth: Int) {
        if (element == null || !element.isJsonObject || depth > MAX_BAG_DEPTH) return
        for (entry in element.asJsonObject.entrySet()) {
            val value = entry.value
            if (entry.key != "data" || !value.isJsonPrimitive) {
                itemAttributes(value, output, depth + 1)
                continue
            }
            val text = value.asString
            if (!looksLikeCompressedNbt(text)) continue
            try {
                val bytes = Base64.getDecoder().decode(text)
                if (bytes.size > MAX_NBT_BYTES) continue
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                    DataInputStream(LimitedInputStream(gzip, MAX_BAG_BYTES)).use { input ->
                        findExtraAttributes(NbtTreeReader.read(input, MAX_BAG_NODES), output, 0)
                    }
                }
            } catch (_: Exception) {
                // One unreadable bag only hides its own items.
            }
        }
    }

    private fun findExtraAttributes(value: Any?, output: MutableList<Map<*, *>>, depth: Int) {
        if (depth > MAX_NBT_DEPTH || output.size >= MAX_BAG_ITEMS) return
        if (value is List<*>) {
            for (child in value) findExtraAttributes(child, output, depth + 1)
        } else if (value is Map<*, *>) {
            val attributes = value["ExtraAttributes"] as? Map<*, *>
            if (attributes != null) output.add(attributes)
            else for (child in value.values) findExtraAttributes(child, output, depth + 1)
        }
    }

    fun formatTime(seconds: Long): String {
        if (seconds <= 0) return "-"
        return "${seconds / 60}:${String.format(Locale.ROOT, "%02d", seconds % 60)}"
    }

    fun formatCatacombsLevel(totalExperience: Long, showOverflow: Boolean): String {
        val progress = catacombsProgress(totalExperience)
        if (!showOverflow || !progress.capped || progress.overflowExperience <= 0) {
            return progress.level.toString()
        }
        val level = 50.0 + progress.overflowExperience / 200_000_000.0
        return String.format(Locale.ROOT, "%.2f", level)
    }

    fun catacombsProgress(totalExperience: Long): DungeonStatsService.CataProgress {
        val xp = maxOf(0L, totalExperience)
        val cataLevel = level(xp, 50)
        if (cataLevel >= 50) {
            return DungeonStatsService.CataProgress(
                50, xp, 0, 0, maxOf(0L, xp - DUNGEON_XP_LEVELS[50]), true,
            )
        }
        val start = DUNGEON_XP_LEVELS[cataLevel]
        val needed = DUNGEON_XP_LEVELS[cataLevel + 1] - start
        return DungeonStatsService.CataProgress(cataLevel, xp, xp - start, needed, 0, false)
    }

    private fun dispatch(callback: Consumer<DungeonStatsService.Result>?, result: DungeonStatsService.Result) {
        Minecraft.getInstance().execute { callback?.accept(result) }
    }

    private fun pruneCache(now: Long) {
        for ((key, value) in CACHE) {
            if (now - value.timestamp >= CACHE_TTL_MILLIS) CACHE.remove(key, value)
        }
        if (CACHE.size <= MAX_CACHE_ENTRIES) return
        var excess = CACHE.size - MAX_CACHE_ENTRIES
        for (key in CACHE.keys) {
            if (excess-- <= 0) break
            CACHE.remove(key)
        }
    }

    private data class CacheEntry(val stats: DungeonStats, val timestamp: Long)

    private fun load(name: String): DungeonStatsService.Result {
        try {
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Resolving profile identity")
            val uuidJson = getJson(UUID_BASE + encodePath(name))
            val id = string(uuidJson, "id")
            val returnedName = string(uuidJson, "name")
            if (id == null) {
                GeilerAddons.LOGGER.debug("[Dungeon Stats] Identity provider returned no UUID")
                return DungeonStatsService.Result.failure("Mojang did not return a UUID")
            }
            val uuid = UUID.fromString(withDashes(id))
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Loading profile data")
            // Most profiles contain total secrets. Only use the dedicated endpoint when the profile omits it.
            val profile = getJson(API_BASE + "get/" + id)
            val member = profileMember(profile, id)
            if (member == null) {
                GeilerAddons.LOGGER.debug("[Dungeon Stats] Profile response contained no member data")
                return DungeonStatsService.Result.failure("Profile data is unavailable")
            }
            val profileSecrets = findNumber(
                jsonObject(member, "dungeons"),
                "total_secrets", "totalSecrets", "secrets", "secrets_found",
            )
            val endpointSecrets = if (profileSecrets.present) null else fetchEndpointSecrets(id)
            val stats = parseStats(returnedName ?: name, uuid, profile, member, endpointSecrets)
            if (stats.cacheable()) {
                CACHE[name.lowercase(Locale.ROOT)] = CacheEntry(stats, System.currentTimeMillis())
                pruneCache(System.currentTimeMillis())
            } else {
                GeilerAddons.LOGGER.debug("[Dungeon Stats] Keeping incomplete profile out of cache")
            }
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Profile data loaded; cacheable={}", stats.cacheable())
            return DungeonStatsService.Result.success(stats)
        } catch (exception: Exception) {
            // Do not put player names, UUIDs, response URLs, or profile values in diagnostic logs.
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Profile lookup failed ({})", exception.javaClass.simpleName)
            return DungeonStatsService.Result.failure(errorText(exception))
        }
    }

    private fun fetchEndpointSecrets(id: String): Long? {
        try {
            val value = getJsonElement(API_BASE + "secrets/" + id)
            return if (value != null && value.isJsonPrimitive) value.asLong else null
        } catch (exception: IOException) {
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Dedicated secrets fallback failed ({})", exception.javaClass.simpleName)
            return null
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Dedicated secrets fallback interrupted")
            return null
        } catch (exception: RuntimeException) {
            GeilerAddons.LOGGER.debug("[Dungeon Stats] Dedicated secrets fallback failed ({})", exception.javaClass.simpleName)
            return null
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun getJson(url: String): JsonObject {
        val parsed = getJsonElement(url)
        if (!parsed.isJsonObject) throw IOException("Response was not an object")
        return parsed.asJsonObject
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun getJsonElement(url: String): JsonElement {
        var current = URI.create(url)
        if (!allowedUri(current)) throw IOException("Refusing an untrusted endpoint")
        var response: HttpResponse<ByteArray>
        var redirects = 0
        var rateLimitRetries = 0
        while (true) {
            val requestDelay = HTTP_REQUEST_PACER.reserveStartDelayNanos(System.nanoTime())
            if (requestDelay > 0) TimeUnit.NANOSECONDS.sleep(requestDelay)
            val request = HttpRequest.newBuilder(current)
                .timeout(TIMEOUT)
                .header("User-Agent", "GeilerAddons")
                .header("Accept", "application/json")
                .GET()
                .build()
            response = BoundedHttpBodyReader.send(HTTP, request, MAX_RESPONSE_BYTES, TIMEOUT)
            val status = response.statusCode()
            if (status == 429 && rateLimitRetries == 0) {
                val retryDelayMillis = retryAfterMillis(response.headers().firstValue("Retry-After").orElse(null))
                if (retryDelayMillis >= 0) {
                    rateLimitRetries++
                    TimeUnit.MILLISECONDS.sleep(retryDelayMillis)
                    continue
                }
            }
            if (status < 300 || status >= 400) break
            if (redirects++ >= 1) throw IOException("Too many redirects")
            val location = response.headers().firstValue("Location").orElse(null)
                ?: throw IOException("Redirect had no location")
            val next = current.resolve(location)
            if (!allowedUri(next)) throw IOException("Refusing redirected endpoint")
            current = next
        }
        if (!allowedUri(response.uri())) throw IOException("Refusing redirected endpoint")
        GeilerAddons.LOGGER.debug(
            "[Dungeon Stats] GET {} -> {}",
            endpointLabel(response.uri()),
            response.statusCode(),
        )
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            val retryAfter = if (response.statusCode() == 429) {
                response.headers().firstValue("Retry-After").map { " (retry after $it)" }.orElse("")
            } else ""
            throw IOException("HTTP ${response.statusCode()}$retryAfter")
        }
        val body = String(response.body(), StandardCharsets.UTF_8)
        return BoundedDungeonJsonParser.parse(body, MAX_JSON_DEPTH, MAX_JSON_NODES)
    }

    /** Retry one short provider backoff; longer or malformed Retry-After values go to manual retry. */
    private fun retryAfterMillis(header: String?): Long {
        if (header.isNullOrBlank()) return 1_000L
        var waitMillis: Long
        try {
            waitMillis = Math.multiplyExact(header.trim().toLong(), 1_000L)
        } catch (_: NumberFormatException) {
            try {
                val retryAt = ZonedDateTime.parse(header.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                waitMillis = maxOf(0L, Duration.between(Instant.now(), retryAt).toMillis())
            } catch (_: java.time.format.DateTimeParseException) {
                return -1L
            } catch (_: ArithmeticException) {
                return -1L
            }
        } catch (_: ArithmeticException) {
            return -1L
        }
        return if (waitMillis in 0L..2_000L) waitMillis else -1L
    }

    private fun allowedUri(uri: URI?): Boolean {
        if (uri == null || uri.scheme?.equals("https", ignoreCase = true) != true) return false
        if (uri.rawUserInfo != null || uri.rawFragment != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host ?: return false
        // The public election data (no key), for the mayor's Catacombs XP bonus.
        if (host.equals("api.hypixel.net", ignoreCase = true)) return uri.rawPath == "/v2/resources/skyblock/election"
        return host.equals("api.minecraftservices.com", ignoreCase = true) ||
            host.equals("api.odtheking.com", ignoreCase = true) ||
            host.equals("hypixel.odtheking.com", ignoreCase = true)
    }

    private fun endpointLabel(uri: URI): String {
        val path = uri.path ?: ""
        return when {
            uri.host.equals("api.minecraftservices.com", ignoreCase = true) -> "Mojang profile lookup"
            path.startsWith("/hypixel/get/") -> "Odin profile"
            path.startsWith("/hypixel/secrets/") -> "Odin secrets"
            else -> "allowlisted profile provider"
        }
    }

    private fun profileMember(root: JsonObject, uuid: String): JsonObject? {
        val profile = profileForMember(root, uuid) ?: return null
        val compact = uuid.replace("-", "")
        val dashed = withDashes(compact)
        val members = jsonObject(profile, "members")
        val member = jsonObject(members, compact)
        return member ?: jsonObject(members, dashed)
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8).replace("+", "%20")

    private fun errorText(exception: Throwable): String {
        val message = exception.message
        return if (message.isNullOrBlank()) "Request failed" else message
    }

    private fun level(xp: Long, cap: Int): Int {
        var result = 0
        for (index in DUNGEON_XP_LEVELS.indices) {
            if (index > cap) break
            if (xp >= DUNGEON_XP_LEVELS[index]) result = index else break
        }
        return result
    }

    private fun parseStats(
        name: String,
        uuid: UUID,
        root: JsonObject,
        member: JsonObject,
        endpointSecrets: Long?,
    ): DungeonStats {
        val dungeons = jsonObject(member, "dungeons")
        val types = jsonObject(dungeons, "dungeon_types")
        val catacombs = jsonObject(types, "catacombs")
        val master = jsonObject(types, "master_catacombs")
        val available = EnumSet.noneOf(DungeonStats.DataField::class.java)
        val cataExperienceKnown = hasNumber(catacombs, "experience")
        val cataExperience = maxOf(0L, number(catacombs, "experience"))
        val cata = level(cataExperience, 50)
        if (cataExperienceKnown) available.add(DungeonStats.DataField.CATACOMBS_LEVEL)

        val classLevels = EnumMap<DungeonClass, Int>(DungeonClass::class.java)
        val classes = jsonObject(dungeons, "player_classes")
        var allClassesKnown = classes != null
        for (dungeonClass in DungeonClass.values()) {
            val value = jsonObject(classes, dungeonClass.name.lowercase(Locale.ROOT))
            if (hasNumber(value, "experience")) {
                classLevels[dungeonClass] = level(number(value, "experience"), 50)
            } else {
                allClassesKnown = false
            }
        }
        val classAverage = classLevels.values.map { it.toInt() }.average().let { if (it.isNaN()) 0.0 else it }
        if (allClassesKnown) available.add(DungeonStats.DataField.CLASS_AVERAGE)

        val profileSecretValue = findNumber(
            dungeons,
            "total_secrets", "totalSecrets", "secrets", "secrets_found",
        )
        val secretValue = if (endpointSecrets == null) profileSecretValue else NumberValue(maxOf(0L, endpointSecrets), true)
        val secrets = if (secretValue.present) secretValue.value else 0L
        if (secretValue.present) available.add(DungeonStats.DataField.SECRETS)
        val runs = totalRuns(catacombs, master)
        if (hasCompletions(catacombs) || hasCompletions(master)) available.add(DungeonStats.DataField.RUNS)
        val selectedProfile = profileForMember(root, uuid.toString())
        val banking = jsonObject(selectedProfile, "banking")
        val bank = firstLong(banking, "balance", "bank_balance")
        val bankKnown = banking != null && hasNumber(banking, "balance", "bank_balance")
        if (bankKnown) available.add(DungeonStats.DataField.BANK)
        val magicalPowerValue = findNumber(
            member,
            "highest_magical_power", "magical_power", "magicalPower", "magical_power_value",
        )
        val magicalPower = if (magicalPowerValue.present) magicalPowerValue.value.toInt() else 0
        if (magicalPowerValue.present) available.add(DungeonStats.DataField.MAGICAL_POWER)

        val gear = EnumSet.noneOf(DungeonStats.Gear::class.java)
        val allText = collectItemText(member)
        val itemDetails = collectGearDetails(member)
        for (item in itemDetails) gear.add(item.gear())
        if (containsItemIdentifier(allText, "terminator")) gear.add(DungeonStats.Gear.TERMINATOR)
        if (containsItemIdentifier(allText, "hyperion")) gear.add(DungeonStats.Gear.HYPERION)
        if (containsItemIdentifier(allText, "golden_dragon") || containsItemIdentifier(allText, "golden dragon")) {
            gear.add(DungeonStats.Gear.GOLDEN_DRAGON)
        }
        if (containsPetIdentifier(member, "golden_dragon")) gear.add(DungeonStats.Gear.GOLDEN_DRAGON)

        val pbs = EnumMap<DungeonFloor, Long>(DungeonFloor::class.java)
        if (hasPersonalBestData(catacombs) || hasPersonalBestData(master)) {
            available.add(DungeonStats.DataField.PERSONAL_BESTS)
        }
        readPbs(pbs, catacombs, false)
        readPbs(pbs, master, true)
        // Names in free text do not establish availability; require readable inventory and pet sections.
        val inventoryKnown = hasInventoryData(member)
        val petKnown = hasPetData(member)
        val goldenDragonPets = collectGoldenDragonDetails(member)
        val knownGear = EnumSet.noneOf(DungeonStats.Gear::class.java)
        if (inventoryKnown) {
            knownGear.add(DungeonStats.Gear.TERMINATOR)
            knownGear.add(DungeonStats.Gear.HYPERION)
        }
        if (petKnown) knownGear.add(DungeonStats.Gear.GOLDEN_DRAGON)
        if (knownGear.size == DungeonStats.Gear.values().size) available.add(DungeonStats.DataField.GEAR)
        val selectedClass = DungeonClass.parse(firstString(dungeons, "selected_dungeon_class", "selectedClass"))
        if (selectedClass != null) available.add(DungeonStats.DataField.SELECTED_CLASS)
        return DungeonStats(
            name, uuid, cata, selectedClass, classLevels, classAverage, secrets, runs,
            magicalPower, bank, bankKnown, knownGear, gear, pbs, available, cataExperience,
            cataExperienceKnown, itemDetails, goldenDragonPets,
        )
    }

    private fun collectGearDetails(member: JsonObject): List<DungeonStats.ItemDetails> {
        val output = ArrayList<DungeonStats.ItemDetails>()
        collectInventoryContainers(member, 0, JsonBudget(MAX_JSON_NODES), TooltipBudget(), output)
        return java.util.List.copyOf(output)
    }

    private fun collectInventoryContainers(
        element: JsonElement?,
        depth: Int,
        jsonBudget: JsonBudget,
        tooltipBudget: TooltipBudget,
        output: MutableList<DungeonStats.ItemDetails>,
    ) {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !jsonBudget.visit()) return
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectInventoryContainers(child, depth + 1, jsonBudget, tooltipBudget, output)
            }
            return
        }
        if (!element.isJsonObject) return
        for (entry in element.asJsonObject.entrySet()) {
            val value = entry.value
            if (isInventoryKey(entry.key)) {
                collectInventoryDetails(value, inventoryLabel(entry.key), depth + 1,
                    JsonBudget(MAX_JSON_NODES), tooltipBudget, output)
            } else {
                collectInventoryContainers(value, depth + 1, jsonBudget, tooltipBudget, output)
            }
            if (tooltipBudget.exhausted()) return
        }
    }

    private fun collectInventoryDetails(
        element: JsonElement?,
        source: String,
        depth: Int,
        jsonBudget: JsonBudget,
        tooltipBudget: TooltipBudget,
        output: MutableList<DungeonStats.ItemDetails>,
    ) {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !jsonBudget.visit() ||
            tooltipBudget.exhausted()
        ) return
        if (element.isJsonPrimitive) {
            if (!element.asJsonPrimitive.isString) return
            val value = element.asString
            if (looksLikeCompressedNbt(value)) {
                readGearDetailsFromNbt(value, source, tooltipBudget, output)
            } else {
                val gear = gearForIdentifier(value)
                if (gear != null && tooltipBudget.takeItem()) {
                    output.add(DungeonStats.ItemDetails(gear, value, gearDisplayName(gear), source, emptyList()))
                }
            }
            return
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectInventoryDetails(child, source, depth + 1, jsonBudget, tooltipBudget, output)
                if (tooltipBudget.exhausted()) return
            }
            return
        }
        if (!element.isJsonObject) return

        val json = element.asJsonObject
        if (hasGearIdentityInItemObject(json)) {
            val item = itemDetailsFromObject(jsonValue(json, 0, JsonBudget(MAX_JSON_NODES)), source, tooltipBudget)
            if (item != null) {
                output.add(item)
                return
            }
        }
        for (entry in json.entrySet()) {
            val value = entry.value
            val key = entry.key?.lowercase(Locale.ROOT) ?: ""
            if (key == "data" && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                looksLikeCompressedNbt(value.asString)
            ) {
                readGearDetailsFromNbt(value.asString, source, tooltipBudget, output)
            } else if (isInventoryKey(key)) {
                collectInventoryDetails(value, inventoryLabel(key), depth + 1, jsonBudget, tooltipBudget, output)
            } else {
                collectInventoryDetails(value, source, depth + 1, jsonBudget, tooltipBudget, output)
            }
            if (tooltipBudget.exhausted()) return
        }
    }

    /** Avoid converting every ordinary inventory item into a second object graph. */
    private fun hasGearIdentityInItemObject(json: JsonObject): Boolean {
        for (entry in json.entrySet()) {
            val key = entry.key?.lowercase(Locale.ROOT) ?: ""
            val value = entry.value
            if (isItemIdentityKey(key) && value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                gearForIdentifier(value.asString) != null
            ) return true
            if (isItemMetadataKey(key) || key == "components") {
                if (containsGearIdentity(value, 0, JsonBudget(2_000))) return true
            }
        }
        return false
    }

    private fun containsGearIdentity(element: JsonElement?, depth: Int, budget: JsonBudget): Boolean {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return false
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                if (containsGearIdentity(child, depth + 1, budget)) return true
            }
            return false
        }
        if (!element.isJsonObject) return false
        for (entry in element.asJsonObject.entrySet()) {
            val key = entry.key?.lowercase(Locale.ROOT) ?: ""
            val value = entry.value
            if (isItemIdentityKey(key) && value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                gearForIdentifier(value.asString) != null
            ) return true
            if (containsGearIdentity(value, depth + 1, budget)) return true
        }
        return false
    }

    private fun jsonValue(element: JsonElement?, depth: Int, budget: JsonBudget): Any? {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return null
        if (element.isJsonPrimitive) {
            val primitive = element.asJsonPrimitive
            if (primitive.isString) return element.asString
            if (primitive.isBoolean) return element.asBoolean
            return try {
                element.asNumber
            } catch (_: RuntimeException) {
                null
            }
        }
        if (element.isJsonArray) {
            val values = ArrayList<Any?>()
            for (child in element.asJsonArray) values.add(jsonValue(child, depth + 1, budget))
            return values
        }
        if (element.isJsonObject) {
            val values = LinkedHashMap<String, Any?>()
            for (entry in element.asJsonObject.entrySet()) values[entry.key] = jsonValue(entry.value, depth + 1, budget)
            return values
        }
        return null
    }

    private fun looksLikeCompressedNbt(value: String?): Boolean =
        value != null && value.length > 32 && value.length <= MAX_COMPRESSED_VALUE_CHARS &&
            BASE64_VALUE.matches(value)

    private fun readGearDetailsFromNbt(
        value: String,
        source: String,
        tooltipBudget: TooltipBudget,
        output: MutableList<DungeonStats.ItemDetails>,
    ) {
        try {
            val bytes = Base64.getDecoder().decode(value)
            if (bytes.isEmpty() || bytes.size > MAX_NBT_BYTES) return
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                DataInputStream(LimitedInputStream(gzip, MAX_NBT_BYTES)).use { input ->
                    val root = NbtTreeReader.read(input)
                    collectGearItems(root, source, tooltipBudget, output, 0)
                }
            }
        } catch (_: Exception) {
            // Invalid optional inventory details do not discard other profile statistics.
        }
    }

    private fun collectGearItems(
        value: Any?,
        source: String,
        tooltipBudget: TooltipBudget,
        output: MutableList<DungeonStats.ItemDetails>,
        depth: Int,
    ) {
        if (value == null || depth > MAX_NBT_DEPTH || tooltipBudget.exhausted()) return
        if (value is Map<*, *>) {
            if (isNbtItemObject(value)) {
                val item = itemDetailsFromObject(value, source, tooltipBudget)
                if (item != null) {
                    output.add(item)
                    return
                }
            }
            for (child in value.values) {
                collectGearItems(child, source, tooltipBudget, output, depth + 1)
                if (tooltipBudget.exhausted()) return
            }
        } else if (value is List<*>) {
            for (child in value) {
                collectGearItems(child, source, tooltipBudget, output, depth + 1)
                if (tooltipBudget.exhausted()) return
            }
        }
    }

    private fun isNbtItemObject(map: Map<*, *>): Boolean {
        for (entry in map.entries) {
            val key = entry.key.toString().lowercase(Locale.ROOT)
            if (isItemIdentityKey(key) && gearForIdentifier(profileText(entry.value)) != null) return true
            if ((isItemMetadataKey(key) || key == "components") && findGearIdentity(entry.value, 0) != null) return true
        }
        return false
    }

    private fun itemDetailsFromObject(
        value: Any?,
        source: String,
        tooltipBudget: TooltipBudget,
    ): DungeonStats.ItemDetails? {
        val map = value as? Map<*, *> ?: return null
        val gear = findGearIdentity(map, 0) ?: return null
        var identifier = findGearIdentifier(map, 0)
        var displayName = findDisplayName(map)
        if (displayName.isBlank()) displayName = gearDisplayName(gear)
        if (!tooltipBudget.takeItem()) return null
        displayName = tooltipBudget.take(displayName)
        identifier = tooltipBudget.take(identifier)
        val lore = collectLore(map, tooltipBudget)
        return DungeonStats.ItemDetails(gear, identifier, displayName, source, lore)
    }

    private fun findGearIdentity(value: Any?, depth: Int): DungeonStats.Gear? {
        if (value == null || depth > MAX_NBT_DEPTH) return null
        if (value is Map<*, *>) {
            for (entry in value.entries) {
                val key = entry.key.toString().lowercase(Locale.ROOT)
                if (isItemIdentityKey(key)) {
                    val gear = gearForIdentifier(profileText(entry.value))
                    if (gear != null) return gear
                }
                if (key != "lore" && key != "minecraft:lore") {
                    val nested = findGearIdentity(entry.value, depth + 1)
                    if (nested != null) return nested
                }
            }
        } else if (value is List<*>) {
            for (child in value) {
                val nested = findGearIdentity(child, depth + 1)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun findGearIdentifier(value: Any?, depth: Int): String {
        if (value == null || depth > MAX_NBT_DEPTH) return ""
        if (value is Map<*, *>) {
            for (entry in value.entries) {
                val key = entry.key.toString().lowercase(Locale.ROOT)
                if (isItemIdentityKey(key) && gearForIdentifier(profileText(entry.value)) != null) {
                    return profileText(entry.value)
                }
                if (key != "lore" && key != "minecraft:lore") {
                    val nested = findGearIdentifier(entry.value, depth + 1)
                    if (nested.isNotBlank()) return nested
                }
            }
        } else if (value is List<*>) {
            for (child in value) {
                val nested = findGearIdentifier(child, depth + 1)
                if (nested.isNotBlank()) return nested
            }
        }
        return ""
    }

    private fun findDisplayName(value: Any?): String {
        val tag = mapValue(value, "tag", "nbt")
        val display = mapValue(tag, "display")
        var result = profileText(mapValue(display, "Name", "name"))
        if (result.isNotBlank()) return result
        val components = mapValue(value, "components")
        result = profileText(mapValue(components, "minecraft:custom_name", "custom_name", "name"))
        if (result.isNotBlank()) return result
        return findNamedText(value, setOf("display_name", "displayname", "custom_name", "name"), 0)
    }

    private fun findNamedText(value: Any?, keys: Set<String>, depth: Int): String {
        if (value == null || depth > MAX_NBT_DEPTH) return ""
        if (value is Map<*, *>) {
            for (entry in value.entries) {
                val key = entry.key.toString().lowercase(Locale.ROOT)
                if (keys.contains(key)) {
                    val found = profileText(entry.value)
                    if (found.isNotBlank()) return found
                }
            }
            for (entry in value.entries) {
                val key = entry.key.toString().lowercase(Locale.ROOT)
                if (key != "lore" && key != "minecraft:lore") {
                    val found = findNamedText(entry.value, keys, depth + 1)
                    if (found.isNotBlank()) return found
                }
            }
        } else if (value is List<*>) {
            for (child in value) {
                val found = findNamedText(child, keys, depth + 1)
                if (found.isNotBlank()) return found
            }
        }
        return ""
    }

    private fun collectLore(value: Any?, tooltipBudget: TooltipBudget): List<String> {
        val lines = ArrayList<String>()
        collectLore(value, tooltipBudget, lines, 0)
        return java.util.List.copyOf(lines)
    }

    private fun collectLore(value: Any?, tooltipBudget: TooltipBudget, lines: MutableList<String>, depth: Int) {
        if (value == null || depth > MAX_NBT_DEPTH || lines.size >= MAX_GEAR_TOOLTIP_LINES ||
            tooltipBudget.exhausted()
        ) return
        if (value is Map<*, *>) {
            for (entry in value.entries) {
                val key = entry.key.toString().lowercase(Locale.ROOT)
                if (key == "lore" || key == "minecraft:lore") {
                    val loreValue = entry.value
                    if (loreValue is List<*>) {
                        for (line in loreValue) {
                            if (lines.size >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) break
                            val text = profileText(line)
                            if (text.isNotBlank()) lines.add(tooltipBudget.take(text))
                        }
                    } else {
                        val text = profileText(loreValue)
                        if (text.isNotBlank()) lines.add(tooltipBudget.take(text))
                    }
                } else {
                    collectLore(entry.value, tooltipBudget, lines, depth + 1)
                }
                if (lines.size >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) return
            }
        } else if (value is List<*>) {
            for (child in value) {
                collectLore(child, tooltipBudget, lines, depth + 1)
                if (lines.size >= MAX_GEAR_TOOLTIP_LINES || tooltipBudget.exhausted()) return
            }
        }
    }

    private fun mapValue(value: Any?, vararg keys: String): Any? {
        val map = value as? Map<*, *> ?: return null
        for (wanted in keys) {
            for (entry in map.entries) {
                if (wanted.equals(entry.key.toString(), ignoreCase = true)) return entry.value
            }
        }
        return null
    }

    private fun profileText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is Map<*, *>, is List<*> -> com.google.gson.Gson().toJson(value)
        else -> ""
    }

    private fun gearForIdentifier(value: String?): DungeonStats.Gear? {
        if (value == null) return null
        val normalized = value.trim().lowercase(Locale.ROOT).replace("minecraft:", "").replace(' ', '_')
        return when (normalized) {
            "terminator" -> DungeonStats.Gear.TERMINATOR
            "hyperion" -> DungeonStats.Gear.HYPERION
            "golden_dragon" -> DungeonStats.Gear.GOLDEN_DRAGON
            else -> null
        }
    }

    private fun gearDisplayName(gear: DungeonStats.Gear): String = when (gear) {
        DungeonStats.Gear.TERMINATOR -> "Terminator"
        DungeonStats.Gear.HYPERION -> "Hyperion"
        DungeonStats.Gear.GOLDEN_DRAGON -> "Golden Dragon"
    }

    private fun inventoryLabel(key: String?): String = when (key?.lowercase(Locale.ROOT) ?: "") {
        "inv_contents", "inventory", "items", "item_data" -> "Inventory"
        "ender_chest_contents" -> "Ender Chest"
        "armor_contents" -> "Armor"
        "talisman_bag" -> "Accessory Bag"
        "wardrobe_contents" -> "Wardrobe"
        "equipment_contents" -> "Equipment"
        else -> key?.replace('_', ' ') ?: "Inventory"
    }

    private fun collectGoldenDragonDetails(member: JsonObject): List<DungeonStats.GoldenDragonPet> {
        val petsData = jsonObject(member, "pets_data")
        val pets = if (petsData == null) member.get("pets") else petsData.get("pets")
        val output = ArrayList<DungeonStats.GoldenDragonPet>()
        collectGoldenDragonDetails(pets, output, 0, JsonBudget(MAX_JSON_NODES), TooltipBudget())
        return java.util.List.copyOf(output)
    }

    private fun collectGoldenDragonDetails(
        element: JsonElement?,
        output: MutableList<DungeonStats.GoldenDragonPet>,
        depth: Int,
        budget: JsonBudget,
        detailBudget: TooltipBudget,
    ) {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit() ||
            output.size >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()
        ) return
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectGoldenDragonDetails(child, output, depth + 1, budget, detailBudget)
                if (output.size >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()) return
            }
            return
        }
        if (!element.isJsonObject) return
        val json = element.asJsonObject
        if (isGoldenDragonPet(json)) {
            if (!detailBudget.takeItem()) return
            output.add(
                DungeonStats.GoldenDragonPet(
                    detailBudget.take(firstPrimitiveText(json, "tier", "rarity")),
                    detailBudget.take(firstPrimitiveText(json, "level", "lvl")),
                    detailBudget.take(firstPrimitiveText(json, "exp", "experience", "xp")),
                    detailBudget.take(firstPrimitiveText(json, "heldItem", "held_item", "helditem")),
                    detailBudget.take(firstPrimitiveText(json, "skin")),
                    primitiveBoolean(json, "active"),
                ),
            )
            return
        }
        for (entry in json.entrySet()) {
            collectGoldenDragonDetails(entry.value, output, depth + 1, budget, detailBudget)
            if (output.size >= MAX_GOLDEN_DRAGON_PETS || detailBudget.exhausted()) return
        }
    }

    private fun isGoldenDragonPet(json: JsonObject?): Boolean {
        if (json == null) return false
        for (key in arrayOf("type", "pet_type", "id", "internal_name")) {
            if (normalizeIdentifier(firstPrimitiveText(json, key)) == "golden_dragon") return true
        }
        return false
    }

    private fun firstPrimitiveText(json: JsonObject?, vararg keys: String): String {
        if (json == null) return ""
        for (key in keys) {
            for (entry in json.entrySet()) {
                if (key.equals(entry.key, ignoreCase = true) && entry.value.isJsonPrimitive &&
                    !entry.value.asJsonPrimitive.isBoolean
                ) return entry.value.asString
            }
        }
        return ""
    }

    private fun primitiveBoolean(json: JsonObject?, key: String): Boolean? {
        if (json == null) return null
        for (entry in json.entrySet()) {
            if (!key.equals(entry.key, ignoreCase = true) || !entry.value.isJsonPrimitive ||
                !entry.value.asJsonPrimitive.isBoolean
            ) continue
            return entry.value.asBoolean
        }
        return null
    }

    private fun readPbs(output: MutableMap<DungeonFloor, Long>, type: JsonObject?, master: Boolean) {
        if (type == null) return
        val fastest = jsonObject(type, "fastest_time_s_plus") ?: jsonObject(type, "fastestTimeSPlus") ?: return
        for (floor in 1..7) {
            val raw = firstLong(fastest, floor.toString(), "floor_$floor", "F$floor")
            if (raw <= 0) continue
            val seconds = if (raw > 10_000) raw / 1_000 else raw
            val parsedFloor = DungeonFloor.parse((if (master) "M" else "F") + floor) ?: continue
            output[parsedFloor] = seconds
        }
    }

    private fun hasPersonalBestData(type: JsonObject?): Boolean =
        jsonObject(type, "fastest_time_s_plus") != null || jsonObject(type, "fastestTimeSPlus") != null

    private fun totalRuns(normal: JsonObject?, master: JsonObject?): Long = completions(normal) + completions(master)

    private fun completions(type: JsonObject?): Long {
        val completions = jsonObject(type, "tier_completions") ?: jsonObject(type, "tierCompletions") ?: return 0L
        var total = 0L
        for (floor in 1..7) total += firstLong(completions, floor.toString(), "floor_$floor")
        return total
    }

    private fun hasCompletions(type: JsonObject?): Boolean {
        val completions = jsonObject(type, "tier_completions") ?: jsonObject(type, "tierCompletions") ?: return false
        for (floor in 1..7) if (hasNumber(completions, floor.toString(), "floor_$floor")) return true
        return false
    }

    private fun collectItemText(element: JsonElement?): String {
        val out = StringBuilder()
        collectInventoryIdentifiers(element, out, 0, JsonBudget(MAX_JSON_NODES), false)
        return out.toString().lowercase(Locale.ROOT)
    }

    /** Walk only item identity fields; lore and status text are deliberately ignored. */
    private fun collectInventoryIdentifiers(
        element: JsonElement?,
        out: StringBuilder,
        depth: Int,
        budget: JsonBudget,
        insideInventory: Boolean,
    ) {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return
        if (element.isJsonPrimitive) {
            if (!insideInventory || !element.asJsonPrimitive.isString) return
            val value = element.asString
            if (likelyItemIdentifier(value)) appendLimited(out, value)
            else if (value.length > 32 && value.length <= MAX_COMPRESSED_VALUE_CHARS && BASE64_VALUE.matches(value)) {
                searchCompressedNbt(value, out)
            }
            return
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                collectInventoryIdentifiers(child, out, depth + 1, budget, insideInventory)
                if (out.length >= MAX_ITEM_TEXT_CHARS) return
            }
            return
        }
        if (!element.isJsonObject) return
        for (entry in element.asJsonObject.entrySet()) {
            val key = entry.key?.lowercase(Locale.ROOT) ?: ""
            val value = entry.value
            when {
                isInventoryKey(key) -> collectInventoryIdentifiers(value, out, depth + 1, budget, true)
                insideInventory && isItemIdentityKey(key) -> collectIdentityValue(value, out, depth + 1, budget)
                insideInventory && key == "data" -> collectIdentityValue(value, out, depth + 1, budget)
                insideInventory && isItemMetadataKey(key) -> collectInventoryIdentifiers(value, out, depth + 1, budget, true)
                insideInventory && (value.isJsonObject || value.isJsonArray) ->
                    collectInventoryIdentifiers(value, out, depth + 1, budget, true)
            }
            if (out.length >= MAX_ITEM_TEXT_CHARS) return
        }
    }

    private fun collectIdentityValue(value: JsonElement?, out: StringBuilder, depth: Int, budget: JsonBudget) {
        if (value == null || value.isJsonNull) return
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            val text = value.asString
            if (likelyItemIdentifier(text)) appendLimited(out, text)
            else if (text.length > 32 && text.length <= MAX_COMPRESSED_VALUE_CHARS && BASE64_VALUE.matches(text)) {
                searchCompressedNbt(text, out)
            }
            return
        }
        collectInventoryIdentifiers(value, out, depth, budget, true)
    }

    private fun appendLimited(out: StringBuilder, value: String?) {
        if (value == null || out.length >= MAX_ITEM_TEXT_CHARS) return
        val remaining = MAX_ITEM_TEXT_CHARS - out.length
        out.append(value.substring(0, minOf(value.length, remaining))).append(' ')
    }

    private fun isInventoryKey(key: String?): Boolean {
        if (key == null) return false
        val normalized = key.lowercase(Locale.ROOT)
        return normalized == "inv_contents" || normalized == "ender_chest_contents" ||
            normalized == "armor_contents" || normalized == "talisman_bag" ||
            normalized == "wardrobe_contents" || normalized == "equipment_contents" ||
            normalized == "inventory" || normalized == "items" || normalized == "item_data" ||
            normalized.endsWith("_contents")
    }

    private fun isItemIdentityKey(key: String): Boolean =
        key == "id" || key == "item_id" || key == "itemid" || key == "item_name" ||
            key == "itemname" || key == "internalname" || key == "internal_name" ||
            key == "displayname" || key == "display_name"

    private fun isItemMetadataKey(key: String): Boolean =
        key == "tag" || key == "nbt" || key == "extra_attributes" || key == "extraattributes"

    private fun searchCompressedNbt(value: String, out: StringBuilder) {
        searchCompressedNbtReadable(value, out)
    }

    private fun searchCompressedNbtReadable(value: String, out: StringBuilder): Boolean {
        try {
            val bytes = Base64.getDecoder().decode(value)
            if (bytes.isEmpty() || bytes.size > MAX_NBT_BYTES) return false
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gzip ->
                DataInputStream(LimitedInputStream(gzip, MAX_NBT_BYTES)).use { input ->
                    NbtTextReader.read(input, out)
                }
            }
            return true
        } catch (_: Exception) {
            // Inventory fields are optional and not every long string is compressed NBT.
            return false
        }
    }

    private fun hasInventoryData(element: JsonElement?): Boolean =
        findInventoryData(element, 0, JsonBudget(MAX_JSON_NODES))

    private fun findInventoryData(element: JsonElement?, depth: Int, budget: JsonBudget): Boolean {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return false
        if (element.isJsonArray) {
            for (child in element.asJsonArray) if (findInventoryData(child, depth + 1, budget)) return true
            return false
        }
        if (!element.isJsonObject) return false
        for (entry in element.asJsonObject.entrySet()) {
            val value = entry.value
            if (isInventoryKey(entry.key)) {
                // Inventory metadata can be arbitrary; only a readable payload establishes availability.
                if (inventoryValueIsReadable(value)) return true
                continue
            }
            if (findInventoryData(value, depth + 1, budget)) return true
        }
        return false
    }

    private fun inventoryValueIsReadable(element: JsonElement?): Boolean =
        inventoryValueIsReadable(element, 0, JsonBudget(MAX_JSON_NODES), true)

    private fun inventoryValueIsReadable(
        element: JsonElement?,
        depth: Int,
        budget: JsonBudget,
        allowPrimitive: Boolean,
    ): Boolean {
        if (element == null || element.isJsonNull) return false
        if (depth > MAX_JSON_DEPTH || !budget.visit()) return false
        if (element.isJsonObject) {
            val json = element.asJsonObject
            if (isItemObject(json)) return true
            for (entry in json.entrySet()) {
                val key = entry.key?.lowercase(Locale.ROOT) ?: ""
                val child = entry.value
                if (isItemIdentityKey(key) && identityValueIsReadable(child, depth + 1, budget)) return true
                if (key == "data" && identityValueIsReadable(child, depth + 1, budget)) return true
                if ((isItemMetadataKey(key) || child.isJsonObject || child.isJsonArray) &&
                    inventoryValueIsReadable(child, depth + 1, budget, false)
                ) return true
            }
            return false
        }
        if (element.isJsonArray) {
            if (element.asJsonArray.isEmpty) return false
            for (child in element.asJsonArray) {
                if (inventoryValueIsReadable(child, depth + 1, budget, allowPrimitive)) return true
            }
            return false
        }
        if (!allowPrimitive || !element.isJsonPrimitive || !element.asJsonPrimitive.isString) return false
        val text = element.asString
        if (text.isBlank() || text.length > MAX_COMPRESSED_VALUE_CHARS) return false
        if (text.length > 32 && BASE64_VALUE.matches(text)) {
            return searchCompressedNbtReadable(text, StringBuilder())
        }
        return likelyItemIdentifier(text)
    }

    /** A container needs item-shaped data; arbitrary status/error strings never authorize a gear check. */
    private fun isItemObject(json: JsonObject): Boolean {
        for (entry in json.entrySet()) {
            val key = entry.key.lowercase(Locale.ROOT)
            if (!ITEM_ID_KEYS.contains(key)) continue
            val value = entry.value
            if (value == null || value.isJsonNull) continue
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                if (isItemIdentityKey(key) && likelyItemIdentifier(value.asString)) return true
            } else if (key == "tag" || key == "nbt") {
                if (inventoryValueIsReadable(value)) return true
            }
        }
        return false
    }

    private fun identityValueIsReadable(element: JsonElement?, depth: Int, budget: JsonBudget): Boolean {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return false
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return false
        val text = element.asString
        if (likelyItemIdentifier(text)) return true
        return text.length > 32 && text.length <= MAX_COMPRESSED_VALUE_CHARS && BASE64_VALUE.matches(text) &&
            searchCompressedNbtReadable(text, StringBuilder())
    }

    private fun likelyItemIdentifier(value: String): Boolean {
        val normalized = value.trim()
        val lower = normalized.lowercase(Locale.ROOT)
        if (lower == "terminator" || lower == "hyperion" || lower == "golden_dragon" || lower == "golden dragon") {
            return true
        }
        if (normalized.contains(':')) return NAMESPACED_ITEM_IDENTIFIER.matches(normalized)
        return INTERNAL_ITEM_IDENTIFIER.matches(normalized)
    }

    private fun containsItemIdentifier(text: String?, identifier: String?): Boolean {
        if (text == null || identifier.isNullOrBlank()) return false
        val lower = text.lowercase(Locale.ROOT)
        val needle = identifier.lowercase(Locale.ROOT)
        var from = 0
        while (true) {
            from = lower.indexOf(needle, from)
            if (from < 0) return false
            val end = from + needle.length
            val left = from == 0 || !lower[from - 1].isLetterOrDigit()
            val right = end == lower.length || !lower[end].isLetterOrDigit()
            if (left && right) return true
            from = end
        }
    }

    /** Pet data is a separate API section; it must not be inferred from inventory availability. */
    private fun containsPetIdentifier(member: JsonObject?, identifier: String?): Boolean {
        if (member == null || identifier.isNullOrBlank()) return false
        val petsData = jsonObject(member, "pets_data")
        val pets = if (petsData == null) member.get("pets") else petsData.get("pets")
        return containsPetIdentifier(pets, identifier, 0, JsonBudget(MAX_JSON_NODES))
    }

    private fun containsPetIdentifier(
        element: JsonElement?,
        identifier: String,
        depth: Int,
        budget: JsonBudget,
    ): Boolean {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) return false
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return normalizeIdentifier(element.asString) == normalizeIdentifier(identifier)
        }
        if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                if (containsPetIdentifier(child, identifier, depth + 1, budget)) return true
            }
            return false
        }
        if (!element.isJsonObject) return false
        for (entry in element.asJsonObject.entrySet()) {
            val key = entry.key?.lowercase(Locale.ROOT) ?: ""
            val value = entry.value
            if ((key == "type" || key == "pet_type" || key == "id" || key == "internal_name") &&
                value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                normalizeIdentifier(value.asString) == normalizeIdentifier(identifier)
            ) return true
            if (containsPetIdentifier(value, identifier, depth + 1, budget)) return true
        }
        return false
    }

    private fun hasPetData(member: JsonObject?): Boolean {
        if (member == null) return false
        val petsData = jsonObject(member, "pets_data")
        if (petsData != null && jsonArray(petsData, "pets") != null) return true
        return member.has("pets") && member.get("pets").isJsonArray
    }

    private fun normalizeIdentifier(value: String?): String =
        value?.trim()?.lowercase(Locale.ROOT)?.replace(' ', '_') ?: ""

    private fun jsonObject(parent: JsonObject?, name: String): JsonObject? {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject) return null
        return parent.getAsJsonObject(name)
    }

    private fun jsonArray(parent: JsonObject?, name: String): JsonArray? {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray) return null
        return parent.getAsJsonArray(name)
    }

    private fun number(json: JsonObject?, key: String): Long {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive) return 0L
        return try {
            json.get(key).asLong
        } catch (_: RuntimeException) {
            0L
        }
    }

    private fun firstLong(json: JsonObject?, vararg keys: String): Long {
        for (key in keys) {
            val value = number(json, key)
            if (value != 0L) return value
        }
        return 0L
    }

    private fun hasNumber(json: JsonObject?, vararg keys: String): Boolean {
        if (json == null) return false
        for (key in keys) {
            if (!json.has(key) || !json.get(key).isJsonPrimitive) continue
            try {
                json.get(key).asLong
                return true
            } catch (_: RuntimeException) {
                // Try the next API spelling.
            }
        }
        return false
    }

    private data class NumberValue(val value: Long, val present: Boolean) {
        companion object {
            val MISSING = NumberValue(0L, false)
        }
    }

    private fun findNumber(element: JsonElement?, vararg keys: String): NumberValue =
        findNumber(element, 0, JsonBudget(MAX_JSON_NODES), keys)

    private fun findNumber(
        element: JsonElement?,
        depth: Int,
        budget: JsonBudget,
        keys: Array<out String>,
    ): NumberValue {
        if (element == null || element.isJsonNull || depth > MAX_JSON_DEPTH || !budget.visit()) {
            return NumberValue.MISSING
        }
        if (element.isJsonObject) {
            val json = element.asJsonObject
            for (key in keys) if (hasNumber(json, key)) return NumberValue(number(json, key), true)
            for (entry in json.entrySet()) {
                val found = findNumber(entry.value, depth + 1, budget, keys)
                if (found.present) return found
            }
        } else if (element.isJsonArray) {
            for (child in element.asJsonArray) {
                val found = findNumber(child, depth + 1, budget, keys)
                if (found.present) return found
            }
        }
        return NumberValue.MISSING
    }

    private class JsonBudget(maximumNodes: Int) {
        private var remaining = maximumNodes

        fun visit(): Boolean {
            val available = remaining > 0
            remaining--
            return available
        }
    }

    private fun profileForMember(root: JsonObject?, uuid: String): JsonObject? {
        val profiles = jsonArray(root, "profiles") ?: return null
        val compact = uuid.replace("-", "")
        val dashed = withDashes(compact)
        var fallback: JsonObject? = null
        var inspected = 0
        for (element in profiles) {
            inspected++
            if (inspected > 128) break
            if (!element.isJsonObject) continue
            val profile = element.asJsonObject
            val members = jsonObject(profile, "members")
            if (members == null || (!members.has(compact) && !members.has(dashed))) continue
            if (booleanValue(profile, "selected")) return profile
            if (fallback == null) fallback = profile
        }
        return fallback
    }

    private fun booleanValue(json: JsonObject?, key: String): Boolean {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive) return false
        return try {
            json.get(key).asBoolean
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun firstString(json: JsonObject?, vararg keys: String): String? {
        for (key in keys) {
            val value = string(json, key)
            if (value != null) return value
        }
        return null
    }

    private fun string(json: JsonObject?, key: String): String? {
        if (json == null || !json.has(key) || !json.get(key).isJsonPrimitive) return null
        return try {
            json.get(key).asString
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun withDashes(id: String): String {
        val value = id.replace("-", "")
        if (value.length != 32) return id
        return value.substring(0, 8) + "-" + value.substring(8, 12) + "-" +
            value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20)
    }

    private class TooltipBudget {
        private var remainingItems = MAX_GEAR_TOOLTIP_ITEMS
        private var remainingChars = MAX_GEAR_TOOLTIP_CHARS

        fun takeItem(): Boolean {
            if (remainingItems <= 0 || remainingChars <= 0) return false
            remainingItems--
            return true
        }

        fun take(value: String?): String {
            if (value.isNullOrEmpty() || remainingChars <= 0) return ""
            val allowed = minOf(value.length, MAX_GEAR_TOOLTIP_LINE_CHARS, remainingChars)
            remainingChars -= allowed
            if (value.length <= allowed) return value
            if (allowed <= 1) return value.substring(0, allowed)
            return value.substring(0, allowed - 1) + "…"
        }

        fun exhausted(): Boolean = remainingItems <= 0 || remainingChars <= 0
    }

    private class NbtBudget(maximumNodes: Int = MAX_NBT_NODES) {
        private var nodes = maximumNodes
        private var bytes = MAX_NBT_BYTES

        fun visit(): Boolean {
            val available = nodes > 0
            nodes--
            return available
        }

        fun consumeBytes(amount: Int): Boolean {
            if (amount < 0 || amount > bytes) return false
            bytes -= amount
            return true
        }
    }

    /** Parses bounded NBT values into detached data, keeping Minecraft tooltip work on the client thread. */
    private object NbtTreeReader {
        @Throws(IOException::class)
        fun read(input: DataInputStream, maximumNodes: Int = MAX_NBT_NODES): Any? {
            val budget = NbtBudget(maximumNodes)
            val type = input.readUnsignedByte()
            if (type == 0) return null
            readString(input, budget)
            return readPayload(input, type, budget, 0)
        }

        @Throws(IOException::class)
        private fun readPayload(input: DataInputStream, type: Int, budget: NbtBudget, depth: Int): Any? {
            if (depth > MAX_NBT_DEPTH || !budget.visit()) throw IOException("NBT limits exceeded")
            return when (type) {
                1 -> input.readByte()
                2 -> input.readShort()
                3 -> input.readInt()
                4 -> input.readLong()
                5 -> input.readFloat()
                6 -> input.readDouble()
                7 -> {
                    NbtTextReader.skipArray(input, input.readInt(), 1, budget)
                    null
                }
                8 -> readString(input, budget)
                9 -> {
                    val childType = input.readUnsignedByte()
                    val count = input.readInt()
                    if (count < 0 || count > MAX_NBT_NODES || (childType == 0 && count != 0)) {
                        throw IOException("Invalid NBT list length")
                    }
                    val list = ArrayList<Any?>(count)
                    repeat(count) { list.add(readPayload(input, childType, budget, depth + 1)) }
                    list
                }
                10 -> {
                    val compound = LinkedHashMap<String, Any?>()
                    while (true) {
                        val childType = input.readUnsignedByte()
                        if (childType == 0) break
                        val key = readString(input, budget)
                        compound[key] = readPayload(input, childType, budget, depth + 1)
                    }
                    compound
                }
                11 -> {
                    NbtTextReader.skipArray(input, input.readInt(), 4, budget)
                    null
                }
                12 -> {
                    NbtTextReader.skipArray(input, input.readInt(), 8, budget)
                    null
                }
                else -> throw IOException("Unknown NBT tag $type")
            }
        }

        @Throws(IOException::class)
        private fun readString(input: DataInputStream, budget: NbtBudget): String {
            val length = input.readUnsignedShort()
            if (length > MAX_NBT_STRING_CHARS || !budget.consumeBytes(length)) {
                throw IOException("NBT string limit exceeded")
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    /** Minimal bounded NBT text walker used only to find item ids in compressed inventory fields. */
    private object NbtTextReader {
        @Throws(IOException::class)
        fun read(input: DataInputStream, out: StringBuilder) {
            val budget = NbtBudget()
            val type = input.readUnsignedByte()
            if (type == 0) return
            readString(input, budget)
            readPayload(input, type, out, budget, 0, "")
        }

        @Throws(IOException::class)
        private fun readPayload(
            input: DataInputStream,
            type: Int,
            out: StringBuilder,
            budget: NbtBudget,
            depth: Int,
            key: String?,
        ) {
            if (depth > MAX_NBT_DEPTH || !budget.visit()) throw IOException("NBT limits exceeded")
            when (type) {
                1 -> input.readByte()
                2 -> input.readShort()
                3 -> input.readInt()
                4 -> input.readLong()
                5 -> input.readFloat()
                6 -> input.readDouble()
                7 -> skipBytes(input, input.readInt(), budget)
                8 -> {
                    val value = readString(input, budget)
                    val normalizedKey = key?.lowercase(Locale.ROOT) ?: ""
                    if (isItemIdentityKey(normalizedKey) && likelyItemIdentifier(value)) appendLimited(out, value)
                }
                9 -> {
                    val childType = input.readUnsignedByte()
                    val count = input.readInt()
                    if (count < 0 || count > MAX_NBT_NODES) throw IOException("Invalid NBT list length")
                    repeat(count) { readPayload(input, childType, out, budget, depth + 1, key) }
                }
                10 -> {
                    while (true) {
                        val childType = input.readUnsignedByte()
                        if (childType == 0) break
                        val childKey = readString(input, budget)
                        readPayload(input, childType, out, budget, depth + 1, childKey)
                    }
                }
                11 -> skipArray(input, input.readInt(), 4, budget)
                12 -> skipArray(input, input.readInt(), 8, budget)
                else -> throw IOException("Unknown NBT tag $type")
            }
        }

        @Throws(IOException::class)
        private fun readString(input: DataInputStream, budget: NbtBudget): String {
            val length = input.readUnsignedShort()
            if (length > MAX_NBT_STRING_CHARS || !budget.consumeBytes(length)) {
                throw IOException("NBT string limit exceeded")
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        @Throws(IOException::class)
        fun skipArray(input: DataInputStream, count: Int, bytesPerValue: Int, budget: NbtBudget) {
            val byteCount = count.toLong() * bytesPerValue
            if (count < 0 || byteCount > MAX_NBT_BYTES || byteCount > Int.MAX_VALUE ||
                !budget.consumeBytes(byteCount.toInt())
            ) throw IOException("NBT array limit exceeded")
            skipRawBytes(input, byteCount.toInt())
        }

        @Throws(IOException::class)
        private fun skipBytes(input: DataInputStream, count: Int, budget: NbtBudget) {
            if (count < 0 || count > MAX_NBT_BYTES || !budget.consumeBytes(count)) {
                throw IOException("NBT byte limit exceeded")
            }
            skipRawBytes(input, count)
        }

        @Throws(IOException::class)
        private fun skipRawBytes(input: DataInputStream, count: Int) {
            input.skipNBytes(count.toLong())
        }
    }

    /** Stops a compressed inventory payload from expanding without bound while it is parsed. */
    private class LimitedInputStream(input: InputStream, limit: Int) : FilterInputStream(input) {
        private var remaining = limit

        @Throws(IOException::class)
        override fun read(): Int {
            if (remaining <= 0) throw IOException("Input limit exceeded")
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        @Throws(IOException::class)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) throw IOException("Input limit exceeded")
            val allowed = minOf(length, remaining)
            val read = super.read(bytes, offset, allowed)
            if (read > 0) remaining -= read
            return read
        }

        @Throws(IOException::class)
        override fun skip(count: Long): Long {
            if (count <= 0 || remaining <= 0) return 0L
            val skipped = super.skip(minOf(count, remaining.toLong()))
            if (skipped > 0) remaining -= skipped.toInt()
            return skipped
        }
    }

    private val BASE64_VALUE = Regex("[A-Za-z0-9+/=]+")
    private val NAMESPACED_ITEM_IDENTIFIER = Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_./-]+")
    private val INTERNAL_ITEM_IDENTIFIER = Regex("[A-Z0-9]+(?:_[A-Z0-9]+)+")
}
