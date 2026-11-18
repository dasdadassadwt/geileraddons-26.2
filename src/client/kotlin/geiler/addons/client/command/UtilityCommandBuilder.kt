package geiler.addons.client.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import geiler.addons.client.module.impl.GeneralModule
import geiler.addons.client.module.impl.LeverAimAssistModule
import geiler.addons.client.module.impl.UtilityTimerModule
import geiler.addons.client.utility.CarriedSupplyCounter
import geiler.addons.client.utility.CarriedSupplyPlanner
import geiler.addons.client.utility.TimerDurationParser
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** Feature-local builder to attach utility children beneath the existing `/ga` root. */
object UtilityCommandBuilder {
    private val supplyDiagnosticCategory = Category.MISCELLANEOUS
    private const val SUPPLY_DIAGNOSTIC_MODULE = "Supply Commands"
    private const val MAX_SUPPLY_DIAGNOSTICS_PER_TICK = 16
    private var supplyDiagnosticTick = Long.MIN_VALUE
    private var supplyDiagnosticsThisTick = 0
    private var suppressedSupplyDiagnostics = 0L

    @JvmStatic
    fun appendTo(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        appendSupplyCommand(root, "ep", CarriedSupplyPlanner.Supply.ENDER_PEARL)
        appendSupplyCommand(root, "sb", CarriedSupplyPlanner.Supply.SUPERBOOM_TNT)
        root.then(
            ClientCommands.literal("testlever")
                .executes { testLever(true) }
                .then(ClientCommands.literal("clear").executes { testLever(false) }),
        )
        root.then(
            ClientCommands.literal("chat")
                .executes { chatUsage() }
                .then(
                    ClientCommands.argument("text", StringArgumentType.greedyString())
                        .executes { context -> fakeChat(StringArgumentType.getString(context, "text")) },
                ),
        )
        root.then(
            ClientCommands.literal("timer")
                .executes { getTimer() }
                .then(ClientCommands.literal("get").executes { getTimer() })
                .then(
                    ClientCommands.literal("set")
                        .executes { timerUsage() }
                        .then(
                            ClientCommands.argument("duration", StringArgumentType.greedyString())
                                .executes { context ->
                                    setTimer(StringArgumentType.getString(context, "duration"))
                                },
                        ),
                )
                .then(ClientCommands.literal("pause").executes { pauseTimer() })
                .then(ClientCommands.literal("resume").executes { resumeTimer() })
                .then(ClientCommands.literal("reset").executes { resetTimer() }),
        )
    }

    private fun testLever(place: Boolean): Int {
        if (!place) {
            LeverAimAssistModule.INSTANCE.clearTestLevers()
            supplyMessage("Test levers removed.")
            return 1
        }
        val placed = LeverAimAssistModule.INSTANCE.placeTestLevers()
        when {
            placed < 0 -> supplyMessage("Join a world first.")
            LeverAimAssistModule.INSTANCE.isEnabled() ->
                supplyMessage("Placed $placed test levers ahead of you. /ga testlever clear removes them.")
            else -> supplyMessage("Placed $placed test levers, but Lever Aim Assist is off. Enable it to try it.")
        }
        return 1
    }

    private fun chatUsage(): Int {
        supplyMessage("Usage: /ga chat <text>. & colour codes work. The line goes through the same path as a server message.")
        return 1
    }

    /** Debug aid: shows [text] as a game chat line and runs every chat listener on it, as if the server had sent it. */
    private fun fakeChat(text: String): Int {
        val mc = Minecraft.getInstance()
        val message = Component.literal(text.replace('&', '§'))
        val shown = ClientReceiveMessageEvents.ALLOW_GAME.invoker().allowReceiveGameMessage(message, false)
        if (shown) mc.gui.hud.getChat().addClientSystemMessage(message)
        ClientReceiveMessageEvents.GAME.invoker().onReceiveGameMessage(message, false)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.DEV, "Chat Debug", true)
        GeilerAddonsLog.write(Category.DEV, "Chat Debug", mc.level?.gameTime ?: 0L, "CHAT_LINE shown=$shown length=${text.length}")
        return 1
    }

    private fun appendSupplyCommand(
        root: LiteralArgumentBuilder<FabricClientCommandSource>,
        literal: String,
        supply: CarriedSupplyPlanner.Supply,
    ) {
        root.then(
            ClientCommands.literal(literal)
                .executes { requestSupply(supply, supply.targetCount()) }
                .then(
                    ClientCommands.argument("amount", IntegerArgumentType.integer(1))
                        .executes { context ->
                            requestSupply(supply, IntegerArgumentType.getInteger(context, "amount"))
                        },
                ),
        )
    }

    private fun requestSupply(supply: CarriedSupplyPlanner.Supply, targetCount: Int): Int {
        val mc = Minecraft.getInstance()
        val scan = CarriedSupplyCounter.scan(mc, supply)
        val player = mc.player
        val connection = player?.connection
        if (player == null || connection == null) {
            recordSupplyDiagnostic(mc, supply, targetCount, scan, 0, "none", "no-player-connection")
            supplyMessage("Join a world before requesting carried supplies.")
            return 0
        }
        val carried = scan.carriedCount
        val command = CarriedSupplyPlanner.withdrawalCommand(supply, carried, targetCount)
        if (!command.isPresent) {
            recordSupplyDiagnostic(mc, supply, targetCount, scan, 0, "none", "already-at-target")
            supplyMessage("Carrying $carried/$targetCount ${supply.displayName()}; no /gfs command sent.")
            return 1
        }
        val commandText = command.get()
        connection.sendCommand(commandText)
        val requested = CarriedSupplyPlanner.deficit(supply, carried, targetCount)
        recordSupplyDiagnostic(mc, supply, targetCount, scan, requested, commandText, "command-sent")
        supplyMessage("Requesting $requested ${supply.displayName()} (carrying $carried/$targetCount).")
        return 1
    }

    private fun recordSupplyDiagnostic(
        mc: Minecraft,
        supply: CarriedSupplyPlanner.Supply,
        targetCount: Int,
        scan: CarriedSupplyCounter.ScanResult,
        requested: Int,
        command: String,
        outcome: String,
    ) {
        val tick = mc.level?.gameTime ?: 0L
        GeilerAddonsLog.setModuleDiagnosticsEnabled(
            supplyDiagnosticCategory,
            SUPPLY_DIAGNOSTIC_MODULE,
            true,
        )
        if (supplyDiagnosticTick != tick) {
            if (suppressedSupplyDiagnostics > 0) {
                GeilerAddonsLog.write(
                    supplyDiagnosticCategory,
                    SUPPLY_DIAGNOSTIC_MODULE,
                    supplyDiagnosticTick,
                    "EVENTS_SUPPRESSED count=$suppressedSupplyDiagnostics " +
                        "limit=$MAX_SUPPLY_DIAGNOSTICS_PER_TICK",
                )
            }
            supplyDiagnosticTick = tick
            supplyDiagnosticsThisTick = 0
            suppressedSupplyDiagnostics = 0
        }
        if (supplyDiagnosticsThisTick >= MAX_SUPPLY_DIAGNOSTICS_PER_TICK) {
            suppressedSupplyDiagnostics++
            return
        }
        supplyDiagnosticsThisTick++
        GeilerAddonsLog.write(
            supplyDiagnosticCategory,
            SUPPLY_DIAGNOSTIC_MODULE,
            tick,
            "SUPPLY_REQUEST item=${supply.skyBlockId()} " +
                "inventoryAvailable=${scan.available} " +
                "slots=${scan.slotsScanned} " +
                "nonEmptyStacks=${scan.nonEmptyStacks} " +
                "customDataStacks=${scan.customDataStacks} " +
                "firstMatchFound=${scan.firstMatchFound} " +
                "firstMatchingSlot=${scan.firstMatchingSlot} " +
                "carried=${scan.carriedCount} target=$targetCount requested=$requested " +
                "command=$command outcome=$outcome",
        )
    }

    private fun getTimer(): Int {
        UtilityTimerModule.INSTANCE.tick()
        message(UtilityTimerModule.INSTANCE.state().status(System.nanoTime()))
        return 1
    }

    private fun setTimer(input: String): Int {
        val seconds = TimerDurationParser.parseSeconds(input)
        if (seconds.isEmpty) {
            message("Use /ga timer set <duration>, for example 1h 20m 10s or 90s.")
            return 0
        }
        val parsedSeconds = seconds.asLong
        UtilityTimerModule.INSTANCE.setTimer(parsedSeconds)
        message("Timer started: ${TimerDurationParser.formatSeconds(parsedSeconds)}.")
        return 1
    }

    private fun pauseTimer(): Int {
        UtilityTimerModule.INSTANCE.tick()
        if (!UtilityTimerModule.INSTANCE.pauseTimer()) {
            message("The timer is not running.")
            return 0
        }
        message("Timer paused: ${UtilityTimerModule.INSTANCE.state().status(System.nanoTime())}.")
        return 1
    }

    private fun resumeTimer(): Int {
        UtilityTimerModule.INSTANCE.tick()
        if (!UtilityTimerModule.INSTANCE.resumeTimer()) {
            message("No paused timer can be resumed.")
            return 0
        }
        message("Timer resumed.")
        return 1
    }

    private fun resetTimer(): Int {
        UtilityTimerModule.INSTANCE.tick()
        if (!UtilityTimerModule.INSTANCE.resetTimer()) {
            message("No timer has been set.")
            return 0
        }
        message(
            "Timer reset to ${TimerDurationParser.formatSeconds(
                UtilityTimerModule.INSTANCE.state().initialDurationSeconds(),
            )} (paused).",
        )
        return 1
    }

    private fun timerUsage(): Int {
        message("Use /ga timer set <duration>, for example 1h 20m 10s or 90s.")
        return 0
    }

    private fun supplyMessage(text: String) {
        if (GeneralModule.INSTANCE.supplyMessagesEnabled()) message(text)
    }

    private fun message(text: String) {
        val mc = Minecraft.getInstance()
        if (mc.gui != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[GA] $text"))
        }
    }
}
