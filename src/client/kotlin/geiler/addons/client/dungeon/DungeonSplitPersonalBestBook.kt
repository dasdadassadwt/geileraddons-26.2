package geiler.addons.client.dungeon

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import geiler.addons.client.dungeon.odin.DungeonSplitTracker
import java.io.IOException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.LinkedHashMap
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Immutable per-floor/per-split best and latest measurements; it does not retain completed runs. */
class DungeonSplitPersonalBestBook private constructor(entries: Map<Key, Entry>) {
    private val storedEntries: Map<Key, Entry> = java.util.Map.copyOf(entries)

    fun find(floor: DungeonFloor?, splitId: String?): Entry? {
        if (floor == null || splitId == null) return null
        return storedEntries[Key(floor, splitId)]
    }

    fun entries(): List<Entry> = java.util.List.copyOf(storedEntries.values)

    /** Returns a new book and per-metric PB flags for one split that has just closed. */
    fun record(
        floor: DungeonFloor?,
        split: DungeonSplitTracker.Split?,
        updatedAtMillis: Long,
    ): Update {
        if (!validSplit(floor, split?.id) || split == null || !split.complete ||
            split.elapsedNanos < 0L || split.elapsedTicks < 0L || split.elapsedPingPackets < 0L ||
            split.elapsedServerTicks < 0L
        ) {
            throw IllegalArgumentException("Only a completed, recognized split can be recorded")
        }
        val splitId = split.id!!
        val key = Key(floor!!, splitId)
        val previous = storedEntries[key]
        val real = split.elapsedNanos
        val clientTicks = split.elapsedTicks
        val serverPingPackets = split.elapsedPingPackets
        val realPb = previous == null || real < previous.bestRealTimeNanos
        val clientTicksPb = previous == null || clientTicks < previous.bestClientTicks
        val serverPingPb = previous == null || serverPingPackets < previous.bestServerPingPackets
        val updated = Entry(
            floor,
            splitId,
            if (previous == null) 1 else Math.min(MAX_ATTEMPTS, previous.attempts + 1),
            if (previous == null) real else minOf(real, previous.bestRealTimeNanos),
            if (previous == null) clientTicks else minOf(clientTicks, previous.bestClientTicks),
            if (previous == null) serverPingPackets else minOf(serverPingPackets, previous.bestServerPingPackets),
            real,
            clientTicks,
            serverPingPackets,
            maxOf(0L, updatedAtMillis),
        )
        val next = LinkedHashMap(storedEntries)
        next[key] = updated
        if (next.size > MAX_ENTRIES) {
            val oldest = next.entries.minByOrNull { it.value.updatedAtMillis }?.key
            if (oldest != null && oldest != key) next.remove(oldest)
        }
        return Update(
            DungeonSplitPersonalBestBook(next),
            updated,
            previous == null,
            realPb,
            clientTicksPb,
            serverPingPb,
        )
    }

    fun encode(): String {
        val root = JsonObject()
        root.addProperty("version", VERSION)
        val records = JsonArray()
        val sortedEntries = storedEntries.values.sortedWith(
            compareBy<Entry> { it.floor!!.ordinal }
                .thenBy { segmentIndex(it.floor!!, it.splitId!!) },
        )
        for (entry in sortedEntries) {
            val row = JsonObject()
            row.addProperty("floor", entry.floor!!.name)
            row.addProperty("splitId", entry.splitId)
            row.addProperty("attempts", entry.attempts)
            row.addProperty("bestRealTimeNanos", entry.bestRealTimeNanos)
            row.addProperty("bestClientTicks", entry.bestClientTicks)
            row.addProperty("bestServerPingPackets", entry.bestServerPingPackets)
            row.addProperty("lastRealTimeNanos", entry.lastRealTimeNanos)
            row.addProperty("lastClientTicks", entry.lastClientTicks)
            row.addProperty("lastServerPingPackets", entry.lastServerPingPackets)
            row.addProperty("updatedAtMillis", entry.updatedAtMillis)
            records.add(row)
        }
        root.add("entries", records)
        val json = GSON.toJson(root)
        if (json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
            throw IllegalStateException("Dungeon split data exceeds its size limit")
        }
        return json
    }

    /** Rejects unsupported, malformed, duplicate, or oversized files without changing stored data. */
    companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 256
        const val MAX_JSON_BYTES = 64 * 1024
        private const val MAX_ATTEMPTS = 1_000_000_000
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().create()

        @JvmStatic
        fun empty(): DungeonSplitPersonalBestBook = DungeonSplitPersonalBestBook(java.util.Map.of())

        @JvmStatic
        fun decode(json: String?): DecodeResult {
            if (json == null || json.toByteArray(StandardCharsets.UTF_8).size > MAX_JSON_BYTES) {
                return DecodeResult(false, null, "file is missing or exceeds the size limit")
            }
            try {
                val parsed = BoundedDungeonJsonParser.parse(json, 6, 4_096)
                if (!parsed.isJsonObject) return DecodeResult(false, null, "root is not an object")
                val root = parsed.asJsonObject
                if (integer(root, "version") != VERSION.toLong()) {
                    return DecodeResult(false, null, "unsupported version")
                }
                val entriesElement = root.get("entries")
                if (entriesElement == null || !entriesElement.isJsonArray) {
                    return DecodeResult(false, null, "entries are missing")
                }
                val records = entriesElement.asJsonArray
                if (records.size() > MAX_ENTRIES) return DecodeResult(false, null, "entry count exceeds the limit")
                val decoded = LinkedHashMap<Key, Entry>()
                for (element in records) {
                    if (!element.isJsonObject) return DecodeResult(false, null, "entry is not an object")
                    val row = element.asJsonObject
                    val floorName = string(row, "floor", 16)
                    val floor = DungeonFloor.parse(floorName)
                    val splitId = string(row, "splitId", 32)
                    if (!validSplit(floor, splitId)) {
                        return DecodeResult(false, null, "entry has an unknown floor or split")
                    }
                    val attemptsValue = integer(row, "attempts")
                    if (attemptsValue < 1L || attemptsValue > MAX_ATTEMPTS.toLong()) {
                        return DecodeResult(false, null, "attempt count is outside the allowed range")
                    }
                    val bestReal = nonNegative(row, "bestRealTimeNanos")
                    val bestClientTicks = nonNegative(row, "bestClientTicks")
                    val bestServerPing = nonNegative(row, "bestServerPingPackets")
                    val lastReal = nonNegative(row, "lastRealTimeNanos")
                    val lastClientTicks = nonNegative(row, "lastClientTicks")
                    val lastServerPing = nonNegative(row, "lastServerPingPackets")
                    val updatedAt = nonNegative(row, "updatedAtMillis")
                    if (bestReal > lastReal || bestClientTicks > lastClientTicks || bestServerPing > lastServerPing) {
                        return DecodeResult(false, null, "best measurement is greater than the latest measurement")
                    }
                    val entry = Entry(
                        floor,
                        splitId,
                        attemptsValue.toInt(),
                        bestReal,
                        bestClientTicks,
                        bestServerPing,
                        lastReal,
                        lastClientTicks,
                        lastServerPing,
                        updatedAt,
                    )
                    if (decoded.putIfAbsent(Key(floor!!, splitId), entry) != null) {
                        return DecodeResult(false, null, "duplicate floor/split entry")
                    }
                }
                return DecodeResult(true, DungeonSplitPersonalBestBook(decoded), null)
            } catch (error: IOException) {
                return DecodeResult(false, null, error.message ?: "invalid JSON data")
            } catch (error: IllegalArgumentException) {
                return DecodeResult(false, null, error.message ?: "invalid JSON data")
            } catch (error: ArithmeticException) {
                return DecodeResult(false, null, error.message ?: "invalid JSON data")
            }
        }

        private fun validSplit(floor: DungeonFloor?, splitId: String?): Boolean {
            if (floor == null || splitId == null || splitId.isBlank() || splitId.length > 32) return false
            return DungeonGuideSegments.contains(floor, splitId) || when (splitId) {
                "WATCHER", "BLOOD", "CAMP", "PORTAL" -> true
                else -> false
            }
        }

        private fun segmentIndex(floor: DungeonFloor, splitId: String): Int {
            if (splitId == "BLOOD") return -30
            if (splitId == "CAMP") return -20
            if (splitId == "PORTAL") return -10
            val source = DungeonGuideSegments.builtIn(floor)
            val segments = ArrayList<DungeonGuideSegments.Segment>(source.size + 1)
            for (segment in source) {
                segments.add(segment)
                if (segment.id == "BLOOD_OPEN") {
                    segments.add(DungeonGuideSegments.Segment("WATCHER", "Watcher"))
                }
            }
            for (index in segments.indices) {
                val segment = segments[index]
                if (segment.id == splitId) return index * 10
                if (segment.id == "TERMINALS" && floor.number() == 7 && splitId.matches(Regex("S[1-4]"))) {
                    return index * 10 + Character.digit(splitId[1], 10)
                }
            }
            return Int.MAX_VALUE
        }

        @Throws(IOException::class)
        private fun string(row: JsonObject, name: String, maxLength: Int): String {
            val value = row.get(name)
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                throw IOException("missing or invalid $name")
            }
            val text = value.asString
            if (text.isBlank() || text.length > maxLength) throw IOException("$name is outside the allowed range")
            return text
        }

        @Throws(IOException::class)
        private fun nonNegative(row: JsonObject, name: String): Long {
            val value = integer(row, name)
            if (value < 0L) throw IOException("$name cannot be negative")
            return value
        }

        @Throws(IOException::class)
        private fun integer(row: JsonObject, name: String): Long {
            val value: JsonElement? = row.get(name)
            if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
                throw IOException("missing or invalid $name")
            }
            return try {
                BigDecimal(value.asString).longValueExact()
            } catch (invalid: NumberFormatException) {
                throw IOException("$name is not an integer", invalid)
            } catch (invalid: ArithmeticException) {
                throw IOException("$name is not an integer", invalid)
            }
        }
    }

    private data class Key(val floor: DungeonFloor, val splitId: String)

    @JvmRecord
    data class Entry(
        val floor: DungeonFloor?,
        val splitId: String?,
        val attempts: Int,
        val bestRealTimeNanos: Long,
        val bestClientTicks: Long,
        val bestServerPingPackets: Long,
        val lastRealTimeNanos: Long,
        val lastClientTicks: Long,
        val lastServerPingPackets: Long,
        val updatedAtMillis: Long,
    ) {
        override fun toString() =
            "Entry[floor=$floor, splitId=$splitId, attempts=$attempts, bestRealTimeNanos=$bestRealTimeNanos, " +
                "bestClientTicks=$bestClientTicks, bestServerPingPackets=$bestServerPingPackets, " +
                "lastRealTimeNanos=$lastRealTimeNanos, lastClientTicks=$lastClientTicks, " +
                "lastServerPingPackets=$lastServerPingPackets, updatedAtMillis=$updatedAtMillis]"
    }

    @JvmRecord
    data class Update(
        val book: DungeonSplitPersonalBestBook?,
        val entry: Entry?,
        val firstForSplit: Boolean,
        val realTimePersonalBest: Boolean,
        val clientTicksPersonalBest: Boolean,
        val serverPingPacketsPersonalBest: Boolean,
    ) {
        fun isPersonalBest(mode: DungeonSplitTimeMode?): Boolean {
            if (mode == null) return false
            return when (mode) {
                DungeonSplitTimeMode.REAL_TIME -> realTimePersonalBest
                DungeonSplitTimeMode.CLIENT_TICKS -> clientTicksPersonalBest
                DungeonSplitTimeMode.SERVER_PING_PACKETS -> serverPingPacketsPersonalBest
            }
        }

        override fun toString() =
            "Update[book=$book, entry=$entry, firstForSplit=$firstForSplit, " +
                "realTimePersonalBest=$realTimePersonalBest, clientTicksPersonalBest=$clientTicksPersonalBest, " +
                "serverPingPacketsPersonalBest=$serverPingPacketsPersonalBest]"
    }

    @JvmRecord
    data class DecodeResult(
        val valid: Boolean,
        val book: DungeonSplitPersonalBestBook?,
        val error: String?,
    ) {
        override fun toString() = "DecodeResult[valid=$valid, book=$book, error=$error]"
    }
}
