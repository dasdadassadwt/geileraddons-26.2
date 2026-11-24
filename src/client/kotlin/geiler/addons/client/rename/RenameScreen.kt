package geiler.addons.client.rename

import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.module.impl.AnyRenameModule
import geiler.addons.client.module.impl.VisualModule
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/** Editor for one item override with a live preview; every field maps to one [RenameOverride] property. */
class RenameScreen(private val parent: Screen?, private val target: ItemStack) : Screen(Component.literal("Any Rename")) {
    private val real = RenameApplier.original(target)
    private val uuid = SkyblockItems.itemUuid(real)
    private val skyId = SkyblockItems.skyBlockId(real)
    private val working: RenameOverride = (AnyRenameModule.INSTANCE.existing(target)?.copy() ?: RenameOverride())
    private var preview: ItemStack = ItemStack.EMPTY
    private var dirty = true
    private var panelX = 0
    private var panelY = 0
    private val boxes = ArrayList<EditBox>()
    private lateinit var nameBox: EditBox
    private lateinit var gradientBox: EditBox
    private lateinit var iconBox: EditBox
    private lateinit var loreBox: EditBox
    private lateinit var rarityBox: EditBox
    private lateinit var hideBox: EditBox
    private lateinit var dyeBox: EditBox
    private lateinit var glintButton: Button
    private lateinit var loreModeButton: Button
    private lateinit var scopeButton: Button
    private val flagButtons = ArrayList<Button>()

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        panelX = (width - PANEL_W) / 2
        panelY = maxOf(4, (height - PANEL_H) / 2)
        boxes.clear()
        flagButtons.clear()
        val x = panelX + 12
        val w = PANEL_W - 24
        nameBox = box(x, panelY + 28, w, "Name: &6Gold &lBold &#12ABEFHex", working.name)
        gradientBox = box(x, panelY + 50, w, "Gradient: FF0000,0000FF or rainbow", working.gradient)
        iconBox = box(x, panelY + 72, w, "Icon: minecraft:diamond_sword | head:Name | texture:<base64>", working.icon)
        loreBox = box(x, panelY + 94, w, "Lore lines separated by |", working.lore)
        rarityBox = box(x, panelY + 116, w, "Rarity line, e.g. &d&lMYTHIC", working.rarityLine)
        hideBox = box(x, panelY + 138, w, "Hide lore lines containing (comma separated)", working.hide)
        dyeBox = box(x, panelY + 160, w, "Dye colour RRGGBB (leather, potions)", if (working.dye >= 0) "%06X".format(working.dye) else "")
        val labels = listOf("B", "I", "U", "S", "O")
        for (i in labels.indices) {
            val button = Button.builder(Component.empty()) { flip(i) }.bounds(x + i * 26, panelY + 184, 24, 18).build()
            flagButtons.add(addRenderableWidget(button))
        }
        glintButton = addRenderableWidget(
            Button.builder(Component.empty()) { working.glint = next(RenameOverride.GLINT_MODES, working.glint); changed() }
                .bounds(x + 136, panelY + 184, 80, 18).build(),
        )
        loreModeButton = addRenderableWidget(
            Button.builder(Component.empty()) { working.loreMode = next(RenameOverride.LORE_MODES, working.loreMode); changed() }
                .bounds(x + 222, panelY + 184, w - 222, 18).build(),
        )
        scopeButton = addRenderableWidget(
            Button.builder(Component.empty()) { if (uuid.isNotEmpty()) working.allOfType = !working.allOfType; changed() }
                .bounds(x, panelY + 206, w, 18).build(),
        )
        if (uuid.isEmpty()) working.allOfType = true
        val bw = (w - 16) / 5
        var bx = x
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "Save" to ::save, "Reset" to ::reset, "Copy" to ::copy, "Paste" to ::paste, "Cancel" to ::onClose,
        )) {
            addRenderableWidget(Button.builder(Component.literal(label)) { action() }.bounds(bx, panelY + 230, bw, 18).build())
            bx += bw + 4
        }
        refreshLabels()
        setInitialFocus(nameBox)
    }

    private fun box(x: Int, y: Int, w: Int, hint: String, value: String): EditBox {
        val field = EditBox(font, x, y, w, 18, Component.literal(hint))
        field.setMaxLength(MAX_FIELD)
        field.setHint(Component.literal(hint))
        field.setValue(value)
        field.setResponder { dirty = true }
        boxes.add(field)
        return addRenderableWidget(field)
    }

    private fun flip(i: Int) {
        when (i) {
            0 -> working.bold = !working.bold
            1 -> working.italic = !working.italic
            2 -> working.underline = !working.underline
            3 -> working.strike = !working.strike
            else -> working.obfuscated = !working.obfuscated
        }
        changed()
    }

    private fun next(options: List<String>, current: String): String = options[(options.indexOf(current) + 1) % options.size]

    private fun changed() {
        dirty = true
        refreshLabels()
    }

    private fun refreshLabels() {
        val on = booleanArrayOf(working.bold, working.italic, working.underline, working.strike, working.obfuscated)
        val labels = listOf("B", "I", "U", "S", "O")
        for (i in flagButtons.indices) flagButtons[i].message = Component.literal(if (on[i]) "[${labels[i]}]" else labels[i])
        glintButton.message = Component.literal("Glint: ${working.glint}")
        loreModeButton.message = Component.literal("Lore: ${working.loreMode}")
        scopeButton.message = Component.literal(
            if (uuid.isEmpty()) "Applies to every $skyId (item has no uuid)"
            else if (working.allOfType) "Applies to every $skyId" else "Applies to this item only",
        )
    }

    /** Copies the field values into [working]. */
    private fun sync() {
        working.name = nameBox.value
        working.gradient = gradientBox.value
        working.icon = iconBox.value
        working.lore = loreBox.value
        working.rarityLine = rarityBox.value
        working.hide = hideBox.value
        working.dye = RenameText.parseHex(dyeBox.value)
    }

    private fun fill(o: RenameOverride) {
        nameBox.setValue(o.name)
        gradientBox.setValue(o.gradient)
        iconBox.setValue(o.icon)
        loreBox.setValue(o.lore)
        rarityBox.setValue(o.rarityLine)
        hideBox.setValue(o.hide)
        dyeBox.setValue(if (o.dye >= 0) "%06X".format(o.dye) else "")
        working.bold = o.bold
        working.italic = o.italic
        working.underline = o.underline
        working.strike = o.strike
        working.obfuscated = o.obfuscated
        working.glint = o.glint
        working.loreMode = o.loreMode
        if (uuid.isNotEmpty()) working.allOfType = o.allOfType
        changed()
    }

    private fun save() {
        sync()
        AnyRenameModule.INSTANCE.save(target, working)
        minecraft?.gui?.setScreen(parent)
    }

    private fun reset() {
        AnyRenameModule.INSTANCE.reset(target, true)
        minecraft?.gui?.setScreen(parent)
    }

    private fun copy() {
        sync()
        Minecraft.getInstance().keyboardHandler.clipboard = RenameStore.export(working)
        AnyRenameModule.INSTANCE.say("Override copied to the clipboard.")
    }

    private fun paste() {
        val imported = RenameStore.importOverride(Minecraft.getInstance().keyboardHandler.clipboard)
        if (imported == null) AnyRenameModule.INSTANCE.say("The clipboard does not hold a valid Any Rename string.") else fill(imported)
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        if (dirty) {
            sync()
            preview = RenameApplier.preview(real, working)
            dirty = false
        }
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        GuiTheme.roundedRectBordered(
            graphics, panelX, panelY, PANEL_W, PANEL_H, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.text(font, "Any Rename · ${GuiTheme.fit(font, real.hoverName.string, 190)}", panelX + 12, panelY + 10, GuiTheme.TEXT_PRIMARY)
        val ix = panelX + PANEL_W - 30
        val iy = panelY + 6
        graphics.item(preview, ix, iy)
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
            val lines = ArrayList<Component>()
            lines.add(preview.hoverName)
            preview.get(DataComponents.LORE)?.lines()?.let { lines.addAll(it) }
            graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY)
        }
    }

    private companion object {
        const val PANEL_W = 340
        const val PANEL_H = 256
        const val MAX_FIELD = 256
    }
}
