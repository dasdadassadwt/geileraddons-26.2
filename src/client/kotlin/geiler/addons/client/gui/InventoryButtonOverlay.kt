package geiler.addons.client.gui

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.GeilerAddons
import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.access.AbstractRecipeBookScreenAccess
import geiler.addons.client.access.RecipeBookComponentAccess
import geiler.addons.client.config.ModConfig
import geiler.addons.client.macro.MacroDefinition
import geiler.addons.client.macro.MacroRunner
import geiler.addons.client.module.impl.InventoryButtonLayout
import geiler.addons.client.module.impl.InventoryButtonPlacement
import geiler.addons.client.module.impl.InventoryButtonRules
import geiler.addons.client.module.impl.InventoryButtonTextAction
import geiler.addons.client.module.impl.InventoryButtonsModule
import geiler.addons.client.module.impl.MacrosModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.function.Consumer

/** Render and input bridge for the standard player InventoryScreen only. */
object InventoryButtonOverlay {
    private val PNG_TEXTURES: MutableMap<String, IconTexture> = HashMap()
    private val FAILED_PNGS: MutableSet<String> = HashSet()
    private const val PANEL_ROW_TOP = 37
    private const val PANEL_ROW_HEIGHT = 17
    private const val EDIT_GRID_COLOR = 0x20FFFFFF
    private const val NO_PENDING_CELL = Int.MIN_VALUE
    private const val EDITOR_ROW_COUNT = 9
    private var editing = false
    private var selectedMacroId = -1
    private var selectedPlacementId = -1
    private var draggingId = -1
    private var moveMode = false
    private var pendingGridX = NO_PENDING_CELL
    private var pendingGridY = NO_PENDING_CELL
    private var pendingMacroId = -1
    private var pendingMacroTargetPlacementId = -1
    private var appearance = InventoryButtonPlacement.Appearance.ITEM
    private var appearanceValue = "minecraft:stone"
    private var hoverTooltipValue = ""
    private var selectedTextAction: String? = null
    private var textActionMode = false
    private var status = ""
    private var editorReturnScreen: Screen? = null
    private var editorInventoryScreen: InventoryScreen? = null

    /** Drops runtime texture handles before an imported profile or reset replaces icon files. */
    @JvmStatic
    fun clearIconTextureCache() {
        for (texture in PNG_TEXTURES.values) Minecraft.getInstance().getTextureManager().release(texture.id)
        PNG_TEXTURES.clear()
        FAILED_PNGS.clear()
    }

    @JvmStatic
    fun isEditing(): Boolean = editing

    @JvmStatic
    fun beginEditing() {
        beginEditing(-1, null, null)
    }

    @JvmStatic
    fun beginEditing(placementId: Int) {
        beginEditing(placementId, null, null)
    }

    @JvmStatic
    fun beginEditing(placementId: Int, returnScreen: Screen?, inventoryScreen: InventoryScreen?) {
        editing = true
        editorReturnScreen = returnScreen
        editorInventoryScreen = inventoryScreen
        selectedMacroId = -1
        selectedPlacementId = -1
        draggingId = -1
        moveMode = false
        pendingGridX = NO_PENDING_CELL
        pendingGridY = NO_PENDING_CELL
        pendingMacroId = -1
        pendingMacroTargetPlacementId = -1
        appearance = InventoryButtonPlacement.Appearance.ITEM
        appearanceValue = "minecraft:stone"
        hoverTooltipValue = ""
        selectedTextAction = null
        textActionMode = false
        val selected: InventoryButtonPlacement? = InventoryButtonsModule.INSTANCE.placement(placementId)
        if (selected != null) {
            selectedPlacementId = selected.id()
            appearance = selected.appearance()
            appearanceValue = selected.value()
            hoverTooltipValue = selected.hoverTooltip()
            selectedTextAction = selected.textAction()
            textActionMode = selected.hasTextAction()
            status = "Selected button ${selected.id()}. Drag it, choose Move, or edit its settings."
        } else {
            status = "Click any faint exterior grid cell to choose or create a macro button."
        }
    }

    @JvmStatic
    fun tick() {
        if (!editing) return
        val screen = Minecraft.getInstance().gui.screen()
        if (screen is InventoryScreen || screen is InventoryButtonPickerScreen
            || screen is InventoryButtonTextScreen
            || screen is ScratchMacroEditorScreen
        ) {
            if (screen is InventoryScreen && pendingMacroId >= 0) {
                val macroId = pendingMacroId
                pendingMacroId = -1
                if (hasPendingCell()) {
                    placeMacroAtPending(screen, macroId)
                } else if (pendingMacroTargetPlacementId >= 0) {
                    val target: InventoryButtonPlacement? = InventoryButtonsModule.INSTANCE
                        .placement(pendingMacroTargetPlacementId)
                    pendingMacroTargetPlacementId = -1
                    if (target != null) {
                        target.setMacroId(macroId)
                        target.setTextAction(null)
                        ModConfig.markDirty()
                        status = "Button assigned to ${macroName(macroId)}."
                    }
                } else {
                    selectedMacroId = macroId
                    status = "Macro created. Click an open exterior grid cell to place its button."
                }
            }
            return
        }
        stopEditing()
        editorReturnScreen = null
        editorInventoryScreen = null
    }

    /** Consumes only the inventory screen instance opened by the layout editor. */
    @JvmStatic
    fun consumeEditorReturnScreen(screen: InventoryScreen): Screen? {
        if (screen !== editorInventoryScreen) return null
        val parent = editorReturnScreen
        editorReturnScreen = null
        editorInventoryScreen = null
        return parent
    }

    @JvmStatic
    fun stopEditing() {
        editing = false
        selectedMacroId = -1
        selectedPlacementId = -1
        draggingId = -1
        moveMode = false
        pendingGridX = NO_PENDING_CELL
        pendingGridY = NO_PENDING_CELL
        pendingMacroId = -1
        pendingMacroTargetPlacementId = -1
        status = ""
        selectedTextAction = null
    }

    @JvmStatic
    fun listPngIcons(): List<String> {
        val directory = InventoryButtonsModule.INSTANCE.iconDirectory()
        try {
            Files.createDirectories(directory)
            Files.list(directory).use { paths ->
                return paths.filter { path -> Files.isRegularFile(path) }
                    .map { path -> path.fileName.toString() }
                    .filter { fileName -> InventoryButtonsModule.INSTANCE.isPngIconAllowed(fileName) }
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList()
            }
        } catch (_: IOException) {
            return emptyList()
        } catch (_: SecurityException) {
            return emptyList()
        }
    }

    @JvmStatic
    fun render(
        screen: AbstractContainerScreen<*>,
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (screen !is InventoryScreen) return
        VisualModule.INSTANCE.refreshTheme()
        val editSurface = editing
        val module = InventoryButtonsModule.INSTANCE
        if (!editSurface && !module.isEnabled()) return
        val left = left(screen)
        val top = top(screen)
        val windowWidth = imageWidth(screen)
        val screenWidth = graphics.guiWidth()
        val screenHeight = graphics.guiHeight()
        val placements = module.placements()
        val minecraft = Minecraft.getInstance()
        MacrosModule.INSTANCE.syncSettings()
        val vanillaBounds = vanillaBounds(screen)
        val panel = panel(left, top, windowWidth, screenWidth, screenHeight, vanillaBounds)
        val editBounds = editingBounds(vanillaBounds, panel)
        if (editSurface) drawGrid(graphics, left, top, screenWidth, screenHeight, editBounds)

        for (placement in placements) {
            val bounds = if (editSurface) editBounds else vanillaBounds
            val valid = InventoryButtonLayout.isPlacementValid(
                placements, placement, screenWidth, screenHeight, left, top, bounds,
            )
            if (!valid) {
                if (editSurface) {
                    drawInvalidPlacement(graphics, placement, mouseX, mouseY, left, top, screenWidth, screenHeight)
                }
                continue
            }
            if (!editSurface && !placement.hasTextAction()
                && (placement.macroId() < 0 || MacrosModule.INSTANCE.macro(placement.macroId()) == null)
            ) continue
            val x = InventoryButtonLayout.placementPixelX(placement, left)
            val y = InventoryButtonLayout.placementPixelY(placement, top)
            val macro: MacroDefinition? = if (placement.macroId() < 0) null
            else MacrosModule.INSTANCE.macro(placement.macroId())
            val eligibility: InventoryButtonRules.Eligibility? = if (placement.hasTextAction()) null
            else MacroRunner.inventoryButtonEligibility(macro, minecraft, screen)
            val hovered = mouseX >= x && mouseX < x + InventoryButtonLayout.CELL_SIZE
                && mouseY >= y && mouseY < y + InventoryButtonLayout.CELL_SIZE
            drawButton(
                graphics, placement, macro, x, y, editSurface,
                eligibility == null || eligibility.eligible, placement.id() == selectedPlacementId, hovered, 255,
            )
            if (hovered) graphics.setTooltipForNextFrame(
                minecraft.font, buttonTooltip(placement, macro, eligibility, editSurface), mouseX, mouseY,
            )
        }

        if (editSurface) {
            val ghostGridX = InventoryButtonLayout.gridX(mouseX.toDouble(), left)
            val ghostGridY = InventoryButtonLayout.gridY(mouseY.toDouble(), top)
            val movingId = if (draggingId >= 0) draggingId else if (moveMode) selectedPlacementId else -1
            val moving: InventoryButtonPlacement? = if (movingId < 0) null else module.placement(movingId)
            val showingGhost = moving != null || selectedMacroId >= 0 || selectedTextAction != null
            if (showingGhost && InventoryButtonLayout.fitsViewportAndBounds(
                    ghostGridX, ghostGridY, screenWidth, screenHeight, left, top, editBounds,
                )
            ) {
                val valid = InventoryButtonLayout.canPlace(
                    placements, movingId, ghostGridX, ghostGridY, screenWidth, screenHeight, left, top, editBounds,
                )
                val ghost: InventoryButtonPlacement
                val macro: MacroDefinition?
                if (moving != null) {
                    ghost = InventoryButtonPlacement(moving)
                    ghost.setGrid(ghostGridX, ghostGridY)
                    macro = if (moving.macroId() < 0) null else MacrosModule.INSTANCE.macro(moving.macroId())
                } else if (selectedMacroId >= 0) {
                    ghost = InventoryButtonPlacement(-1)
                    ghost.setMacroId(selectedMacroId)
                    ghost.setGrid(ghostGridX, ghostGridY)
                    ghost.setAppearance(appearance, appearanceValue)
                    ghost.setHoverTooltip(hoverTooltipValue)
                    macro = MacrosModule.INSTANCE.macro(selectedMacroId)
                } else {
                    ghost = InventoryButtonPlacement(-1)
                    ghost.setGrid(ghostGridX, ghostGridY)
                    ghost.setAppearance(appearance, appearanceValue)
                    ghost.setHoverTooltip(hoverTooltipValue)
                    ghost.setTextAction(selectedTextAction)
                    macro = null
                }
                drawButton(
                    graphics, ghost, macro,
                    InventoryButtonLayout.pixelX(ghostGridX, left),
                    InventoryButtonLayout.pixelY(ghostGridY, top), true, valid, false, false, 145,
                )
            }
            drawEditorPanel(graphics, panel, placements, left, top, screenWidth, screenHeight,
                editBounds, mouseX, mouseY)
        }
    }

    @JvmStatic
    fun mouseClicked(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        if (screen !is InventoryScreen) return false
        val module = InventoryButtonsModule.INSTANCE
        val left = left(screen)
        val top = top(screen)
        val screenWidth = screenWidth()
        val screenHeight = screenHeight()
        val mouseX = event.x().toInt()
        val mouseY = event.y().toInt()
        val placements = module.placements()
        val vanillaBounds = vanillaBounds(screen)
        val panel = panel(left, top, imageWidth(screen), screenWidth, screenHeight, vanillaBounds)
        val editBounds = editingBounds(vanillaBounds, panel)

        if (editing) {
            if (panel.contains(mouseX, mouseY)) {
                if (event.button() == 0) handlePanelClick(
                    screen, panel, mouseX, mouseY, editBounds, placements, left, top, screenWidth, screenHeight,
                )
                return true
            }
            var hit: InventoryButtonPlacement? = InventoryButtonLayout.hit(
                placements, event.x(), event.y(), left, top, screenWidth, screenHeight, editBounds,
            )
            if (hit == null) {
                hit = InventoryButtonLayout.hitSaved(placements, event.x(), event.y(), left, top, screenWidth, screenHeight)
                if (hit != null) {
                    selectedPlacementId = hit.id()
                    selectedMacroId = -1
                    appearance = hit.appearance()
                    appearanceValue = hit.value()
                    hoverTooltipValue = hit.hoverTooltip()
                    selectedTextAction = hit.textAction()
                    textActionMode = hit.hasTextAction()
                    draggingId = if (event.button() == 0) hit.id() else -1
                    status = "Invalid saved position. Drag this button or use Reflow to move it outside the inventory."
                    return true
                }
            }
            if (hit != null) {
                selectedPlacementId = hit.id()
                selectedMacroId = -1
                appearance = hit.appearance()
                appearanceValue = hit.value()
                hoverTooltipValue = hit.hoverTooltip()
                selectedTextAction = hit.textAction()
                textActionMode = hit.hasTextAction()
                if (event.button() == 0 && moveMode) {
                    status = "Choose an open grid cell for the selected button."
                } else {
                    draggingId = if (event.button() == 0) hit.id() else -1
                    status = "Selected button ${hit.id()}. Drag it or use Move / Delete in the editor panel."
                }
                return true
            }
            if (event.button() != 0) {
                if (event.button() == 1 && (selectedMacroId >= 0 || selectedTextAction != null)) {
                    selectedMacroId = -1
                    selectedTextAction = null
                    status = "Button placement cancelled."
                    return true
                }
                return false
            }
            val gridX = InventoryButtonLayout.gridX(event.x(), left)
            val gridY = InventoryButtonLayout.gridY(event.y(), top)
            if (!InventoryButtonLayout.fitsViewportAndBounds(
                    gridX, gridY, screenWidth, screenHeight, left, top, editBounds,
                )
            ) return false
            if (moveMode && selectedPlacementId >= 0) {
                if (InventoryButtonLayout.canPlace(
                        placements, selectedPlacementId, gridX, gridY,
                        screenWidth, screenHeight, left, top, editBounds,
                    )
                ) {
                    val selected: InventoryButtonPlacement? = module.placement(selectedPlacementId)
                    if (selected != null) {
                        selected.setGrid(gridX, gridY)
                        ModConfig.markDirty()
                    }
                    moveMode = false
                    status = "Button moved."
                } else {
                    status = "That exterior cell is occupied."
                }
                return true
            }
            if (selectedMacroId >= 0) {
                if (InventoryButtonLayout.canPlace(
                        placements, -1, gridX, gridY, screenWidth, screenHeight, left, top, editBounds,
                    )
                ) {
                    module.create(selectedMacroId, gridX, gridY, appearance, appearanceValue)
                    val created = module.placements().last()
                    created.setHoverTooltip(hoverTooltipValue)
                    ModConfig.markDirty()
                    selectedPlacementId = created.id()
                    status = "Button placed. Click another open cell or select a different macro."
                } else {
                    status = "That exterior cell is occupied."
                }
                return true
            }
            if (selectedTextAction != null) {
                if (InventoryButtonLayout.canPlace(
                        placements, -1, gridX, gridY, screenWidth, screenHeight, left, top, editBounds,
                    )
                ) {
                    InventoryButtonsModule.INSTANCE.createTextAction(
                        selectedTextAction, gridX, gridY, appearance, appearanceValue,
                    ).setHoverTooltip(hoverTooltipValue)
                    ModConfig.markDirty()
                    selectedPlacementId = InventoryButtonsModule.INSTANCE.placements().last().id()
                    status = "Text action button placed."
                } else {
                    status = "That exterior cell is occupied."
                }
                return true
            }
            if (!InventoryButtonLayout.canPlace(
                    placements, -1, gridX, gridY, screenWidth, screenHeight, left, top, editBounds,
                )
            ) {
                status = "That exterior cell is occupied."
                return true
            }
            pendingGridX = gridX
            pendingGridY = gridY
            if (textActionMode) openTextActionEditor(screen) else openMacroPicker(screen)
            return true
        }

        if (!module.isEnabled()) return false
        val hit = InventoryButtonLayout.hit(
            placements, event.x(), event.y(), left, top, screenWidth, screenHeight, vanillaBounds,
        ) ?: return false
        // Consume every click on a valid button so the inventory slot beneath it stays inert.
        if (event.button() == 0 && hit.hasTextAction()) {
            executeTextAction(hit.id(), hit.textAction())
        } else if (event.button() == 0 && hit.macroId() >= 0) {
            val eligibility = MacroRunner.activateInventoryButton(hit.macroId())
            if (!eligibility.eligible) status = eligibility.reason!!
        }
        return true
    }

    @JvmStatic
    fun mouseDragged(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        if (!editing || screen !is InventoryScreen || draggingId < 0 || event.button() != 0) return false
        val left = left(screen)
        val top = top(screen)
        val vanillaBounds = vanillaBounds(screen)
        val panel = panel(left, top, imageWidth(screen), screenWidth(), screenHeight(), vanillaBounds)
        val blocked = editingBounds(vanillaBounds, panel)
        val placement: InventoryButtonPlacement? = InventoryButtonsModule.INSTANCE.placement(draggingId)
        if (placement == null) return true
        val gridX = InventoryButtonLayout.gridX(event.x(), left)
        val gridY = InventoryButtonLayout.gridY(event.y(), top)
        if (InventoryButtonLayout.canPlace(
                InventoryButtonsModule.INSTANCE.placements(), draggingId,
                gridX, gridY, screenWidth(), screenHeight(), left, top, blocked,
            )
            && (placement.gridX() != gridX || placement.gridY() != gridY)
        ) {
            placement.setGrid(gridX, gridY)
            ModConfig.markDirty()
        }
        return true
    }

    @JvmStatic
    fun keyPressed(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        if (!editing || screen !is InventoryScreen) return false
        if (event.key() == InputConstants.KEY_ESCAPE && moveMode) {
            moveMode = false
            status = "Move cancelled."
            return true
        }
        if (selectedPlacementId >= 0 && (event.key() == InputConstants.KEY_DELETE
                || event.key() == InputConstants.KEY_BACKSPACE)
        ) {
            InventoryButtonsModule.INSTANCE.remove(selectedPlacementId)
            selectedPlacementId = -1
            moveMode = false
            status = "Button deleted."
            return true
        }
        return false
    }

    @JvmStatic
    fun mouseReleased(screen: AbstractContainerScreen<*>) {
        if (screen is InventoryScreen) draggingId = -1
    }

    @JvmStatic
    fun mouseReleased(screen: InventoryScreen) {
        mouseReleased(screen as AbstractContainerScreen<*>)
    }

    private fun handlePanelClick(
        screen: Screen,
        panel: Panel,
        mouseX: Int,
        mouseY: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
        placements: List<InventoryButtonPlacement>,
        left: Int,
        top: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val row = (mouseY - panel.y - PANEL_ROW_TOP) / panel.rowHeight
        if (row < 0 || row >= EDITOR_ROW_COUNT || mouseY < panel.y + PANEL_ROW_TOP
            || mouseX < panel.x + 4 || mouseX >= panel.x + panel.width - 4
        ) return
        when (row) {
            0 -> {
                textActionMode = !textActionMode
                val selected = selectedPlacement()
                selectedTextAction = if (textActionMode && selected != null) selected.textAction() else null
                selectedMacroId = -1
                status = "Button type: ${if (textActionMode) "direct text action" else "macro"}."
            }
            1 -> if (textActionMode) openTextActionEditor(screen) else openMacroPicker(screen)
            2 -> cycleAppearance()
            3 -> openAppearancePicker(screen)
            4 -> openTooltipEditor(screen)
            5 -> {
                if (selectedPlacementId < 0) status = "Select a button before moving it."
                else {
                    moveMode = !moveMode
                    status = if (moveMode) "Click an open exterior grid cell to move the selected button."
                    else "Move mode cancelled."
                }
            }
            6 -> {
                if (selectedPlacementId < 0) status = "Select a button before deleting it."
                else {
                    InventoryButtonsModule.INSTANCE.remove(selectedPlacementId)
                    selectedPlacementId = -1
                    moveMode = false
                    status = "Button deleted."
                }
            }
            7 -> reflow(placements, left, top, screenWidth, screenHeight, blocked)
            8 -> stopEditing()
            else -> Unit
        }
    }

    private fun openMacroPicker(parent: Screen) {
        val minecraft = Minecraft.getInstance()
        minecraft.gui.setScreen(
            InventoryButtonPickerScreen(
                parent,
                InventoryButtonPickerScreen.Mode.MACRO,
                Consumer { value -> selectMacro(value) },
                Runnable { createMacroAndOpenEditor(parent) },
                Runnable { cancelPendingCell() },
            ),
        )
    }

    private fun selectMacro(value: String) {
        val macroId = try {
            Integer.parseInt(value)
        } catch (_: NumberFormatException) {
            return
        }
        textActionMode = false
        selectedTextAction = null
        val selected = selectedPlacement()
        if (selected != null && pendingGridX == NO_PENDING_CELL) {
            selected.setMacroId(macroId)
            selected.setTextAction(null)
            ModConfig.markDirty()
            selectedMacroId = -1
            status = "Button assigned to ${macroName(macroId)}."
        } else if (hasPendingCell()) {
            placeMacroAtPending(Minecraft.getInstance().gui.screen() as InventoryScreen, macroId)
        } else {
            selectedPlacementId = -1
            selectedMacroId = macroId
            status = "Macro selected. Click an open exterior grid cell to place it."
        }
    }

    private fun createMacroAndOpenEditor(parent: Screen) {
        textActionMode = false
        selectedTextAction = null
        val macro = MacrosModule.INSTANCE.createMacro()
        pendingMacroId = macro.id()
        pendingMacroTargetPlacementId = if (hasPendingCell()) -1 else selectedPlacementId
        Minecraft.getInstance().gui.setScreen(ScratchMacroEditorScreen(parent, macro))
    }

    private fun placeMacroAtPending(screen: InventoryScreen?, macroId: Int) {
        if (screen == null || !hasPendingCell()) return
        val left = left(screen)
        val top = top(screen)
        val vanillaBounds = vanillaBounds(screen)
        val panel = panel(left, top, imageWidth(screen), screenWidth(), screenHeight(), vanillaBounds)
        val blocked = editingBounds(vanillaBounds, panel)
        val placements = InventoryButtonsModule.INSTANCE.placements()
        val x = pendingGridX
        val y = pendingGridY
        pendingGridX = NO_PENDING_CELL
        pendingGridY = NO_PENDING_CELL
        if (!InventoryButtonLayout.canPlace(
                placements, -1, x, y, screenWidth(), screenHeight(), left, top, blocked,
            )
        ) {
            selectedMacroId = macroId
            status = "The original cell is no longer available. Choose another exterior cell."
            return
        }
        val created = InventoryButtonsModule.INSTANCE.create(macroId, x, y, appearance, appearanceValue)
        created.setTextAction(null)
        created.setHoverTooltip(hoverTooltipValue)
        ModConfig.markDirty()
        selectedPlacementId = created.id()
        selectedMacroId = -1
        status = "Button placed for ${macroName(macroId)}. Hover it to preview its tooltip."
    }

    private fun cancelPendingCell() {
        pendingGridX = NO_PENDING_CELL
        pendingGridY = NO_PENDING_CELL
        status = "Macro selection cancelled."
    }

    private fun hasPendingCell(): Boolean = pendingGridX != NO_PENDING_CELL && pendingGridY != NO_PENDING_CELL

    private fun cycleAppearance() {
        appearance = when (appearance) {
            InventoryButtonPlacement.Appearance.ITEM -> InventoryButtonPlacement.Appearance.TEXT
            InventoryButtonPlacement.Appearance.TEXT -> InventoryButtonPlacement.Appearance.PNG
            InventoryButtonPlacement.Appearance.PNG -> InventoryButtonPlacement.Appearance.ITEM
        }
        if (appearance == InventoryButtonPlacement.Appearance.ITEM) appearanceValue = "minecraft:stone"
        if (appearance == InventoryButtonPlacement.Appearance.TEXT) appearanceValue = "Go"
        if (appearance == InventoryButtonPlacement.Appearance.PNG) appearanceValue = ""
        applyAppearanceToSelected()
        status = "Appearance style: ${appearance.name}. Choose its content on the next row."
    }

    private fun openAppearancePicker(parent: Screen) {
        when (appearance) {
            InventoryButtonPlacement.Appearance.ITEM -> Minecraft.getInstance().gui.setScreen(
                InventoryButtonPickerScreen(
                    parent,
                    InventoryButtonPickerScreen.Mode.ITEM,
                    Consumer { value -> setAppearanceValue(value) },
                ),
            )
            InventoryButtonPlacement.Appearance.PNG -> Minecraft.getInstance().gui.setScreen(
                InventoryButtonPickerScreen(
                    parent,
                    InventoryButtonPickerScreen.Mode.PNG,
                    Consumer { value -> setAppearanceValue(value) },
                ),
            )
            InventoryButtonPlacement.Appearance.TEXT -> Minecraft.getInstance().gui.setScreen(
                InventoryButtonTextScreen(
                    parent,
                    currentAppearanceValue(),
                    Consumer { value -> setAppearanceValue(value) },
                ),
            )
        }
    }

    private fun openTooltipEditor(parent: Screen) {
        val selected = selectedPlacement()
        val initial = if (selected == null) hoverTooltipValue else selected.hoverTooltip()
        Minecraft.getInstance().gui.setScreen(
            InventoryButtonTextScreen(
                parent,
                "Inventory Button Tooltip",
                "Shown with the macro name and eligibility state when hovered.",
                initial,
                Consumer { value ->
                    hoverTooltipValue = value ?: ""
                    if (selected != null) {
                        selected.setHoverTooltip(hoverTooltipValue)
                        ModConfig.markDirty()
                    }
                    status = "Hover tooltip updated."
                },
            ),
        )
    }

    private fun currentAppearanceValue(): String {
        val selected = selectedPlacement()
        return if (selected == null) appearanceValue else selected.value()
    }

    private fun setAppearanceValue(value: String?) {
        appearanceValue = value ?: ""
        applyAppearanceToSelected()
        status = "Button appearance updated."
    }

    private fun applyAppearanceToSelected() {
        val selected = selectedPlacement()
        if (selected != null) {
            selected.setAppearance(appearance, appearanceValue)
            ModConfig.markDirty()
        }
    }

    private fun selectedPlacement(): InventoryButtonPlacement? =
        if (selectedPlacementId < 0) null else InventoryButtonsModule.INSTANCE.placement(selectedPlacementId)

    private fun reflow(
        placements: List<InventoryButtonPlacement>,
        left: Int,
        top: Int,
        screenWidth: Int,
        screenHeight: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
    ) {
        val result = InventoryButtonLayout.reflowInvalid(placements, screenWidth, screenHeight, left, top, blocked)
        if (result.moved > 0) ModConfig.markDirty()
        status = if (result.remaining == 0) {
            "Reflow moved ${result.moved} button(s) into valid exterior cells."
        } else {
            "Reflow moved ${result.moved}; ${result.remaining} button(s) still need space. Increase GUI room or remove a placement."
        }
    }

    private fun drawGrid(
        graphics: GuiGraphicsExtractor,
        left: Int,
        top: Int,
        screenWidth: Int,
        screenHeight: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
    ) {
        val originX = left + InventoryButtonLayout.FIRST_SLOT_X
        val originY = top + InventoryButtonLayout.FIRST_SLOT_Y
        val cellSize = InventoryButtonLayout.CELL_SIZE
        if (screenWidth <= 0 || screenHeight <= 0) return

        // Draw the lattice once as thin strokes instead of filling and outlining every cell. The
        // old per-cell path emitted several thousand GUI operations on a typical screen each frame.
        val minGridX = kotlin.math.ceil(-originX / cellSize.toDouble()).toInt()
        val minGridY = kotlin.math.ceil(-originY / cellSize.toDouble()).toInt()
        val maxGridX = kotlin.math.floor((screenWidth - 1 - originX) / cellSize.toDouble()).toInt()
        val maxGridY = kotlin.math.floor((screenHeight - 1 - originY) / cellSize.toDouble()).toInt()
        for (gridX in minGridX..maxGridX) {
            drawVerticalGridLine(graphics, originX + gridX * cellSize, screenHeight, blocked)
        }
        for (gridY in minGridY..maxGridY) {
            drawHorizontalGridLine(graphics, originY + gridY * cellSize, screenWidth, blocked)
        }
    }

    private fun drawVerticalGridLine(
        graphics: GuiGraphicsExtractor,
        x: Int,
        screenHeight: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
    ) {
        var cursor = 0
        while (cursor < screenHeight) {
            var nextBlocked = screenHeight
            var coveredUntil = cursor
            for (bounds in blocked) {
                if (bounds == null || x < bounds.x || x >= bounds.x + bounds.width) continue
                val start = bounds.y
                val end = minOf(screenHeight, bounds.y + bounds.height)
                if (end <= cursor || start >= screenHeight) continue
                if (start <= cursor) coveredUntil = maxOf(coveredUntil, end)
                else nextBlocked = minOf(nextBlocked, start)
            }
            if (coveredUntil > cursor) {
                cursor = coveredUntil
            } else if (nextBlocked > cursor) {
                graphics.fill(x, cursor, x + 1, nextBlocked, EDIT_GRID_COLOR)
                cursor = nextBlocked
            } else {
                graphics.fill(x, cursor, x + 1, screenHeight, EDIT_GRID_COLOR)
                break
            }
        }
    }

    private fun drawHorizontalGridLine(
        graphics: GuiGraphicsExtractor,
        y: Int,
        screenWidth: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
    ) {
        var cursor = 0
        while (cursor < screenWidth) {
            var nextBlocked = screenWidth
            var coveredUntil = cursor
            for (bounds in blocked) {
                if (bounds == null || y < bounds.y || y >= bounds.y + bounds.height) continue
                val start = bounds.x
                val end = minOf(screenWidth, bounds.x + bounds.width)
                if (end <= cursor || start >= screenWidth) continue
                if (start <= cursor) coveredUntil = maxOf(coveredUntil, end)
                else nextBlocked = minOf(nextBlocked, start)
            }
            if (coveredUntil > cursor) {
                cursor = coveredUntil
            } else if (nextBlocked > cursor) {
                graphics.fill(cursor, y, nextBlocked, y + 1, EDIT_GRID_COLOR)
                cursor = nextBlocked
            } else {
                graphics.fill(cursor, y, screenWidth, y + 1, EDIT_GRID_COLOR)
                break
            }
        }
    }

    private fun drawEditorPanel(
        graphics: GuiGraphicsExtractor,
        panel: Panel,
        placements: List<InventoryButtonPlacement>,
        left: Int,
        top: Int,
        screenWidth: Int,
        screenHeight: Int,
        blocked: List<InventoryButtonLayout.Bounds>,
        mouseX: Int,
        mouseY: Int,
    ) {
        GuiTheme.roundedRectBordered(
            graphics, panel.x, panel.y, panel.width, panel.height, GuiTheme.RADIUS_SMALL,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER,
        )
        graphics.text(Minecraft.getInstance().font, "Inventory Buttons · Edit", panel.x + 7, panel.y + 6, GuiTheme.TEXT_PRIMARY)
        val selected = selectedPlacement()
        val macro: MacroDefinition? = if (selected == null && selectedMacroId >= 0) {
            MacrosModule.INSTANCE.macro(selectedMacroId)
        } else if (selected == null) {
            null
        } else {
            MacrosModule.INSTANCE.macro(selected.macroId())
        }
        val previewX = panel.x + panel.width - 27
        val previewY = panel.y + 4
        var preview = selected
        if (preview == null && (selectedMacroId >= 0 || selectedTextAction != null)) {
            preview = InventoryButtonPlacement(-1)
            if (selectedMacroId >= 0) preview.setMacroId(selectedMacroId)
            preview.setAppearance(appearance, appearanceValue)
            preview.setHoverTooltip(hoverTooltipValue)
            preview.setTextAction(selectedTextAction)
        }
        if (preview != null) {
            drawButton(
                graphics, preview, macro, previewX, previewY, true, true,
                selected != null, mouseX >= previewX && mouseX < previewX + 18
                    && mouseY >= previewY && mouseY < previewY + 18, 255,
            )
            if (mouseX >= previewX && mouseX < previewX + 18 && mouseY >= previewY && mouseY < previewY + 18) {
                graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font,
                    buttonTooltip(preview, macro, null, true),
                    mouseX,
                    mouseY,
                )
            }
        }
        val target = if (selected == null) {
            if (selectedTextAction != null) "New · Text action"
            else if (macro == null) "No button selected" else "New · ${macro.name()}"
        } else if (selected.hasTextAction()) {
            "Text action · ${trim(selected.textAction(), panel.width - 88)}"
        } else if (macro == null) {
            "Unassigned button ${selected.id()}"
        } else {
            macro.name()
        }
        graphics.text(
            Minecraft.getInstance().font, trim(target, panel.width - 18), panel.x + 7, panel.y + 24, GuiTheme.TEXT_MUTED,
        )
        val invalid = InventoryButtonLayout.invalidCount(placements, screenWidth, screenHeight, left, top, blocked)
        drawPanelRow(graphics, panel, 0, "Button type: ${if (textActionMode) "Text action" else "Macro"}", mouseX, mouseY)
        drawPanelRow(
            graphics, panel, 1,
            if (textActionMode) {
                if (selectedTextAction == null) "Set command / chat text" else "Edit command / chat text"
            } else if (selected == null) "Choose macro / create new" else "Change assigned macro",
            mouseX, mouseY,
        )
        drawPanelRow(graphics, panel, 2, "Style: ${appearance.name}", mouseX, mouseY)
        drawPanelRow(
            graphics, panel, 3,
            when (appearance) {
                InventoryButtonPlacement.Appearance.ITEM -> "Choose registered icon"
                InventoryButtonPlacement.Appearance.TEXT -> "Edit button text"
                InventoryButtonPlacement.Appearance.PNG -> "Choose PNG file"
            },
            mouseX, mouseY,
        )
        drawPanelRow(
            graphics, panel, 4,
            "Hover tooltip: ${if (isJavaBlank(hoverTooltipValue)) "none" else "edit"}", mouseX, mouseY,
        )
        drawPanelRow(graphics, panel, 5, if (moveMode) "Click a cell to move…" else "Move selected button", mouseX, mouseY)
        drawPanelRow(graphics, panel, 6, "Delete selected button", mouseX, mouseY)
        drawPanelRow(graphics, panel, 7, "Reflow invalid ($invalid)", mouseX, mouseY)
        drawPanelRow(graphics, panel, 8, "Done editing", mouseX, mouseY)
        if (!isJavaBlank(status)) {
            graphics.text(
                Minecraft.getInstance().font, trim(status, panel.width - 14),
                panel.x + 7, panel.y + panel.height - 12, GuiTheme.TEXT_MUTED,
            )
        }
    }

    private fun drawInvalidPlacement(
        graphics: GuiGraphicsExtractor,
        placement: InventoryButtonPlacement,
        mouseX: Int,
        mouseY: Int,
        left: Int,
        top: Int,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        val x = InventoryButtonLayout.placementPixelX(placement, left)
        val y = InventoryButtonLayout.placementPixelY(placement, top)
        if (x < 0 || y < 0 || x + InventoryButtonLayout.CELL_SIZE > screenWidth
            || y + InventoryButtonLayout.CELL_SIZE > screenHeight
        ) return
        graphics.fill(x, y, x + InventoryButtonLayout.CELL_SIZE, y + InventoryButtonLayout.CELL_SIZE, 0x55D02030)
        graphics.outline(
            x, y, InventoryButtonLayout.CELL_SIZE, InventoryButtonLayout.CELL_SIZE,
            if (placement.id() == selectedPlacementId) 0xFFFFD65A.toInt() else 0xFFFF5555.toInt(),
        )
        graphics.fill(x + 5, y + 4, x + 13, y + 6, 0xFFFF5555.toInt())
        graphics.fill(x + 5, y + 11, x + 13, y + 13, 0xFFFF5555.toInt())
        graphics.fill(x + 8, y + 6, x + 10, y + 11, 0xFFFF5555.toInt())
        if (mouseX >= x && mouseX < x + InventoryButtonLayout.CELL_SIZE
            && mouseY >= y && mouseY < y + InventoryButtonLayout.CELL_SIZE
        ) {
            val macro: MacroDefinition? = if (placement.macroId() < 0) null
            else MacrosModule.INSTANCE.macro(placement.macroId())
            val lines = ArrayList<String>()
            if (!isJavaBlank(placement.hoverTooltip())) lines.add(placement.hoverTooltip())
            lines.add(
                if (placement.hasTextAction()) "Text action: ${placement.textAction()}"
                else if (macro == null) "Unassigned button" else macro.name(),
            )
            lines.add("Invalid saved position · drag or use Reflow")
            graphics.setTooltipForNextFrame(
                Minecraft.getInstance().font,
                Component.literal(java.lang.String.join("\n", lines)),
                mouseX,
                mouseY,
            )
        }
    }

    private fun drawPanelRow(
        graphics: GuiGraphicsExtractor,
        panel: Panel,
        row: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
    ) {
        val y = panel.y + PANEL_ROW_TOP + row * panel.rowHeight
        val rowBoxHeight = maxOf(9, panel.rowHeight - 2)
        val hover = mouseX >= panel.x + 4 && mouseX < panel.x + panel.width - 4
            && mouseY >= y && mouseY < y + rowBoxHeight
        GuiTheme.roundedRect(
            graphics, panel.x + 4, y, panel.width - 8, rowBoxHeight, GuiTheme.RADIUS_SMALL,
            if (hover) GuiTheme.BUTTON_HOVER else GuiTheme.BUTTON_BG,
        )
        graphics.centeredText(
            Minecraft.getInstance().font, trim(label, panel.width - 18),
            panel.x + panel.width / 2, y + maxOf(0, (rowBoxHeight - 9) / 2),
            if (hover) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY,
        )
    }

    private fun drawButton(
        graphics: GuiGraphicsExtractor,
        placement: InventoryButtonPlacement,
        macro: MacroDefinition?,
        x: Int,
        y: Int,
        edit: Boolean,
        eligible: Boolean,
        selected: Boolean,
        hovered: Boolean,
        alpha: Int,
    ) {
        val themed = VisualModule.INSTANCE.themeSurfaces().value()
        val backgroundAlpha = minOf(alpha, 208)
        val defaultRgb = if (edit) {
            if (eligible) 0x00181B23 else 0x003A1C1C
        } else {
            if (eligible) 0x00181B23 else 0x003C2525
        }
        val surfaceRgb = if (themed) GuiTheme.BUTTON_BG and 0x00FFFFFF else defaultRgb
        graphics.fill(x, y, x + 18, y + 18, (backgroundAlpha shl 24) or surfaceRgb)
        val border = if (selected) 0xFFFFD65A.toInt() else if (hovered) 0xFFFFFFFF.toInt()
        else if (eligible) {
            if (themed) GuiTheme.withOpacity(GuiTheme.CATEGORY_SELECTED, 0.85f) else 0xFFC5C7D2.toInt()
        } else 0xFFE07070.toInt()
        graphics.outline(x, y, InventoryButtonLayout.CELL_SIZE, InventoryButtonLayout.CELL_SIZE, border)
        if (placement.macroId() < 0 && !placement.hasTextAction()) {
            graphics.centeredText(Minecraft.getInstance().font, "?", x + 9, y + 5, 0xFFFFD65A.toInt())
        } else {
            drawAppearance(graphics, placement, x, y)
        }
        if (edit && macro == null && placement.macroId() >= 0) {
            graphics.fill(x, y, x + 18, y + 18, 0x88000000.toInt())
        }
    }

    private fun drawAppearance(graphics: GuiGraphicsExtractor, placement: InventoryButtonPlacement, x: Int, y: Int) {
        when (placement.appearance()) {
            InventoryButtonPlacement.Appearance.ITEM -> {
                val item = registeredItem(placement.value())
                if (item != Items.AIR) graphics.item(ItemStack(item), x + 1, y + 1)
                else graphics.centeredText(Minecraft.getInstance().font, "?", x + 9, y + 5, 0xFFFF7777.toInt())
            }
            InventoryButtonPlacement.Appearance.TEXT -> {
                var label = placement.value()
                if (isJavaBlank(label)) label = "?"
                graphics.pose().pushMatrix()
                graphics.pose().translate((x + 1).toFloat(), (y + 4).toFloat())
                graphics.pose().scale(0.55f, 0.55f)
                graphics.centeredText(Minecraft.getInstance().font, trim(label, 26), 14, 8, 0xFFFFFFFF.toInt())
                graphics.pose().popMatrix()
            }
            InventoryButtonPlacement.Appearance.PNG -> drawPng(graphics, placement.value(), x + 1, y + 1)
        }
    }

    private fun registeredItem(value: String?): Item {
        val id = Identifier.tryParse(value ?: "") ?: return Items.AIR
        if (!BuiltInRegistries.ITEM.keySet().contains(id)) return Items.AIR
        return BuiltInRegistries.ITEM.getValue(id) ?: Items.AIR
    }

    private fun drawPng(graphics: GuiGraphicsExtractor, fileName: String, x: Int, y: Int) {
        if (!InventoryButtonsModule.INSTANCE.isPngIconAllowed(fileName)) return
        val texture = pngTexture(fileName)
        if (texture == null) {
            graphics.centeredText(Minecraft.getInstance().font, "?", x + 8, y + 4, 0xFFFF7777.toInt())
            return
        }
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, texture.id,
            x, y, 0f, 0f, 16, 16, texture.width, texture.height,
        )
    }

    private fun pngTexture(fileName: String): IconTexture? {
        val cached = PNG_TEXTURES[fileName]
        if (cached != null) return cached
        if (FAILED_PNGS.contains(fileName) || PNG_TEXTURES.size >= 128) return null
        val directory = InventoryButtonsModule.INSTANCE.iconDirectory().toAbsolutePath().normalize()
        val path = directory.resolve(fileName).normalize()
        if (!path.startsWith(directory)) return null
        try {
            val size = Files.size(path)
            if (size <= 0 || size > 4 * 1024 * 1024) throw IOException("PNG exceeds the 4 MiB icon limit")
            val image = Files.newInputStream(path).use { input -> NativeImage.read(input) }
            if (image.width < 1 || image.height < 1 || image.width > 1024 || image.height > 1024
                || image.width.toLong() * image.height.toLong() > 1_048_576L
            ) {
                image.close()
                throw IOException("PNG dimensions exceed the 1024×1024 icon limit")
            }
            val id = GeilerAddons.id("inventory_button_icons/${textureKey(fileName)}")
            Minecraft.getInstance().getTextureManager().register(
                id,
                DynamicTexture({ "GeilerAddons inventory icon $fileName" }, image),
            )
            val created = IconTexture(id, image.width, image.height)
            PNG_TEXTURES[fileName] = created
            return created
        } catch (_: IOException) {
            FAILED_PNGS.add(fileName)
            return null
        } catch (_: RuntimeException) {
            FAILED_PNGS.add(fileName)
            return null
        }
    }

    private fun textureKey(value: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val hex = StringBuilder(24)
            for (index in 0 until 12) hex.append(String.format(Locale.ROOT, "%02x", digest[index]))
            return hex.toString()
        } catch (_: NoSuchAlgorithmException) {
            return Integer.toUnsignedString(value.hashCode(), 16)
        }
    }

    private fun panel(
        left: Int,
        top: Int,
        inventoryWidth: Int,
        screenWidth: Int,
        screenHeight: Int,
        occupied: List<InventoryButtonLayout.Bounds>,
    ): Panel {
        val position = InventoryButtonLayout.editorPanel(
            screenWidth, screenHeight, left, top, inventoryWidth, occupied,
        )
        val rowHeight = maxOf(10, minOf(PANEL_ROW_HEIGHT,
            (position.height - PANEL_ROW_TOP - 12) / EDITOR_ROW_COUNT))
        return Panel(position.x, position.y, position.width, position.height, rowHeight)
    }

    private fun vanillaBounds(screen: AbstractContainerScreen<*>): List<InventoryButtonLayout.Bounds> {
        val left = left(screen)
        val top = top(screen)
        val result = ArrayList<InventoryButtonLayout.Bounds>()
        result.add(InventoryButtonLayout.inventoryBounds(left, top, imageWidth(screen), imageHeight(screen)))
        if (screen is AbstractRecipeBookScreen<*>) {
            val recipeBook = (screen as AbstractRecipeBookScreenAccess).`geileraddons$getRecipeBookComponent`()
            if (recipeBook.isVisible()) {
                val invoker = recipeBook as RecipeBookComponentAccess
                result.add(
                    InventoryButtonLayout.inventoryBounds(
                        invoker.`geileraddons$getXOrigin`(),
                        invoker.`geileraddons$getYOrigin`(),
                        RecipeBookComponent.IMAGE_WIDTH,
                        RecipeBookComponent.IMAGE_HEIGHT,
                    ),
                )
            }
        }
        return Collections.unmodifiableList(result)
    }

    private fun editingBounds(
        vanillaBounds: List<InventoryButtonLayout.Bounds>,
        panel: Panel,
    ): List<InventoryButtonLayout.Bounds> {
        val result = ArrayList(vanillaBounds)
        result.add(panel.bounds())
        return Collections.unmodifiableList(result)
    }

    private fun left(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getLeftPos`()

    private fun top(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getTopPos`()

    private fun imageWidth(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getImageWidth`()

    private fun imageHeight(screen: AbstractContainerScreen<*>): Int =
        (screen as AbstractContainerScreenAccess).`geileraddons$getImageHeight`()

    private fun buttonTooltip(
        placement: InventoryButtonPlacement,
        macro: MacroDefinition?,
        eligibility: InventoryButtonRules.Eligibility?,
        editSurface: Boolean,
    ): Component {
        val lines = ArrayList<String>()
        if (!isJavaBlank(placement.hoverTooltip())) lines.add(placement.hoverTooltip())
        if (placement.hasTextAction()) {
            lines.add("Text action: ${placement.textAction()}")
            lines.add(if (placement.textAction()!!.startsWith("/")) "Sends a command" else "Sends a chat message")
        } else {
            lines.add(if (macro == null) "Unassigned button" else macro.name())
        }
        if (eligibility != null && !eligibility.eligible) lines.add(eligibility.reason ?: "null")
        else if (editSurface) lines.add("Click to select · drag or use Move to reposition")
        else lines.add(if (placement.hasTextAction()) "Click to send" else "Click to run")
        return Component.literal(java.lang.String.join("\n", lines))
    }

    private fun openTextActionEditor(parent: Screen) {
        val selected = selectedPlacement()
        val initial = if (selected == null) selectedTextAction else selected.textAction()
        Minecraft.getInstance().gui.setScreen(
            InventoryButtonTextScreen(
                parent,
                "Inventory Button Action",
                "Start with / to send a command; otherwise the text is sent as a chat message.",
                initial,
                Consumer { value -> selectTextAction(value) },
            ),
        )
    }

    private fun selectTextAction(value: String?) {
        val action = value?.trim { character -> character <= ' ' } ?: ""
        if (action.isEmpty()) {
            status = "Enter a command or chat message before saving."
            return
        }
        selectedTextAction = action.substring(0, minOf(256, action.length))
        textActionMode = true
        selectedMacroId = -1
        val selected = selectedPlacement()
        if (selected != null && !hasPendingCell()) {
            selected.setMacroId(-1)
            selected.setTextAction(selectedTextAction)
            ModConfig.markDirty()
            status = "Text action saved."
            return
        }
        val screen = Minecraft.getInstance().gui.screen()
        if (hasPendingCell() && screen is InventoryScreen) {
            val left = left(screen)
            val top = top(screen)
            val vanilla = vanillaBounds(screen)
            val panel = panel(left, top, imageWidth(screen), screenWidth(), screenHeight(), vanilla)
            val blocked = editingBounds(vanilla, panel)
            val gridX = pendingGridX
            val gridY = pendingGridY
            pendingGridX = NO_PENDING_CELL
            pendingGridY = NO_PENDING_CELL
            if (InventoryButtonLayout.canPlace(
                    InventoryButtonsModule.INSTANCE.placements(), -1,
                    gridX, gridY, screenWidth(), screenHeight(), left, top, blocked,
                )
            ) {
                val created = InventoryButtonsModule.INSTANCE.createTextAction(
                    selectedTextAction, gridX, gridY, appearance, appearanceValue,
                )
                created.setHoverTooltip(hoverTooltipValue)
                selectedPlacementId = created.id()
                status = "Text action button placed."
            } else {
                status = "That exterior cell is no longer available."
            }
            ModConfig.markDirty()
            return
        }
        selectedPlacementId = -1
        status = "Text action ready. Click an open exterior cell to place the button."
    }

    private fun executeTextAction(placementId: Int, raw: String?) {
        val module = InventoryButtonsModule.INSTANCE
        val dispatch = InventoryButtonTextAction.parse(raw)
        if (dispatch == null) {
            module.recordTextActionDispatch(
                placementId, null, raw?.length ?: 0, InventoryButtonsModule.TextActionDispatchOutcome.EMPTY,
            )
            return
        }
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val connection = player?.connection
        if (connection == null) {
            module.recordTextActionDispatch(
                placementId, dispatch.kind, raw?.length ?: 0,
                InventoryButtonsModule.TextActionDispatchOutcome.NO_CONNECTION,
            )
            return
        }
        if (dispatch.kind == InventoryButtonTextAction.Kind.COMMAND) {
            connection.sendCommand(dispatch.payload!!)
            module.recordTextActionDispatch(
                placementId, dispatch.kind, raw?.length ?: 0,
                InventoryButtonsModule.TextActionDispatchOutcome.COMMAND_SUBMITTED,
            )
        } else {
            connection.sendChat(dispatch.payload!!)
            module.recordTextActionDispatch(
                placementId, dispatch.kind, raw?.length ?: 0,
                InventoryButtonsModule.TextActionDispatchOutcome.CHAT_SUBMITTED,
            )
        }
    }

    private fun macroName(id: Int): String {
        val macro: MacroDefinition? = MacrosModule.INSTANCE.macro(id)
        return if (macro == null) "Macro $id" else macro.name()
    }

    private fun screenWidth(): Int = Minecraft.getInstance().window.guiScaledWidth

    private fun screenHeight(): Int = Minecraft.getInstance().window.guiScaledHeight

    private fun trim(value: String?, maxWidth: Int): String = GuiTheme.fit(Minecraft.getInstance().font, value, maxWidth)

    private fun isJavaBlank(value: String): Boolean {
        for (character in value) {
            if (!java.lang.Character.isWhitespace(character)) return false
        }
        return true
    }

    private data class Panel(val x: Int, val y: Int, val width: Int, val height: Int, val rowHeight: Int) {
        fun bounds(): InventoryButtonLayout.Bounds = InventoryButtonLayout.Bounds(x, y, width, height)

        fun contains(pointX: Int, pointY: Int): Boolean =
            pointX >= x && pointX < x + width && pointY >= y && pointY < y + height
    }

    private data class IconTexture(val id: Identifier, val width: Int, val height: Int)
}

