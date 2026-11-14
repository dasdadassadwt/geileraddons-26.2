package geiler.addons.client.macros.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.area.Paint
import geiler.addons.client.macros.area.PaintBrush
import geiler.addons.client.macros.area.PaintStore
import geiler.addons.client.macros.config.MacroWorkspaceConfig
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * `/ga area`: painted areas, where the Area Killer may walk. `paint <name> <radius> <allowed|preferred|forbidden|wall|rest|erase>` arms the
 * brush - right click stamps it on the ground round the block looked at - and `paint off` puts it down. The area being painted, the one
 * `show`n, or the one the macro `use`s is drawn in the world: green allowed, gold preferred, red forbidden, purple wall, blue rest.
 * `/ga brush <name> <type> <radius>` is the same brush with the type first. Rest cells are where the macro waits with nothing to hit.
 */
object AreaCommands {
    private val config get() = MacroRuntime.config

    fun build(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val root = ClientCommands.literal("area")

        val types = ClientCommands.literal("paint")
        types.then(ClientCommands.literal("off").executes { context ->
            PaintBrush.stop()
            reply(context, "Brush put down.")
            1
        })
        val radius = ClientCommands.argument("radius", IntegerArgumentType.integer(1, PaintBrush.MAX_RADIUS))
        for (type in Paint.entries) radius.then(ClientCommands.literal(type.label).executes { paint(it, type) })
        radius.then(ClientCommands.literal("erase").executes { paint(it, null) })
        types.then(nameArgument().then(radius))
        root.then(types)

        root.then(
            ClientCommands.literal("use")
                .then(ClientCommands.literal("none").executes { context ->
                    config.paintArea = ""
                    MacroWorkspaceConfig.save()
                    PaintStore.once("use none")
                    reply(context, "The Area Killer uses no painted area.")
                    1
                })
                .then(nameArgument().executes { context ->
                    val name = nameOf(context)
                    val area = name?.let { PaintStore.get(it) }
                    if (name == null || area == null) {
                        context.source.sendError(Component.literal("There is no area '${StringArgumentType.getString(context, "name")}'. /ga area list"))
                        return@executes 0
                    }
                    config.paintArea = name
                    MacroWorkspaceConfig.save()
                    PaintStore.once("use name=$name cells=${area.size()}")
                    reply(context, "The Area Killer uses '$name' (${area.size()} cells${if (area.restrictive()) ", only the painted ground" else ", only what is shut"}). It takes effect at the next start.")
                    1
                }),
        )
        root.then(
            ClientCommands.literal("show")
                .then(ClientCommands.literal("off").executes { context ->
                    PaintStore.show(null)
                    reply(context, "No area shown.")
                    1
                })
                .then(nameArgument().executes { context ->
                    val name = nameOf(context)
                    if (name == null || PaintStore.get(name) == null) {
                        context.source.sendError(Component.literal("There is no area '${StringArgumentType.getString(context, "name")}'. /ga area list"))
                        return@executes 0
                    }
                    PaintStore.show(name)
                    reply(context, "Showing '$name': green allowed, gold preferred, red forbidden, purple wall, blue rest.")
                    1
                }),
        )
        root.then(ClientCommands.literal("list").executes { context ->
            val names = PaintStore.names()
            reply(
                context,
                if (names.isEmpty()) "No areas yet. /ga area paint <name> <radius> <allowed|preferred|forbidden|wall|rest|erase>"
                else "Areas: " + names.joinToString(", ") { it + (if (it == config.paintArea) " (used)" else "") },
            )
            1
        })
        root.then(
            ClientCommands.literal("info").then(nameArgument().executes { context ->
                val area = nameOf(context)?.let { PaintStore.get(it) }
                if (area == null) {
                    context.source.sendError(Component.literal("There is no area '${StringArgumentType.getString(context, "name")}'."))
                    return@executes 0
                }
                val counts = Paint.entries.joinToString(", ") { type ->
                    var n = 0
                    area.forEachNear(0.0, 0.0, 1.0E9, Int.MAX_VALUE) { _, _, _, t, _ -> if (t == type) n++ }
                    "${type.label} $n"
                }
                reply(context, "Area '${area.name}': ${area.size()} cells ($counts); ${if (area.restrictive()) "limits the macro to the painted ground" else "only shuts what is forbidden or walled"}.")
                1
            }),
        )
        root.then(
            ClientCommands.literal("clear").then(nameArgument().executes { context ->
                val area = nameOf(context)?.let { PaintStore.get(it) }
                if (area == null) {
                    context.source.sendError(Component.literal("There is no area '${StringArgumentType.getString(context, "name")}'."))
                    return@executes 0
                }
                area.clear()
                PaintStore.touched(area)
                PaintStore.once("clear name=${area.name}")
                reply(context, "Area '${area.name}' is empty.")
                1
            }),
        )
        root.then(
            ClientCommands.literal("delete").then(nameArgument().executes { context ->
                val name = nameOf(context)
                if (name == null || !PaintStore.delete(name)) {
                    context.source.sendError(Component.literal("There is no area '${StringArgumentType.getString(context, "name")}'."))
                    return@executes 0
                }
                if (config.paintArea == name) {
                    config.paintArea = ""
                    MacroWorkspaceConfig.save()
                }
                PaintStore.once("delete name=$name")
                reply(context, "Area '$name' deleted.")
                1
            }),
        )
        return root
    }

    /** `/ga brush <name> <type> <radius>`: the same brush as `/ga area paint`, with the type before the radius. `/ga brush off` puts it down. */
    fun buildBrush(): LiteralArgumentBuilder<FabricClientCommandSource> {
        val root = ClientCommands.literal("brush")
        root.then(ClientCommands.literal("off").executes { context ->
            PaintBrush.stop()
            reply(context, "Brush put down.")
            1
        })
        val named = nameArgument()
        for (type in Paint.entries) {
            named.then(ClientCommands.literal(type.label).then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, PaintBrush.MAX_RADIUS)).executes { paint(it, type) }))
        }
        named.then(ClientCommands.literal("erase").then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, PaintBrush.MAX_RADIUS)).executes { paint(it, null) }))
        root.then(named)
        return root
    }

    private fun nameArgument(): RequiredArgumentBuilder<FabricClientCommandSource, String> =
        ClientCommands.argument("name", StringArgumentType.word()).suggests { _, builder -> SharedSuggestionProvider.suggest(PaintStore.names(), builder) }

    private fun nameOf(context: CommandContext<FabricClientCommandSource>): String? = PaintStore.clean(StringArgumentType.getString(context, "name"))

    private fun paint(context: CommandContext<FabricClientCommandSource>, type: Paint?): Int {
        val name = nameOf(context)
        if (name == null) {
            context.source.sendError(Component.literal("An area name is letters, digits, - and _, up to 32."))
            return 0
        }
        val area = PaintStore.getOrCreate(name)!!
        val radius = IntegerArgumentType.getInteger(context, "radius")
        PaintBrush.start(area, radius, type)
        PaintStore.show(name)
        reply(
            context,
            "Brush: '$name', radius $radius, ${type?.label ?: "erase"}. Right click on the ground to paint; /ga area paint off to stop. " +
                "Green is allowed, gold preferred, red forbidden, purple a wall, blue a place to rest.",
        )
        return 1
    }

    private fun reply(context: CommandContext<FabricClientCommandSource>, text: String) {
        context.source.sendFeedback(Component.literal(text))
    }
}
