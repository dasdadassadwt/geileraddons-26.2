package geiler.addons.client.module.impl

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ModuleKeybind
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.visual.ZoomRules
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Hold the module keybind (C on a fresh install) to zoom: the field of view is divided by the zoom, scrolling changes it, and releasing the key zooms back out.
 * Smooth mode eases in and out, instant mode jumps. Cinematic Camera turns on vanilla's mouse smoothing while zoomed.
 */
class ZoomModule private constructor() : Module(
    "Zoom",
    "Hold the module key to zoom in. Scroll while zooming to change how far.",
    Category.VISUAL,
    false,
    mode, initialZoom, maxZoom, scrollSpeed, smoothSpeed, cinematic,
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 300)
    private var holding = false
    private var level = 1.0
    private var current = 1.0
    private var lastFrameNanos = 0L
    private var cinematicApplied = false
    private var cinematicBefore = false
    private var presses = 0L
    private var scrolls = 0L

    init {
        setKeybind(defaultKeybind())
    }

    private fun smooth() = mode.value() == MODE_SMOOTH

    /** Zoom holds on its bind (see [tick]); the Enable switch only says whether zooming is available. */
    override fun togglesOnKeybind(): Boolean = false

    override fun defaultKeybind(): ModuleKeybind = DEFAULT_KEYBIND

    private fun bindHeld(): Boolean {
        val bind = keybind()
        if (!bind.isBound()) return false
        val handle = Minecraft.getInstance().window.handle()
        fun down(key: Int) = GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS
        val key = bind.key()
        val keyDown = if (key.type == InputConstants.Type.MOUSE) {
            GLFW.glfwGetMouseButton(handle, key.value) == GLFW.GLFW_PRESS
        } else down(key.value)
        return ZoomKeybindMigration.held(
            bind, keyDown,
            down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT),
            down(GLFW.GLFW_KEY_LEFT_CONTROL) || down(GLFW.GLFW_KEY_RIGHT_CONTROL),
            down(GLFW.GLFW_KEY_LEFT_ALT) || down(GLFW.GLFW_KEY_RIGHT_ALT),
            down(GLFW.GLFW_KEY_LEFT_SUPER) || down(GLFW.GLFW_KEY_RIGHT_SUPER),
        )
    }

    override fun onEnable() {
        holding = false
        level = 1.0
        current = 1.0
        presses = 0
        scrolls = 0
        diagnostics.enable("bind=${keybind().diagnosticName()};mode=${mode.value()};initial=${initialZoom.value()};max=${maxZoom.value()};scrollSpeed=${scrollSpeed.value()};" +
            "smoothSpeed=${smoothSpeed.value()};cinematic=${cinematic.value()}")
    }

    override fun onDisable() {
        holding = false
        current = 1.0
        applyCinematic(false)
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val client = Minecraft.getInstance()
        val now = client.player != null && client.gui.screen() == null && bindHeld()
        if (now != holding) {
            holding = now
            if (now) {
                level = ZoomRules.initial(initialZoom.value().toDouble(), maxZoom.value().toDouble())
                presses++
            }
            diagnostics.event(if (now) "ZOOM start level=${"%.2f".format(level)} mode=${mode.value()}" else "ZOOM end")
        }
        if (holding) level = level.coerceIn(1.0, maxOf(1.0, maxZoom.value().toDouble()))
        applyCinematic(holding && cinematic.value())
        diagnostics.statusOnChange { "holding=$holding level=${"%.2f".format(level)} current=${"%.2f".format(current)} presses=$presses scrolls=$scrolls" }
    }

    /** Called every frame by the camera; eases the zoom toward its target and returns the factor for the field of view. */
    fun fovScale(): Double {
        if (!isEnabled() && current == 1.0) return 1.0
        val now = System.nanoTime()
        val dt = if (lastFrameNanos == 0L) 0.0 else (now - lastFrameNanos) / 1_000_000_000.0
        lastFrameNanos = now
        current = ZoomRules.step(current, if (holding && isEnabled()) level else 1.0, smoothSpeed.value().toDouble(), dt, smooth())
        return ZoomRules.fovScale(current)
    }

    /** A mouse-wheel scroll while zooming changes the zoom instead of the hotbar; true when it was used. */
    fun onScroll(vertical: Double): Boolean {
        if (!isEnabled() || !holding) return false
        level = ZoomRules.scroll(level, vertical, scrollSpeed.value().toDouble(), maxZoom.value().toDouble())
        scrolls++
        return true
    }

    private fun applyCinematic(on: Boolean) {
        val options = Minecraft.getInstance().options ?: return
        if (on && !cinematicApplied) {
            cinematicBefore = options.smoothCamera
            options.smoothCamera = true
            cinematicApplied = true
        } else if (!on && cinematicApplied) {
            options.smoothCamera = cinematicBefore
            cinematicApplied = false
        }
    }

    companion object {
        const val MODE_SMOOTH = "SMOOTH"
        const val MODE_INSTANT = "INSTANT"

        private val DEFAULT_KEYBIND = ModuleKeybind(InputConstants.getKey("key.keyboard.c"), 0)
        private val mode = ChoiceSetting(
            "Mode", MODE_SMOOTH, mapOf(MODE_SMOOTH to "Smooth", MODE_INSTANT to "Instant"), MODE_SMOOTH, MODE_INSTANT,
        )
        private val initialZoom = NumberSetting("Initial Zoom", 1.5f, 30f, 4f, false)
        private val maxZoom = NumberSetting("Max Zoom", 2f, 50f, 30f, false)
        private val scrollSpeed = NumberSetting("Scroll Speed", 1f, 10f, 3f, true)
        private val smoothSpeed = NumberSetting("Smooth Speed", 1f, 30f, 10f, true)
        private val cinematic = BooleanSetting("Cinematic Camera", false)

        @JvmField
        val INSTANCE = ZoomModule()
    }
}
