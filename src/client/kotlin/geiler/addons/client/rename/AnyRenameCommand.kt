package geiler.addons.client.rename

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.module.impl.AnyRenameModule
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

/** `/ga rename` and its subcommands; they act on the item in your main hand. */
object AnyRenameCommand {
    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("rename")
                .executes { held { stack -> Minecraft.getInstance().execute { AnyRenameModule.INSTANCE.edit(null, stack) } } }
                .then(ClientCommands.literal("reset").executes { held { stack -> reset(stack) } })
                .then(ClientCommands.literal("list").executes { list() })
                .then(ClientCommands.literal("export").executes { held { stack -> export(stack) } })
                .then(
                    ClientCommands.literal("import").then(
                        ClientCommands.argument("data", StringArgumentType.greedyString())
                            .executes { ctx -> held { stack -> import(stack, StringArgumentType.getString(ctx, "data")) } },
                    ),
                ),
        )
    }

    private fun held(action: (ItemStack) -> Unit): Int {
        val stack = Minecraft.getInstance().player?.mainHandItem
        if (stack == null || stack.isEmpty) {
            AnyRenameModule.INSTANCE.say("Hold the item first.")
            return 0
        }
        action(stack)
        return 1
    }

    private fun reset(stack: ItemStack) {
        val removed = AnyRenameModule.INSTANCE.reset(stack, true)
        AnyRenameModule.INSTANCE.say(if (removed) "Override removed." else "That item has no override.")
    }

    private fun list(): Int {
        AnyRenameModule.INSTANCE.say("${RenameStore.INSTANCE.size()} override(s) saved in ${RenameStore.file()}")
        return 1
    }

    private fun export(stack: ItemStack) {
        val existing = AnyRenameModule.INSTANCE.existing(stack)
        if (existing == null) {
            AnyRenameModule.INSTANCE.say("That item has no override to export.")
            return
        }
        Minecraft.getInstance().keyboardHandler.clipboard = RenameStore.export(existing)
        AnyRenameModule.INSTANCE.say("Override copied to the clipboard.")
    }

    private fun import(stack: ItemStack, data: String) {
        val imported = RenameStore.importOverride(data)
        if (imported == null) {
            AnyRenameModule.INSTANCE.say("That is not a valid Any Rename string.")
            return
        }
        AnyRenameModule.INSTANCE.save(stack, imported)
        AnyRenameModule.INSTANCE.say("Override imported.")
    }
}
