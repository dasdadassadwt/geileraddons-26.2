package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.inventory.PlayerInventoryControls
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.KeyMappingMirror
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/** Tap-to-lock and hold-to-bind controls for the player's inventory slots. */
class PlayerInventoryControlsModule private constructor(settings: Settings) : Module(
    "Slot Locking",
    "Tap the slot key to lock or unlock a slot (tap a bound slot to remove its binding); hold it from an inventory slot to a hotbar slot to bind them.",
    Category.MISCELLANEOUS,
    false,
    *settings.allSettings(),
) {
    private val protectBoundSlots = settings.protectBoundSlots
    private val drawBoundOutline = settings.drawBoundOutline
    private val pointingLineMode = settings.pointingLineMode
    private val lineWidth = settings.lineWidth
    private val lockedSlotStyle = settings.lockedSlotStyle
    private val gestureKey = settings.gestureKey

    private var diagnosticTick = 0L
    private var lastStatusTick = Long.MIN_VALUE
    private var lastDiagnosticState = ""

    init {
        group(
            SettingGroup("Keys", gestureKey.setting),
            SettingGroup("Slot Protection", protectBoundSlots, lockedSlotStyle),
            SettingGroup("Slot Bindings", drawBoundOutline, pointingLineMode, lineWidth),
        )
    }

    override fun isWorkInProgress(): Boolean = true

    /** Preserve the old config key while changing the user-facing module name. */
    override fun configName(): String = "Player Inventory Controls"

    fun protectBoundSlots(): Boolean = protectBoundSlots.value()

    fun drawBoundOutline(): Boolean = drawBoundOutline.value()

    fun drawPointingLines(): Boolean {
        if (pointingLineMode.value() == "Always") return true
        if (pointingLineMode.value() != "Shifting") return false
        val client = Minecraft.getInstance()
        return client != null && client.gui.screen() != null &&
            (GLFW.glfwGetKey(client.window.handle(), GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(client.window.handle(), GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS)
    }

    fun lineWidth(): Int = lineWidth.intValue()

    fun linkColor(hotbarSlot: Int): Int = if (hotbarSlot in HOTBAR_BINDING_COLORS.indices) {
        HOTBAR_BINDING_COLORS[hotbarSlot]
    } else INVALID_BINDING_COLOR

    fun lockedSlotColor(): Int = LOCKED_SLOT_COLOR

    fun lockedSlotUsesIcon(): Boolean = lockedSlotStyle.value() == "Lock Icon"

    override fun onEnable() {
        diagnosticTick = 0L
        lastStatusTick = diagnosticTick
        lastDiagnosticState = diagnosticState()
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), true)
        recordDiagnostic("FEATURE ENABLED $lastDiagnosticState")
    }

    override fun onDisable() {
        PlayerInventoryControls.INSTANCE.resetScreenGestures()
        recordDiagnostic("FEATURE DISABLED ${diagnosticState()}")
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.MISCELLANEOUS, name(), false)
        lastDiagnosticState = ""
        lastStatusTick = Long.MIN_VALUE
    }

    /** Samples configuration and persisted counts on the client thread; no player or item text is read. */
    private fun tickDiagnostics() {
        gestureKey.sync()
        if (!isEnabled()) return
        if (diagnosticTick < Long.MAX_VALUE) diagnosticTick++
        if (diagnosticTick % DIAGNOSTIC_SAMPLE_INTERVAL_TICKS != 0L) return

        val state = diagnosticState()
        val changed = state != lastDiagnosticState
        val heartbeat = lastStatusTick == Long.MIN_VALUE ||
            diagnosticTick - lastStatusTick >= DIAGNOSTIC_HEARTBEAT_TICKS
        if (!changed && !heartbeat) return

        recordDiagnostic(
            "STATUS $state changed=$changed heartbeat=$heartbeat " +
                "sampleTicks=$DIAGNOSTIC_SAMPLE_INTERVAL_TICKS heartbeatTicks=$DIAGNOSTIC_HEARTBEAT_TICKS",
        )
        lastDiagnosticState = state
        lastStatusTick = diagnosticTick
    }

    /** Contains only settings, fixed enum values, and numeric lock/link counts. */
    private fun diagnosticState(): String {
        val data = PlayerInventoryControls.INSTANCE.settings()!!
        return "enabled=${isEnabled()} protectBoundSlots=${protectBoundSlots.value()} " +
            "drawBoundOutline=${drawBoundOutline.value()} pointingLineMode=${pointingLineMode.value()} " +
            "lineWidth=${lineWidth.intValue()} lockedSlotStyle=${lockedSlotStyle.value()} " +
            "lockedSlots=${data.lockedSlots().size} activeLinks=${data.slotLinks().size}"
    }

    private fun recordDiagnostic(line: String) {
        GeilerAddonsLog.write(
            Category.MISCELLANEOUS,
            name(),
            diagnosticTick,
            line.take(MAX_DIAGNOSTIC_LINE_CHARS),
        )
    }

    private class Settings {
        val protectBoundSlots = BooleanSetting("Protect Bound Slots", true)
        val drawBoundOutline = BooleanSetting("Bound Outline", true)
        val pointingLineMode = ChoiceSetting("Pointing Line Mode", "None", "None", "Always", "Shifting")
        val lineWidth = NumberSetting("Line Width", 1f, 6f, 2f, true)
        val lockedSlotStyle = ChoiceSetting("Locked Slot Style", "Lock Icon", "Lock Icon", "Box")
        /** Not persisted: the vanilla key mapping stays the source of truth. */
        val gestureKey = KeyMappingMirror(PlayerInventoryControls.bindInventorySlotsKey(), "Slot Gesture Key")

        fun allSettings(): Array<Setting> = arrayOf(
            protectBoundSlots,
            drawBoundOutline,
            pointingLineMode,
            lineWidth,
            lockedSlotStyle,
        )
    }

    companion object {
        private const val DIAGNOSTIC_SAMPLE_INTERVAL_TICKS = 20L
        private const val DIAGNOSTIC_HEARTBEAT_TICKS = 100L
        private const val MAX_DIAGNOSTIC_LINE_CHARS = 512
        private const val LOCKED_SLOT_COLOR = 0xFFFF5555.toInt()
        private const val INVALID_BINDING_COLOR = 0xFFFFFFFF.toInt()
        private val HOTBAR_BINDING_COLORS = intArrayOf(
            0xFFFF5555.toInt(),
            0xFFFFAA00.toInt(),
            0xFFFFFF55.toInt(),
            0xFF55FF55.toInt(),
            0xFF55FFFF.toInt(),
            0xFF55AAFF.toInt(),
            0xFF5555FF.toInt(),
            0xFFAA55FF.toInt(),
            0xFFFF55FF.toInt(),
        )

        @JvmField
        val INSTANCE = PlayerInventoryControlsModule(Settings())

        init {
            ClientTickEvents.END_CLIENT_TICK.register { _ -> INSTANCE.tickDiagnostics() }
        }
    }
}
