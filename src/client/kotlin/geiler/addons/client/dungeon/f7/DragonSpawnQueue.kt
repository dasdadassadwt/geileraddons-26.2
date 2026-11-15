package geiler.addons.client.dungeon.f7

import java.util.EnumMap

/**
 * Which M7 dragon spawns next and where its flight starts (Devonian's `M7Events` queue, GPL-3.0; written
 * independently). Pure state fed by the module with particle bursts and dragon entities.
 */
class DragonSpawnQueue(private val listener: Listener) {
    interface Listener {
        fun activeChanged(spawn: Spawn)
        fun dead(dragon: AimDragon)
    }

    /** [spawnTick] is the server tick at which the dragon appears. */
    class Spawn(val type: AimDragon, val high: Boolean, val spawnTick: Int)

    private val cooldown = EnumMap<AimDragon, Int>(AimDragon::class.java)
    val queued = ArrayList<Spawn>()

    /** A flame burst at [tick]; returns the new spawn when it was accepted. */
    fun onParticle(
        x: Double, y: Double, z: Double, count: Int,
        xDist: Float, yDist: Float, zDist: Float, maxSpeed: Float, flame: Boolean, tick: Int,
    ): Spawn? {
        if (!flame || count != 20 || xDist != 2f || yDist != 3f || zDist != 2f || maxSpeed != 0f) return null
        if (x % 1 != 0.0 || z % 1 != 0.0) return null
        val high = when (y.toInt()) {
            19 -> false
            27 -> true
            else -> return null
        }
        val dragon = AimDragon.entries.find { it.particleX == x.toInt() && it.particleZ == z.toInt() } ?: return null
        val until = cooldown[dragon] ?: -1
        if (until != -1 && tick < until) return null
        cooldown[dragon] = tick + 100

        val spawn = Spawn(dragon, high, tick + 100)
        queued.add(spawn)
        if (queued.size > 1) {
            while (true) {
                val head = queued.firstOrNull() ?: break
                if (head.spawnTick > tick) break
                queued.removeAt(0)
                listener.dead(head.type)
            }
        }
        if (queued.size == 1) listener.activeChanged(spawn)
        return spawn
    }

    /** A dragon entity vanished or began dying; the next queued dragon becomes the active one. */
    fun onDragonGone(dragon: AimDragon) {
        val first = queued.firstOrNull()
        val index = queued.indexOfFirst { it.type == dragon }
        if (index < 0) return
        queued.removeAt(index)
        listener.dead(dragon)
        val now = queued.firstOrNull()
        if (now != null && now !== first) listener.activeChanged(now)
    }

    /** The dragon type whose flight start is nearest to a spawned entity at (x, y, z). */
    fun nearestType(x: Double, y: Double, z: Double): AimDragon =
        AimDragon.entries.minBy {
            abs(it.path[0][0] - x) + abs(it.path[0][1] - y) + abs(it.path[0][2] - z)
        }

    fun reset() {
        cooldown.clear()
        queued.clear()
    }

    private fun abs(v: Double) = if (v < 0) -v else v
}
