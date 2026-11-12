package geiler.addons.client.inventory

import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import kotlin.jvm.JvmField
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Runtime entrypoints used by Java mixins, Kotlin UI code, and profile transactions. */
class PlayerInventoryControls private constructor() {
    init {
        // Retain eager local-store loading and key registration through the Kotlin runtime.
        PlayerInventoryControlsRuntime.settings()
    }

    fun settings(): PlayerInventoryControlsData? = PlayerInventoryControlsRuntime.settings()

    /** Replaces cached settings only after the installed file has been read and validated. */
    fun reloadFromDisk(): Boolean = PlayerInventoryControlsRuntime.reloadFromDisk()

    /** Flushes edits made before a profile transaction replaces the standalone file. */
    fun flush(): Boolean = PlayerInventoryControlsRuntime.flush()

    /** Keeps an unreadable existing store intact in the recovery archive for an import that replaces it. */
    fun flushForProfileImport(): Boolean = PlayerInventoryControlsRuntime.flushForProfileImport()

    fun snapshotRuntimeState(): RuntimeSnapshot = PlayerInventoryControlsRuntime.snapshotRuntimeState()

    fun restoreRuntimeState(snapshot: RuntimeSnapshot?) {
        PlayerInventoryControlsRuntime.restoreRuntimeState(snapshot)
    }

    /** Pauses settings edits while a profile transaction replaces the standalone store. */
    fun beginProfileTransaction() = PlayerInventoryControlsRuntime.beginProfileTransaction()
    fun endProfileTransaction() = PlayerInventoryControlsRuntime.endProfileTransaction()

    fun isSlotLocked(inventorySlot: Int): Boolean =
        PlayerInventoryControlsRuntime.isSlotLocked(inventorySlot)

    fun setSlotLocked(inventorySlot: Int, locked: Boolean) {
        PlayerInventoryControlsRuntime.setSlotLocked(inventorySlot, locked)
    }

    fun toggleSlotLock(inventorySlot: Int) = PlayerInventoryControlsRuntime.toggleSlotLock(inventorySlot)

    fun applyData(next: PlayerInventoryControlsData?) = PlayerInventoryControlsRuntime.applyData(next)

    fun blocksSlotClick(minecraft: Minecraft?, slot: Slot?): Boolean =
        PlayerInventoryControlsRuntime.blocksSlotClick(minecraft, slot)

    /** Guards clicked sources and operation targets known at the screen-input boundary. */
    fun blocksContainerInput(
        minecraft: Minecraft?,
        screen: AbstractContainerScreen<*>?,
        slot: Slot?,
        slotId: Int,
        button: Int,
        input: ContainerInput?,
    ): Boolean = PlayerInventoryControlsRuntime.blocksContainerInput(
        minecraft,
        screen,
        slot,
        slotId,
        button,
        input,
    )

    /**
     * Preflights one vanilla AbstractContainerMenu.moveItemStackTo range before vanilla mutates it.
     * The supplied range and reverse flag are the actual ordered destinations selected by the menu.
     * Menus that mutate inventory slots directly instead of using this helper are outside this
     * destination guard; their clicked locked sources are still rejected at the screen-input boundary.
     */
    fun blocksMoveItemStackTo(
        minecraft: Minecraft?,
        menu: AbstractContainerMenu?,
        moving: ItemStack?,
        start: Int,
        end: Int,
        reverse: Boolean,
    ): Boolean = PlayerInventoryControlsRuntime.blocksMoveItemStackTo(
        minecraft,
        menu,
        moving,
        start,
        end,
        reverse,
    )

    /** A drag candidate is excluded before AbstractContainerScreen adds it to its distribution set. */
    fun blocksQuickCraftTarget(minecraft: Minecraft?, slot: Slot?): Boolean =
        PlayerInventoryControlsRuntime.blocksQuickCraftTarget(minecraft, slot)

    fun blocksWorldDrop(minecraft: Minecraft?): Boolean =
        PlayerInventoryControlsRuntime.blocksWorldDrop(minecraft)

    fun blocksOffhandSwap(minecraft: Minecraft?): Boolean =
        PlayerInventoryControlsRuntime.blocksOffhandSwap(minecraft)

    @JvmRecord
    data class RuntimeSnapshot(val settings: PlayerInventoryControlsData?)

    /** Runs before a vanilla hotbar mapping can interpret the same press as a slot swap. */
    fun handleKeyEvent(minecraft: Minecraft?, action: Int, event: KeyEvent?): Boolean =
        PlayerInventoryControlsRuntime.handleKeyEvent(minecraft, action, event)

    /** Called by the inventory-screen mixin before the ordinary key handling path. */
    fun handleScreenKeyPressed(
        screen: AbstractContainerScreen<*>?,
        hoveredSlot: Slot?,
        event: KeyEvent?,
    ): Boolean = PlayerInventoryControlsRuntime.handleScreenKeyPressed(screen, hoveredSlot, event)

    /** Completes or cancels the held slot-pair gesture on key release. */
    fun handleScreenKeyReleased(
        screen: AbstractContainerScreen<*>?,
        hoveredSlot: Slot?,
        event: KeyEvent?,
    ): Boolean = PlayerInventoryControlsRuntime.handleScreenKeyReleased(screen, hoveredSlot, event)

    fun resetScreenGestures() = PlayerInventoryControlsRuntime.resetScreenGestures()

    fun runLinkedQuickMove(
        minecraft: Minecraft?,
        menu: AbstractContainerMenu?,
        clicked: Slot?,
        slotId: Int,
    ): Boolean = PlayerInventoryControlsRuntime.runLinkedQuickMove(minecraft, menu, clicked, slotId)

    fun slotLinkColor(hotbarSlot: Int): Int = PlayerInventoryControlsRuntime.slotLinkColor(hotbarSlot)

    companion object {
        @JvmField
        val INSTANCE = PlayerInventoryControls()

        @JvmStatic
        fun lockHoveredSlotKey(): KeyMapping = PlayerInventoryControlsRuntime.lockHoveredSlotKey()

        @JvmStatic
        fun bindInventorySlotsKey(): KeyMapping = PlayerInventoryControlsRuntime.bindInventorySlotsKey()
    }
}

// Keep the former Java record accessor call syntax available to the Kotlin runtime.
internal fun PlayerInventoryControls.RuntimeSnapshot.settings(): PlayerInventoryControlsData? = settings
