package geiler.addons.client.gui

import geiler.addons.client.access.AbstractContainerScreenAccess
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.inventory.ContainerInput

/**
 * A replacement surface for one kind of Hypixel chest menu (Party Finder, Auction House, Bazaar). The real container
 * screen stays open underneath as the backend: menu updates arrive as usual, and every action is a click on a real
 * slot. The container mixins skip vanilla drawing while a skin owns the screen.
 */
interface ContainerSkin {
    fun owns(screen: AbstractContainerScreen<*>): Boolean
    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int)

    /** True consumes the click; vanilla never sees a click on its hidden slots either way. */
    fun click(screen: AbstractContainerScreen<*>, event: MouseButtonEvent): Boolean = true
    fun scroll(screen: AbstractContainerScreen<*>, mouseX: Double, mouseY: Double, amount: Double): Boolean = true

    /** True consumes the key. Escape is never offered, so closing always works. */
    fun key(screen: AbstractContainerScreen<*>, event: KeyEvent): Boolean = false
    fun type(screen: AbstractContainerScreen<*>, event: CharacterEvent): Boolean = false
}

object ContainerSkins {
    private val skins = ArrayList<ContainerSkin>()

    fun add(skin: ContainerSkin) {
        skins.add(skin)
    }

    @JvmStatic
    fun owner(screen: AbstractContainerScreen<*>?): ContainerSkin? = screen?.let { s -> skins.firstOrNull { it.owns(s) } }

    @JvmStatic
    fun owns(screen: AbstractContainerScreen<*>?): Boolean = owner(screen) != null

    /** Called from the container render hook; true means the skin drew and vanilla drawing is skipped. */
    @JvmStatic
    fun render(screen: AbstractContainerScreen<*>, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int): Boolean {
        val skin = owner(screen) ?: return false
        skin.render(screen, graphics, mouseX, mouseY)
        return true
    }

    /** One click on a real menu slot through the screen's own slot click, as if the player clicked it in the vanilla menu. */
    @JvmStatic
    fun clickSlot(screen: AbstractContainerScreen<*>, slot: Int, button: Int = 0) {
        if (Minecraft.getInstance().player == null || slot < 0 || slot >= screen.menu.slots.size) return
        (screen as AbstractContainerScreenAccess).`geileraddons$invokeSlotClicked`(screen.menu.slots[slot], slot, button, ContainerInput.PICKUP)
    }

    fun register() {
        FancySignOverlay.register()
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is AbstractContainerScreen<*>) return@register
            ScreenMouseEvents.allowMouseClick(screen).register { target, event ->
                val s = target as AbstractContainerScreen<*>
                owner(s)?.let { !it.click(s, event) } ?: true
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { target, x, y, _, vertical ->
                val s = target as AbstractContainerScreen<*>
                owner(s)?.let { !it.scroll(s, x, y, vertical) } ?: true
            }
            ScreenKeyboardEvents.allowKeyPress(screen).register { target, event ->
                val s = target as AbstractContainerScreen<*>
                if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) true else owner(s)?.let { !it.key(s, event) } ?: true
            }
            ScreenKeyboardEvents.allowCharType(screen).register { target, event ->
                val s = target as AbstractContainerScreen<*>
                owner(s)?.let { !it.type(s, event) } ?: true
            }
        }
    }
}
