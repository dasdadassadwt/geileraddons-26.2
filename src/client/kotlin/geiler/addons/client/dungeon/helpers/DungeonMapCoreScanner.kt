package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonGridRules
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import java.util.HashMap

/** Incremental center-chunk scanner for the dungeon room map. */
class DungeonMapCoreScanner {
    @JvmRecord
    data class RoomCore(
        val hash: Int,
        val highestBlock: Int,
        val data: DungeonRoomCatalog.RoomData?,
    ) {
        fun withData(value: DungeonRoomCatalog.RoomData?): RoomCore = copy(data = value)

        override fun toString(): String =
            "RoomCore[hash=$hash, highestBlock=$highestBlock, data=$data]"
    }

    private val scannedChunks = arrayOfNulls<LevelChunk>(TILE_COUNT)
    private val unresolvedTiles = BooleanArray(TILE_COUNT)
    private val found = HashMap<Int, DungeonMapCoreScanner.RoomCore>()
    private val loadedCenterChunks = HashMap<Int, LevelChunk>()
    @Volatile
    private var snapshot: Map<Int, DungeonMapCoreScanner.RoomCore> = emptyMap()

    private var scanAttempts = 0L
    private var unresolvedRetries = 0L
    private var retainedIdentities = 0L
    private var conflictingCatalogReads = 0L
    private var currentLevel: Any? = null
    private var loadedLevel: Any? = null
    private var scanActive = false
    private var initialScanComplete = false
    private var centerChunkLoads = 0L
    private var centerChunkUnloads = 0L
    private var eventScans = 0L
    private var polledScans = 0L
    private var rejectedChunkEvents = 0L
    private var nextTile = 0
    private var focusedLevel: Any? = null
    private var focusedTile = -1
    private var focusedChunk: LevelChunk? = null
    private var focusedRoomCore: DungeonMapCoreScanner.RoomCore? = null

    init {
        ClientChunkEvents.CHUNK_LOAD.register { world, chunk -> onChunkLoad(world, chunk) }
        ClientChunkEvents.CHUNK_UNLOAD.register { world, chunk -> onChunkUnload(world, chunk) }
    }

    /** Scans loaded center chunks on arrival, then maintains a one-tile unresolved retry cadence. */
    fun tick(client: Minecraft?) {
        val level = client?.level
        val player = client?.player
        if (client == null || level == null || player == null) {
            resetWorld()
            return
        }
        if (currentLevel !== level) reset(level)
        scanActive = true
        if (!initialScanComplete) {
            for (tile in 0 until TILE_COUNT) {
                val chunk = loadedChunk(level, tile)
                if (chunk != null && scannedChunks[tile] !== chunk) {
                    polledScans++
                    scanTile(tile, chunk)
                }
            }
            initialScanComplete = true
        }
        val backgroundTile = nextTile
        nextTile = (nextTile + 1) % TILE_COUNT
        val blockPosition = player.blockPosition()
        val currentCell = DungeonGridRules.cellAt(blockPosition.getX(), blockPosition.getZ())
        val playerTile = if (currentCell == null) -1 else currentCell.componentX + currentCell.componentZ * 6
        val playerChunk = loadedChunk(level, playerTile)
        val playerChunkNeedsScan = playerChunk != null && scannedChunks[playerTile] !== playerChunk
        val tile = preferredScanTile(playerTile, backgroundTile, playerChunkNeedsScan)
        val chunk = if (tile == playerTile) playerChunk else loadedChunk(level, tile)
        if (chunk != null && (scannedChunks[tile] !== chunk || unresolvedTiles[tile])) polledScans++
        scanTile(tile, chunk)
    }

    private fun onChunkLoad(world: Level?, chunk: LevelChunk?) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onChunkLoad(world, chunk) }
            return
        }
        if (world == null || chunk == null) return
        ensureLoadedLevel(world)
        val pos = chunk.getPos()
        val tile = tileForCenterChunk(pos.x, pos.z)
        if (tile < 0) {
            rejectedChunkEvents++
            return
        }
        val previous = loadedCenterChunks.put(tile, chunk)
        centerChunkLoads++
        if (scanActive && currentLevel === world && previous !== chunk) {
            eventScans++
            scanTile(tile, chunk)
        }
    }

    private fun onChunkUnload(world: Level?, chunk: LevelChunk?) {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute { onChunkUnload(world, chunk) }
            return
        }
        if (world == null || chunk == null || loadedLevel !== world) return
        val pos = chunk.getPos()
        val tile = tileForCenterChunk(pos.x, pos.z)
        if (tile < 0) return
        if (loadedCenterChunks.remove(tile, chunk)) centerChunkUnloads++
        if (scannedChunks[tile] === chunk) scannedChunks[tile] = null
    }

    private fun ensureLoadedLevel(level: Any) {
        if (loadedLevel === level) return
        loadedCenterChunks.clear()
        loadedLevel = level
    }

    fun snapshot(): Map<Int, DungeonMapCoreScanner.RoomCore> = snapshot

    /** Bounded run-local scan health for the automatic Dungeon Map status line. */
    fun diagnostics(): String {
        var recognized = 0
        var unresolved = 0
        var catalogMask = 0L
        var unresolvedMask = 0L
        for (tile in 0 until TILE_COUNT) {
            val core = snapshot[tile]
            if (core != null && core.data != null) {
                recognized++
                catalogMask = catalogMask or (1L shl tile)
            }
            if (unresolvedTiles[tile]) {
                unresolved++
                unresolvedMask = unresolvedMask or (1L shl tile)
            }
        }
        return "tiles=${snapshot.size},coverage=${snapshot.size}/$TILE_COUNT" +
            ",loadedCenters=${loadedCenterChunks.size},catalog=$recognized" +
            ",catalogMask=${hexMask(catalogMask)},retry=$unresolved" +
            ",unresolvedMask=${hexMask(unresolvedMask)}" +
            ",attempts=$scanAttempts,retries=$unresolvedRetries" +
            ",retained=$retainedIdentities,catalogConflicts=$conflictingCatalogReads" +
            ",chunkLoads=$centerChunkLoads,chunkUnloads=$centerChunkUnloads" +
            ",eventScans=$eventScans,polledScans=$polledScans" +
            ",rejectedChunkEvents=$rejectedChunkEvents"
    }

    /** Resolves the catalog entry for one tile using only the already-loaded center chunk. */
    fun roomDataAt(client: Minecraft?, cell: DungeonGridRules.Cell?): DungeonRoomCatalog.RoomData? =
        roomCoreAt(client, cell)?.data

    /** Resolves the cached room signature and sampled ceiling height without requesting a chunk. */
    fun roomCoreAt(client: Minecraft?, cell: DungeonGridRules.Cell?): DungeonMapCoreScanner.RoomCore? {
        val level = client?.level
        if (client == null || !client.isSameThread || level == null || cell == null) return null
        val tile = cell.componentX + cell.componentZ * 6
        if (tile < 0 || tile >= TILE_COUNT) return null
        if (focusedLevel !== level || focusedTile != tile) {
            focusedLevel = level
            focusedTile = tile
            focusedChunk = null
            focusedRoomCore = null
        }
        val chunkX = CHUNK_ORIGIN + cell.componentX * 2
        val chunkZ = CHUNK_ORIGIN + cell.componentZ * 2
        // ClientChunkCache.getChunk(..., false) is a non-creating lookup; never request a chunk.
        val chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false)
        if (chunk == null) {
            focusedChunk = null
            focusedRoomCore = null
            return null
        }
        if (focusedChunk !== chunk) {
            focusedChunk = chunk
            val core = readCore(chunk, chunkX, chunkZ)
            focusedRoomCore = core.withData(DungeonRoomCatalog.bundled().find(core.hash))
        }
        return focusedRoomCore
    }

    /** Clears run-local observations while retaining loaded chunks for a pre-entry handoff. */
    fun reset() {
        currentLevel = null
        clearRunState()
    }

    /** Clears run observations and loaded-chunk references at a confirmed world boundary. */
    fun resetWorld() {
        currentLevel = null
        loadedLevel = null
        loadedCenterChunks.clear()
        clearRunState()
    }

    private fun reset(level: Level) {
        ensureLoadedLevel(level)
        currentLevel = level
        clearRunState()
    }

    private fun clearRunState() {
        nextTile = 0
        scanActive = false
        initialScanComplete = false
        found.clear()
        java.util.Arrays.fill(scannedChunks, null)
        java.util.Arrays.fill(unresolvedTiles, false)
        snapshot = emptyMap()
        scanAttempts = 0
        unresolvedRetries = 0
        retainedIdentities = 0
        conflictingCatalogReads = 0
        focusedLevel = null
        focusedTile = -1
        focusedChunk = null
        focusedRoomCore = null
    }

    private fun loadedChunk(level: Level?, tile: Int): LevelChunk? {
        if (level == null || tile < 0 || tile >= TILE_COUNT) return null
        val tileX = tile % 6
        val tileZ = tile / 6
        val chunkX = CHUNK_ORIGIN + tileX * 2
        val chunkZ = CHUNK_ORIGIN + tileZ * 2
        // ClientChunkCache.getChunk(..., false) is the non-creating lookup; a missing chunk is skipped.
        val chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false)
        if (chunk == null) loadedCenterChunks.remove(tile)
        else {
            ensureLoadedLevel(level)
            loadedCenterChunks[tile] = chunk
        }
        return chunk
    }

    private fun scanTile(tile: Int, chunk: LevelChunk?) {
        if (tile < 0 || tile >= TILE_COUNT || chunk == null ||
            (scannedChunks[tile] === chunk && !unresolvedTiles[tile])
        ) return
        if (scannedChunks[tile] === chunk) unresolvedRetries++
        scanAttempts++
        scannedChunks[tile] = chunk
        val chunkX = CHUNK_ORIGIN + tile % 6 * 2
        val chunkZ = CHUNK_ORIGIN + tile / 6 * 2
        val core = readCore(chunk, chunkX, chunkZ)
        val data = DungeonRoomCatalog.bundled().find(core.hash)
        val previous = found[tile]
        if (data != null && !shouldRetainCatalogIdentity(previous, data)) {
            found[tile] = core.withData(data)
            unresolvedTiles[tile] = false
        } else if (previous != null && previous.data != null) {
            // Keep a positively identified room identity through later conflicting chunk reads.
            retainedIdentities++
            if (data != null) {
                if (previous.data != data) conflictingCatalogReads++
                unresolvedTiles[tile] = false
            } else {
                unresolvedTiles[tile] = true
            }
        } else {
            found[tile] = core.withData(null)
            unresolvedTiles[tile] = true
        }
        snapshot = java.util.Map.copyOf(found)
    }

    companion object {
        private const val TILE_COUNT = 36
        private const val CHUNK_ORIGIN = -12
        private const val SAMPLE_LOCAL_XZ = 7
        private const val SAMPLE_TOP_Y = 140
        private const val SAMPLE_BOTTOM_Y = 12

        @JvmStatic
        fun tileForCenterChunk(chunkX: Int, chunkZ: Int): Int {
            val localX = chunkX - CHUNK_ORIGIN
            val localZ = chunkZ - CHUNK_ORIGIN
            if (localX < 0 || localZ < 0 || localX > 10 || localZ > 10 ||
                (localX and 1) != 0 || (localZ and 1) != 0
            ) return -1
            return localX / 2 + localZ / 2 * 6
        }

        @JvmStatic
        fun preferredScanTile(playerTile: Int, backgroundTile: Int, playerChunkNeedsScan: Boolean): Int =
            if (playerChunkNeedsScan && playerTile in 0 until TILE_COUNT) playerTile else backgroundTile

        @JvmStatic
        fun shouldRetainCatalogIdentity(
            previous: DungeonMapCoreScanner.RoomCore?,
            observed: DungeonRoomCatalog.RoomData?,
        ): Boolean = previous != null && previous.data != null && previous.data != observed

        @JvmStatic
        fun readCore(chunk: LevelChunk, chunkX: Int, chunkZ: Int): DungeonMapCoreScanner.RoomCore {
            val signature = StringBuilder(512)
            var foundHighest = false
            var highestBlock = 0
            var consecutiveBedrock = 0
            val blockX = chunkX * 16 + SAMPLE_LOCAL_XZ
            val blockZ = chunkZ * 16 + SAMPLE_LOCAL_XZ
            for (y in SAMPLE_TOP_Y downTo SAMPLE_BOTTOM_Y) {
                val state: BlockState = chunk.getBlockState(BlockPos(blockX, y, blockZ))
                if (!foundHighest) {
                    if (!state.isAir && state.getBlock() !== Blocks.GOLD_BLOCK) {
                        foundHighest = true
                        highestBlock = y
                    } else {
                        signature.append('0')
                    }
                }
                if (!foundHighest) continue
                if (state.isAir && consecutiveBedrock >= 2 && y < 69) {
                    signature.append("0".repeat((y - 11).coerceAtLeast(0)))
                    break
                }
                if (state.getBlock() === Blocks.BEDROCK) consecutiveBedrock++
                else {
                    consecutiveBedrock = 0
                    if (state.getBlock() === Blocks.OAK_PLANKS || state.getBlock() === Blocks.TRAPPED_CHEST ||
                        state.getBlock() === Blocks.CHEST
                    ) continue
                }
                // Odin appends each registered Block.toString(); empty air remains significant.
                signature.append(state.getBlock())
            }
            return DungeonMapCoreScanner.RoomCore(signature.toString().hashCode(), highestBlock, null)
        }

        private fun hexMask(mask: Long): String {
            val hex = java.lang.Long.toHexString(mask)
            return "0".repeat((9 - hex.length).coerceAtLeast(0)) + hex
        }
    }
}
