package geiler.addons.client.inventory

import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.ClientKeyMappingRegistrar
import geiler.addons.client.module.impl.PlayerInventoryControlsModule
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import java.util.ArrayList

/** Kotlin implementation behind the Java-facing runtime and record compatibility surface. */
object PlayerInventoryControlsRuntime {
    private var settings: PlayerInventoryControlsData? = PlayerInventoryControlsStore.load()

    @Volatile
    private var profileTransactionActive = false
    private var pendingGesture: SlotGesture? = null
    // One key handles both quick lock toggles and held inventory-to-hotbar bindings.
    private val bindInventorySlotsKeyMapping = ClientKeyMappingRegistrar.register(
        KeyMapping(
            "key.geileraddons.bind_inventory_slots",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_G,
            KeyMapping.Category.GAMEPLAY,
        ),
    )

    fun settings(): PlayerInventoryControlsData? = settings
    fun lockHoveredSlotKey(): KeyMapping = bindInventorySlotsKeyMapping
    fun bindInventorySlotsKey(): KeyMapping = bindInventorySlotsKeyMapping

    /** Replaces cached settings only after the installed file has been read and validated. */
    fun reloadFromDisk(): Boolean {
        val loaded = PlayerInventoryControlsStore.readForReload()
        if (!loaded.success()) return false
        settings = loaded.data()
        return true
    }

    /** Flushes edits made before a profile transaction replaces the standalone file. */
    fun flush(): Boolean {
        PlayerInventoryControlsStore.awaitIdle(2000)
        return PlayerInventoryControlsStore.save(settings)
    }

    /** Keeps an unreadable existing store intact in the recovery archive for an import that replaces it. */
    fun flushForProfileImport(): Boolean = PlayerInventoryControlsStore.flushForProfileImport(settings)

    fun snapshotRuntimeState(): PlayerInventoryControls.RuntimeSnapshot =
        PlayerInventoryControls.RuntimeSnapshot(settings)

    fun restoreRuntimeState(snapshot: PlayerInventoryControls.RuntimeSnapshot?) {
        if (snapshot != null) settings = snapshot.settings()
    }

    /** Pauses settings edits while a profile transaction replaces the standalone store. */
    fun beginProfileTransaction() {
        profileTransactionActive = true
    }

    fun endProfileTransaction() {
        profileTransactionActive = false
    }

    fun isSlotLocked(inventorySlot: Int): Boolean = currentSettings().isLocked(inventorySlot)

    fun setSlotLocked(inventorySlot: Int, locked: Boolean) {
        if (profileTransactionActive || !PlayerInventoryControlsModule.INSTANCE.isEnabled()) return
        val current = currentSettings()
        val next = current.withSlotLocked(inventorySlot, locked)
        if (next == current) return
        settings = next
        PlayerInventoryControlsStore.saveLater(settings)
    }

    fun toggleSlotLock(inventorySlot: Int) {
        setSlotLocked(inventorySlot, !isSlotLocked(inventorySlot))
    }

    fun applyData(next: PlayerInventoryControlsData?) {
        setData(next)
    }

    fun blocksSlotClick(minecraft: Minecraft?, slot: Slot?): Boolean {
        if (!isEnabled() || minecraft == null || minecraft.player == null || slot == null) return false
        val player = minecraft.player ?: return false
        val inventory: Inventory = player.getInventory()
        val current = currentSettings()
        val playerSlot = slot.container === inventory
        return playerSlot && (
            PlayerSlotLockRules.blocksSlotClick(true, slot.getContainerSlot(), current) ||
                protectsBoundSlots() && current.hasLinkAt(slot.getContainerSlot())
            )
    }

    /** Guards clicked sources and operation targets known at the screen-input boundary. */
    fun blocksContainerInput(
        minecraft: Minecraft?,
        screen: AbstractContainerScreen<*>?,
        slot: Slot?,
        slotId: Int,
        button: Int,
        input: ContainerInput?,
    ): Boolean {
        if (!isEnabled() || minecraft == null || minecraft.player == null || screen == null || input == null) {
            return false
        }
        val menu = screen.getMenu()
        var clicked = slot
        if (clicked == null && slotId >= 0 && slotId < menu.slots.size) {
            clicked = menu.slots[slotId]
        }
        if (input == ContainerInput.QUICK_MOVE && button == 0 &&
            runLinkedQuickMove(minecraft, menu, clicked, slotId)
        ) {
            return true
        }
        val clickedLocked = blocksSlotClick(minecraft, clicked)
        val action = when (input) {
            ContainerInput.PICKUP -> PlayerSlotLockRules.ContainerAction.PICKUP
            ContainerInput.QUICK_MOVE -> PlayerSlotLockRules.ContainerAction.QUICK_MOVE
            ContainerInput.SWAP -> PlayerSlotLockRules.ContainerAction.SWAP
            ContainerInput.CLONE -> PlayerSlotLockRules.ContainerAction.CLONE
            ContainerInput.THROW -> PlayerSlotLockRules.ContainerAction.THROW
            ContainerInput.QUICK_CRAFT -> PlayerSlotLockRules.ContainerAction.QUICK_CRAFT
            ContainerInput.PICKUP_ALL -> PlayerSlotLockRules.ContainerAction.PICKUP_ALL
        }
        val current = currentSettings()
        val swapTarget = if (input == ContainerInput.SWAP) {
            PlayerSlotLockRules.swapTargetInventorySlot(button)
        } else {
            -1
        }
        val swapTargetLocked = swapTarget >= 0 && (
            current.isLocked(swapTarget) || protectsBoundSlots() && current.hasLinkAt(swapTarget)
            )
        val pickupAllWouldTakeLocked = input == ContainerInput.PICKUP_ALL &&
            pickupAllWouldTakeLockedSlot(minecraft, menu)
        return PlayerSlotLockRules.blocksContainerAction(
            action,
            clickedLocked,
            swapTargetLocked,
            false,
            pickupAllWouldTakeLocked,
        )
    }

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
    ): Boolean {
        val moduleEnabled = isEnabled()
        val protectedSlotsPresent = hasProtectedSlots()
        if (minecraft == null || minecraft.player == null || menu == null || moving == null ||
            moving.isEmpty() ||
            !PlayerSlotLockRules.shouldPreflightQuickMove(moduleEnabled, protectedSlotsPresent)
        ) {
            return false
        }
        if (start < 0 || end > menu.slots.size || start >= end) return false

        val player = minecraft.player ?: return false
        val inventory = player.getInventory()
        val ordered = ArrayList<PlayerSlotLockRules.QuickMoveDestination>(end - start)
        var index = if (reverse) end - 1 else start
        while (if (reverse) index >= start else index < end) {
            val slot = menu.slots[index]
            val existing = slot.getItem()
            val empty = existing.isEmpty()
            val sameItem = !empty && ItemStack.isSameItemSameComponents(moving, existing)
            val locked = slot.container === inventory && isProtectedInventorySlot(slot.getContainerSlot())
            val maxStackSize = slot.getMaxStackSize(if (empty) moving else existing)
            ordered.add(
                PlayerSlotLockRules.QuickMoveDestination(
                    locked,
                    empty,
                    sameItem,
                    slot.mayPlace(moving),
                    if (empty) 0 else existing.getCount(),
                    maxStackSize,
                ),
            )
            index += if (reverse) -1 else 1
        }
        return PlayerSlotLockRules.quickMoveWouldMutateLockedSlot(
            moduleEnabled,
            protectedSlotsPresent,
            moving.getCount(),
            moving.isStackable(),
            ordered,
        )
    }

    /** A drag candidate is excluded before AbstractContainerScreen adds it to its distribution set. */
    fun blocksQuickCraftTarget(minecraft: Minecraft?, slot: Slot?): Boolean = blocksSlotClick(minecraft, slot)

    private fun pickupAllWouldTakeLockedSlot(minecraft: Minecraft, menu: AbstractContainerMenu): Boolean {
        val carried = menu.getCarried()
        val player = minecraft.player ?: return false
        if (carried.isEmpty() || carried.getCount() >= carried.getMaxStackSize() || !hasProtectedSlots()) {
            return false
        }
        val inventory = player.getInventory()
        for (candidate in menu.slots) {
            if (candidate.container !== inventory ||
                !isProtectedInventorySlot(candidate.getContainerSlot()) ||
                !candidate.hasItem() || !candidate.mayPickup(player) ||
                !menu.canTakeItemForPickAll(carried, candidate)
            ) {
                continue
            }
            if (ItemStack.isSameItemSameComponents(carried, candidate.getItem())) return true
        }
        return false
    }

    fun blocksWorldDrop(minecraft: Minecraft?): Boolean {
        if (!isEnabled() || minecraft == null || minecraft.gui.screen() != null || minecraft.player == null) {
            return false
        }
        if (!PlayerSlotLockRules.dropProtectionApplies(DungeonContextTracker.isRunStarted())) return false
        val player = minecraft.player ?: return false
        val selected = player.getInventory().getSelectedSlot()
        val current = currentSettings()
        return PlayerSlotLockRules.blocksWorldDrop(selected, current) ||
            protectsBoundSlots() && current.hasLinkAt(selected)
    }

    fun blocksOffhandSwap(minecraft: Minecraft?): Boolean {
        if (!isEnabled() || minecraft == null || minecraft.player == null) return false
        val player = minecraft.player ?: return false
        val selected = player.getInventory().getSelectedSlot()
        val current = currentSettings()
        return current.isLocked(selected) || current.isLocked(40) ||
            protectsBoundSlots() && (current.hasLinkAt(selected) || current.hasLinkAt(40))
    }

    /** Runs before a vanilla hotbar mapping can interpret the same press as a slot swap. */
    fun handleKeyEvent(minecraft: Minecraft?, action: Int, event: KeyEvent?): Boolean {
        // This raw hook has no hovered slot; the screen hook resolves the same-key gesture.
        return false
    }

    /** Called by the inventory-screen mixin before the ordinary key handling path. */
    fun handleScreenKeyPressed(
        screen: AbstractContainerScreen<*>?,
        hoveredSlot: Slot?,
        event: KeyEvent?,
    ): Boolean {
        val minecraft = Minecraft.getInstance()
        if (!isEnabled() || minecraft.player == null || event == null) return false
        if (!bindInventorySlotsKeyMapping.matches(event)) return false
        if (pendingGesture != null) return true
        val source = playerInventorySlot(minecraft, hoveredSlot)
        if (source < 0) return false
        pendingGesture = SlotGesture(screen, source, System.nanoTime())
        return true
    }

    /** Completes or cancels the held slot-pair gesture on key release. */
    fun handleScreenKeyReleased(
        screen: AbstractContainerScreen<*>?,
        hoveredSlot: Slot?,
        event: KeyEvent?,
    ): Boolean {
        if (event == null) return false
        if (!bindInventorySlotsKeyMapping.matches(event)) return false
        val gesture = pendingGesture
        pendingGesture = null
        if (gesture == null) return false
        if (!isEnabled() || gesture.screen !== screen) return true
        val minecraft = Minecraft.getInstance()
        val endpoint = playerInventorySlot(minecraft, hoveredSlot)
        val resolution = PlayerInventorySlotGesture.resolve(
            gesture.inventorySlot,
            endpoint,
            (System.nanoTime() - gesture.startedAtNanos).coerceAtLeast(0L),
            currentSettings().hasLinkAt(gesture.inventorySlot),
        )
        when (resolution.action) {
            PlayerInventorySlotGesture.Action.TOGGLE_LOCK -> toggleSlotLock(resolution.inventorySlot)
            PlayerInventorySlotGesture.Action.BIND -> setData(
                currentSettings().withLink(resolution.inventorySlot, resolution.hotbarSlot),
            )
            PlayerInventorySlotGesture.Action.CLEAR_LINKS -> setData(
                currentSettings().withoutLinksAt(resolution.inventorySlot),
            )
            PlayerInventorySlotGesture.Action.NONE -> Unit
        }
        return true
    }

    fun resetScreenGestures() {
        pendingGesture = null
    }

    fun runLinkedQuickMove(
        minecraft: Minecraft?,
        menu: AbstractContainerMenu?,
        clicked: Slot?,
        slotId: Int,
    ): Boolean {
        if (!isEnabled() || minecraft == null) return false
        val player = minecraft.player ?: return false
        if (menu == null || clicked == null || clicked.container !== player.getInventory()) return false
        val current = currentSettings()
        val clickedPlayerSlot = clicked.getContainerSlot()
        val selected: PlayerInventoryControlsData.SlotLink
        val swapSourceScreenSlot: Int
        val swapHotbarSlot: Int
        val inventoryEndpoint: Boolean
        if (clickedPlayerSlot >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) {
            val partners = current.hotbarPartners(clickedPlayerSlot)
            if (partners.isEmpty()) return false
            swapHotbarSlot = partners[0]
            swapSourceScreenSlot = slotId
            inventoryEndpoint = true
            selected = PlayerInventoryControlsData.SlotLink(clickedPlayerSlot, swapHotbarSlot)
        } else {
            val partners = current.inventoryPartners(clickedPlayerSlot)
            if (partners.isEmpty()) return false
            val inventorySlot = partners[0]
            val source = menu.slots.firstOrNull { candidate ->
                candidate.container === player.getInventory() && candidate.getContainerSlot() == inventorySlot
            } ?: return false
            swapSourceScreenSlot = menu.slots.indexOf(source)
            swapHotbarSlot = clickedPlayerSlot
            inventoryEndpoint = false
            selected = PlayerInventoryControlsData.SlotLink(inventorySlot, clickedPlayerSlot)
        }
        if (swapSourceScreenSlot < 0) return false
        // The linked operation bypasses bound-slot protection, but explicit slot locks always win.
        if (current.isLocked(selected.inventorySlot()) || current.isLocked(selected.hotbarSlot())) return true
        val gameMode = minecraft.gameMode ?: return true
        setData(current.prioritizeLink(selected, inventoryEndpoint))
        gameMode.handleContainerInput(
            menu.containerId,
            swapSourceScreenSlot,
            swapHotbarSlot,
            ContainerInput.SWAP,
            player,
        )
        return true
    }

    fun slotLinkColor(hotbarSlot: Int): Int = PlayerInventoryControlsModule.INSTANCE.linkColor(hotbarSlot)

    private fun isEnabled(): Boolean = PlayerInventoryControlsModule.INSTANCE.isEnabled()

    private fun protectsBoundSlots(): Boolean = PlayerInventoryControlsModule.INSTANCE.protectBoundSlots()

    private fun hasProtectedSlots(): Boolean {
        val current = currentSettings()
        return !current.lockedSlots().isEmpty() || protectsBoundSlots() && !current.slotLinks().isEmpty()
    }

    private fun isProtectedInventorySlot(inventorySlot: Int): Boolean {
        val current = currentSettings()
        return current.isLocked(inventorySlot) || protectsBoundSlots() && current.hasLinkAt(inventorySlot)
    }

    private fun playerInventorySlot(minecraft: Minecraft?, slot: Slot?): Int {
        if (minecraft == null || minecraft.player == null || slot == null) return -1
        val player = minecraft.player ?: return -1
        if (slot.container !== player.getInventory()) return -1
        val index = slot.getContainerSlot()
        return if (index >= 0 && index < PlayerInventoryControlsData.PLAYER_SLOT_COUNT) index else -1
    }

    private fun setData(next: PlayerInventoryControlsData?) {
        if (profileTransactionActive || next == null || next == settings) return
        settings = next
        PlayerInventoryControlsStore.saveLater(settings)
    }

    private fun currentSettings(): PlayerInventoryControlsData = settings!!

    private data class SlotGesture(
        val screen: AbstractContainerScreen<*>?,
        val inventorySlot: Int,
        val startedAtNanos: Long,
    )
}
