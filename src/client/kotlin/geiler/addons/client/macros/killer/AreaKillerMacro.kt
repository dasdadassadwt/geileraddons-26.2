package geiler.addons.client.macros.killer

import geiler.addons.client.macros.area.Paint
import geiler.addons.client.macros.area.GateArea
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.config.MacroWorkspaceConfig
import geiler.addons.client.macros.input.CpsClicker
import geiler.addons.client.macros.input.InputSwap
import geiler.addons.client.macros.input.ItemSwitcher
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.input.WalkInput
import geiler.addons.client.macros.navigation.HopPlanner
import geiler.addons.client.macros.navigation.InstantHops
import geiler.addons.client.macros.navigation.LookHeight
import geiler.addons.client.macros.navigation.PathfindMacro
import geiler.addons.client.macros.navigation.RouteRunner
import geiler.addons.client.macros.navigation.StuckEscape
import geiler.addons.client.macros.navigation.WarpRule
import geiler.addons.client.macros.pathing.AreaMemory
import geiler.addons.client.macros.pathing.EntityGoal
import geiler.addons.client.macros.pathing.Path
import geiler.addons.client.macros.pathing.PathSearch
import geiler.addons.client.macros.pathing.PositionGoal
import geiler.addons.client.macros.pathing.SearchBudget
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.pathing.WalkGraph
import geiler.addons.client.macros.pathing.WalkGraphs
import geiler.addons.client.macros.pathing.WorldStore
import geiler.addons.client.macros.record.RecordLines
import geiler.addons.client.macros.rotation.SmoothRotation
import geiler.addons.client.macros.rotation.LookControl
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.util.Util
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom
import java.util.function.DoubleSupplier
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a configured mob in the stretch of tunnel it was started in, walks to it and kills it - and never goes through
 * the gate that closes that stretch off.
 *
 * The loop is: search for a route to the nearest thing worth killing, follow it while the route is kept up to date behind
 * the scenes, and click once standing somewhere the crosshair can reach. Long stretches of route are crossed by teleport
 * jumps ([RouteRunner]). A mob that has been brought below a share of its health is finished off with the Black Hole
 * instead: the held item is changed after the configured pause and the right mouse button is clicked on it.
 *
 * Every click is a simulated mouse click ([SimulatedMouse]) at a rate drawn from the configured range, and only while the
 * crosshair is on the mob - a click on whatever the crosshair has drifted onto would be a block broken.
 *
 * The gate is enforced three times over, because each layer covers a failure of the next. Mobs in the gate zone or seen
 * through the gate are never picked. The planner is built on a [Terrain] that has no stands in the gate zone, so no route
 * can go through it - and since it only walks from where the player stands, it cannot reach the other side any other way.
 * And every tick the player's own position is checked: if anything at all - a knockback, a lagged teleport, a bug - has
 * put the player in the gate zone, the macro stops and lets go of the keys rather than try to walk back.
 */
class AreaKillerMacro private constructor(private val config: AreaKillerConfig) {
    private enum class State {
        /** No route: looking for one. */
        SEARCHING,

        /** Following a route. */
        WALKING,

        /** In range of the target and hitting it. */
        ENGAGING,

        /** Warping onto the ground next to a target the feet cannot get to. */
        WARPING,
    }

    private val scanner = TargetScanner()
    private val runner = RouteRunner(config).also { it.log = { text -> diagnostics(text) } }
    private val look = LookControl()
    private val aim = AimPoint(config)
    private val chase = DirectChase()
    private val items = ItemSwitcher()
    private val inputSwap = InputSwap()
    private val leftClicker = CpsClicker(
        SimulatedMouse.Button.LEFT, DoubleSupplier { config.leftCpsMin }, DoubleSupplier { config.leftCpsMax },
        DoubleSupplier { config.draw(config.cpsRandomnessMin, config.cpsRandomness) },
    )
    private val rightClicker = CpsClicker(
        SimulatedMouse.Button.RIGHT, DoubleSupplier { config.rightCpsMin }, DoubleSupplier { config.rightCpsMax },
        DoubleSupplier { config.draw(config.cpsRandomnessMin, config.cpsRandomness) },
    )

    private var running = false
    private var area: GateArea? = null
    private var state = State.SEARCHING
    private var target: Entity? = null
    private var search: PathSearch? = null
    private var searchGoal: EntityGoal? = null

    /** The height the view looks at on a route, eased so it does not follow every slab. */
    private val routeHeight = LookHeight()

    /** Notices a walk that goes nowhere, for the log. */
    private val stall = StallWatch()
    private val pause = StallWatch()

    /** How fluid the walk is, counted in five second windows for the log, and a watch for standing still with a target and nothing to wait for. */
    private val walkStats = WalkStats()
    private val standWatch = StandWatch()

    /** The rest point: since when nothing has been in reach, the search for a way to it or to a spot round it, and where that is. */
    private var idleSinceMillis = 0L
    private var restWhyAtMillis = 0L
    private var restSearch: PathSearch? = null
    private var resting = false
    private var restWanderAtMillis = 0L
    private var restBlockedUntilMillis = 0L
    private var restPollTicks = 0
    private var plannedTargetPosition: Vec3? = null

    /** A route to the mob after this one, worked out while this one is fought, and what is needed to work it out. */
    private class Prepared(val entity: Entity, val route: Path, val from: Vec3, val madeAtMillis: Long, val targetAt: Vec3)
    private var prepared: Prepared? = null

    /** The mobs in the order the planner wants them: the first is the target, the second the one after it. */
    private var plan: List<Entity> = emptyList()

    /** The mobs round the player, refreshed a few times a second, and whether they are so many within reach that the player stands to fight. */
    private var nearMobs: List<Entity> = emptyList()
    private var nearAtMillis = 0L
    private var clusterStanding = false
    private var clusterIdleTicks = 0
    private var clusterOffUntilMillis = 0L

    init {
        // A stretch of route with mobs along it is walked, and they are killed on the way, before it is skipped by a teleport.
        runner.shortcutBlocked = { route, from, to -> mobsOnWay(route, from, to) }
        // A warp along a route is for a long clear stretch that it really shortens, and not while there is work close by.
        runner.warpMinRoute = WarpRule.MIN_ROUTE
        runner.warpWorthIt = { remaining, flight, gain -> WarpRule.allow(remaining, flight, gain, mobsNearForWarp()) }
    }

    /** Whether a mob on the list is within [WARP_MOB_RADIUS] blocks: it is the work, and a warp past it is not. */
    private fun mobsNearForWarp(): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        val level = client.level ?: return false
        return scanner.candidates(player, level, config.mobs, WARP_MOB_RADIUS, 1) { entity -> onThisSideOfGate(player, entity) }.isNotEmpty()
    }

    /** The walk cost to a mob, in blocks, from the map of the ground; NaN when there is no map that says. */
    private fun walkCostTo(mob: Entity): Double {
        val client = Minecraft.getInstance()
        val player = client.player ?: return Double.NaN
        val level = client.level ?: return Double.NaN
        return walkCostsOf(player, level, listOf(mob))?.firstOrNull() ?: Double.NaN
    }

    /**
     * The walk to each of these mobs in blocks, from the map of the ground, for the [COST_POOL] nearest of them; NaN for the others and when
     * there is no map that is believed, infinity for one that cannot be walked to. Null when there is no map or the setting is off.
     */
    private fun walkCostsOf(player: LocalPlayer, level: ClientLevel, mobs: List<Entity>): DoubleArray? {
        if (!config.useRouteMap || mobs.isEmpty()) return null
        val region = area ?: return null
        val terrain = Terrain(level, player, config, region)
        val start = terrain.standUnder(player) ?: return null
        val order = mobs.indices.sortedBy { player.distanceToSqr(mobs[it]) }.take(COST_POOL)
        val approach = max(approachNow, PLANNER_MIN_APPROACH)
        val costs = WalkGraphs.walkCosts(terrain, start, order.map { EntityGoal(listOf(mobs[it]), approach, terrain) }) ?: return null
        val out = DoubleArray(mobs.size) { Double.NaN }
        for ((k, i) in order.withIndex()) out[i] = costs[k]
        return out
    }

    /** One line per choice: the first few mobs with the line to them, the walk to them and whether they stay. */
    private fun logChoice(player: LocalPlayer, found: List<Entity>, costs: DoubleArray, straights: DoubleArray, keep: BooleanArray) {
        val now = Util.getMillis()
        if (now - lastChoiceLogMillis < CHOICE_LOG_MILLIS) return
        lastChoiceLogMillis = now
        val rows = found.indices.sortedBy { straights[it] }.take(5).joinToString(" ") { i ->
            val cost = costs[i]
            val shown = if (cost.isNaN()) "?" else if (cost.isInfinite()) "none" else one(cost)
            "[${MobIds.label(found[i])} line=${one(straights[i])} walk=$shown ${if (keep[i]) "keep" else "drop"}]"
        }
        diagnostics("target choice candidates=${found.size} dropped=${keep.count { !it }} costMs=${one(costMillis)} $rows")
    }

    private var lastChoiceLogMillis = 0L
    private var costMillis = 0.0
    private var movingWhileSearching = false
    private var nextSearch: PathSearch? = null
    private var nextGoal: EntityGoal? = null
    private var nextStart: Vec3? = null
    private var nextEntity: Entity? = null
    private var nextTried = false
    private var repathTicks = 0
    private var stuckStrikes = 0
    private var failedSearches = 0
    private var rescanAtMillis = 0L
    private var finisherGaveUpOn: Entity? = null
    private var finisherReadyMillis = 0L

    /** How close the player gets to the target it has now, drawn from the configured distance and its variation. */
    private var approachNow = config.approachDistance

    /** Whether the player is crouched for the target it has: drawn once per mob, held until that mob is gone. */
    private val sneakRule = SneakRule()
    private var sneakApplied = false

    /** Whether the view is on the target (visible and inside the view cone), and what the view was last put on, for the log. */
    private val focus = LookFocus()
    private var lastLook = ""

    /** The mob walked toward while a route is searched, and the way last headed, for the idle look. */
    private var searchMover: Entity? = null
    private var directActive = false
    private var idleHeading = Double.NaN

    /** The last second of walking, tick by tick: 0 not moving, 1 forward only, 2 anything else; and the tick count for the aim line. */
    private val moveRing = IntArray(20)
    private var moveRingAt = 0
    private var aimTicks = 0
    private var targetHealth = Double.NaN
    private var lastTargetY = 0.0
    private var targetYSeen = false
    private var calmUntilMillis = 0L

    /** The session: since when the macro has been running, and how many mobs it has killed since. */
    private var sessionStartMillis = 0L
    private var sessionKills = 0

    /** The mob last hit, and when: what decides whether a mob that is gone was a kill. */
    private var lastHit: Entity? = null
    private var lastHitMillis = 0L
    private var sessionEndMillis = 0L

    /** When the time run was last added to the total, and when the totals were last written. */
    private var totalsTickMillis = 0L
    private var totalsSavedMillis = 0L

    /** The failsafe: since when the player has been trying to get closer to the target, and from how far. 0 is not started. */
    private var approachSinceMillis = 0L
    private var approachFromDistance = 0.0

    /** The target that was last warped onto, and how many times in a row. */
    private var directWarpTarget: Entity? = null
    private var directWarps = 0

    /** The mob the server said is being hunted with the Huntaxe, and until when it is the Black Hole's and not the weapon's. */
    private var huntTarget: Entity? = null
    private var huntUntilMillis = 0L

    /** With nothing to do: what the view rests on, and until when. */
    private var idleLookPoint: Vec3? = null
    private var idleLookUntilMillis = 0L

    /** The session with the rate per hour, for when it ends. */
    private fun sessionSummary(): String {
        val millis = Util.getMillis() - sessionStartMillis
        val rate = if (millis < 60_000L) "" else String.format(" (%.0f/h)", sessionKills * 3_600_000.0 / millis)
        return "session " + clock(millis) + ", " + sessionKills + (if (sessionKills == 1) " kill" else " kills") + rate
    }

    private fun begin() {
        val client = Minecraft.getInstance()
        val player = client.player
        val level = client.level
        if (player == null || level == null) return
        if (config.mobs.isEmpty()) {
            message("Kill list is empty - look at a mob and use /ga addmobkill, or /ga areakiller add <mob>")
            return
        }

        val painted = if (config.paintArea.isEmpty()) null else PaintStore.get(config.paintArea)
        if (config.paintArea.isNotEmpty() && painted == null) {
            message("The painted area '" + config.paintArea + "' does not exist - /ga area list, or /ga area use none")
            return
        }
        val candidate: GateArea
        try {
            candidate = GateArea.create(config.gate.from, config.gate.to, config.margin, config.bounds, painted)
        } catch (exception: IllegalArgumentException) {
            message(exception.message ?: "The gate is not valid")
            return
        }
        if (!candidate.containsPoint(player.position())) {
            message("You are outside the painted area, too close to the gate or outside the bounds - step back first (" + candidate.describe() + ")")
            return
        }

        PathfindMacro.stop("killer started")
        reset(client)
        area = candidate
        running = true
        runner.resetHops()
        config.newTrip()
        sessionStartMillis = Util.getMillis()
        walkStats.reset()
        sessionKills = 0
        totalsTickMillis = sessionStartMillis
        totalsSavedMillis = sessionStartMillis
        val store = storeFile(client, level)
        if (config.saveWorld) WorldStore.activate(level, store, gateMiddle(player))
        if (config.learnArea) {
            AreaMemory.activate(level, store.resolveSibling(store.fileName.toString().replace(".bin", ".learn")))
        }
        message("Started - " + candidate.describe())

        // A gate narrower than the tunnel leaves a way round it. Say so rather than start as if all were well.
        val opening = candidate.findOpening(Terrain(level, player, config))
        if (opening != null) {
            message(
                "Warning: there is walkable ground beside the gate at " + opening +
                    " - the tunnel may be wider than the gate. Extend the gate to the full width.",
            )
        }
    }

    /**
     * The file the world around this area is kept in: one per server, dimension and gate, so the ground of one area is
     * never taken for another's.
     */
    private fun storeFile(client: Minecraft, level: ClientLevel): java.nio.file.Path {
        val current = client.currentServer
        val local = client.singleplayerServer
        val server = if (current != null) {
            current.ip
        } else if (local != null) {
            "local-" + local.worldData.levelName
        } else {
            "unknown"
        }
        val from = config.gate.from
        val to = config.gate.to
        val place = if (from.size == 3 && to.size == 3) {
            from[0].toString() + "_" + from[1] + "_" + from[2] + "_" + to[0] + "_" + to[1] + "_" + to[2]
        } else {
            "area_" + config.paintArea.ifEmpty { "open" }
        }
        val name = server + "_" + level.dimension().identifier() + "_" + place
        val safe = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]"), "_")
        return FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("macros").resolve("worlds").resolve("$safe.bin")
    }

    private fun gateMiddle(player: LocalPlayer): BlockPos {
        val from = config.gate.from
        val to = config.gate.to
        if (from.size != 3 || to.size != 3) return player.blockPosition()
        return BlockPos((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, (from[2] + to[2]) / 2)
    }

    private fun halt(reason: String) {
        diagnostics("session summary ${walkStats.total().describe()} kills=$sessionKills")
        addRunTime()
        sessionEndMillis = Util.getMillis()
        saveConfig()
        running = false
        reset(Minecraft.getInstance())
        message("Stopped - " + reason + " · " + sessionSummary())
    }

    private fun tick(client: Minecraft) {
        if (!running) return

        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            // Left the world: the session ends here, and what it added to the totals is kept.
            addRunTime()
            sessionEndMillis = Util.getMillis()
            saveConfig()
            running = false
            reset(client)
            diagnostics("stopped: left the world")
            return
        }
        if (player.isDeadOrDying) {
            halt("you died")
            return
        }
        // The hard limit. Nothing below is trusted to keep the player away from the gate: if the player is anywhere in
        // its zone, everything is let go of at once.
        val region = area!!
        if (region.breached(player.position())) {
            halt("reached the gate zone")
            return
        }

        WorldStore.tick(level)
        AreaMemory.tick(level)
        addRunTime()
        if (Util.getMillis() - totalsSavedMillis > 60_000L) {
            // The totals are kept in the config; written now and then, so a game that is closed while running loses little.
            totalsSavedMillis = Util.getMillis()
            saveConfig()
        }
        val input = inputSwap.attach(player)
        input.turnFirst(config.turnFirstDegrees)

        // A real player can neither move nor click with a screen open. Keep the route and carry on after.
        if (client.gui.screen() != null) {
            approachSinceMillis = 0L
            input.releaseAll()
            look.release()
            leftClicker.release(client)
            rightClicker.release(client)
            runner.release(client)
            return
        }

        target?.let { current ->
            if (!stillWorthKilling(player, level, current)) {
                countKill(player, level, current)
                dropTarget()
                handOver(player, level, current)
            }
        }
        target?.let { current ->
            if (approachStalled(player, current)) {
                message(
                    String.format(
                        "Failsafe: not %.1f m closer to %s in %.0f s - switching target",
                        config.failsafeDistance, MobIds.label(current), config.failsafeSeconds,
                    ),
                )
                scanner.ignore(current, FAILSAFE_IGNORE_MILLIS)
                dropTarget()
            }
        }
        if (state != State.ENGAGING) {
            leftClicker.release(client)
            rightClicker.release(client)
        }

        items.tick(player)
        // A slice of the work of keeping the map of the ground current, see WalkGraphs. Never in the same tick as a search
        // that is being made from it: both are main-thread work with a budget, and they take turns.
        if (search == null && nextSearch == null) WalkGraphs.maintain(level, player, config, region)
        trackTargetMotion()
        updateTargetHealth(level)
        when (state) {
            State.SEARCHING -> doSearch(player, level, input)
            State.WALKING -> doWalk(client, player, level, input)
            State.ENGAGING -> doEngage(client, player, level, input)
            State.WARPING -> doWarp(client, player, level, input)
        }

        aimTrace(player, input)
        stallTrace(player, input)
        qualityTrace(player, input)
        shutTrace(player, region)

        val note = runner.consumeNote()
        if (note != null) message(note)
    }

    /**
     * One line every few ticks while running: what the view is on, how far it is from the mob, which way the keys push compared
     * with where the view points, which keys are down, how much of the last second was walked on the forward key alone, and the
     * heights. It is how a view that is off the mob, a crouch that should not be there or a walk that weaves shows in the log.
     */
    private fun aimTrace(player: LocalPlayer, input: WalkInput) {
        val keys = input.keyPresses
        val strafing = keys.left() || keys.right() || keys.backward()
        moveRing[moveRingAt] = if (!keys.forward() && !strafing) 0 else if (strafing) 2 else 1
        moveRingAt = (moveRingAt + 1) % moveRing.size
        if (++aimTicks % AIM_TRACE_TICKS != 0) return

        var forwardOnly = 0
        var moving = 0
        for (v in moveRing) {
            if (v != 0) moving++
            if (v == 1) forwardOnly++
        }
        val current = target
        val mob = if (current == null) {
            "mob=none"
        } else {
            val centre = current.boundingBox.center
            val wanted = SmoothRotation.lookAt(player.eyePosition, centre)
            val yawError = Mth.wrapDegrees(wanted.yaw - player.yRot).toDouble()
            val pitchError = (wanted.pitch - player.xRot).toDouble()
            "mob='${MobIds.label(current)}' dist=${one(player.distanceTo(current).toDouble())} " +
                "viewOffYaw=${one(yawError)} viewOffPitch=${one(pitchError)} mobY=${one(current.y)} los=${player.hasLineOfSight(current)}"
        }
        val heading = input.heading()
        val walkOff = if (heading.isNaN()) "none" else one(Mth.wrapDegrees(heading - player.yRot).toDouble())
        diagnostics(
            "aim state=${state.name} look=${look.kindName()} $mob walkOffView=$walkOff " +
                "keys='${RecordLines.keys(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint())}' " +
                "forwardOnly=${if (moving == 0) "n/a" else "${forwardOnly * 100 / moving}%"} y=${one(player.y)} pitch=${one(player.xRot.toDouble())} " +
                "hold=${input.isHolding()} sneak=$sneakApplied ground=${player.onGround()} wall=${player.horizontalCollision} " +
                "speed=${one(Math.hypot(player.deltaMovement.x, player.deltaMovement.z) * 20.0)}",
        )
    }

    /**
     * Every tick: counts how the walk went (see [WalkStats]), says it in a `walk quality` line every five seconds, and when the player
     * stands still for a second with a target to go for and nothing to wait on says why in an `anomaly standing` line.
     */
    private fun qualityTrace(player: LocalPlayer, input: WalkInput) {
        val keys = input.keyPresses
        val heading = input.heading()
        val off = if (heading.isNaN()) Double.NaN else Mth.wrapDegrees(heading - player.yRot).toDouble()
        val walking = state == State.WALKING
        walkStats.sample(
            walking, keys.forward() || keys.backward() || keys.left() || keys.right(), keys.left() || keys.right() || keys.backward(),
            input.isHolding(), off,
        )
        val window = walkStats.poll()
        if (window != null && window.walkTicks > 0) diagnostics("walk quality ${window.describe()}")

        // The route and the player disagreeing: said at most once a second, with where on the route the player is and how fast.
        if (state == State.WALKING && runner.path() != null && runner.deviation() > DEVIATION_LOG_BLOCKS &&
            Util.getMillis() - lastDeviationLogMillis > 1000L
        ) {
            lastDeviationLogMillis = Util.getMillis()
            diagnostics(
                "route deviation=${one(runner.deviation())} progress=${one(runner.progress())} remaining=${one(runner.remaining())} " +
                    "speed=${one(Math.hypot(player.deltaMovement.x, player.deltaMovement.z) * 20.0)} ground=${player.onGround()} inFlight=${runner.inFlight()}",
            )
        }

        val speed = Math.hypot(player.deltaMovement.x, player.deltaMovement.z) * 20.0
        val current = target
        val standing = current != null && state != State.ENGAGING && player.onGround() && speed < 0.6 && !runner.inFlight() &&
            !runner.controlsView()
        if (standWatch.tick(standing) && current != null) {
            val route = runner.path()
            diagnostics(
                "anomaly standing state=${state.name} look=${look.kindName()} mob='${MobIds.label(current)}' dist=${one(player.distanceTo(current).toDouble())} " +
                    "los=${player.hasLineOfSight(current)} heading=${if (heading.isNaN()) "none" else one(heading.toDouble())} viewOff=${one(off.let { if (it.isNaN()) 0.0 else it })} " +
                    "hold=${input.isHolding()} keys='${RecordLines.keys(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), keys.shift(), keys.sprint())}' " +
                    "route=${if (route == null) "none" else "progress=${one(runner.progress())} remaining=${one(runner.remaining())}"} " +
                    "search=${search != null} moving=${movingWhileSearching} resting=$resting cluster=$clusterStanding",
            )
        }
    }

    private var lastShutLogMillis = 0L
    private var lastDeviationLogMillis = 0L

    /** Says in the log, at most every two seconds, when the player stands in a cell painted as shut: a wall, or forbidden ground. */
    private fun shutTrace(player: LocalPlayer, region: GateArea) {
        val paint = region.paint() ?: return
        val x = Mth.floor(player.x)
        val y = Mth.floor(player.y + 1.0E-4)
        val z = Mth.floor(player.z)
        if (!paint.shutAt(x, y, z)) return
        val now = Util.getMillis()
        if (now - lastShutLogMillis < SHUT_LOG_MILLIS) return
        lastShutLogMillis = now
        diagnostics("standing in a shut cell state=${state.name} pos=$x,$y,$z type=${paint.typeAt(x, y, z)?.label ?: "wall column"} inFlight=${runner.inFlight()} sinceLandingMs=${runner.sinceLandingMillis()}")
    }

    /** The blocks 0.7 ahead along the heading at the feet, the knee and the head, with the height of their tops: what a stall is against. */
    private fun blockAhead(player: LocalPlayer, heading: Float): String {
        if (heading.isNaN()) return "none"
        val radians = Math.toRadians(heading.toDouble())
        val x = player.x - Math.sin(radians) * 0.7
        val z = player.z + Math.cos(radians) * 0.7
        val level = player.level()
        val parts = StringBuilder()
        for ((label, rise) in listOf("feet" to 0.01, "head" to 1.0)) {
            val pos = net.minecraft.core.BlockPos.containing(x, player.y + rise, z)
            val state = level.getBlockState(pos)
            val shape = state.getCollisionShape(level, pos)
            val top = if (shape.isEmpty) "-" else one(shape.max(net.minecraft.core.Direction.Axis.Y))
            parts.append(label).append('[').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).path)
                .append(" top=").append(top).append("] ")
        }
        return parts.toString().trim()
    }

    /**
     * A walk that goes nowhere: the keys were asked to move the player and the speed stays under a block a second. Said in the log
     * with the reason that is visible from here - the keys held back to turn the view first, a wall, the air - when it has lasted a
     * half second and then every second.
     */
    private fun stallTrace(player: LocalPlayer, input: WalkInput) {
        val asked = state == State.WALKING && !input.heading().isNaN()
        val speed = Math.hypot(player.deltaMovement.x, player.deltaMovement.z) * 20.0
        // Standing still while WALKING with no keys asked for at all: the other kind of pause, and the log says which.
        val idle = state == State.WALKING && input.heading().isNaN() && player.onGround() && !input.isHolding()
        if (pause.tick(idle, speed)) {
            val why = if (runner.inFlight()) "teleport in flight" else if (runner.controlsView()) "turning to a teleport" else "no keys: waiting or braking"
            diagnostics("pause ticks=${pause.ticks()} reason='$why' look=${look.kindName()} y=${one(player.y)} pos=${one(player.x)},${one(player.z)}")
        }
        if (!stall.tick(asked, speed)) return
        val reason = if (input.isHolding()) "turn-first hold" else if (player.horizontalCollision) "wall" else if (!player.onGround()) "air" else "no progress"
        val heading = input.heading()
        diagnostics(
            "stall ticks=${stall.ticks()} reason='$reason' walkOffView=${if (heading.isNaN()) "none" else one(Mth.wrapDegrees(heading - player.yRot).toDouble())} " +
                "look=${look.kindName()} y=${one(player.y)} pos=${one(player.x)},${one(player.z)} ahead=${blockAhead(player, heading)} " +
                "keys='${RecordLines.keys(input.keyPresses.forward(), input.keyPresses.backward(), input.keyPresses.left(), input.keyPresses.right(), input.keyPresses.jump(), input.keyPresses.shift(), input.keyPresses.sprint())}'",
        )
    }

    /**
     * Counts the target as killed if that is what happened to it: it was hit a moment ago, and it has died - or is gone
     * from the world near the player in a way a chunk going does not explain, which is how a server's own creatures often
     * go.
     */
    private fun countKill(player: LocalPlayer, level: ClientLevel, entity: Entity) {
        if (entity !== lastHit || Util.getMillis() - lastHitMillis > KILL_CREDIT_MILLIS) return
        val died = entity is LivingEntity && (entity.isDeadOrDying || !entity.isAlive && !entity.isRemoved)
        val removal = entity.removalReason
        val gone = entity.isRemoved &&
            (removal == Entity.RemovalReason.KILLED || removal == Entity.RemovalReason.DISCARDED) &&
            player.distanceTo(entity) <= KILL_CREDIT_DISTANCE
        val health = MobNames.healthFraction(level, entity)
        if (died || gone || health <= 0.0) {
            sessionKills++
            config.totalKills++
            lastHit = null
            scanner.forgetStreak(entity)
            diagnostics("kill counted mob='${MobIds.label(entity)}' died=$died gone=$gone sessionKills=$sessionKills")
        }
    }

    /** Adds the time since the last call to the total time run. */
    private fun addRunTime() {
        val now = Util.getMillis()
        if (totalsTickMillis > 0L) config.totalRunMillis += now - totalsTickMillis
        totalsTickMillis = now
    }

    /**
     * Whether the player has stopped getting closer to the target: not the configured distance closer within the
     * configured time. Whatever the reason - a route that keeps failing, a mob that walks off as fast as it is followed,
     * one that is somewhere the route cannot quite get to - the time is better spent on another one. A target that can be
     * hit, or was hit a moment ago, is being fought, not approached, and the clock starts again.
     */
    private fun approachStalled(player: LocalPlayer, current: Entity): Boolean {
        if (config.failsafeSeconds <= 0.0) return false
        val now = Util.getMillis()
        val distance = player.distanceTo(current).toDouble()
        val fighting = canHit(player) || lastHit === current && now - lastHitMillis < FAILSAFE_HIT_GRACE_MILLIS
        if (approachSinceMillis == 0L || fighting || distance <= approachFromDistance - config.failsafeDistance) {
            approachSinceMillis = now
            approachFromDistance = distance
            return false
        }
        return now - approachSinceMillis > config.failsafeSeconds * 1000.0
    }

    private fun stillWorthKilling(player: LocalPlayer, level: ClientLevel, entity: Entity): Boolean =
        scanner.isValid(level, entity, config.mobs) && onThisSideOfGate(player, entity)

    /** Not in the gate zone and not on the far side of the gate from the player. */
    private fun onThisSideOfGate(player: LocalPlayer, entity: Entity): Boolean {
        val region = area!!
        return region.containsEntity(entity) && !region.throughGate(player.position(), entity.position())
    }

    private fun want(player: LocalPlayer, slot: Int) {
        items.want(player, slot, config.switchDelayMinMillis, config.switchDelayMaxMillis)
    }

    /** The weapon, or the teleport item while there is a stretch of route ahead to cross with it. */
    private fun wantTravelItem(player: LocalPlayer) {
        val teleport = runner.desiredItem(player)
        want(player, if (teleport >= 0) teleport else config.weaponSlot - 1)
    }

    /**
     * The mob to keep walking toward while no route is ready: the target, or else the first of the plan. A search takes a
     * few ticks and a kill ends the walk, and a player who stops at each of them looks like a macro. Only a plain walk is
     * taken (see [DirectChase]); where it is not one, or nothing is left to go to, the player stands.
     */
    private fun keepMoving(player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        val mover = target ?: plan.firstOrNull { it.isAlive && !it.isRemoved && !scanner.isIgnored(it) && onThisSideOfGate(player, it) }
        searchMover = mover
        var result = DirectChase.Result.BLOCKED
        if (mover != null) {
            val terrain = Terrain(level, player, config, area!!)
            result = chase.step(
                player, terrain, mover, min(approachNow, MAX_STAND_OFF), config.chaseRange,
                config.sprint() && !sneakNow(), config.hybridSprintDistance(), input,
            )
        }
        if (result == DirectChase.Result.BLOCKED) input.stand(player.isInWater)
        val moving = result == DirectChase.Result.MOVING
        if (moving != movingWhileSearching) {
            movingWhileSearching = moving
            if (moving) diagnostics("moving while searching heading=${one(Mth.wrapDegrees(player.yRot).toDouble())} mob='${MobIds.label(mover!!)}'")
        }
    }

    private fun doSearch(player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        if (backOffTicks > 0) {
            // Out of a tight place: a few steps back and to one side, the way a person gets off a corner, then the route is planned again.
            backOffTicks--
            input.walk(player.yRot + 180.0f + backOffSide * BACK_OFF_DEGREES, false, false)
            return
        }
        if (clusterHold(player, level, input)) return
        keepMoving(player, level, input)
        applySneak(input, "search")
        wantTravelItem(player)

        if (restSearch != null) {
            watchOrIdle(player, level)
            advanceRest(player)
            return
        }
        if (search == null) {
            if (target == null && usePrepared(player, level)) return
            if (Util.getMillis() < rescanAtMillis) {
                watchOrIdle(player, level)
                return
            }
            // A target that is still valid is kept rather than re-chosen: switching to whatever happens to be nearest on
            // every replan is how a macro ends up oscillating between two mobs and killing neither.
            val current = target
            val candidates = if (current != null) {
                listOf(current)
            } else {
                byPattern(
                    player,
                    scanner.candidates(player, level, config.mobs, config.scanBlocks(), MAX_CANDIDATES) { entity ->
                        onThisSideOfGate(player, entity)
                    },
                )
            }
            if (candidates.isEmpty()) {
                if (tickRest(player, level)) return
                watchOrIdle(player, level)
                rescanAtMillis = Util.getMillis() + EMPTY_SCAN_PAUSE_MILLIS
                return
            }
            if (!beginSearch(player, level, candidates)) {
                // Nothing to plan from while the feet are off the ground, or nothing that can be got to - or a warp onto a
                // target the feet cannot get to has just begun. Mobs that cannot be got to are no reason to stand about: they
                // count as nothing in reach, so the rest clock runs and the rest walk can begin.
                if (state == State.SEARCHING) {
                    if (target == null && searchGoal == null && tickRest(player, level)) return
                    watchOrIdle(player, level)
                }
                return
            }
            idleSinceMillis = 0L
        }
        // While the route is worked out the view is not left where it was: on the target if it can be seen.
        watchOrIdle(player, level)
        advanceSearch(player)
    }

    private fun beginSearch(player: LocalPlayer, level: ClientLevel, candidates: List<Entity>): Boolean {
        val terrain = Terrain(level, player, config, area!!)
        val start = terrain.standUnder(player) ?: return false
        walkStats.replan()

        val approach = max(approachNow, PLANNER_MIN_APPROACH)
        val reachable = withoutUnreachable(terrain, start, candidates, approach)
        if (reachable.isEmpty()) {
            // None of them can be got to on foot, and the map of the ground says so without a search that has to try every
            // place there is before it may. A warp may still get there.
            if (warpOnto(player, level, candidates)) return false
            dropTarget()
            runner.clear()
            rescanAtMillis = Util.getMillis() + FAILED_SEARCH_PAUSE_MILLIS
            return false
        }

        // Every candidate costs work on every stand the search looks at: the nearest few are searched, the rest wait in the plan.
        val pool = if (reachable.size <= ReplanRules.GOAL_POOL) {
            reachable
        } else {
            val distances = DoubleArray(reachable.size) { player.distanceToSqr(reachable[it]) }
            ReplanRules.nearest(distances, ReplanRules.GOAL_POOL).map { reachable[it] }
        }
        val goal = EntityGoal(pool, approach, terrain)
        searchGoal = goal
        val guide = WalkGraphs.informed(goal, terrain, start)
        val walking = runner.path() != null && !runner.inFlight()
        val scale = SearchBudget.scale(walking, if (walking) runner.remaining() else 0.0)
        search = PathSearch(terrain, start, guide, config, scale)
        return true
    }

    /**
     * The candidates, less those the map of the ground knows cannot be got to from here: they are left alone for a while,
     * as the ones a search failed to find a way to are. Without a map, or where it does not reach, all of them.
     */
    private fun withoutUnreachable(terrain: Terrain, start: Terrain.Stand, candidates: List<Entity>, approach: Double): List<Entity> {
        val reach = WalkGraphs.reach(terrain, start) ?: return candidates
        val kept = ArrayList<Entity>(candidates.size)
        for (candidate in candidates) {
            val alone = EntityGoal(listOf(candidate), approach, terrain)
            if (reach.verdict(terrain, alone) == WalkGraph.Reach.Verdict.UNREACHABLE) {
                scanner.ignoreUnreachable(candidate)
            } else {
                kept.add(candidate)
            }
        }
        return kept
    }

    private fun advanceSearch(player: LocalPlayer) {
        val finished = search!!
        val status = finished.step()
        if (status == PathSearch.Status.RUNNING) return

        val goal = searchGoal!!
        search = null
        searchGoal = null
        if (runner.inFlight()) {
            // A jump is on its way. The route this search started from is behind the player by now.
            return
        }
        val route = finished.path()
        diagnostics(
            "search status=$status tier=${finished.tier().name} length=${route?.length()?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "none"} " +
                "from=${one(player.x)},${one(player.y)},${one(player.z)} " +
                "to=${goal.entities().firstOrNull()?.let { "${one(it.x)},${one(it.y)},${one(it.z)}" } ?: "none"} " +
                "looked=${finished.expansions()}",
        )

        if (status == PathSearch.Status.FOUND) {
            failedSearches = 0
            adopt(goal.matched())
            plannedTargetPosition = target?.position()
            if (route == null || route.length() < 0.5) {
                beginEngage()
                return
            }
            if (state == State.WALKING && keepCurrentRoute(route)) return
            startWalking(route, player)
            return
        }

        // Nothing reachable. A partial route is still worth walking once - getting round the corner is often all that was
        // missing - but a second failure means it really cannot be got to.
        // Not, though, a partial route that only ends at the edge of the water the player is in: swimming to the wall of a
        // pool is no step toward getting out of it. That is what a warp is for, at once.
        // Nor one under a ledge no jump can reach: it only ends with the player hopping at the face of it. A teleport up goes first.
        val ledge = config.useTeleport && goal.entities().firstOrNull()?.let { it.y - player.y > finished.terrain().jumpHeight() + LEDGE_EXTRA } == true
        if (ledge) diagnostics("ledge goal: the target is above any jump, teleport before walking")
        if (route != null && !ledge && !endsInWater(player, route) && ++failedSearches < 2) {
            startWalking(route, player)
            return
        }

        failedSearches = 0
        val level = player.level()
        if (level is ClientLevel && warpOnto(player, level, goal.entities())) return
        for (unreachable in goal.entities()) scanner.ignoreUnreachable(unreachable)
        adopt(null)
        runner.clear()
        state = State.SEARCHING
        // The ones tried are left alone now; any others are looked at again at once, not after the pause of an empty scan.
        rescanAtMillis = Util.getMillis() + FAILED_SEARCH_PAUSE_MILLIS
    }

    /** Whether the player is in water and the route ends in water too, without getting out of it. */
    private fun endsInWater(player: LocalPlayer, route: Path): Boolean {
        val end = route.endPoint()
        val at = BlockPos.containing(end.x, end.y + 0.1, end.z)
        return player.isInWater &&
            (!player.level().getFluidState(at).isEmpty || !player.level().getFluidState(at.below()).isEmpty)
    }

    /**
     * Starts a warp onto the ground next to the first of these mobs that a warp can get to, when walking cannot: across
     * water, onto a ledge. When the ground next to none of them can be seen - from the bottom of a pool with walls too high
     * to climb, say - the warp goes onto whatever block that can be seen is nearest to the nearest of them, and the route
     * is planned again from there. Each target is warped onto or toward at most [MAX_DIRECT_WARPS] times in a row; after
     * that it counts as out of reach like any other. Whether it worked is up to [doWarp].
     */
    private fun warpOnto(player: LocalPlayer, level: ClientLevel, candidates: List<Entity>): Boolean {
        if (!config.useTeleport) return false
        // What is near is reached by a hop of the item's length (a right click, no crouch); a warp is for what is far.
        val near = candidates.firstOrNull()?.let { player.distanceTo(it) < config.etherMinDistance } ?: false
        if (near && hopToward(player, level, candidates)) return true
        if (config.useEtherwarp && etherOnto(player, level, candidates)) return true
        return !near && hopToward(player, level, candidates)
    }

    /**
     * A hop of the teleport item's length toward a mob the feet cannot get to: out of a pit, onto a platform, over a gap. Only the first
     * few of them are tried, and only from the ground - a fan of directions is the costly part. Each mob is hopped toward at most
     * [MAX_DIRECT_WARPS] times in a row, like a warp.
     */
    private fun hopToward(player: LocalPlayer, level: ClientLevel, candidates: List<Entity>): Boolean {
        if (!player.onGround()) return false
        val region = area!!
        val terrain = Terrain(level, player, config, region)
        var tried = 0
        for (candidate in candidates) {
            if (candidate === directWarpTarget && directWarps >= MAX_DIRECT_HOPS) continue
            if (++tried > HOP_CANDIDATES) break
            // A landing the feet can walk on from to the mob is the one to take; where none is, the one that gets nearest, so a chain of
            // hops across several gaps still works.
            val goal = EntityGoal(listOf(candidate), max(approachNow, PLANNER_MIN_APPROACH), terrain)
            val connected = InstantHops.Accept { hop ->
                val stand = terrain.standAt(Mth.floor(hop.landX), hop.standY.toInt(), Mth.floor(hop.landZ))
                val reach = if (stand == null) null else WalkGraphs.reach(terrain, stand)
                reach != null && reach.verdict(terrain, goal) == WalkGraph.Reach.Verdict.REACHABLE
            }
            val plan = HopPlanner.planInstant(level, player, region, player.position(), candidate.position(), config.teleportDistance, config.hopDrop, connected)
                ?: HopPlanner.planInstant(level, player, region, player.position(), candidate.position(), config.teleportDistance, config.hopDrop)
            if (plan != null) {
                startHop(candidate, plan)
                return true
            }
        }
        if (tried > 0) diagnostics("hop none tried=$tried range=${one(config.teleportDistance)} y=${one(player.y)}")
        return false
    }

    private fun startHop(candidate: Entity, plan: InstantHops.Hop) {
        if (candidate !== directWarpTarget) {
            directWarpTarget = candidate
            directWarps = 0
        }
        directWarps++
        adopt(candidate)
        plannedTargetPosition = candidate.position()
        runner.startDirectHop(plan)
        state = State.WARPING
        diagnostics(
            "hop onto mob='${MobIds.label(candidate)}' attempt=$directWarps yaw=${one(plan.yaw)} pitch=${one(plan.pitch)} " +
                "lands=${one(plan.landX)},${one(plan.landY)},${one(plan.landZ)} stands=${one(plan.standY)} fall=${one(plan.fall)} length=${one(plan.length)}",
        )
    }

    private fun etherOnto(player: LocalPlayer, level: ClientLevel, candidates: List<Entity>): Boolean {
        val region = area!!
        val terrain = Terrain(level, player, config, region)
        for (candidate in candidates) {
            if (candidate === directWarpTarget && directWarps >= MAX_DIRECT_WARPS) continue
            if (player.distanceTo(candidate) > config.etherRange) continue
            val plan = HopPlanner.planOnto(level, player, terrain, region, player.position(), candidate.boundingBox, config.etherRange)
            if (plan != null) {
                startWarp(candidate, plan)
                return true
            }
        }
        for (candidate in candidates) {
            if (candidate === directWarpTarget && directWarps >= MAX_DIRECT_WARPS) continue
            val plan = HopPlanner.planToward(level, player, terrain, region, player.position(), candidate.position(), config.etherRange)
            if (plan != null) {
                startWarp(candidate, plan)
                return true
            }
            // Only the nearest: the fan of rays is the costly part, and a step toward it is a step toward the others too.
            break
        }
        return false
    }

    private fun startWarp(candidate: Entity, plan: HopPlanner.EtherPlan) {
        if (candidate !== directWarpTarget) {
            directWarpTarget = candidate
            directWarps = 0
        }
        directWarps++
        adopt(candidate)
        plannedTargetPosition = candidate.position()
        runner.startDirectWarp(plan)
        state = State.WARPING
        diagnostics("warp onto mob='${MobIds.label(candidate)}' attempt=$directWarps")
    }

    /** One tick of a warp onto a target: once landed the route is planned from there, and if it fails the target is left. */
    private fun doWarp(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        wantTravelItem(player)
        val current = target
        if (current == null) {
            runner.clear()
            state = State.SEARCHING
            return
        }
        if (canHit(player) && !runner.inFlight()) {
            runner.clear()
            input.sneak(false)
            beginEngage()
            return
        }
        val step = runner.tickDirectWarp(client, player, level, area!!, input, look, items)
        if (step == RouteRunner.Step.WALKING) {
            state = State.SEARCHING
            rescanAtMillis = 0L
        } else if (step == RouteRunner.Step.NO_PATH) {
            scanner.ignoreUnreachable(current)
            dropTarget()
        }
    }

    /**
     * With nothing to walk to or fight, the view does what somebody waiting does: it rests on a mob on the list that can be
     * seen - one that is left alone for now, say - or else looks into the open, now one way, now another, a little either
     * side of where it was. Left where it happened to be, it stared at the wall for as long as there was nothing to do.
     */
    /**
     * What the view is on while there is no route to follow. The mob that is walked toward is looked at - a player runs where
     * they look - and so are the target and the first of the plan when they are seen and inside the view cone. Otherwise the
     * view rests, level (see [idleLook]); it is never put on a point of its own while a mob there is to go for is in sight.
     */
    private fun watchOrIdle(player: LocalPlayer, level: ClientLevel) {
        val mover = if (movingWhileSearching) searchMover else null
        if (mover != null && mover.isAlive && !mover.isRemoved) {
            aimAtTarget(mover, "walking toward it")
            return
        }
        val watched = target ?: plan.firstOrNull { it.isAlive && !it.isRemoved }
        if (watched != null && onFocus(player, watched)) {
            aimAtTarget(watched, "in view")
            return
        }
        idleLook(player, level)
    }

    /** Whether the camera is on the mob while a route is walked, by [LookPlan]; the heading is the way the keys push, NaN when they do not. */
    private fun lookOnMob(player: LocalPlayer, mob: Entity, heading: Float): Boolean {
        val reference = if (heading.isNaN()) player.yRot.toDouble() else heading.toDouble()
        val off = LookFocus.offDegrees(reference, player.x, player.z, mob.x, mob.z)
        val now = Util.getMillis()
        val want = LookPlan.onMob(off, player.hasLineOfSight(mob), onMobNow, now - onMobSinceMillis, false)
        if (want != onMobNow) {
            onMobNow = want
            onMobSinceMillis = now
        }
        return want
    }

    private var onMobNow = false
    private var onMobSinceMillis = 0L

    /** Whether this mob is the focus of the view: seen, and inside the view cone (with hysteresis, see [LookFocus]). */
    private fun onFocus(player: LocalPlayer, mob: Entity): Boolean {
        val off = LookFocus.offDegrees(player.yRot.toDouble(), player.x, player.z, mob.x, mob.z)
        return focus.update(off, player.hasLineOfSight(mob))
    }

    /** Says in the log when the view goes from one kind of thing to another, and why. */
    private fun traceLook(kind: String, reason: String) {
        if (kind == lastLook) return
        walkStats.lookSwitch()
        diagnostics("look switch from=${lastLook.ifEmpty { "none" }} to=$kind reason='$reason'")
        lastLook = kind
    }

    private fun idleLook(player: LocalPlayer, level: ClientLevel) {
        traceLook("idle", "nothing to hit in view")
        val now = Util.getMillis()
        // A hold on a drifting point ends a moment after a mob of the list comes into view - the time a person takes to notice - and a
        // hold on a mob ends when it is gone, instead of running its time out on the place where it stood.
        if (idleLookPoint != null && now < idleLookUntilMillis && ++idlePollTicks % IDLE_POLL_TICKS == 0) {
            val seen = listedMobInView(player, level)
            if (idleOnMob && (seen == null || seen !== idleMob)) {
                idleLookUntilMillis = 0L
            } else if (!idleOnMob && seen != null) {
                if (idleNoticeAtMillis == 0L) {
                    idleNoticeAtMillis = now + IDLE_NOTICE_MIN_MILLIS +
                        (ThreadLocalRandom.current().nextDouble() * (IDLE_NOTICE_MAX_MILLIS - IDLE_NOTICE_MIN_MILLIS)).toLong()
                } else if (now >= idleNoticeAtMillis) {
                    idleLookUntilMillis = 0L
                    diagnostics("idle look ended: mob='${MobIds.label(seen)}' came into view")
                }
            } else {
                idleNoticeAtMillis = 0L
            }
        }
        if (idleLookPoint == null || now >= idleLookUntilMillis) {
            idleNoticeAtMillis = 0L
            idleLookPoint = idlePoint(player, level)
            idleLookUntilMillis = now + IDLE_LOOK_MIN_MILLIS +
                (ThreadLocalRandom.current().nextDouble() * (IDLE_LOOK_MAX_MILLIS - IDLE_LOOK_MIN_MILLIS)).toLong()
        }
        val point = idleLookPoint
        if (point == null) {
            look.release()
            return
        }
        look.lookAt(if (idleOnMob) idleMob?.takeIf { it.isAlive && !it.isRemoved }?.boundingBox?.center ?: point else point)
    }

    private var idleOnMob = false
    private var idleMob: Entity? = null
    private var idlePollTicks = 0
    private var idleNoticeAtMillis = 0L

    /** The nearest mob of the list that is seen and no more than a view cone to the side, or null. */
    private fun listedMobInView(player: LocalPlayer, level: ClientLevel): Entity? {
        var watched: Entity? = null
        var nearest = Double.POSITIVE_INFINITY
        for (entity in level.getEntities(player, player.boundingBox.inflate(config.scanBlocks()))) {
            if (scanner.isValid(level, entity, config.mobs) && player.hasLineOfSight(entity) &&
                LookFocus.offDegrees(player.yRot.toDouble(), player.x, player.z, entity.x, entity.z) <= LookFocus.LEAVE_DEGREES
            ) {
                val distance = player.distanceToSqr(entity)
                if (distance < nearest) {
                    nearest = distance
                    watched = entity
                }
            }
        }
        return watched
    }

    /**
     * Where the view rests with nothing to hit. A mob of the kill list that is seen and in front is looked at: it is what the
     * player is looking for. Otherwise the view is level - a little up at most, see [IdleLook] - and drifts a few degrees either
     * side of the way last headed, which is where the next mob most likely comes from. Never at the sky, never at the ground.
     */
    private fun idlePoint(player: LocalPlayer, level: ClientLevel): Vec3? {
        val eye = player.eyePosition
        val watched = listedMobInView(player, level)
        idleOnMob = watched != null
        idleMob = watched
        if (watched != null) return watched.boundingBox.center
        val random = ThreadLocalRandom.current()
        val base = if (idleHeading.isNaN()) player.yRot.toDouble() else idleHeading
        val look = IdleLook.angles(base, random.nextDouble(), random.nextDouble())
        val direction = Vec3.directionFromRotation((-look[1]).toFloat(), look[0].toFloat())
        return eye.add(direction.scale(IDLE_LOOK_RANGE))
    }

    /**
     * What the server says about the fight: the mob is being hunted with the Huntaxe ("don't kill it") or the Black Hole has
     * it ("can't move your Black Hole while a monster is being captured"). Either way the weapon stays off it and the Black
     * Hole is used on it at once, whatever its health reads. Nothing the server says stops the Black Hole itself: it is
     * clicked, or held, until the mob is gone.
     */
    private fun onServerMessage(text: String) {
        val current = target
        if (!running || current == null) return
        val lower = text.lowercase(Locale.ROOT)
        val now = Util.getMillis()
        if (lower.contains("can't move your black hole") || lower.contains("monster is being captured") ||
            lower.contains("currently hunting this monster")
        ) {
            huntTarget = current
            huntUntilMillis = now + HUNT_LOCK_MILLIS
            if (finisherGaveUpOn === current) finisherGaveUpOn = null
            diagnostics("hunt lock mob='${MobIds.label(current)}'")
        }
    }

    /** Whether the server has said the target is being hunted with the Huntaxe, and is not to be hit with the weapon. */
    private fun huntLocked(): Boolean = target != null && target === huntTarget && Util.getMillis() < huntUntilMillis

    /** Whether the server has said the Black Hole has the target: shown as such, and the Black Hole keeps going. */
    private fun capturing(): Boolean = huntLocked()

    private fun startWalking(route: Path, player: LocalPlayer, rest: Boolean = false) {
        runner.follow(route, player.position())
        repathTicks = config.repathIntervalTicks
        resting = rest
        state = State.WALKING
    }

    /**
     * With nothing in reach for a while and a rest point set, walks there - or, once there, to a spot round it now and then. Returns
     * whether a walk was begun.
     */
    private fun tickRest(player: LocalPlayer, level: ClientLevel): Boolean {
        val now = Util.getMillis()
        if (idleSinceMillis == 0L) idleSinceMillis = now
        // Rest cells painted into the area are the places to rest, and need no rest point; else the one rest point is.
        val painted = area?.paint()?.takeIf { it.restCount() > 0 }
        val point = config.restPoint
        if (now < restBlockedUntilMillis) return restWhy(now, "blocked for ${(restBlockedUntilMillis - now) / 1000}s after an unreachable spot")
        if (painted == null && (!config.restEnabled || !RestPoint.configured(point))) {
            return restWhy(
                now,
                "no rest place: paintedRestCells=${area?.paint()?.restCount() ?: -1} area='${config.paintArea}' restEnabled=${config.restEnabled} point=${if (RestPoint.configured(point)) "set" else "none"}",
            )
        }
        if (!RestPoint.due(now - idleSinceMillis, config.restDelaySeconds)) return restWhy(now, "not due idleMs=${now - idleSinceMillis} delay=${one(config.restDelaySeconds)}s")
        val random = ThreadLocalRandom.current()
        val spot: BlockPos
        val source: String
        if (painted != null) {
            source = "paint"
            val here = painted.typeAt(Mth.floor(player.x), Mth.floor(player.y), Mth.floor(player.z))
            if (here == Paint.REST && now < restWanderAtMillis) return false
            restWanderAtMillis = now + RestPoint.pauseMillis(random.nextDouble())
            val cell = painted.restCell(random.nextInt(painted.restCount())) ?: return false
            spot = BlockPos(cell[0], cell[1], cell[2])
        } else {
            source = "point"
            val centreX = point[0] + 0.5
            val centreZ = point[2] + 0.5
            if (!RestPoint.inside(player.x, player.z, centreX, centreZ, config.restRadius)) {
                spot = BlockPos(point[0], point[1], point[2])
            } else {
                if (now < restWanderAtMillis) return false
                restWanderAtMillis = now + RestPoint.pauseMillis(random.nextDouble())
                val drawn = RestPoint.spot(centreX, centreZ, config.restRadius, random.nextDouble(), random.nextDouble())
                spot = BlockPos.containing(drawn[0], point[1].toDouble(), drawn[1])
            }
        }
        val terrain = Terrain(level, player, config, area!!)
        val start = terrain.standUnder(player) ?: return false
        restSearch = PathSearch(terrain, start, PositionGoal(spot, terrain), config)
        diagnostics("rest walk to=${spot.x},${spot.y},${spot.z} source=$source idleMs=${now - idleSinceMillis}")
        return true
    }

    /** Says why the macro is not going to rest, every two seconds at most (the log is bounded), and returns false. */
    private fun restWhy(now: Long, reason: String): Boolean {
        if (now >= restWhyAtMillis) {
            restWhyAtMillis = now + REST_WHY_MILLIS
            diagnostics("rest check: $reason")
        }
        return false
    }

    private fun advanceRest(player: LocalPlayer) {
        val finished = restSearch ?: return
        val status = finished.step()
        if (status == PathSearch.Status.RUNNING) return
        restSearch = null
        val route = finished.path()
        if (status == PathSearch.Status.FOUND && route != null && route.length() >= 0.5) {
            diagnostics("rest route length=${one(route.length())}")
            startWalking(route, player, true)
        } else {
            diagnostics("rest spot not reachable looked=${finished.expansions()}")
            restBlockedUntilMillis = Util.getMillis() + REST_RETRY_MILLIS
        }
    }

    /** Whether to stop walking to the rest point: a mob has come into reach. Looked at every half second, not every tick. */
    private fun restInterrupted(player: LocalPlayer, level: ClientLevel): Boolean {
        if (++restPollTicks % REST_POLL_TICKS != 0) return false
        val seen = scanner.candidates(player, level, config.mobs, config.scanBlocks(), MAX_CANDIDATES) { entity -> onThisSideOfGate(player, entity) }
        if (seen.isEmpty()) return false
        // A mob the ground says cannot be got to does not end the walk to rest, or the walk would be begun and ended every half second.
        val terrain = Terrain(level, player, config, area!!)
        val start = terrain.standUnder(player) ?: return true
        return withoutUnreachable(terrain, start, seen, max(approachNow, PLANNER_MIN_APPROACH)).isNotEmpty()
    }

    private fun doWalk(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        wantTravelItem(player)
        if (target != null && canHit(player)) {
            beginEngage()
            return
        }
        if (clusterHold(player, level, input)) return
        val onWay = nearMobs.firstOrNull { it !== target && !it.isRemoved && it.isAlive && inReachOf(player, it, 0.0) }
        if (onWay != null) {
            // Another mob is within reach on the way to the target: it is killed first, not walked past.
            adopt(onWay)
            plannedTargetPosition = onWay.position()
            diagnostics("target on the way mob='${MobIds.label(onWay)}'")
            beginEngage()
            return
        }
        if (resting && target == null && restInterrupted(player, level)) {
            diagnostics("rest walk ended: a mob is in reach")
            resting = false
            runner.clear()
            rescanAtMillis = 0L
            state = State.SEARCHING
            return
        }
        if (directApproach(player, level, input)) return

        keepRouteFresh(player, level)
        if (state != State.WALKING) {
            // The search that just finished decided something else - engage, or give up.
            return
        }

        applySneak(input, "walk")
        val step = runner.tick(client, player, level, area!!, input, look, items)
        if (!runner.controlsView()) {
            steerView(player, input)
            // A mob that has been engaged keeps the player crouched until it is dead, also while the way back to it is
            // walked again.
            if (sneakNow()) input.sneak(true)
        }
        when (step) {
            RouteRunner.Step.WALKING, RouteRunner.Step.HOPPING -> Unit
            RouteRunner.Step.ARRIVED -> {
                // At the end of the route without being in range: the target has moved on. Plan again.
                if (resting) {
                    resting = false
                    restWanderAtMillis = Util.getMillis() + RestPoint.pauseMillis(ThreadLocalRandom.current().nextDouble())
                    diagnostics("rest walk arrived")
                }
                stuckStrikes = 0
                runner.clear()
                state = State.SEARCHING
            }
            RouteRunner.Step.STUCK, RouteRunner.Step.OFF_PATH, RouteRunner.Step.NO_PATH -> {
                diagnostics("route failed step=$step strikes=${stuckStrikes + 1}")
                routeFailed(player, level, input, step)
            }
        }
    }

    /**
     * Runs straight at the target while it is in sight and the ground between is a plain walk (see [DirectChase]), with the view on
     * it, and works out where it is again every tick: no route to go stale while a mob walks about, and no walking through it
     * because the route was drawn through where it used to be. Where the ground is not plain the route planner takes over, as it
     * always did. Returns whether this tick was spent on it.
     */
    private fun directApproach(player: LocalPlayer, level: ClientLevel, input: WalkInput): Boolean {
        val current = target ?: return false
        if (!config.directApproach) return false
        val terrain = Terrain(level, player, config, area!!)
        val result = chase.step(
            player, terrain, current, min(approachNow, MAX_STAND_OFF), config.scanBlocks(),
            config.sprint() && !sneakNow(), config.hybridSprintDistance(), input,
        )
        return when (result) {
            DirectChase.Result.MOVING -> {
                if (!directActive) {
                    directActive = true
                    runner.clear()
                    search = null
                    searchGoal = null
                    diagnostics("direct approach started mob='${MobIds.label(current)}' dist=${one(player.distanceTo(current).toDouble())}")
                }
                applySneak(input, "direct")
                aimAtTarget(current, "running at it")
                true
            }
            DirectChase.Result.CLOSE -> {
                if (directActive) {
                    directActive = false
                    diagnostics("direct approach arrived mob='${MobIds.label(current)}'")
                    runner.clear()
                    // Close enough but not in reach: a fresh look from the planner, not a route that was dropped.
                    if (!canHit(player)) state = State.SEARCHING else beginEngage()
                    true
                } else {
                    false
                }
            }
            DirectChase.Result.BLOCKED -> {
                if (directActive) {
                    directActive = false
                    runner.clear()
                    state = State.SEARCHING
                    diagnostics("direct approach ended: not a plain run mob='${MobIds.label(current)}'")
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Times a mob moved more than a block but less than its limit, so the route to it was left as it was; logged now and then. */
    private var skippedReplans = 0
    private var lastReplanLogMillis = 0L

    /** Keeps one search running in the background while walking, so the route follows a moving target. */
    private fun keepRouteFresh(player: LocalPlayer, level: ClientLevel) {
        if (search != null) {
            advanceSearch(player)
            return
        }
        val current = target
        if (current == null || runner.inFlight()) return

        val planned = plannedTargetPosition
        // A far mob has to move farther than a near one before the route to it is searched for again.
        val limit = ReplanRules.moveLimit(player.distanceTo(current).toDouble())
        val movedBy = planned?.let { current.position().distanceTo(it) } ?: Double.MAX_VALUE
        val moved = movedBy > limit
        if (--repathTicks > 0 && !moved) {
            if (movedBy > REPLAN_TARGET_MOVE && movedBy <= limit) skippedReplans++
            return
        }
        if (skippedReplans > 0 && Util.getMillis() - lastReplanLogMillis > SCAN_LOG_MILLIS) {
            lastReplanLogMillis = Util.getMillis()
            diagnostics("replan skipped count=$skippedReplans limit=${one(limit)} moved=${one(if (movedBy == Double.MAX_VALUE) 0.0 else movedBy)}")
            skippedReplans = 0
        }
        repathTicks = repathInterval(player, current)
        beginSearch(player, level, listOf(current))
    }

    /** How many off-path failures have come in a row, and when the first was. */
    private var offPathCount = 0
    private var offPathFirstMillis = 0L

    private var backOffTicks = 0
    private var backOffSide = 1.0f

    private fun routeFailed(player: LocalPlayer, level: ClientLevel, input: WalkInput, step: RouteRunner.Step) {
        input.stand(player.isInWater)
        runner.clear()
        resting = false
        if (step == RouteRunner.Step.STUCK && stuckStrikes >= 1 && player.horizontalCollision && !player.isInWater) {
            backOffTicks = BACK_OFF_TICKS
            backOffSide = if (ThreadLocalRandom.current().nextBoolean()) 1.0f else -1.0f
            diagnostics("back off from a tight place ticks=$backOffTicks strikes=$stuckStrikes")
        }
        val current = target
        val now = Util.getMillis()
        if (step == RouteRunner.Step.OFF_PATH) {
            if (offPathCount == 0 || now - offPathFirstMillis > StuckEscape.OFF_PATH_WINDOW_MILLIS) {
                offPathCount = 0
                offPathFirstMillis = now
            }
            offPathCount++
        } else {
            offPathCount = 0
        }
        // Off the path is not stuck: the route is worked out again from where the player is, and only a second time within a few seconds
        // counts as something to get past. A teleport for a player who was walking is a click spent for nothing.
        if (step == RouteRunner.Step.OFF_PATH && offPathCount < 2) {
            diagnostics("route off path replan count=$offPathCount")
            state = State.SEARCHING
            return
        }
        // A fence, a small block, a climb that needs an adjustment in the air: a warp or a hop onto the mob gets past what feet do not.
        if (current != null && config.useTeleport && stuckStrikes == 0 && StuckEscape.warpAllowed(step.name, offPathCount, now - offPathFirstMillis)) {
            val straight = player.distanceTo(current).toDouble()
            val cost = walkCostTo(current)
            if (StuckEscape.mobWarpUseful(cost, straight)) {
                val started = warpOnto(player, level, listOf(current))
                diagnostics("stuck escape ${if (started) "started" else "none"} mob='${MobIds.label(current)}' step=${step.name} walkCost=${one(cost)} straight=${one(straight)}")
                if (started) {
                    stuckStrikes++
                    return
                }
            } else {
                diagnostics("stuck escape skipped mob='${MobIds.label(current)}' step=${step.name} walkCost=${one(cost)} straight=${one(straight)}: walking is not much longer")
            }
        }
        if (current != null && ++stuckStrikes >= MAX_STUCK_STRIKES) {
            // Whatever is in the way is not going to move out of it. Someone else can have a turn.
            scanner.ignoreUnreachable(current)
            dropTarget()
            return
        }
        state = State.SEARCHING
    }

    private fun beginEngage() {
        idleSinceMillis = 0L
        runner.clear()
        stuckStrikes = 0
        finisherReadyMillis = 0L
        chase.reset()
        rollApproach()
        target?.let { current ->
            val chance = config.draw(config.sneakChanceMin, config.sneakChance)
            if (sneakRule.roll(current, chance, ThreadLocalRandom.current().nextDouble())) {
                diagnostics("sneak roll mob='${MobIds.label(current)}' chance=${one(chance)} crouch=${sneakRule.now(current)}")
            }
        }
        state = State.ENGAGING
    }

    /** Draws how close to stand to the target this time: the configured distance, give or take its variation. */
    private fun rollApproach() {
        val variation = config.draw(config.approachVariationMin, config.approachVariation)
        val drawn = config.approachDistance + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * variation
        approachNow = max(0.3, min(3.5, drawn))
    }

    private fun doEngage(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        val current = target
        if (current == null) {
            state = State.SEARCHING
            return
        }
        input.stand(player.isInWater)
        applySneak(input, "engage")

        // Close in on the target, aiming at it, up to the distance asked for - also while it can already be hit, so a mob
        // is hit on the way in and then followed, not waited for at the edge of reach. And when it is knocked out of
        // reach, run after it the same way instead of going back through the planner: a mob takes a step every tick, and a
        // route is for a place, not for something that moves.
        val canHit = canHit(player)
        precomputeNext(player, level)
        // A crowd within reach is fought from where the player stands: no step toward the target, the view moves between them.
        val crowd = updateCluster(player, level)
        if (clusterStanding) retargetInCrowd(player, crowd)
        var chased = DirectChase.Result.BLOCKED
        if (!clusterStanding && (canHit || config.chaseKnockedBack)) {
            val terrain = Terrain(level, player, config, area!!)
            chased = chase.step(
                player, terrain, current, min(approachNow, MAX_STAND_OFF), config.chaseRange,
                config.sprint() && !sneakNow(), config.hybridSprintDistance(), input,
            )
        }

        if (!canHit) {
            leftClicker.release(client)
            rightClicker.release(client)
            if (clusterStanding) {
                aimAtTarget(target ?: current, "crowd")
                return
            }
            if (chased == DirectChase.Result.MOVING) {
                aimAtTarget(current, "chasing")
                return
            }
            // Out of reach for a real reason, or not a straight run. A fresh search is both the new route and the decision
            // about whether this is still the best thing to be chasing.
            state = State.SEARCHING
            return
        }

        aimAtTarget(target ?: current, "fighting")
        if (wantsFinisher()) finishOff(client, player) else fight(client, player)
    }

    /**
     * Whether the player is crouched for the target it has. That is decided once per mob, when the fight with it begins -
     * with the configured chance - and then holds until the mob is dead: a crouch that stopped in the middle of a kill, or
     * started in it, is not how anyone fights. The next mob is a new draw.
     */
    private fun sneakNow(): Boolean = sneakRule.now(target)

    /**
     * Presses or lets go of the crouch key for the target there is now - in every state, so a crouch drawn for the last mob
     * cannot go on through the walk to the next - and says so when it changes.
     */
    private fun applySneak(input: WalkInput, reason: String) {
        val want = sneakNow()
        input.sneak(want)
        if (want != sneakApplied) {
            sneakApplied = want
            diagnostics("sneak ${if (want) "on" else "off"} reason=$reason mob='${target?.let { MobIds.label(it) }}'")
        }
    }

    /** Notes the target's height change once a tick; fast movement up or down makes the aim calm for a moment. */
    private fun trackTargetMotion() {
        val current = target
        if (current == null) {
            targetYSeen = false
            return
        }
        val y = current.y
        if (targetYSeen && abs(y - lastTargetY) > VERTICAL_SPEED) calmUntilMillis = Util.getMillis() + CALM_HOLD_MILLIS
        lastTargetY = y
        targetYSeen = true
    }

    /**
     * Puts the view on a spot of the target's hitbox. The spot is one of the ticked zones and changes now and then. While
     * the target is flying up or down - knocked into the air - the crosshair is not chased after it: see
     * [LookControl.lookAtEntity].
     */
    private fun aimAtTarget(current: Entity, reason: String = "aim") {
        traceLook("target", reason)
        val calm = Util.getMillis() < calmUntilMillis
        val spot = aim.spotFor(current, calm)
        look.lookAtEntity(current, spot.height, spot.sideX, spot.sideZ, calm)
    }

    /** Hits with the weapon, at the left click rate, while the crosshair is on the target. */
    private fun fight(client: Minecraft, player: LocalPlayer) {
        rightClicker.release(client)
        finisherReadyMillis = 0L
        if (huntLocked()) {
            // Not to be killed with the weapon, and the Black Hole is off: the mob is left to whoever may finish it.
            leftClicker.release(client)
            return
        }
        want(player, config.weaponSlot - 1)
        if (!items.ready(player)) {
            leftClicker.release(client)
            return
        }
        val clicks = leftClicker.tick(client, crosshairOnTarget(client, player))
        noteHits(clicks)
        if (clicks > 0) assumeOneTap(player)
    }

    /**
     * Changes to the Black Hole after the configured pause, then uses it on the target without a break: clicked at the
     * right click rate, or with the button held down. Nothing the server says stops it - a cooldown, a capture under way -
     * and the crosshair does not have to be on the mob for it, only the view: it goes on until the mob is gone.
     */
    private fun finishOff(client: Minecraft, player: LocalPlayer) {
        leftClicker.release(client)
        want(player, config.blackHoleSlot - 1)
        if (!items.ready(player)) {
            finisherReadyMillis = 0L
            rightClicker.release(client)
            return
        }

        val now = Util.getMillis()
        if (finisherReadyMillis == 0L) finisherReadyMillis = now
        if (now - finisherReadyMillis > FINISHER_PATIENCE_MILLIS) {
            // Long past anything a cooldown or a capture takes: the Black Hole is not working on this one at all - the slot
            // is empty, say. Back to the weapon rather than click at it for ever.
            finisherGaveUpOn = target
            finisherReadyMillis = 0L
            rightClicker.release(client)
            diagnostics("finisher gave up")
            return
        }
        crosshairOnTarget(client, player)
        lastHit = target
        lastHitMillis = now
        if (config.holdBlackHole) rightClicker.hold(client) else rightClicker.tick(client, true)
    }

    /**
     * With "assume one tap" on, a hit that would kill the mob counts as a kill at once and the macro moves on without
     * waiting for the mob to die on screen. The estimate is the player's attack damage against the mob's health and
     * absorption; armor, enchantments and the attack cooldown are not counted. Not used for a server's own creatures, whose
     * health the client is not told truthfully.
     */
    private fun assumeOneTap(player: LocalPlayer) {
        if (!config.assumeOneTap) return
        val current = target as? LivingEntity ?: return
        val level = player.level() as? ClientLevel ?: return
        if (!MobNames.healthFraction(level, current).isNaN()) return
        val health = current.health + current.absorptionAmount
        val damage = player.getAttributeValue(Attributes.ATTACK_DAMAGE)
        if (damage < health) return
        sessionKills++
        config.totalKills++
        lastHit = null
        diagnostics(
            "one-tap assumed kill mob='${MobIds.label(current)}' health=${one(health.toDouble())} " +
                "damage=${one(damage)} sessionKills=$sessionKills",
        )
        scanner.ignore(current, ONE_TAP_IGNORE_MILLIS)
        dropTarget()
        handOver(player, level, current)
    }

    private fun one(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    /**
     * The found mobs as the planner orders them, kept in [plan]. The nearest-first default hands every mob on to the
     * search, which picks the cheapest route; any other pattern hands on only the first of the tour.
     */
    private fun byPattern(player: LocalPlayer, scanned: List<Entity>): List<Entity> {
        logScan(player, scanned)
        // Mobs on another level wait while there are any on this one: they respawn, and the walk up and round and back is not a hunt.
        var found = if (scanned.size < 2) scanned else ClusterRules.onLevel(player.y, scanned.map { it.y }).map { scanned[it] }
        if (found.size < 2) {
            plan = found
            return found
        }
        // What the walk to each costs, from the map of the ground: a mob behind a wall is as far as the way round.
        val level = player.level()
        val costBegan = System.nanoTime()
        var costs = if (level is ClientLevel) walkCostsOf(player, level, found) else null
        costMillis = (System.nanoTime() - costBegan) / 1.0E6
        if (costs != null) {
            val straights = DoubleArray(found.size) { player.distanceTo(found[it]).toDouble() }
            val keep = DetourRule.keep(costs, straights)
            logChoice(player, found, costs, straights, keep)
            if (keep.any { it }) {
                // Mobs that cannot be walked to or are a long detour wait their turn; a few without a cost - far ones - only when there is little else.
                val costed = costs.count { !it.isNaN() && it.isFinite() }
                val kept = ArrayList<Entity>()
                val keptCosts = ArrayList<Double>()
                for (i in found.indices) {
                    if (!keep[i]) {
                        if (costs[i].isInfinite()) scanner.ignoreUnreachable(found[i])
                        continue
                    }
                    if (costs[i].isNaN() && costed >= UNCOSTED_DROP_FROM) continue
                    kept.add(found[i])
                    keptCosts.add(costs[i])
                }
                found = kept
                costs = keptCosts.toDoubleArray()
            } else {
                costs = null
            }
        }
        if (found.size < 2) {
            plan = found
            return found
        }
        // The stretches of ground the mobs stand on: one is cleared before the next is gone to.
        val segments = SegmentRules.cluster(
            DoubleArray(found.size) { found[it].x }, DoubleArray(found.size) { found[it].y }, DoubleArray(found.size) { found[it].z }, costs,
        )
        val ordered = planFor(player, found, costs, segments)
        // The search gets the few best mobs and picks the one cheapest to reach: the first three of the plan, or for the nearest-first
        // default with no view cone the three that cost least by distance and neighbours.
        val few = if (config.targetPattern == TargetPattern.NEAREST && config.viewCone <= 0.0) {
            TargetPlanner.nearestFirst(
                found.mapIndexed { i, it -> TargetPlanner.Mob(it, it.x, it.z, it.y, costs?.getOrNull(i) ?: Double.NaN, segments[i]) },
                player.x, player.z, player.y, GOAL_PLANNED,
                found.indices.minByOrNull { player.distanceToSqr(found[it]) }?.let { segments[it] } ?: -1,
            ).map { it.item }
        } else {
            ordered.take(GOAL_PLANNED)
        }
        diagnostics("goal planned=${few.size} first='${MobIds.label(few.first())}' pattern=${config.targetPattern.name} cone=${one(config.viewCone)}")
        return few
    }

    private var lastScanLogMillis = 0L

    /** What the scan saw, now and then: how many mobs there were in the radius, how many were kept, and how far the kept ones reach. */
    private fun logScan(player: LocalPlayer, scanned: List<Entity>) {
        val now = Util.getMillis()
        if (now - lastScanLogMillis < SCAN_LOG_MILLIS) return
        lastScanLogMillis = now
        val onLevel = if (scanned.size < 2) scanned.size else ClusterRules.onLevel(player.y, scanned.map { it.y }).size
        val far = scanned.maxOfOrNull { player.distanceTo(it).toDouble() } ?: 0.0
        val near = scanned.minOfOrNull { player.distanceTo(it).toDouble() } ?: 0.0
        diagnostics(
            "scan radius=${config.scanRadius} found=${scanner.lastFound(config.scanBlocks())} kept=${scanned.size} onLevel=$onLevel " +
                "nearest=${one(near)} farthest=${one(far)}",
        )
    }

    /** Plans the tour over these mobs from the player, and keeps it while it is still about as good. */
    private fun planFor(player: LocalPlayer, found: List<Entity>, costs: DoubleArray? = null, segments: IntArray? = null): List<Entity> {
        val motion = player.deltaMovement
        val heading = if (motion.x * motion.x + motion.z * motion.z > 0.0025) {
            Math.toDegrees(atan2(-motion.x, motion.z))
        } else {
            player.yRot.toDouble()
        }
        val mobs = found.mapIndexed { i, it -> TargetPlanner.Mob(it, it.x, it.z, it.y, costs?.getOrNull(i) ?: Double.NaN, segments?.getOrNull(i) ?: -1) }
        val made = TargetPlanner.plan(
            config.targetPattern, mobs, player.x, player.z, heading, ThreadLocalRandom.current(), plan.takeIf { it.isNotEmpty() },
            config.viewCone, player.y,
        )
        plan = made.order.map { it.item }
        diagnostics(
            "plan order=[${plan.take(TargetPlanner.DEPTH).joinToString(",") { MobIds.label(it) }}] cost=${one(made.cost)} " +
                "turn=${one(made.turnDegrees)} ${if (made.kept) "kept" else "replanned"} pattern=${config.targetPattern.name} " +
                "candidates=${found.size} inView=${inView(player, plan)} behind=${plan.size - inView(player, plan)} segments=${segments?.let { (it.maxOrNull() ?: -1) + 1 } ?: 0}",
        )
        return plan
    }

    /** How many of these mobs are inside the view cone from where the player looks. */
    private fun inView(player: LocalPlayer, mobs: List<Entity>): Int =
        mobs.count { LookFocus.offDegrees(player.yRot.toDouble(), player.x, player.z, it.x, it.z) <= LookFocus.ENTER_DEGREES }

    /** The mob that follows [current] in a tour that starts at it: what the next route is planned toward. */
    private fun nextAfter(player: LocalPlayer, current: Entity, found: List<Entity>): Entity? {
        if (found.size < 2) return found.firstOrNull()
        val heading = Math.toDegrees(atan2(-(current.x - player.x), current.z - player.z))
        val mobs = found.map { TargetPlanner.Mob(it, it.x, it.z, it.y) }
        return TargetPlanner.plan(config.targetPattern, mobs, current.x, current.z, heading, ThreadLocalRandom.current(), null, config.viewCone, current.y)
            .order.firstOrNull()?.item
    }

    /**
     * A kill ends the target and the next one starts in the same tick: the route that was worked out during the fight if it
     * is still good, otherwise the first mob of a fresh plan, which the walk toward starts at once (see [keepMoving]).
     */
    private fun handOver(player: LocalPlayer, level: ClientLevel, from: Entity?) {
        // A crowd round the player is finished before anyone is walked to: the next mob is the nearest in reach.
        nearAtMillis = 0L
        refreshNear(player, level)
        val adjacent = nearMobs.firstOrNull { it !== from && !it.isRemoved && it.isAlive && inReachOf(player, it, 0.0) }
        if (adjacent != null) {
            adopt(adjacent)
            plannedTargetPosition = adjacent.position()
            diagnostics("handover next='${MobIds.label(adjacent)}' crowd=true")
            return
        }
        if (prepared != null && usePrepared(player, level)) {
            diagnostics("handover next='${target?.let { MobIds.label(it) }}' prepared=true")
            return
        }
        val found = scanner.candidates(player, level, config.mobs, config.scanBlocks(), MAX_CANDIDATES) { entity ->
            entity !== from && onThisSideOfGate(player, entity)
        }
        if (found.isEmpty()) return
        val next = if (found.size < 2) found.also { plan = it }.first() else planFor(player, found).first()
        adopt(next)
        plannedTargetPosition = next.position()
        diagnostics("handover next='${MobIds.label(next)}' prepared=false")
    }

    /** The old route is kept when a fresh search ends in the same place: the target has not moved, so nothing is gained by turning. */
    private fun keepCurrentRoute(fresh: Path): Boolean {
        val old = runner.path() ?: return false
        if (runner.remaining() < KEEP_MIN_REMAINING) return false
        // A route the player is off is not kept: the fresh one starts from where they are.
        if (runner.deviation() > KEEP_MAX_DEVIATION) return false
        val apart = old.endPoint().distanceTo(fresh.endPoint())
        val keep = apart < KEEP_END_DISTANCE
        diagnostics(
            "route ${if (keep) "kept" else "replaced"} endApart=${one(apart)} remaining=${one(runner.remaining())} " +
                "oldLength=${one(old.length())} newLength=${one(fresh.length())}",
        )
        return keep
    }

    /** How many ticks to wait before planning again: a far target moves less in the player's way than a near one. */
    private fun repathInterval(player: LocalPlayer, mob: Entity): Int {
        val distance = player.distanceTo(mob)
        // Often: a mob moves, and at the speed a macro runs at a route that is half a second old is several blocks stale.
        val base = max(2, config.repathIntervalTicks / 2)
        return if (distance > 14.0f) base * 3 else if (distance > 7.0f) base * 2 else base
    }

    /** While the target is fought: picks the next mob and plans the way to it, a little every tick, once per target. */
    private fun precomputeNext(player: LocalPlayer, level: ClientLevel) {
        if (!config.precomputeNext) return
        if (nextSearch != null) {
            advanceNext()
            return
        }
        if (nextTried || prepared != null || search != null) return
        nextTried = true
        val current = target
        val found = scanner.candidates(player, level, config.mobs, config.scanBlocks(), MAX_CANDIDATES) { entity ->
            entity !== current && onThisSideOfGate(player, entity)
        }
        val pick = (if (current != null) nextAfter(player, current, found) else found.firstOrNull()) ?: return
        val terrain = Terrain(level, player, config, area!!)
        val start = terrain.standUnder(player) ?: return
        val goal = EntityGoal(listOf(pick), max(approachNow, PLANNER_MIN_APPROACH), terrain)
        val reach = WalkGraphs.reach(terrain, start)
        if (reach != null && reach.verdict(terrain, goal) == WalkGraph.Reach.Verdict.UNREACHABLE) {
            diagnostics("next target unreachable mob='${MobIds.label(pick)}'")
            return
        }
        nextGoal = goal
        nextStart = player.position()
        nextEntity = pick
        nextSearch = PathSearch(terrain, start, WalkGraphs.informed(goal, terrain, start), config)
        diagnostics("next search started mob='${MobIds.label(pick)}' distance=${one(player.distanceTo(pick).toDouble())}")
    }

    private fun advanceNext() {
        val working = nextSearch ?: return
        val status = working.step()
        if (status == PathSearch.Status.RUNNING) return
        val goal = nextGoal
        val from = nextStart
        val route = working.path()
        val entity = goal?.matched() ?: nextEntity
        nextSearch = null
        nextGoal = null
        if (status == PathSearch.Status.FOUND && route != null && entity != null && from != null) {
            prepared = Prepared(entity, route, from, Util.getMillis(), entity.position())
            diagnostics("next route ready mob='${MobIds.label(entity)}' length=${one(route.length())}")
        } else {
            nextEntity = null
            diagnostics("next route failed status=$status")
        }
    }

    private fun clearNext() {
        nextSearch = null
        nextGoal = null
        nextStart = null
        nextEntity = null
    }

    /** Starts on the route that was worked out during the last fight, if it is still good. Returns whether it did. */
    private fun usePrepared(player: LocalPlayer, level: ClientLevel): Boolean {
        val ready = prepared ?: return false
        prepared = null
        nextEntity = null
        val age = Util.getMillis() - ready.madeAtMillis
        val moved = ready.entity.position().distanceTo(ready.targetAt)
        val away = player.position().distanceTo(ready.from)
        val valid = age < PREPARED_MAX_AGE_MILLIS && away < PREPARED_MAX_PLAYER_MOVE && moved < REPLAN_TARGET_MOVE &&
            stillWorthKilling(player, level, ready.entity)
        diagnostics(
            "prepared route ${if (valid) "used" else "dropped"} mob='${MobIds.label(ready.entity)}' ageMs=$age " +
                "targetMoved=${one(moved)} playerMoved=${one(away)}",
        )
        if (!valid) return false
        adopt(ready.entity)
        plannedTargetPosition = ready.entity.position()
        if (ready.route.length() < 0.5) {
            beginEngage()
            return true
        }
        startWalking(ready.route, player)
        return true
    }

    /** Remembers that the target was just clicked at with the crosshair on it. */
    private fun noteHits(clicks: Int) {
        if (clicks > 0 && target != null) {
            lastHit = target
            lastHitMillis = Util.getMillis()
        }
    }

    /** Whether the target is low enough on health to be finished off, and the Black Hole is not already known to fail on it. */
    private fun wantsFinisher(): Boolean {
        if (!config.useBlackHole || target == null || target === finisherGaveUpOn) return false
        if (huntLocked()) return true
        return !targetHealth.isNaN() && targetHealth < config.finisherHealthPercent / 100.0
    }

    /**
     * How much health the target has left, once a tick. A server's own creatures say nothing true about their health to the
     * client - it is full, always - and show it in the name tag over them instead, so that is read first; the health the
     * game knows is for everything that has no such tag.
     */
    private fun updateTargetHealth(level: ClientLevel) {
        targetHealth = Double.NaN
        val current = target
        if (!config.useBlackHole || current == null) return
        var fraction = MobNames.healthFraction(level, current)
        if (fraction.isNaN() && current is LivingEntity && current.maxHealth > 0.0f) {
            fraction = (current.health / current.maxHealth).toDouble()
        }
        targetHealth = fraction
    }

    /**
     * Whether the crosshair is on the target right now. The pick is cast afresh and stored where the game looks for it: a
     * click acts on what the game last picked, which was before the view was turned.
     */
    private fun crosshairOnTarget(client: Minecraft, player: LocalPlayer): Boolean {
        val camera = client.cameraEntity ?: player
        val hit = player.raycastHitResult(1.0f, camera)
        client.hitResult = hit
        return hit is EntityHitResult && hit.entity === target
    }

    /** View on the target once it is close and visible, otherwise on the route ahead. */
    private fun steerView(player: LocalPlayer, input: WalkInput) {
        val current = target
        // The target is looked at when it is seen and more or less where the keys are taking the player (see [LookPlan]); a view on a mob
        // that lies far off the way the route goes left the player standing, turning, or strafing sideways to it.
        if (current != null && lookOnMob(player, current, input.heading())) {
            routeHeight.reset()
            aimAtTarget(current, "in view")
            return
        }
        val ahead = runner.cameraPoint()
        if (ahead == null) {
            routeHeight.reset()
            look.release()
            return
        }
        traceLook("route", if (current == null) "no target" else "target not in view")
        // Look along the route at about eye height: a player walking somewhere looks where they are going, not at the
        // ground in front of their feet.
        var point = ahead.add(0.0, player.eyeHeight - 0.25, 0.0)
        // On a long way the view leans a little toward the mob after this one, so it is already facing the next stretch of the
        // tour when this one ends; the walk itself does not depend on where the view points.
        val following = if (current != null && player.distanceToSqr(current) > FAR_VIEW_DISTANCE * FAR_VIEW_DISTANCE) {
            plan.firstOrNull { it !== current && it.isAlive && !it.isRemoved }
        } else {
            null
        }
        if (following != null) {
            val pull = following.position().add(0.0, following.bbHeight * 0.5, 0.0)
            point = point.add(pull.subtract(point).scale(NEXT_VIEW_PULL))
        }
        // How far above or below the eyes the view looks is eased: the route climbs a slab at a time and the eyes go up with each,
        // and a view that follows the height of every point exactly nods at each of them.
        val eye = player.eyePosition
        if (Math.hypot(point.x - player.x, point.z - player.z) < NEAR_LOOK_BLOCKS) {
            // A point right at the player swings the yaw with every bob and drift: the end of a route out of water is one. The
            // view keeps the way it points until the route has a point further ahead.
            look.lookAt(eye.add(player.lookAngle.scale(6.0)))
            return
        }
        look.lookAt(Vec3(point.x, eye.y + routeHeight.filter(point.y - eye.y), point.z))
    }

    // ------------------------------------------------------------------ crowds and mobs on the way

    private fun refreshNear(player: LocalPlayer, level: ClientLevel) {
        val now = Util.getMillis()
        if (now - nearAtMillis < NEAR_REFRESH_MILLIS) return
        nearAtMillis = now
        nearMobs = scanner.candidates(player, level, config.mobs, NEAR_RADIUS, MAX_CANDIDATES) { entity -> onThisSideOfGate(player, entity) }
    }

    /** Whether a mob can be hit from here, by the rules of a click, with its box grown by [margin] blocks. */
    private fun inReachOf(player: LocalPlayer, mob: Entity, margin: Double): Boolean =
        player.getAttackRangeWith(player.mainHandItem).isInRange(player, mob.boundingBox.inflate(margin), 0.0) && player.hasLineOfSight(mob)

    /**
     * Works out whether the player stands to fight a crowd now: two or more mobs close together (see [ClusterRules.CLUSTER_RADIUS]) with
     * one of them in reach. Returns the mobs that can be hit from here, nearest first.
     */
    private fun updateCluster(player: LocalPlayer, level: ClientLevel): List<Entity> {
        refreshNear(player, level)
        val alive = nearMobs.filter { !it.isRemoved && it.isAlive }.sortedBy { player.distanceToSqr(it) }
        val hittable = alive.map { inReachOf(player, it, 0.0) }
        val off = Util.getMillis() < clusterOffUntilMillis
        val size = if (off) 0 else ClusterRules.crowdSize(alive.map { doubleArrayOf(it.x, it.y, it.z) }, hittable)
        val was = clusterStanding
        clusterStanding = ClusterRules.standing(size, was)
        if (clusterStanding != was) diagnostics("cluster ${if (clusterStanding) "standing" else "walking on"} mobs=$size hittable=${hittable.count { it }} off=$off")
        if (!clusterStanding) clusterIdleTicks = 0
        return alive.filterIndexed { index, _ -> hittable[index] }
    }

    /** In a crowd the target is a mob that can be hit from here, the one already targeted if it is, else the nearest. */
    private fun retargetInCrowd(player: LocalPlayer, near: List<Entity>) {
        if (near.isEmpty()) return
        val current = target
        if (current != null && near.contains(current)) return
        val pick = near.first()
        adopt(pick)
        plannedTargetPosition = pick.position()
    }

    /**
     * Stands and fights where the player is while a crowd is within reach: no walking, the view on the nearest, the weapon out. Returns
     * whether this tick was spent on it.
     */
    private fun clusterHold(player: LocalPlayer, level: ClientLevel, input: WalkInput): Boolean {
        val near = updateCluster(player, level)
        if (!clusterStanding) return false
        retargetInCrowd(player, near)
        val current = target ?: return false
        runner.clear()
        search = null
        searchGoal = null
        directActive = false
        input.stand(player.isInWater)
        applySneak(input, "crowd")
        if (canHit(player)) {
            clusterIdleTicks = 0
            if (state != State.ENGAGING) beginEngage()
        } else {
            aimAtTarget(current, "crowd")
            // A stand with nothing to hit is not a fight: walk on, and do not stand again for a moment.
            if (ClusterRules.giveUp(++clusterIdleTicks)) {
                clusterStanding = false
                clusterIdleTicks = 0
                clusterOffUntilMillis = Util.getMillis() + ClusterRules.COOLDOWN_MILLIS
                diagnostics("cluster gave up: nothing to hit for ${ClusterRules.IDLE_TICKS} ticks")
                return false
            }
        }
        return true
    }

    /** Whether a mob other than the target is near the stretch of this route between these two distances along it. */
    private fun mobsOnWay(route: Path, from: Double, to: Double): Boolean {
        if (nearMobs.isEmpty()) return false
        val points = ArrayList<DoubleArray>()
        var along = from
        while (along <= to) {
            val point = route.pointAt(along)
            points.add(doubleArrayOf(point.x, point.y, point.z))
            along += ROUTE_SAMPLE
        }
        val mobs = nearMobs.filter { it !== target && !it.isRemoved && it.isAlive }.map { doubleArrayOf(it.x, it.y, it.z) }
        return ClusterRules.mobsOnStretch(points, mobs)
    }

    /** Whether the target can be hit from where the player stands, by the rules the click will use. */
    private fun canHit(player: LocalPlayer): Boolean {
        val current = target ?: return false
        return player.getAttackRangeWith(player.mainHandItem).isInRange(player, current.boundingBox, 0.0) &&
            player.hasLineOfSight(current)
    }

    private fun adopt(next: Entity?) {
        if (target === next) return
        target = next
        if (next != null) resting = false
        nextTried = false
        approachSinceMillis = 0L
        stuckStrikes = 0
        finisherReadyMillis = 0L
        aim.reset()
        chase.reset()
        focus.reset()
        targetYSeen = false
        calmUntilMillis = 0L
        if (next != null) {
            rollApproach()
            Minecraft.getInstance().player?.let { idleHeading = Math.toDegrees(atan2(-(next.x - it.x), next.z - it.z)) }
            diagnostics("target mob='${MobIds.label(next)}'")
        }
        if (finisherGaveUpOn !== next) finisherGaveUpOn = null
    }

    private fun dropTarget() {
        adopt(null)
        plannedTargetPosition = null
        search = null
        searchGoal = null
        directActive = false
        runner.clear()
        look.release()
        state = State.SEARCHING
    }

    /** Back to a clean slate with the keyboard and the mouse handed back to the player. */
    private fun reset(client: Minecraft) {
        WorldStore.deactivate()
        AreaMemory.deactivate()
        leftClicker.release(client)
        rightClicker.release(client)
        runner.release(client)
        inputSwap.release()
        look.release()
        runner.clear()
        scanner.clear()
        items.clear()
        search = null
        searchGoal = null
        restSearch = null
        resting = false
        idleSinceMillis = 0L
        restWanderAtMillis = 0L
        restBlockedUntilMillis = 0L
        backOffTicks = 0
        routeHeight.reset()
        clearNext()
        prepared = null
        target = null
        plannedTargetPosition = null
        stuckStrikes = 0
        failedSearches = 0
        rescanAtMillis = 0L
        finisherGaveUpOn = null
        finisherReadyMillis = 0L
        sneakRule.clear()
        sneakApplied = false
        focus.reset()
        lastLook = ""
        searchMover = null
        directActive = false
        idleHeading = Double.NaN
        plan = emptyList()
        movingWhileSearching = false
        targetYSeen = false
        calmUntilMillis = 0L
        aim.reset()
        chase.reset()
        state = State.SEARCHING
    }

    companion object {
        /** Most targets one search will consider at once. Each one costs a check per expanded node. */
        private const val MAX_CANDIDATES = 24

        /** How many of the nearest mobs have the walk to them costed, and from how many costed mobs the others - far ones - are left out. */
        private const val COST_POOL = 12
        private const val UNCOSTED_DROP_FROM = 3
        private const val CHOICE_LOG_MILLIS = 1000L

        /** How many of the planned mobs the search gets as one goal. */
        private const val GOAL_PLANNED = 3
        private const val SCAN_LOG_MILLIS = 3000L
        private const val ONE_TAP_IGNORE_MILLIS = 1500L
        private const val PREPARED_MAX_AGE_MILLIS = 6000L
        private const val PREPARED_MAX_PLAYER_MOVE = 3.5
        private const val KEEP_MIN_REMAINING = 1.5
        private const val KEEP_END_DISTANCE = 2.0
        private const val EMPTY_SCAN_PAUSE_MILLIS = 400L

        /** The pause after the mobs tried could not be got to: short, because other mobs may be waiting. */
        private const val FAILED_SEARCH_PAUSE_MILLIS = 100L

        /** How much higher than the best jump a mob has to be for the way up to be a teleport, not a walk. */
        private const val LEDGE_EXTRA = 0.5

        /** A route point nearer than this, flat, is not looked at: see [steerView]. */
        private const val NEAR_LOOK_BLOCKS = 2.5

        /** After a rest spot that could not be got to, how long before the next try, and how often a walk to one is checked for a mob in reach. */
        private const val REST_RETRY_MILLIS = 15_000L
        private const val REST_POLL_TICKS = 10

        /** How often a held idle look checks for a mob coming into view, in ticks, and how long a person takes to notice one. */
        private const val IDLE_POLL_TICKS = 4
        private const val IDLE_NOTICE_MIN_MILLIS = 150L
        private const val IDLE_NOTICE_MAX_MILLIS = 350L
        private const val REST_WHY_MILLIS = 2_000L

        /** Getting off a corner: this many ticks of walking back and a little to one side, at this angle off straight back. */
        private const val BACK_OFF_TICKS = 7
        private const val BACK_OFF_DEGREES = 40.0f

        /** How long a target that cannot be reached is left alone. */

        /** How far a target may wander from where the route was planned before it is planned again. */
        private const val REPLAN_TARGET_MOVE = 1.0

        /** Failed routes in a row before the target is written off rather than approached again. */
        private const val MAX_STUCK_STRIKES = 3

        /** The aim line is written every this many ticks while the macro runs. */
        private const val AIM_TRACE_TICKS = 5

        /** Beyond this distance to the target the view leans toward the next mob of the plan, by this share of the way. */
        private const val FAR_VIEW_DISTANCE = 12.0
        private const val NEXT_VIEW_PULL = 0.2

        /**
         * The planner never stops further in than this, whatever the approach distance: the last stretch to a mob is the
         * direct chase's, which follows a mob that moves, and a planner asked for "right next to it" is asked for a stand
         * that may not exist.
         */
        private const val PLANNER_MIN_APPROACH = 1.2

        /** The closest the approach distance is allowed to be set to stand from, so that something can still be hit. */
        private const val MAX_STAND_OFF = 2.7

        /** A target that moves this far up or down in one tick is flying; the aim stays calm for a while after. */
        private const val VERTICAL_SPEED = 0.12
        private const val CALM_HOLD_MILLIS = 600L

        /** How long the Black Hole may be clicked on one mob, once in hand, before going back to the weapon. */
        private const val FINISHER_PATIENCE_MILLIS = 20_000L

        /**
         * A mob counts as killed when it dies or disappears this soon after it was last hit by the macro, near the player.
         * One that walks off or is left behind is not a kill, and neither is one that went with its chunk.
         */
        private const val KILL_CREDIT_MILLIS = 3000L
        private const val KILL_CREDIT_DISTANCE = 12.0

        /** How long a target the failsafe gave up on is left alone, and how long after a hit the failsafe waits. */
        private const val FAILSAFE_IGNORE_MILLIS = 20_000L
        private const val FAILSAFE_HIT_GRACE_MILLIS = 1500L

        /**
         * How often in a row the macro warps onto or toward the same target before it leaves it alone, as one it cannot get
         * to: out of a pool, then onto a ledge, then next to the mob is three.
         */
        private const val MAX_DIRECT_WARPS = 3

        /** How far round the player mobs are looked for to tell a crowd from a lone mob, and how often that is looked again. */
        private const val NEAR_RADIUS = 14.0
        private const val SHUT_LOG_MILLIS = 2000L
        private const val DEVIATION_LOG_BLOCKS = 1.0
        private const val KEEP_MAX_DEVIATION = 1.0

        /** A mob on the list this close keeps a warp along the route from being taken. */
        private const val WARP_MOB_RADIUS = 20.0
        private const val NEAR_REFRESH_MILLIS = 100L
        private const val ROUTE_SAMPLE = 2.0

        /** Hops toward one mob in a row: they are short, and a chain of them is how a mob across a gap or up a wall is reached. */
        private const val MAX_DIRECT_HOPS = 6

        /** How many mobs of the plan a hop is looked for toward, nearest first. */
        private const val HOP_CANDIDATES = 3

        /**
         * How long a mob the server says is being hunted with the Huntaxe or captured by the Black Hole is not hit with the
         * weapon, after the last time it said so: it is the Black Hole's.
         */
        private const val HUNT_LOCK_MILLIS = 6000L

        /** With nothing to do, how long the view rests on one thing before it looks at another: drawn between these. */
        private const val IDLE_LOOK_MIN_MILLIS = 1200L
        private const val IDLE_LOOK_MAX_MILLIS = 3200L

        /** How far ahead the idle look's point is put; the angles are what count, not the distance. */
        private const val IDLE_LOOK_RANGE = 16.0

        private var instance: AreaKillerMacro? = null

        /** Where the macro's events go for the module's bounded diagnostics log; set by the module. */
        @JvmField
        var diagnostics: (String) -> Unit = {}

        @JvmStatic
        fun init(config: AreaKillerConfig) {
            val macro = AreaKillerMacro(config)
            instance = macro
            ClientTickEvents.START_CLIENT_TICK.register { macro.tick(it) }
            ClientReceiveMessageEvents.GAME.register { message, _ -> macro.onServerMessage(message.string) }
        }

        @JvmStatic
        fun isRunning(): Boolean = instance?.running == true

        @JvmStatic
        fun start() {
            instance?.begin()
        }

        @JvmStatic
        fun stop(reason: String) {
            val macro = instance ?: return
            if (macro.running) macro.halt(reason)
        }

        /** The route being walked, for the overlay. */
        @JvmStatic
        fun currentPath(): Path? = instance?.runner?.path()

        /** Whether the macro is running straight at its target now, with no route: the overlay draws the line itself. */
        fun isDirect(): Boolean = instance?.directActive == true

        /** How far along [currentPath] the player is, in blocks. */
        @JvmStatic
        fun currentProgress(): Double = instance?.runner?.progress() ?: 0.0

        /** What is being hunted, for the overlay. */
        @JvmStatic
        fun currentTarget(): Entity? = instance?.target

        /** The mob after this one, once picked, for the overlay; null when none is. */
        @JvmStatic
        fun nextTarget(): Entity? {
            val macro = instance ?: return null
            return macro.prepared?.entity ?: macro.nextEntity?.takeIf { macro.nextSearch != null }
                ?: macro.plan.firstOrNull { it !== macro.target && it.isAlive && !it.isRemoved }
        }

        /** The route worked out to the next mob, once it is ready, for the overlay. */
        @JvmStatic
        fun nextPath(): Path? = instance?.prepared?.route

        /** The area being enforced, or null before the first start. */
        @JvmStatic
        fun currentArea(): GateArea? = instance?.area

        /** How long the session has been running, or how long the last one ran; 0 before the first. */
        @JvmStatic
        fun sessionMillis(): Long {
            val macro = instance
            if (macro == null || macro.sessionStartMillis == 0L) return 0L
            return (if (macro.running) Util.getMillis() else macro.sessionEndMillis) - macro.sessionStartMillis
        }

        /** The mobs killed in this session, or in the last one. */
        @JvmStatic
        fun sessionKills(): Int = instance?.sessionKills ?: 0

        /** What the macro is doing right now in a few words, or null when it is not running. For the HUD. */
        @JvmStatic
        fun hudLine(): String? {
            val macro = instance
            if (macro == null || !macro.running) return null
            val target = macro.target
            return when (macro.state) {
                State.SEARCHING -> if (target == null) "Searching" else "Planning route"
                State.WALKING -> (
                    if (macro.runner.controlsView()) {
                        "Teleporting"
                    } else if (target == null) {
                        "Walking"
                    } else {
                        "Approaching " + MobIds.label(target)
                    }
                    ) + String.format(" · %.0f m", macro.runner.remaining())
                State.ENGAGING -> if (target == null) {
                    "Fighting"
                } else {
                    (if (macro.capturing()) "Capturing " else if (macro.wantsFinisher()) "Finishing " else "Fighting ") +
                        MobIds.label(target)
                }
                State.WARPING -> if (target == null) "Warping" else "Warping to " + MobIds.label(target)
            }
        }

        /** A duration as a clock: 04:05, or 1:04:05 once it is an hour or more. */
        @JvmStatic
        fun clock(millis: Long): String {
            val seconds = max(0L, millis / 1000L)
            val hours = seconds / 3600L
            return if (hours > 0L) {
                String.format("%d:%02d:%02d", hours, seconds / 60L % 60L, seconds % 60L)
            } else {
                String.format("%02d:%02d", seconds / 60L, seconds % 60L)
            }
        }

        /** One line about the state of the macro, for the status command. */
        @JvmStatic
        fun describe(): String {
            val macro = instance ?: return "not initialised"
            val where = macro.area?.let { ", " + it.describe() } ?: ""
            val learnt = if (macro.running && macro.config.learnArea) ", area memory: " + AreaMemory.describe() else ""
            return (
                if (macro.running) {
                    "running (" + macro.state.name.lowercase(Locale.getDefault()) + "), " + macro.sessionSummary()
                } else {
                    "stopped"
                }
                ) + where + learnt
        }

        /** The kill list, the statistics and every setting are in the Macros workspace file. */
        private fun saveConfig() = MacroWorkspaceConfig.save()

        private fun message(text: String) {
            diagnostics(text)
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§6[Area Killer] §r$text"))
        }
    }
}
