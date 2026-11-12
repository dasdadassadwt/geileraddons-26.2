package geiler.addons.client.dungeon.odin

import geiler.addons.client.dungeon.DungeonFloor
import kotlin.jvm.JvmRecord

/** Bounded bridge for the one-second run cue if it arrives just before dungeon context is confirmed. */
class DungeonSplitEntryBuffer {
    private var pending: Pending? = null

    fun capture(
        floor: DungeonFloor?,
        level: Any?,
        connection: Any?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
    ): Boolean = capture(floor, level, connection, nowNanos, clientTick, serverPingPackets, 0L)

    fun capture(
        floor: DungeonFloor?,
        level: Any?,
        connection: Any?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (floor == null || level == null || connection == null) return false
        // Odin starts a fresh run for each countdown, so only the newest buffered cue is authoritative.
        pending = Pending(floor, level, connection, nowNanos, clientTick, serverPingPackets, serverTickCount)
        return true
    }

    /** Clears a cue that crossed worlds/servers, outlived its short window, or changed confirmed floor. */
    fun discardIfStale(
        inDungeon: Boolean,
        knownFloor: DungeonFloor?,
        level: Any?,
        connection: Any?,
        nowNanos: Long,
    ) {
        val current = pending ?: return
        val elapsedNanos = nowNanos - current.capturedAtNanos
        val stale = level == null || connection == null || level !== current.level ||
            connection !== current.connection || elapsedNanos < 0L || elapsedNanos > MAX_PENDING_NANOS ||
            inDungeon && knownFloor != null && knownFloor !== current.floor
        if (stale) clear()
    }

    /** Consumes the buffered start only after the same floor/world/server is confirmed as a dungeon. */
    fun consumeIfReady(
        inDungeon: Boolean,
        floor: DungeonFloor?,
        level: Any?,
        connection: Any?,
        nowNanos: Long,
    ): Pending? {
        discardIfStale(inDungeon, floor, level, connection, nowNanos)
        val current = pending ?: return null
        if (!inDungeon || floor == null) return null
        if (floor !== current.floor) {
            clear()
            return null
        }
        pending = null
        return current
    }

    fun clear() {
        pending = null
    }

    fun isPending(): Boolean = pending != null

    companion object {
        const val MAX_PENDING_NANOS = 15_000_000_000L
    }

    @JvmRecord
    data class Pending(
        val floor: DungeonFloor?,
        val level: Any?,
        val connection: Any?,
        val capturedAtNanos: Long,
        val clientTick: Long,
        val serverPingPackets: Long,
        val serverTickCount: Long,
    ) {
        constructor(
            floor: DungeonFloor?,
            level: Any?,
            connection: Any?,
            capturedAtNanos: Long,
            clientTick: Long,
            serverPingPackets: Long,
        ) : this(floor, level, connection, capturedAtNanos, clientTick, serverPingPackets, 0L)

        override fun toString() =
            "Pending[floor=$floor, level=$level, connection=$connection, capturedAtNanos=$capturedAtNanos, " +
                "clientTick=$clientTick, serverPingPackets=$serverPingPackets, serverTickCount=$serverTickCount]"
    }
}
