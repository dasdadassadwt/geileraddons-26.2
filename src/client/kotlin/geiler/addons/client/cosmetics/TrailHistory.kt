package geiler.addons.client.cosmetics

import kotlin.math.sqrt

/**
 * World-space history for the trail cosmetics: where a player walked, as a bounded ring of spawn events per
 * player. Nothing spawns while the player stands still (events are paid for in distance walked, not in time),
 * every event expires after its lifetime, and players that are no longer rendered are forgotten.
 * Render thread only. Positions are world coordinates; [TrailRig] turns them into the player's model space.
 */
class TrailHistory {
    /** One player's events, oldest first. Parallel arrays in a ring so nothing is allocated per event. */
    class Track {
        @JvmField var style: CosmeticStyle? = null
        @JvmField val x = DoubleArray(CAPACITY)
        @JvmField val y = DoubleArray(CAPACITY)
        @JvmField val z = DoubleArray(CAPACITY)
        @JvmField val birth = DoubleArray(CAPACITY)
        @JvmField val side = FloatArray(CAPACITY)
        @JvmField val r1 = FloatArray(CAPACITY)
        @JvmField val r2 = FloatArray(CAPACITY)
        @JvmField val r3 = FloatArray(CAPACITY)
        @JvmField val serial = IntArray(CAPACITY)
        private var start = 0
        var size = 0
            private set

        private var lastX = 0.0
        private var lastY = 0.0
        private var lastZ = 0.0
        private var lastSeconds = 0.0
        private var hasLast = false
        private var accumulated = 0f
        private var spawned = 0
        internal var seen = 0.0

        /** Physical array index of the [i]th oldest event. */
        fun at(i: Int): Int = (start + i) % CAPACITY

        fun clear() {
            start = 0
            size = 0
            accumulated = 0f
        }

        internal fun add(px: Double, py: Double, pz: Double, time: Double, sideSign: Float, seed: Int) {
            val index: Int
            if (size == CAPACITY) {
                index = start
                start = (start + 1) % CAPACITY
            } else {
                index = (start + size) % CAPACITY
                size++
            }
            x[index] = px; y[index] = py; z[index] = pz
            birth[index] = time
            side[index] = sideSign
            serial[index] = spawned
            r1[index] = rand(seed, spawned, 1)
            r2[index] = rand(seed, spawned, 2)
            r3[index] = rand(seed, spawned, 3)
            spawned++
        }

        internal fun move(
            style: CosmeticStyle, seconds: Double, px: Double, py: Double, pz: Double, onGround: Boolean, seed: Int, backBlocks: Float,
        ) {
            val spec = spec(style)
            val jumped = hasLast && (seconds < lastSeconds - 1.0 || seconds - lastSeconds > 2.0 || distance(px - lastX, py - lastY, pz - lastZ) > TELEPORT)
            val first = !hasLast
            if (first || jumped) {
                clear()
                hasLast = true
            } else if (seconds > lastSeconds) {
                val dx = px - lastX
                val dz = pz - lastZ
                val dy = py - lastY
                val length = (if (spec.groundOnly) sqrt(dx * dx + dz * dz) else distance(dx, dy, dz)).toFloat()
                if (length > MIN_MOVE && (onGround || !spec.groundOnly)) {
                    val horizontal = sqrt(dx * dx + dz * dz)
                    val hx = if (horizontal > 1e-6) dx / horizontal else 0.0
                    val hz = if (horizontal > 1e-6) dz / horizontal else 0.0
                    var at = spec.spacing - accumulated
                    var made = 0
                    while (at <= length && made < MAX_PER_UPDATE) {
                        val f = (at / length).toDouble()
                        val px2 = lastX + dx * f - hx * backBlocks
                        val pz2 = lastZ + dz * f - hz * backBlocks
                        var sx = 0.0
                        var sz = 0.0
                        var sideSign = 0f
                        if (spec.alternates) {
                            sideSign = if (spawned % 2 == 0) 1f else -1f
                            sx = -hz * spec.sideOffset * sideSign
                            sz = hx * spec.sideOffset * sideSign
                        }
                        add(px2 + sx, lastY + dy * f, pz2 + sz, lastSeconds + (seconds - lastSeconds) * f, sideSign, seed)
                        at += spec.spacing
                        made++
                    }
                    accumulated = if (made == MAX_PER_UPDATE) 0f else spec.spacing - (at - length)
                }
            }
            if (first || jumped || seconds >= lastSeconds) {
                lastX = px; lastY = py; lastZ = pz; lastSeconds = seconds
            }
            // Drop what has outlived every possible lifetime (events are ordered by birth).
            val limit = seconds - spec.maxLife
            while (size > 0 && birth[start] < limit) {
                start = (start + 1) % CAPACITY
                size--
            }
        }
    }

    private val tracks = HashMap<Int, Track>()
    private var lastPrune = 0.0

    /**
     * Records this frame's position of player [id] and returns their track. Call once per rendered frame with a
     * smooth clock (`ageInTicks / 20`).
     * @param backBlocks how far behind the walk line new events appear (the Depth setting)
     */
    fun update(
        id: Int, style: CosmeticStyle, seconds: Double, x: Double, y: Double, z: Double, onGround: Boolean, seed: Int, backBlocks: Float,
    ): Track {
        val track = tracks.getOrPut(id) { Track() }
        if (track.style != style) {
            track.clear()
            track.style = style
        }
        track.seen = seconds
        track.move(style, seconds, x, y, z, onGround, seed, backBlocks)
        if (seconds - lastPrune > PRUNE_INTERVAL || seconds < lastPrune) prune(seconds)
        return track
    }

    /** Marks a player as still rendered without recording (swimming, gliding): their events just keep aging. */
    fun touch(id: Int, seconds: Double) {
        tracks[id]?.seen = seconds
    }

    fun players(): Int = tracks.size

    fun events(): Int = tracks.values.sumOf { it.size }

    private fun prune(now: Double) {
        lastPrune = now
        tracks.values.removeIf { now - it.seen > FORGET_SECONDS || it.seen - now > FORGET_SECONDS }
        // A crowd of players cannot grow the store without bound: keep the most recently seen ones.
        while (tracks.size > MAX_PLAYERS) {
            val oldest = tracks.entries.minByOrNull { it.value.seen } ?: break
            tracks.remove(oldest.key)
        }
    }

    /** How a style spawns its events. */
    @JvmRecord
    data class Spec(
        /** Walked distance (blocks) between two events. */
        val spacing: Float,
        /** Longest lifetime of an event, seconds (before the style's own variation). */
        val maxLife: Float,
        /** Only spawns on the ground (footsteps). */
        val groundOnly: Boolean,
        /** Alternates left and right of the walk line (feet). */
        val alternates: Boolean,
        val sideOffset: Float,
    )

    companion object {
        /** Events kept per player; the oldest are overwritten first. */
        const val CAPACITY = 64
        const val MAX_PLAYERS = 24
        const val FORGET_SECONDS = 4.0
        private const val PRUNE_INTERVAL = 2.0
        private const val MIN_MOVE = 1e-4f
        private const val MAX_PER_UPDATE = 4
        private const val TELEPORT = 10.0

        private val FLAME = Spec(0.62f, 1.7f, true, true, 0.13f)
        private val FLOWER = Spec(0.78f, 2.5f, true, true, 0.14f)
        private val SPARKLE = Spec(0.15f, 1.4f, false, false, 0f)
        private val BUBBLE = Spec(0.26f, 2.2f, false, false, 0f)
        private val ENDER = Spec(0.19f, 1.9f, false, false, 0f)
        private val RIBBON = Spec(0.2f, 1.75f, false, false, 0f)

        @JvmStatic
        fun spec(style: CosmeticStyle): Spec = when (style) {
            CosmeticStyle.FLAME_FOOTPRINTS -> FLAME
            CosmeticStyle.FLOWER_STEPS -> FLOWER
            CosmeticStyle.SPARKLE_TRAIL -> SPARKLE
            CosmeticStyle.BUBBLE_TRAIL -> BUBBLE
            CosmeticStyle.ENDER_SPARKS -> ENDER
            else -> RIBBON
        }

        private fun distance(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)

        /** Deterministic value in [0, 1) from a seed, an event number and a lane. */
        @JvmStatic
        fun rand(seed: Int, n: Int, lane: Int): Float {
            var h = seed * -0x61c88647 + n * 0x27d4eb2d + lane * 0x165667b1
            h = h xor (h ushr 15)
            h *= -0x7a143595
            h = h xor (h ushr 13)
            h *= -0x3d4d51cb
            h = h xor (h ushr 16)
            return (h and 0xFFFFFF) / 16777216f
        }
    }
}
