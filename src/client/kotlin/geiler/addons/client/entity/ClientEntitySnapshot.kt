package geiler.addons.client.entity

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.entity.EntityTypeTest
import net.minecraft.world.phys.AABB
import java.util.LinkedHashMap
import java.util.Objects
import java.util.function.Supplier

/** Bounded client-thread entity queries shared by the nearby-entity features. */
class ClientEntitySnapshot private constructor() {
    companion object {
        private const val MAX_CACHED_QUERIES = 4
        private val CACHE = Cache<Entity>(MAX_CACHED_QUERIES)

        /** Returns live client-side entities, reusing the query for the same level tick and scan area. */
        @JvmStatic
        fun nearby(level: ClientLevel?, player: LocalPlayer?, range: Double): List<Entity> {
            if (level == null || player == null || !range.isFinite() || range <= 0.0) return java.util.List.of()

            val x = player.x
            val y = player.y
            val z = player.z
            val key = Key(level, player, level.gameTime, x, y, z, range)
            return CACHE.get(key) {
                val area = AABB.ofSize(player.position(), range * 2, range * 2, range * 2)
                level.getEntities(EntityTypeTest.forClass(Entity::class.java), area) { entity -> entity.isAlive }
            }
        }
    }

    /** Bounded FIFO query cache for deterministic offline checks and shared production use. */
    class Cache<T> {
        private val capacity: Int
        private val entries = LinkedHashMap<Key, List<T>>()
        private var level: Any? = null
        private var gameTime = 0L
        private var primed = false

        constructor() : this(4)

        constructor(capacity: Int) {
            this.capacity = maxOf(1, capacity)
        }

        fun get(requested: Key, loader: Supplier<List<T>>): List<T> {
            if (!primed || !Objects.equals(level, requested.level) || gameTime != requested.gameTime) {
                // Positions, liveness and even the level itself can all change between ticks.
                entries.clear()
                level = requested.level
                gameTime = requested.gameTime
                primed = true
            }

            val cached = entries[requested]
            if (cached != null) return cached

            // Keep List.copyOf semantics: cached query snapshots are immutable and reject null entries.
            val loaded: List<T> = java.util.List.copyOf(loader.get())
            if (entries.size >= capacity) {
                val oldest = entries.keys.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
            entries[requested] = loaded
            return loaded
        }
    }

    /** Record metadata and component order remain compatible with the Java callers and checks. */
    @JvmRecord
    data class Key(
        val level: Any?,
        val player: Any?,
        val gameTime: Long,
        val x: Double,
        val y: Double,
        val z: Double,
        val range: Double,
    ) {
        override fun toString(): String =
            "Key[level=$level, player=$player, gameTime=$gameTime, x=$x, y=$y, z=$z, range=$range]"
    }
}
