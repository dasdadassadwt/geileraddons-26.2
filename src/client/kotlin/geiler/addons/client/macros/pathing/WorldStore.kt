package geiler.addons.client.macros.pathing

import geiler.addons.GeilerAddons
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.Util
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The world around the area as it was last seen, kept on disk from one session to the next.
 *
 * The client only has the chunks inside the view distance, and the planner treats everything else as unknown - never as
 * open ground. The ground of an area does not change, though: what was seen once is what will be there next time. So
 * every chunk near the area that the client has is written down here, block by block, and where a chunk is not loaded the
 * planner reads it from here instead. Every session adds what it saw, and a route can be planned through ground beyond
 * the view distance as soon as it has been seen once.
 *
 * A loaded chunk always wins: it is what the server says now, and it replaces what was kept. The store is used only while
 * the area killer runs, and is kept per server, dimension and gate - and a chunk that comes in looking nothing like what
 * was kept (another instance of an island, the same coordinates in another world) throws away everything kept for it,
 * rather than plan through a world that is not this one.
 *
 * States are kept by the game's own numbers for them, which depend on the game version and the mods; a file written under
 * others is ignored. All of it is the client thread's except reading and writing the file.
 */
object WorldStore {
    private const val MAGIC = 0x414B5753
    private const val VERSION = 1

    /** How far around the middle of the area chunks are kept, in blocks, and how far up and down. */
    private const val RECORD_RADIUS = 160
    private const val RECORD_HALF_HEIGHT = 48

    /** What a tick may spend writing chunks down, in milliseconds. */
    private const val SNAPSHOT_MILLIS = 2.0
    private const val SAVE_EVERY_MILLIS = 60_000L

    /**
     * A chunk that differs from what was kept in more than this share of its blocks - counting only blocks that are not
     * air in one or the other, and only when there are enough of them to tell - is not the chunk that was kept.
     */
    private const val FOREIGN_SHARE = 0.5
    private const val FOREIGN_MIN_BLOCKS = 512
    private const val SECTION_SIZE = 4096

    /** One 16 x 16 x 16 piece of the world as last seen: its states by palette, one byte each, two where there are many. */
    class Section(val palette: IntArray, val small: ByteArray?, val wide: CharArray?) {
        fun id(index: Int): Int {
            if (small != null) return palette[small[index].toInt() and 0xFF]
            return if (wide != null) palette[wide[index].code] else palette[0]
        }
    }

    private val IO = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "geileraddons-macros-world-store").apply { isDaemon = true }
    }

    private var level: ClientLevel? = null
    private var file: java.nio.file.Path? = null
    private var sections: Long2ObjectOpenHashMap<Section>? = null
    private var loading: CompletableFuture<Long2ObjectOpenHashMap<Section>>? = null
    private val QUEUE = LongLinkedOpenHashSet()
    private var centerX = 0
    private var centerZ = 0
    private var minSectionY = 0
    private var maxSectionY = 0
    private var dirty = false
    private var savedMillis = 0L

    /**
     * Starts keeping and using the world around this point, in the file of this name - read from disk if there is one.
     * Nothing happens if it is already the one in use.
     */
    @JvmStatic
    fun activate(world: ClientLevel, storeFile: java.nio.file.Path, center: BlockPos) {
        if (world === level && storeFile == file) return
        deactivate()
        level = world
        file = storeFile
        sections = Long2ObjectOpenHashMap()
        centerX = center.x
        centerZ = center.z
        minSectionY = Math.max(world.minSectionY, (center.y - RECORD_HALF_HEIGHT) shr 4)
        maxSectionY = Math.min(world.maxSectionY, (center.y + RECORD_HALF_HEIGHT) shr 4)
        dirty = false
        savedMillis = Util.getMillis()
        loading = CompletableFuture.supplyAsync({ read(storeFile) }, IO)
        val chunks = RECORD_RADIUS shr 4
        for (cx in (centerX shr 4) - chunks..(centerX shr 4) + chunks) {
            for (cz in (centerZ shr 4) - chunks..(centerZ shr 4) + chunks) queue(cx, cz)
        }
        TerrainCache.clear()
    }

    /** Writes what was seen to disk and stops using it. */
    @JvmStatic
    fun deactivate() {
        if (level == null) return
        save()
        level = null
        file = null
        sections = null
        loading = null
        QUEUE.clear()
        TerrainCache.clear()
    }

    /** How many pieces of 16 x 16 x 16 blocks are kept, for the records. */
    @JvmStatic
    fun size(): Int = sections?.size ?: 0

    /** Call every tick while active: takes in the file once it is read, writes chunks down and saves now and then. */
    @JvmStatic
    fun tick(world: ClientLevel) {
        val kept = sections
        if (world !== level || kept == null) return
        val pending = loading
        if (pending != null && pending.isDone) {
            val read = pending.getNow(null)
            loading = null
            if (read != null) {
                // What was seen since the start is newer than the file.
                for (entry in read.long2ObjectEntrySet()) kept.putIfAbsent(entry.longKey, entry.value)
                TerrainCache.clear()
                GeilerAddons.LOGGER.info("World store: {} sections read from {}", read.size, file?.fileName)
            }
        }
        if (loading == null) {
            val deadline = System.nanoTime() + (SNAPSHOT_MILLIS * 1.0E6).toLong()
            while (!QUEUE.isEmpty() && System.nanoTime() < deadline) {
                val key = QUEUE.removeFirstLong()
                snapshot(ChunkPos.getX(key), ChunkPos.getZ(key))
            }
        }
        if (dirty && Util.getMillis() - savedMillis > SAVE_EVERY_MILLIS) save()
    }

    /** What was last seen at this block, or null when nothing is known of it or the store is not in use for this world. */
    @JvmStatic
    fun stateAt(world: ClientLevel, x: Int, y: Int, z: Int): BlockState? {
        val kept = sections
        if (world !== level || kept == null) return null
        val section = kept.get(BlockPos.asLong(x shr 4, y shr 4, z shr 4))
        return if (section == null) null else Block.stateById(section.id(TerrainCache.index(x, y, z)))
    }

    @JvmStatic
    fun blockChanged(world: ClientLevel, x: Int, y: Int, z: Int) {
        if (world === level && (y shr 4) >= minSectionY && (y shr 4) <= maxSectionY) queue(x shr 4, z shr 4)
    }

    @JvmStatic
    fun chunkChanged(world: ClientLevel, chunkX: Int, chunkZ: Int) {
        if (world === level) queue(chunkX, chunkZ)
    }

    private fun queue(chunkX: Int, chunkZ: Int) {
        val dx = ((chunkX shl 4) + 8 - centerX).toDouble()
        val dz = ((chunkZ shl 4) + 8 - centerZ).toDouble()
        if (dx * dx + dz * dz <= (RECORD_RADIUS + 12.0) * (RECORD_RADIUS + 12.0)) QUEUE.add(ChunkPos.pack(chunkX, chunkZ))
    }

    /** Writes down the chunk as it is now, if the client has it. */
    private fun snapshot(chunkX: Int, chunkZ: Int) {
        val world = level ?: return
        val kept = sections ?: return
        if (!world.chunkSource.hasChunk(chunkX, chunkZ)) return
        val chunk = world.getChunk(chunkX, chunkZ)
        val air = Block.getId(Blocks.AIR.defaultBlockState())
        var differing = 0
        var compared = 0
        val taken = Long2ObjectOpenHashMap<Section>()
        for (sy in minSectionY..maxSectionY) {
            val source = chunk.getSection(world.getSectionIndexFromSectionY(sy))
            val section = if (source.hasOnlyAir()) Section(intArrayOf(air), null, null) else read(source)
            val key = BlockPos.asLong(chunkX, sy, chunkZ)
            val old = kept.get(key)
            if (old != null) {
                for (i in 0 until SECTION_SIZE) {
                    val was = old.id(i)
                    val now = section.id(i)
                    if (was != air || now != air) {
                        compared++
                        if (was != now) differing++
                    }
                }
            }
            taken.put(key, section)
        }
        if (compared >= FOREIGN_MIN_BLOCKS && differing > compared * FOREIGN_SHARE) {
            GeilerAddons.LOGGER.info("World store: chunk {} {} is not the one kept ({} of {} blocks differ), starting over", chunkX, chunkZ, differing, compared)
            kept.clear()
            TerrainCache.clear()
        }
        kept.putAll(taken)
        dirty = true
    }

    private fun read(source: LevelChunkSection): Section {
        val slots = Int2IntOpenHashMap()
        slots.defaultReturnValue(-1)
        val ids = IntArray(SECTION_SIZE)
        var palette = IntArray(16)
        var size = 0
        var lastId = -1
        var lastSlot = -1
        for (i in 0 until SECTION_SIZE) {
            val id = Block.getId(source.getBlockState(i and 15, (i shr 8) and 15, (i shr 4) and 15))
            var slot: Int
            if (id == lastId) {
                slot = lastSlot
            } else {
                slot = slots.get(id)
                if (slot < 0) {
                    slot = size
                    slots.put(id, slot)
                    if (size == palette.size) palette = palette.copyOf(size * 2)
                    palette[size++] = id
                }
                lastId = id
                lastSlot = slot
            }
            ids[i] = slot
        }
        palette = palette.copyOf(size)
        if (size == 1) return Section(palette, null, null)
        if (size <= 256) {
            val small = ByteArray(SECTION_SIZE)
            for (i in 0 until SECTION_SIZE) small[i] = ids[i].toByte()
            return Section(palette, small, null)
        }
        val wide = CharArray(SECTION_SIZE)
        for (i in 0 until SECTION_SIZE) wide[i] = ids[i].toChar()
        return Section(palette, null, wide)
    }

    /** Hands a copy of what is kept to the writer thread. */
    private fun save() {
        val kept = sections
        val target = file
        if (!dirty || kept == null || target == null) return
        dirty = false
        savedMillis = Util.getMillis()
        val copy = Long2ObjectOpenHashMap(kept)
        IO.execute { write(target, copy) }
    }

    /** Which states the numbers in a file stand for: a file written under a different game or set of mods is no use. */
    @JvmStatic
    fun fingerprint(): Long = (Block.BLOCK_STATE_REGISTRY.size().toLong() shl 32) xor BuiltInRegistries.BLOCK.size().toLong()

    @JvmStatic
    fun write(target: java.nio.file.Path, data: Long2ObjectOpenHashMap<Section>) {
        try {
            Files.createDirectories(target.parent)
            val temporary = target.resolveSibling(target.fileName.toString() + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary)))).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(fingerprint())
                out.writeInt(data.size)
                for (entry in data.long2ObjectEntrySet()) {
                    val section = entry.value
                    out.writeLong(entry.longKey)
                    out.writeShort(section.palette.size)
                    for (id in section.palette) out.writeInt(id)
                    if (section.small != null) {
                        out.write(section.small)
                    } else if (section.wide != null) {
                        for (slot in section.wide) out.writeChar(slot.code)
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (exception: IOException) {
            GeilerAddons.LOGGER.error("Could not write {}", target, exception)
        }
    }

    @JvmStatic
    fun read(source: java.nio.file.Path): Long2ObjectOpenHashMap<Section> {
        val data = Long2ObjectOpenHashMap<Section>()
        if (!Files.exists(source)) return data
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(source)))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION || input.readLong() != fingerprint()) {
                    GeilerAddons.LOGGER.info("World store {} is from another version, not used", source.fileName)
                    return data
                }
                val count = input.readInt()
                for (n in 0 until count) {
                    val key = input.readLong()
                    val size = input.readUnsignedShort()
                    val palette = IntArray(size)
                    for (i in 0 until size) palette[i] = input.readInt()
                    if (size == 1) {
                        data.put(key, Section(palette, null, null))
                    } else if (size <= 256) {
                        val small = ByteArray(SECTION_SIZE)
                        input.readFully(small)
                        data.put(key, Section(palette, small, null))
                    } else {
                        val wide = CharArray(SECTION_SIZE)
                        for (i in 0 until SECTION_SIZE) wide[i] = input.readChar()
                        data.put(key, Section(palette, null, wide))
                    }
                }
            }
        } catch (exception: IOException) {
            GeilerAddons.LOGGER.error("Could not read {}, starting with nothing", source, exception)
            return Long2ObjectOpenHashMap()
        } catch (exception: RuntimeException) {
            GeilerAddons.LOGGER.error("Could not read {}, starting with nothing", source, exception)
            return Long2ObjectOpenHashMap()
        }
        return data
    }
}
