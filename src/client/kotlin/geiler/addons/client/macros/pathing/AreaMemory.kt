package geiler.addons.client.macros.pathing

import geiler.addons.GeilerAddons
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.phys.Vec3
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What the macro has learnt about an area from walking it: the places where it got stuck.
 *
 * The planner only knows what the blocks say, and some places the blocks say are fine are not: a corner the hitbox
 * catches on, a step that has to be met square, a spot where a mob always stands in the way. Every time the walker gives
 * up on a route because it stopped making progress, the place it stood and the next stretch of the route ahead are noted
 * here, and every note is extra cost for a route through that place - so the next route goes round it where there is
 * another way. A place that is walked through without trouble afterwards is forgiven a share of it each time, so a spot
 * that was only once unlucky does not stay avoided, and one that keeps causing trouble keeps its cost.
 *
 * It is kept on disk per area, beside the [WorldStore], and the area gets better to walk with every session. Only used
 * while the area killer runs. All of it is the client thread's except reading and writing the file.
 */
object AreaMemory {
    private const val MAGIC = 0x414B414D
    private const val VERSION = 1

    /** Cost added for one time the walker got stuck at a place, in the planner's ticks: about three blocks of detour. */
    private const val TROUBLE = 10.0f

    /** The most a place can cost on top: a way through a bad place still beats no way at all. */
    private const val MAX_PENALTY = 60.0f

    /** The share of its cost a place keeps each time it is walked through without trouble, and below which it is forgotten. */
    private const val FORGIVE = 0.8f
    private const val FORGOTTEN = 0.5f

    /** How far ahead of the player, along the route, the places that are blamed for being stuck lie, in blocks. */
    private val BLAMED_AHEAD = doubleArrayOf(0.0, 0.8, 1.6)
    private const val SAVE_EVERY_MILLIS = 60_000L

    private val IO = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "geileraddons-macros-area-memory").apply { isDaemon = true }
    }

    private var level: ClientLevel? = null
    private var file: java.nio.file.Path? = null
    private var penalties: Long2FloatOpenHashMap? = null
    private var lastStand = Long.MIN_VALUE
    private var dirty = false
    private var savedMillis = 0L
    private var learntThisSession = 0

    /** A place known to be trouble, and how much it costs on top. */
    class Spot(val pos: BlockPos, val penalty: Float)

    /** Starts using and adding to what is known of the area kept in this file. */
    @JvmStatic
    fun activate(world: ClientLevel, memoryFile: java.nio.file.Path) {
        if (world === level && memoryFile == file) return
        deactivate()
        level = world
        file = memoryFile
        // Read at once, not on the writer thread: the file is small, and the first route of the session is planned in the
        // same tick - read later, it would go straight back into the places that were learnt.
        val read = read(memoryFile)
        penalties = read
        GeilerAddons.LOGGER.info("Area memory: {} places read from {}", read.size, memoryFile.fileName)
        lastStand = Long.MIN_VALUE
        dirty = false
        savedMillis = Util.getMillis()
        learntThisSession = 0
    }

    /** Writes what was learnt to disk and stops using it. */
    @JvmStatic
    fun deactivate() {
        if (level == null) return
        save()
        level = null
        file = null
        penalties = null
    }

    /** Call every tick while active: saves now and then. */
    @JvmStatic
    fun tick(world: ClientLevel) {
        if (world !== level || penalties == null) return
        if (dirty && Util.getMillis() - savedMillis > SAVE_EVERY_MILLIS) save()
    }

    /** The extra cost of standing in this block, or 0. */
    @JvmStatic
    fun penalty(world: ClientLevel, standKey: Long): Double {
        val known = penalties
        if (world !== level || known == null || known.isEmpty()) return 0.0
        return known.get(standKey).toDouble()
    }

    /** How many places are known to be trouble, and how many were added this session, for the status line. */
    @JvmStatic
    fun describe(): String {
        val known = penalties ?: return "not in use"
        return "${known.size} trouble spots known, $learntThisSession learnt this session"
    }

    /** The places known to be trouble within this distance, for the overlay: block positions and their cost. */
    @JvmStatic
    fun near(around: Vec3, radius: Double): List<Spot> {
        val spots = ArrayList<Spot>()
        val known = penalties ?: return spots
        val limit = radius * radius
        for (entry in known.long2FloatEntrySet()) {
            val pos = BlockPos.of(entry.longKey)
            if (pos.distToCenterSqr(around) <= limit) spots.add(Spot(pos, entry.floatValue))
        }
        return spots
    }

    /** The walker gave up on a route here: the player's place and the stretch just ahead of it on the route are noted. */
    @JvmStatic
    fun stuck(world: ClientLevel, feet: Vec3, route: Path?, progress: Double) {
        val known = penalties
        if (world !== level || known == null) return
        var previous = Long.MIN_VALUE
        for (ahead in BLAMED_AHEAD) {
            val at = if (ahead == 0.0 || route == null) feet else route.pointAt(progress + ahead)
            val key = keyOf(at)
            if (key == previous) continue
            previous = key
            val now = Math.min(MAX_PENALTY, known.get(key) + TROUBLE)
            known.put(key, now)
        }
        learntThisSession++
        dirty = true
    }

    /** The player is walking a route without trouble: the place they have just come to is forgiven a share of its cost. */
    @JvmStatic
    fun walking(world: ClientLevel, feet: Vec3) {
        val known = penalties
        if (world !== level || known == null) return
        val key = keyOf(feet)
        if (key == lastStand) return
        lastStand = key
        val had = known.get(key)
        if (had > 0.0f) {
            val now = had * FORGIVE
            if (now < FORGOTTEN) known.remove(key) else known.put(key, now)
            dirty = true
        }
    }

    /** The block the feet are in, which is what a stand is known by. */
    private fun keyOf(feet: Vec3): Long = BlockPos.asLong(Mth.floor(feet.x), Mth.floor(feet.y + 1.0E-3), Mth.floor(feet.z))

    private fun save() {
        val known = penalties
        val target = file
        if (!dirty || known == null || target == null) return
        dirty = false
        savedMillis = Util.getMillis()
        val copy = Long2FloatOpenHashMap(known)
        IO.execute { write(target, copy) }
    }

    @JvmStatic
    fun write(target: java.nio.file.Path, data: Long2FloatOpenHashMap) {
        try {
            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary)))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(data.size)
                for (entry in data.long2FloatEntrySet()) {
                    out.writeLong(entry.longKey)
                    out.writeFloat(entry.floatValue)
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: IOException) {
            GeilerAddons.LOGGER.error("Could not write {}", target, exception)
        }
    }

    @JvmStatic
    fun read(source: java.nio.file.Path): Long2FloatOpenHashMap {
        val data = Long2FloatOpenHashMap()
        if (!Files.exists(source)) return data
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(source)))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return data
                val count = input.readInt()
                for (i in 0 until count) data.put(input.readLong(), Math.min(MAX_PENALTY, input.readFloat()))
            }
        } catch (exception: IOException) {
            GeilerAddons.LOGGER.error("Could not read {}, starting with nothing", source, exception)
            return Long2FloatOpenHashMap()
        } catch (exception: RuntimeException) {
            GeilerAddons.LOGGER.error("Could not read {}, starting with nothing", source, exception)
            return Long2FloatOpenHashMap()
        }
        return data
    }
}
