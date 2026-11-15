package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.input.ItemSwitcher
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.input.WalkInput
import geiler.addons.client.macros.pathing.AreaMemory
import geiler.addons.client.macros.pathing.Path
import geiler.addons.client.macros.pathing.Region
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.rotation.LookControl
import geiler.addons.client.macros.rotation.SmoothRotation
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.util.Util
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.min

/**
 * Follows a finished route, on foot and - when it pays - by teleport.
 *
 * Walking is [PathWalker]'s. On top of it the runner uses the two modes of the teleport item, both worked out by
 * [HopPlanner] and both fired with one simulated right click:
 *
 * - A **jump** carries the player a fixed distance along the view when nothing is in the way. A stretch of route that is
 *   clear for that distance is crossed in one go.
 * - A **warp** (sneak and right click) puts the player on top of the block looked at, up to a long range, if there is room
 *   above it. On a long route the runner picks a block somewhere along it - never right at the end - crouches, turns to it
 *   and warps.
 *
 * Both are planned from where the player will be when the click is acted on, not where they are: the click is read next
 * tick and the server acts on it a little later still, and in the air the player does not stand still in between. A second
 * jump straight after a first that ended in the air is therefore aimed from the lower spot the fall has taken the player to
 * by then.
 *
 * Water is left by teleport, always. Climbing out of it with the jump key depends on the height of the edge, the depth of
 * the water and the tick the key goes down on, and it fails often enough to be worth nothing. So a player in water with
 * dry ground on the route ahead warps or jumps onto that ground - however short the route is, and whatever the minimum
 * distances for the warp are - and only goes on swimming when there is no such ground or no teleport item.
 *
 * A teleport item that does nothing is not something to keep clicking. If two tries in a row do not move the player,
 * teleporting is switched off for the rest of the trip and a note says so.
 */
class RouteRunner(private val config: AreaKillerConfig) {
    enum class Step {
        /** Following the route on foot. */
        WALKING,

        /** Aiming, firing or waiting on a jump or a warp. */
        HOPPING,

        /** The end of the route is reached. */
        ARRIVED,

        /** No progress for a while: something is in the way that the route does not know about. */
        STUCK,

        /** Too far off the line to keep following it. */
        OFF_PATH,

        /** Nothing to follow. */
        NO_PATH,
    }

    private enum class Hop {
        IDLE,
        WAITING_FOR_LANDING,
    }

    private enum class Pick {
        CLEAR,
        BLOCKED,
        GAVE_UP,
    }

    private val walker = PathWalker()

    /** A warp straight onto a landing is under way, with no route: see [startDirectWarp]. */
    private var direct = false
    private var hop = Hop.IDLE
    private var clickHeld = false
    private var viewHeld = false
    private var sneaking = false
    private var warp: HopPlanner.EtherPlan? = null

    /** An Instant Transmission hop straight onto a landing is under way, with no route: see [startDirectHop]. */
    private var directHop: InstantHops.Hop? = null

    /** A hop is being turned to, and the route is walked meanwhile: the view stays the runner's. Set by [tryJump], read by [tick]. */
    private var walkWhileAiming = false

    /** Whether the warp being aimed was planned to get out of water, and is to be finished as that whatever happens to the water. */
    private var warpLeavesWater = false
    private var wetTicks = 0
    private var exitWaitTicks = 0
    private var sneakTicks = 0
    private var clickedFrom: Vec3? = null
    private var expectedMove = 0.0
    private var waitedTicks = 0
    private var plannedProgress = 0.0
    private var lastLandingMillis = 0L

    /** The pause before the next jump or warp, drawn between the configured bounds after every landing. */
    private var pauseMillis = 0L
    private var nextPlanMillis = 0L
    private var nextWarpPlanMillis = 0L
    private var blockedTicks = 0
    private var failures = 0
    private var teleportDisabled = false
    private var note: String? = null

    /** Where the click in flight is expected to put the player, for the line that says how near it came. */
    private var expectedLanding: Vec3? = null

    /** Teleports that landed more than [LANDING_ERROR] blocks from where they were worked out to, in this trip. */
    private var landingErrors = 0

    /** Whether the number of hops the stretch needs has been written to the log for the hop being made. */
    private var hopCounted = false

    /** What share of the item's reach is relied on: it shrinks when landings keep falling short of the plan. */
    private var reachScale = 1.0

    /**
     * Whether the stretch of route between these two distances along it is better walked: the owner knows of something to do on it
     * (mobs to kill on the way). Asked before a teleport over it is begun; the default has nothing against any.
     */
    var shortcutBlocked: (Path, Double, Double) -> Boolean = { _, _, _ -> false }
    private var lastSkipLogMillis = 0L

    /** Writes that a teleport was left for the walk, at most once in a while. */
    private fun skipped(now: Long, reason: String) {
        if (now - lastSkipLogMillis < SKIP_LOG_MILLIS) return
        lastSkipLogMillis = now
        log("teleport skipped reason='$reason'")
    }

    /** Diagnostics sink of the macro that owns this runner. */
    var log: (String) -> Unit = {}

    /** The reach the plans are worked out for: the item's, less what the trip has shown it falls short by. */
    private fun reach(): Double = config.teleportDistance * reachScale

    init {
        drawPause()
    }

    private fun replanPause(now: Long): Long =
        if (now - lastLandingMillis < RECENT_LANDING_MILLIS) QUICK_REPLAN_PAUSE_MILLIS else REPLAN_PAUSE_MILLIS

    private fun drawPause() {
        val low = config.teleportDelayMinMillis
        val high = max(low, config.teleportDelayMaxMillis)
        pauseMillis = low + (ThreadLocalRandom.current().nextDouble() * (high - low)).toLong()
    }

    fun follow(route: Path?, position: Vec3) {
        walker.follow(route, position)
        hop = Hop.IDLE
        viewHeld = false
        warp = null
        direct = false
        directHop = null
    }

    /** Forgets the route. A button still held is let go of by the next [release]. */
    fun clear() {
        walker.clear()
        hop = Hop.IDLE
        viewHeld = false
        warp = null
        direct = false
        directHop = null
    }

    /**
     * Starts a hop with the teleport item - a right click without sneaking - along this look, with no route under it: for a mob the
     * feet cannot get to but a jump of the item's length can. Aimed and clicked by [tickDirectWarp] like a warp, without the crouch.
     */
    fun startDirectHop(plan: InstantHops.Hop) {
        walker.clear()
        hop = Hop.IDLE
        warp = null
        directHop = plan
        direct = true
        blockedTicks = 0
        hopWaitTicks = 0
    }

    private fun tickDirectHop(
        client: Minecraft, player: LocalPlayer, input: WalkInput, look: LookControl, items: ItemSwitcher, plan: InstantHops.Hop,
    ): Step {
        if (!config.useTeleport || teleportDisabled) {
            directHop = null
            direct = false
            return Step.NO_PATH
        }
        input.stand(false)
        if (!items.holding(player, config.teleportSlot - 1)) return if (gaveUpWaiting("item not in hand")) failDirect() else Step.HOPPING
        viewHeld = true
        look.lookAlong(plan.yaw.toFloat(), plan.pitch.toFloat(), false)
        if (look.residualDegrees(player) > HOP_AIM_TOLERANCE_DEGREES) {
            return if (gaveUpWaiting("aim residual=${fmt(look.residualDegrees(player).toDouble())}")) failDirect() else Step.HOPPING
        }
        if (!gapPassed(false)) return Step.HOPPING
        val pick = pick(client, player, Util.getMillis(), null)
        if (pick == Pick.BLOCKED) return if (gaveUpWaiting("blocked by what is under the crosshair")) failDirect() else Step.HOPPING
        if (pick == Pick.GAVE_UP) {
            directHop = null
            direct = false
            return Step.NO_PATH
        }
        fire(client, player, 0.0, plan.length, Vec3(plan.landX, plan.landY, plan.landZ), false)
        return Step.HOPPING
    }

    /**
     * Starts a warp straight onto this landing, with no route under it: for a mob the feet cannot get to. It is made the way
     * any warp is - the item in hand, crouched, the view on the block, the click - by [tickDirectWarp].
     */
    fun startDirectWarp(plan: HopPlanner.EtherPlan) {
        walker.clear()
        hop = Hop.IDLE
        warp = plan
        direct = true
        sneakTicks = 0
        blockedTicks = 0
        hopWaitTicks = 0
    }

    /**
     * One tick of a direct warp. HOPPING while it is being made or is on its way, WALKING once the player has landed, and
     * NO_PATH when it cannot be made after all - the block cannot be seen any more, something stays under the crosshair, or
     * the item did not move the player.
     */
    fun tickDirectWarp(
        client: Minecraft, player: LocalPlayer, level: ClientLevel, region: Region, input: WalkInput, look: LookControl, items: ItemSwitcher,
    ): Step {
        if (clickHeld) release(client)
        if (hop == Hop.WAITING_FOR_LANDING) {
            val before = teleportDisabled
            val failuresBefore = failures
            val step = awaitLanding(client, player, input)
            if (step == Step.HOPPING) return step
            direct = false
            directHop = null
            sneaking = false
            input.sneak(false)
            // Landed, or the item did nothing: the latter is a failure like any other teleport's.
            return if (failures > failuresBefore || teleportDisabled != before) Step.NO_PATH else Step.WALKING
        }
        directHop?.let { return tickDirectHop(client, player, input, look, items, it) }
        val plan = warp
        if (plan == null || !config.useTeleport || teleportDisabled) {
            direct = false
            return Step.NO_PATH
        }
        input.stand(false)
        if (!items.holding(player, config.teleportSlot - 1)) return if (gaveUpWaiting("item not in hand")) failDirect() else Step.HOPPING
        val now = Util.getMillis()
        // From where the player is now: the click goes out in this tick before the movement of this tick does, so the server
        // casts it from here, whatever the ping. In water, crouched, the player sinks, and the eyes move with every tick;
        // the aim is worked out afresh each time, and the click goes when the view as it is meets the block.
        val origin = HopPlanner.etherOrigin(player, player.position())
        if (!HopPlanner.landsOn(level, player, origin, plan.aimPoint, config.etherRange - 1.0, plan.floor)) {
            warp = null
            direct = false
            return Step.NO_PATH
        }
        input.sneak(true)
        sneaking = true
        sneakTicks++
        viewHeld = true
        val angles = SmoothRotation.lookAt(origin, plan.aimPoint)
        look.lookAlong(angles.yaw, angles.pitch, true)
        if (sneakTicks < ETHER_SNEAK_TICKS || !player.isShiftKeyDown || !warpAimed(level, player, look, origin)) {
            return if (gaveUpWaiting(warpWaitReason(player, look))) failDirect() else Step.HOPPING
        }
        if (!gapPassed(true)) return Step.HOPPING
        val pick = pick(client, player, now, plan.floor)
        if (pick == Pick.BLOCKED) return if (gaveUpWaiting("blocked by what is under the crosshair")) failDirect() else Step.HOPPING
        if (pick == Pick.GAVE_UP) {
            direct = false
            return Step.NO_PATH
        }
        val verdict = checkWarp(player, level, region, plan)
        if (!verdict.ok) return if (gaveUpWaiting("warp check refused: ${verdict.reason}")) failDirect() else Step.HOPPING
        fire(client, player, 0.0, plan.landing.distanceTo(player.position()), plan.landing, true)
        return Step.HOPPING
    }

    /** Starts a trip with teleporting allowed again, even if an earlier one switched it off. */
    fun resetHops() {
        teleportDisabled = false
        reachScale = 1.0
        landingErrors = 0
        failures = 0
        drawPause()
        nextPlanMillis = 0L
        nextWarpPlanMillis = 0L
        note = null
    }

    /** Lets go of the right mouse button if a jump or a warp left it down. */
    fun release(client: Minecraft) {
        if (clickHeld) {
            SimulatedMouse.release(client, SimulatedMouse.Button.RIGHT)
            clickHeld = false
        }
    }

    fun path(): Path? = walker.path()

    fun remaining(): Double = walker.remaining()

    /** How far the player is from the route being walked, in blocks. */
    fun deviation(): Double = walker.deviation()

    /** How far along the route the player is, in blocks. */
    fun progress(): Double = walker.progress()

    fun cameraPoint(): Vec3? = walker.cameraPoint()

    /** The least route left for a warp along it that the macro asks for, 0 for none; and whether a planned warp is worth taking (remaining route, flight, route gain). */
    @JvmField
    var warpMinRoute = 0.0

    @JvmField
    var warpWorthIt: (Double, Double, Double) -> Boolean = { _, _, _ -> true }

    /** Whether the runner is turning the view itself this tick, so nobody else should. */
    fun controlsView(): Boolean = viewHeld

    /** Milliseconds since the last teleport landed; a large number when none has. */
    fun sinceLandingMillis(): Long = if (lastLandingMillis == 0L) Long.MAX_VALUE else Util.getMillis() - lastLandingMillis

    /** Whether a jump or a warp is on its way, and the route must not be replaced until it has landed. */
    fun inFlight(): Boolean = hop == Hop.WAITING_FOR_LANDING

    /** The 0-based slot of the teleport item when there is a stretch of route ahead to cross with it, otherwise -1. */
    fun desiredItem(player: LocalPlayer): Int {
        if (direct) return config.teleportSlot - 1
        if (!config.useTeleport || teleportDisabled || walker.path() == null) return -1
        if (leavesWater(player)) return config.teleportSlot - 1
        var needed = config.teleportDistance + ITEM_MARGIN
        if (config.useEtherwarp) needed = min(needed, warpRouteMin())
        return if (walker.remaining() >= needed) config.teleportSlot - 1 else -1
    }

    /** In water, or just out of it. */
    private fun wet(player: LocalPlayer): Boolean = player.isInWater || wetTicks > 0

    /** Whether the player is to teleport out of water: they are in it, and the route has dry ground to put them on. */
    private fun leavesWater(player: LocalPlayer): Boolean = wet(player) && dryAhead(player.level())

    /**
     * Whether the player is to stay put because they are leaving the water by teleport and it has not gone off yet. Only for
     * so long: a teleport that cannot be made at all - no line to anywhere dry, an item that is not there - must not hold
     * the player in the water for ever, so after [EXIT_PATIENCE_TICKS] the walk goes on as it used to.
     */
    private fun waitsToLeaveWater(player: LocalPlayer): Boolean {
        if (!config.useTeleport || teleportDisabled || !leavesWater(player)) {
            exitWaitTicks = 0
            return false
        }
        return ++exitWaitTicks <= EXIT_PATIENCE_TICKS
    }

    /**
     * Whether the route has a place without water in it, far enough along to be worth a teleport and short of its end.
     * Looked up in the world rather than planned, because the item is brought to hand on the strength of this, and a route
     * that ends in the water - a mob swimming in it - is fought in the water, not left.
     */
    private fun dryAhead(level: Level): Boolean {
        val route = walker.path() ?: return false
        val end = route.length() - EXIT_STAND_OFF
        var along = walker.progress() + EXIT_MIN_DISTANCE
        while (along <= end) {
            val point = route.pointAt(along)
            val feet = BlockPos.containing(point.x, point.y + 0.1, point.z)
            if (level.getFluidState(feet).isEmpty && level.getFluidState(feet.above()).isEmpty) return true
            along += 1.0
        }
        return false
    }

    /** Something the player should be told, once: teleporting was given up, say. Null when there is nothing. */
    private var edgeBrakesSeen = 0
    private var edgeLogMillis = 0L

    fun consumeNote(): String? {
        val pending = note
        note = null
        return pending
    }

    fun tick(
        client: Minecraft, player: LocalPlayer, level: ClientLevel, region: Region, input: WalkInput, look: LookControl, items: ItemSwitcher,
    ): Step {
        if (clickHeld) release(client)
        if (player.isInWater) {
            wetTicks = WET_GRACE_TICKS
        } else if (wetTicks > 0) {
            wetTicks--
        }
        // Crouching belongs to a warp in progress and to nothing else.
        if (sneaking && warp == null) {
            input.sneak(false)
            sneaking = false
        }
        if (walker.path() == null) return Step.NO_PATH
        if (hop == Hop.WAITING_FOR_LANDING) return awaitLanding(client, player, input)

        walkWhileAiming = false
        waitReason = null
        val teleported = tryTeleport(client, player, level, region, input, look, items)
        if (teleported != Step.HOPPING || waitReason == null || hop == Hop.WAITING_FOR_LANDING) hopWaitTicks = 0
        if (teleported != null) {
            if (teleported == Step.HOPPING && waitReason != null && gaveUpWaiting(waitReason!!)) {
                waitReason = null
                return Step.WALKING
            }
            return teleported
        }
        viewHeld = walkWhileAiming
        if (waitsToLeaveWater(player)) {
            // Out of the water by teleport, and by nothing else: the item is still on its way to the hand, or the pause is
            // not over, or the aim is not found yet. Swimming on in the meantime would end at the edge and a jump out of
            // it - the very thing that is not to be relied on - so the player stays where they float until it works.
            input.stand(false)
            return Step.HOPPING
        }
        return when (walker.tick(player, input, config)) {
            PathWalker.Status.WALKING -> {
                if (player.onGround()) AreaMemory.walking(level, player.position())
                val brakes = walker.edgeBrakes()
                if (brakes != edgeBrakesSeen) {
                    edgeBrakesSeen = brakes
                    val now = Util.getMillis()
                    // One line in two seconds at most: the log is bounded.
                    if (now - edgeLogMillis > 2000L) {
                        edgeLogMillis = now
                        note = "edge guard: keys released, the ground ends within the slide (total=$brakes speed=${String.format(java.util.Locale.ROOT, "%.2f", player.deltaMovement.horizontalDistance())})"
                    }
                }
                Step.WALKING
            }
            PathWalker.Status.ARRIVED -> Step.ARRIVED
            PathWalker.Status.STUCK -> {
                // Stopped making progress on a route the planner thought was fine: worth remembering, see AreaMemory.
                AreaMemory.stuck(level, player.position(), walker.path(), walker.progress())
                walker.stuckReason()?.let { reason -> note = "stuck: $reason" }
                Step.STUCK
            }
            PathWalker.Status.OFF_PATH -> Step.OFF_PATH
            PathWalker.Status.NO_PATH -> Step.NO_PATH
        }
    }

    /** The least route left for a warp along it: the setting, and what the macro asks for on top of it. */
    private fun warpRouteMin(): Double = max(config.etherMinDistance + ETHER_STAND_OFF, warpMinRoute)

    private fun tryTeleport(
        client: Minecraft, player: LocalPlayer, level: ClientLevel, region: Region, input: WalkInput, look: LookControl, items: ItemSwitcher,
    ): Step? {
        if (!config.useTeleport || teleportDisabled) {
            warp = null
            return null
        }
        val now = Util.getMillis()
        val paused = warp == null && now - lastLandingMillis < pauseMillis
        // A warp that was begun to get out of water is finished as one, whatever the water does in the meantime.
        val leaving = if (warp != null) warpLeavesWater else leavesWater(player)
        // A warp needs the crouch first and is not started in the pause. A jump is aimed in it: the pause is the time
        // before the click, not the time before the turn, and turning first is what makes the pause the thing that sets
        // how fast the clicks follow each other instead of the pause plus the aim.
        if (paused && config.useEtherwarp && (leaving || walker.remaining() >= warpRouteMin())) return null
        // The item is brought to hand by whoever owns the trip, with its own pause; a try waits for it.
        if (!items.holding(player, config.teleportSlot - 1)) {
            warp = null
            return null
        }

        val terrain = Terrain(level, player, config, region)
        val feet = HopPlanner.predictFeet(player, terrain, config.teleportLeadTicks + pingTicks(client, player))

        // The gap since the last click is waited out before a new teleport is begun; one already being aimed goes on.
        val warpGapOpen = warp != null || gapPassed(true)
        val hopGapOpen = gapPassed(false)
        // A stretch long enough for a warp is not hopped meanwhile: the player walks until the warp's gap is over.
        if (config.useEtherwarp && !warpGapOpen && (leaving || walker.remaining() >= warpRouteMin())) return null
        if (config.useEtherwarp && !paused && warpGapOpen) {
            val warping = tryWarp(client, player, level, region, input, look, terrain, feet, now, leaving)
            if (warping != null) return warping
        }
        if (!hopGapOpen) return null
        return tryJump(client, player, level, region, input, look, terrain, feet, now, paused, leaving)
    }

    // ------------------------------------------------------------------ warp: sneak and right click

    private fun tryWarp(
        client: Minecraft, player: LocalPlayer, level: ClientLevel, region: Region, input: WalkInput, look: LookControl, terrain: Terrain,
        feet: Vec3, now: Long, leaving: Boolean,
    ): Step? {
        val minDistance = if (leaving) EXIT_MIN_DISTANCE else config.etherMinDistance
        val standOff = if (leaving) EXIT_STAND_OFF else ETHER_STAND_OFF
        if (walker.remaining() < (if (leaving) minDistance + standOff else max(minDistance + standOff, warpMinRoute))) {
            warp = null
            return null
        }

        if (warp == null) {
            if (now < nextWarpPlanMillis) return null
            warp = HopPlanner.planEther(
                level, player, terrain, region, walker.path()!!, walker.progress(), feet, config.etherRange, minDistance, standOff,
            )
            warpLeavesWater = leaving
            sneakTicks = 0
            if (warp == null) {
                nextWarpPlanMillis = now + replanPause(now)
                return null
            }
            // Along a route a warp is for a long clear stretch that it really shortens, not for every few blocks: the macro says when.
            val first = warp!!
            if (!leaving && !warpWorthIt(walker.remaining(), first.landing.distanceTo(feet), first.progress - walker.progress())) {
                warp = null
                nextWarpPlanMillis = now + SHORTCUT_PAUSE_MILLIS
                skipped(now, "warp not worth it remaining=${fmt(walker.remaining())} flight=${fmt(first.landing.distanceTo(feet))} gain=${fmt(first.progress - walker.progress())}")
                return null
            }
        }
        val plan = warp!!
        if (!leaving && sneakTicks == 0 && shortcutBlocked(walker.path()!!, walker.progress(), plan.progress)) {
            warp = null
            nextWarpPlanMillis = now + SHORTCUT_PAUSE_MILLIS
            skipped(now, "mobs on the way")
            return null
        }

        // Committed to one landing, so the aim does not hop between candidates from tick to tick. Only whether it
        // still works from where the player will be is asked again.
        val origin = HopPlanner.etherOrigin(player, feet)
        if (!HopPlanner.landsOn(level, player, origin, plan.aimPoint, config.etherRange - 1.0, plan.floor)) {
            warp = null
            nextWarpPlanMillis = now + replanPause(now)
            return null
        }

        input.stand(false)
        input.sneak(true)
        sneaking = true
        sneakTicks++
        viewHeld = true
        val angles = SmoothRotation.lookAt(origin, plan.aimPoint)
        look.lookAlong(angles.yaw, angles.pitch, true)
        if (sneakTicks < ETHER_SNEAK_TICKS || !player.isShiftKeyDown || !warpAimed(level, player, look, origin)) {
            waitReason = warpWaitReason(player, look)
            return Step.HOPPING
        }

        val pick = pick(client, player, now, plan.floor)
        if (pick == Pick.BLOCKED) waitReason = "blocked by what is under the crosshair"
        if (pick != Pick.CLEAR) return if (pick == Pick.BLOCKED) Step.HOPPING else null
        // The view never rests exactly on the point asked for: the block it is on now has to be the green one, and the planned one.
        val verdict = checkWarp(player, level, region, plan)
        if (!verdict.ok) {
            waitReason = "warp check refused: ${verdict.reason}"
            return Step.HOPPING
        }
        fire(client, player, plan.progress, plan.landing.distanceTo(player.position()), plan.landing, true)
        return Step.HOPPING
    }

    /** The warp check of the view as it is, logged: `warp check ok|refused`. */
    private fun checkWarp(player: LocalPlayer, level: ClientLevel, region: Region, plan: HopPlanner.EtherPlan): TeleportCheck.Verdict {
        val verdict = TeleportCheck.warp(level, player, region, player.position(), player.getViewVector(1.0f), config.etherRange, plan.landing)
        if (verdict.ok != lastCheckOk || (verdict.reason != lastCheckReason)) {
            lastCheckOk = verdict.ok
            lastCheckReason = verdict.reason
            val land = verdict.landing
            log(
                "warp check ${if (verdict.ok) "ok" else "refused"} reason='${verdict.reason}' plan=${fmt(plan.landing.x)},${fmt(plan.landing.y)},${fmt(plan.landing.z)} " +
                    "sim=${if (land == null) "none" else "${fmt(land.x)},${fmt(land.y)},${fmt(land.z)}"} distance=${fmt(plan.landing.distanceTo(player.position()))}",
            )
        }
        return verdict
    }

    private var lastCheckOk = true
    private var lastCheckReason = ""

    // ------------------------------------------------------------------ jump: right click

    private fun tryJump(
        client: Minecraft, player: LocalPlayer, level: ClientLevel, region: Region, input: WalkInput, look: LookControl, terrain: Terrain,
        feet: Vec3, now: Long, holdFire: Boolean, leaving: Boolean,
    ): Step? {
        if (now < nextPlanMillis || (!leaving && walker.remaining() < reach() + ROUTE_MARGIN)) return null

        // Out of water the jump does not have to land on the route's chord: any dry ground near it will do, and the
        // route may be shorter than the jump - the fan of directions finds what the straight line does not.
        val route = walker.path()!!
        val plan = if (leaving) {
            HopPlanner.planExit(level, player, terrain, region, route, walker.progress(), feet, reach(), config.hopDrop)
        } else {
            HopPlanner.plan(level, player, terrain, region, route, walker.progress(), feet, reach(), config.hopDrop)
        }
        if (plan == null || (!leaving && plan.progress - walker.progress() < reach() * MIN_GAIN_SHARE)) {
            // None, or one that gains too little of the route for a click: a landing that is mostly sideways costs a hop for nothing.
            nextPlanMillis = now + replanPause(now)
            return null
        }
        if (!leaving && shortcutBlocked(route, walker.progress(), plan.progress)) {
            nextPlanMillis = now + SHORTCUT_PAUSE_MILLIS
            skipped(now, "mobs on the way")
            return null
        }
        if (!hopCounted) {
            hopCounted = true
            val needed = Math.ceil((walker.remaining() - ROUTE_MARGIN) / reach()).toInt()
            log("teleport route hops=$needed reach=${fmt(reach())} remaining=${fmt(walker.remaining())} gain=${fmt(plan.progress - walker.progress())}")
        }

        viewHeld = true
        val angles = SmoothRotation.lookAt(Vec3.ZERO, plan.direction)
        look.lookAlong(angles.yaw, angles.pitch, false)
        // The item carries the player along the view by its length whichever way it points, and the landing is worked out from where it
        // points: a player does not aim a hop to the tenth of a degree, they turn to the general direction and click. So the click goes
        // once the view is near the way, and the walk goes on while it turns instead of standing still for it.
        if (holdFire || look.residualDegrees(player) > HOP_AIM_TOLERANCE_DEGREES) {
            if (holdFire) {
                input.stand(false)
                return Step.HOPPING
            }
            walkWhileAiming = true
            return null
        }
        input.stand(false)

        val pick = pick(client, player, now, null)
        if (pick == Pick.BLOCKED) waitReason = "blocked by what is under the crosshair"
        if (pick != Pick.CLEAR) return if (pick == Pick.BLOCKED) Step.HOPPING else null
        fire(client, player, plan.progress, reach(), plan.landing, false)
        return Step.HOPPING
    }

    // ------------------------------------------------------------------ shared

    /** Why the teleport being set up has not been clicked yet, set where it waits; read by [tick]. Null when it is not waiting on anything. */
    private var waitReason: String? = null
    private var hopWaitTicks = 0

    /** When the last teleport click went out, the random drawn for the gap after it, and whether it was a warp. */
    private var lastClickMillis = 0L
    private var gapJitter = 0.0
    private var firedWarp = false

    private fun warpWaitReason(player: LocalPlayer, look: LookControl): String = when {
        sneakTicks < ETHER_SNEAK_TICKS -> "crouching ticks=$sneakTicks"
        !player.isShiftKeyDown -> "not crouched"
        else -> "aim residual=${fmt(look.residualDegrees(player).toDouble())}"
    }

    /**
     * One tick of waiting for a teleport click for this [reason]: logs it every [HopWait.REPORT_EVERY] ticks and, past
     * [HopWait.GIVE_UP_AFTER], gives the hop up (teleporting is left alone for a moment) and says so. True when it was given up.
     */
    private fun gaveUpWaiting(reason: String): Boolean {
        when (HopWait.action(++hopWaitTicks)) {
            HopWait.Action.NONE -> return false
            HopWait.Action.REPORT -> {
                log("hop waiting reason='$reason' ticks=$hopWaitTicks")
                return false
            }
            HopWait.Action.GIVE_UP -> {
                log("hop gave up reason='$reason' ticks=$hopWaitTicks")
                hopWaitTicks = 0
                val now = Util.getMillis()
                nextPlanMillis = now + FAILURE_PAUSE_MILLIS
                nextWarpPlanMillis = nextPlanMillis
                warp = null
                directHop = null
                viewHeld = false
                return true
            }
        }
    }

    /** A direct hop or warp that was given up on. */
    private fun failDirect(): Step {
        direct = false
        sneaking = false
        return Step.NO_PATH
    }

    /**
     * The click acts on what the game last picked, which was before the view was turned. Pick again, and click
     * only when nothing is under the crosshair: a mob in the way would be used on, a door opened.
     *
     * @param landsOn the block a warp is aimed at, or null: a warp to a block within reach has the crosshair on that very
     *   block - the only way to look at it - and that is the one thing under it that is allowed
     * @return whether the click may go out now, has to wait for the way to clear, or is given up on
     */
    private fun pick(client: Minecraft, player: LocalPlayer, now: Long, landsOn: BlockPos?): Pick {
        val picked = player.raycastHitResult(1.0f, player)
        client.hitResult = picked
        val onTarget = landsOn != null && picked is BlockHitResult && picked.blockPos == landsOn
        if (picked.type == HitResult.Type.MISS || onTarget) {
            blockedTicks = 0
            return Pick.CLEAR
        }
        if (++blockedTicks <= MAX_BLOCKED_TICKS) return Pick.BLOCKED
        blockedTicks = 0
        nextPlanMillis = now + FAILURE_PAUSE_MILLIS
        nextWarpPlanMillis = now + FAILURE_PAUSE_MILLIS
        viewHeld = false
        warp = null
        return Pick.GAVE_UP
    }

    /**
     * Whether the view is on the block of the warp: within the tolerance of the angles asked for, or - whatever the
     * angles - the ray from the crouching eyes along the view as it is meets that block, which is all the item asks. In
     * water the player bobs up and down, the angles asked for move with every tick, and the tolerance alone was met only
     * by chance: a warp out of a pool could wait seconds for it.
     */
    private fun warpAimed(level: ClientLevel, player: LocalPlayer, look: LookControl, origin: Vec3): Boolean {
        if (look.residualDegrees(player) <= AIM_TOLERANCE_DEGREES) return true
        val plan = warp!!
        val along = player.getViewVector(1.0f).scale(origin.distanceTo(plan.aimPoint) + 0.5)
        return HopPlanner.landsOn(level, player, origin, origin.add(along), config.etherRange - 1.0, plan.floor)
    }

    /** Whether enough time has passed since the last teleport click for another of this kind (see [TeleportGap]). */
    private fun gapPassed(warp: Boolean): Boolean =
        Util.getMillis() - lastClickMillis >= TeleportGap.minMillis(warp, config.hopGapTicks, config.warpGapTicks, gapJitter)

    private fun fire(client: Minecraft, player: LocalPlayer, progress: Double, distance: Double, landing: Vec3?, warp: Boolean) {
        expectedLanding = landing
        hopWaitTicks = 0
        val now = Util.getMillis()
        log("teleport click kind=${if (warp) "warp" else "hop"} sinceLastMs=${if (lastClickMillis == 0L) -1 else now - lastClickMillis} distance=${fmt(distance)}")
        lastClickMillis = now
        gapJitter = ThreadLocalRandom.current().nextDouble()
        firedWarp = warp
        // A click, not a hold: the button goes down and up in the same tick, so it can never be down across one.
        SimulatedMouse.press(client, SimulatedMouse.Button.RIGHT)
        SimulatedMouse.release(client, SimulatedMouse.Button.RIGHT)
        clickHeld = false
        clickedFrom = player.position()
        plannedProgress = progress
        expectedMove = distance
        waitedTicks = 0
        hop = Hop.WAITING_FOR_LANDING
    }

    private fun awaitLanding(client: Minecraft, player: LocalPlayer, input: WalkInput): Step {
        input.stand(false)
        waitedTicks++

        // Moved a fair way: a landing that fell short of the plan (a block in the way the plan did not see) is a landing all the same,
        // and waiting out the rest of the time for a distance it will never cover is a pause for nothing.
        if (player.position().distanceTo(clickedFrom!!) >= min(expectedMove * 0.5, MIN_LANDED_MOVE)) {
            val planned = expectedLanding
            if (planned != null) {
                val error = player.position().distanceTo(planned)
                log(
                    "teleport landed kind=${if (firedWarp) "warp" else "hop"} predicted=${fmt(planned.x)},${fmt(planned.y)},${fmt(planned.z)} " +
                        "actual=${fmt(player.x)},${fmt(player.y)},${fmt(player.z)} err=${fmt(error)} ticks=$waitedTicks",
                )
                // Landings that keep falling more than a block off the plan: the item goes less far than it is believed to.
                if (error > LANDING_ERROR && ++landingErrors >= 2 && reachScale > MIN_REACH_SCALE) {
                    reachScale = max(MIN_REACH_SCALE, reachScale * 0.9)
                    landingErrors = 0
                    log("teleport reach trimmed scale=${fmt(reachScale)} reach=${fmt(reach())}")
                }
            }
            expectedLanding = null
            hopCounted = false
            hop = Hop.IDLE
            viewHeld = false
            warp = null
            failures = 0
            lastLandingMillis = Util.getMillis()
            drawPause()
            walker.seek(plannedProgress, player.position())
            return Step.WALKING
        }
        if (waitedTicks > LANDING_TIMEOUT_TICKS + 2 * pingTicks(client, player)) {
            hop = Hop.IDLE
            viewHeld = false
            warp = null
            nextPlanMillis = Util.getMillis() + FAILURE_PAUSE_MILLIS
            nextWarpPlanMillis = nextPlanMillis
            if (++failures >= MAX_FAILURES) {
                teleportDisabled = true
                note = "The teleport item did not move you - teleporting is off for this trip. Check the teleport slot."
            }
            return Step.WALKING
        }
        return Step.HOPPING
    }

    companion object {
        /** How close to the planned direction the view must be before the click: at 12 blocks 0.35 degrees is 7 cm. */
        private const val AIM_TOLERANCE_DEGREES = 0.35

        /** A click has gone off once the player is this far from where it was made, if the plan was for less than twice as much. */
        private const val MIN_LANDED_MOVE = 1.5
        private const val SHORTCUT_PAUSE_MILLIS = 500L
        private const val SKIP_LOG_MILLIS = 3000L

        /** A hop along a route has to gain at least this share of the item's reach along it. */
        private const val MIN_GAIN_SHARE = 0.6
        private const val LANDING_ERROR = 1.5
        private const val MIN_REACH_SCALE = 0.6
        private fun fmt(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

        /** A hop is a click along the view, not a point to land on: this near the way is aim enough (about a block at the item's length). */
        private const val HOP_AIM_TOLERANCE_DEGREES = 5.0
        private const val LANDING_TIMEOUT_TICKS = 30
        private const val REPLAN_PAUSE_MILLIS = 400L

        /**
         * Right after a landing a try that finds no way is much likelier to be the player still settling - a tenth of a block
         * of fall changes the ray that has to be clear - than a stretch with nowhere to go. So in a run of teleports the next
         * try comes soon; waiting the full pause there put a floor of nearly half a second under every teleport, however
         * small the configured pause was.
         */
        private const val QUICK_REPLAN_PAUSE_MILLIS = 40L
        private const val RECENT_LANDING_MILLIS = 700L
        private const val FAILURE_PAUSE_MILLIS = 1000L

        /** Ticks the click may be held back by something in the line of fire before the try is given up. */
        private const val MAX_BLOCKED_TICKS = 10

        /** A jump this long is only worth planning when there is route enough left to land on. */
        private const val ROUTE_MARGIN = 4.0

        /** The teleport item is brought to hand a little before it is needed, while there is still a stretch to cross. */
        private const val ITEM_MARGIN = 4.0

        /** The last stretch of a route before its end is walked, not warped, whatever the range. */
        private const val ETHER_STAND_OFF = 3.0

        /**
         * Ticks of crouching before the warp may be fired. The item branches on whether the player is sneaking and the
         * server only learns of it from the input packet of the tick the key went down, so the pose has to be in place
         * on the server before the click, not applied with it.
         */
        private const val ETHER_SNEAK_TICKS = 3
        private const val MAX_FAILURES = 2

        /** Ticks the player still counts as being in water after they last were: the surface bobs them in and out of it. */
        private const val WET_GRACE_TICKS = 6

        /** Leaving water: the nearest landing, and how much of the route's end is left alone. Both are far less than for a trip. */
        private const val EXIT_MIN_DISTANCE = 2.0
        private const val EXIT_STAND_OFF = 1.0

        /** How long the player waits in the water for the teleport out of it before swimming on: three seconds. */
        private const val EXIT_PATIENCE_TICKS = 60

        /** The round trip to the server in ticks, which is how much later than the click it acts on it. */
        private fun pingTicks(client: Minecraft, player: LocalPlayer): Int {
            val connection = client.connection ?: return 0
            val info = connection.getPlayerInfo(player.uuid)
            return if (info == null) 0 else Math.round(info.latency / 50.0).toInt()
        }
    }
}
