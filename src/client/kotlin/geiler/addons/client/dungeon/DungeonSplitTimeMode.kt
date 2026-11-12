package geiler.addons.client.dungeon

import geiler.addons.client.dungeon.odin.DungeonSplitTracker
import java.util.Locale
import kotlin.jvm.JvmStatic

/** A split display metric whose label identifies the source of the measurement. */
enum class DungeonSplitTimeMode {
    REAL_TIME,
    CLIENT_TICKS,
    SERVER_PING_PACKETS;

    fun value(split: DungeonSplitTracker.Split?): Long {
        if (split == null) return 0L
        return when (this) {
            REAL_TIME -> split.elapsedNanos
            CLIENT_TICKS -> split.elapsedTicks
            SERVER_PING_PACKETS -> split.elapsedPingPackets
        }
    }

    fun value(entry: DungeonSplitPersonalBestBook.Entry?, best: Boolean): Long {
        if (entry == null) return 0L
        return when (this) {
            REAL_TIME -> if (best) entry.bestRealTimeNanos else entry.lastRealTimeNanos
            CLIENT_TICKS -> if (best) entry.bestClientTicks else entry.lastClientTicks
            SERVER_PING_PACKETS -> if (best) entry.bestServerPingPackets else entry.lastServerPingPackets
        }
    }

    fun improves(candidate: Long, previousBest: Long): Boolean =
        candidate >= 0L && (previousBest < 0L || candidate < previousBest)

    fun format(value: Long): String {
        val safe = maxOf(0L, value)
        return when (this) {
            REAL_TIME -> formatRealTime(safe)
            CLIENT_TICKS -> safe.toString() + if (safe == 1L) " client tick" else " client ticks"
            SERVER_PING_PACKETS -> safe.toString() +
                (if (safe == 1L) " ping event" else " ping events") + " (Odin proxy; not server ticks)"
        }
    }

    companion object {
        /** Describes a real-time split's pace relative to its saved best without mixing in packet counts. */
        @JvmStatic
        fun formatPbPace(elapsedNanos: Long, bestNanos: Long): String {
            val delta = elapsedNanos - bestNanos
            if (delta == 0L) return "time PB"
            val magnitude = if (delta == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(delta)
            return "PB pace " + (if (delta < 0L) "ahead " else "behind ") + REAL_TIME.format(magnitude)
        }

        private fun formatRealTime(nanos: Long): String {
            val tenths = nanos / 100_000_000L
            val seconds = tenths / 10L
            val tenth = tenths % 10L
            return if (seconds < 60L) {
                String.format(Locale.ROOT, "%d.%ds", seconds, tenth)
            } else {
                String.format(Locale.ROOT, "%d:%02d.%ds", seconds / 60L, seconds % 60L, tenth)
            }
        }
    }
}
