package geiler.addons.client.gui

import com.mojang.blaze3d.platform.NativeImage
import geiler.addons.client.config.ModConfig
import geiler.addons.client.cosmetics.CosmeticIcons
import geiler.addons.client.cosmetics.CosmeticPiece
import geiler.addons.client.cosmetics.CosmeticSlot
import geiler.addons.client.cosmetics.CosmeticStyle
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.impl.CosmeticsModule
import geiler.addons.client.module.impl.VisualModule
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.util.Mth
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.joml.Vector3f
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min

/**
 * The Cosmetics menu (Click GUI top-bar wing button): your player on the left, a card per cosmetic on the
 * right. Clicking a card wears or removes it (a tick marks what you wear); its gear opens that slot's
 * settings. Pictures are drawn by [geiler.addons.client.cosmetics.CosmeticIconRenderer] in your colors.
 */
class CosmeticsScreen(private val parent: Screen?) : Screen(Component.literal("Cosmetics")) {
    private val module = CosmeticsModule.INSTANCE
    private var settingsScroll = 0

    /** The card list keeps its scroll across a slot's settings and across reopening the menu. */
    private var scroll: Int
        get() = if (editing == null) cardScroll else settingsScroll
        set(value) {
            if (editing == null) cardScroll = value else settingsScroll = value
        }
    private var contentHeight = 0
    private var editing: CosmeticSlot? = null
    private var dragging: NumberSetting? = null
    private var dragTrack: Rect? = null

    /** The scrollbar track and thumb as drawn last frame (null when everything fits); [barGrab] is where the thumb was taken. */
    private var barTrack: Rect? = null
    private var barThumb: Rect? = null
    private var barGrab: Int? = null

    /** Preview turn (degrees) set by dragging the player; [turning] while the drag is held. */
    private var turning = false
    private val icons = HashMap<CosmeticStyle, Icon>()

    private class Icon(val id: Identifier, val texture: DynamicTexture, var piece: CosmeticPiece?)

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun contains(px: Double, py: Double): Boolean = px >= x && px < x + w && py >= y && py < y + h
        val right get() = x + w
        val bottom get() = y + h
    }

    /** Hit targets collected while drawing, so clicks match exactly what is on screen. */
    private val targets = ArrayList<Pair<Rect, () -> Unit>>()
    private val sliders = ArrayList<Pair<Rect, NumberSetting>>()

    override fun isPauseScreen(): Boolean = false

    // ---- layout ---------------------------------------------------------------------------------

    private fun panel(): Rect {
        val w = min(width - 24, 600)
        val h = min(height - 24, 360)
        return Rect((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun previewBox(panel: Rect): Rect = Rect(panel.x + 10, panel.y + 30, (panel.w * 0.3f).toInt(), panel.h - 40)

    private fun contentBox(panel: Rect): Rect {
        val preview = previewBox(panel)
        return Rect(preview.right + 10, panel.y + 30, panel.right - preview.right - 20, panel.h - 40)
    }

    // ---- drawing --------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        VisualModule.INSTANCE.refreshTheme()
        targets.clear()
        sliders.clear()
        graphics.fill(0, 0, width, height, GuiTheme.DIALOG_SHADE)
        val panel = panel()
        GuiTheme.roundedRectBordered(graphics, panel.x, panel.y, panel.w, panel.h, GuiTheme.RADIUS,
            GuiTheme.PANEL_TOP, GuiTheme.PANEL_BOTTOM, GuiTheme.BORDER)
        val back = Rect(panel.x + 10, panel.y + 8, 14 + font.width("Cosmetics"), 14)
        graphics.text(font, "‹  Cosmetics", back.x, back.y + 3,
            if (back.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.TEXT_PRIMARY else GuiTheme.TEXT_SECONDARY, false)
        targets.add(back to { if (editing != null) editing = null else onClose() })
        val note = "Only you see what you pick here"
        graphics.text(font, note, panel.right - 10 - font.width(note), back.y + 3, GuiTheme.TEXT_MUTED, false)

        drawPreview(graphics, previewBox(panel), mouseX, mouseY)
        val content = contentBox(panel)
        graphics.enableScissor(content.x, content.y, content.right, content.bottom)
        val editingSlot = editing
        contentHeight = if (editingSlot == null) drawCards(graphics, content, mouseX, mouseY)
        else drawSettings(graphics, content, editingSlot, mouseX, mouseY)
        graphics.disableScissor()
        scroll = scroll.coerceIn(0, max(0, contentHeight - content.h))
        barTrack = null
        barThumb = null
        if (contentHeight > content.h) {
            val barH = max(16, content.h * content.h / contentHeight)
            val barY = content.y + (content.h - barH) * scroll / max(1, contentHeight - content.h)
            // A wider hit area than the drawn bar, so it is easy to grab and drag.
            val track = Rect(content.right + 1, content.y, 8, content.h)
            val thumb = Rect(track.x, barY, track.w, barH)
            val active = barGrab != null || thumb.contains(mouseX.toDouble(), mouseY.toDouble())
            graphics.fill(content.right + 3, barY, content.right + 7, barY + barH, if (active) GuiTheme.TEXT_SECONDARY else GuiTheme.SCROLLBAR)
            barTrack = track
            barThumb = thumb
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }

    private fun drawPreview(graphics: GuiGraphicsExtractor, box: Rect, mouseX: Int, mouseY: Int) {
        GuiTheme.roundedRect(graphics, box.x, box.y, box.w, box.h, GuiTheme.RADIUS_SMALL, GuiTheme.CARD_BG)
        val player = minecraft.player
        val toggleRow = Rect(box.x + 8, box.bottom - 22, box.w - 16, 14)
        if (player == null) {
            graphics.centeredText(font, "Join a world to see", box.x + box.w / 2, box.y + box.h / 2 - 10, GuiTheme.TEXT_MUTED)
            graphics.centeredText(font, "your player here", box.x + box.w / 2, box.y + box.h / 2, GuiTheme.TEXT_MUTED)
        } else {
            val area = Rect(box.x + 4, box.y + 6, box.w - 8, toggleRow.y - box.y - 10)
            val scale = (min(area.h / 2.4f, area.w / 1.2f)).toInt().coerceAtLeast(10)
            drawPlayer(graphics, area, scale, player)
            graphics.centeredText(font, "Drag to turn", box.x + box.w / 2, toggleRow.y - 12, GuiTheme.TEXT_MUTED)
        }
        val hosted = module.showHostedSetting()
        graphics.text(font, GuiTheme.fit(font, "Show others' cosmetics", toggleRow.w - 26), toggleRow.x, toggleRow.y + 3,
            GuiTheme.TEXT_SECONDARY, false)
        GuiTheme.toggleSwitch(graphics, toggleRow.right - 20, toggleRow.y + 1, 20, 12, hosted.value())
        targets.add(toggleRow to {
            hosted.setValue(!hosted.value())
            module.note("UI SHOW_HOSTED ${hosted.value()}")
        })
    }

    /**
     * The player like [InventoryScreen.extractEntityInInventoryFollowsMouse], but turned by dragging ([previewYaw],
     * [previewPitch]) instead of following the mouse; head and body face the same way.
     */
    private fun drawPlayer(graphics: GuiGraphicsExtractor, area: Rect, scale: Int, player: LivingEntity) {
        val state = minecraft.entityRenderDispatcher.getRenderer(player).createRenderState(player, 1f)
        state.shadowPieces.clear()
        state.outlineColor = 0
        if (state is LivingEntityRenderState) {
            state.bodyRot = 180f + previewYaw
            state.yRot = 0f
            state.xRot = 0f
            state.boundingBoxWidth /= state.scale
            state.boundingBoxHeight /= state.scale
            state.scale = 1f
        }
        val camera = Quaternionf().rotateX(previewPitch * Mth.DEG_TO_RAD)
        val rotation = Quaternionf().rotateZ(Mth.PI).mul(camera)
        val offset = Vector3f(0f, state.boundingBoxHeight / 2f + 0.0625f, 0f)
        graphics.entity(state, scale.toFloat(), offset, rotation, camera, area.x, area.y, area.right, area.bottom)
    }

    /** The card grid: a section per slot, then the body sliders. Returns the content height. */
    private fun drawCards(graphics: GuiGraphicsExtractor, box: Rect, mouseX: Int, mouseY: Int): Int {
        val columns = max(1, (box.w + CARD_GAP) / (CARD_W + CARD_GAP))
        var y = box.y - scroll
        for (slot in CosmeticSlot.entries) {
            graphics.text(font, slot.label, box.x, y + 2, GuiTheme.TEXT_PRIMARY, false)
            y += 14
            val styles = slot.styles()
            for ((index, style) in styles.withIndex()) {
                val card = Rect(box.x + (index % columns) * (CARD_W + CARD_GAP), y + (index / columns) * (CARD_H + CARD_GAP), CARD_W, CARD_H)
                drawCard(graphics, card, style, box, mouseX, mouseY)
            }
            y += ((styles.size + columns - 1) / columns) * (CARD_H + CARD_GAP) + 6
        }
        graphics.text(font, "Body", box.x, y + 2, GuiTheme.TEXT_PRIMARY, false)
        y += 16
        y = slider(graphics, box, y, module.chestSetting(), "Chest", mouseX, mouseY)
        y = slider(graphics, box, y, module.waistSetting(), "Waist", mouseX, mouseY)
        return y + scroll - box.y + 4
    }

    private fun drawCard(graphics: GuiGraphicsExtractor, card: Rect, style: CosmeticStyle, clip: Rect, mouseX: Int, mouseY: Int) {
        if (card.bottom < clip.y || card.y > clip.bottom) return
        // Worn includes a piece the hosted profile gives you, so you can see it and take it off.
        val worn = module.worn(style.slot) == style
        val inClip = mouseY >= clip.y && mouseY < clip.bottom
        val hovered = inClip && card.contains(mouseX.toDouble(), mouseY.toDouble())
        GuiTheme.roundedRectBordered(graphics, card.x, card.y, card.w, card.h, GuiTheme.RADIUS_SMALL,
            if (hovered) GuiTheme.CARD_BG_HOVER else if (worn) GuiTheme.CARD_BG_ENABLED else GuiTheme.CARD_BG,
            if (hovered) GuiTheme.CARD_BG_HOVER else if (worn) GuiTheme.CARD_BG_ENABLED else GuiTheme.CARD_BG,
            if (worn) GuiTheme.CARD_BORDER_ENABLED else GuiTheme.CARD_BORDER)
        blitIcon(graphics, icon(style), card.x + (card.w - ICON_SHOWN) / 2, card.y + 4, ICON_SHOWN)
        graphics.centeredText(font, GuiTheme.fit(font, style.label, card.w - 6), card.x + card.w / 2, card.y + ICON_SHOWN + 7,
            GuiTheme.TEXT_PRIMARY)
        // The tick below the name marks what you wear.
        val tick = if (worn) "✔ Worn" else "Wear"
        graphics.centeredText(font, tick, card.x + card.w / 2, card.y + ICON_SHOWN + 19,
            if (worn) GuiTheme.SLIDER_FILL else GuiTheme.TEXT_MUTED)
        val gear = Rect(card.right - 15, card.y + 3, 12, 12)
        val gearHovered = inClip && gear.contains(mouseX.toDouble(), mouseY.toDouble())
        if (gearHovered) GuiTheme.roundedRect(graphics, gear.x - 1, gear.y - 1, gear.w + 2, gear.h + 2, 3, GuiTheme.BUTTON_HOVER)
        blitTinted(graphics, GEAR, gear.x, gear.y, gear.w, if (gearHovered) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_MUTED)
        if (!inClip) return
        // Gear first, so its click is not taken by the card.
        targets.add(Rect(gear.x, max(gear.y, clip.y), gear.w, gear.h) to {
            editing = style.slot
            scroll = 0
            module.note("UI SETTINGS ${style.slot.id}")
        })
        targets.add(Rect(card.x, max(card.y, clip.y), card.w, min(card.bottom, clip.bottom) - max(card.y, clip.y)) to {
            module.toggle(style)
            click()
        })
    }

    /** One slot's settings: style chips, colors, size, offsets and speed. */
    private fun drawSettings(graphics: GuiGraphicsExtractor, box: Rect, slot: CosmeticSlot, mouseX: Int, mouseY: Int): Int {
        val settings = module.slot(slot)
        var y = box.y - scroll
        graphics.text(font, "${slot.label} settings", box.x, y + 2, GuiTheme.TEXT_PRIMARY, false)
        y += 16
        var x = box.x
        val hosted = module.hostedPiece(slot)
        val worn = module.worn(slot)
        // "Hosted" goes back to the piece the hosted profile gives you; "None" then switches it off.
        val chips = listOf<CosmeticStyle?>(null) + slot.styles()
        val labels = chips.map { it?.label ?: "None" }.toMutableList()
        if (hosted != null) labels.add("Hosted (${hosted.style.label})")
        for ((index, label) in labels.withIndex()) {
            val style = chips.getOrNull(index)
            val hostedChip = index >= chips.size
            val chip = Rect(x, y, font.width(label) + 12, 14)
            if (chip.right > box.right) {
                x = box.x
                y += 18
            }
            val placed = Rect(x, y, chip.w, chip.h)
            val selected = if (hostedChip) settings.style.value() == CosmeticsModule.NONE else worn == style &&
                !(style != null && hosted != null && settings.selected() == null)
            GuiTheme.roundedRect(graphics, placed.x, placed.y, placed.w, placed.h, 4,
                if (selected) GuiTheme.SLIDER_FILL else if (placed.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.CARD_BG_HOVER else GuiTheme.CARD_BG)
            graphics.text(font, label, placed.x + 6, placed.y + 3, if (selected) GuiTheme.TEXT_ON_ACCENT else GuiTheme.TEXT_PRIMARY, false)
            targets.add(placed to {
                when {
                    hostedChip -> settings.style.setValue(CosmeticsModule.NONE)
                    style == null -> module.takeOff(slot)
                    else -> settings.style.setValue(style.id)
                }
                module.note("UI STYLE ${slot.id}=${settings.style.value()}")
                click()
            })
            x += placed.w + 6
        }
        y += 22
        val row = Rect(box.x, y, box.w, 14)
        graphics.text(font, "Use Style Colors", row.x, row.y + 3, GuiTheme.TEXT_SECONDARY, false)
        GuiTheme.toggleSwitch(graphics, row.right - 22, row.y + 1, 20, 12, settings.styleColors.value())
        targets.add(row to { settings.styleColors.setValue(!settings.styleColors.value()) })
        y += 20
        if (!settings.styleColors.value()) {
            y = swatch(graphics, box, y, settings.color, "Color", mouseX, mouseY)
            y = swatch(graphics, box, y, settings.color2, "Second Color", mouseX, mouseY)
        }
        y = slider(graphics, box, y, settings.size, "Size", mouseX, mouseY)
        y = slider(graphics, box, y, settings.up, "Height", mouseX, mouseY)
        y = slider(graphics, box, y, settings.back, "Depth", mouseX, mouseY)
        y = slider(graphics, box, y, settings.speed, settings.speedLabel, mouseX, mouseY)
        return y + scroll - box.y + 4
    }

    private fun swatch(graphics: GuiGraphicsExtractor, box: Rect, y: Int, color: ColorSetting, label: String, mouseX: Int, mouseY: Int): Int {
        val row = Rect(box.x, y, box.w, 16)
        graphics.text(font, label, row.x, row.y + 4, GuiTheme.TEXT_SECONDARY, false)
        val chip = Rect(row.right - 40, row.y + 1, 38, 14)
        GuiTheme.roundedRectBordered(graphics, chip.x, chip.y, chip.w, chip.h, 3, color.opaqueArgb(), color.opaqueArgb(),
            if (chip.contains(mouseX.toDouble(), mouseY.toDouble())) GuiTheme.TEXT_PRIMARY else GuiTheme.BORDER)
        targets.add(row to {
            minecraft.gui.setScreen(GuiColorPickerScreen(this, label, color.opaqueArgb(), false) { argb ->
                color.set((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, 255)
                module.note("UI COLOR ${color.name()}")
            })
        })
        return y + 20
    }

    private fun slider(graphics: GuiGraphicsExtractor, box: Rect, y: Int, setting: NumberSetting, label: String, mouseX: Int, mouseY: Int): Int {
        graphics.text(font, label, box.x, y + 2, GuiTheme.TEXT_SECONDARY, false)
        val value = setting.display()
        graphics.text(font, value, box.right - font.width(value), y + 2, GuiTheme.TEXT_PRIMARY, false)
        val track = Rect(box.x, y + 14, box.w, 4)
        graphics.fill(track.x, track.y, track.right, track.bottom, GuiTheme.SLIDER_TRACK)
        val filled = track.x + (track.w * setting.fraction()).toInt()
        graphics.fill(track.x, track.y, filled, track.bottom, GuiTheme.SLIDER_FILL)
        graphics.fill(filled - 2, track.y - 3, filled + 2, track.bottom + 3,
            if (dragging === setting) GuiTheme.TEXT_PRIMARY else GuiTheme.SWITCH_KNOB)
        sliders.add(Rect(track.x, track.y - 5, track.w, track.h + 10) to setting)
        return y + 26
    }

    // ---- icons ----------------------------------------------------------------------------------

    /** The style's card picture, re-rendered only when its colors or shape settings change. */
    private fun icon(style: CosmeticStyle): Icon {
        val piece = module.slot(style.slot).piece(style)
        val existing = icons[style]
        if (existing != null && existing.piece == piece) return existing
        val icon = existing ?: run {
            val id = Identifier.fromNamespaceAndPath("geileraddons", "cosmetics/card_${style.id}")
            val texture = DynamicTexture({ "GeilerAddons cosmetic card ${style.id}" }, NativeImage(ICON_SIZE, ICON_SIZE, true))
            minecraft.textureManager.register(id, texture)
            Icon(id, texture, null).also { icons[style] = it }
        }
        val pixels = CosmeticIcons.pixels(piece, ICON_SIZE, 0.6f)
        val image = icon.texture.pixels
        for (y in 0 until ICON_SIZE) for (x in 0 until ICON_SIZE) image.setPixel(x, y, pixels[y * ICON_SIZE + x])
        icon.texture.upload()
        icon.piece = piece
        return icon
    }

    private fun blitIcon(graphics: GuiGraphicsExtractor, icon: Icon, x: Int, y: Int, size: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(size / ICON_SIZE.toFloat(), size / ICON_SIZE.toFloat())
        graphics.blit(RenderPipelines.GUI_TEXTURED, icon.id, 0, 0, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE)
        pose.popMatrix()
    }

    private fun blitTinted(graphics: GuiGraphicsExtractor, texture: Identifier, x: Int, y: Int, size: Int, color: Int) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(size / GEAR_SIZE.toFloat(), size / GEAR_SIZE.toFloat())
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, GEAR_SIZE, GEAR_SIZE, GEAR_SIZE, GEAR_SIZE, color)
        pose.popMatrix()
    }

    // ---- input ----------------------------------------------------------------------------------

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 0) {
            val track = barTrack
            val thumb = barThumb
            if (track != null && thumb != null && track.contains(event.x(), event.y())) {
                // Grab the thumb where it was clicked; a click on the track centers the thumb there.
                barGrab = if (thumb.contains(event.x(), event.y())) event.y().toInt() - thumb.y else thumb.h / 2
                dragBar(event.y())
                return true
            }
            val preview = previewBox(panel())
            if (minecraft.player != null && preview.contains(event.x(), event.y()) && event.y() < preview.bottom - 26) {
                turning = true
                return true
            }
            for ((track, setting) in sliders) {
                if (track.contains(event.x(), event.y()) && contentBox(panel()).contains(event.x(), event.y())) {
                    dragging = setting
                    dragTrack = track
                    drag(event.x())
                    return true
                }
            }
            for ((rect, action) in targets) {
                if (rect.contains(event.x(), event.y())) {
                    action()
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (dragging != null) {
            drag(event.x())
            return true
        }
        if (barGrab != null) {
            dragBar(event.y())
            return true
        }
        if (turning) {
            previewYaw = ((previewYaw - dragX.toFloat() * 1.5f) % 360f + 360f) % 360f
            previewPitch = (previewPitch - dragY.toFloat() * 0.6f).coerceIn(-30f, 30f)
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    /** Scrolls so the thumb's grabbed point sits under the mouse. */
    private fun dragBar(mouseY: Double) {
        val track = barTrack ?: return
        val thumb = barThumb ?: return
        val grab = barGrab ?: return
        val room = track.h - thumb.h
        if (room <= 0) return
        val fraction = ((mouseY - grab - track.y) / room).coerceIn(0.0, 1.0)
        scroll = (fraction * max(0, contentHeight - track.h)).toInt()
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (barGrab != null || turning) {
            barGrab = null
            turning = false
            return true
        }
        val setting = dragging
        if (setting != null) {
            module.note("UI SLIDER ${setting.name()}=${setting.display()}")
            dragging = null
            dragTrack = null
            return true
        }
        return super.mouseReleased(event)
    }

    private fun drag(mouseX: Double) {
        val track = dragTrack ?: return
        dragging?.setFraction(((mouseX - track.x) / track.w).toFloat().coerceIn(0f, 1f))
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (contentBox(panel()).contains(mouseX, mouseY)) {
            scroll = max(0, scroll - (scrollY * 18).toInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (editing != null) {
                editing = null
            } else {
                onClose()
            }
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun removed() {
        ModConfig.save()
        val textures = minecraft.textureManager
        for (icon in icons.values) textures.release(icon.id)
        icons.clear()
        super.removed()
    }

    private fun click() {
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
    }

    private companion object {
        /** Remembered for the game session, like the preview's turn. */
        var cardScroll = 0
        var previewYaw = 0f
        var previewPitch = 0f

        const val CARD_W = 78
        const val CARD_H = 94
        const val CARD_GAP = 8
        const val ICON_SIZE = 96
        const val ICON_SHOWN = 62
        const val GEAR_SIZE = 128
        val GEAR: Identifier = Identifier.fromNamespaceAndPath("geileraddons", "textures/gui/octicon-gear-16.png")
    }
}
