package geiler.addons.client.dungeon.f7

import java.util.EnumMap
import java.util.UUID

/** The five M7 dragons with their spawn data (Odin's `WitherDragonsEnum`, BSD 3-Clause; written independently). */
enum class WitherDragon(
    val spawnX: Int, val spawnY: Int, val spawnZ: Int,
    val statueX: Int, val statueY: Int, val statueZ: Int,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
    val colorCode: Char,
    val argb: Int,
    val xRange: ClosedFloatingPointRange<Double>,
    val zRange: ClosedFloatingPointRange<Double>,
) {
    Red(27, 14, 59, 32, 22, 59, 14.5, 13.0, 45.5, 39.5, 28.0, 70.5, 'c', 0xFFFF5555.toInt(), 24.0..30.0, 56.0..62.0),
    Orange(85, 14, 56, 80, 23, 56, 72.0, 8.0, 47.0, 102.0, 28.0, 77.0, '6', 0xFFFFAA00.toInt(), 82.0..88.0, 53.0..59.0),
    Green(27, 14, 94, 32, 23, 94, 7.0, 8.0, 80.0, 37.0, 28.0, 110.0, 'a', 0xFF55FF55.toInt(), 23.0..29.0, 91.0..97.0),
    Blue(84, 14, 94, 79, 23, 94, 71.5, 13.0, 82.5, 96.5, 26.0, 107.5, 'b', 0xFF55FFFF.toInt(), 82.0..88.0, 91.0..97.0),
    Purple(56, 14, 125, 56, 22, 120, 45.5, 13.0, 113.5, 68.5, 23.0, 136.5, '5', 0xFFAA00AA.toInt(), 53.0..59.0, 122.0..128.0);

    fun contains(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ
}

enum class WitherDragonState { SPAWNING, ALIVE, DEAD }

/**
 * Dragon spawn, life and death tracking for the Wither Dragons module. Pure state: the module feeds it packets
 * and server ticks and turns the [Listener] callbacks into chat, titles and personal bests.
 */
class WitherDragonTracker(private val listener: Listener) {
    interface Listener {
        fun spawning(dragon: WitherDragon)
        fun spawned(dragon: WitherDragon, timesSpawned: Int)
        fun died(dragon: WitherDragon, ticksAlive: Long, realTime: Boolean)
        fun counts(dragon: WitherDragon)
        fun sprayed(dragon: WitherDragon, ticksAfterSpawn: Long)
    }

    class Entry {
        var timeToSpawn = 0
        var state = WitherDragonState.DEAD
        var timesSpawned = 0
        var entityUuid: UUID? = null
        var sprayed = false
        var spawnedTime = 0L
    }

    val entries = EnumMap<WitherDragon, Entry>(WitherDragon::class.java).also { map ->
        WitherDragon.entries.forEach { map[it] = Entry() }
    }
    val spawnOrder = ArrayList<WitherDragon>()
    /** Spawn bursts ignored because that dragon's countdown was still running (diagnostics). */
    var ignoredBursts = 0
        private set
    private val statuesBroken = java.util.EnumSet.noneOf(WitherDragon::class.java)
    var currentTick = 0L
        private set
    private var lastDeath: WitherDragon? = null

    val priority: WitherDragon? get() = spawnOrder.firstOrNull()

    fun entry(dragon: WitherDragon): Entry = entries.getValue(dragon)

    /** The dragon whose countdown ends soonest, or null when none is counting down. */
    fun nextToSpawn(): WitherDragon? =
        WitherDragon.entries.filter { entry(it).timeToSpawn > 0 }.minByOrNull { entry(it).timeToSpawn }

    /** Returns true when [flame] particle fields describe a dragon spawn burst that was accepted. */
    fun onSpawnParticle(
        x: Double, y: Double, z: Double, count: Int,
        xDist: Float, yDist: Float, zDist: Float, maxSpeed: Float, flame: Boolean,
    ): Boolean {
        if (count != 20 || y != 19.0 || !flame || xDist != 2f || yDist != 3f || zDist != 2f || maxSpeed != 0f ||
            x % 1 != 0.0 || z % 1 != 0.0
        ) return false
        val dragon = WitherDragon.entries.find {
            entry(it).state == WitherDragonState.DEAD && x in it.xRange && z in it.zRange
        } ?: return false
        val e = entry(dragon)
        // A running countdown is never restarted (a statue break can mark the dragon dead while it still counts).
        if (e.timeToSpawn > 0) {
            ignoredBursts++
            return false
        }
        e.state = WitherDragonState.SPAWNING
        e.timeToSpawn = 100
        spawnOrder.add(dragon)
        listener.spawning(dragon)
        return true
    }

    fun onServerTick() {
        for (dragon in WitherDragon.entries) {
            val e = entry(dragon)
            if (e.timeToSpawn > 0) e.timeToSpawn--
            else if (e.state == WitherDragonState.SPAWNING) setAlive(dragon, null)
        }
        currentTick++
    }

    fun onEntityAdded(uuid: UUID, x: Double, y: Double, z: Double) {
        WitherDragon.entries.find { it.contains(x, y, z) }?.let { setAlive(it, uuid) }
    }

    fun onHealth(uuid: UUID, health: Float) {
        if (health > 0) return
        WitherDragon.entries.firstOrNull { entry(it).entityUuid == uuid }?.let {
            if (entry(it).state != WitherDragonState.DEAD) setDead(it, true)
        }
    }

    /** A statue block turning to air means that dragon is gone. */
    fun onBlockAir(x: Int, y: Int, z: Int) {
        WitherDragon.entries.find { it.statueX == x && it.statueY == y && it.statueZ == z }?.let {
            statuesBroken.add(it)
            setDead(it, false)
        }
    }

    /** False once the dragon's statue block has been seen turning to air. */
    fun statueIntact(dragon: WitherDragon): Boolean = dragon !in statuesBroken

    fun onWitherKingLine() {
        val dragon = lastDeath ?: WitherDragon.entries.find { entry(it).state != WitherDragonState.DEAD } ?: return
        listener.counts(dragon)
        if (entry(dragon).state != WitherDragonState.DEAD) setDead(dragon, false)
        lastDeath = null
    }

    /** [distanceTo] gives the distance from the sprayed armor stand to a tracked dragon entity, or null if unknown. */
    fun onIceSpray(distanceTo: (UUID) -> Double?) {
        for (dragon in WitherDragon.entries) {
            val e = entry(dragon)
            val uuid = e.entityUuid ?: continue
            val distance = distanceTo(uuid) ?: continue
            if (e.sprayed || e.state != WitherDragonState.ALIVE || distance > 8) continue
            listener.sprayed(dragon, currentTick - e.spawnedTime)
            e.sprayed = true
        }
    }

    fun reset() {
        for (e in entries.values) {
            e.timeToSpawn = 0
            e.timesSpawned = 0
            e.state = WitherDragonState.DEAD
            e.entityUuid = null
            e.sprayed = false
            e.spawnedTime = 0
        }
        spawnOrder.clear()
        ignoredBursts = 0
        statuesBroken.clear()
        lastDeath = null
    }

    private fun setAlive(dragon: WitherDragon, uuid: UUID?) {
        val e = entry(dragon)
        if (uuid != null) e.entityUuid = uuid
        if (e.state != WitherDragonState.SPAWNING) return
        e.state = WitherDragonState.ALIVE
        e.timesSpawned++
        e.spawnedTime = currentTick
        e.sprayed = false
        listener.spawned(dragon, e.timesSpawned)
    }

    private fun setDead(dragon: WitherDragon, realTime: Boolean) {
        val e = entry(dragon)
        e.state = WitherDragonState.DEAD
        e.entityUuid = null
        lastDeath = dragon
        spawnOrder.remove(dragon)
        listener.died(dragon, currentTick - e.spawnedTime, realTime)
    }
}
