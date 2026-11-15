package geiler.addons.client.macros.command

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.gui.ClickGuiStyle
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.macros.MacroRuntime
import geiler.addons.client.macros.area.GateArea
import geiler.addons.client.macros.config.AreaKillerConfig
import geiler.addons.client.macros.config.MacroWorkspaceConfig
import geiler.addons.client.macros.input.SimulatedMouse
import geiler.addons.client.macros.killer.AreaKillerMacro
import geiler.addons.client.macros.killer.MobIds
import geiler.addons.client.macros.killer.MobNames
import geiler.addons.client.macros.module.AreaKillerModule
import geiler.addons.client.macros.module.HumanRecorderModule
import geiler.addons.client.macros.module.PathfinderModule
import geiler.addons.client.macros.pathing.PathSearch
import geiler.addons.client.macros.pathing.WorldStore
import geiler.addons.client.macros.record.HumanRecorder
import geiler.addons.client.macros.rotation.LookedAt
import geiler.addons.client.module.Category
import geiler.addons.client.module.Workspace
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player

/**
 * `/areakiller`, `/addmobkill` and `/pathfind`. All of them are client commands, so none is ever sent to a server and
 * every answer is written into the chat on this client only. Values that are also Click GUI settings are set through
 * their module, so the page and the command never disagree.
 */
object MacroCommands {
    /** How far away a mob may be and still be picked by looking at it. */
    private const val LOOK_RANGE = 64.0

    private val config: AreaKillerConfig get() = MacroRuntime.config

    /** Adds `areakiller`, `addmobkill`, `pathfind` and `spawnmob` beneath the `/ga` root, before it is registered. */
    @JvmStatic
    fun appendTo(parent: LiteralArgumentBuilder<FabricClientCommandSource>) {
        val root = ClientCommands.literal("areakiller")

        root.then(
            ClientCommands.literal("start").executes {
                AreaKillerModule.INSTANCE.start()
                1
            },
        )
        root.then(
            ClientCommands.literal("stop").executes {
                AreaKillerMacro.stop("by user")
                1
            },
        )
        root.then(ClientCommands.literal("status").executes { context -> status(context) })
        root.then(
            ClientCommands.literal("gui").executes {
                // Like the Click GUI command, this only asks: the chat screen it was typed in closes itself right after.
                val client = Minecraft.getInstance()
                client.execute {
                    ClickGuiState.setWorkspace(Workspace.MACROS)
                    ClickGuiState.setCategory(Category.AREA_KILLER)
                    client.gui.setScreen(ClickGuiStyle.screen(VisualModule.INSTANCE.clickGuiStyle()))
                }
                1
            },
        )

        root.then(
            ClientCommands.literal("add")
                .executes { context -> addLookedAt(reply(context)) }
                .then(
                    ClientCommands.argument("mob", StringArgumentType.greedyString())
                        .executes { context -> addById(StringArgumentType.getString(context, "mob"), reply(context)) },
                ),
        )
        root.then(
            ClientCommands.literal("remove").then(
                ClientCommands.argument("mob", StringArgumentType.greedyString())
                    .executes { context -> remove(StringArgumentType.getString(context, "mob"), reply(context)) },
            ),
        )
        root.then(ClientCommands.literal("list").executes { context -> list(reply(context)) })

        root.then(
            ClientCommands.literal("gate").then(ClientCommands.literal("clear").executes { context ->
                AreaKillerModule.INSTANCE.clearGate()
                save()
                context.source.sendFeedback(Component.literal("No gate. Painted areas (/ga area) or nothing limit the macro now."))
                1
            }).then(
                ClientCommands.argument("x1", IntegerArgumentType.integer()).then(
                    ClientCommands.argument("y1", IntegerArgumentType.integer()).then(
                        ClientCommands.argument("z1", IntegerArgumentType.integer()).then(
                            ClientCommands.argument("x2", IntegerArgumentType.integer()).then(
                                ClientCommands.argument("y2", IntegerArgumentType.integer()).then(
                                    ClientCommands.argument("z2", IntegerArgumentType.integer())
                                        .executes { context -> setGate(context) },
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        root.then(
            ClientCommands.literal("margin").then(
                ClientCommands.argument("blocks", IntegerArgumentType.integer(0, 3)).executes { context ->
                    AreaKillerModule.INSTANCE.setMargin(IntegerArgumentType.getInteger(context, "blocks"))
                    save()
                    context.source.sendFeedback(Component.literal("Gate margin set to " + config.margin))
                    1
                },
            ),
        )

        root.then(
            ClientCommands.literal("slot").then(
                ClientCommands.argument("slot", IntegerArgumentType.integer(1, 9)).executes { context ->
                    AreaKillerModule.INSTANCE.setWeaponSlot(IntegerArgumentType.getInteger(context, "slot"))
                    save()
                    context.source.sendFeedback(Component.literal("Weapon slot set to " + config.weaponSlot))
                    1
                },
            ),
        )
        root.then(
            ClientCommands.literal("radius").then(
                ClientCommands.argument("blocks", IntegerArgumentType.integer(0, 1024)).executes { context ->
                    AreaKillerModule.INSTANCE.setScanRadius(IntegerArgumentType.getInteger(context, "blocks"))
                    save()
                    context.source.sendFeedback(Component.literal(if (config.scanRadius <= 0) "Scan radius set to no limit" else "Scan radius set to " + config.scanRadius))
                    1
                },
            ),
        )
        // The rest point: where the macro waits, and wanders about, when nothing is in reach. One for the macro, not for each area.
        root.then(
            ClientCommands.literal("rest")
                .executes { context ->
                    context.source.sendFeedback(Component.literal(restStatus()))
                    1
                }
                .then(
                    ClientCommands.literal("set").executes { context ->
                        val player = Minecraft.getInstance().player
                        if (player == null) {
                            context.source.sendFeedback(Component.literal("Not in a world"))
                        } else {
                            val at = player.blockPosition()
                            config.restPoint = intArrayOf(at.x, at.y, at.z)
                            AreaKillerModule.INSTANCE.setRestEnabled(true)
                            save()
                            context.source.sendFeedback(Component.literal(restStatus()))
                        }
                        1
                    },
                )
                .then(
                    ClientCommands.literal("clear").executes { context ->
                        config.restPoint = IntArray(0)
                        AreaKillerModule.INSTANCE.setRestEnabled(false)
                        save()
                        context.source.sendFeedback(Component.literal(restStatus()))
                        1
                    },
                )
                .then(
                    ClientCommands.argument("on", BoolArgumentType.bool()).executes { context ->
                        AreaKillerModule.INSTANCE.setRestEnabled(BoolArgumentType.getBool(context, "on"))
                        save()
                        context.source.sendFeedback(Component.literal(restStatus()))
                        1
                    },
                )
                .then(
                    ClientCommands.literal("radius").then(
                        ClientCommands.argument("blocks", DoubleArgumentType.doubleArg(1.0, 16.0)).executes { context ->
                            AreaKillerModule.INSTANCE.setRestRadius(DoubleArgumentType.getDouble(context, "blocks"))
                            save()
                            context.source.sendFeedback(Component.literal(restStatus()))
                            1
                        },
                    ),
                )
                .then(
                    ClientCommands.literal("delay").then(
                        ClientCommands.argument("seconds", DoubleArgumentType.doubleArg(1.0, 120.0)).executes { context ->
                            AreaKillerModule.INSTANCE.setRestDelay(DoubleArgumentType.getDouble(context, "seconds"))
                            save()
                            context.source.sendFeedback(Component.literal(restStatus()))
                            1
                        },
                    ),
                ),
        )
        root.then(
            ClientCommands.literal("overlay").then(
                ClientCommands.argument("on", BoolArgumentType.bool()).executes { context ->
                    AreaKillerModule.INSTANCE.setOverlay(BoolArgumentType.getBool(context, "on"))
                    save()
                    context.source.sendFeedback(Component.literal("Overlay " + (if (config.showOverlay) "on" else "off")))
                    1
                },
            ),
        )

        parent.then(root)
        parent.then(AreaCommands.build())
        parent.then(AreaCommands.buildBrush())

        // The short forms.
        parent.then(
            ClientCommands.literal("addmobkill")
                .executes { context -> addLookedAt(reply(context)) }
                .then(
                    ClientCommands.argument("mob", StringArgumentType.greedyString())
                        .executes { context -> addById(StringArgumentType.getString(context, "mob"), reply(context)) },
                ),
        )
        parent.then(
            ClientCommands.literal("pathfind")
                .executes { context ->
                    context.source.sendFeedback(Component.literal("Usage: /pathfind <x> <y> <z>   or   /pathfind stop"))
                    1
                }
                .then(
                    ClientCommands.literal("stop").executes {
                        PathfinderModule.INSTANCE.setEnabled(false)
                        1
                    },
                )
                .then(
                    ClientCommands.literal("snapshot")
                        .executes { context -> snapshot(context, WalkableSnapshot.DEFAULT_RADIUS) }
                        .then(
                            ClientCommands.argument("radius", IntegerArgumentType.integer(4, WalkableSnapshot.MAX_RADIUS))
                                .executes { context -> snapshot(context, IntegerArgumentType.getInteger(context, "radius")) },
                        ),
                )
                .then(
                    ClientCommands.argument("x", DoubleArgumentType.doubleArg()).then(
                        ClientCommands.argument("y", DoubleArgumentType.doubleArg()).then(
                            ClientCommands.argument("z", DoubleArgumentType.doubleArg()).executes { context -> pathfind(context) },
                        ),
                    ),
                ),
        )

        parent.then(
            ClientCommands.literal("record")
                .executes { context ->
                    context.source.sendFeedback(Component.literal("Human Recorder: " + HumanRecorder.status()))
                    1
                }
                .then(
                    ClientCommands.literal("start").executes { context ->
                        HumanRecorderModule.INSTANCE.begin()
                        context.source.sendFeedback(Component.literal("Human Recorder: " + HumanRecorder.status()))
                        1
                    },
                )
                .then(
                    ClientCommands.literal("stop").executes { context ->
                        HumanRecorderModule.INSTANCE.setEnabled(false)
                        context.source.sendFeedback(Component.literal("Human Recorder: " + HumanRecorder.status()))
                        1
                    },
                )
                .then(
                    ClientCommands.literal("status").executes { context ->
                        context.source.sendFeedback(Component.literal("Human Recorder: " + HumanRecorder.status()))
                        1
                    },
                ),
        )

        parent.then(
            ClientCommands.literal("spawnmob").then(
                ClientCommands.argument("count", IntegerArgumentType.integer(1, MobSpawner.MAX_COUNT)).then(
                    ClientCommands.argument("radius", IntegerArgumentType.integer(1, MobSpawner.MAX_RADIUS)).then(
                        ClientCommands.argument("mob", StringArgumentType.word()).executes { context ->
                            val reply = reply(context)
                            MobSpawner.spawn(
                                IntegerArgumentType.getInteger(context, "count"),
                                IntegerArgumentType.getInteger(context, "radius"),
                                StringArgumentType.getString(context, "mob"),
                                reply.feedback,
                                reply.error,
                            )
                        },
                    ),
                ),
            ),
        )
    }

    private fun snapshot(context: CommandContext<FabricClientCommandSource>, radius: Int): Int {
        val reply = reply(context)
        return WalkableSnapshot.write(radius, reply.feedback, reply.error)
    }

    /** Where an answer goes: the command's own feedback, or the chat when a Click GUI button asked. */
    private class Reply(val feedback: (String) -> Unit, val error: (String) -> Unit)

    private fun reply(context: CommandContext<FabricClientCommandSource>): Reply = Reply(
        { context.source.sendFeedback(Component.literal(it)) },
        { context.source.sendError(Component.literal(it)) },
    )

    private fun chat(): Reply {
        val say = { text: String ->
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("§6[Area Killer] §r$text"))
        }
        return Reply(say, say)
    }

    /** The kill list entry typed into a text field. */
    @JvmStatic
    fun addTyped(text: String) {
        if (text.isBlank()) chat().error("Type a mob id or a name first") else addById(text, chat())
    }

    /** The Click GUI's Remove mob button. */
    @JvmStatic
    fun removeTyped(text: String) {
        if (text.isBlank()) chat().error("Type a mob id or a name first") else remove(text, chat())
    }

    /** The Click GUI's Add looked-at mob button. */
    @JvmStatic
    fun addLookedAt() {
        addLookedAt(chat())
    }

    /** The Click GUI's Show kill list button. */
    @JvmStatic
    fun listMobs() {
        list(chat())
    }

    private fun save() = MacroWorkspaceConfig.save()

    private fun restStatus(): String {
        val point = config.restPoint
        val where = if (point.size == 3) format(point) else "not set"
        return "Rest point: " + where + (if (config.restEnabled) " (on)" else " (off)") +
            ", radius " + config.restRadius + ", after " + config.restDelaySeconds + " s without a mob. " +
            "/areakiller rest set | clear | <true|false> | radius <blocks> | delay <seconds>"
    }

    private fun list(reply: Reply): Int {
        reply.feedback(if (config.mobs.isEmpty()) "Kill list is empty" else "Kill list: " + config.mobs.joinToString(", "))
        return 1
    }

    private fun remove(id: String, reply: Reply): Int {
        if (!config.removeMob(id)) {
            reply.error("$id is not in the kill list")
            return 0
        }
        save()
        reply.feedback("Removed $id from the kill list")
        return 1
    }

    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        source.sendFeedback(Component.literal("Macro " + AreaKillerMacro.describe()))
        source.sendFeedback(
            Component.literal(
                (if (config.gate.from.size == 3) "Gate " + format(config.gate.from) + " to " + format(config.gate.to) + ", margin " + config.margin else "No gate") +
                    (if (config.paintArea.isEmpty()) "" else ", painted area '" + config.paintArea + "'"),
            ),
        )
        source.sendFeedback(Component.literal("Kill list: " + (if (config.mobs.isEmpty()) "empty" else config.mobs.joinToString(", "))))
        source.sendFeedback(
            Component.literal(
                "Clicks sent since the game started: left " + SimulatedMouse.presses(SimulatedMouse.Button.LEFT) +
                    ", right " + SimulatedMouse.presses(SimulatedMouse.Button.RIGHT),
            ),
        )
        source.sendFeedback(
            Component.literal("Pathfinder: " + PathSearch.totals().describe() + "; " + WorldStore.size() + " sections of the world kept"),
        )
        return 1
    }

    private fun setGate(context: CommandContext<FabricClientCommandSource>): Int {
        val from = intArrayOf(
            IntegerArgumentType.getInteger(context, "x1"),
            IntegerArgumentType.getInteger(context, "y1"),
            IntegerArgumentType.getInteger(context, "z1"),
        )
        val to = intArrayOf(
            IntegerArgumentType.getInteger(context, "x2"),
            IntegerArgumentType.getInteger(context, "y2"),
            IntegerArgumentType.getInteger(context, "z2"),
        )
        try {
            GateArea.of(from, to, config.margin, config.bounds)
        } catch (exception: IllegalArgumentException) {
            context.source.sendError(Component.literal(exception.message ?: "The gate is not valid"))
            return 0
        }
        AreaKillerModule.INSTANCE.setGate(from, to)
        save()
        context.source.sendFeedback(Component.literal("Gate set to " + format(from) + " to " + format(to)))
        return 1
    }

    /**
     * Adds the creature under the crosshair to the kill list, however far away it stands. One that has a name of its own -
     * a custom mob, with a name tag over it or a name set on it - goes on by that name, because its type says nothing: a
     * server's creatures are zombies and players alike. Anything else goes on by its type.
     */
    private fun addLookedAt(reply: Reply): Int {
        val client = Minecraft.getInstance()
        val creature = LookedAt.creature(client, LOOK_RANGE)
        val level = client.level
        if (creature == null || level == null) {
            reply.error(
                "Not looking at a mob (within " + LOOK_RANGE.toInt() +
                    " blocks; people playing and name tags are looked through) - or give an id or a name: /addmobkill zombie",
            )
            return 0
        }

        val name = MobNames.nameOf(level, creature)
        val entry: String
        val description: String
        if (name.isNotEmpty()) {
            entry = MobNames.PREFIX + name
            val type = BuiltInRegistries.ENTITY_TYPE.getKey(creature.type)
            description = MobIds.label(name) + " (by name, it is a " + type + ")"
        } else if (creature is Player) {
            reply.error("That is a player entity without any name I can read - give its name: /addmobkill <name>")
            return 0
        } else {
            val type = BuiltInRegistries.ENTITY_TYPE.getKey(creature.type)
            entry = type.toString()
            description = MobIds.label(type.toString()) + " (" + type + ")"
        }

        val added = !config.mobs.contains(entry)
        config.addMob(entry)
        save()
        reply.feedback((if (added) "Added " else "Already on the kill list: ") + description)

        val health = MobNames.healthFraction(level, creature)
        if (!health.isNaN()) {
            reply.feedback(String.format("Health is read from its name tag: %.0f %% right now", health * 100.0))
        }
        return 1
    }

    /** Adds a mob by type if the text is one, and otherwise by name. */
    private fun addById(text: String, reply: Reply): Int {
        if (MobIds.isHuntable(text)) {
            config.addMob(text)
            save()
            reply.feedback("Added " + MobIds.label(MobIds.normalize(text)) + " to the kill list")
            return 1
        }
        val entry = MobIds.nameEntry(text)
        if (entry == null) {
            reply.error("Unknown mob: $text")
            return 0
        }
        config.addMob(entry)
        save()
        reply.feedback("Added " + MobIds.label(entry) + " to the kill list (by name: anything with these words in its name)")
        return 1
    }

    private fun pathfind(context: CommandContext<FabricClientCommandSource>): Int {
        val destination = BlockPos.containing(
            DoubleArgumentType.getDouble(context, "x"),
            DoubleArgumentType.getDouble(context, "y"),
            DoubleArgumentType.getDouble(context, "z"),
        )
        PathfinderModule.INSTANCE.go(destination)
        return 1
    }

    private fun format(position: IntArray): String =
        if (position.size != 3) "none" else position[0].toString() + " " + position[1] + " " + position[2]
}
