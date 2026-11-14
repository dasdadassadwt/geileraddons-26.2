package geiler.addons.client.macros.module

import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.input.TeleportDetect
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.navigation.PathfindMacro
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.RangeSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.impl.ModuleDiagnostics
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component

/**
 * The pathfinder's settings and its go/stop switch. The target is never typed here: it comes from `/pathfind` or from
 * another module through [go]. Turning the module on with no target does nothing and it switches itself off; it turns
 * itself off when the trip ends. The settings are copied into the shared macro configuration
 * every tick, so they also apply to the routes the Area Killer plans.
 */
class PathfinderModule private constructor(private val c: Carrier) : Module(
    "Pathfinder",
    "Walks to a place, by teleport jumps where the route allows them. The place comes from /ga pathfind <x> <y> <z> or from " +
        "another module; turn this off to stop. These movement and search rules also apply to the Area Killer's routes.",
    Category.ENGINE,
    false,
    *c.all(),
) {
    private val diagnostics = ModuleDiagnostics(Category.ENGINE, { name() })
    private val config: AreaKillerConfig get() = MacroRuntime.config

    /** The place a command asked for, used by the next start instead of the typed target. */
    private var requested: BlockPos? = null

    init {
        group(
            SettingGroup(
                "Movement", c.sprint, c.sprintDistance, c.diagonals, c.jumps, c.jumpCost, c.wade, c.swim, c.jumpBoost, c.sprintJump,
            ),
            SettingGroup("Search", c.scanRadius, c.searchMillis, c.respectGate),
            SettingGroup("Travel", c.useTeleport, c.autoItem, c.teleportSlot, c.teleportDistance, c.hopDrop, c.hopGap, c.useEtherwarp, c.etherRange, c.warpGap, c.etherMinDistance),
            SettingGroup("Style", c.randomness, c.straightness, c.waypointSpacing, c.offCentre),
            SettingGroup("Memory", c.routeMap, c.saveWorld, c.learnArea),
        )
        PathfindMacro.diagnostics = { diagnostics.event(it) }
        PathfindMacro.scanRadiusSetting = { c.scanRadius.value().toInt() }
    }

    /** Starts a trip to a place, as `/pathfind x y z` does: a trip under way is replaced. */
    fun go(destination: BlockPos) {
        if (isEnabled()) {
            diagnostics.event("restart to=${destination.x},${destination.y},${destination.z}")
            PathfindMacro.start(destination)
        } else {
            requested = destination
            setEnabled(true)
        }
    }

    fun setScanRadius(blocks: Int) {
        c.scanRadius.setValue(blocks.toFloat())
        pull()
    }

    override fun onEnable() {
        val destination = requested
        requested = null
        diagnostics.enable(
            "target=${destination?.let { "${it.x},${it.y},${it.z}" } ?: "none"} sprint=${c.sprint.value()} " +
                "scanRadius=${c.scanRadius.value().toInt()} searchMillis=${c.searchMillis.value()}",
        )
        // A saved "on" from the last session has nothing to walk in yet; the tick turns it off again.
        if (Minecraft.getInstance().player == null) return
        if (destination == null) {
            diagnostics.event("no target: nothing asked for a place")
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                Component.literal("§6[Pathfind] §rThere is no target. Use /ga pathfind <x> <y> <z>, or let another module ask."),
            )
            // Nothing to walk to: the tick switches the module off again.
            return
        }
        pull()
        PathfindMacro.start(destination)
    }

    override fun onDisable() {
        PathfindMacro.stop("by user")
        diagnostics.disable()
    }

    override fun isActive(): Boolean = PathfindMacro.isRunning()

    /** Called every client tick: copies the settings into the shared configuration and follows the macro's state. */
    fun tick() {
        pull()
        if (!isEnabled()) return
        diagnostics.advance()
        if (!PathfindMacro.isRunning()) {
            // The trip ended by itself, or never began: the switch shows it.
            setEnabled(false)
            return
        }
        diagnostics.status { suppressed -> "running=true ${PathfindMacro.hudLine()} suppressedEvents=$suppressed" }
    }

    private fun pull() {
        val k = config
        k.sprintMode = AreaKillerConfig.SprintMode.valueOf(c.sprint.value())
        k.sprintDistance = c.sprintDistance.value().toDouble()
        k.allowDiagonals = c.diagonals.rawValue()
        k.allowJumps = c.jumps.rawValue()
        k.jumpCostPercent = c.jumpCost.value().toDouble()
        k.allowWater = c.wade.rawValue()
        k.allowSwim = c.swim.rawValue()
        k.allowJumpBoost = c.jumpBoost.rawValue()
        k.sprintJump = c.sprintJump.rawValue()
        k.respectGate = c.respectGate.rawValue()
        // The teleport item: this module's settings count while it is the one running, the Area Killer's while that is.
        if (isEnabled() && PathfindMacro.isRunning() && !AreaKillerMacro.isRunning()) {
            k.useTeleport = c.useTeleport.rawValue()
            k.teleportSlot = c.teleportSlot.value().toInt()
            k.scanRadius = c.scanRadius.value().toInt()
            k.teleportDistance = c.teleportDistance.value().toDouble()
            k.hopDrop = c.hopDrop.value().toDouble()
            k.hopGapTicks = c.hopGap.value().toInt()
            k.warpGapTicks = c.warpGap.value().toInt()
            k.useEtherwarp = c.useEtherwarp.rawValue()
            k.etherRange = c.etherRange.value().toDouble()
            k.etherMinDistance = c.etherMinDistance.value().toDouble()
            if (c.autoItem.rawValue()) {
                val player = Minecraft.getInstance().player
                if (player != null) TeleportDetect.apply(player, k)?.let { diagnostics.event(it) }
            }
        }
        k.pathSearchMillis = c.searchMillis.value().toDouble()
        k.pathStraightness = c.straightness.value().toDouble()
        k.pathRandomnessMin = c.randomness.low().toDouble()
        k.pathRandomness = c.randomness.high().toDouble()
        k.waypointSpacing = c.waypointSpacing.value().toDouble()
        k.offCentre = c.offCentre.value().toDouble()
        k.useRouteMap = c.routeMap.rawValue()
        k.saveWorld = c.saveWorld.rawValue()
        k.learnArea = c.learnArea.rawValue()
        k.normalize()
    }

    override fun settingTooltip(setting: Setting): String? = TOOLTIPS[setting.name()]

    /** All the settings, created before the module so they can be handed to its constructor. */
    private class Carrier {
        val sprint = ChoiceSetting(
            "Sprint", "ALWAYS", mapOf("ALWAYS" to "Always", "HYBRID" to "Hybrid", "NEVER" to "Never"), "ALWAYS", "HYBRID", "NEVER",
        )
        val sprintDistance = NumberSetting("Hybrid sprint distance", 2.0f, 40.0f, 10.0f)
        val diagonals = BooleanSetting("Diagonals", true)
        val jumps = BooleanSetting("Jumps", true)
        val jumpCost = NumberSetting("Jump cost", 0.0f, 500.0f, 100.0f, true)
        val wade = BooleanSetting("Wade", true)
        val swim = BooleanSetting("Swim", true)
        val jumpBoost = BooleanSetting("Jump boost", true)
        val sprintJump = BooleanSetting("Sprint-jump", true)

        val useTeleport = BooleanSetting("Teleport jumps", true)
        val autoItem = BooleanSetting("Auto detect item", true)
        val teleportSlot = NumberSetting("Teleport slot", 1.0f, 9.0f, 2.0f, true)
        val teleportDistance = NumberSetting("Jump distance", 4.0f, 30.0f, 12.0f)
        val hopDrop = NumberSetting("Max teleport drop", 1.0f, 40.0f, 4.0f)
        val hopGap = NumberSetting("Hop gap", "Hop gap (ticks)", 0.0f, 20.0f, 3.0f, true)
        val warpGap = NumberSetting("Etherwarp gap", "Etherwarp gap (ticks)", 0.0f, 40.0f, 8.0f, true)
        val useEtherwarp = BooleanSetting("Etherwarp", true)
        val etherRange = NumberSetting("Etherwarp range", 20.0f, 60.0f, 60.0f)
        val etherMinDistance = NumberSetting("Etherwarp only longer than", 8.0f, 58.0f, 20.0f)

        val scanRadius = NumberSetting("Scan radius", 4.0f, 128.0f, 48.0f, true)
        val respectGate = BooleanSetting("Respect Area Killer gate", false)
        val searchMillis = NumberSetting("Search time per tick", "Search time per tick (ms)", 2.0f, 50.0f, 20.0f)
        val straightness = NumberSetting("Straight routes", 0.0f, 100.0f, 70.0f)
        val randomness = RangeSetting("Route randomness", "Route randomness min", "Route randomness", 0.0f, 100.0f, 18.0f, 30.0f)
        val waypointSpacing = NumberSetting("Waypoint every", 2.0f, 30.0f, 8.0f)
        val offCentre = NumberSetting("Waypoints off centre", 0.0f, 100.0f, 60.0f)

        val routeMap = BooleanSetting("Route map", "Route map (pathfinder)", true)
        val saveWorld = BooleanSetting("Save world", true)
        val learnArea = BooleanSetting("Learn area", true)

        fun all(): Array<Setting> = arrayOf(
            sprint, sprintDistance, diagonals, jumps, jumpCost, wade, swim, jumpBoost, sprintJump,
            useTeleport, autoItem, teleportSlot, teleportDistance, hopDrop, hopGap, warpGap, useEtherwarp, etherRange, etherMinDistance,
            scanRadius, searchMillis, respectGate, straightness, randomness, waypointSpacing, offCentre, routeMap, saveWorld, learnArea,
        )
    }

    companion object {
        private val TOOLTIPS: Map<String, String> = mapOf(
            "Sprint" to "When the macro sprints: always, never, or - hybrid - only while what it runs to is further away than the hybrid sprint distance.",
            "Hybrid sprint distance" to "Hybrid: sprint only while the end of the route, or the mob, is more than this many blocks away.",
            "Jump cost" to "How much a jump costs next to its plain cost, in percent. Above 100 routes prefer stairs and slopes to jumping up, even when the stairs are a few blocks to the side; a jump of more than one block costs extra on top.",
            "Teleport jumps" to "Cover long stretches of the route with the teleport item: a right click carries the player along the view, a sneaking one with etherwarp warps far.",
            "Max teleport drop" to "The furthest a teleport may leave you above the ground it lands on, in blocks. A hop that would carry you over an edge into a bigger drop is not taken.",
            "Hop gap" to "The least time between two plain teleport clicks, in ticks, with up to two more at random. 0 is none beyond the teleport pause.",
            "Etherwarp gap" to "The least time between two etherwarps, in ticks, with up to two more at random: a warp is not spammed.",
            "Respect Area Killer gate" to "Keep out of the gate zone and bounds set for the Area Killer, and stop if they are reached. Off: the Pathfinder walks where it likes.",
            "Auto detect item" to "Find the Aspect of the End / Void (or any etherwarp item) on the hotbar and take its slot and how far it goes, tuners included, from the item. Off: the slot and distances below are used.",
            "Teleport slot" to "The hotbar slot of the teleport item, when it is not detected.",
            "Jump distance" to "How far one right click carries the player, when it is not detected: 8, and one more for every Transmission Tuner.",
            "Etherwarp" to "Warp with a sneaking right click over long stretches, when the item can.",
            "Etherwarp range" to "How far one warp goes, when it is not detected: 57 and one more for every Transmission Tuner.",
            "Etherwarp only longer than" to "Warp only when the stretch to cover is longer than this many blocks; shorter ones are walked or hopped.",
            "Wade" to "Walk through water shallow enough to stand up in, with the head above it.",
            "Swim" to "Swim across deeper water, along its surface.",
            "Jump boost" to "Plan with the jump height the player has with a jump boost on, which is read off the player.",
            "Sprint-jump" to "Jump while sprinting along a long straight stretch, when that is faster at the speed the player has.",
            "Scan radius" to "How far one search reaches for a /pathfind trip, in blocks; it searches further when the place is further away. The Area Killer has its own scan radius.",
            "Search time per tick" to "How long the search for a route may work in every tick, in milliseconds. A tick is 50 ms.",
            "Straight routes" to "How much routes are made to run straight, turning as little as they can.",
            "Route randomness" to "How far the whole route is shifted sideways, up to 0.3 of a block at 100, and how much give there is in when the jump key goes down.",
            "Waypoint every" to "The longest stretch between two waypoints of a route, in blocks; a longer straight stretch is divided.",
            "Waypoints off centre" to "How far the waypoints are moved off the middle of their blocks, up to 0.45 of a block at 100.",
            "Route map" to "Keep a map of the walkable ground around the player: what cannot be reached is known without a search, and searches are guided by the real cost to a few far places. Used by the Area Killer.",
            "Save world" to "Keep what has been read of the world on disk, per server and dimension, and plan with it where the world is not loaded. Used while the Area Killer runs.",
            "Learn area" to "Places where the walker got stuck cost the planner more from then on, and are forgiven bit by bit when walked through without trouble. Used while the Area Killer runs.",
        )

        @JvmField
        val INSTANCE = PathfinderModule(Carrier())
    }
}
