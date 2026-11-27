package geiler.addons.client.dungeon

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** `/ga cata [level] [name]`: Catacombs XP needed and S+ runs per floor. A client command, never sent to the server. */
object CataCommand {
    private const val MAX_LEVEL = 99
    private const val LOG = "Cata_Calculator"

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("cata")
                .executes { run(null, null) }
                .then(
                    ClientCommands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
                        .executes { run(IntegerArgumentType.getInteger(it, "level"), null) }
                        .then(
                            ClientCommands.argument("name", StringArgumentType.word())
                                .executes {
                                    run(
                                        IntegerArgumentType.getInteger(it, "level"),
                                        StringArgumentType.getString(it, "name"),
                                    )
                                },
                        ),
                ),
        )
    }

    private fun run(level: Int?, name: String?): Int {
        val player = name ?: Minecraft.getInstance().player?.gameProfile?.name()
        if (player == null) {
            say("§c[Cata] No local player. Use /ga cata <level> <name>.")
            return 0
        }
        say("§6[Cata] §7Looking up $player...")
        DungeonStatsServiceCore.fetchCata(player) { lookup ->
            val profile = lookup.profile
            if (profile == null) {
                say("§c[Cata] ${lookup.error ?: "Lookup failed"}")
                log("RESULT failed reason=${lookup.error}")
                return@fetchCata
            }
            // Without a level, aim for the next one.
            val target = level ?: (CataCalculator.levelOf(profile.experience).toInt() + 1)
            CataCalculator.report(lookup.name, profile, target, lookup.mayorBonus, lookup.mayorName).forEach(::say)
            log(
                "RESULT target=$target ring=${profile.expertRing} hecatomb=${profile.hecatomb} " +
                    "explorer=${profile.explorerLevel} mayor=${lookup.mayorBonus} dailyLeft=${profile.dailyRunsLeft}",
            )
        }
        return 1
    }

    // The log carries boost values only: no names and no profile numbers.
    private fun log(line: String) = GeilerAddonsLog.write(Category.F7, LOG, 0, line)

    private fun say(text: String) {
        Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(Component.literal(text))
    }
}
