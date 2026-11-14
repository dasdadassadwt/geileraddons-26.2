package geiler.addons.client.macros.rotation

/**
 * Every setting of how the view turns, for every macro: the settings in use, built-in presets and presets saved under a
 * name. There is one engine (see [Rotator]); these settings shape how human its turns look.
 */
class RotationConfig {
    /**
     * What the view is turned for. Each has its own base speed; the two teleports are exact (no overshoot, no aim wander)
     * because they must land within a fraction of a degree.
     */
    enum class Activity(
        val label: String,
        val description: String,
        /** Typical peak speed of a turn in degrees a second, before the speed range and distance scaling. */
        val baseSpeed: Double,
        /** Lands exactly: no overshoot, no wander. */
        val precise: Boolean,
        /** Starts after a reaction delay when the view was still. */
        val reacts: Boolean,
    ) {
        GENERAL("General", "Looking along the way while walking, and the fallback whenever nothing more specific applies.", 260.0, false, false),
        ETHERWARP("Etherwarp", "Lining up etherwarp teleports.", 520.0, true, false),
        TELEPORT("Teleport", "Lining up teleport jumps along the route.", 520.0, true, false),
        COMBAT("Combat", "Aiming at mobs during combat macros.", 420.0, false, true),
    }

    /** One full set of rotation settings: what a preset holds. */
    class Settings {
        /** Off turns on a clean, smooth path with none of the effects below; on adds all of them. */
        @JvmField var humanized = true

        /** Each turn draws its peak speed multiplier inside this range. */
        @JvmField var paceMin = 0.8
        @JvmField var paceMax = 1.2

        /** Each turn lasts at least and at most this long, in milliseconds, whatever its speed and size would say. */
        @JvmField var timeMinMs = 90.0
        @JvmField var timeMaxMs = 650.0

        /** The pause before a turn starts when the view was still, drawn inside this range, in milliseconds. */
        @JvmField var reactionMinMs = 70.0
        @JvmField var reactionMaxMs = 230.0

        /** Overall strength of every humanization effect. */
        @JvmField var effectIntensity = 1.0

        /**
         * How much of the sideways effects (bend, overshoot, aim wander, speed noise) land on the pitch, before the
         * need-based scaling: with no pitch to turn they stay horizontal whatever this says.
         */
        @JvmField var verticalMovement = 1.0

        @JvmField var distanceScaling = false

        /** Distance in blocks and the speed multiplier there, as pairs; between two points it goes in a straight line. */
        @JvmField var distancePoints: MutableList<DoubleArray> = defaultDistancePoints()

        /**
         * The human effects are ranges: every turn draws its own value between the low end (the `...Min` field) and the
         * high end (the original field, which is also the only value an older saved preset has).
         *
         * How often a long turn goes past its target and comes back, and how far, in percent of the turn.
         */
        @JvmField var overshootChanceMin = 15.0
        @JvmField var overshootChance = 25.0
        @JvmField var overshootSizeMin = 3.6
        @JvmField var overshootSize = 6.0

        /** How far turns bend off the straight line. */
        @JvmField var curveStrengthMin = 0.6
        @JvmField var curveStrength = 1.0

        /** How much the speed wanders during a turn. */
        @JvmField var speedNoiseMin = 0.6
        @JvmField var speedNoise = 1.0

        /** How often a turn hesitates for a moment, per second of turning. */
        @JvmField var pausesPerSecondMin = 0.3
        @JvmField var pausesPerSecond = 0.5

        /** How far the aim drifts around the target while holding it, in degrees. */
        @JvmField var aimWanderMin = 0.05
        @JvmField var aimWander = 0.08

        /** How big a jump of the target counts as a new turn, which draws its speed and timing anew. */
        @JvmField var newTurnDegrees = 10.0

        /** The camera jumps onto the target in one frame. */
        @JvmField var instant = false

        fun copy(): Settings {
            val copy = Settings()
            copy.humanized = humanized
            copy.paceMin = paceMin
            copy.paceMax = paceMax
            copy.timeMinMs = timeMinMs
            copy.timeMaxMs = timeMaxMs
            copy.reactionMinMs = reactionMinMs
            copy.reactionMaxMs = reactionMaxMs
            copy.effectIntensity = effectIntensity
            copy.verticalMovement = verticalMovement
            copy.distanceScaling = distanceScaling
            copy.distancePoints = distancePoints.mapTo(ArrayList()) { it.clone() }
            copy.overshootChanceMin = overshootChanceMin
            copy.overshootChance = overshootChance
            copy.overshootSizeMin = overshootSizeMin
            copy.overshootSize = overshootSize
            copy.curveStrengthMin = curveStrengthMin
            copy.curveStrength = curveStrength
            copy.speedNoiseMin = speedNoiseMin
            copy.speedNoise = speedNoise
            copy.pausesPerSecondMin = pausesPerSecondMin
            copy.pausesPerSecond = pausesPerSecond
            copy.aimWanderMin = aimWanderMin
            copy.aimWander = aimWander
            copy.newTurnDegrees = newTurnDegrees
            copy.instant = instant
            return copy
        }

        /** The speed multiplier at a distance, 1 when scaling is off or the distance is not known. */
        fun distanceFactor(distance: Double): Double {
            if (!distanceScaling || distance.isNaN() || distancePoints.isEmpty()) return 1.0
            return curveFactor(distance)
        }

        /** The curve itself, whether or not scaling is on: what the distance graph draws. */
        fun curveFactor(distance: Double): Double {
            val first = distancePoints[0]
            if (distance <= first[0]) return first[1]
            for (i in 1 until distancePoints.size) {
                val previous = distancePoints[i - 1]
                val point = distancePoints[i]
                if (distance <= point[0]) {
                    val t = if (point[0] <= previous[0]) 1.0 else (distance - previous[0]) / (point[0] - previous[0])
                    return previous[1] + (point[1] - previous[1]) * t
                }
            }
            return distancePoints[distancePoints.size - 1][1]
        }

        fun resetTuning() {
            effectIntensity = 1.0
            verticalMovement = 1.0
        }

        fun resetDistance() {
            distancePoints = defaultDistancePoints()
        }

        fun normalize() {
            paceMin = clamp(paceMin, SPEED_LOW, SPEED_HIGH)
            paceMax = clamp(paceMax, paceMin, SPEED_HIGH)
            timeMinMs = clamp(timeMinMs, TIME_LOW, TIME_HIGH)
            timeMaxMs = clamp(timeMaxMs, timeMinMs, TIME_HIGH)
            reactionMinMs = clamp(reactionMinMs, 0.0, REACTION_HIGH)
            reactionMaxMs = clamp(reactionMaxMs, reactionMinMs, REACTION_HIGH)
            effectIntensity = clamp(effectIntensity, 0.0, 2.0)
            verticalMovement = clamp(verticalMovement, 0.0, 2.0)
            distancePoints.removeIf { it.size != 2 }
            for (point in distancePoints) {
                point[0] = clamp(point[0], 0.0, DISTANCE_MAX)
                point[1] = clamp(point[1], DISTANCE_FACTOR_LOW, DISTANCE_FACTOR_HIGH)
            }
            distancePoints.sortWith { a, b -> a[0].compareTo(b[0]) }
            if (distancePoints.isEmpty()) distancePoints = defaultDistancePoints()
            overshootChance = clamp(overshootChance, 0.0, 100.0)
            overshootChanceMin = clamp(overshootChanceMin, 0.0, overshootChance)
            overshootSize = clamp(overshootSize, 0.0, 20.0)
            overshootSizeMin = clamp(overshootSizeMin, 0.0, overshootSize)
            curveStrength = clamp(curveStrength, 0.0, 3.0)
            curveStrengthMin = clamp(curveStrengthMin, 0.0, curveStrength)
            speedNoise = clamp(speedNoise, 0.0, 2.0)
            speedNoiseMin = clamp(speedNoiseMin, 0.0, speedNoise)
            pausesPerSecond = clamp(pausesPerSecond, 0.0, 3.0)
            pausesPerSecondMin = clamp(pausesPerSecondMin, 0.0, pausesPerSecond)
            aimWander = clamp(aimWander, 0.0, 0.5)
            aimWanderMin = clamp(aimWanderMin, 0.0, aimWander)
            newTurnDegrees = clamp(newTurnDegrees, 0.5, 30.0)
        }
    }

    /** A set of settings saved under a name. */
    class Preset(@JvmField var name: String, @JvmField var settings: Settings)

    @JvmField var settings = Settings()
    @JvmField val presets: MutableList<Preset> = ArrayList()

    /** The name of the preset last loaded or saved; empty for none. */
    @JvmField var preset = ""

    fun normalize() {
        settings.normalize()
        presets.removeIf { it.name.isBlank() }
        presets.forEach { it.settings.normalize() }
        if (find(preset) == null) preset = ""
    }

    /** A saved preset first, then a built-in one, by name. */
    fun find(name: String): Preset? =
        presets.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: BUILT_IN.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Whether the name belongs to a built-in preset that no saved preset hides. */
    fun isBuiltIn(name: String): Boolean =
        presets.none { it.name.equals(name, ignoreCase = true) } && BUILT_IN.any { it.name.equals(name, ignoreCase = true) }

    /** The names a preset can be chosen by: the built-in ones, then the saved ones that add to them. */
    fun presetNames(): List<String> =
        BUILT_IN.map { it.name } + presets.map { it.name }.filter { saved -> BUILT_IN.none { it.name.equals(saved, ignoreCase = true) } }

    /** Saves the settings in use under the name, over a preset of that name if there is one. */
    fun saveAs(name: String?) {
        var wanted = name?.trim() ?: ""
        if (wanted.isEmpty()) {
            var number = 1
            while (find("Preset $number") != null) number++
            wanted = "Preset $number"
        }
        val existing = presets.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        if (existing != null) {
            existing.settings = settings.copy()
            preset = existing.name
        } else {
            presets.add(Preset(wanted, settings.copy()))
            preset = wanted
        }
    }

    /** Deletes the chosen saved preset; a built-in one cannot be deleted. */
    fun deletePreset(): Boolean {
        val chosen = presets.firstOrNull { it.name.equals(preset, ignoreCase = true) } ?: return false
        presets.remove(chosen)
        preset = ""
        return true
    }

    companion object {
        const val SPEED_LOW = 0.25
        const val SPEED_HIGH = 3.0
        const val TIME_LOW = 30.0
        const val TIME_MIN_HIGH = 600.0
        const val TIME_HIGH = 3000.0
        const val REACTION_HIGH = 800.0
        const val DISTANCE_MAX = 30.0
        const val DISTANCE_FACTOR_LOW = 0.5
        const val DISTANCE_FACTOR_HIGH = 1.5

        /** The global settings, set once the config is loaded, for the rotations that have no config of their own to hand. */
        private var current = RotationConfig()

        @JvmStatic
        fun current(): RotationConfig = current

        @JvmStatic
        fun use(config: RotationConfig) {
            current = config
        }

        @JvmStatic
        fun defaultDistancePoints(): MutableList<DoubleArray> {
            val points = ArrayList<DoubleArray>()
            for (distance in doubleArrayOf(0.0, 2.0, 5.0, 8.0, 12.0, 20.0)) points.add(doubleArrayOf(distance, 1.0))
            return points
        }

        /** Presets that ship with the mod. The first is what a fresh install uses. */
        @JvmField
        val BUILT_IN: List<Preset> = listOf(
            Preset("Human", Settings()),
            Preset("Relaxed", Settings().also {
                it.paceMin = 0.6; it.paceMax = 0.9; it.timeMinMs = 160.0; it.timeMaxMs = 900.0
                it.reactionMinMs = 120.0; it.reactionMaxMs = 320.0; it.overshootChance = 15.0; it.overshootSize = 4.0
                it.pausesPerSecond = 0.8; it.aimWander = 0.1
                it.overshootChanceMin = 9.0; it.overshootSizeMin = 2.4; it.pausesPerSecondMin = 0.5; it.aimWanderMin = 0.06
            }),
            Preset("Fast", Settings().also {
                it.paceMin = 1.3; it.paceMax = 1.8; it.timeMinMs = 60.0; it.timeMaxMs = 380.0
                it.reactionMinMs = 40.0; it.reactionMaxMs = 110.0; it.overshootChance = 20.0; it.overshootSize = 7.0
                it.pausesPerSecond = 0.2; it.speedNoise = 0.8
                it.overshootChanceMin = 12.0; it.overshootSizeMin = 4.2; it.pausesPerSecondMin = 0.1; it.speedNoiseMin = 0.5
            }),
            Preset("Precise", Settings().also {
                it.paceMin = 1.0; it.paceMax = 1.4; it.timeMinMs = 80.0; it.timeMaxMs = 450.0
                it.reactionMinMs = 50.0; it.reactionMaxMs = 140.0; it.overshootChance = 5.0; it.overshootSize = 3.0
                it.pausesPerSecond = 0.1; it.aimWander = 0.03; it.curveStrength = 0.6
                it.overshootChanceMin = 3.0; it.overshootSizeMin = 1.8; it.pausesPerSecondMin = 0.05; it.aimWanderMin = 0.02
                it.curveStrengthMin = 0.35
            }),
        )

        @JvmStatic
        fun clamp(value: Double, low: Double, high: Double): Double = if (value.isNaN()) low else maxOf(low, minOf(high, value))
    }
}
