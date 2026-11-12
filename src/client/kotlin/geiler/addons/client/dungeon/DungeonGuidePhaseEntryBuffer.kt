package geiler.addons.client.dungeon

import kotlin.jvm.JvmRecord

/** Bounded bridge for a run countdown received shortly before its dungeon context is confirmed. */
class DungeonGuidePhaseEntryBuffer {
    private var pending: Pending? = null

    /** Keeps the first eligible countdown so repeated chat cannot extend its lifetime. */
    fun capture(floor: DungeonFloor?, level: Any?, connection: Any?, nowNanos: Long): Boolean {
        discardIfStale(false, null, level, connection, nowNanos)
        if (floor == null || level == null || connection == null || pending != null) return false
        pending = Pending(floor, level, connection, nowNanos)
        return true
    }

    /** Drops cues that crossed a world/server, expired, or now disagree with the confirmed floor. */
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
            (inDungeon && knownFloor != null && knownFloor !== current.floor)
        if (stale) clear()
    }

    /** Consumes at most once after the same floor, world, and connection are confirmed. */
    fun consumeIfReady(
        inDungeon: Boolean,
        floor: DungeonFloor?,
        level: Any?,
        connection: Any?,
        nowNanos: Long,
    ): Pending? {
        discardIfStale(inDungeon, floor, level, connection, nowNanos)
        val current = pending
        if (!inDungeon || current == null || floor == null) return null
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

    @JvmRecord
    data class Pending(
        val floor: DungeonFloor?,
        val level: Any?,
        val connection: Any?,
        val capturedAtNanos: Long,
    )

    companion object {
        const val MAX_PENDING_NANOS: Long = 15_000_000_000L
    }
}
