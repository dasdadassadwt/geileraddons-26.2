package geiler.addons.client.command

import com.mojang.brigadier.arguments.StringArgumentType
import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.dungeon.DungeonStatsCommand
import geiler.addons.client.gui.ClickGuiStyle
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.macros.command.MacroCommands
import geiler.addons.client.gui.ConfigResetScreen
import geiler.addons.client.gui.ConfigTransferScreen
import geiler.addons.client.module.impl.PartyFinderStatsModule
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component

/** Brigadier commands owned by the client mod, including tab-completable /ga commands. */
object GeilerAddonsCommand {
    @JvmStatic
    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = ClientCommands.literal("ga")
                .executes { openClickGui() }
                .then(
                    ClientCommands.literal("reset")
                        .executes { openResetScreen() },
                )
                .then(
                    ClientCommands.literal("config")
                        .then(
                            ClientCommands.literal("export")
                                .executes { exportConfig("") }
                                .then(
                                    ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .executes { context ->
                                            exportConfig(StringArgumentType.getString(context, "name"))
                                        },
                                ),
                        )
                        .then(
                            ClientCommands.literal("import")
                                .executes { openConfigTransfer() },
                        )
                        .then(
                            ClientCommands.literal("save")
                                .executes { saveConfig() },
                        )
                        .then(
                            ClientCommands.literal("folder")
                                .executes { openConfigFolder() },
                        ),
                )
                .then(
                    ClientCommands.literal("dstats")
                        .executes { DungeonStatsCommand.lookupLocalPlayer() }
                        .then(
                            ClientCommands.argument("name", StringArgumentType.string())
                                .suggests { _, builder ->
                                    val player = Minecraft.getInstance().player
                                    if (player != null && player.connection != null) {
                                        val profileNames: List<String?> = player.connection.getListedOnlinePlayers()
                                            .map { info -> info.getProfile().name() }
                                        for (suggestion in DungeonStatsCommand.suggestions(
                                            profileNames,
                                            builder.getRemaining(),
                                        )) {
                                            builder.suggest(suggestion)
                                        }
                                    }
                                    builder.buildFuture()
                                }
                                .executes { context ->
                                    DungeonStatsCommand.lookup(StringArgumentType.getString(context, "name"))
                                },
                        ),
                )
                .then(
                    ClientCommands.literal("startdoor")
                        .executes { StartDoorAnchorCaptureCommand.usage() }
                        .then(
                            ClientCommands.literal("mark")
                                .executes { StartDoorAnchorCaptureCommand.mark() },
                        )
                        .then(
                            ClientCommands.literal("cancel")
                                .executes { StartDoorAnchorCaptureCommand.cancel() },
                        )
                        .then(
                            ClientCommands.literal("reset")
                                .executes { StartDoorAnchorCaptureCommand.cancel() },
                        ),
                )
                .then(
                    ClientCommands.literal("pfretry")
                        .executes { DungeonStatsCommand.showRetryUsage() }
                        .then(
                            ClientCommands.argument("name", StringArgumentType.word())
                                .executes { context ->
                                    DungeonStatsCommand.retryPartyMember(
                                        StringArgumentType.getString(context, "name"),
                                    )
                                },
                        ),
                )
                .then(
                    ClientCommands.literal("pfstats")
                        .executes { PartyFinderStatsModule.INSTANCE.showLoadedPartyStats() },
                )
            UtilityCommandBuilder.appendTo(root)
            MacroCommands.appendTo(root)
            val registeredRoot = dispatcher.register(root)
            for (alias in arrayOf("Ga", "gA", "GA")) {
                dispatcher.register(
                    ClientCommands.literal(alias)
                        .executes { openClickGui() }
                        .redirect(registeredRoot),
                )
            }
        }
    }

    private fun openClickGui(): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        Minecraft.getInstance().execute {
            Minecraft.getInstance().gui.setScreen(ClickGuiStyle.screen(VisualModule.INSTANCE.clickGuiStyle()))
        }
        return 1
    }

    private fun openResetScreen(): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        val gui = Minecraft.getInstance().gui
        gui.setScreen(ConfigResetScreen(gui.screen()))
        return 1
    }

    private fun openConfigTransfer(): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        val gui = Minecraft.getInstance().gui
        gui.setScreen(ConfigTransferScreen(gui.screen()))
        return 1
    }

    private fun exportConfig(name: String): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        Minecraft.getInstance().gui.hud.getChat()
            .addClientSystemMessage(Component.literal("[GeilerAddons] Exporting configuration…"))
        ConfigTransferService.exportProfile(name, ::reportResult)
        return 1
    }

    private fun saveConfig(): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        Minecraft.getInstance().gui.hud.getChat()
            .addClientSystemMessage(Component.literal("[GeilerAddons] Saving configuration…"))
        ConfigTransferService.saveCurrent(::reportResult)
        return 1
    }

    private fun openConfigFolder(): Int {
        if (ConfigTransferService.isBusy()) return busyNotice()
        ConfigTransferService.openFolder(ConfigTransferService.configDirectory())
        return 1
    }

    private fun busyNotice(): Int {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
            Component.literal("[GeilerAddons] A configuration operation is in progress; wait for it to finish first."),
        )
        return 0
    }

    private fun reportResult(result: ConfigTransferService.Result) {
        val chat = Minecraft.getInstance().gui.hud.getChat()
        chat.addClientSystemMessage(Component.literal("[GeilerAddons] ${result.message()}"))
        result.path()?.let { path ->
            val label = path.toAbsolutePath().toString()
            chat.addClientSystemMessage(
                Component.literal("Saved: $label  [Open GeilerAddons folder]")
                    .withStyle { style ->
                        style.withUnderlined(true)
                            .withClickEvent(ClickEvent.RunCommand("/ga config folder"))
                    },
            )
        }
        result.recoveryPath()?.let { recoveryPath ->
            chat.addClientSystemMessage(
                Component.literal("Recovery profile: ${recoveryPath.toAbsolutePath()}  [Open folder]")
                    .withStyle { style ->
                        style.withUnderlined(true)
                            .withClickEvent(ClickEvent.RunCommand("/ga config folder"))
                    },
            )
        }
    }
}
