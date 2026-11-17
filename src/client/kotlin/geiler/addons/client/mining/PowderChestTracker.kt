package geiler.addons.client.mining

/**
 * Tracks Crystal Hollows powder chests from server block changes, after SkyHanni's Powder Chest Timer (LGPL-2.1): a
 * chest that appears where the player just mined stone is a powder chest, and it vanishes 60 seconds later. Pure
 * state so the spawn rules run offline; the module feeds it block updates, sounds and the clock.
 */
class PowderChestTracker {
    enum class Kind { STONE, AIR, CHEST, OTHER }

    class Chest(val x: Int, val y: Int, val z: Int, val spawnedNanos: Long) {
        fun ageNanos(now: Long): Long = (now - spawnedNanos).coerceAtLeast(0)
        fun remainingNanos(now: Long): Long = (LIFETIME_NANOS - ageNanos(now)).coerceAtLeast(0)
    }

    /** What a block change did to the tracker. */
    enum class Outcome { IGNORED, MINED, ADDED, REJECTED, REMOVED }

    private val mined = HashMap<Long, Long>()
    private val chests = LinkedHashMap<Long, Chest>()
    private var lastSoundNanos = Long.MIN_VALUE / 2

    /** The level-up sound a new powder chest plays; its timing separates real chests from other chest changes. */
    fun onLevelUpSound(nowNanos: Long) {
        lastSoundNanos = nowNanos
    }

    fun onBlockChange(
        x: Int,
        y: Int,
        z: Int,
        old: Kind?,
        new: Kind,
        nowNanos: Long,
        distanceToPlayer: Double,
        playersNearby: Boolean,
    ): Outcome {
        if (distanceToPlayer > MAX_CHEST_DISTANCE) return Outcome.IGNORED
        val key = pack(x, y, z)
        pruneMined(nowNanos)
        return when {
            old == Kind.STONE && new == Kind.AIR -> {
                mined[key] = nowNanos
                Outcome.MINED
            }
            old != Kind.CHEST && new == Kind.CHEST -> {
                val wasMined = mined.remove(key) != null
                val suspicious = playersNearby || (!wasMined && old == Kind.AIR)
                if (suspicious && nowNanos - lastSoundNanos > SOUND_WINDOW_NANOS) return Outcome.REJECTED
                chests[key] = Chest(x, y, z, nowNanos)
                Outcome.ADDED
            }
            old == Kind.CHEST && new != Kind.CHEST -> if (chests.remove(key) != null) Outcome.REMOVED else Outcome.IGNORED
            else -> Outcome.IGNORED
        }
    }

    /** Drops chests older than their lifetime; returns how many expired. */
    fun expire(nowNanos: Long): Int {
        val before = chests.size
        chests.values.removeIf { it.remainingNanos(nowNanos) <= 0 }
        return before - chests.size
    }

    fun remove(x: Int, y: Int, z: Int): Boolean = chests.remove(pack(x, y, z)) != null

    /** Chests in spawn order, oldest first. */
    fun chests(): List<Chest> = chests.values.toList()

    fun size(): Int = chests.size

    fun clear() {
        chests.clear()
        mined.clear()
    }

    private fun pruneMined(now: Long) {
        if (mined.size < 64) return
        mined.values.removeIf { now - it > MINED_WINDOW_NANOS }
    }

    companion object {
        const val LIFETIME_NANOS = 60_000_000_000L
        const val MAX_CHEST_DISTANCE = 15.0
        const val NEAR_PLAYER_DISTANCE = 25.0
        private const val SOUND_WINDOW_NANOS = 200_000_000L
        private const val MINED_WINDOW_NANOS = 5_000_000_000L

        private fun pack(x: Int, y: Int, z: Int): Long =
            (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)

        /** Colour of a chest by time left: green when fresh, red when about to vanish (ARGB, opaque). */
        @JvmStatic
        fun timeColor(remainingNanos: Long): Int {
            val ratio = (remainingNanos.toDouble() / LIFETIME_NANOS).coerceIn(0.0, 1.0)
            val red = (255 * (1 - ratio)).toInt()
            val green = (255 * ratio).toInt()
            return (0xFF shl 24) or (red shl 16) or (green shl 8)
        }

        @JvmStatic
        fun seconds(nanos: Long): String = (nanos / 1_000_000_000L).toString() + "s"
    }
}
