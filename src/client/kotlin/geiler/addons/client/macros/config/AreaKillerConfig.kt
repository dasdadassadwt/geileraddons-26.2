package geiler.addons.client.macros.config

import geiler.addons.client.macros.area.GateBounds
import geiler.addons.client.macros.killer.MobIds
import geiler.addons.client.macros.killer.TargetPattern
import geiler.addons.client.macros.pathing.PathingSettings
import java.util.concurrent.ThreadLocalRandom

/**
 * Everything the macros are configured with, as one object the pathfinder, the walker and the killer read.
 *
 * It is also the configuration of the planner: it implements [PathingSettings] directly, so the pathfinder never learns
 * what is driving it. The defaults are the gate this macro was written for. The Click GUI modules are where the values are
 * edited; they copy them in here every tick (and the kill list, the gate and the statistics are saved by the Macros
 * workspace file), so the ranges below are the source's own and are applied by [normalize].
 */
class AreaKillerConfig : PathingSettings {
    /** The two block positions at either end of the gate, both included. */
    class Gate {
        @JvmField var from = IntArray(0)
        @JvmField var to = IntArray(0)
    }

    /** The painted area the macro uses, by name; empty for none. See PaintedArea. */
    @JvmField var paintArea = ""

    @JvmField var gate = Gate()

    /** How many blocks around the gate are shut as well, so the macro never brushes past it. */
    @JvmField var margin = 1
    @JvmField var bounds: GateBounds? = null
    @JvmField var mobs: MutableList<String> = ArrayList()

    /** Hotbar slots, 1 to 9. */
    @JvmField var weaponSlot = 1
    @JvmField var teleportSlot = 2
    @JvmField var blackHoleSlot = 3
    @JvmField var scanRadius = 48

    /** Clicks per second: every click draws its own rate between the lower and the upper bound. */
    @JvmField var leftCpsMin = 8.0
    @JvmField var leftCpsMax = 12.0
    @JvmField var rightCpsMin = 6.0
    @JvmField var rightCpsMax = 10.0

    /**
     * 0 to 100: how far the clicking strays from an even draw between the bounds - a rhythm that wanders through the
     * range, and an occasional late click.
     */
    @JvmField var cpsRandomnessMin = 36.0
    @JvmField var cpsRandomness = 60.0

    /**
     * Where on a mob the crosshair goes. Any number of these can be ticked; each time the aim is re-chosen one of the
     * ticked zones is drawn, and a spot inside it. All are shares of the hitbox height.
     */
    @JvmField var aimHead = false
    @JvmField var aimShoulders = true
    @JvmField var aimUpper = false
    @JvmField var aimCenter = true
    @JvmField var aimLower = false

    /** 0 to 100: how much of a zone is used. At 0 the crosshair goes to the middle of it every time. */
    @JvmField var aimSpreadMin = 36.0
    @JvmField var aimSpread = 60.0

    /**
     * 0 to 100: the chance, drawn once for every mob when the fight with it begins, that the player is crouched the whole
     * of that kill. 0 is never, 100 is always.
     */
    @JvmField var sneakChanceMin = 15.0
    @JvmField var sneakChance = 25.0

    /** Run after a mob that was knocked out of reach, aiming at it, instead of planning a route to it. */
    @JvmField var chaseKnockedBack = true

    /** How far away a mob may be and still be run after directly, in blocks. */
    @JvmField var chaseRange = 9.0

    /**
     * The failsafe of the approach: when the player has not come this many blocks closer to the target in this many
     * seconds - while it cannot be hit - another target is chosen. 0 seconds is off.
     */
    @JvmField var failsafeSeconds = 8.0
    @JvmField var failsafeDistance = 2.0

    /** The pause before the held item is changed, for every change the macro makes: drawn between these two each time. */
    @JvmField var switchDelayMinMillis = 100
    @JvmField var switchDelayMaxMillis = 200

    /** Below this share of its health a mob is finished off with the Black Hole instead of the weapon. */
    @JvmField var useBlackHole = true
    @JvmField var finisherHealthPercent = 10.0

    /** The Black Hole is used with the right button held down, instead of clicked at the right click rate. */
    @JvmField var holdBlackHole = false

    /** Cover distance on the path in jumps of `teleportDistance` blocks with the teleport item. */
    @JvmField var useTeleport = true
    @JvmField var teleportDistance = 12.0

    /**
     * How fast the teleport item is clicked in a row: the pause after a jump or warp has landed, before the next one is
     * aimed and fired, drawn between these two for every one. The smaller the pause, the faster the spam.
     */
    @JvmField var teleportDelayMinMillis = 120
    @JvmField var teleportDelayMaxMillis = 220

    /** Ticks of lead on top of the ping when working out where the player will be when the jump goes off. */
    @JvmField var teleportLeadTicks = 2

    /** The furthest a teleport may leave the player above the ground it lands on: a hop is never to carry them off a ledge. */
    @JvmField var hopDrop = 4.0

    /** Ticks between two teleport clicks, for the plain item and for etherwarp, with a little random on top (see TeleportGap). */
    @JvmField var hopGapTicks = 3
    @JvmField var warpGapTicks = 8

    /** The Pathfinder keeps out of the Area Killer's gate and bounds, as the killer does, instead of walking where it likes. */
    @JvmField var respectGate = false

    /**
     * The teleport item's second mode, sneak and right click: a jump to the block looked at, however far up to
     * `etherRange`, onto its top - provided there are two air blocks above it.
     */
    @JvmField var useEtherwarp = true
    @JvmField var etherRange = 60.0

    /** A route shorter than this is walked or jumped, not warped. */
    @JvmField var etherMinDistance = 20.0

    @JvmField var showOverlay = true
    @JvmField var showHud = true

    /** Everything the area killer has done, over all sessions: mobs killed and time run, in milliseconds. */
    @JvmField var totalKills = 0L
    @JvmField var totalRunMillis = 0L

    /** How the next mob is picked when several are in range. */
    @JvmField var targetPattern = TargetPattern.HUMAN

    /**
     * The view cone of the target planner, in degrees either side of where the player looks: while any mob is inside it, the
     * next target is picked from those alone. 0 is off (a mob behind the player can be the next target).
     */
    @JvmField var viewCone = 70.0

    /**
     * How far off the way it is going the view may be, in degrees, before the player stops pushing the movement keys and
     * waits for the view to turn. 0 is off. A person turns first and then runs; they do not back-run at a mob behind them.
     */
    @JvmField var turnFirstDegrees = 100.0

    /**
     * Run straight at the target, working out where it is every tick, whenever it is in sight and the ground between is a plain
     * walk. A route is half a second old at best and a mob moves; where the ground is not plain the route planner takes over.
     */
    @JvmField var directApproach = true

    /**
     * A hit that would kill the mob counts as a kill at once: the macro moves to the next target without waiting for the
     * mob to die on screen. Meant for the 1 Tap sim; the estimate uses the player's attack damage against the mob's health.
     */
    @JvmField var assumeOneTap = false

    /** While fighting, plan the route to the next mob already, so the walk to it starts the moment this one is dead. */
    @JvmField var precomputeNext = true

    /**
     * How close the player gets to a mob, eye to hitbox, in blocks. A mob that can be hit from further away is hit on the
     * way in; the player walks on up to this distance.
     */
    @JvmField var approachDistance = 1.2

    /** How far either way, in blocks, the distance drawn for each mob may be from [approachDistance]. */
    @JvmField var approachVariationMin = 0.24
    @JvmField var approachVariation = 0.4

    /**
     * The furthest a route may drop in one step. Not a setting: the player survives any drop worth walking off, and a limit was what
     * left them standing on a ledge a jump boost had put them on. Only here to bound how far down the planner looks for ground.
     */
    val maxDrop: Double get() = MAX_DROP

    /**
     * The rest point: three block coordinates, or empty. With [restEnabled] the player goes there when nothing has been in reach for
     * [restDelaySeconds] and wanders about within [restRadius] blocks of it until a mob comes. One setting for the macro, not one
     * for each area.
     */
    @JvmField var restEnabled = false
    @JvmField var restPoint = IntArray(0)
    @JvmField var restRadius = 4.0
    @JvmField var restDelaySeconds = 4.0

    /** When the macro sprints: always, never, or - hybrid - only while the target is further away than [sprintDistance]. */
    enum class SprintMode {
        ALWAYS,
        NEVER,
        HYBRID,
    }

    @JvmField var sprintMode = SprintMode.ALWAYS

    /** Hybrid: sprint only while what is run to - the end of the route, the mob - is more than this many blocks away. */
    @JvmField var sprintDistance = 10.0

    /** Jump while sprinting along a long straight stretch, when that is faster at the speed the player has. */
    @JvmField var sprintJump = true
    @JvmField var allowJumps = true

    /** How much a jump costs next to its plain cost, in percent: more keeps routes to stairs. */
    @JvmField var jumpCostPercent = 100.0
    @JvmField var allowDiagonals = true

    /** Walk through water shallow enough to stand up in, with the head above it. */
    @JvmField var allowWater = true

    /** Swim across deeper water, along its surface. */
    @JvmField var allowSwim = true

    /**
     * 0 to 100: how far the whole route is shifted sideways, up to 0.3 of a block at 100 - parallel to itself, so a
     * straight run stays straight - and how much give there is in when the jump key goes down.
     */
    @JvmField var pathRandomnessMin = 18.0
    @JvmField var pathRandomness = 30.0
    @JvmField var repathIntervalTicks = 10

    /** The longest stretch between two waypoints of a route, in blocks; a longer straight stretch is divided. */
    @JvmField var waypointSpacing = 8.0

    /** 0 to 100: how far the waypoints are moved off the middle of their blocks, up to 0.45 of a block at 100. */
    @JvmField var offCentre = 60.0

    /**
     * Keep what has been read of the world on disk, per server and dimension, and plan with it where the world is not
     * loaded: the planner then knows the ground beyond the view distance, and more of it every time.
     */
    @JvmField var saveWorld = true

    /**
     * Learn the area while hunting: places where the walker got stuck cost the planner more from then on, and are
     * forgiven bit by bit when they are walked through without trouble. Kept on disk beside the saved world.
     */
    @JvmField var learnArea = true

    /** Let go of the mouse while a macro runs, so it can be used for other windows; the game does not pause when it loses focus. */
    @JvmField var ungrabMouse = true

    /**
     * How long the search for a route may work in every tick, in milliseconds. A tick is 50 ms; the search is the only
     * thing that waits for it, and a long way or a hard one takes a few hundred milliseconds of work in all.
     */
    @JvmField var pathSearchMillis = 20.0

    /** 0 to 100: how much routes are made to run straight, turning as little as they can. */
    @JvmField var pathStraightness = 70.0

    /** Plan with the jump height the player has with a jump boost on, which is read off the player. */
    @JvmField var allowJumpBoost = true

    /**
     * Keep a map of the walkable ground around the player: what cannot be reached is known without a search, and the
     * searches that are made are guided by the real cost to a few far places instead of the straight line alone.
     */
    @JvmField var useRouteMap = true

    /** Picks the weave of the next trip. A route is re-planned all the time, and the weave must not change with it. */
    @Transient private var pathSeed = ThreadLocalRandom.current().nextLong()

    /** The route randomness this trip drew between its low and high end. */
    @Transient private var tripRandomness = pathRandomness

    /** Repairs whatever a hand-edited value can get wrong, so nothing downstream has to null-check. */
    fun normalize() {
        // No gate is two empty lists; anything else that is not two sets of three numbers is cleared. The gate the source shipped as a
        // default (x -120, y 206, z -63 to -56) was a wall nobody had asked for, and is not kept from a saved file.
        if (gate.from.size != 3 || gate.to.size != 3 ||
            (gate.from.contentEquals(intArrayOf(-120, 206, -63)) && gate.to.contentEquals(intArrayOf(-120, 206, -56)))
        ) {
            gate = Gate()
        }
        paintArea = paintArea.trim()
        jumpCostPercent = jumpCostPercent.coerceIn(0.0, 500.0)
        margin = margin.coerceIn(0, 3)
        weaponSlot = weaponSlot.coerceIn(1, 9)
        teleportSlot = teleportSlot.coerceIn(1, 9)
        blackHoleSlot = blackHoleSlot.coerceIn(1, 9)
        scanRadius = scanRadius.coerceIn(0, MAX_SCAN)
        repathIntervalTicks = Math.max(1, repathIntervalTicks)
        if (restPoint.size != 3) restPoint = IntArray(0)
        restRadius = restRadius.coerceIn(1.0, 16.0)
        restDelaySeconds = restDelaySeconds.coerceIn(1.0, 120.0)

        leftCpsMin = Math.max(1.0, Math.min(20.0, leftCpsMin))
        leftCpsMax = Math.max(leftCpsMin, Math.min(20.0, leftCpsMax))
        rightCpsMin = Math.max(1.0, Math.min(20.0, rightCpsMin))
        rightCpsMax = Math.max(rightCpsMin, Math.min(20.0, rightCpsMax))
        waypointSpacing = Math.max(2.0, Math.min(30.0, waypointSpacing))
        offCentre = Math.max(0.0, Math.min(100.0, offCentre))
        aimSpread = Math.max(0.0, Math.min(100.0, aimSpread))
        if (!aimHead && !aimShoulders && !aimUpper && !aimCenter && !aimLower) aimCenter = true
        sneakChance = Math.max(0.0, Math.min(100.0, sneakChance))
        viewCone = Math.max(0.0, Math.min(180.0, viewCone))
        turnFirstDegrees = Math.max(0.0, Math.min(180.0, turnFirstDegrees))
        chaseRange = Math.max(3.0, Math.min(16.0, chaseRange))
        failsafeSeconds = if (failsafeSeconds <= 0.0) 0.0 else Math.max(2.0, Math.min(60.0, failsafeSeconds))
        failsafeDistance = Math.max(0.5, Math.min(10.0, failsafeDistance))
        approachDistance = Math.max(0.3, Math.min(3.5, approachDistance))
        approachVariation = Math.max(0.0, Math.min(1.5, approachVariation))
        cpsRandomness = Math.max(0.0, Math.min(100.0, cpsRandomness))
        cpsRandomnessMin = Math.max(0.0, Math.min(cpsRandomness, cpsRandomnessMin))
        aimSpreadMin = Math.max(0.0, Math.min(aimSpread, aimSpreadMin))
        sneakChanceMin = Math.max(0.0, Math.min(sneakChance, sneakChanceMin))
        approachVariationMin = Math.max(0.0, Math.min(approachVariation, approachVariationMin))
        pathRandomness = Math.max(0.0, Math.min(100.0, pathRandomness))
        pathRandomnessMin = Math.max(0.0, Math.min(pathRandomness, pathRandomnessMin))
        pathStraightness = Math.max(0.0, Math.min(100.0, pathStraightness))
        pathSearchMillis = Math.max(2.0, Math.min(50.0, pathSearchMillis))
        switchDelayMinMillis = Math.max(0, Math.min(1000, switchDelayMinMillis))
        switchDelayMaxMillis = Math.max(switchDelayMinMillis, Math.min(1000, switchDelayMaxMillis))
        finisherHealthPercent = Math.max(1.0, Math.min(50.0, finisherHealthPercent))
        teleportDistance = Math.max(2.0, Math.min(30.0, teleportDistance))
        teleportDelayMinMillis = Math.max(0, Math.min(1000, teleportDelayMinMillis))
        teleportDelayMaxMillis = Math.max(teleportDelayMinMillis, Math.min(1000, teleportDelayMaxMillis))
        teleportLeadTicks = Math.max(0, Math.min(6, teleportLeadTicks))
        hopDrop = Math.max(1.0, Math.min(MAX_DROP, hopDrop))
        hopGapTicks = Math.max(0, Math.min(20, hopGapTicks))
        warpGapTicks = Math.max(0, Math.min(40, warpGapTicks))
        etherRange = Math.max(20.0, Math.min(60.0, etherRange))
        etherMinDistance = Math.max(8.0, Math.min(etherRange - 2.0, etherMinDistance))
        sprintDistance = Math.max(2.0, Math.min(40.0, sprintDistance))
        totalKills = Math.max(0L, totalKills)
        totalRunMillis = Math.max(0L, totalRunMillis)
    }

    /** Puts an entry on the kill list in its stored form, once. */
    fun addMob(entityId: String) {
        val normalized = MobIds.normalize(entityId)
        if (!mobs.contains(normalized)) mobs.add(normalized)
    }

    /** Takes an entry off the list: given as a type it goes by that, otherwise as a name. */
    fun removeMob(entityId: String): Boolean {
        if (mobs.remove(MobIds.normalize(entityId))) return true
        val name = MobIds.nameEntry(entityId)
        return name != null && mobs.remove(name)
    }

    /** Starts a new trip: its routes weave differently from the last one's. */
    fun newTrip() {
        pathSeed = ThreadLocalRandom.current().nextLong()
        tripRandomness = draw(pathRandomnessMin, pathRandomness)
    }

    /** One draw between the two ends of a range setting; the high end when the range is a point. */
    fun draw(low: Double, high: Double): Double =
        if (high <= low) high else low + ThreadLocalRandom.current().nextDouble() * (high - low)

    override fun approachDistance(): Double = approachDistance

    override fun maxFallHeight(): Double = maxDrop

    override fun allowJumps(): Boolean = allowJumps

    override fun allowDiagonals(): Boolean = allowDiagonals

    override fun allowWater(): Boolean = allowWater

    override fun allowSwim(): Boolean = allowSwim

    override fun pathRandomness(): Double = tripRandomness / 100.0

    override fun pathSeed(): Long = pathSeed

    override fun waypointSpacing(): Double = waypointSpacing

    override fun offCentre(): Double = offCentre / 100.0

    override fun straightness(): Double = pathStraightness / 100.0

    override fun useJumpBoost(): Boolean = allowJumpBoost

    override fun useRouteMap(): Boolean = useRouteMap

    override fun sprintJump(): Boolean = sprintJump

    override fun sprint(): Boolean = sprintMode != SprintMode.NEVER

    override fun sprintsAt(distance: Double): Boolean = when (sprintMode) {
        SprintMode.ALWAYS -> true
        SprintMode.NEVER -> false
        SprintMode.HYBRID -> distance > sprintDistance
    }

    /** The distance beyond which the macro sprints, in blocks: nothing in hybrid mode's absence, so the caller's own floor counts. */
    fun hybridSprintDistance(): Double = if (sprintMode == SprintMode.HYBRID) sprintDistance else 0.0

    override fun jumpCost(): Double = jumpCostPercent / 100.0

    override fun searchRadius(): Int = if (scanRadius <= 0) SEARCH_CAP else minOf(scanRadius, SEARCH_CAP)

    /** How far the macro looks for mobs, in blocks: with the scan radius at 0 as far as the game has mobs to show. */
    fun scanBlocks(): Double = if (scanRadius <= 0) UNLIMITED_SCAN else scanRadius.toDouble()

    override fun searchMillisPerTick(): Double = pathSearchMillis

    companion object {
        /** See [maxDrop]. */
        const val MAX_DROP = 40.0

        /** The scan radius goes up to this, and 0 means no limit (this far). The routes are searched within [SEARCH_CAP] of the player: the map of the ground is made for that. */
        const val MAX_SCAN = 1024
        const val UNLIMITED_SCAN = 1024.0
        const val SEARCH_CAP = 96
    }
}
