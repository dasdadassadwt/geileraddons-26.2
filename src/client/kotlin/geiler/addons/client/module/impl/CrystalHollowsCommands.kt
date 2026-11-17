package geiler.addons.client.module.impl

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.mining.CrystalHollowsRules
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider

/** `/ga hollows ...`: add, remove, list, clear and share Crystal Hollows markers (local; no server involved). */
object CrystalHollowsCommands {
    private const val PREFIX = "§6[GeilerAddons] "
    private const val OTHER = "other"

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        val structureArgument = ClientCommands.argument("structure", StringArgumentType.word())
            .suggests { _, builder ->
                SharedSuggestionProvider.suggest(CrystalHollowsRules.Structure.entries.map { it.key } + OTHER, builder)
            }
            .executes { add(StringArgumentType.getString(it, "structure"), null) }
            .then(
                ClientCommands.argument("x", IntegerArgumentType.integer())
                    .then(
                        ClientCommands.argument("y", IntegerArgumentType.integer())
                            .then(
                                ClientCommands.argument("z", IntegerArgumentType.integer())
                                    .executes {
                                        add(
                                            StringArgumentType.getString(it, "structure"),
                                            intArrayOf(IntegerArgumentType.getInteger(it, "x"), IntegerArgumentType.getInteger(it, "y"), IntegerArgumentType.getInteger(it, "z")),
                                        )
                                    },
                            ),
                    ),
            )
        root.then(
            ClientCommands.literal("hollows")
                .then(ClientCommands.literal("add").then(structureArgument))
                .then(
                    ClientCommands.literal("remove").then(
                        ClientCommands.argument("name", StringArgumentType.greedyString())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(CrystalHollowsHelperModule.INSTANCE.waypointNames(), builder) }
                            .executes { remove(StringArgumentType.getString(it, "name")) },
                    ),
                )
                .then(ClientCommands.literal("clear").executes { clear() })
                .then(ClientCommands.literal("list").executes { list() })
                .then(
                    ClientCommands.literal("share")
                        .executes { share("") }
                        .then(
                            ClientCommands.argument("name", StringArgumentType.greedyString())
                                .suggests { _, builder -> SharedSuggestionProvider.suggest(CrystalHollowsHelperModule.INSTANCE.waypointNames(), builder) }
                                .executes { share(StringArgumentType.getString(it, "name")) },
                        ),
                ),
        )
    }

    private fun say(message: String): Int {
        PlayerAlerts.chat(PREFIX + message)
        return 1
    }

    private fun off(): Int = say("§cTurn on the Crystal Hollows Helper module first.")

    /** Without coordinates the marker goes where you stand. */
    private fun add(key: String, at: IntArray?): Int {
        val module = CrystalHollowsHelperModule.INSTANCE
        if (!module.isEnabled()) return off()
        val structure = if (key.equals(OTHER, true)) null else CrystalHollowsRules.structureForKey(key)
            ?: return say("§cUnknown structure §e$key§c. Use one of: §7${CrystalHollowsRules.Structure.entries.joinToString { it.key }}, $OTHER")
        val spot = at ?: Minecraft.getInstance().player?.blockPosition()?.let { intArrayOf(it.x, it.y, it.z) } ?: return 0
        val name = module.addWaypoint(structure, spot[0], spot[1], spot[2]) ?: return say("§cToo many markers; remove one first.")
        return say("§aMarked §e$name§a at §6${spot[0]}§r, §3${spot[1]}§r, §d${spot[2]}")
    }

    private fun remove(name: String): Int {
        val module = CrystalHollowsHelperModule.INSTANCE
        if (!module.isEnabled()) return off()
        return if (module.removeWaypoint(name)) say("§aRemoved §e$name") else say("§cNo marker named §e$name§c.")
    }

    private fun clear(): Int {
        val module = CrystalHollowsHelperModule.INSTANCE
        if (!module.isEnabled()) return off()
        return say("§aCleared ${module.clearWaypoints()} marker(s).")
    }

    private fun list(): Int {
        val module = CrystalHollowsHelperModule.INSTANCE
        if (!module.isEnabled()) return off()
        val lines = module.describe()
        if (lines.isEmpty()) return say("§7No Crystal Hollows markers.")
        lines.forEach { say("§7$it") }
        return 1
    }

    /** Sends the marker to party chat as `Name: x y z`, which other players' Crystal Hollows Helper offers to add. */
    private fun share(name: String): Int {
        val module = CrystalHollowsHelperModule.INSTANCE
        if (!module.isEnabled()) return off()
        val text = module.shareText(name) ?: return say("§cNo marker to share.")
        PlayerAlerts.command("pc $text")
        return say("§aShared in party chat: §e$text")
    }
}
