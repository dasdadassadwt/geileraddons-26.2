package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.f7.F7Support
import geiler.addons.client.dungeon.f7.PersonalBestFile
import geiler.addons.client.dungeon.f7.TerminalSplits
import geiler.addons.client.dungeon.terminal.TerminalType
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.tree.ChatText
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.network.chat.Component

/**
 * Odin's Terminal Times: the time each floor 7 terminal took (open to solved) with a personal best per terminal type,
 * and the section and phase time appended to each Goldor terminal, lever and device chat line, with a summary when the
 * core opens. Solve detection comes from Terminal Solver, so that module must be on. Real time only (Odin's
 * server-tick option is not ported). Written independently after Odin (BSD 3-Clause).
 */
class TerminalTimesModule private constructor(private val s: Settings) : Module(
    "Terminal Times",
    "Records terminal solve times with personal bests and adds split times to terminal chat lines.",
    Category.F7,
    false,
    s.times, s.splits, s.reset,
) {
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 8, 100L, 400)
    private val bests = PersonalBestFile("terminal-personal-bests.json", emptyMap())
    private val splits = TerminalSplits()
    private var solves = 0L
    private var splitLines = 0L

    override fun onEnable() {
        splits.clear()
        solves = 0
        splitLines = 0
        diagnostics.enable("times=${s.times.value()};splits=${s.splits.value()}")
    }

    override fun onDisable() {
        splits.clear()
        diagnostics.disable()
    }

    /** Terminal Solver reports a solved terminal [elapsedMillis] after it opened. */
    fun onSolved(type: TerminalType, elapsedMillis: Long) {
        if (!isEnabled() || !s.times.value()) return
        val seconds = elapsedMillis / 1000f
        val outcome = bests.record(type.name, seconds)
        val tail = if (outcome.newBest) "§7(§d§lNew PB§r§7) Old PB was §8${outcome.old}" else "§8(§7${outcome.old}§8)"
        F7Support.message("§a${label(type)} §7solved in §6${TerminalSplits.seconds(seconds)}s§7! $tail")
        solves++
        diagnostics.event("SOLVE type=${type.name} ms=${elapsedMillis.coerceIn(0L, 3_600_000L)} newBest=${outcome.newBest} solves=$solves")
    }

    /** Appends the split to a terminal chat line, and prints the phase summary when the core opens. */
    fun modify(message: Component, overlay: Boolean): Component {
        if (!isEnabled() || overlay || !s.splits.value() || !DungeonContextTracker.inDungeon()) return message
        diagnostics.advance()
        val observation = splits.observe(ChatText.plain(message.string).trim(), System.currentTimeMillis())
        observation.split?.let {
            splitLines++
            diagnostics.event("SPLIT section=${TerminalSplits.seconds(it.sectionSeconds)} phase=${TerminalSplits.seconds(it.phaseSeconds)} lines=$splitLines")
            return message.copy().append(
                Component.literal(" §8(§7${TerminalSplits.seconds(it.sectionSeconds)}s §8| §7${TerminalSplits.seconds(it.phaseSeconds)}s§8)"),
            )
        }
        observation.summary?.let {
            F7Support.message(
                "§bTimes: " + it.sectionTimes.joinToString(" §8| ") { t -> "§a${TerminalSplits.seconds(t)}s" } +
                    "§8, §bTotal: §a${TerminalSplits.seconds(it.totalSeconds)}s",
            )
            diagnostics.event("SUMMARY sections=${it.sectionTimes.size} total=${TerminalSplits.seconds(it.totalSeconds)}")
        }
        return message
    }

    private fun label(type: TerminalType): String = when (type) {
        TerminalType.PANES -> "Panes"
        TerminalType.RUBIX -> "Rubix"
        TerminalType.NUMBERS -> "Numbers"
        TerminalType.STARTS_WITH -> "Starts With"
        TerminalType.SELECT -> "Select"
        TerminalType.MELODY -> "Melody"
    }

    class Settings {
        val times = BooleanSetting("Terminal Times", true)
        val splits = BooleanSetting("Terminal Splits", true)
        val reset = ModuleAction("Reset PBs", "Forgets every saved terminal personal best.", Runnable {
            INSTANCE.bests.reset()
            F7Support.message("§6Terminal PBs §fhave been reset.")
        })
    }

    companion object {
        @JvmField
        val INSTANCE = TerminalTimesModule(Settings())

        init {
            ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay -> INSTANCE.modify(message, overlay) }
        }
    }
}
