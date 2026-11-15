package geiler.addons.client.waypoint

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.gui.ClickGuiStyle
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/** `/ga waypoint ...`: quick add, list, remove, ordered-route control and clipboard import/export. */
object WaypointCommands {
    private const val QUICK_GROUP = "Quick Waypoints"
    private const val PREFIX = "§6[GeilerAddons] "

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("waypoint")
                .executes { openManager() }
                .then(ClientCommands.literal("manager").executes { openManager() })
                .then(
                    ClientCommands.literal("add")
                        .executes { add("") }
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes { add(StringArgumentType.getString(it, "name")) }),
                )
                .then(ClientCommands.literal("list").executes { list() })
                .then(
                    ClientCommands.literal("remove")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                            .executes { remove(StringArgumentType.getString(it, "name")) }),
                )
                .then(ClientCommands.literal("next").executes { step(1) })
                .then(ClientCommands.literal("previous").executes { step(-1) })
                .then(ClientCommands.literal("reset").executes { step(0) })
                .then(ClientCommands.literal("import").executes { importClipboard() })
                .then(ClientCommands.literal("export").executes { exportClipboard() }),
        )
    }

    private fun say(message: String): Int {
        PlayerAlerts.chat(PREFIX + message)
        return 1
    }

    /** Opens the Waypoint Library: the Waypoints module's settings page in the Click GUI. */
    private fun openManager(): Int {
        val client = Minecraft.getInstance()
        client.execute {
            val module = WaypointsModule.INSTANCE
            ClickGuiState.setCategory(module.category())
            ClickGuiState.setOpenModule(module)
            ClickGuiState.setExpandedColor(null)
            ClickGuiState.setSettingsScroll(0)
            client.gui.setScreen(ClickGuiStyle.screen(VisualModule.INSTANCE.clickGuiStyle()))
        }
        return 1
    }

    private fun add(name: String): Int {
        val player = Minecraft.getInstance().player ?: return 0
        val island = HypixelModApi.currentIsland()
        val mode = island.mode().orEmpty()
        val group = WaypointStore.all().firstOrNull { it.name == QUICK_GROUP && it.island == mode }
            ?: WaypointGroup(name = QUICK_GROUP, island = mode).also { WaypointStore.add(it) }
        val pos = player.blockPosition()
        val label = name.ifBlank { "Waypoint ${group.waypoints.size + 1}" }
        group.waypoints.add(WaypointEntry(label, pos.x, pos.y, pos.z))
        WaypointStore.save()
        return say("§aAdded §e$label§a at §6${pos.x}§r, §3${pos.y}§r, §d${pos.z}")
    }

    private fun list(): Int {
        val groups = WaypointStore.groupsFor(HypixelModApi.currentIsland())
        if (groups.isEmpty()) return say("§7No waypoint groups for this island.")
        for (group in groups) {
            say("§e${group.name}§7 (${group.waypoints.size}${if (group.ordered) ", route" else ""})")
            for (w in group.waypoints.take(10)) say("  §7${w.name.ifBlank { "-" }} §8${w.x}, ${w.y}, ${w.z}")
            if (group.waypoints.size > 10) say("  §8... ${group.waypoints.size - 10} more")
        }
        return 1
    }

    private fun remove(name: String): Int {
        for (group in WaypointStore.groupsFor(HypixelModApi.currentIsland())) {
            val index = group.waypoints.indexOfFirst { it.name.equals(name.trim(), ignoreCase = true) }
            if (index >= 0) {
                group.waypoints.removeAt(index)
                group.currentIndex = 0
                WaypointStore.save()
                return say("§aRemoved §e$name§a from §e${group.name}")
            }
        }
        return say("§cNo waypoint named §e$name§c on this island.")
    }

    /** [delta] 1 = next, -1 = previous, 0 = back to the first waypoint of the ordered group on this island. */
    private fun step(delta: Int): Int {
        val groups = WaypointStore.groupsFor(HypixelModApi.currentIsland()).filter { it.ordered && it.waypoints.isNotEmpty() }
        if (groups.isEmpty()) return say("§cNo ordered waypoint group on this island.")
        for (group in groups) {
            val count = group.waypoints.size
            group.currentIndex = if (delta == 0) 0 else (group.currentIndex + delta + count) % count
        }
        return say("§aOrdered route updated.")
    }

    private fun importClipboard(): Int {
        val result = WaypointShare.decode(Minecraft.getInstance().keyboardHandler.clipboard)
        if (result.error != null) return say("§c${result.error}")
        result.groups.forEach { WaypointStore.add(it) }
        return say("§aImported ${result.groups.size} group(s), ${result.groups.sumOf { it.waypoints.size }} waypoint(s).")
    }

    private fun exportClipboard(): Int {
        val groups = WaypointStore.groupsFor(HypixelModApi.currentIsland())
        if (groups.isEmpty()) return say("§cNothing to export on this island.")
        Minecraft.getInstance().keyboardHandler.clipboard = WaypointShare.encode(groups)
        return say("§aCopied ${groups.size} group(s) to the clipboard (Skyblocker format).")
    }
}
