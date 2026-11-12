package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.module.Category
import net.minecraft.client.Minecraft

/** Bounded, name-free diagnostics for the Terminal Stats HUD and its title observations. */
object DungeonTerminalStatsDiagnostics {
    private const val STATUS_SAMPLE_TICKS = 20L
    private const val STATUS_HEARTBEAT_TICKS = 100L
    private const val MAX_EVENTS_PER_TICK = 8
    private const val MAX_SUPPRESSED = 1_000_000

    private var lastStatusTick = Long.MIN_VALUE
    private var lastStatus: String? = null
    private var coalescedChanges = 0
    private var eventTick = Long.MIN_VALUE
    private var eventsThisTick = 0
    private var suppressedEvents = 0

    /** Called from the client tick after the module's latest HUD snapshot is published. */
    @JvmStatic
    fun recordStatus(
        clientTick: Long,
        enabled: Boolean,
        inDungeon: Boolean,
        floorName: String?,
        breakdownEnabled: Boolean,
        selfOnly: Boolean,
        terminals: Int,
        levers: Int,
        devices: Int,
        trackedPlayers: Int,
        hudLines: Int,
    ) {
        val module = DungeonTerminalStatsModule.INSTANCE
        val tick = clientTick.coerceAtLeast(0L)
        if (!Minecraft.getInstance().isSameThread) return
        if (!enabled) {
            flushSuppressed(tick)
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), false)
            reset()
            return
        }

        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), true)
        flushSuppressed(tick)
        val status = "enabled=true dungeon=$inDungeon floor=${token(floorName, 24)} " +
            "breakdown=$breakdownEnabled selfOnly=$selfOnly " +
            "counts=terminal:${count(terminals)},lever:${count(levers)},device:${count(devices)} " +
            "players=${count(trackedPlayers)} hudLines=${count(hudLines)}"
        val elapsed = if (lastStatusTick == Long.MIN_VALUE) Long.MAX_VALUE else (tick - lastStatusTick).coerceAtLeast(0L)
        val changed = status != lastStatus
        if (changed && elapsed < STATUS_SAMPLE_TICKS) {
            coalescedChanges = increment(coalescedChanges)
            return
        }
        if (changed || elapsed >= STATUS_HEARTBEAT_TICKS) {
            GeilerAddonsLog.write(
                Category.F7,
                module.name(),
                tick,
                "STATUS $status coalescedChanges=$coalescedChanges",
            )
            lastStatus = status
            lastStatusTick = tick
            coalescedChanges = 0
        }
    }

    /** Records only the bounded completion fields; player names and raw title text are omitted. */
    @JvmStatic
    fun recordCompletion(clientTick: Long, action: String?, type: String?, current: Int, total: Int) {
        val module = DungeonTerminalStatsModule.INSTANCE
        if (!module.isEnabled() || !Minecraft.getInstance().isSameThread) return
        val tick = clientTick.coerceAtLeast(0L)
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, module.name(), true)
        if (eventTick != tick) {
            flushSuppressed(tick)
            eventTick = tick
            eventsThisTick = 0
        }
        if (eventsThisTick >= MAX_EVENTS_PER_TICK) {
            suppressedEvents = increment(suppressedEvents)
            return
        }
        eventsThisTick++
        val safeAction = when (action) {
            "activated", "completed" -> action
            else -> "unknown"
        }
        val safeType = when (type) {
            "terminal", "lever", "device" -> type
            else -> "unknown"
        }
        val step = current.coerceIn(0, 32)
        val boundedTotal = total.coerceIn(0, 32)
        GeilerAddonsLog.write(
            Category.F7,
            module.name(),
            tick,
            "COMPLETE action=$safeAction type=$safeType step=$step/$boundedTotal",
        )
    }

    private fun flushSuppressed(tick: Long) {
        if (suppressedEvents == 0) return
        GeilerAddonsLog.write(
            Category.F7,
            DungeonTerminalStatsModule.INSTANCE.name(),
            tick,
            "EVENTS_SUPPRESSED count=$suppressedEvents cap=$MAX_EVENTS_PER_TICK",
        )
        suppressedEvents = 0
    }

    private fun reset() {
        lastStatusTick = Long.MIN_VALUE
        lastStatus = null
        coalescedChanges = 0
        eventTick = Long.MIN_VALUE
        eventsThisTick = 0
        suppressedEvents = 0
    }

    private fun count(value: Int): Int = value.coerceIn(0, MAX_SUPPRESSED)

    private fun increment(value: Int): Int = if (value >= MAX_SUPPRESSED) MAX_SUPPRESSED else value + 1

    private fun token(value: String?, limit: Int): String {
        if (value.isNullOrBlank()) return "unknown"
        val safe = value.replace(Regex("[^A-Za-z0-9_ .,:/+-]"), "?").trim()
        return if (safe.length <= limit) safe else safe.take(limit)
    }
}
