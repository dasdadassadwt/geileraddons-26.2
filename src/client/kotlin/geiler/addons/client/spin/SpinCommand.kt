package geiler.addons.client.spin

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.gui.SpinWheelScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/** `/ga spin`. */
object SpinCommand {
    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("spin").executes {
                // Runs after the chat screen has closed.
                val client = Minecraft.getInstance()
                client.execute { client.gui.setScreen(SpinWheelScreen()) }
                1
            },
        )
    }
}
