package geiler.addons.client.module.impl

import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Budgeted client-thread scanner for loaded sections; clustering consumes only frozen scan results. */
internal class BlockEspScanner {
    private var scan: Scan? = null
    private var pendingSnapshot: FutureTask<Snapshot>? = null
    private var lastLevel: ClientLevel? = null
    private var completedSnapshot = Snapshot.EMPTY
    private var preparedTargets: List<Target> = emptyList()
    private var preparedSettingsFingerprint = Long.MIN_VALUE
    private var preparedTargetSignature = ""
    private var lastSignature = ""
    private val cachePolicy = BlockEspScanCachePolicy()
    private var invalidatedDuringScan: String? = null
    private var lastChunkWork = 0
    private var lastBlockWork = 0
    private var lastAdvanceNanos = 0L

    fun snapshot(): Snapshot = completedSnapshot

    fun clear(entries: List<BlockEspEntry>) {
        scan = null
        cancelPendingSnapshot()
        completedSnapshot = Snapshot.EMPTY
        preparedTargets = emptyList()
        preparedSettingsFingerprint = Long.MIN_VALUE
        preparedTargetSignature = ""
        lastLevel = null
        lastSignature = ""
        cachePolicy.reset()
        invalidatedDuringScan = null
        lastChunkWork = 0
        lastBlockWork = 0
        lastAdvanceNanos = 0
        entries.forEach { it.setStatus(0) }
    }

    fun tick(level: ClientLevel?, player: LocalPlayer?, renderDistanceBlocks: Int, entries: List<BlockEspEntry>) {
        lastChunkWork = 0
        lastBlockWork = 0
        lastAdvanceNanos = 0
        if (level == null || player == null) {
            clear(entries)
            return
        }

        val currentIsland = HypixelModApi.currentIsland()
        val settingsFingerprint = settingsFingerprint(entries, renderDistanceBlocks, currentIsland)
        if (settingsFingerprint != preparedSettingsFingerprint) {
            preparedSettingsFingerprint = settingsFingerprint
            preparedTargets = prepareTargets(entries, renderDistanceBlocks, currentIsland)
            preparedTargetSignature = signature(preparedTargets)
        }

        val current = player.blockPosition()
        if (level !== lastLevel) {
            lastLevel = level
            completedSnapshot = Snapshot.EMPTY
            scan = null
            cancelPendingSnapshot()
            invalidatedDuringScan = null
            cachePolicy.invalidate("world-changed")
        }
        if (preparedTargetSignature != lastSignature) {
            scan = null
            cancelPendingSnapshot()
            invalidatedDuringScan = null
            cachePolicy.invalidate("settings-changed")
            lastSignature = preparedTargetSignature
        }
        if (preparedTargets.isEmpty()) {
            completedSnapshot = Snapshot.EMPTY
            scan = null
            cancelPendingSnapshot()
            invalidatedDuringScan = null
            return
        }

        var nextScan = scan
        if (nextScan == null) {
            val moved = (completedSnapshot.center()?.distSqr(current) ?: 0.0) > CENTER_REFRESH_DISTANCE_SQUARED
            if (moved) cachePolicy.invalidate("player-moved")
            if (!moved && cachePolicy.consumeWaitTick()) return
            nextScan = Scan(level, current, preparedTargets)
            scan = nextScan
        }
        val currentScan = checkNotNull(nextScan)

        advance(currentScan)
        if (!currentScan.complete) return

        // This worker reads frozen candidates only; it never accesses the world or live settings.
        val pending = pendingSnapshot ?: FutureTask(
            java.util.concurrent.Callable { currentScan.toSnapshot() },
        ).also {
            pendingSnapshot = it
            SNAPSHOT_WORKER.execute(it)
        }
        if (!pending.isDone) return

        try {
            val nextSnapshot = pending.get()
            val invalidation = invalidatedDuringScan
            if (invalidation != null) {
                invalidatedDuringScan = null
                scan = null
                pendingSnapshot = null
                cachePolicy.invalidate("$invalidation-during-scan")
                return
            }
            completedSnapshot = nextSnapshot
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            scan = null
            pendingSnapshot = null
            invalidatedDuringScan = null
            cachePolicy.failedScan()
            return
        } catch (_: ExecutionException) {
            retryAfterSnapshotFailure()
            return
        } catch (_: CancellationException) {
            retryAfterSnapshotFailure()
            return
        }

        currentScan.targets.forEach { target ->
            target.entry.setStatus(completedSnapshot.result(target.entry.id())?.matchCount() ?: 0)
        }
        scan = null
        pendingSnapshot = null
        cachePolicy.completedScan()
    }

    /** Invalidate a cached/active result when a relevant client chunk is loaded or unloaded. */
    fun invalidateChunk(level: ClientLevel, chunkX: Int, chunkZ: Int, loaded: Boolean) {
        val currentScan = scan
        val expectedLevel = currentScan?.level ?: lastLevel ?: return
        if (level !== expectedLevel) return

        val center = currentScan?.center ?: completedSnapshot.center() ?: return
        val targets = currentScan?.targets ?: preparedTargets
        if (targets.none { chunkIntersectsRadius(center.x, center.z, chunkX, chunkZ, it.radius) }) return

        val reason = if (loaded) "chunk-loaded" else "chunk-unloaded"
        if (currentScan != null) {
            invalidatedDuringScan = reason
        } else {
            cachePolicy.invalidate(reason)
        }
    }

    /** Scan stage and per-tick work counters used by the bounded automatic diagnostic status. */
    fun diagnosticSummary(): String {
        val currentScan = scan
        val scanState = if (currentScan == null) {
            "idle"
        } else {
            val currentTask = currentScan.tasks.getOrNull(currentScan.taskIndex)
            "${if (currentScan.sectionsPrepared) "blocks" else "chunks"}:" +
                "${currentScan.chunkIndex}/${currentScan.chunkCoordinates.size}," +
                "sections=${currentScan.taskIndex}/${currentScan.tasks.size}," +
                "cell=${currentTask?.localIndex ?: 0}/$SECTION_BLOCK_COUNT," +
                "targets=${currentScan.targets.size},complete=${currentScan.complete}"
        }
        val center = completedSnapshot.center()?.let { "${it.x},${it.y},${it.z}" } ?: "none"
        val totalMatches = completedSnapshot.results().sumOf(EntryResult::matchCount)
        val snapshotPending = pendingSnapshot?.let { !it.isDone } ?: false
        return "stage=$scanState,workChunks=$lastChunkWork,workCells=$lastBlockWork," +
            "advanceUs=${lastAdvanceNanos / 1_000},pendingSnapshot=$snapshotPending," +
            "preparedTargets=${preparedTargets.size}," +
            "completedCenter=$center,completedEntries=${completedSnapshot.results().size}," +
            "completedMatches=$totalMatches,refreshDelay=${cachePolicy.ticksUntilRefresh()}," +
            "refreshReason=${cachePolicy.refreshReason()},invalidations=${cachePolicy.invalidationCount()}," +
            "invalidatedDuringScan=${invalidatedDuringScan ?: "none"}"
    }

    private fun retryAfterSnapshotFailure() {
        // Preserve the last complete result if the worker fails; the next refresh can retry.
        scan = null
        pendingSnapshot = null
        invalidatedDuringScan = null
        cachePolicy.failedScan()
    }

    private fun prepareTargets(
        entries: List<BlockEspEntry>,
        renderDistanceBlocks: Int,
        currentIsland: Island?,
    ): List<Target> = buildList {
        entries.forEach { entry ->
            if (!entry.enabled().value() || entry.blockId().isBlank()) {
                entry.setStatus(0)
                return@forEach
            }
            val block = BlockEspRules.resolve(entry.blockId())
            if (block == null || !BlockEspRules.matches(entry.blockId(), block) || !entry.appliesOn(currentIsland)) {
                entry.setStatus(0)
                return@forEach
            }
            val radius = effectiveRadius(
                renderDistanceBlocks,
                entry.useCustomRange().value(),
                entry.customRange().intValue(),
            )
            add(Target(entry, block, radius, entry.blockId(), entry.connectTouching().value(), currentIsland!!))
        }
    }

    /** Avoid re-resolving registry blocks on scan-idle ticks. */
    private fun settingsFingerprint(
        entries: List<BlockEspEntry>,
        renderDistanceBlocks: Int,
        currentIsland: Island?,
    ): Long {
        var hash = FNV_OFFSET
        hash = mix(hash, renderDistanceBlocks.toLong())
        hash = mix(hash, (currentIsland?.ordinal ?: -1).toLong())
        hash = mix(hash, if (HypixelModApi.hasLocation()) 1 else 0)
        entries.forEach { entry ->
            hash = mix(hash, entry.id().toLong())
            hash = mix(hash, if (entry.enabled().value()) 1 else 0)
            hash = mix(hash, entry.blockId().hashCode().toLong())
            hash = mix(hash, if (entry.useCustomRange().value()) 1 else 0)
            hash = mix(hash, entry.customRange().intValue().toLong())
            hash = mix(hash, if (entry.connectTouching().value()) 1 else 0)
            hash = mix(hash, if (entry.appliesOn(currentIsland)) 1 else 0)
        }
        return hash
    }

    private fun cancelPendingSnapshot() {
        val pending = pendingSnapshot ?: return
        pending.cancel(true)
        SNAPSHOT_WORKER.remove(pending)
        pendingSnapshot = null
    }

    private fun advance(currentScan: Scan) {
        val startedAt = System.nanoTime()
        val deadline = startedAt + BLOCK_SCAN_BUDGET_NANOS
        if (!currentScan.sectionsPrepared) {
            lastChunkWork = prepareSections(currentScan, deadline)
            lastAdvanceNanos = System.nanoTime() - startedAt
            return
        }

        val generation = ++currentScan.checkGeneration
        var work = 0
        var iterations = 0
        while (work < BLOCK_BUDGET_PER_TICK && currentScan.taskIndex < currentScan.tasks.size) {
            if ((iterations++ and 0x3ff) == 0 && System.nanoTime() >= deadline) break
            val task = currentScan.tasks[currentScan.taskIndex]
            if (task.checkedGeneration != generation) {
                task.checkedGeneration = generation
                if (task.chunk !== currentScan.level.getChunkSource().getChunk(task.chunkX, task.chunkZ, false)) {
                    task.localIndex = SECTION_BLOCK_COUNT
                    continue
                }
            }
            if (task.localIndex >= SECTION_BLOCK_COUNT) {
                currentScan.taskIndex++
                continue
            }

            val index = task.localIndex++
            work++
            val localX = index and 15
            val localZ = (index ushr 4) and 15
            val localY = index ushr 8
            val x = task.chunkX * 16 + localX
            val y = task.minY + localY
            val z = task.chunkZ * 16 + localZ
            if (!withinRadius(currentScan.center, x, y, z, currentScan.maxRadius)) continue
            val matchingTargets = currentScan.targetsByBlock[task.section.getBlockState(localX, localY, localZ).block]
                ?: continue
            var position: BlockPos? = null
            matchingTargets.forEach { target ->
                if (withinRadius(currentScan.center, x, y, z, target.radius)) {
                    if (position == null) position = BlockPos(x, y, z)
                    currentScan.candidates.getValue(target.entry.id()).offer(position!!)
                }
            }
        }
        lastBlockWork = work
        currentScan.complete = currentScan.taskIndex >= currentScan.tasks.size
        lastAdvanceNanos = System.nanoTime() - startedAt
    }

    private fun prepareSections(currentScan: Scan, deadline: Long): Int {
        var work = 0
        while (work < CHUNK_BUDGET_PER_TICK && currentScan.chunkIndex < currentScan.chunkCoordinates.size) {
            val coordinate = currentScan.chunkCoordinates[currentScan.chunkIndex++]
            work++
            val chunk = currentScan.level.getChunkSource().getChunk(coordinate.x, coordinate.z, false)
            if (chunk == null) {
                if (System.nanoTime() >= deadline) break
                continue
            }

            val chunkTasks = ArrayList<SectionTask>()
            val sections = chunk.sections
            for (sectionIndex in sections.indices) {
                val section = sections[sectionIndex] ?: continue
                val minY = chunk.minY + sectionIndex * 16
                if (!Scan.intersectsSphere(currentScan.center, coordinate.x * 16, minY, coordinate.z * 16, currentScan.maxRadius)) {
                    continue
                }
                if (!section.maybeHas { state -> currentScan.selectedBlocks.contains(state.block) }) continue
                val distance = Scan.distanceToSectionSquared(
                    currentScan.center,
                    coordinate.x * 16,
                    minY,
                    coordinate.z * 16,
                )
                chunkTasks += SectionTask(chunk, section, coordinate.x, coordinate.z, minY, distance)
            }
            chunkTasks.sortWith(compareBy<SectionTask> { it.distanceSquared }.thenBy { it.minY })
            currentScan.tasks.addAll(chunkTasks)
            if (System.nanoTime() >= deadline) break
        }
        currentScan.sectionsPrepared = currentScan.chunkIndex >= currentScan.chunkCoordinates.size
        return work
    }

    private fun signature(targets: List<Target>): String = buildString {
        targets.forEach { target ->
            append(target.entry.id()).append(':').append(target.blockId).append(':').append(target.radius)
                .append(':').append(target.connectTouching).append(':').append(target.island).append('|')
        }
    }

    private data class Target(
        val entry: BlockEspEntry,
        val block: Block,
        val radius: Int,
        val blockId: String,
        val connectTouching: Boolean,
        val island: Island,
    )

    class Snapshot(center: BlockPos?, results: List<EntryResult>) {
        private val scanCenter = center?.immutable()
        private val entryResults = Collections.unmodifiableList(ArrayList(results))

        fun center(): BlockPos? = scanCenter
        fun results(): List<EntryResult> = entryResults
        fun result(id: Int): EntryResult? = entryResults.firstOrNull { it.entryId() == id }

        companion object {
            @JvmField
            val EMPTY = Snapshot(null, emptyList())
        }
    }

    class EntryResult(
        entryId: Int,
        blockId: String,
        radius: Int,
        connectTouching: Boolean,
        island: Island,
        blocks: Set<BlockPos>,
        clusters: List<BlockClusterer.Cluster>,
        renderCache: BlockEspRenderCache?,
        matchCount: Int,
    ) {
        private val id = entryId
        private val selectedBlockId = blockId
        private val scanRadius = radius
        private val connected = connectTouching
        private val scanIsland = island
        private val matchedBlocks = Collections.unmodifiableSet(blocks)
        private val blockClusters = Collections.unmodifiableList(ArrayList(clusters))
        private val preparedRenderCache = renderCache
        private val totalMatches = matchCount

        fun entryId(): Int = id
        fun blockId(): String = selectedBlockId
        fun radius(): Int = scanRadius
        fun connectTouching(): Boolean = connected
        fun island(): Island = scanIsland
        fun blocks(): Set<BlockPos> = matchedBlocks
        fun clusters(): List<BlockClusterer.Cluster> = blockClusters
        fun renderCache(): BlockEspRenderCache? = preparedRenderCache
        fun matchCount(): Int = totalMatches

    }

    private class Scan(
        val level: ClientLevel,
        center: BlockPos,
        val targets: List<Target>,
    ) {
        val center: BlockPos = center.immutable()
        val maxRadius = targets.maxOfOrNull(Target::radius) ?: 0
        val targetsByBlock = HashMap<Block, MutableList<Target>>()
        val candidates = HashMap<Int, Candidates>()
        val selectedBlocks = HashSet<Block>()
        val chunkCoordinates: List<ChunkCoordinate>
        val tasks = ArrayList<SectionTask>()
        var chunkIndex = 0
        var taskIndex = 0
        var checkGeneration = 0
        var sectionsPrepared = false
        var complete = false

        init {
            targets.forEach { target ->
                targetsByBlock.getOrPut(target.block) { ArrayList() } += target
                selectedBlocks += target.block
                candidates[target.entry.id()] = Candidates()
            }
            val minChunkX = Math.floorDiv(this.center.x - maxRadius, 16)
            val maxChunkX = Math.floorDiv(this.center.x + maxRadius, 16)
            val minChunkZ = Math.floorDiv(this.center.z - maxRadius, 16)
            val maxChunkZ = Math.floorDiv(this.center.z + maxRadius, 16)
            val scanCenter = this.center
            chunkCoordinates = buildList {
                for (chunkX in minChunkX..maxChunkX) {
                    for (chunkZ in minChunkZ..maxChunkZ) {
                        val dx = distanceToInterval(scanCenter.x, chunkX * 16, chunkX * 16 + 15)
                        val dz = distanceToInterval(scanCenter.z, chunkZ * 16, chunkZ * 16 + 15)
                        val distance = dx * dx + dz * dz
                        if (distance <= maxRadius.toLong() * maxRadius) {
                            add(ChunkCoordinate(chunkX, chunkZ, distance))
                        }
                    }
                }
            }.sortedWith(compareBy<ChunkCoordinate> { it.distanceSquared }.thenBy { it.x }.thenBy { it.z })
        }

        fun toSnapshot(): Snapshot {
            val results = targets.map { target ->
                val blocks = candidates.getValue(target.entry.id()).positions
                val clusters = if (target.connectTouching) BlockClusterer.cluster(blocks, true) else emptyList()
                val renderCache = if (target.connectTouching) BlockEspRenderCache.prepare(blocks) else null
                EntryResult(
                    target.entry.id(),
                    target.blockId,
                    target.radius,
                    target.connectTouching,
                    target.island,
                    blocks,
                    clusters,
                    renderCache,
                    blocks.size,
                )
            }
            return Snapshot(center, results)
        }

        companion object {
            fun intersectsSphere(center: BlockPos, minX: Int, minY: Int, minZ: Int, radius: Int): Boolean =
                distanceToSectionSquared(center, minX, minY, minZ) <= radius.toLong() * radius

            fun distanceToSectionSquared(center: BlockPos, minX: Int, minY: Int, minZ: Int): Long {
                val dx = distanceToInterval(center.x, minX, minX + 15)
                val dy = distanceToInterval(center.y, minY, minY + 15)
                val dz = distanceToInterval(center.z, minZ, minZ + 15)
                return dx * dx + dy * dy + dz * dz
            }

            private fun distanceToInterval(point: Int, min: Int, max: Int): Long = when {
                point < min -> min.toLong() - point
                point > max -> point.toLong() - max
                else -> 0
            }
        }
    }

    private data class ChunkCoordinate(val x: Int, val z: Int, val distanceSquared: Long)

    private class SectionTask(
        val chunk: LevelChunk,
        val section: LevelChunkSection,
        val chunkX: Int,
        val chunkZ: Int,
        val minY: Int,
        val distanceSquared: Long,
    ) {
        var localIndex = 0
        var checkedGeneration = -1
    }

    class Candidates {
        @JvmField
        val positions: MutableSet<BlockPos> = HashSet()

        fun offer(position: BlockPos) {
            positions += position
        }
    }

    companion object {
        private const val CHUNK_BUDGET_PER_TICK = 32
        private const val BLOCK_BUDGET_PER_TICK = 250_000
        private const val BLOCK_SCAN_BUDGET_NANOS = 2_000_000L
        private const val CENTER_REFRESH_DISTANCE_SQUARED = 64L
        private const val FNV_OFFSET = -3750763034362895579L
        private const val FNV_PRIME = 0x100000001B3L

        private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

        const val SECTION_BLOCK_COUNT = SectionPos.SECTION_BLOCK_COUNT

        private val SNAPSHOT_WORKER = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            { task -> Thread(task, "GeilerAddons Block ESP Clustering").apply { isDaemon = true } },
        )

        @JvmStatic
        fun effectiveRadius(renderDistanceBlocks: Int, useCustomRange: Boolean, customRange: Int): Int {
            val renderRadius = renderDistanceBlocks.coerceAtLeast(0)
            return if (useCustomRange) renderRadius.coerceAtMost(customRange.coerceAtLeast(0)) else renderRadius
        }

        @JvmStatic
        fun mayInspectChunk(chunkAlreadyLoaded: Boolean): Boolean = chunkAlreadyLoaded

        @JvmStatic
        fun chunkIntersectsRadius(centerX: Int, centerZ: Int, chunkX: Int, chunkZ: Int, radius: Int): Boolean {
            val minX = chunkX.toLong() * 16L
            val minZ = chunkZ.toLong() * 16L
            val dx = distanceToInterval(centerX.toLong(), minX, minX + 15L)
            val dz = distanceToInterval(centerZ.toLong(), minZ, minZ + 15L)
            val safeRadius = radius.coerceAtLeast(0).toLong()
            return dx * dx + dz * dz <= safeRadius * safeRadius
        }

        @JvmStatic
        fun withinRadius(center: BlockPos, position: BlockPos, radius: Int): Boolean =
            withinRadius(center, position.x, position.y, position.z, radius)

        private fun withinRadius(center: BlockPos, x: Int, y: Int, z: Int, radius: Int): Boolean {
            val dx = center.x.toLong() - x
            val dy = center.y.toLong() - y
            val dz = center.z.toLong() - z
            return dx * dx + dy * dy + dz * dz <= radius.toLong() * radius
        }

        private fun distanceToInterval(point: Long, min: Long, max: Long): Long = when {
            point < min -> min - point
            point > max -> point - max
            else -> 0L
        }

    }
}
