package geiler.addons.client.termsim

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletableFuture

/**
 * `/ga termsim [ping]` and `/ga termsim <terminal> [ping]`. These are client commands: the dispatcher handles them
 * locally and they are never sent to the server.
 */
object TermSimCommand {
    private const val MAX_PING = 5000L

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("termsim")
                .executes { open(null, 0L) }
                .then(
                    ClientCommands.argument("ping", LongArgumentType.longArg(0L, MAX_PING))
                        .executes { open(null, LongArgumentType.getLong(it, "ping")) },
                )
                .then(
                    ClientCommands.argument("terminal", StringArgumentType.word())
                        .suggests { _, builder -> suggestTerminals(builder) }
                        .executes { openNamed(it.getArgument("terminal", String::class.java), 0L) }
                        .then(
                            ClientCommands.argument("ping", LongArgumentType.longArg(0L, MAX_PING))
                                .executes {
                                    openNamed(it.getArgument("terminal", String::class.java), LongArgumentType.getLong(it, "ping"))
                                },
                        ),
                ),
        )
    }

    private fun suggestTerminals(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        SimKind.entries.forEach { builder.suggest(it.id) }
        return builder.buildFuture()
    }

    private fun openNamed(name: String, ping: Long): Int {
        val kind = SimKind.parse(name)
        if (kind == null) {
            Minecraft.getInstance().gui.hud.chat.addClientSystemMessage(usage())
            return 0
        }
        return open(kind, ping)
    }

    private fun open(kind: SimKind?, ping: Long): Int {
        Minecraft.getInstance().execute {
            TermSimService.openFromCommand(kind, ping)
        }
        return 1
    }

    /** Names accepted by the terminal argument, for checks and usage text. */
    @JvmStatic
    fun terminalNames(): List<String> = SimKind.entries.map { it.id }

    @JvmStatic
    fun usage(): Component = Component.literal("[GeilerAddons] /ga termsim [ping] or /ga termsim <${terminalNames().joinToString("|")}> [ping]")
}
