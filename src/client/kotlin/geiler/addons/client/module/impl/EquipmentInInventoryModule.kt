package geiler.addons.client.module.impl

import geiler.addons.client.access.AbstractContainerScreenAccess
import geiler.addons.client.mixin.SlotPositionAccessor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import geiler.addons.client.inventory.EquipmentRules
import geiler.addons.client.inventory.EquipmentStore
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.utility.PlayerAlerts
import geiler.addons.client.utility.SkyblockItems
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Four equipment slots inside the inventory, where the offhand slot was; the offhand moves one slot right. Hypixel
 * only sends equipment inside its own menu, so the items are remembered from the last time that menu was open. A
 * click runs `/stats`.
 */
class EquipmentInInventoryModule private constructor() : Module(
    "Equipment In Inventory",
    "Shows your necklace, cloak, belt and gloves in your inventory and moves the offhand slot one to the right.",
    Category.MISCELLANEOUS,
    false,
) {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)
    private var profileId: String? = null
    private var account: UUID? = null
    private var file = CompoundTag()
    private var items: List<ItemStack>? = null
    private var itemsKey: String? = null
    private var lastCommandMillis = 0L
    private var captures = 0
    private var clicks = 0
    private var drawn = 0L

    override fun onEnable() {
        forget()
        diagnostics.enable("profileKnown=${profileId != null}")
    }

    override fun onDisable() {
        moveOffhand(false)
        forget()
        diagnostics.disable()
    }

    private fun forget() {
        account = null
        file = CompoundTag()
        items = null
        itemsKey = null
    }

    /** Runs while disabled too: the profile line only arrives when joining a server. */
    fun onChatMessage(plain: String) {
        val id = EquipmentRules.profileId(plain) ?: return
        if (id == profileId) return
        profileId = id
        items = null
        itemsKey = null
        diagnostics.event("PROFILE changed")
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status {
            "profileKnown=${profileId != null} loaded=${items != null} captures=$captures clicks=$clicks " +
                "drawn=$drawn suppressedEvents=$it"
        }
    }

    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is ContainerScreen && EquipmentRules.isMenu(screen.title.string)) {
                // The slots fill after the screen opens and change when you swap an item, so read them while it is up.
                ScreenEvents.afterTick(screen).register { capture(screen) }
            } else if (screen is InventoryScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                    !handleClick(screen, event.x(), event.y(), event.button())
                }
                // The player menu outlives the screen, so the offhand goes home with it.
                ScreenEvents.remove(screen).register { moveOffhand(false) }
            }
        }
    }

    private fun currentKey(): String =
        EquipmentRules.key(
            profileId ?: file.getStringOr("lastProfile", UNKNOWN_PROFILE),
            HypixelModApi.currentIsland() == Island.THE_RIFT,
        )

    /** The saved items of the current profile, loading the account file on first use. */
    private fun current(): List<ItemStack>? {
        val player = Minecraft.getInstance().player ?: return null
        if (account != player.uuid) {
            account = player.uuid
            file = EquipmentStore.load(player.uuid)
            itemsKey = null
        }
        val key = currentKey()
        if (key != itemsKey) {
            itemsKey = key
            items = EquipmentStore.get(file, key, player.connection.registryAccess())
        }
        return items
    }

    private fun capture(screen: ContainerScreen) {
        if (!isEnabled() || !SkyblockItems.inSkyblock()) return
        val player = Minecraft.getInstance().player ?: return
        val slots = screen.menu.slots
        if (slots.size <= EquipmentRules.menuSlot(EquipmentRules.SLOTS - 1)) return
        val raw = List(EquipmentRules.SLOTS) { slots[EquipmentRules.menuSlot(it)].item }
        // An empty slot shows a glass pane; four truly empty slots mean the menu has not filled yet.
        if (raw.all { it.isEmpty }) return
        val read = raw.map { stack ->
            if (stack.isEmpty || BuiltInRegistries.ITEM.getKey(stack.item).path.endsWith("stained_glass_pane")) {
                ItemStack.EMPTY
            } else {
                stack.copyWithCount(1)
            }
        }
        val before = current()
        if (before != null && before.indices.all { ItemStack.matches(before[it], read[it]) }) return
        val key = currentKey()
        EquipmentStore.put(file, key, read, player.connection.registryAccess())
        profileId?.let { file.putString("lastProfile", it) }
        items = read
        itemsKey = key
        EquipmentStore.save(player.uuid, file)
        captures++
        diagnostics.event("CAPTURE filled=${read.count { !it.isEmpty }} profileKnown=${profileId != null}")
    }

    private fun visible(): Boolean = isEnabled() && SkyblockItems.inSkyblock()

    private fun leftPos(screen: InventoryScreen): Int = (screen as AbstractContainerScreenAccess).`geileraddons$getLeftPos`()

    private fun topPos(screen: InventoryScreen): Int = (screen as AbstractContainerScreenAccess).`geileraddons$getTopPos`()

    private fun inPanel(screen: InventoryScreen, x: Double, y: Double): Boolean =
        visible() && EquipmentRules.panelContains(leftPos(screen), topPos(screen), x.toInt(), y.toInt())

    private fun moveOffhand(moved: Boolean) {
        val slot = Minecraft.getInstance().player?.inventoryMenu?.slots?.getOrNull(EquipmentRules.OFFHAND_MENU_SLOT) ?: return
        val x = if (moved) EquipmentRules.OFFHAND_MOVED_X else EquipmentRules.OFFHAND_HOME_X
        if (slot.x == x) return
        (slot as SlotPositionAccessor).`geileraddons$setX`(x)
        if (isEnabled()) diagnostics.event("OFFHAND x=$x")
    }

    /** Where the recipe book button goes, so it stays clear of the moved offhand slot. */
    fun recipeButtonShift(): Int = if (visible()) EquipmentRules.RECIPE_BUTTON_SHIFT else 0

    /**
     * Runs before the inventory's slots are drawn, in inventory coordinates: moves the offhand and draws the column,
     * so a carried item and the tooltip stay on top of it.
     */
    fun beforeSlots(screen: Any, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (screen !is InventoryScreen) return
        val shown = visible()
        moveOffhand(shown)
        if (!shown) return
        // The offhand's frame art follows it. A blank piece of the same texture hides the old one.
        frame(graphics, EquipmentRules.OFFHAND_HOME_X - 1, EquipmentRules.OFFHAND_Y - 1, BLANK_U, BLANK_V)
        frame(graphics, EquipmentRules.OFFHAND_MOVED_X - 1, EquipmentRules.OFFHAND_Y - 1,
            EquipmentRules.OFFHAND_HOME_X - 1, EquipmentRules.OFFHAND_Y - 1)
        val saved = current()
        val x = EquipmentRules.slotX(0)
        val rarity = ItemRarityBackgroundsModule.INSTANCE
        // All backgrounds first, then all items: a fill between two items would stack a layer per slot.
        for (index in 0 until EquipmentRules.SLOTS) {
            rarity.drawBackground(graphics, saved?.get(index) ?: ItemStack.EMPTY, x, EquipmentRules.slotY(0, index))
        }
        for (index in 0 until EquipmentRules.SLOTS) {
            val stack = saved?.get(index) ?: ItemStack.EMPTY
            if (!stack.isEmpty) graphics.item(stack, x, EquipmentRules.slotY(0, index))
        }
        val hovered = EquipmentRules.slotAt(leftPos(screen), topPos(screen), mouseX, mouseY)
        if (hovered >= 0) {
            val y = EquipmentRules.slotY(0, hovered)
            graphics.fill(x, y, x + 16, y + 16, HOVER_FILL)
        }
        drawn++
    }

    private fun frame(graphics: GuiGraphicsExtractor, x: Int, y: Int, u: Int, v: Int) {
        graphics.blit(
            RenderPipelines.GUI_TEXTURED, AbstractContainerScreen.INVENTORY_LOCATION, x, y,
            u.toFloat(), v.toFloat(), 18, 18, 256, 256,
        )
    }

    /** The column's tooltip, drawn with the rest of the inventory's overlays. */
    fun renderOnInventory(screen: InventoryScreen, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!visible() || !screen.menu.carried.isEmpty) return
        val hovered = EquipmentRules.slotAt(leftPos(screen), topPos(screen), mouseX, mouseY)
        if (hovered < 0) return
        val saved = current()
        val stack = saved?.get(hovered) ?: ItemStack.EMPTY
        val font = Minecraft.getInstance().font
        if (!stack.isEmpty) {
            graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY)
        } else {
            val hint = if (saved == null) "Open /equipment once to fill this" else "Empty"
            graphics.setComponentTooltipForNextFrame(
                font,
                listOf(Component.literal(EquipmentRules.NAMES[hovered]), Component.literal("§7$hint")),
                mouseX, mouseY,
            )
        }
    }

    /** True when the click landed on the column; it is never passed on to the inventory. */
    private fun handleClick(screen: InventoryScreen, mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (!inPanel(screen, mouseX, mouseY)) return false
        if (button != 0) return true
        val now = System.currentTimeMillis()
        if (now - lastCommandMillis < COMMAND_GAP_MILLIS) return true
        lastCommandMillis = now
        clicks++
        diagnostics.event("CLICK command=stats")
        PlayerAlerts.command("stats")
        return true
    }

    companion object {
        private const val UNKNOWN_PROFILE = "unknown"
        private const val COMMAND_GAP_MILLIS = 500L
        private const val HOVER_FILL = 0x80FFFFFF.toInt()
        /** A blank 18x18 piece of the inventory texture, right of the player preview. */
        private const val BLANK_U = 76
        private const val BLANK_V = 24

        @JvmField
        val INSTANCE = EquipmentInInventoryModule()
    }
}
