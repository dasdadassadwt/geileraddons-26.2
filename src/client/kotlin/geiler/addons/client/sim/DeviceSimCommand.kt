package geiler.addons.client.sim

import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/** `/ga sim setpos|ss [ping]|i4 [ping]|stop`. Client commands: they are never sent to the server. */
object DeviceSimCommand {
    private const val MAX_PING = 1000L

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        val sim = ClientCommands.literal("sim")
            .then(ClientCommands.literal("setpos").executes { run { DeviceSimService.setPosition() } })
            .then(ClientCommands.literal("stop").executes { run { DeviceSimService.stop("stopped", announce = true) } })
        for (kind in DeviceSimService.Kind.entries) {
            sim.then(
                ClientCommands.literal(kind.id)
                    .executes { run { DeviceSimService.start(kind, 0L) } }
                    .then(
                        ClientCommands.argument("ping", LongArgumentType.longArg(0L, MAX_PING)).executes {
                            val ping = LongArgumentType.getLong(it, "ping")
                            run { DeviceSimService.start(kind, ping) }
                        },
                    ),
            )
        }
        root.then(sim)
    }

    private fun run(action: () -> Unit): Int {
        Minecraft.getInstance().execute(action)
        return 1
    }
}
