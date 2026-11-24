package geiler.addons.client.carnival

import com.google.gson.JsonParser
import geiler.addons.client.dungeon.ClientJsonFile
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate

/**
 * What the Carnival module remembers between sessions (`config/geileraddons/carnival.json`): reached goals for the
 * current SkyBlock year, the last UTC day tickets were claimed and each token shop's purchased upgrade levels.
 * Also reads whether Foxy's Chivalrous Carnival perk is active from the public Hypixel election resource.
 */
class CarnivalStore {
    class Data {
        @JvmField var goalsYear: Int = -1
        @JvmField var goals: MutableList<String> = ArrayList()
        @JvmField var lastClaimedDay: String? = null
        @JvmField var shops: MutableMap<String, MutableMap<String, Int>> = HashMap()
    }

    private var data = Data()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        val path = path() ?: return
        ClientJsonFile.read(path, Data::class.java)?.let {
            if (it.goals == null) it.goals = ArrayList()
            if (it.shops == null) it.shops = HashMap()
            data = it
        }
    }

    /** Goals not yet reached this SkyBlock year (a new year clears them, as SkyHanni does). */
    fun openGoals(): List<CarnivalGoal> {
        val year = skyblockYear(System.currentTimeMillis())
        val reached = if (data.goalsYear == year) data.goals.toSet() else emptySet()
        return CarnivalGoal.entries.filter { it.name !in reached }
    }

    fun setGoal(goal: CarnivalGoal, reached: Boolean, year: Int) {
        if (data.goalsYear != year) {
            data.goalsYear = year
            data.goals.clear()
        }
        val changed = if (reached) !data.goals.contains(goal.name) && data.goals.add(goal.name) else data.goals.remove(goal.name)
        if (changed) save()
    }

    fun claimedOn(day: LocalDate): Boolean = data.lastClaimedDay == day.toString()

    fun markClaimed(day: LocalDate) {
        if (data.lastClaimedDay == day.toString()) return
        data.lastClaimedDay = day.toString()
        save()
    }

    fun shopProgress(shop: String): Map<String, Int>? = data.shops.entries.firstOrNull { it.key.equals(shop, true) }?.value

    fun saveShop(shop: String, purchased: Map<String, Int>) {
        if (data.shops[shop] == purchased) return
        data.shops[shop] = HashMap(purchased)
        save()
    }

    private fun save() {
        val path = path() ?: return
        ClientJsonFile.writeAsync(path, ClientJsonFile.GSON.toJson(data))
    }

    private fun path(): Path? = try {
        FabricLoader.getInstance().configDir?.resolve("geileraddons")?.resolve("carnival.json")
    } catch (_: NullPointerException) {
        null
    }

    companion object {
        // SkyBlock time: year 1 began 2019-06-11 17:55 UTC and a year lasts 124 real hours (SkyHanni SkyBlockTime).
        private const val SKYBLOCK_EPOCH_MS = 1_560_275_700_000L
        private const val SKYBLOCK_YEAR_MS = 446_400_000L
        private const val ELECTION_URL = "https://api.hypixel.net/v2/resources/skyblock/election"
        private const val MAYOR_REFRESH_MS = 60 * 60_000L
        private const val PERK = "Chivalrous Carnival"

        @Volatile private var perkActive = false
        @Volatile private var lastMayorFetch = 0L
        @Volatile private var fetching = false

        @JvmStatic
        fun skyblockYear(nowMs: Long): Int = ((nowMs - SKYBLOCK_EPOCH_MS) / SKYBLOCK_YEAR_MS).toInt() + 1

        @JvmStatic
        fun carnivalPerkActive(): Boolean = perkActive

        /** True when the mayor's or the minister's perks include Chivalrous Carnival. */
        @JvmStatic
        fun perkInElection(json: String): Boolean {
            val root = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return false }
            val mayor = root.getAsJsonObject("mayor") ?: return false
            val perks = mayor.getAsJsonArray("perks")?.mapNotNull { it.asJsonObject.get("name")?.asString }.orEmpty()
            val minister = mayor.getAsJsonObject("minister")?.getAsJsonObject("perk")?.get("name")?.asString
            return PERK in perks || minister == PERK
        }

        /** Refreshes the perk flag at most once an hour, off the client thread. */
        @JvmStatic
        fun refreshMayor() {
            val now = System.currentTimeMillis()
            if (fetching || now - lastMayorFetch < MAYOR_REFRESH_MS) return
            fetching = true
            lastMayorFetch = now
            Thread({
                try {
                    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    val request = HttpRequest.newBuilder(URI.create(ELECTION_URL)).timeout(Duration.ofSeconds(15)).GET().build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    if (response.statusCode() == 200) perkActive = perkInElection(response.body())
                } catch (_: Exception) {
                    // Keep the last known value; the next hourly refresh retries.
                } finally {
                    fetching = false
                }
            }, "geileraddons-carnival-mayor").also { it.isDaemon = true }.start()
        }
    }
}
