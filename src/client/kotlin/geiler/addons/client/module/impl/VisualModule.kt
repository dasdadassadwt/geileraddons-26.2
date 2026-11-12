package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.config.ModConfig
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.SettingGroup
import net.minecraft.client.Minecraft
import java.util.Locale
import kotlin.jvm.JvmRecord

/**
 * The mod's one theme, shared by the click GUI and every HUD panel.
 *
 * Deliberately a single theme rather than one per module: the point of the settings living
 * here is that changing a colour changes it everywhere at once. Modules keep their own colours
 * only for things that carry meaning in the world - waypoint states, solver directions - which
 * are signals, not decoration, and would stop being readable if a theme could repaint them.
 */
class VisualModule private constructor(settings: Settings) : Module(
    "Theme",
    "Colours for the menu and every HUD panel. Pick a preset or build your own.",
    Category.VISUAL,
    settings.background, settings.border, settings.accent, settings.text, settings.muted,
    settings.iconColor, settings.tracker, settings.amethyst, settings.midnight, settings.forest,
    settings.aurora, settings.ember, settings.orchid, settings.iceGlass, settings.roseGlass,
    settings.clickGuiMotion, settings.themeSurfaces,
) {
    private val background = settings.background
    private val border = settings.border
    private val accent = settings.accent
    private val text = settings.text
    private val muted = settings.muted
    private val iconColor = settings.iconColor
    private val clickGuiMotion = settings.clickGuiMotion
    private val themeSurfaces = settings.themeSurfaces

    private var lastBackground = 0
    private var lastBorder = 0
    private var lastAccent = 0
    private var lastText = 0
    private var lastMuted = 0
    private var applied = false

    private var lastDiagnosticSnapshot: ThemeSnapshot? = null
    private var pendingDiagnosticSnapshot: ThemeSnapshot? = null
    private var pendingDiagnosticChangeCount = 0L
    private var lastDiagnosticWriteNanos = Long.MIN_VALUE

    private constructor() : this(Settings())

    init {
        group(
            SettingGroup("Colours", settings.background, settings.border, settings.accent,
                settings.text, settings.muted, settings.iconColor),
            SettingGroup("Presets", settings.tracker, settings.amethyst, settings.midnight,
                settings.forest, settings.aurora, settings.ember, settings.orchid,
                settings.iceGlass, settings.roseGlass),
            SettingGroup("Click GUI", settings.clickGuiMotion),
            SettingGroup("Surfaces", settings.themeSurfaces),
        )

        // Theme is a pseudo-module with no effective disable control: its values style every GUI
        // and HUD panel. Keep its trace automatic and independent of Dev -> Debug.
        val initialSnapshot = themeSnapshot()
        lastDiagnosticSnapshot = initialSnapshot
        GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        writeDiagnostic("STATUS reason=initialized scope=global ${initialSnapshot.fields()}")
    }

    /** Just a carrier so the constructor can both build the settings and keep references to them. */
    private class Settings {
        val background = ColorSetting("Background", 11, 18, 32, 129)
        val border = ColorSetting("Border", 25, 29, 36, 255)
        val accent = ColorSetting("Accent", 59, 130, 246, 255)
        val text = ColorSetting("Text", 234, 242, 255, 255)
        val muted = ColorSetting("Muted Text", 126, 143, 168, 255)
        val iconColor = ColorSetting("Icon color", 234, 242, 255)
        val clickGuiMotion = ChoiceSetting("Motion", "Expressive", "None", "Reduced", "Expressive")

        /**
         * Renamed from "Theme Macro Colors", which did not describe what it does.
         *
         * The toggle also decides the Inventory Buttons surface, which its old label and its old
         * "Macro Editor" group did not claim - a described-behaviour mismatch rather than a hidden
         * feature, so the row is named for both surfaces and grouped under "Surfaces". Renaming moves
         * the config key, and ModConfig carries the old value across once.
         */
        val themeSurfaces = BooleanSetting("Theme Macro + Inventory Colors", true)

        val tracker = ModuleAction("Tracker", Runnable { VisualModule.preset(Preset.TRACKER) })
        val amethyst = ModuleAction("Amethyst", Runnable { VisualModule.preset(Preset.AMETHYST) })
        val midnight = ModuleAction("Midnight", Runnable { VisualModule.preset(Preset.MIDNIGHT) })
        val forest = ModuleAction("Forest", Runnable { VisualModule.preset(Preset.FOREST) })
        val aurora = ModuleAction("Aurora", Runnable { VisualModule.preset(Preset.AURORA) })
        val ember = ModuleAction("Ember", Runnable { VisualModule.preset(Preset.EMBER) })
        val orchid = ModuleAction("Orchid", Runnable { VisualModule.preset(Preset.ORCHID) })
        val iceGlass = ModuleAction("Ice Glass", Runnable { VisualModule.preset(Preset.ICE_GLASS) })
        val roseGlass = ModuleAction("Rose Glass", Runnable { VisualModule.preset(Preset.ROSE_GLASS) })
    }

    /** Preset background alpha is reduced to 75% of its former value; other channels stay unchanged. */
    @JvmRecord
    data class Preset(
        val background: Int,
        val border: Int,
        val accent: Int,
        val text: Int,
        val iconColor: Int,
        val muted: Int,
    ) {
        companion object {
            @JvmField
            val TRACKER = Preset(0xA2071925.toInt(), 0xFF3A8198.toInt(), 0xFF27CDE5.toInt(),
                0xFFF3FBFF.toInt(), 0xFFF3FBFF.toInt(), 0xFF9DB9C6.toInt())
            @JvmField
            val AMETHYST = Preset(0x9E1A1430.toInt(), 0xFF826BAC.toInt(), 0xFF9B78F3.toInt(),
                0xFFF8F2FF.toInt(), 0xFFF8F2FF.toInt(), 0xFFB1A3C8.toInt())
            @JvmField
            val MIDNIGHT = Preset(0xB40B1220.toInt(), 0x33A8C7FF.toInt(), 0xFF3B82F6.toInt(),
                0xFFEAF2FF.toInt(), 0xFFEAF2FF.toInt(), 0xFF7E8FA8.toInt())
            @JvmField
            val FOREST = Preset(0xA011211A.toInt(), 0xFF5B9B76.toInt(), 0xFF51C987.toInt(),
                0xFFF0FFF4.toInt(), 0xFFF0FFF4.toInt(), 0xFFA0C4AA.toInt())
            @JvmField
            val AURORA = Preset(0x971A2635.toInt(), 0xFF56A99B.toInt(), 0xFF48DCC2.toInt(),
                0xFFF0FFFC.toInt(), 0xFFF0FFFC.toInt(), 0xFF9EC8C0.toInt())
            @JvmField
            val EMBER = Preset(0x9D251718.toInt(), 0xFFC77F66.toInt(), 0xFFFF795F.toInt(),
                0xFFFFF5F0.toInt(), 0xFFFFF5F0.toInt(), 0xFFD2AAA0.toInt())
            @JvmField
            val ORCHID = Preset(0x9E1C1830.toInt(), 0xFF967AC2.toInt(), 0xFFE08EEB.toInt(),
                0xFFFFF4FF.toInt(), 0xFFFFF4FF.toInt(), 0xFFC4AACA.toInt())
            @JvmField
            val ICE_GLASS = Preset(0x82142735.toInt(), 0xFF71A7B6.toInt(), 0xFF63DDF1.toInt(),
                0xFFF4FCFF.toInt(), 0xFFF4FCFF.toInt(), 0xFFB2D3DE.toInt())
            @JvmField
            val ROSE_GLASS = Preset(0x9D161B2B.toInt(), 0xFFB6819B.toInt(), 0xFFFF8EB7.toInt(),
                0xFFFFF5FA.toInt(), 0xFFFFF5FA.toInt(), 0xFFD2ADB9.toInt())
        }
    }

    private data class ThemeSnapshot(
        val background: Int,
        val border: Int,
        val accent: Int,
        val text: Int,
        val muted: Int,
        val iconColor: Int,
        val motion: String,
        val surfaces: Boolean,
    ) {
        fun matches(
            background: Int,
            border: Int,
            accent: Int,
            text: Int,
            muted: Int,
            iconColor: Int,
            motion: String,
            surfaces: Boolean,
        ): Boolean =
            this.background == background && this.border == border && this.accent == accent &&
                this.text == text && this.muted == muted && this.iconColor == iconColor &&
                this.motion == motion && this.surfaces == surfaces

        fun fields(): String =
            "background=#${formatArgb(background)};border=#${formatArgb(border)};" +
                "accent=#${formatArgb(accent)};text=#${formatArgb(text)};" +
                "muted=#${formatArgb(muted)};icon=#${formatArgb(iconColor)};" +
                "motion=$motion;surfaces=$surfaces"
    }

    private fun themeSnapshot() = ThemeSnapshot(
        background.argb(),
        border.argb(),
        accent.argb(),
        text.argb(),
        muted.argb(),
        iconColor.argb(),
        clickGuiMotion.value(),
        themeSurfaces.value(),
    )

    private fun observeThemeSnapshot(
        background: Int,
        border: Int,
        accent: Int,
        text: Int,
        muted: Int,
        iconColor: Int,
        motion: String,
        surfaces: Boolean,
    ) {
        val previous = lastDiagnosticSnapshot
        if (previous == null || !previous.matches(
                background, border, accent, text, muted, iconColor, motion, surfaces,
            )) {
            val snapshot = ThemeSnapshot(background, border, accent, text, muted, iconColor, motion, surfaces)
            lastDiagnosticSnapshot = snapshot
            pendingDiagnosticSnapshot = snapshot
            if (pendingDiagnosticChangeCount < MAX_COALESCED_CHANGES) {
                pendingDiagnosticChangeCount++
            }
        }

        val pending = pendingDiagnosticSnapshot ?: return
        val now = System.nanoTime()
        if (lastDiagnosticWriteNanos != Long.MIN_VALUE
            && now - lastDiagnosticWriteNanos < MIN_CHANGE_LOG_INTERVAL_NANOS) {
            return
        }

        val coalesced = (pendingDiagnosticChangeCount - 1).coerceAtLeast(0)
        writeDiagnostic("STATUS reason=changed coalesced=$coalesced ${pending.fields()}")
        pendingDiagnosticSnapshot = null
        pendingDiagnosticChangeCount = 0
        lastDiagnosticWriteNanos = now
    }

    private fun writeDiagnostic(line: String) {
        GeilerAddonsLog.write(Category.VISUAL, name(), currentDiagnosticTick(),
            line.take(MAX_DIAGNOSTIC_LINE_LENGTH))
    }

    private fun currentDiagnosticTick(): Long =
        try {
            Minecraft.getInstance().level?.gameTime ?: 0L
        } catch (_: RuntimeException) {
            0L
        }

    /** Applies the preset values; the action wrapper separately records the config change. */
    private fun applyPresetValues(preset: Preset) {
        set(background, preset.background)
        set(border, preset.border)
        set(accent, preset.accent)
        set(text, preset.text)
        set(iconColor, preset.iconColor)
        set(muted, preset.muted)
        refreshTheme()
    }

    /** Pushes the colours into GuiTheme if any of them moved. */
    fun refreshTheme() {
        val backgroundArgb = background.argb()
        val borderArgb = border.argb()
        val accentArgb = accent.argb()
        val textArgb = text.argb()
        val mutedArgb = muted.argb()

        observeThemeSnapshot(
            backgroundArgb, borderArgb, accentArgb, textArgb, mutedArgb,
            iconColor.argb(), clickGuiMotion.value(), themeSurfaces.value(),
        )

        if (applied && backgroundArgb == lastBackground && borderArgb == lastBorder
            && accentArgb == lastAccent && textArgb == lastText && mutedArgb == lastMuted) {
            return
        }
        lastBackground = backgroundArgb
        lastBorder = borderArgb
        lastAccent = accentArgb
        lastText = textArgb
        lastMuted = mutedArgb
        applied = true
        GuiTheme.apply(backgroundArgb, borderArgb, accentArgb, textArgb, mutedArgb)
    }

    fun clickGuiMotion(): ChoiceSetting = clickGuiMotion

    /** User-editable RGB tint for monochrome module icons. */
    fun iconColorSetting(): ColorSetting = iconColor

    /** Whether themed surfaces keep the accent instead of their own fixed colours. */
    fun themeSurfaces(): BooleanSetting = themeSurfaces

    override fun showsToggleControl(): Boolean = false

    override fun showsKeybindControl(): Boolean = false

    companion object {
        @JvmField
        val INSTANCE = VisualModule()

        private const val MIN_CHANGE_LOG_INTERVAL_NANOS = 250_000_000L
        private const val MAX_COALESCED_CHANGES = 1_000_000L
        private const val MAX_DIAGNOSTIC_LINE_LENGTH = 512

        private fun preset(preset: Preset) {
            applyPreset(preset)
            ModConfig.markDirty()
        }

        /** Preserves the Java static entry point used by module checks and existing callers. */
        @JvmStatic
        fun applyPreset(preset: Preset) {
            INSTANCE.applyPresetValues(preset)
        }

        private fun set(setting: ColorSetting, argb: Int) {
            setting.set((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF,
                (argb ushr 24) and 0xFF)
        }
    }
}

private fun formatArgb(value: Int): String = String.format(Locale.ROOT, "%08X", value)
