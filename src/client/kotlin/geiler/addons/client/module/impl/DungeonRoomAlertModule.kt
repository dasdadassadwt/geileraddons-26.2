package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonGridRules
import geiler.addons.client.dungeon.DungeonMapGeometry
import geiler.addons.client.dungeon.DungeonMapRoomDetector
import geiler.addons.client.dungeon.DungeonRoomAlertTracker
import geiler.addons.client.hud.TitleAlertStack
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import java.util.Locale

/** Alerts on separate room-clear and all-secrets map transitions. */
class DungeonRoomAlertModule private constructor(private val settings: Settings) : Module(
    "Room Alerts",
    "Shows separate alerts when a room clears and when all map-marked secrets are found.",
    Category.F7,
    false,
    settings.roomClear, settings.roomClearTitle, settings.roomClearText, settings.roomClearColor,
    settings.roomClearSoundEnabled, settings.roomClearSound, settings.roomClearVolume, settings.roomClearPitch,
    settings.secretsComplete, settings.secretsTitle, settings.secretsText, settings.secretsColor,
    settings.secretsSoundEnabled, settings.secretsSound, settings.secretsVolume, settings.secretsPitch,
    settings.alertDuration,
) {
    private val tracker = DungeonRoomAlertTracker()
    private var lastLevel: ClientLevel? = null
    private var diagnosticTick = 0L
    private var lastDiagnosticTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""
    private var suppressedDiagnosticChanges = 0L

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    /** Retain the original config prefix after shortening the visible name. */
    override fun configName(): String = "Dungeon Room Alerts"

    override fun isActive(): Boolean = isEnabled() && DungeonContextTracker.inDungeon()

    override fun inactiveReason(): String? =
        if (isEnabled() && !DungeonContextTracker.inDungeon()) "Waiting for a dungeon" else null

    fun tick() {
        val client = Minecraft.getInstance()
        val tick = ++diagnosticTick
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.F7, name(), isEnabled())
        if (client.level !== lastLevel || !DungeonContextTracker.inDungeon()) {
            lastLevel = client.level
            tracker.clear()
            clearAlerts()
        }
        if (!isEnabled()) {
            clearAlerts()
            return
        }
        if (!isActive() || client.level == null || client.player == null) {
            tracker.observe(emptyList(), DungeonMapGeometry.RoomClearStatus.UNKNOWN)
            reportDiagnostics(
                tick,
                "context=inactive,dungeon=${DungeonContextTracker.inDungeon()}",
                false,
            )
            return
        }
        val reading = DungeonMapRoomDetector.current()
        val cell = reading?.currentCell
        if (reading == null || !reading.hasCurrentCell() || cell == null) {
            tracker.observe(emptyList(), DungeonMapGeometry.RoomClearStatus.UNKNOWN)
            reportDiagnostics(
                tick,
                "context=active,map=unavailable,reading=${reading?.status ?: "null"},reason=" +
                    bounded(reading?.fallbackReason ?: "none", MAX_DIAGNOSTIC_TEXT_CHARS),
                false,
            )
            return
        }

        val roomCells = roomCells(reading, cell)
        val status = DungeonMapRoomDetector.currentRoomClearStatus()
        val decision = tracker.observe(roomCells, status)
        val events = decision.events
        val outputs = StringBuilder()
        if (events.roomCleared) {
            val showRoomClearTitle = settings.roomClearTitle.value()
            val output = if (settings.roomClear.value()) {
                alert(
                    "room.clear",
                    "Room Cleared",
                    showRoomClearTitle,
                    settings.roomClearText.value(),
                    settings.roomClearColor.argb(),
                    settings.roomClearSoundEnabled.value(),
                    settings.roomClearSound.value(),
                    settings.roomClearVolume.value(),
                    settings.roomClearPitch.value(),
                )
            } else {
                "alert-disabled"
            }
            appendOutput(outputs, "room=${output}")
        }
        if (events.secretsComplete) {
            val output = if (settings.secretsComplete.value()) {
                alert(
                    "room.secrets",
                    "Secrets Cleared",
                    settings.secretsTitle.value(),
                    settings.secretsText.value(),
                    settings.secretsColor.argb(),
                    settings.secretsSoundEnabled.value(),
                    settings.secretsSound.value(),
                    settings.secretsVolume.value(),
                    settings.secretsPitch.value(),
                    // A map that jumps straight to all-secrets also clears the room: keep both lines visible.
                    subtitle = events.roomCleared && settings.roomClear.value() && settings.roomClearTitle.value(),
                )
            } else {
                "alert-disabled"
            }
            appendOutput(outputs, "secrets=${output}")
        }

        val mapId = reading.mapId
        val geometry = reading.geometry
        reportDiagnostics(
            tick,
            "context=active,floor=${bounded(floorName(), 32)}" +
                ",mapId=${mapId?.id() ?: "unknown"}" +
                ",cell=${cell.componentX},${cell.componentZ}" +
                ",room=${geometry?.roomType()?.label() ?: "unknown"}" +
                "/${geometry?.shape()?.let { bounded(it, 24) } ?: "unknown"}" +
                ",footprint=${roomCells.size}/${Integer.toHexString(roomCells.hashCode())}" +
                ",mapStatus=${reading.status},checkmark=${status}" +
                ",tracker=${decision.disposition}/${decision.stableObservations}/5" +
                ",events=${events.roomCleared}/${events.secretsComplete}" +
                ",outputs=${if (outputs.length == 0) "none" else outputs}",
            events.roomCleared || events.secretsComplete,
        )
    }

    private fun roomCells(reading: DungeonMapRoomDetector.Reading, cell: DungeonGridRules.Cell): List<String> {
        val floor = DungeonContextTracker.currentFloor()
        val mapId = reading.mapId
        val prefix = (floor?.name ?: "unknown") + "|" + (mapId?.id() ?: "unknown") + "|"
        val geometry = reading.geometry
        val connected = geometry?.connectedCells()?.takeIf { it.isNotEmpty() }
            ?: listOf(DungeonMapGeometry.CellOffset(0, 0))
        return connected.take(36)
            .map { offset -> prefix + (cell.componentX + offset.x) + "," + (cell.componentZ + offset.z) }
            .sorted()
    }

    private fun alert(
        source: String,
        fallbackTitle: String,
        showTitle: Boolean,
        text: String?,
        color: Int,
        soundEnabled: Boolean,
        sound: String?,
        volume: Float,
        pitch: Float,
        subtitle: Boolean = false,
    ): String {
        var titleResult = "hidden:#${String.format(Locale.ROOT, "%08X", color)}"
        if (showTitle) {
            val title = text?.takeIf { it.isNotBlank() } ?: fallbackTitle
            if (subtitle) TitleAlertStack.submitSubtitle(source, title, settings.alertDuration.intValue(), color)
            else TitleAlertStack.submit(source, title, settings.alertDuration.intValue(), color)
            titleResult =
                (if (subtitle) "subtitle:" else "shown:") +
                    "#${String.format(Locale.ROOT, "%08X", color)}:${bounded(title, MAX_DIAGNOSTIC_TEXT_CHARS)}"
        }
        val soundResult = if (soundEnabled) playSound(sound, volume, pitch) else "disabled"
        return "title=${titleResult},placement=fixed-latest-wins,sound=${soundResult},soundId=" +
            "${bounded(sound, 96)},volume=${volume},pitch=${pitch}"
    }

    private fun appendOutput(outputs: StringBuilder, value: String) {
        if (outputs.isNotEmpty()) outputs.append(';')
        outputs.append(value)
    }

    private fun reportDiagnostics(tick: Long, rawState: String, oneShotEvent: Boolean) {
        val state = bounded(rawState, MAX_DIAGNOSTIC_CHARS)
        val changed = state != lastDiagnosticState
        val elapsed = if (lastDiagnosticTick == Long.MIN_VALUE) Long.MAX_VALUE else tick - lastDiagnosticTick
        if (!oneShotEvent && !changed && elapsed < DIAGNOSTIC_HEARTBEAT_TICKS) return
        if (!oneShotEvent && changed && elapsed < DIAGNOSTIC_MIN_INTERVAL_TICKS) {
            suppressedDiagnosticChanges++
            return
        }
        GeilerAddonsLog.write(
            Category.F7,
            name(),
            tick,
            "Room alert decision: ${state}, suppressedChanges=${suppressedDiagnosticChanges}",
        )
        lastDiagnosticState = state
        lastDiagnosticTick = tick
        suppressedDiagnosticChanges = 0
    }

    private fun floorName(): String = DungeonContextTracker.currentFloor()?.name ?: "unknown"

    private fun bounded(value: String?, maxChars: Int): String {
        if (value.isNullOrBlank()) return "none"
        val safe = value.replace('\r', ' ').replace('\n', ' ')
        return if (safe.length <= maxChars) safe else safe.substring(0, maxOf(0, maxChars - 3)) + "..."
    }

    private fun clearAlerts() {
        TitleAlertStack.clearSource("room.clear")
        TitleAlertStack.clearSource("room.secrets")
    }

    private fun playSound(sound: String?, volume: Float, pitch: Float): String {
        val id = Identifier.tryParse(sound?.trim() ?: "") ?: return "invalid-id"
        val event: SoundEvent = BuiltInRegistries.SOUND_EVENT.getValue(id) ?: return "unregistered"
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
        return "played"
    }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Room Alerts")
        val roomClear = spec.toggle("roomClear")
        val roomClearTitle = spec.toggle("roomClearTitle")
        val roomClearText = spec.text("roomClearText")
        val roomClearColor = spec.color("roomClearColor")
        val roomClearSoundEnabled = spec.toggle("roomClearSoundEnabled")
        val roomClearSound = spec.text("roomClearSound")
        val roomClearVolume = spec.slider("roomClearVolume")
        val roomClearPitch = spec.slider("roomClearPitch")
        val secretsComplete = spec.toggle("secretsComplete")
        val secretsTitle = spec.toggle("secretsTitle")
        val secretsText = spec.text("secretsText")
        val secretsColor = spec.color("secretsColor")
        val secretsSoundEnabled = spec.toggle("secretsSoundEnabled")
        val secretsSound = spec.text("secretsSound")
        val secretsVolume = spec.slider("secretsVolume")
        val secretsPitch = spec.slider("secretsPitch")
        val alertDuration = spec.slider("alertDuration")
    }

    companion object {
        private const val MAX_DIAGNOSTIC_CHARS = 1200
        private const val MAX_DIAGNOSTIC_TEXT_CHARS = 80
        private const val DIAGNOSTIC_MIN_INTERVAL_TICKS = 5
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100

        @JvmField
        val INSTANCE = DungeonRoomAlertModule(Settings())
    }
}
