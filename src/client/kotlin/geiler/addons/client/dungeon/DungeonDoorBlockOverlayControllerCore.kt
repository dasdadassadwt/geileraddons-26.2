package geiler.addons.client.dungeon

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonDoorBlockReplacementRules.DoorKind
import geiler.addons.client.module.Category
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.ArrayList
import java.util.EnumMap
import java.util.HashMap
import java.util.HashSet

/** Kotlin owner of the Java-callable Glass Doors API and its client-thread state boundary. */
class DungeonDoorBlockOverlayController private constructor() {
    companion object {
        /** Reads the latest server-provided state when this position has a local projection. */
        @JvmStatic
        fun authoritativeState(level: ClientLevel?, position: BlockPos?): BlockState? =
            DungeonDoorBlockOverlayControllerCore.authoritativeState(level, position)

        /** Retains a newly discovered coal or red-terracotta source in the same-world mask. */
        @JvmStatic
        fun rememberDoorSource(
            level: ClientLevel?,
            kind: DoorKind?,
            position: BlockPos?,
        ): Boolean = DungeonDoorBlockOverlayControllerCore.rememberDoorSource(level, kind, position)

        /** Retains a chiseled Start source selected by the shared map-derived record. */
        @JvmStatic
        fun rememberStartSource(level: ClientLevel?, position: BlockPos?, sourceState: BlockState?): Boolean =
            DungeonDoorBlockOverlayControllerCore.rememberStartSource(level, position, sourceState)

        /** Returns the authoritative source only while the supplied state is our owned projection. */
        @JvmStatic
        fun miningState(level: ClientLevel?, position: BlockPos?, observedState: BlockState?): BlockState? =
            DungeonDoorBlockOverlayControllerCore.miningState(level, position, observedState)

        /** Returns selected source masks for the requested door type. */
        @JvmStatic
        fun retainedPositions(level: ClientLevel?, kind: DoorKind?): Set<Long> =
            DungeonDoorBlockOverlayControllerCore.retainedPositions(level, kind)

        /** Reconciles desired per-position local glass projections on the client thread. */
        @JvmStatic
        fun reconcile(level: ClientLevel?, desired: Map<Long, Projection>?) =
            DungeonDoorBlockOverlayControllerCore.reconcile(level, desired)

        /** Must run after vanilla installs a single-block server update. */
        @JvmStatic
        fun onServerBlockState(level: ClientLevel?, position: BlockPos?, serverState: BlockState?) =
            DungeonDoorBlockOverlayControllerCore.onServerBlockState(level, position, serverState)

        /** Must run after vanilla applies the complete section update. */
        @JvmStatic
        fun onServerSectionState(level: ClientLevel?, position: BlockPos?, serverState: BlockState?) =
            DungeonDoorBlockOverlayControllerCore.onServerSectionState(level, position, serverState)

        /** Re-seeds a newly installed chunk and reapplies retained active door projections. */
        @JvmStatic
        fun onChunkInstalled(level: ClientLevel?, chunkX: Int, chunkZ: Int) =
            DungeonDoorBlockOverlayControllerCore.onChunkInstalled(level, chunkX, chunkZ)

        /** Drops chunk-owned caches without writing into a chunk being unloaded. */
        @JvmStatic
        fun onChunkUnloaded(level: ClientLevel?, chunkX: Int, chunkZ: Int) =
            DungeonDoorBlockOverlayControllerCore.onChunkUnloaded(level, chunkX, chunkZ)

        /** Restores still-owned projections before the current client level is discarded. */
        @JvmStatic
        fun restoreAndClear(level: ClientLevel?) = DungeonDoorBlockOverlayControllerCore.restoreAndClear(level)

        /** Snapshot of projection stages and successful local writes/restores. */
        @JvmStatic
        fun stats(level: ClientLevel?): Map<DoorKind, TypeStats> =
            DungeonDoorBlockOverlayControllerCore.stats(level)

        @JvmStatic
        fun suppressedDetailLogCount(level: ClientLevel?): Long =
            DungeonDoorBlockOverlayControllerCore.suppressedDetailLogCount(level)

        /** Records status/layout snapshots folded into a later bounded change batch. */
        @JvmStatic
        fun recordCoalescedDetailSuppressions(level: ClientLevel?, count: Long) =
            DungeonDoorBlockOverlayControllerCore.recordCoalescedDetailSuppressions(level, count)
    }

    /** One desired local replacement, with the same record-style Java accessors and value equality. */
    data class Projection(val kind: DoorKind?, val state: BlockState?) {
        fun kind(): DoorKind? = kind
        fun state(): BlockState? = state

        override fun toString(): String = "Projection[kind=$kind, state=$state]"
    }

    /** Bounded current and cumulative projection counters for one door type. */
    data class TypeStats(
        val targets: Int,
        val loaded: Int,
        val sourceAir: Int,
        val sourceNonAir: Int,
        val active: Int,
        val writes: Long,
        val restores: Long,
        val failures: Long,
        val miningReads: Long,
        val miningSourceReturns: Long,
        val miningFallbacks: Long,
        val miningOwnershipMismatches: Long,
    ) {
        fun targets(): Int = targets
        fun loaded(): Int = loaded
        fun sourceAir(): Int = sourceAir
        fun sourceNonAir(): Int = sourceNonAir
        fun active(): Int = active
        fun writes(): Long = writes
        fun restores(): Long = restores
        fun failures(): Long = failures
        fun miningReads(): Long = miningReads
        fun miningSourceReturns(): Long = miningSourceReturns
        fun miningFallbacks(): Long = miningFallbacks
        fun miningOwnershipMismatches(): Long = miningOwnershipMismatches

        override fun toString(): String =
            "TypeStats[targets=$targets, loaded=$loaded, sourceAir=$sourceAir, sourceNonAir=$sourceNonAir, " +
                "active=$active, writes=$writes, restores=$restores, failures=$failures, " +
                "miningReads=$miningReads, miningSourceReturns=$miningSourceReturns, " +
                "miningFallbacks=$miningFallbacks, miningOwnershipMismatches=$miningOwnershipMismatches]"
    }

    /** Injectable world boundary retained for isolated lifecycle and update checks. */
    interface BlockAccess {
        fun levelIdentity(): Any?
        fun hasChunkAt(position: BlockPos): Boolean
        fun getBlockState(position: BlockPos): BlockState
        fun setBlock(position: BlockPos, state: BlockState, flags: Int): Boolean
        fun gameTick(): Long = 0L
    }

    /** Backend facade retained for offline lifecycle and update checks. */
    class Engine {
        private val delegate = DungeonDoorBlockOverlayControllerCore.newEngine()

        fun authoritativeState(world: BlockAccess, position: BlockPos): BlockState =
            delegate.authoritativeState(world, position)

        fun rememberDoorSource(world: BlockAccess, kind: DoorKind, position: BlockPos): Boolean =
            delegate.rememberDoorSource(world, kind, position)

        fun rememberStartSource(world: BlockAccess, position: BlockPos, sourceState: BlockState): Boolean =
            delegate.rememberStartSource(world, position, sourceState)

        fun miningState(world: BlockAccess, position: BlockPos, observedState: BlockState): BlockState =
            delegate.miningState(world, position, observedState) ?: observedState

        fun retainedPositions(world: BlockAccess, kind: DoorKind): Set<Long> =
            delegate.retainedPositions(world, kind)

        fun reconcile(world: BlockAccess, desired: Map<Long, Projection>) =
            delegate.reconcile(world, desired)

        fun onServerBlockState(world: BlockAccess, position: BlockPos, serverState: BlockState) =
            delegate.onServerBlockState(world, position, serverState)

        fun onChunkInstalled(world: BlockAccess, chunkX: Int, chunkZ: Int) =
            delegate.onChunkInstalled(world, chunkX, chunkZ)

        fun onChunkUnloaded(world: BlockAccess, chunkX: Int, chunkZ: Int) =
            delegate.onChunkUnloaded(world, chunkX, chunkZ)

        fun restoreAndClear(world: BlockAccess) = delegate.restoreAndClear(world)
        fun restoreAndClearWithoutWorld() = delegate.restoreAndClearWithoutWorld()
        fun clearWithoutWorld() = delegate.clearWithoutWorld()
        fun detailLogsThisTick(): Int = delegate.detailLogsThisTick()
        fun suppressedDetailLogs(): Long = delegate.suppressedDetailLogs()
        fun totalSuppressedDetailLogs(): Long = delegate.totalSuppressedDetailLogs()

        fun recordCoalescedDetailSuppressions(world: BlockAccess, count: Long) =
            delegate.recordCoalescedDetailSuppressions(world, count)

        fun recordCoalescedDetailSuppressionsWithoutWorld(count: Long) =
            delegate.recordCoalescedDetailSuppressionsWithoutWorld(count)

        fun stats(world: BlockAccess?): Map<DoorKind, TypeStats> = delegate.stats(world)
    }
}

/** Kotlin implementation behind the Java Glass Doors ABI and mixin boundary. */
object DungeonDoorBlockOverlayControllerCore {
    private const val MODULE = "Glass Doors"
    private const val MAX_DETAIL_LOGS_PER_TICK = 128
    private val ENGINE = Engine()

    @JvmStatic
    fun newEngine(): Engine = Engine()

    @JvmStatic
    fun authoritativeState(level: ClientLevel?, position: BlockPos?): BlockState? {
        if (level == null || position == null) return null
        return ENGINE.authoritativeState(ClientAccess(level), position)
    }

    @JvmStatic
    fun rememberDoorSource(
        level: ClientLevel?,
        kind: DoorKind?,
        position: BlockPos?,
    ): Boolean {
        if (level == null || kind == null || position == null) return false
        return ENGINE.rememberDoorSource(ClientAccess(level), kind, position)
    }

    @JvmStatic
    fun rememberStartSource(
        level: ClientLevel?,
        position: BlockPos?,
        sourceState: BlockState?,
    ): Boolean {
        if (level == null || position == null || sourceState == null) return false
        return ENGINE.rememberStartSource(ClientAccess(level), position, sourceState)
    }

    @JvmStatic
    fun miningState(
        level: ClientLevel?,
        position: BlockPos?,
        observedState: BlockState?,
    ): BlockState? {
        if (level == null || position == null) return observedState
        return ENGINE.miningState(ClientAccess(level), position, observedState)
    }

    @JvmStatic
    fun retainedPositions(level: ClientLevel?, kind: DoorKind?): Set<Long> {
        if (level == null) return emptySet()
        return ENGINE.retainedPositions(ClientAccess(level), kind)
    }

    @JvmStatic
    fun reconcile(
        level: ClientLevel?,
        desired: Map<Long, DungeonDoorBlockOverlayController.Projection?>?,
    ) {
        if (level == null) return
        ENGINE.reconcile(ClientAccess(level), desired ?: emptyMap())
    }

    @JvmStatic
    fun onServerBlockState(level: ClientLevel?, position: BlockPos?, serverState: BlockState?) {
        if (level == null || position == null || serverState == null) return
        ENGINE.onServerBlockState(ClientAccess(level), position, serverState)
    }

    @JvmStatic
    fun onServerSectionState(level: ClientLevel?, position: BlockPos?, serverState: BlockState?) {
        onServerBlockState(level, position, serverState)
    }

    @JvmStatic
    fun onChunkInstalled(level: ClientLevel?, chunkX: Int, chunkZ: Int) {
        if (level == null) return
        ENGINE.onChunkInstalled(ClientAccess(level), chunkX, chunkZ)
    }

    @JvmStatic
    fun onChunkUnloaded(level: ClientLevel?, chunkX: Int, chunkZ: Int) {
        if (level == null) return
        ENGINE.onChunkUnloaded(ClientAccess(level), chunkX, chunkZ)
    }

    @JvmStatic
    fun restoreAndClear(level: ClientLevel?) {
        if (level != null) ENGINE.restoreAndClear(ClientAccess(level))
        else ENGINE.restoreAndClearWithoutWorld()
    }

    @JvmStatic
    fun stats(level: ClientLevel?): Map<DoorKind, DungeonDoorBlockOverlayController.TypeStats> {
        return if (level == null) ENGINE.stats(null) else ENGINE.stats(ClientAccess(level))
    }

    @JvmStatic
    fun suppressedDetailLogCount(level: ClientLevel?): Long {
        if (level != null) ENGINE.stats(ClientAccess(level))
        return ENGINE.totalSuppressedDetailLogs()
    }

    @JvmStatic
    fun recordCoalescedDetailSuppressions(level: ClientLevel?, count: Long) {
        if (count <= 0) return
        if (level == null) ENGINE.recordCoalescedDetailSuppressionsWithoutWorld(count)
        else ENGINE.recordCoalescedDetailSuppressions(ClientAccess(level), count)
    }

    private class ClientAccess(private val level: ClientLevel) : DungeonDoorBlockOverlayController.BlockAccess {
        override fun levelIdentity(): Any = level

        override fun hasChunkAt(position: BlockPos): Boolean = level.hasChunkAt(position)

        override fun getBlockState(position: BlockPos): BlockState = level.getBlockState(position)

        override fun setBlock(position: BlockPos, state: BlockState, flags: Int): Boolean =
            level.setBlock(position, state, flags)

        override fun gameTick(): Long = level.gameTime
    }

    /** Package-visible through the Java facade so existing offline checks can use a fake world. */
    class Engine {
        private val authoritative = HashMap<Long, BlockState>()
        private val desired = HashMap<Long, DungeonDoorBlockOverlayController.Projection>()
        private val applied = HashMap<Long, Applied>()
        private val lastWriteLog = HashMap<Long, String>()
        private val lastMiningLog = HashMap<Long, String>()
        private val retiredDoorSources = HashMap<Long, DoorKind>()
        private val masks = EnumMap<DoorKind, MutableSet<Long>>(DoorKind::class.java)
        private val totals = EnumMap<DoorKind, MutableStats>(DoorKind::class.java)
        private val lastFailure = HashMap<Long, String>()
        private var levelIdentity: Any? = null
        private var currentGameTick = 0L
        private var logBudgetTick = Long.MIN_VALUE
        private var detailLogsThisTick = 0
        private var suppressedDetailLogs = 0L
        private var totalSuppressedDetailLogs = 0L

        init {
            for (kind in DungeonDoorBlockOverlayControllerCore.replacementKinds()) {
                masks[kind] = HashSet()
                totals[kind] = MutableStats()
            }
        }

        fun authoritativeState(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            position: BlockPos,
        ): BlockState {
            ensureLevel(world)
            val cached = authoritative[position.asLong()]
            return cached ?: world.getBlockState(position)
        }

        fun rememberDoorSource(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            kind: DoorKind,
            position: BlockPos,
        ): Boolean {
            ensureLevel(world)
            val mask = masks[kind]
            if ((kind != DoorKind.WITHER && kind != DoorKind.BLOOD)
                || mask == null || !world.hasChunkAt(position)
            ) return false
            val packed = position.asLong()
            if (retiredDoorSources.containsKey(packed)) return false
            val source = authoritative[packed] ?: world.getBlockState(position)
            if (!DungeonDoorBlockOverlayControllerCore.matchesDoorSource(kind, source)) return false
            mask.add(packed)
            authoritative.putIfAbsent(packed, source)
            return true
        }

        fun rememberStartSource(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            position: BlockPos,
            sourceState: BlockState,
        ): Boolean {
            ensureLevel(world)
            if (!world.hasChunkAt(position)) return false
            val packed = position.asLong()
            if (retiredDoorSources.containsKey(packed)) return false
            val mask = masks[DoorKind.START]!!
            if (mask.contains(packed)) return true
            if (!DungeonDoorBlockOverlayControllerCore.isStartSelectionSource(sourceState)) return false
            val added = mask.add(packed)
            authoritative.putIfAbsent(packed, sourceState)
            if (added) log(
                "MAP START SOURCE SELECTED pos=" + position.toShortString()
                    + " source=" + DungeonDoorBlockOverlayControllerCore.describe(sourceState),
            )
            return true
        }

        fun miningState(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            position: BlockPos,
            observedState: BlockState?,
        ): BlockState? {
            ensureLevel(world)
            val packed = position.asLong()
            val expected = desired[packed]
            val current = applied[packed]
            val kind = expected?.kind()
                ?: current?.projection?.kind()
                ?: retainedKind(packed)
            if (kind == null) return observedState
            val count = totals[kind] ?: return observedState
            count.miningReads++

            val source = authoritative[packed]
            var fallback: String? = null
            if (expected == null) fallback = "no-desired-projection"
            else if (current == null) fallback = "no-active-projection"
            else if (!current.owned) fallback = "projection-not-owned"
            else if (current.projection != expected) fallback = "projection-changed"
            else if (!world.hasChunkAt(position)) fallback = "chunk-unloaded"
            else if (observedState == null || observedState != expected.state()) {
                fallback = "observed-state-mismatch"
                count.miningOwnershipMismatches++
            } else if (world.getBlockState(position) != expected.state()) {
                fallback = "client-state-mismatch"
                count.miningOwnershipMismatches++
            } else if (source == null) fallback = "source-state-unavailable"
            else if (source.isAir) fallback = "source-air"

            if (fallback == null) {
                count.miningSourceReturns++
                logMining(
                    packed,
                    "SOURCE:" + DungeonDoorBlockOverlayControllerCore.describe(observedState)
                        + "->" + DungeonDoorBlockOverlayControllerCore.describe(source),
                    "MINING SOURCE STATE kind=" + kind + " pos=" + position.toShortString()
                        + " observed=" + DungeonDoorBlockOverlayControllerCore.describe(observedState)
                        + " source=" + DungeonDoorBlockOverlayControllerCore.describe(source),
                )
                return source
            }

            count.miningFallbacks++
            logMining(
                packed,
                "FALLBACK:" + DungeonDoorBlockOverlayControllerCore.describe(observedState) + ":" + fallback,
                "MINING SOURCE FALLBACK kind=" + kind + " pos=" + position.toShortString()
                    + " observed=" + DungeonDoorBlockOverlayControllerCore.describe(observedState)
                    + " reason=" + fallback,
            )
            return observedState
        }

        private fun logMining(packed: Long, signature: String, message: String) {
            if (signature != lastMiningLog.put(packed, signature)) log(message)
            else suppressDetailLog()
        }

        fun retainedPositions(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            kind: DoorKind?,
        ): Set<Long> {
            ensureLevel(world)
            val mask = if (kind == null) null else masks[kind]
            return if (mask == null) emptySet() else java.util.Set.copyOf(mask)
        }

        fun reconcile(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            nextDesired: Map<Long, DungeonDoorBlockOverlayController.Projection?>,
        ) {
            ensureLevel(world)
            val sanitized = HashMap<Long, DungeonDoorBlockOverlayController.Projection>()
            for ((packed, projection) in nextDesired) {
                if (projection != null && projection.kind() != null && projection.state() != null
                    && hasSelection(projection, packed)
                ) sanitized[packed] = projection
            }

            for (packed in ArrayList(desired.keys)) {
                val previous = desired[packed]
                val replacement = sanitized[packed]
                if (previous != replacement) removeProjection(world, packed, previous)
            }
            desired.clear()
            desired.putAll(sanitized)

            for ((packed, projection) in desired) {
                val position = BlockPos.of(packed)
                if (!world.hasChunkAt(position)) continue
                val serverState = authoritative.computeIfAbsent(packed) { world.getBlockState(position) }
                if (serverState.isAir) {
                    applied.remove(packed)
                    continue
                }
                val current = applied[packed]
                if (current != null && current.projection == projection
                    && world.getBlockState(position) == projection.state()
                ) continue
                applyProjection(world, packed, projection, serverState)
            }
        }

        fun onServerBlockState(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            position: BlockPos,
            serverState: BlockState,
        ) {
            ensureLevel(world)
            val packed = position.asLong()
            val projection = desired[packed]
            val retained = isRetained(packed)
            var selectedDoorKind = retainedKind(packed)
            if (projection != null) selectedDoorKind = projection.kind()
            val retiredDoorKind = retiredDoorSources[packed]
            val previousServerState = authoritative[packed]
            if (projection != null || retained || retiredDoorKind != null) authoritative[packed] = serverState
            else authoritative.remove(packed)
            val sourceChanged = previousServerState != null && previousServerState != serverState
            if (sourceChanged) {
                lastWriteLog.remove(packed)
                lastMiningLog.remove(packed)
            }
            val previous = applied.remove(packed)
            if ((projection != null || retained || retiredDoorKind != null) && sourceChanged) {
                val kind = projection?.kind()
                    ?: previous?.projection?.kind()
                    ?: selectedDoorKind
                    ?: retiredDoorKind
                    ?: retainedKind(packed)
                log(
                    "SERVER SOURCE STATE kind=" + kind + " pos=" + position.toShortString()
                        + " previous=" + DungeonDoorBlockOverlayControllerCore.describe(previousServerState)
                        + " server=" + DungeonDoorBlockOverlayControllerCore.describe(serverState)
                        + (if (serverState.isAir) "; local projection cleared" else "")
                        + (if (retiredDoorKind != null) "; retired=true" else ""),
                )
            } else if (previous != null && previousServerState == null && !serverState.isAir) {
                log(
                    "SERVER STATE REPLACED LOCAL OVERLAY kind=" + previous.projection.kind()
                        + " pos=" + position.toShortString() + " server="
                        + DungeonDoorBlockOverlayControllerCore.describe(serverState),
                )
            } else if (retiredDoorKind != null && previousServerState == null) {
                log(
                    "SERVER SOURCE STATE kind=" + retiredDoorKind + " pos=" + position.toShortString()
                        + " previous=unobserved server="
                        + DungeonDoorBlockOverlayControllerCore.describe(serverState) + "; retired=true",
                )
            }
            if (selectedDoorKind != null && serverState.isAir) {
                retireDoorSource(selectedDoorKind!!, packed, "authoritative-air")
            }
            if (projection == null || serverState.isAir || !world.hasChunkAt(position)) return
            applyProjection(world, packed, projection, serverState)
        }

        fun onChunkInstalled(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            chunkX: Int,
            chunkZ: Int,
        ) {
            ensureLevel(world)
            clearChunkRuntime(world, chunkX, chunkZ, true)
            for (kind in DungeonDoorBlockOverlayControllerCore.replacementKinds()) {
                for (packed in ArrayList(masks[kind]!!)) {
                    val position = BlockPos.of(packed)
                    if (!DungeonDoorBlockOverlayControllerCore.inChunk(position, chunkX, chunkZ)
                        || !world.hasChunkAt(position)
                    ) continue
                    val serverState = world.getBlockState(position)
                    authoritative[packed] = serverState
                    if (serverState.isAir) {
                        retireDoorSource(kind, packed, "installed-chunk-air")
                        continue
                    }
                    val projection = desired[packed]
                    if (projection != null && !serverState.isAir) {
                        applyProjection(world, packed, projection, serverState)
                    }
                }
            }
            for (packed in ArrayList(retiredDoorSources.keys)) {
                val position = BlockPos.of(packed)
                if (DungeonDoorBlockOverlayControllerCore.inChunk(position, chunkX, chunkZ)
                    && world.hasChunkAt(position)
                ) {
                    authoritative[packed] = world.getBlockState(position)
                }
            }
        }

        fun onChunkUnloaded(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            chunkX: Int,
            chunkZ: Int,
        ) {
            ensureLevel(world)
            clearChunkRuntime(world, chunkX, chunkZ, false)
        }

        fun restoreAndClear(world: DungeonDoorBlockOverlayController.BlockAccess) {
            updateGameTick(world.gameTick())
            if (levelIdentity != null && levelIdentity !== world.levelIdentity()) {
                logLostOwnership("client level changed before restoration")
                clearWithoutWorld()
                ensureLevel(world)
                return
            }
            ensureLevel(world)
            for (packed in ArrayList(applied.keys)) {
                val value = applied[packed]
                if (value != null) restoreProjection(world, packed, value)
            }
            clearWithoutWorld()
            levelIdentity = null
        }

        fun restoreAndClearWithoutWorld() {
            if (levelIdentity != null) logLostOwnership("client level unavailable before restoration")
            clearWithoutWorld()
        }

        fun clearWithoutWorld() {
            authoritative.clear()
            desired.clear()
            applied.clear()
            lastWriteLog.clear()
            lastMiningLog.clear()
            for (mask in masks.values) mask.clear()
            retiredDoorSources.clear()
            lastFailure.clear()
            levelIdentity = null
        }

        fun detailLogsThisTick(): Int = detailLogsThisTick
        fun suppressedDetailLogs(): Long = suppressedDetailLogs
        fun totalSuppressedDetailLogs(): Long = totalSuppressedDetailLogs

        fun recordCoalescedDetailSuppressions(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            count: Long,
        ) {
            ensureLevel(world)
            recordSuppressedDetailLogCount(count)
        }

        fun recordCoalescedDetailSuppressionsWithoutWorld(count: Long) {
            recordSuppressedDetailLogCount(count)
        }

        private fun recordSuppressedDetailLogCount(count: Long) {
            suppressedDetailLogs += count
            totalSuppressedDetailLogs += count
        }

        fun stats(
            world: DungeonDoorBlockOverlayController.BlockAccess?,
        ): Map<DoorKind, DungeonDoorBlockOverlayController.TypeStats> {
            if (world != null) ensureLevel(world)
            val result = EnumMap<DoorKind, DungeonDoorBlockOverlayController.TypeStats>(DoorKind::class.java)
            for (kind in DungeonDoorBlockOverlayControllerCore.replacementKinds()) {
                var targets = 0
                var loaded = 0
                var sourceAir = 0
                var sourceNonAir = 0
                var activeCount = 0
                for ((packed, projection) in desired) {
                    if (projection.kind() != kind) continue
                    targets++
                    val position = BlockPos.of(packed)
                    if (world == null || !world.hasChunkAt(position)) continue
                    loaded++
                    val source = authoritative[packed] ?: world.getBlockState(position)
                    if (source.isAir) sourceAir++ else sourceNonAir++
                    if (applied.containsKey(packed)) activeCount++
                }
                val count = totals[kind]!!
                result[kind] = DungeonDoorBlockOverlayController.TypeStats(
                    targets,
                    loaded,
                    sourceAir,
                    sourceNonAir,
                    activeCount,
                    count.writes,
                    count.restores,
                    count.failures,
                    count.miningReads,
                    count.miningSourceReturns,
                    count.miningFallbacks,
                    count.miningOwnershipMismatches,
                )
            }
            return java.util.Map.copyOf(result)
        }

        private fun applyProjection(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            packed: Long,
            projection: DungeonDoorBlockOverlayController.Projection,
            serverState: BlockState,
        ) {
            val kind = projection.kind() ?: return
            val replacement = projection.state() ?: return
            val position = BlockPos.of(packed)
            if (!world.hasChunkAt(position) || serverState.isAir) return
            val current = world.getBlockState(position)
            if (current == replacement) {
                applied[packed] = Applied(projection, false)
                return
            }
            try {
                val changed = world.setBlock(
                    position,
                    replacement,
                    DungeonDoorBlockOverlayControllerCore.CLIENT_UPDATE_FLAGS,
                )
                if (changed || world.getBlockState(position) == replacement) {
                    applied[packed] = Applied(projection, changed)
                    if (changed) {
                        totals[kind]!!.writes++
                        lastFailure.remove(packed)
                        val signature = "$kind:${DungeonDoorBlockOverlayControllerCore.describe(serverState)}->" +
                            DungeonDoorBlockOverlayControllerCore.describe(replacement)
                        if (signature != lastWriteLog.put(packed, signature)) {
                            log(
                                "LOCAL GLASS WRITE kind=" + kind + " pos=" + position.toShortString()
                                    + " source=" + DungeonDoorBlockOverlayControllerCore.describe(serverState)
                                    + " replacement="
                                    + DungeonDoorBlockOverlayControllerCore.describe(replacement),
                            )
                        } else suppressDetailLog()
                    }
                } else {
                    fail(
                        kind,
                        packed,
                        "write rejected; current="
                            + DungeonDoorBlockOverlayControllerCore.describe(world.getBlockState(position)),
                    )
                }
            } catch (error: RuntimeException) {
                fail(kind, packed, "write threw " + error.javaClass.simpleName)
            }
        }

        private fun removeProjection(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            packed: Long,
            expected: DungeonDoorBlockOverlayController.Projection?,
        ) {
            val current = applied.remove(packed) ?: return
            if (expected != null && current.projection != expected) return
            restoreProjection(world, packed, current)
        }

        private fun restoreProjection(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            packed: Long,
            current: Applied,
        ) {
            val projectionKind = current.projection.kind() ?: return
            val projectionState = current.projection.state() ?: return
            val position = BlockPos.of(packed)
            if (!world.hasChunkAt(position)) return
            val source = authoritative[packed] ?: return
            val observed = world.getBlockState(position)
            if (observed != projectionState) {
                if (current.owned) {
                    fail(
                        projectionKind,
                        packed,
                        "restore ownership mismatch; current="
                            + DungeonDoorBlockOverlayControllerCore.describe(observed),
                    )
                }
                return
            }
            if (!current.owned || observed == source) return
            try {
                val changed = world.setBlock(
                    position,
                    source,
                    DungeonDoorBlockOverlayControllerCore.CLIENT_UPDATE_FLAGS,
                )
                if (changed || world.getBlockState(position) == source) {
                    if (changed) {
                        totals[projectionKind]!!.restores++
                        lastFailure.remove(packed)
                        lastWriteLog.remove(packed)
                        log(
                            "LOCAL GLASS RESTORE kind=" + projectionKind + " pos="
                                + position.toShortString() + " source="
                                + DungeonDoorBlockOverlayControllerCore.describe(source),
                        )
                    }
                } else {
                    fail(
                        projectionKind,
                        packed,
                        "restore rejected; current="
                            + DungeonDoorBlockOverlayControllerCore.describe(world.getBlockState(position)),
                    )
                }
            } catch (error: RuntimeException) {
                fail(projectionKind, packed, "restore threw " + error.javaClass.simpleName)
            }
        }

        private fun clearChunkRuntime(
            world: DungeonDoorBlockOverlayController.BlockAccess,
            chunkX: Int,
            chunkZ: Int,
            installed: Boolean,
        ) {
            var changed = false
            for (packed in ArrayList(authoritative.keys)) {
                if (DungeonDoorBlockOverlayControllerCore.inChunk(
                        BlockPos.of(packed),
                        chunkX,
                        chunkZ,
                    )
                ) {
                    authoritative.remove(packed)
                    changed = true
                }
            }
            for (packed in ArrayList(applied.keys)) {
                if (DungeonDoorBlockOverlayControllerCore.inChunk(
                        BlockPos.of(packed),
                        chunkX,
                        chunkZ,
                    )
                ) {
                    applied.remove(packed)
                    changed = true
                }
            }
            for (kind in DungeonDoorBlockOverlayControllerCore.replacementKinds()) {
                val mask = masks[kind]!!
                if (!installed) {
                    for (packed in ArrayList(mask)) {
                        if (DungeonDoorBlockOverlayControllerCore.inChunk(
                                BlockPos.of(packed),
                                chunkX,
                                chunkZ,
                            )
                        ) {
                            mask.remove(packed)
                            changed = true
                        }
                    }
                }
                if (!installed) {
                    for (packed in ArrayList(desired.keys)) {
                        val projection = desired[packed]!!
                        if (projection.kind() == kind && DungeonDoorBlockOverlayControllerCore.inChunk(
                                BlockPos.of(packed),
                                chunkX,
                                chunkZ,
                            )
                        ) {
                            desired.remove(packed)
                            changed = true
                        }
                    }
                } else if (installed) {
                    for (packed in ArrayList(desired.keys)) {
                        val projection = desired[packed]!!
                        if (projection.kind() == kind && DungeonDoorBlockOverlayControllerCore.inChunk(
                                BlockPos.of(packed),
                                chunkX,
                                chunkZ,
                            )
                            && !hasSelection(projection, packed)
                        ) {
                            desired.remove(packed)
                            changed = true
                        }
                    }
                }
            }
            for (packed in ArrayList(lastFailure.keys)) {
                if (DungeonDoorBlockOverlayControllerCore.inChunk(
                        BlockPos.of(packed),
                        chunkX,
                        chunkZ,
                    )
                ) {
                    lastFailure.remove(packed)
                    changed = true
                }
            }
            for (packed in ArrayList(lastWriteLog.keys)) {
                if (DungeonDoorBlockOverlayControllerCore.inChunk(
                        BlockPos.of(packed),
                        chunkX,
                        chunkZ,
                    )
                ) lastWriteLog.remove(packed)
            }
            for (packed in ArrayList(lastMiningLog.keys)) {
                if (DungeonDoorBlockOverlayControllerCore.inChunk(
                        BlockPos.of(packed),
                        chunkX,
                        chunkZ,
                    )
                ) lastMiningLog.remove(packed)
            }
            val hasStartTarget = installed && desired.entries.any { (packed, projection) ->
                projection.kind() == DoorKind.START
                    && DungeonDoorBlockOverlayControllerCore.inChunk(BlockPos.of(packed), chunkX, chunkZ)
            }
            val hasRetainedTarget = installed && masks.values.any { mask ->
                mask.any { packed ->
                    DungeonDoorBlockOverlayControllerCore.inChunk(BlockPos.of(packed), chunkX, chunkZ)
                }
            }
            if (installed && (changed || hasStartTarget || hasRetainedTarget)) {
                log(
                    "CHUNK INSTALLED xz=" + chunkX + "," + chunkZ
                        + "; retained door masks preserved; loaded sources re-seeded",
                )
            } else if (!installed && changed) {
                log("CHUNK UNLOADED xz=" + chunkX + "," + chunkZ + "; caches and door masks cleared")
            }
        }

        private fun ensureLevel(world: DungeonDoorBlockOverlayController.BlockAccess) {
            updateGameTick(world.gameTick())
            if (levelIdentity === world.levelIdentity()) return
            if (levelIdentity != null) logLostOwnership("client level changed without a restore callback")
            clearWithoutWorld()
            levelIdentity = world.levelIdentity()
        }

        private fun isRetained(packed: Long): Boolean = masks.values.any { it.contains(packed) }

        private fun hasSelection(
            projection: DungeonDoorBlockOverlayController.Projection,
            packed: Long,
        ): Boolean = masks[projection.kind()]?.contains(packed) == true

        private fun retainedKind(packed: Long): DoorKind? {
            for ((kind, positions) in masks) {
                if (positions.contains(packed)) return kind
            }
            return null
        }

        private fun retireDoorSource(kind: DoorKind, packed: Long, reason: String) {
            if (!DungeonDoorBlockOverlayControllerCore.replacementKinds().contains(kind)) return
            retiredDoorSources.putIfAbsent(packed, kind)
            masks[kind]?.remove(packed)
            val projection = desired[packed]
            if (projection != null && projection.kind() == kind) desired.remove(packed)
            log(
                (if (kind == DoorKind.START) "START" else "DOOR")
                    + " SOURCE RETIRED kind=" + kind + " pos=" + BlockPos.of(packed).toShortString()
                    + " reason=" + reason
                    + "; future non-air updates stay unprojected until world/dungeon reset",
            )
        }

        private fun logLostOwnership(reason: String) {
            val lost = EnumMap<DoorKind, Int>(DoorKind::class.java)
            for (value in applied.values) {
                if (!value.owned) continue
                val kind = value.projection.kind()
                lost[kind] = (lost[kind] ?: 0) + 1
                val count = totals[kind]
                if (count != null) count.failures++
            }
            if (lost.isNotEmpty()) log("OWNERSHIP MISMATCH " + reason + "; dropped projections=" + lost)
        }

        private fun fail(kind: DoorKind, packed: Long, reason: String) {
            val count = totals[kind]
            if (count != null) count.failures++
            val signature = kind.toString() + ":" + reason
            if (signature != lastFailure.put(packed, signature)) {
                log(
                    "LOCAL GLASS FAILURE kind=" + kind + " pos=" + BlockPos.of(packed).toShortString()
                        + " reason=" + reason,
                )
            } else suppressDetailLog()
        }

        private fun log(message: String) {
            if (logBudgetTick != currentGameTick) updateGameTick(currentGameTick)
            if (detailLogsThisTick >= DungeonDoorBlockOverlayControllerCore.MAX_DETAIL_LOGS_PER_TICK) {
                suppressDetailLog()
                return
            }
            detailLogsThisTick++
            GeilerAddonsLog.write(
                Category.F7,
                DungeonDoorBlockOverlayControllerCore.MODULE,
                currentGameTick,
                message,
            )
        }

        private fun suppressDetailLog() {
            suppressedDetailLogs++
            totalSuppressedDetailLogs++
        }

        private fun updateGameTick(tick: Long) {
            if (logBudgetTick != Long.MIN_VALUE && tick != logBudgetTick) {
                if (suppressedDetailLogs > 0) {
                    GeilerAddonsLog.write(
                        Category.F7,
                        DungeonDoorBlockOverlayControllerCore.MODULE,
                        tick,
                        "DETAIL LOGS SUPPRESSED count=" + suppressedDetailLogs
                            + " previousTick=" + logBudgetTick,
                    )
                }
                suppressedDetailLogs = 0
                detailLogsThisTick = 0
            }
            logBudgetTick = tick
            currentGameTick = tick
        }

        private data class Applied(
            val projection: DungeonDoorBlockOverlayController.Projection,
            val owned: Boolean,
        )

        private class MutableStats {
            var writes = 0L
            var restores = 0L
            var failures = 0L
            var miningReads = 0L
            var miningSourceReturns = 0L
            var miningFallbacks = 0L
            var miningOwnershipMismatches = 0L
        }
    }

    private fun inChunk(position: BlockPos, chunkX: Int, chunkZ: Int): Boolean =
        (position.x shr 4) == chunkX && (position.z shr 4) == chunkZ

    private fun replacementKinds(): List<DoorKind> =
        listOf(DoorKind.START, DoorKind.WITHER, DoorKind.BLOOD)

    private fun matchesDoorSource(kind: DoorKind, state: BlockState?): Boolean {
        if (state == null) return false
        return when (kind) {
            DoorKind.WITHER -> state.block == Blocks.COAL_BLOCK
            DoorKind.BLOOD -> state.block == Blocks.DYED_TERRACOTTA.pick(DyeColor.RED)
            else -> false
        }
    }

    private fun describe(state: BlockState?): String = state?.block?.toString() ?: "<null>"

    private fun isStartSelectionSource(state: BlockState?): Boolean =
        state != null && (state.block == Blocks.CHISELED_STONE_BRICKS
            || state.block == Blocks.INFESTED_CHISELED_STONE_BRICKS)

    private const val CLIENT_UPDATE_FLAGS = 18
}
