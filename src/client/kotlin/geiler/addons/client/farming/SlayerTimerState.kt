package geiler.addons.client.farming

/** Tracks the current boss spawn time and the most recent kill duration. */
class SlayerTimerState {
    private var bossSpawnNanos = 0L
    private var lastBossSpawnNanos = 0L
    private var lastKillNanos = 0L
    private var lastKillDurationNanos = 0L
    private var bossActive = false
    private var hasLastBossSpawn = false
    private var hasLastKill = false

    /** @return true only on the transition into a new boss spawn. */
    fun onBossSpawn(nowNanos: Long): Boolean {
        if (bossActive) return false
        bossActive = true
        bossSpawnNanos = nowNanos
        lastBossSpawnNanos = bossSpawnNanos
        hasLastBossSpawn = true
        return true
    }

    /** @return the kill duration when a boss was active, or -1 when no spawn was timed. */
    fun onBossKill(nowNanos: Long): Long {
        if (!bossActive) return -1L
        val duration = Math.max(0L, nowNanos - bossSpawnNanos)
        lastKillDurationNanos = duration
        lastKillNanos = nowNanos
        hasLastKill = true
        bossActive = false
        bossSpawnNanos = 0L
        return duration
    }

    fun onQuestFailed() {
        bossActive = false
        bossSpawnNanos = 0L
    }

    fun bossActive(): Boolean = bossActive
    fun bossSpawnNanos(): Long = bossSpawnNanos
    fun lastBossSpawnNanos(): Long = lastBossSpawnNanos
    fun lastKillNanos(): Long = lastKillNanos
    fun lastKillDurationNanos(): Long = lastKillDurationNanos
    fun hasLastKill(): Boolean = hasLastKill

    fun secondsSinceSpawn(nowNanos: Long): Double =
        if (bossActive) Math.max(0L, nowNanos - bossSpawnNanos) / 1_000_000_000.0 else -1.0

    fun reset() {
        bossSpawnNanos = 0L
        lastBossSpawnNanos = 0L
        lastKillNanos = 0L
        lastKillDurationNanos = 0L
        bossActive = false
        hasLastBossSpawn = false
        hasLastKill = false
    }
}
