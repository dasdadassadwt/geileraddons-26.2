package geiler.addons.client.gui

import geiler.addons.client.module.impl.ScoreboardModule
import geiler.addons.client.scoreboard.ScoreboardBackend
import geiler.addons.client.scoreboard.ScoreboardFontService
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.concurrent.CompletionException

/** Imports a locally owned TTF/OTF font into the current client profile. */
class ScoreboardFontImportScreen(private val parent: Screen?) : Screen(Component.literal("Import Font")) {
    private var pathField: EditBox? = null
    private var status = "Paste the full path to a local .ttf or .otf file."

    override fun init() {
        val fieldWidth = maxOf(1, minOf(500, width - 48))
        val left = (width - fieldWidth) / 2
        val field = EditBox(font, left, height / 2 - 18, fieldWidth, 20, Component.literal("Absolute font file path"))
        field.setMaxLength(1024)
        pathField = addRenderableWidget(field)
        addRenderableWidget(
            Button.builder(Component.literal("Import")) { importFont() }
                .bounds(width / 2 - 106, height / 2 + 18, 100, 20).build(),
        )
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { minecraft.gui.setScreen(parent) }
                .bounds(width / 2 + 6, height / 2 + 18, 100, 20).build(),
        )
    }

    private fun importFont() {
        val source = try {
            Path.of(pathField?.value?.trim() ?: "")
        } catch (_: RuntimeException) {
            status = "That path could not be read."
            rebuildWidgets()
            return
        }
        status = "Importing and refreshing the font pack…"
        ScoreboardFontService.importFont(source).whenComplete { message, error ->
            minecraft.execute {
                if (error != null) {
                    val cause = if (error is CompletionException && error.cause != null) error.cause else error
                    status = cause?.message ?: "Font import failed."
                } else {
                    status = message ?: ""
                    ScoreboardBackend.save()
                    ScoreboardModule.INSTANCE.refreshFontChoices()
                }
                rebuildWidgets()
            }
        }
        rebuildWidgets()
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xB8000000.toInt())
        val panelWidth = minOf(600, width - 16)
        GuiTheme.roundedRectBordered(
            graphics,
            (width - panelWidth) / 2,
            height / 2 - 120,
            panelWidth,
            230,
            10,
            0xF0181D29.toInt(),
            0xF010141D.toInt(),
            0xFF566174.toInt(),
            1,
        )
        graphics.centeredText(font, "Import a font", width / 2, height / 2 - 99, 0xFFFFFFFF.toInt())
        graphics.centeredText(
            font,
            "Enter a local .ttf or .otf path, then choose it in Scoreboard settings.",
            width / 2,
            height / 2 - 69,
            0xFFE6EBF2.toInt(),
        )
        graphics.centeredText(
            font,
            "The file is copied into this Minecraft profile's config/geileraddons/scoreboard/fonts folder.",
            width / 2,
            height / 2 - 48,
            0xFF9DAAC0.toInt(),
        )
        graphics.centeredText(
            font,
            "Import only fonts you have permission to use. Their license still applies to your local copy.",
            width / 2,
            height / 2 + 58,
            0xFF9DAAC0.toInt(),
        )
        graphics.centeredText(font, status, width / 2, height / 2 + 83, 0xFFFFFF80.toInt())
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }
}
