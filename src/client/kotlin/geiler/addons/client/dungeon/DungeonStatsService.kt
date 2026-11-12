package geiler.addons.client.dungeon

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.util.UUID
import java.util.function.Consumer

/** Stable Java ABI for profile requests, shared by Kotlin modules and legacy Java call sites. */
object DungeonStatsService {
    @JvmStatic
    fun fetch(name: String?, callback: Consumer<Result>?) {
        DungeonStatsServiceCore.fetch(name, callback, false)
    }

    /** Fetches a fresh profile even when the normal five-minute cache contains an older result. */
    @JvmStatic
    fun fetchFresh(name: String?, callback: Consumer<Result>?) {
        DungeonStatsServiceCore.fetch(name, callback, true)
    }

    @JvmStatic
    fun clearCache() {
        DungeonStatsServiceCore.clearCache()
    }

    /** Cancels outstanding profile work when the client is stopping. */
    @JvmStatic
    fun close() {
        DungeonStatsServiceCore.close()
    }

    @JvmRecord
    data class Result(val stats: DungeonStats?, val error: String?) {
        companion object {
            @JvmStatic
            fun success(stats: DungeonStats?): Result = Result(stats, null)

            @JvmStatic
            fun failure(error: String?): Result = Result(null, error)
        }

        fun available(): Boolean = stats != null
    }

    @JvmRecord
    data class CataProgress(
        val level: Int,
        val totalExperience: Long,
        val experienceIntoLevel: Long,
        val experienceForLevel: Long,
        val overflowExperience: Long,
        val capped: Boolean,
    )

    /** Formats the capped API progression, optionally showing level-50 overflow to two decimals. */
    @JvmStatic
    fun formatCatacombsLevel(totalExperience: Long, showOverflow: Boolean): String =
        DungeonStatsServiceCore.formatCatacombsLevel(totalExperience, showOverflow)

    /** Breaks down the API's raw Catacombs XP without discarding level-50 overflow. */
    @JvmStatic
    fun catacombsProgress(totalExperience: Long): CataProgress =
        DungeonStatsServiceCore.catacombsProgress(totalExperience)

    @JvmStatic
    fun formatTime(seconds: Long): String = DungeonStatsServiceCore.formatTime(seconds)

    /** Package boundary for the offline profile fixtures; production requests enter through [fetch]. */
    @JvmStatic
    fun parseForChecks(
        name: String,
        uuid: UUID,
        root: JsonObject,
        member: JsonObject,
        endpointSecrets: Long?,
    ): DungeonStats = DungeonStatsServiceCore.parseForChecks(name, uuid, root, member, endpointSecrets)

    /** Package boundary for the offline inventory-evidence fixtures. */
    @JvmStatic
    fun hasValidatedInventoryData(element: JsonElement?): Boolean =
        DungeonStatsServiceCore.hasValidatedInventoryData(element)

    @JvmStatic
    fun endpointAllowedForChecks(uri: URI?): Boolean = DungeonStatsServiceCore.endpointAllowedForChecks(uri)

    @JvmStatic
    fun retryAfterForChecks(retryAfter: String?): Long = DungeonStatsServiceCore.retryAfterForChecks(retryAfter)

    @JvmStatic
    fun encodePathForChecks(value: String): String = DungeonStatsServiceCore.encodePathForChecks(value)
}
