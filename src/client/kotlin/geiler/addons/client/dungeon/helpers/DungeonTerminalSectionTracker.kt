package geiler.addons.client.dungeon.helpers

import java.util.ArrayList
import java.util.Locale
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord

private val TASK = Pattern.compile(
    "^(\\w+) (?:activated|completed) a (terminal|lever|device)! \\((\\d)/\\d\\)$",
    Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS,
)
private val TERMINAL_TARGETS = intArrayOf(4, 5, 4, 4)

/** Client-observed F7 terminal section boundaries using the same task and carry-over rules as Devonian. */
class DungeonTerminalSectionTracker {
    private val sections = Array(4) { index -> Section(index == 3) }
    private val finished = ArrayList<Reading>()
    private val newlyFinished = ArrayList<Reading>()
    private var activeIndex = -1
    private var started = false
    private var startedNanos = 0L
    private var startedTick = 0L
    private var startedPingPackets = 0L
    private var startedServerTicks = 0L

    /** Starts S1 at the observed Goldor domain or first terminal-task signal. */
    fun begin(nowNanos: Long, clientTick: Long, serverPingPackets: Long): Boolean =
        begin(nowNanos, clientTick, serverPingPackets, 0L)

    fun begin(nowNanos: Long, clientTick: Long, serverPingPackets: Long, serverTickCount: Long): Boolean {
        if (started) return false
        reset()
        started = true
        activeIndex = 0
        startAt(nowNanos, clientTick, serverPingPackets, serverTickCount)
        return true
    }

    /** Applies one exact client chat observation and advances only when the source requirements pass. */
    fun observeChat(plainMessage: String?, nowNanos: Long, clientTick: Long, serverPingPackets: Long): Boolean =
        observeChat(plainMessage, nowNanos, clientTick, serverPingPackets, 0L)

    fun observeChat(
        plainMessage: String?,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): Boolean {
        if (activeIndex < 0 || plainMessage == null) return false
        val section = sections[activeIndex]
        if (plainMessage == "The gate has been destroyed!") {
            section.gateDestroyed = true
        } else {
            val matcher = TASK.matcher(plainMessage)
            if (!matcher.matches()) return false
            val player = matcher.group(1)
            val type = matcher.group(2).lowercase(Locale.ROOT)
            val index = matcher.group(3).toInt()
            applyTask(activeIndex, player, type, index)
            section.lastPlayer = player
            section.lastIndex = index
            section.lastType = type
        }

        if (!section.ready(TERMINAL_TARGETS[activeIndex])) return true
        closeActive(nowNanos, clientTick, serverPingPackets, serverTickCount)
        if (activeIndex < sections.size - 1) {
            activeIndex++
            startAt(nowNanos, clientTick, serverPingPackets, serverTickCount)
        } else {
            activeIndex = -1
        }
        return true
    }

    /** Goldor's observed Core opening stops Devonian's nested terminal sequence. */
    fun stopAtCore(nowNanos: Long, clientTick: Long, serverPingPackets: Long): Boolean =
        stopAtCore(nowNanos, clientTick, serverPingPackets, 0L)

    fun stopAtCore(nowNanos: Long, clientTick: Long, serverPingPackets: Long, serverTickCount: Long): Boolean {
        if (activeIndex < 0) return false
        closeActive(nowNanos, clientTick, serverPingPackets, serverTickCount)
        activeIndex = -1
        return true
    }

    fun snapshot(nowNanos: Long, clientTick: Long, serverPingPackets: Long): List<Reading> =
        snapshot(nowNanos, clientTick, serverPingPackets, 0L)

    fun snapshot(
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
    ): List<Reading> {
        val rows = ArrayList(finished)
        if (activeIndex >= 0) {
            rows.add(reading(activeIndex, nowNanos, clientTick, serverPingPackets, serverTickCount, false))
        }
        return java.util.List.copyOf(rows)
    }

    fun drainNewlyFinished(): List<Reading> {
        if (newlyFinished.isEmpty()) return java.util.List.of()
        val rows = java.util.List.copyOf(newlyFinished)
        newlyFinished.clear()
        return rows
    }

    fun reset() {
        for (index in sections.indices) sections[index] = Section(index == 3)
        finished.clear()
        newlyFinished.clear()
        activeIndex = -1
        started = false
        startedNanos = 0L
        startedTick = 0L
        startedPingPackets = 0L
        startedServerTicks = 0L
    }

    fun active(): Boolean = activeIndex >= 0

    private fun applyTask(currentSection: Int, player: String, type: String, index: Int) {
        val current = sections[currentSection]
        if (index == current.lastIndex) {
            if (player == current.lastPlayer && type == current.lastType) return
            if (type == "device") {
                if (currentSection == 0) {
                    if (!sections[3].deviceDone) sections[3].deviceDone = true
                    else if (!sections[1].deviceDone) sections[1].deviceDone = true
                } else if (currentSection == 1 && !sections[3].deviceDone) {
                    sections[3].deviceDone = true
                }
                return
            } else if (current.lastType == "device") {
                current.deviceDone = false
            }
        } else if (index == 2 && current.lastIndex == 0) {
            if (!current.deviceDone && currentSection == 1 && sections[3].deviceDone) {
                sections[3].deviceDone = false
            }
            current.deviceDone = true
        } else if (index == 1) {
            if (type == "device") {
                if (currentSection == 0) {
                    if (!sections[3].deviceDone) sections[3].deviceDone = true
                    else if (!sections[1].deviceDone) sections[1].deviceDone = true
                    return
                }
            } else {
                if (current.deviceDone && currentSection == 1 && !sections[3].deviceDone) {
                    sections[3].deviceDone = true
                }
                current.deviceDone = false
            }
        }

        when (type) {
            "terminal" -> current.terminalsDone++
            "lever" -> current.leversDone++
            "device" -> {
                if (current.deviceDone && currentSection == 1) sections[3].deviceDone = true
                current.deviceDone = true
            }
            else -> Unit
        }
    }

    private fun startAt(nowNanos: Long, clientTick: Long, serverPingPackets: Long, serverTickCount: Long) {
        startedNanos = nowNanos
        startedTick = clientTick
        startedPingPackets = serverPingPackets
        startedServerTicks = serverTickCount
    }

    private fun closeActive(nowNanos: Long, clientTick: Long, serverPingPackets: Long, serverTickCount: Long) {
        val closed = reading(activeIndex, nowNanos, clientTick, serverPingPackets, serverTickCount, true)
        finished.add(closed)
        newlyFinished.add(closed)
    }

    private fun reading(
        index: Int,
        nowNanos: Long,
        clientTick: Long,
        serverPingPackets: Long,
        serverTickCount: Long,
        complete: Boolean,
    ): Reading {
        val id = "S${index + 1}"
        return Reading(
            id,
            id,
            maxOf(0L, nowNanos - startedNanos),
            maxOf(0L, clientTick - startedTick),
            maxOf(0L, serverPingPackets - startedPingPackets),
            maxOf(0L, serverTickCount - startedServerTicks),
            complete,
        )
    }

    private class Section(var gateDestroyed: Boolean) {
        var terminalsDone = 0
        var leversDone = 0
        var deviceDone = false
        var lastPlayer = ""
        var lastIndex = 0
        var lastType = ""

        fun ready(terminalTarget: Int): Boolean =
            terminalsDone >= terminalTarget && leversDone >= 2 && deviceDone && gateDestroyed
    }

    @JvmRecord
    data class Reading(
        val id: String?,
        val label: String?,
        val elapsedNanos: Long,
        val elapsedTicks: Long,
        val elapsedPingPackets: Long,
        val elapsedServerTicks: Long,
        val complete: Boolean,
    ) {
        constructor(
            id: String?,
            label: String?,
            elapsedNanos: Long,
            elapsedTicks: Long,
            elapsedPingPackets: Long,
            complete: Boolean,
        ) : this(id, label, elapsedNanos, elapsedTicks, elapsedPingPackets, 0L, complete)

        override fun toString() =
            "Reading[id=$id, label=$label, elapsedNanos=$elapsedNanos, elapsedTicks=$elapsedTicks, " +
                "elapsedPingPackets=$elapsedPingPackets, elapsedServerTicks=$elapsedServerTicks, complete=$complete]"
    }
}
