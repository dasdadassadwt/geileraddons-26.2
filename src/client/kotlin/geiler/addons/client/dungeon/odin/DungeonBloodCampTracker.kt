package geiler.addons.client.dungeon.odin

import java.util.ArrayDeque
import java.util.ArrayList
import java.util.HashMap
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord

/** Client-thread packet tracker for Odin-style Watcher and Blood mob movement observations. */
class DungeonBloodCampTracker {
    private val eligible = HashMap<Int, Eligibility>()
    private val tracks = HashMap<Int, CampTrack>()
    private var watcherEntityId = -1
    private var watcherPosition: Position? = null
    private var nextSerial = 0L
    private var firstWave = true

    fun watcherEntityId(): Int = watcherEntityId
    fun watcherPosition(): Position? = watcherPosition
    fun hasWatcher(): Boolean = watcherEntityId >= 0 && watcherPosition != null
    fun firstWave(): Boolean = firstWave
    fun eligibleCount(): Int = eligible.size
    fun trackedCount(): Int = tracks.size

    /** Stores a Watcher already validated by the caller against its Zombie head texture. */
    fun observeWatcher(entityId: Int, position: Position?) {
        if (position == null) return
        if (watcherEntityId != entityId) clearEntities()
        watcherEntityId = entityId
        watcherPosition = position
    }

    /** The Watcher move line changes the projection for entities first observed after this event. */
    fun observeWatcherMoved() {
        firstWave = false
    }

    /**
     * Validates an ArmorStand head update against the caller's exact skin-texture match and the
     * currently tracked Watcher. It intentionally has no map-room or height restriction.
     */
    fun observeCandidateHead(
        entityId: Int,
        armorStand: Boolean,
        headSlot: Boolean,
        playerHead: Boolean,
        mobTextureMatch: Boolean,
        position: Position?,
    ): CandidateCheck {
        if (!armorStand) return rejectAndRemove(entityId, "not-armor-stand")
        if (!headSlot) return rejectAndRemove(entityId, "not-head-slot")
        if (!playerHead) return rejectAndRemove(entityId, "not-player-head")
        if (!mobTextureMatch) return rejectAndRemove(entityId, "unrecognized-mob-texture")
        if (position == null) return rejectAndRemove(entityId, "position-unavailable")
        val watcher = watcherPosition
        if (watcherEntityId < 0 || watcher == null) return rejectAndRemove(entityId, "watcher-not-found")

        val distanceSquared = watcher.distanceSquared(position)
        if (distanceSquared > WATCHER_RANGE_SQUARED) {
            return rejectAndRemove(entityId, "outside-watcher-range", distanceSquared)
        }

        if (!eligible.containsKey(entityId)) {
            makeRoom()
            eligible[entityId] = Eligibility(position)
        }
        return CandidateCheck(true, "accepted", Math.sqrt(distanceSquared))
    }

    /**
     * Called after vanilla applies a movement packet. The first nonzero movement sample becomes
     * Odin's prediction origin; later samples update its rolling 16-delta direction estimate.
     */
    fun observeEntityMove(entityId: Int, position: Position?, nowNanos: Long, spawnTicks: Int): MovementCheck {
        val marker = eligible[entityId]
            ?: return MovementCheck(null, "candidate-not-eligible", Double.NaN)
        val watcher = watcherPosition
        if (watcherEntityId < 0 || watcher == null) {
            removeCandidate(entityId)
            return MovementCheck(null, "watcher-not-found", Double.NaN)
        }
        if (position == null) return MovementCheck(null, "position-unavailable", Double.NaN)

        val distanceSquared = watcher.distanceSquared(position)
        if (distanceSquared > WATCHER_RANGE_SQUARED) {
            removeCandidate(entityId)
            return MovementCheck(null, "outside-watcher-range", Math.sqrt(distanceSquared))
        }

        var track = tracks[entityId]
        if (track == null) {
            val delta = position.minus(marker.lastObservedPosition)
            marker.lastObservedPosition = position
            if (delta.lengthSquared() <= MOVEMENT_EPSILON_SQUARED) {
                return MovementCheck(null, "zero-movement", Math.sqrt(distanceSquared))
            }
            makeRoom()
            track = CampTrack(++nextSerial, position, nowNanos, firstWave)
            tracks[entityId] = track
            return MovementCheck(
                reading(entityId, track, nowNanos, spawnTicks, Math.sqrt(distanceSquared)),
                "track-started",
                Math.sqrt(distanceSquared),
            )
        }

        val delta = position.minus(track.last)
        track.last = position
        marker.lastObservedPosition = position
        if (delta.lengthSquared() > MOVEMENT_EPSILON_SQUARED) {
            track.deltas.addLast(delta)
            while (track.deltas.size > MAX_DELTA_SAMPLES) track.deltas.removeFirst()
        }
        var next = reading(entityId, track, nowNanos, spawnTicks, Math.sqrt(distanceSquared))
        if (next.destination != track.destination) {
            track.previousDestination = track.destination
            track.destination = next.destination!!
            track.destinationUpdatedNanos = nowNanos
            next = reading(entityId, track, nowNanos, spawnTicks, Math.sqrt(distanceSquared))
        }
        return MovementCheck(
            next,
            if (delta.lengthSquared() > MOVEMENT_EPSILON_SQUARED) "movement" else "zero-movement",
            Math.sqrt(distanceSquared),
        )
    }

    /** Immutable, entity-id ordered render snapshot. Candidates remain until entity removal or a reset. */
    fun candidateSnapshot(nowNanos: Long, spawnTicks: Int): List<CandidateReading> {
        val result = ArrayList<CandidateReading>(tracks.size)
        val watcher = watcherPosition
        for ((entityId, track) in tracks) {
            result.add(
                reading(
                    entityId,
                    track,
                    nowNanos,
                    spawnTicks,
                    if (watcherEntityId >= 0 && watcher != null)
                        Math.sqrt(watcher.distanceSquared(track.last)) else Double.NaN,
                ),
            )
        }
        result.sortWith(compareBy { it.entityId })
        return java.util.List.copyOf(result)
    }

    fun tracksCandidate(entityId: Int): Boolean = eligible.containsKey(entityId)

    /** Forget both the Watcher and stand data when its entity id is removed or reused. */
    fun removeEntity(entityId: Int) {
        if (entityId == watcherEntityId) {
            clearEntities()
            return
        }
        removeCandidate(entityId)
    }

    fun removeCandidate(entityId: Int) {
        eligible.remove(entityId)
        tracks.remove(entityId)
    }

    /** Clears entity observations while preserving the move-line wave transition. */
    fun clearEntities() {
        eligible.clear()
        tracks.clear()
        watcherEntityId = -1
        watcherPosition = null
    }

    fun reset() {
        clearEntities()
        firstWave = true
    }

    private fun rejectAndRemove(entityId: Int, reason: String): CandidateCheck {
        removeCandidate(entityId)
        return CandidateCheck(false, reason, Double.NaN)
    }

    private fun rejectAndRemove(entityId: Int, reason: String, distanceSquared: Double): CandidateCheck {
        removeCandidate(entityId)
        return CandidateCheck(false, reason, Math.sqrt(distanceSquared))
    }

    private fun makeRoom() {
        if (eligible.size < MAX_ENTITIES && tracks.size < MAX_ENTITIES) return
        var oldestId: Int? = null
        var oldestStartedNanos = Long.MAX_VALUE
        for ((entityId, track) in tracks) {
            if (oldestId == null || track.startedNanos < oldestStartedNanos) {
                oldestId = entityId
                oldestStartedNanos = track.startedNanos
            }
        }
        val id = oldestId
        if (id != null) {
            tracks.remove(id)
            eligible.remove(id)
        } else {
            val first = eligible.keys.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            }
        }
    }

    private fun reading(
        entityId: Int,
        track: CampTrack,
        nowNanos: Long,
        spawnTicks: Int,
        watcherDistance: Double,
    ): CandidateReading {
        var total = Position.ZERO
        for (step in track.deltas) total = total.plus(step)
        val magnitude = Math.sqrt(total.lengthSquared())
        val hasDirection = magnitude > 0.05
        val direction = if (hasDirection) total.scale(1.0 / magnitude) else Position.ZERO
        val destination = track.start.plus(direction.scale(if (track.firstWave) 16.1 else 11.9))
        val elapsedNanos = Math.max(0L, nowNanos - track.startedNanos)
        val elapsedMillis = elapsedNanos / 1_000_000L
        val timeLeftMillis = (if (track.firstWave) 2_000L else 0L) + clampTicks(spawnTicks) * 50L - elapsedMillis
        val elapsedTicks = Math.min(Int.MAX_VALUE.toLong(), elapsedNanos / 50_000_000L).toInt()
        val maxTicks = clampTicks(spawnTicks)
        val remainingTicks = Math.max(0L, (timeLeftMillis + 49L) / 50L).toInt()
        val velocityMillis = Math.max(1L, elapsedMillis)
        val velocityPerMs = track.last.minus(track.start).scale(1.0 / velocityMillis)
        return CandidateReading(
            entityId,
            track.serial,
            track.start,
            track.last,
            destination,
            track.previousDestination,
            track.destinationUpdatedNanos,
            velocityPerMs,
            elapsedTicks,
            remainingTicks,
            maxTicks,
            timerColor(timeLeftMillis),
            hasDirection,
            track.firstWave,
            track.startedNanos,
            timeLeftMillis,
            watcherDistance,
        )
    }

    private fun clampTicks(ticks: Int): Int = Math.max(35, Math.min(41, ticks))

    private fun timerColor(milliseconds: Long): Int {
        val seconds = milliseconds / 1000.0
        if (seconds > 1.5) return 0xFF55FF55.toInt()
        if (seconds >= 0.5) return 0xFFFFAA00.toInt()
        if (seconds >= 0.0) return 0xFFFF5555.toInt()
        return 0xFF55FFFF.toInt()
    }

    private class Eligibility(var lastObservedPosition: Position)

    private class CampTrack(
        val serial: Long,
        val start: Position,
        val startedNanos: Long,
        val firstWave: Boolean,
    ) {
        val deltas = ArrayDeque<Position>()
        var last: Position = start
        var destination: Position = start
        var previousDestination: Position = start
        var destinationUpdatedNanos: Long = startedNanos
    }

    @JvmRecord
    data class Position(val x: Double, val y: Double, val z: Double) {
        init {
            if (!java.lang.Double.isFinite(x) || !java.lang.Double.isFinite(y) || !java.lang.Double.isFinite(z)) {
                throw IllegalArgumentException("Blood Camp positions must be finite")
            }
        }

        fun plus(other: Position?): Position {
            val right = other ?: throw NullPointerException()
            return Position(x + right.x, y + right.y, z + right.z)
        }

        fun minus(other: Position?): Position {
            val right = other ?: throw NullPointerException()
            return Position(x - right.x, y - right.y, z - right.z)
        }

        fun scale(multiplier: Double): Position = Position(x * multiplier, y * multiplier, z * multiplier)

        fun lengthSquared(): Double = x * x + y * y + z * z

        fun distanceSquared(other: Position?): Double = minus(other).lengthSquared()

        override fun toString(): String =
            "Position[x=" + x + ", y=" + y + ", z=" + z + "]"

        companion object {
            @JvmField
            val ZERO = Position(0.0, 0.0, 0.0)
        }
    }

    @JvmRecord
    data class CandidateCheck(val accepted: Boolean, val reason: String?, val watcherDistance: Double) {
        override fun toString(): String =
            "CandidateCheck[accepted=" + accepted + ", reason=" + reason + ", watcherDistance=" + watcherDistance + "]"
    }

    @JvmRecord
    data class MovementCheck(val reading: CandidateReading?, val reason: String?, val watcherDistance: Double) {
        override fun toString(): String =
            "MovementCheck[reading=" + reading + ", reason=" + reason + ", watcherDistance=" + watcherDistance + "]"
    }

    @JvmRecord
    data class Prediction(
        val id: Any?,
        val current: Position?,
        val endpoint: Position?,
        val etaNanos: Long,
        val hasDirection: Boolean,
    ) {
        override fun toString(): String =
            "Prediction[id=" + id + ", current=" + current + ", endpoint=" + endpoint +
                ", etaNanos=" + etaNanos + ", hasDirection=" + hasDirection + "]"
    }

    @JvmRecord
    data class CandidateReading(
        val entityId: Int,
        val serial: Long,
        val spawn: Position?,
        val current: Position?,
        val destination: Position?,
        val previousDestination: Position?,
        val destinationUpdatedNanos: Long,
        val velocityPerMs: Position?,
        val elapsedTicks: Int,
        val remainingTicks: Int,
        val maxTravelTicks: Int,
        val timerColorArgb: Int,
        val hasDirection: Boolean,
        val firstWave: Boolean,
        val startedNanos: Long,
        val timeLeftMillis: Long,
        val watcherDistance: Double,
    ) {
        override fun toString(): String =
            "CandidateReading[entityId=" + entityId + ", serial=" + serial + ", spawn=" + spawn +
                ", current=" + current + ", destination=" + destination +
                ", previousDestination=" + previousDestination + ", destinationUpdatedNanos=" +
                destinationUpdatedNanos + ", velocityPerMs=" + velocityPerMs + ", elapsedTicks=" +
                elapsedTicks + ", remainingTicks=" + remainingTicks + ", maxTravelTicks=" + maxTravelTicks +
                ", timerColorArgb=" + timerColorArgb + ", hasDirection=" + hasDirection +
                ", firstWave=" + firstWave + ", startedNanos=" + startedNanos +
                ", timeLeftMillis=" + timeLeftMillis + ", watcherDistance=" + watcherDistance + "]"
    }

    private companion object {
        const val MAX_ENTITIES = 512
        const val MAX_DELTA_SAMPLES = 16
        const val WATCHER_RANGE_SQUARED = 20.0 * 20.0
        const val MOVEMENT_EPSILON_SQUARED = 1.0e-8
    }
}
