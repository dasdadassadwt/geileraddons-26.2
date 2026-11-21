package geiler.addons.client.module.impl

import geiler.addons.client.config.ClickGuiState
import geiler.addons.client.config.ConfigTransferService
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.ClickGuiScreen
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Module
import geiler.addons.client.render.PanoramaRenderer
import geiler.addons.client.visual.PanoramaMath
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.options.OptionsScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The menus outside a world: the player's own photo or a flying 3D golden-hour desert behind them,
 * vanilla buttons in the Click GUI background colour, and the title screen's logo, version text and
 * copyright hidden on request. A settings button sits in the title screen's bottom-right corner.
 */
class CustomMainMenuModule private constructor() : Module(
    "Custom Main Menu",
    "Your photo or a flying 3D desert behind the menus, Click GUI buttons, and a cleaner title screen.",
    Category.VISUAL,
    false,
    TITLE,
    MENUS,
    BUTTONS,
    RESOLUTION,
    ANIMATE,
    DUNES,
    DUNE_HEIGHT,
    SAND_DARK,
    SAND_LIGHT,
    SKY,
    SUNSET,
    LOGO,
    VERSION,
    COPYRIGHT,
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 200L, 300)
    private val startNanos = System.nanoTime()
    private var lastDrawNanos = 0L
    private var desertFrames = 0L
    private var photoFrames = 0L
    private var styledButtons = 0L
    private var hiddenLogoFrames = 0L
    private var hiddenVersionFrames = 0L
    private var titleInits = 0L
    private var windowFrames = 0
    private var windowNanos = 0L
    private var windowMaxNanos = 0L
    private var windowStart = 0L
    private var lastFps = 0
    private var lastAvgMs = 0.0
    private var lastMaxMs = 0.0
    private var reportedFailure: String? = null
    private var reportedPhoto = ""
    private var photoStamp: String? = null
    private var photoCheckTicks = 0

    /** Settings were saved under the module's first name. */
    override fun configName(): String = "Custom Panorama"

    override fun settingTooltip(setting: geiler.addons.client.module.Setting): String? = when (setting) {
        TITLE -> "Photo reads config/geileraddons/panorama.png (or panorama.jpg) and shows it behind every menu outside a world. Without it the desert is shown."
        MENUS -> "Desert (or your photo) replaces the panorama behind other menus outside a world. In a world menus keep the blurred game."
        BUTTONS -> "Click GUI draws every vanilla button as a see-through rounded button in the theme background colour. Sliders stay vanilla."
        RESOLUTION -> "The desert is drawn at this share of the window and scaled up. Lower is faster on weak graphics cards."
        ANIMATE -> "Off stops the camera on one view and the photo drift."
        DUNES -> "The mix of dunes in the desert: Mixed is the original; Gentle is calm, Towering is tall and wild, Long ridges and Star dunes favour one dune type, Rolling is soft hills."
        DUNE_HEIGHT -> "Scales the height of every dune; 1 is the original."
        SAND_DARK -> "Sand in the shadowed troughs of the desert."
        SAND_LIGHT -> "Sunlit sand on the crests of the desert."
        SKY -> "The sky overhead in the desert; it fades to the sunset colour toward the horizon."
        SUNSET -> "The glow on the horizon toward the sun in the desert."
        LOGO -> "The Minecraft logo, the Java Edition banner and the yellow splash text on the title screen."
        VERSION -> "The version line in the title screen's bottom-left corner, including mod counts other mods add."
        COPYRIGHT -> "The Mojang copyright line in the title screen's bottom-right corner."
        else -> null
    }

    override fun onEnable() {
        PanoramaRenderer.clearFailure()
        PanoramaRenderer.reloadPhoto()
        reportedFailure = null
        reportedPhoto = ""
        photoStamp = null
        diagnostics.enable(
            "title=${TITLE.value()} menus=${MENUS.value()} buttons=${BUTTONS.value()} resolution=${RESOLUTION.value()} " +
                "animate=${ANIMATE.value()} dunes=${DUNES.value()} height=${DUNE_HEIGHT.value()} " +
                "sand=#${hex(SAND_DARK.argb())}/#${hex(SAND_LIGHT.argb())} sky=#${hex(SKY.argb())} sunset=#${hex(SUNSET.argb())} " +
                "logo=${LOGO.value()} version=${VERSION.value()} copyright=${COPYRIGHT.value()} photo=${photoPath()}",
        )
    }

    override fun onDisable() {
        diagnostics.event("DISABLE desertFrames=$desertFrames photoFrames=$photoFrames styledButtons=$styledButtons")
        PanoramaRenderer.release(includePhoto = true)
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        if (probe) runProbe(++probeTicks)
        // Free GPU memory once no menu has drawn a background for a while (e.g. while playing).
        if (lastDrawNanos != 0L && System.nanoTime() - lastDrawNanos > IDLE_RELEASE_NANOS && PanoramaRenderer.hasGpuResources()) {
            PanoramaRenderer.release(includePhoto = true)
            diagnostics.event("RELEASE reason=idle")
            lastDrawNanos = 0L
        }
        if (++photoCheckTicks >= PHOTO_CHECK_TICKS) {
            photoCheckTicks = 0
            val stamp = photoStamp()
            if (photoStamp != null && stamp != photoStamp) {
                PanoramaRenderer.reloadPhoto()
                diagnostics.event("PHOTO CHANGED reloading")
            }
            photoStamp = stamp
        }
        val failure = PanoramaRenderer.failure
        if (failure != null && failure != reportedFailure) {
            reportedFailure = failure
            diagnostics.event("DESERT FAILED fallback=vanilla error=$failure")
        }
        val photo = PanoramaRenderer.photoState
        if (photo != reportedPhoto) {
            reportedPhoto = photo
            diagnostics.event("PHOTO state=$photo")
        }
        diagnostics.status {
            "desertFrames=$desertFrames photoFrames=$photoFrames desertFps=$lastFps gameFps=${Minecraft.getInstance().fps} cpuMsAvg=${"%.3f".format(lastAvgMs)} " +
                "cpuMsMax=${"%.3f".format(lastMaxMs)} gpuMs=${"%.2f".format(PanoramaRenderer.gpuMillis)} target=${PanoramaRenderer.lastTargetWidth}x${PanoramaRenderer.lastTargetHeight} " +
                "scale=${PanoramaMath.resolutionScale(RESOLUTION.value())} cameraY=${"%.1f".format(PanoramaRenderer.lastCameraHeight)} " +
                "clearance=${"%.1f".format(PanoramaRenderer.lastClearance)} photo=$photo styledButtons=$styledButtons titleInits=$titleInits " +
                "hiddenLogoFrames=$hiddenLogoFrames hiddenVersionFrames=$hiddenVersionFrames failure=${failure ?: "none"} suppressedEvents=$it"
        }
    }

    /** Called instead of the vanilla panorama; true means it was drawn and vanilla must be skipped. */
    fun drawPanorama(screen: Screen, graphics: GuiGraphicsExtractor): Boolean {
        if (!isEnabled()) return false
        val now = System.nanoTime()
        lastDrawNanos = now
        val time = if (ANIMATE.value()) PanoramaMath.loopTime((now - startNanos) / 1e9) else STILL_TIME
        if (screen !is TitleScreen && MENUS.value() == "Vanilla") return false
        // The photo covers every menu the desert would, not only the title screen.
        if (TITLE.value() == "Photo" &&
            PanoramaRenderer.drawPhoto(graphics, screen.width, screen.height, photoPath(), time, ANIMATE.value())
        ) {
            photoFrames++
            return true
        }
        val drawn = PanoramaRenderer.drawDesert(graphics, screen.width, screen.height, time, PanoramaMath.resolutionScale(RESOLUTION.value()), desertLook())
        if (drawn) {
            desertFrames++
            recordFrame(System.nanoTime() - now)
        }
        return drawn
    }

    /** Called before a vanilla button body; true means the Click GUI body was drawn instead. */
    fun drawButton(graphics: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, active: Boolean, highlighted: Boolean, alpha: Float): Boolean {
        if (!isEnabled() || BUTTONS.value() != "Click GUI" || w <= 0 || h <= 0) return false
        GuiTheme.roundedRect(graphics, x, y, w, h, GuiTheme.RADIUS_SMALL, buttonColor(GuiTheme.PANEL_TOP, GuiTheme.SWITCH_ON, active, highlighted, alpha))
        styledButtons++
        return true
    }

    /** The desert's colours and dune style as set now; the style and height also move the camera over the sand. */
    private fun desertLook() = PanoramaMath.DesertLook(
        SAND_DARK.argb(), SAND_LIGHT.argb(), SKY.argb(), SUNSET.argb(),
        PanoramaMath.duneStyle(DUNES.value(), DUNE_HEIGHT.value()),
    )

    /** Title-screen pieces; each stays when the module is off. Counted so a broken hook shows in the log. */
    fun showLogo(): Boolean = (!isEnabled() || LOGO.value()).also { if (!it) hiddenLogoFrames++ }

    fun showVersion(): Boolean = (!isEnabled() || VERSION.value()).also { if (!it) hiddenVersionFrames++ }

    fun showCopyright(): Boolean = !isEnabled() || COPYRIGHT.value()

    /** The bottom-right settings button for a freshly laid-out title screen, or null when the module is off. */
    fun settingsButton(screenWidth: Int, screenHeight: Int): AbstractButton? {
        if (!isEnabled()) return null
        titleInits++
        // Above the copyright line while it is shown, otherwise in the corner.
        val bottom = screenHeight - BUTTON_MARGIN - if (showCopyright()) COPYRIGHT_LINE else 0
        return SettingsButton(screenWidth - BUTTON_MARGIN - BUTTON_SIZE, bottom - BUTTON_SIZE) { openSettings() }
    }

    /** Same state hand-off Move Elements uses to land on a module's settings page; closing returns here. */
    private fun openSettings() {
        diagnostics.event("OPEN SETTINGS from=title")
        ClickGuiState.setWorkspace(category().workspace)
        ClickGuiState.setCategory(category())
        ClickGuiState.setOpenModule(this)
        ClickGuiState.setExpandedColor(null)
        ClickGuiState.setSettingsScroll(0)
        ModConfig.markDirty()
        Minecraft.getInstance().gui.setScreen(ClickGuiScreen())
    }

    private fun hex(argb: Int): String = (argb and 0xFFFFFF).toString(16).padStart(6, '0')

    private class SettingsButton(x: Int, y: Int, onClick: () -> Unit) : Button(
        x, y, BUTTON_SIZE, BUTTON_SIZE, Component.literal("Custom Main Menu settings"),
        Button.OnPress { onClick() }, Button.CreateNarration { it.get() },
    ) {
        init {
            setTooltip(Tooltip.create(Component.literal("Custom Main Menu settings")))
        }

        override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            extractDefaultSprite(graphics)
            val color = GuiTheme.withOpacity(if (isHoveredOrFocused) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_SECONDARY, alpha)
            val inset = (BUTTON_SIZE - ICON_SIZE) / 2
            graphics.blit(RenderPipelines.GUI_TEXTURED, GEAR, x + inset, y + inset, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, color)
        }
    }

    /**
     * Dev-only visual check: in a development run with `config/geileraddons/panorama-probe` present, steps
     * through the title and options screens and every style, saving a screenshot of each to `screenshots/`.
     */
    private val probe: Boolean by lazy {
        FabricLoader.getInstance().isDevelopmentEnvironment &&
            Files.exists(ConfigTransferService.configDirectory().resolve("panorama-probe"))
    }
    private var probeTicks = 0

    private fun runProbe(t: Int) {
        val client = Minecraft.getInstance()
        when (t) {
            160 -> probeShot(client, "1-title-photo-clickgui")
            170 -> TITLE.setValue("Desert")
            220 -> probeShot(client, "2-title-desert")
            230 -> ANIMATE.setValue(false)
            250 -> probeShot(client, "3-title-desert-still")
            255 -> ANIMATE.setValue(true)
            290 -> client.setScreenAndShow(OptionsScreen(TitleScreen(), client.options, false))
            340 -> probeShot(client, "4-options-balanced")
            350 -> RESOLUTION.setValue("Performance")
            400 -> probeShot(client, "5-options-performance")
            410 -> RESOLUTION.setValue("Quality")
            460 -> probeShot(client, "6-options-quality")
            470 -> { BUTTONS.setValue("Minecraft"); ANIMATE.setValue(false) }
            510 -> probeShot(client, "7-options-minecraft-buttons-still")
            520 -> {
                TITLE.setValue("Photo"); BUTTONS.setValue("Click GUI"); RESOLUTION.setValue("Balanced"); ANIMATE.setValue(true)
                client.setScreenAndShow(TitleScreen())
            }
            600 -> probeShot(client, "8-title-photo-again")
        }
    }

    private fun probeShot(client: Minecraft, name: String) {
        diagnostics.event("PROBE $name fps=${client.fps} gpuMs=${"%.2f".format(PanoramaRenderer.gpuMillis)} " +
            "target=${PanoramaRenderer.lastTargetWidth}x${PanoramaRenderer.lastTargetHeight} photo=${PanoramaRenderer.photoState} failure=${PanoramaRenderer.failure ?: "none"}")
        Screenshot.grab(client.gameDirectory, "panorama-probe-$name.png", client.gameRenderer.mainRenderTarget(), 1) {}
    }

    private fun recordFrame(nanos: Long) {
        val now = System.nanoTime()
        if (windowStart == 0L) windowStart = now
        windowFrames++
        windowNanos += nanos
        windowMaxNanos = maxOf(windowMaxNanos, nanos)
        if (now - windowStart >= 1_000_000_000L) {
            lastFps = windowFrames
            lastAvgMs = windowNanos / 1e6 / windowFrames
            lastMaxMs = windowMaxNanos / 1e6
            windowFrames = 0
            windowNanos = 0L
            windowMaxNanos = 0L
            windowStart = now
        }
    }

    /** `panorama.png`, or a `.jpg`/`.jpeg` of the same name; the file content decides the format. */
    private fun photoPath(): Path {
        val dir = ConfigTransferService.configDirectory()
        return PHOTO_FILES.map(dir::resolve).firstOrNull(Files::isRegularFile) ?: dir.resolve(PHOTO_FILES[0])
    }

    private fun photoStamp(): String = try {
        val path = photoPath()
        if (Files.isRegularFile(path)) "${Files.size(path)}:${Files.getLastModifiedTime(path).toMillis()}" else "missing"
    } catch (_: java.io.IOException) {
        "unreadable"
    }

    companion object {
        private val PHOTO_FILES = listOf("panorama.png", "panorama.jpg", "panorama.jpeg")
        /** A still view with the sun low over a long ridge field. */
        private const val STILL_TIME = 40f
        private const val IDLE_RELEASE_NANOS = 10_000_000_000L
        private const val PHOTO_CHECK_TICKS = 40
        private const val BUTTON_SIZE = 20
        private const val ICON_SIZE = 14
        private const val BUTTON_MARGIN = 2
        /** Height vanilla gives the copyright line at the bottom of the title screen. */
        private const val COPYRIGHT_LINE = 12
        private val GEAR: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png")

        private val TITLE = ChoiceSetting("Title background", "Photo", "Photo", "Desert")
        private val MENUS = ChoiceSetting("Menu background", "Desert", "Desert", "Vanilla")
        private val BUTTONS = ChoiceSetting("Buttons", "Minecraft", "Minecraft", "Click GUI")
        private val RESOLUTION = ChoiceSetting("Resolution", "Balanced", "Performance", "Balanced", "Quality")
        private val ANIMATE = BooleanSetting("Animate", true)
        private val DUNES = ChoiceSetting("Dune style", "Mixed", *PanoramaMath.DUNE_STYLES.toTypedArray())
        private val DUNE_HEIGHT = NumberSetting("Dune height", PanoramaMath.MIN_DUNE_HEIGHT, PanoramaMath.MAX_DUNE_HEIGHT, 1.0f)
        private val SAND_DARK = ColorSetting("Sand shadow", 211, 174, 141)
        private val SAND_LIGHT = ColorSetting("Sand light", 236, 205, 168)
        private val SKY = ColorSetting("Sky", 104, 141, 194)
        private val SUNSET = ColorSetting("Sunset glow", 255, 208, 156)
        private val LOGO = BooleanSetting("Minecraft logo", false)
        private val VERSION = BooleanSetting("Version text", false)
        private val COPYRIGHT = BooleanSetting("Copyright", false)

        /**
         * A Click GUI button body: the theme background colour, see-through, no border. Hover tints it toward
         * the accent and makes it more solid; an inactive button is fainter. [alpha] is the widget's own fade.
         */
        @JvmStatic
        fun buttonColor(background: Int, accent: Int, active: Boolean, highlighted: Boolean, alpha: Float): Int {
            // Glassy themes keep a usable floor, or the buttons would vanish.
            val baseAlpha = maxOf(background ushr 24, 160)
            val (rgb, share) = when {
                !active -> background to 0.35f
                highlighted -> GuiTheme.lerpColor(background, accent, 0.3f) to 0.8f
                else -> background to 0.55f
            }
            val a = (baseAlpha * share * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
            return (a shl 24) or (rgb and 0xFFFFFF)
        }

        @JvmField
        val INSTANCE = CustomMainMenuModule()
    }
}
