package geiler.addons.client.macros.navigation

import geiler.addons.client.macros.area.GateArea
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.input.InputSwap
import geiler.addons.client.macros.input.ItemSwitcher
import geiler.addons.client.macros.input.WalkInput
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.pathing.Path
import geiler.addons.client.macros.pathing.PathSearch
import geiler.addons.client.macros.pathing.PathingSettings
import geiler.addons.client.macros.pathing.PositionGoal
import geiler.addons.client.macros.pathing.SearchBudget
import geiler.addons.client.macros.pathing.Terrain
import geiler.addons.client.macros.rotation.LookControl
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * `/pathfind x y z`: walks to a place, by teleport jumps where the route allows them.
 *
 * The same pathfinder, route follower and gate as the killer use, pointed at a position instead of a mob. A place further
 * away than one search can see is reached in legs: a search that finds no way all the way still hands back the way to the
 * point that got closest, and the next one starts from there. It stops trying when three searches in a row bring it no
 * more than two blocks closer than the best so far.
 */
class PathfindMacro private constructor(private val config: AreaKillerConfig) {
    private val inputSwap = InputSwap()
    private val runner = RouteRunner(config).also { it.log = { text -> diagnostics(text) } }
    private val look = LookControl()
    private val items = ItemSwitcher()

    private var running = false
    private var gate: GateArea? = null
    private var target: BlockPos? = null
    private var search: PathSearch? = null
    private var settings: PathingSettings? = null
    private var searching = true

    /** A warp toward the place is under way, there being no way on foot; and how many this trip has made. */
    private var warping = false
    private var towardWarps = 0
    private var stuckStrikes = 0
    private var failedSearches = 0
    private var airborneTicks = 0
    private var bestDistance = 0.0
    private var startedMillis = 0L

    private fun begin(destination: BlockPos) {
        val client = Minecraft.getInstance()
        val player = client.player
        if (player == null || client.level == null) return

        val candidate: GateArea
        try {
            // The Area Killer gate and bounds are its own: the Pathfinder keeps out of them only when asked to.
            candidate = if (config.respectGate) {
                // The painted area the Area Killer works in is a wall to this trip too: not walked into, not teleported through.
                GateArea.create(config.gate.from, config.gate.to, config.margin, config.bounds, if (config.paintArea.isEmpty()) null else PaintStore.get(config.paintArea))
            } else {
                GateArea.create(IntArray(0), IntArray(0), config.margin, null, null)
            }
        } catch (exception: IllegalArgumentException) {
            message(exception.message ?: "The gate is not valid")
            return
        }
        if (!candidate.containsPoint(player.position())) {
            message("You are too close to the gate (or outside the bounds) - step back first")
            return
        }
        if (!candidate.contains(destination.x, destination.y, destination.z)) {
            message("That place is inside the gate zone, which the pathfinder never enters")
            return
        }

        if (!standableNear(client, player, candidate, destination)) {
            message("There is nowhere to stand near ${destination.x} ${destination.y} ${destination.z}")
            return
        }

        reset(client)
        gate = candidate
        target = destination
        running = true
        startedMillis = System.currentTimeMillis()
        runner.resetHops()
        config.newTrip()
        bestDistance = Double.POSITIVE_INFINITY
        val distance = player.position().distanceTo(Vec3.atBottomCenterOf(destination))
        val radius = min(MAX_SEARCH_RADIUS, max(scanRadiusSetting(), distance.toInt() + SEARCH_RADIUS_SLACK))
        settings = WideSearch(config, radius)
        message(String.format(Locale.ROOT, "Pathfinding to %d %d %d (%.0f blocks)", destination.x, destination.y, destination.z, distance))
    }

    /** Whether there is somewhere to stand within two blocks of the place. A place in a chunk that is not loaded is taken as it is. */
    private fun standableNear(client: Minecraft, player: LocalPlayer, region: GateArea, destination: BlockPos): Boolean {
        val level = client.level ?: return true
        if (!level.hasChunkAt(destination)) return true
        val terrain = Terrain(level, player, config, region)
        for (dy in -2..2) for (dx in -2..2) for (dz in -2..2) {
            if (terrain.standAt(destination.x + dx, destination.y + dy, destination.z + dz) != null) return true
        }
        return false
    }

    /** Whether the route being walked stops short of the place, and since when: it is searched again now and then, as chunks load. */
    private var partial = false
    private var routeSinceMillis = 0L

    /** How fast the player has been getting about, in blocks a second, smoothed: for the time left. */
    private var speed = 0.0
    private var lastX = 0.0
    private var lastZ = 0.0

    private fun finish(text: String) {
        running = false
        reset(Minecraft.getInstance())
        message(text)
    }

    private fun tick(client: Minecraft) {
        if (!running) return
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            running = false
            reset(client)
            return
        }
        if (player.isDeadOrDying) {
            finish("Stopped - you died")
            return
        }
        if (gate!!.breached(player.position())) {
            finish("Stopped - reached the gate zone")
            return
        }

        val input = inputSwap.attach(player)
        if (client.gui.screen() != null) {
            input.releaseAll()
            look.release()
            runner.release(client)
            return
        }

        speed += (hypot(player.x - lastX, player.z - lastZ) * 20.0 - speed) * 0.05
        lastX = player.x
        lastZ = player.z

        items.tick(player)
        val slot = runner.desiredItem(player)
        if (slot >= 0) items.want(player, slot, config.switchDelayMinMillis, config.switchDelayMaxMillis)

        if (warping) {
            warp(client, player, level, input)
        } else if (searching) {
            search(client, player, level, input)
        } else {
            walk(client, player, level, input)
        }

        val note = runner.consumeNote()
        if (note != null) message(note)
    }

    private fun search(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        val region = gate!!
        val rules = settings!!
        val destination = target!!
        input.stand(player.isInWater)
        input.sneak(false)
        if (search == null) {
            val terrain = Terrain(level, player, rules, region)
            val start = terrain.standUnder(player)
            if (start == null) {
                // Nothing to plan from while the feet are off the ground.
                if (++airborneTicks > MAX_AIRBORNE_TICKS) finish("Stopped - nothing to stand on to plan from")
                return
            }
            airborneTicks = 0
            val walking = runner.path() != null
            search = PathSearch(terrain, start, PositionGoal(destination, terrain), rules, SearchBudget.scale(walking, if (walking) runner.remaining() else 0.0))
        }

        val running = search!!
        val status = running.step()
        if (status == PathSearch.Status.RUNNING) return
        val route = running.path()
        search = null
        diagnostics("search status=$status length=${route?.length()?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "none"}")

        if (status == PathSearch.Status.FOUND) {
            failedSearches = 0
            if (route == null || route.length() < 0.4) {
                arrived()
                return
            }
            partial = false
            startWalking(route, player)
            return
        }

        // No way all the way. A leg that gets closer is worth walking, and the next search starts from there.
        val ledge = config.useTeleport && config.useEtherwarp && towardWarps < MAX_TOWARD_WARPS &&
            destination.y - player.y > running.terrain().jumpHeight() + LEDGE_EXTRA
        if (route != null && !ledge) {
            val left = route.endPoint().distanceTo(Vec3.atBottomCenterOf(destination))
            if (left < bestDistance - PROGRESS_BLOCKS) {
                bestDistance = left
                failedSearches = 0
            } else {
                failedSearches++
            }
            if (failedSearches < MAX_FAILED_SEARCHES) {
                partial = true
                startWalking(route, player)
                return
            }
        }
        // No way on foot. A warp onto whatever can be seen nearest to the place may still get there - out of a pool with
        // walls too high to climb, onto a ledge - and the route is planned again from where it lands.
        if (config.useTeleport && config.useEtherwarp && towardWarps < MAX_TOWARD_WARPS) {
            val plan = HopPlanner.planToward(
                level, player, Terrain(level, player, rules, region), region, player.position(),
                Vec3.atBottomCenterOf(destination), config.etherRange,
            )
            if (plan != null) {
                towardWarps++
                runner.startDirectWarp(plan)
                warping = true
                return
            }
        }
        finish(
            String.format(
                Locale.ROOT, "No way to %d %d %d: the closest it got was %.0f blocks away",
                destination.x, destination.y, destination.z,
                min(bestDistance, player.position().distanceTo(Vec3.atBottomCenterOf(destination))),
            ),
        )
    }

    private fun startWalking(route: Path, player: LocalPlayer) {
        runner.follow(route, player.position())
        routeSinceMillis = System.currentTimeMillis()
        searching = false
    }

    /** One tick of a warp toward the place: once landed the route is planned again from there. */
    private fun warp(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        val step = runner.tickDirectWarp(client, player, level, gate!!, input, look, items)
        if (step == RouteRunner.Step.HOPPING) return
        warping = false
        input.sneak(false)
        if (step == RouteRunner.Step.WALKING) {
            failedSearches = 0
            searching = true
        } else {
            val destination = target!!
            finish("Stopped - the warp toward ${destination.x} ${destination.y} ${destination.z} did not work")
        }
    }

    private fun walk(client: Minecraft, player: LocalPlayer, level: ClientLevel, input: WalkInput) {
        // A route that stops short of the place was searched when part of the way was not loaded: look again now and then.
        if (partial && !runner.inFlight() && System.currentTimeMillis() - routeSinceMillis > REFRESH_MILLIS) {
            diagnostics("route refresh partial=true")
            runner.clear()
            searching = true
            return
        }
        val step = runner.tick(client, player, level, gate!!, input, look, items)
        if (!runner.controlsView()) {
            val ahead = runner.cameraPoint()
            if (ahead == null) {
                look.release()
            } else {
                // Look along the route at about eye height, as somebody walking somewhere does.
                look.lookAt(ahead.add(0.0, player.eyeHeight - 0.25, 0.0))
            }
        }

        when (step) {
            RouteRunner.Step.WALKING, RouteRunner.Step.HOPPING -> Unit
            RouteRunner.Step.ARRIVED -> {
                stuckStrikes = 0
                runner.clear()
                val destination = target!!
                val flat = hypot(player.x - (destination.x + 0.5), player.z - (destination.z + 0.5))
                if (flat <= ARRIVAL_DISTANCE && abs(player.y - destination.y) <= 2.0) {
                    arrived()
                } else {
                    searching = true
                }
            }
            RouteRunner.Step.STUCK, RouteRunner.Step.OFF_PATH, RouteRunner.Step.NO_PATH -> {
                diagnostics("route failed step=$step strikes=${stuckStrikes + 1}")
                input.stand(player.isInWater)
                runner.clear()
                // The ladder: plan again; then a teleport past what feet do not get past (a fence, a small block, a climb that
                // needs an adjustment in the air); then stop.
                val strikes = ++stuckStrikes
                if (strikes >= MAX_STUCK_STRIKES) {
                    finish("Stopped - stuck on the way")
                } else if (strikes >= 2 && escapeByTeleport(player, level, step)) {
                    return
                } else {
                    searching = true
                }
            }
        }
    }

    /** A warp, or a hop with the plain item, toward the place, to get past what is in the way. Whether one was begun. */
    private fun escapeByTeleport(player: LocalPlayer, level: ClientLevel, step: RouteRunner.Step): Boolean {
        if (!config.useTeleport) return false
        val destination = Vec3.atBottomCenterOf(target!!)
        val region = gate!!
        if (config.useEtherwarp && towardWarps < MAX_TOWARD_WARPS) {
            val plan = HopPlanner.planToward(level, player, Terrain(level, player, settings!!, region), region, player.position(), destination, config.etherRange)
            if (plan != null) {
                diagnostics("stuck escape warp step=$step")
                towardWarps++
                runner.startDirectWarp(plan)
                warping = true
                return true
            }
        }
        if (player.onGround() && towardWarps < MAX_TOWARD_WARPS) {
            val hop = HopPlanner.planInstant(level, player, region, player.position(), destination, config.teleportDistance, config.hopDrop)
            if (hop != null) {
                diagnostics("stuck escape hop step=$step")
                towardWarps++
                runner.startDirectHop(hop)
                warping = true
                return true
            }
        }
        diagnostics("stuck escape none step=$step")
        return false
    }

    private fun arrived() {
        val seconds = (System.currentTimeMillis() - startedMillis) / 1000.0
        val destination = target!!
        finish(String.format(Locale.ROOT, "Arrived at %d %d %d in %.1f s", destination.x, destination.y, destination.z, seconds))
    }

    /** Back to a clean slate with the keyboard and the mouse handed back to the player. */
    private fun reset(client: Minecraft) {
        runner.release(client)
        runner.clear()
        inputSwap.release()
        look.release()
        items.clear()
        search = null
        searching = true
        stuckStrikes = 0
        failedSearches = 0
        airborneTicks = 0
        warping = false
        towardWarps = 0
    }

    /**
     * The configured movement rules with a wider search than the killer's scan radius. The route map is the planner's own
     * walkable-ground map and this search never used it, as in the source.
     */
    private class WideSearch(config: AreaKillerConfig, private val radius: Int) : PathingSettings by config {
        override fun searchRadius(): Int = radius

        override fun useRouteMap(): Boolean = false
    }

    companion object {
        /** How much higher than the best jump the destination has to be for a teleport to go before any walk. */
        private const val LEDGE_EXTRA = 0.5

        private const val MAX_STUCK_STRIKES = 3

        /** A route that stops short of the place is searched again this often, in milliseconds. */
        private const val REFRESH_MILLIS = 2000L

        /** How many warps toward the place one trip makes when there is no way on foot. */
        private const val MAX_TOWARD_WARPS = 4
        private const val MAX_FAILED_SEARCHES = 3

        /** A leg has to bring the player this much closer than the best so far to count as progress. */
        private const val PROGRESS_BLOCKS = 2.0

        /** Searches may reach further than the killer's scan radius, since the place can be far off. */
        private const val MAX_SEARCH_RADIUS = 160
        private const val SEARCH_RADIUS_SLACK = 16

        /** How close, horizontally, counts as there once the route has been walked to its end. */
        private const val ARRIVAL_DISTANCE = 1.5
        private const val MAX_AIRBORNE_TICKS = 100

        private var instance: PathfindMacro? = null

        @JvmStatic
        fun init(config: AreaKillerConfig) {
            val macro = PathfindMacro(config)
            instance = macro
            ClientTickEvents.START_CLIENT_TICK.register { macro.tick(it) }
        }

        @JvmStatic
        fun isRunning(): Boolean = instance?.running == true

        /** Starts the trip, stopping the Area Killer first: only one macro holds the keyboard at a time. */
        @JvmStatic
        fun start(destination: BlockPos) {
            val macro = instance ?: return
            AreaKillerMacro.stop("pathfind started")
            macro.begin(destination)
        }

        @JvmStatic
        fun stop(reason: String) {
            val macro = instance ?: return
            if (macro.running) macro.finish("Stopped - $reason")
        }

        /** The route being walked, for the overlay. */
        @JvmStatic
        fun currentPath(): Path? = instance?.takeIf { it.running }?.runner?.path()

        /** How far along [currentPath] the player is, in blocks. */
        @JvmStatic
        fun currentProgress(): Double = instance?.takeIf { it.running }?.runner?.progress() ?: 0.0

        /** The place being walked to, for the overlay, or null. */
        @JvmStatic
        fun currentTarget(): BlockPos? = instance?.takeIf { it.running }?.target

        @JvmStatic
        fun currentGate(): GateArea? = instance?.gate

        /** What is going on in a few words, or null when not running. For the HUD. */
        @JvmStatic
        fun hudLine(): String? {
            val macro = instance?.takeIf { it.running } ?: return null
            val player = Minecraft.getInstance().player
            val destination = macro.target
            if (player == null || destination == null) return "Pathfinding"
            val distance = player.position().distanceTo(Vec3.atBottomCenterOf(destination))
            val seconds = if (macro.speed > 1.0) distance / macro.speed else -1.0
            return if (seconds >= 0.0) {
                String.format(Locale.ROOT, "Pathfinding · %.0f m · ~%.0f s", distance, seconds)
            } else {
                String.format(Locale.ROOT, "Pathfinding · %.0f m", distance)
            }
        }

        /** The Pathfinder module's own scan radius, set by the module: the shared config's one is the Area Killer's. */
        @JvmField
        var scanRadiusSetting: () -> Int = { 48 }

        /** Where the macro's events go for the module's bounded diagnostics log; set by the module. */
        @JvmField
        var diagnostics: (String) -> Unit = {}

        private fun message(text: String) {
            diagnostics(text)
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§6[Pathfind] §r$text"))
        }
    }
}
