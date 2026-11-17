package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import org.lwjgl.glfw.GLFW
import geiler.addons.client.dungeon.terminal.TerminalAim
import geiler.addons.client.dungeon.terminal.ColorKey
import geiler.addons.client.dungeon.terminal.TermSlot
import geiler.addons.client.dungeon.terminal.TerminalConfig
import geiler.addons.client.dungeon.terminal.TerminalHandler
import geiler.addons.client.dungeon.terminal.TerminalType
import geiler.addons.client.gui.GuiTheme
import geiler.addons.client.termsim.TermSimScreen
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Odin's Terminal Solver for floor 7 terminals with its custom GUI: the solved slots are drawn as a compact grid,
 * clicks go straight to the server, with click prediction, first-click protection and terminal sounds.
 * Settings, defaults (the user's Odin config), solving rules and click behavior follow Odin 0.3.6 (BSD 3-Clause);
 * the code is written independently. Only the custom GUI render type exists here.
 */
class TerminalSolverModule private constructor(private val s: Settings) : Module(
    "Terminal Solver",
    "Renders solution for terminals in floor 7.",
    Category.F7,
    false,
    s.termSize, s.roundness, s.slotGap,
    s.clickPrediction, s.resolveTimeout, s.stopMelody, s.melodySize, s.rubixMode, s.rubixReplaceClicks,
    s.aimAssist, s.aimRange, s.melodyBlockWrong, s.melodyAutoCursor,
    s.firstClickProt, s.serverLag, s.lagTicks,
    s.background, s.panes, s.rubixText, s.rubix1, s.rubix2, s.rubixNeg1, s.rubixNeg2,
    s.numbersText, s.numbers1, s.numbers2, s.numbers3, s.numbers4, s.startsWith, s.select,
    s.melodyColumn, s.melodyPointer, s.melodyBackground, s.debug,
    s.clickSounds, s.clickSound, s.clickPitch, s.clickVolume, s.playClick,
    s.completeSounds, s.completeSound, s.completePitch, s.completeVolume, s.playComplete,
) {
    private val config = object : TerminalConfig {
        override val clickPrediction get() = s.clickPrediction.value()
        override val rubixLeftClicksOnly get() = s.rubixMode.value() == "LEFT_CLICKS_ONLY"
        override val rubixReplaceClicks get() = s.rubixReplaceClicks.value()
        override val melodyBlockWrongClicks get() = s.melodyBlockWrong.value()
        override val firstClickProtectionMillis get() = if (simProtectionOff()) 0 else s.firstClickProt.intValue()
        override val protectWithServerTicks get() = !simProtectionOff() && s.serverLag.value()
        override val protectionTicks get() = s.lagTicks.intValue()
    }

    /** Odin's Terminal Simulator "First Click Prot" switch: no protection while a simulator is open. */
    private fun simProtectionOff() = currentScreen is TermSimScreen && TerminalSimulatorModule.INSTANCE.disableFirstClickProtection()

    /** True while the solver's own click sound replaces the simulator's default one. */
    fun clickSoundsActive(): Boolean = isEnabled() && s.clickSounds.value()

    private var current: TerminalHandler? = null
    private var currentScreen: AbstractContainerScreen<*>? = null
    private var lastOpened: TerminalHandler? = null
    private var lastServerTicks = -1L
    private var lastClickSoundMillis = 0L
    private var clicks = 0
    private var blockedClicks = 0
    private var hovered: Int? = null
    private var cursorPending = false
    private var grid: Grid? = null
    private val diagnostics = ModuleDiagnostics(Category.F7, { name() }, 10, 100L, 320)

    init {
        group(
            SettingGroup("Display", s.termSize, s.roundness, s.slotGap),
            SettingGroup(
                "Solver Functionality", s.clickPrediction, s.resolveTimeout, s.stopMelody, s.melodySize, s.rubixMode,
                s.rubixReplaceClicks, s.aimAssist, s.aimRange, s.melodyBlockWrong, s.melodyAutoCursor,
            ),
            SettingGroup("Click Protection", s.firstClickProt, s.serverLag, s.lagTicks),
            SettingGroup.folded(
                "Colors", s.background, s.panes, s.rubixText, s.rubix1, s.rubix2, s.rubixNeg1, s.rubixNeg2,
                s.numbersText, s.numbers1, s.numbers2, s.numbers3, s.numbers4, s.startsWith, s.select,
                s.melodyColumn, s.melodyPointer, s.melodyBackground, s.debug,
            ),
            SettingGroup.switched("Click Sounds", s.clickSounds, s.clickSound, s.clickPitch, s.clickVolume, s.playClick),
            SettingGroup.switched(
                "Completion Sounds", s.completeSounds, s.completeSound, s.completePitch, s.completeVolume, s.playComplete,
            ),
        )
    }

    override fun isSettingVisible(setting: Setting): Boolean = when (setting) {
        s.resolveTimeout -> s.clickPrediction.value()
        s.melodySize -> !s.stopMelody.value()
        s.aimRange -> s.aimAssist.value()
        s.melodyAutoCursor -> !s.stopMelody.value()
        s.lagTicks -> s.serverLag.value()
        s.melodyColumn, s.melodyPointer, s.melodyBackground -> !s.stopMelody.value()
        else -> true
    }

    override fun onEnable() {
        clear()
        diagnostics.enable(
            "size=${s.termSize.value()} round=${s.roundness.value()} gap=${s.slotGap.intValue()} " +
                "prediction=${s.clickPrediction.value()} timeout=${s.resolveTimeout.intValue()} " +
                "rubix=${s.rubixMode.value()} replaceClicks=${s.rubixReplaceClicks.value()} " +
                "aim=${s.aimAssist.value()} aimRange=${s.aimRange.value()} melodyBlock=${s.melodyBlockWrong.value()} " +
                "melodyCursor=${s.melodyAutoCursor.value()} protMs=${s.firstClickProt.intValue()} lag=${s.serverLag.value()} " +
                "clickSounds=${s.clickSounds.value()} completeSounds=${s.completeSounds.value()}",
        )
    }

    override fun onDisable() {
        clear()
        diagnostics.disable()
    }

    private fun clear() {
        current = null
        currentScreen = null
        grid = null
        hovered = null
        lastServerTicks = -1L
    }

    // ----- terminal lifecycle ---------------------------------------------------------------------------------

    fun onScreenOpened(screen: AbstractContainerScreen<*>) {
        if (!isEnabled()) return
        if (screen === currentScreen && current != null) return
        val handler = TerminalHandler.open(screen.title.string, config) ?: return
        current = handler
        currentScreen = screen
        lastOpened = handler
        cursorPending = true
        clicks = 0
        blockedClicks = 0
        diagnostics.event("OPEN type=${handler.type} title=${screen.title.string.take(40)} floor7=${floor7()}")
    }

    fun onScreenClosed(screen: AbstractContainerScreen<*>) {
        if (screen !== currentScreen) return
        diagnostics.event("CLOSE type=${current?.type} clicks=$clicks blocked=$blockedClicks")
        current = null
        currentScreen = null
        grid = null
        hovered = null
    }

    private fun floor7() = DungeonContextTracker.inDungeon() && DungeonContextTracker.currentFloor()?.number() == 7

    /** A server slot update for [menu]; recomputes the solution when it belongs to the open terminal. */
    fun onSlotSet(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
        val handler = current ?: return
        if (!isEnabled() || menu !== currentScreen?.menu) return
        handler.updateSlot(slot, BuiltInRegistries.ITEM.getKey(stack.item).path == TermSlot.BLACK_PANE, termSlots(menu, handler.type))
        diagnostics.event("SLOT slot=$slot solution=${handler.solution.take(12)}")
    }

    /** The whole container was replaced; reload the solution as a slot-0 update does. */
    fun onContentsSet(menu: AbstractContainerMenu) {
        val handler = current ?: return
        if (!isEnabled() || menu !== currentScreen?.menu) return
        handler.updateSlot(0, false, termSlots(menu, handler.type))
        diagnostics.event("CONTENTS solution=${handler.solution.take(12)}")
    }

    private fun termSlots(menu: AbstractContainerMenu, type: TerminalType): List<TermSlot> {
        val count = minOf(type.windowSize, menu.slots.size)
        return List(count) { i ->
            val stack = menu.slots[i].item
            if (stack.isEmpty) TermSlot.EMPTY
            else TermSlot(
                BuiltInRegistries.ITEM.getKey(stack.item).path, stack.count, stack.hoverName.string,
                stack.hasFoil(), stack.item in glintOverrides,
            )
        }
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        val handler = current
        val now = DungeonOdinAssistModule.INSTANCE.serverTicks()
        val delta = if (lastServerTicks < 0L || now < lastServerTicks) 0L else minOf(now - lastServerTicks, 40L)
        lastServerTicks = now
        if (handler != null) {
            handler.ticksOpened += delta.toInt()
            if (handler.clickedSlots.isNotEmpty() &&
                System.currentTimeMillis() - handler.lastClickTime >= s.resolveTimeout.intValue()
            ) {
                handler.clickedSlots.clear()
                currentScreen?.menu?.let { handler.updateSlot(0, false, termSlots(it, handler.type)) }
                diagnostics.event("RESOLVE_TIMEOUT reloaded solution=${handler.solution.take(12)}")
            }
        }
        diagnostics.status {
            "terminal=${handler?.type} solution=${handler?.solution?.size} clicked=${handler?.clickedSlots?.size} " +
                "ticksOpened=${handler?.ticksOpened} clicks=$clicks blocked=$blockedClicks suppressedEvents=$it"
        }
    }

    // ----- clicking --------------------------------------------------------------------------------------------

    /** A click on [slot] with mouse [button]: sends it when the solver allows it. Returns true when consumed. */
    private fun clickSlot(slot: Int, button: Int, source: String): Boolean {
        val handler = current ?: return false
        val click = handler.click(slot, button, s.clickPrediction.value())
        if (click == null) {
            blockedClicks++
            diagnostics.event("CLICK_BLOCKED source=$source slot=$slot button=$button protected=${handler.shouldProtect()}")
            return true
        }
        clicks++
        val mc = Minecraft.getInstance()
        val player = mc.player
        val gameMode = mc.gameMode
        val simulator = currentScreen as? TermSimScreen
        if (simulator != null) {
            // A simulator is a local chest: the click stays on this client and no packet is built.
            simulator.clickIndex(click.slot, click.button)
        } else if (player != null && gameMode != null) {
            gameMode.handleContainerInput(
                player.containerMenu.containerId, click.slot, click.button,
                if (click.button == 2) ContainerInput.CLONE else ContainerInput.PICKUP, player,
            )
        }
        diagnostics.event(
            "CLICK source=$source slot=${click.slot} pressed=$button sent=${click.button} left=${handler.solution.size}",
        )
        onTerminalClick(handler, click.slot)
        return true
    }

    /** The vanilla slot-click path: terminals handle every slot click themselves. */
    fun onSlotClicked(screen: AbstractContainerScreen<*>, slotId: Int, button: Int): Boolean {
        if (!isEnabled() || current == null || screen !== currentScreen) return false
        clickSlot(slotId, button, "slot")
        return true
    }

    // ----- custom GUI ------------------------------------------------------------------------------------------

    private fun renderMelody() = !(s.stopMelody.value() && current?.type == TerminalType.MELODY)

    /** True while the custom GUI replaces [screen]. */
    fun ownsScreen(screen: AbstractContainerScreen<*>?): Boolean =
        isEnabled() && screen != null && screen === currentScreen && current != null && renderMelody()

    private class Box(val slot: Int, val x: Int, val y: Int, val size: Int)

    private class Grid(val originX: Float, val originY: Float, val width: Int, val height: Int, val scale: Float, val boxes: List<Box>) {
        fun toBase(screenX: Double, screenY: Double): Pair<Float, Float> =
            Pair((screenX.toFloat() - originX) / scale, (screenY.toFloat() - originY) / scale)
    }

    private fun shape(type: TerminalType): IntArray = when (type) {
        TerminalType.PANES -> intArrayOf(3, 5, 1, 2)
        TerminalType.RUBIX -> intArrayOf(3, 3, 1, 3)
        TerminalType.NUMBERS -> intArrayOf(2, 5, 1, 2)
        TerminalType.STARTS_WITH -> intArrayOf(3, 7, 1, 1)
        TerminalType.SELECT -> intArrayOf(4, 7, 1, 1)
        TerminalType.MELODY -> intArrayOf(5, 7, 0, 1)
    }

    private fun buildGrid(screen: AbstractContainerScreen<*>, type: TerminalType): Grid {
        val (rows, cols, startRow, startCol) = shape(type).toList()
        val gap = s.slotGap.intValue()
        val scale = if (type == TerminalType.MELODY) s.melodySize.value() else s.termSize.value()
        val w = cols * SLOT_SIZE + (cols - 1) * gap
        val h = rows * SLOT_SIZE + (rows - 1) * gap
        val boxes = ArrayList<Box>(rows * cols)
        for (row in 0 until rows) for (col in 0 until cols) {
            boxes.add(Box((startRow + row) * 9 + (startCol + col), col * (SLOT_SIZE + gap), row * (SLOT_SIZE + gap), SLOT_SIZE))
        }
        return Grid((screen.width - w * scale) / 2f, (screen.height - h * scale) / 2f, w, h, scale, boxes)
    }

    private fun argb(key: ColorKey): Int = when (key) {
        ColorKey.PANES -> s.panes.argb()
        ColorKey.RUBIX_1 -> s.rubix1.argb()
        ColorKey.RUBIX_2 -> s.rubix2.argb()
        ColorKey.RUBIX_NEG_1 -> s.rubixNeg1.argb()
        ColorKey.RUBIX_NEG_2 -> s.rubixNeg2.argb()
        ColorKey.NUMBERS_1 -> s.numbers1.argb()
        ColorKey.NUMBERS_2 -> s.numbers2.argb()
        ColorKey.NUMBERS_3 -> s.numbers3.argb()
        ColorKey.NUMBERS_4 -> s.numbers4.argb()
        ColorKey.NUMBERS_TRANSPARENT -> 0
        ColorKey.STARTS_WITH -> s.startsWith.argb()
        ColorKey.SELECT -> s.select.argb()
        ColorKey.MELODY_COLUMN -> s.melodyColumn.argb()
        ColorKey.MELODY_POINTER -> s.melodyPointer.argb()
        ColorKey.MELODY_BACKGROUND -> s.melodyBackground.argb()
    }

    /** What to draw in grid slot [slot], or null to leave it empty. */
    private fun resolve(handler: TerminalHandler, slot: Int): Pair<Int, String?>? {
        handler.visual(slot)?.let { return argb(it.color) to it.text }
        if (handler.type == TerminalType.MELODY) {
            val row = slot / 9
            val col = slot % 9
            if (!(row == 0 || row == 4 || col == 6)) return argb(ColorKey.MELODY_BACKGROUND) to null
        }
        return null
    }

    /** Draws the grid in place of the vanilla screen; returns true when it did, so the caller cancels vanilla. */
    fun renderCustomScreen(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
        if (!ownsScreen(screen)) return false
        val handler = current ?: return false
        val mc = Minecraft.getInstance()
        val built = buildGrid(screen, handler.type)
        grid = built
        if (cursorPending) {
            cursorPending = false
            if (handler.type == TerminalType.MELODY && s.melodyAutoCursor.value()) moveCursorToMelodyButtons(built)
        }
        val (baseX, baseY) = built.toBase(mouseX.toDouble(), mouseY.toDouble())
        hovered = null
        val pose = graphics.pose()

        // Shapes are laid out in physical pixels: GuiTheme antialiases one pixel of coverage, which only reads as
        // smooth when that pixel is a real screen pixel and not a scaled-up GUI pixel.
        val guiScale = mc.window.guiScale.toFloat()
        val px = built.scale * guiScale
        fun phys(v: Float): Int = Math.round(v * px)
        val radius = phys(s.roundness.value())
        val labels = ArrayList<Pair<Box, String>>()
        pose.pushMatrix()
        pose.translate(Math.round(built.originX * guiScale) / guiScale, Math.round(built.originY * guiScale) / guiScale)
        pose.scale(1f / guiScale, 1f / guiScale)
        val left = phys(-PADDING.toFloat())
        val top = phys(-PADDING.toFloat())
        GuiTheme.roundedRect(
            graphics, left, top, phys((built.width + PADDING).toFloat()) - left, phys((built.height + PADDING).toFloat()) - top,
            radius, s.background.argb(),
        )
        for (box in built.boxes) {
            val (color, text) = resolve(handler, box.slot) ?: continue
            if (baseX >= box.x && baseY >= box.y && baseX < box.x + box.size && baseY < box.y + box.size) hovered = box.slot
            val x0 = phys(box.x.toFloat())
            val y0 = phys(box.y.toFloat())
            GuiTheme.roundedRect(
                graphics, x0, y0, phys((box.x + box.size).toFloat()) - x0, phys((box.y + box.size).toFloat()) - y0, radius, color,
            )
            if (text != null) labels.add(box to text)
        }
        pose.popMatrix()

        val textColor = if (handler.type == TerminalType.NUMBERS) s.numbersText.argb() else s.rubixText.argb()
        pose.pushMatrix()
        pose.translate(built.originX, built.originY)
        pose.scale(built.scale, built.scale)
        for ((box, text) in labels) {
            graphics.text(
                mc.font, text, box.x + (box.size - mc.font.width(text)) / 2,
                box.y + (box.size - mc.font.lineHeight) / 2 + 1, textColor,
            )
        }
        pose.popMatrix()
        if (s.debug.value()) renderDebug(graphics, handler, screen)
        return true
    }

    /** Puts the cursor on the middle melody button (slot 25) once, so the long trip to the right is saved. */
    private fun moveCursorToMelodyButtons(built: Grid) {
        val box = built.boxes.firstOrNull { it.slot == MELODY_MIDDLE_BUTTON } ?: return
        val window = Minecraft.getInstance().window
        if (window.guiScaledWidth <= 0 || window.guiScaledHeight <= 0) return
        val guiX = built.originX + (box.x + box.size / 2f) * built.scale
        val guiY = built.originY + (box.y + box.size / 2f) * built.scale
        GLFW.glfwSetCursorPos(
            window.handle(), (guiX * window.screenWidth / window.guiScaledWidth).toDouble(),
            (guiY * window.screenHeight / window.guiScaledHeight).toDouble(),
        )
        diagnostics.event("AUTO_CURSOR slot=$MELODY_MIDDLE_BUTTON gui=${guiX.toInt()},${guiY.toInt()}")
    }

    private fun renderDebug(graphics: GuiGraphicsExtractor, handler: TerminalHandler, screen: AbstractContainerScreen<*>) {
        val font = Minecraft.getInstance().font
        val lines = listOf(
            "Type: ${handler.type}",
            "Window Name: ${screen.title.string}",
            "Container ID: ${screen.menu.containerId}",
            "Time Open: ${System.currentTimeMillis() - handler.timeOpened}ms",
            "Ticks Open: ${handler.ticksOpened}",
            "Solution: ${handler.solution.joinToString(", ")}",
            "Clicked Slots: ${handler.clickedSlots}",
        )
        lines.forEachIndexed { i, line -> graphics.text(font, line, 4, 4 + i * 10, 0xFFFFFFFF.toInt()) }
    }

    /** A mouse click while the custom GUI is up: presses the slot under the cursor. */
    fun handleMouseClick(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean {
        if (!ownsScreen(screen)) return false
        val built = grid ?: return true
        val (baseX, baseY) = built.toBase(event.x(), event.y())
        val box = built.boxes.firstOrNull { baseX >= it.x && baseY >= it.y && baseX < it.x + it.size && baseY < it.y + it.size }
        val handler = current
        val aimed = if (s.aimAssist.value() && handler != null) {
            TerminalAim.pick(
                baseX.toDouble(), baseY.toDouble(), built.boxes.map { TerminalAim.Box(it.slot, it.x, it.y, it.size) },
                { handler.canClick(it, event.button()) }, s.aimRange.value().toDouble(),
            )
        } else null
        val slot = aimed ?: box?.slot
        if (aimed != null && aimed != box?.slot) diagnostics.event("AIM_ASSIST from=${box?.slot} to=$aimed")
        hovered = slot
        slot?.let { clickSlot(it, event.button(), "mouse") }
        return true
    }

    /** The drop and hotbar keys press the hovered slot: left click, or right click while holding control. */
    fun handleKey(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean {
        if (!ownsScreen(screen)) return false
        val options = Minecraft.getInstance().options
        if (!options.keyDrop.matches(event) && options.keyHotbarSlots.none { it.matches(event) }) return false
        hovered?.let { clickSlot(it, if (event.hasControlDown()) 1 else 0, "key") }
        return true
    }

    // ----- sounds ----------------------------------------------------------------------------------------------

    private fun shouldReplaceSounds() = current != null && s.clickSounds.value()

    private fun onTerminalClick(handler: TerminalHandler, slot: Int) {
        if (!isEnabled()) return
        val done = handler.solution.isEmpty() || (handler.type == TerminalType.MELODY && slot == 43)
        if (done && s.completeSounds.value()) {
            playCompletion()
        } else if (s.clickSounds.value()) {
            val now = System.currentTimeMillis()
            if (now - lastClickSoundMillis <= 2) return
            ModuleSounds.play(s.clickSound.value(), s.clickVolume.value(), s.clickPitch.value())
            lastClickSoundMillis = now
        }
    }

    private fun playCompletion() {
        ModuleSounds.play(s.completeSound.value(), s.completeVolume.value(), s.completePitch.value())
    }

    /** The terminal-complete pling is replaced by the click sound while a terminal is open. */
    fun shouldCancelPling(packet: ClientboundSoundPacket): Boolean =
        isEnabled() && shouldReplaceSounds() && packet.volume == 8f && packet.pitch == 4.047619f &&
            packet.sound.value().location().path == "block.note_block.pling"

    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        if (!DungeonContextTracker.inDungeon() || !shouldReplaceSounds()) return
        if (GATE.matches(plain) || CORE.matches(plain)) ModuleSounds.play("minecraft:block.note_block.pling", 1f, 1f)
    }

    class Settings {
        val termSize = NumberSetting("Term Size", 1f, 3f, 1.8f)
        val roundness = NumberSetting("Roundness", 0f, 15f, 4f)
        val slotGap = NumberSetting("Slot gap", 0f, 8f, 0f, true)
        val clickPrediction = BooleanSetting("Client Prediction", true)
        val resolveTimeout = NumberSetting("Resolve timeout", 300f, 1200f, 300f, true)
        val stopMelody = BooleanSetting("Stop Melody Solver", false)
        val melodySize = NumberSetting("Melody Size", 1f, 3f, 1.5f)
        val rubixReplaceClicks = BooleanSetting("Rubix Replace Clicks", false)
        val aimAssist = BooleanSetting("Aim Assist", false)
        val aimRange = NumberSetting("Aim Assist Range", 1f, 12f, 6f)
        val melodyBlockWrong = BooleanSetting("Block Wrong Melody Clicks", false)
        val melodyAutoCursor = BooleanSetting("Melody Auto Cursor", false)
        val rubixMode = ChoiceSetting(
            "Rubix Mode", "LEFT_CLICKS_ONLY",
            mapOf("FEWEST_CLICKS" to "Fewest Clicks", "LEFT_CLICKS_ONLY" to "Left Clicks Only"),
            "FEWEST_CLICKS", "LEFT_CLICKS_ONLY",
        )
        val firstClickProt = NumberSetting("First Click Prot", 0f, 600f, 500f, true)
        val serverLag = BooleanSetting("Server Lag", false)
        val lagTicks = NumberSetting("Lag Protection Ticks", 7f, 16f, 8f, true)
        val background = ColorSetting("Background", 0x1A, 0x1A, 0x1A, 255)
        val panes = ColorSetting("Panes", 255, 255, 255, 255)
        val rubixText = ColorSetting("Rubix Text", 255, 255, 255, 255)
        val rubix1 = ColorSetting("Rubix 1", 255, 255, 255, 255)
        val rubix2 = ColorSetting("Rubix 2", 255, 255, 255, 255)
        val rubixNeg1 = ColorSetting("Rubix -1", 255, 255, 255, 255)
        val rubixNeg2 = ColorSetting("Rubix -2", 255, 255, 255, 255)
        val numbersText = ColorSetting("Numbers Text", 255, 255, 255, 255)
        val numbers1 = ColorSetting("Numbers 1", 0x55, 0xFF, 0x55, 255)
        val numbers2 = ColorSetting("Numbers 2", 0x2A, 0x80, 0x2A, 255)
        val numbers3 = ColorSetting("Numbers 3", 0x15, 0x40, 0x15, 255)
        val numbers4 = ColorSetting("Numbers 4", 0x0B, 0x20, 0x0B, 255)
        val startsWith = ColorSetting("Starts With", 255, 255, 255, 255)
        val select = ColorSetting("Select", 255, 255, 255, 255)
        val melodyColumn = ColorSetting("Melody Column", 0xAA, 0x00, 0xAA, 255)
        val melodyPointer = ColorSetting("Melody Pointer", 0x55, 0xFF, 0x55, 255)
        val melodyBackground = ColorSetting("Melody Background", 0x26, 0x26, 0x26, 255)
        val debug = BooleanSetting("Debug", false)
        val clickSounds = BooleanSetting("Click Sounds", true)
        val clickSound = TextSetting("Click Sound", "entity.item.pickup", 64)
        val clickPitch = NumberSetting("Click Sound Pitch", 0.1f, 2f, 1f)
        val clickVolume = NumberSetting("Click Sound Volume", 0.1f, 1f, 1f)
        val playClick = ModuleAction("Play Click Sound", "Plays the selected sound.", Runnable {
            ModuleSounds.play(INSTANCE.s.clickSound.value(), INSTANCE.s.clickVolume.value(), INSTANCE.s.clickPitch.value())
        })
        val completeSounds = BooleanSetting("Complete Sounds", false)
        val completeSound = TextSetting("Completion Sound", "entity.experience_orb.pickup", 64)
        val completePitch = NumberSetting("Completion Sound Pitch", 0.1f, 2f, 1f)
        val completeVolume = NumberSetting("Completion Sound Volume", 0.1f, 1f, 1f)
        val playComplete = ModuleAction("Play Completion Sound", "Plays the selected sound.", Runnable {
            ModuleSounds.play(INSTANCE.s.completeSound.value(), INSTANCE.s.completeVolume.value(), INSTANCE.s.completePitch.value())
        })
    }

    companion object {
        private const val SLOT_SIZE = 24
        private const val PADDING = 2
        private const val MELODY_MIDDLE_BUTTON = 25
        private val GATE = Regex("^The gate has been destroyed!$")
        private val CORE = Regex("^The Core entrance is opening!$")

        /** Items that always glint, which the starts-with terminal must not skip. */
        private val glintOverrides: Set<net.minecraft.world.item.Item> by lazy {
            val set = HashSet<net.minecraft.world.item.Item>()
            for (item in BuiltInRegistries.ITEM) {
                if (item.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) set.add(item)
            }
            set.add(Items.GOLDEN_APPLE)
            set
        }

        @JvmField
        val INSTANCE = TerminalSolverModule(Settings())
    }
}
