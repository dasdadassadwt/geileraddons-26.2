package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.rename.RenameApplier
import geiler.addons.client.rename.RenameOverride
import geiler.addons.client.rename.RenameScreen
import geiler.addons.client.rename.RenameStore
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/**
 * Client-side item overrides (name, colours, icon, tooltip, look) matched by Hypixel item uuid or SkyBlock id, applied when
 * the server sends the stack so nothing costs anything per frame. See [RenameApplier] and [RenameStore].
 */
class AnyRenameModule private constructor(private val paintbrush: BooleanSetting) : Module(
    "Any Rename",
    "Rename items and change their icon, tooltip and look on your client only, by item uuid. Use /ga rename or the inventory brush.",
    Category.VISUAL,
    false,
    paintbrush,
) {
    companion object {
        @JvmField
        val INSTANCE = AnyRenameModule(BooleanSetting("Inventory Brush Button", true))
        private const val SLOW_NANOS = 1_000_000L
    }

    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() })
    private var loaded = false
    private var lastApplied = 0L

    fun brushEnabled(): Boolean = paintbrush.value()

    override fun onEnable() {
        if (!loaded) {
            RenameStore.INSTANCE.load(RenameStore.file())
            loaded = true
        }
        diagnostics.enable("entries=${RenameStore.INSTANCE.size()} brush=${paintbrush.value()}")
        Minecraft.getInstance().execute { RenameApplier.reapplyAll() }
    }

    override fun onDisable() {
        RenameApplier.restoreAll()
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { suppressed ->
            "entries=${RenameStore.INSTANCE.size()} appliedTotal=${RenameApplier.applied} restoredTotal=${RenameApplier.restored} " +
                "originals=${RenameApplier.originalCount()} slowestUs=${RenameApplier.slowest / 1000} suppressedEvents=$suppressed"
        }
    }

    /** Packet hook: applies the matching override to a received stack. Only touches stacks on the client thread. */
    fun onStack(stack: ItemStack) {
        if (!isEnabled() || !RenameStore.INSTANCE.any || stack.isEmpty || !Minecraft.getInstance().isSameThread) return
        val start = System.nanoTime()
        val changed = RenameApplier.apply(stack)
        val took = System.nanoTime() - start
        if (took > RenameApplier.slowest) RenameApplier.slowest = took
        if (changed && took > SLOW_NANOS) diagnostics.event("SLOW_APPLY us=${took / 1000} id=${SkyblockItems.skyBlockId(stack)}")
    }

    /** Opens the editor for [stack] (a held or hovered item). */
    fun edit(parent: Screen?, stack: ItemStack) {
        if (!isEnabled()) return say("Enable the Any Rename module first.")
        val real = RenameApplier.original(stack)
        if (real.isEmpty) return say("Hold or pick an item to rename.")
        if (SkyblockItems.itemUuid(real).isEmpty() && SkyblockItems.skyBlockId(real).isEmpty()) {
            diagnostics.event("EDIT_REJECTED reason=no_uuid_no_id item=${real.item}")
            return say("This item has no SkyBlock id or uuid, so it cannot be renamed.")
        }
        diagnostics.event("EDIT_OPEN id=${SkyblockItems.skyBlockId(real)} hasUuid=${SkyblockItems.itemUuid(real).isNotEmpty()}")
        Minecraft.getInstance().gui.setScreen(RenameScreen(parent, stack))
    }

    /** Stores [override] for [stack]'s uuid (or its SkyBlock id for a type rule), saves and re-applies it right away. */
    fun save(stack: ItemStack, override: RenameOverride) {
        val real = RenameApplier.original(stack)
        val uuid = SkyblockItems.itemUuid(real)
        val id = SkyblockItems.skyBlockId(real)
        RenameStore.INSTANCE.put(uuid, id, override)
        RenameStore.INSTANCE.saveAsync(RenameStore.file())
        RenameApplier.reapplyAll()
        diagnostics.event("SAVE id=$id type=${override.allOfType} empty=${override.isEmpty()} entries=${RenameStore.INSTANCE.size()}")
    }

    /** Removes both the uuid override and, when [alsoType] is set, the type rule for [stack]. Returns whether anything was removed. */
    fun reset(stack: ItemStack, alsoType: Boolean): Boolean {
        val real = RenameApplier.original(stack)
        val store = RenameStore.INSTANCE
        val uuid = SkyblockItems.itemUuid(real)
        val id = SkyblockItems.skyBlockId(real)
        val had = store.lookup(uuid, if (alsoType) id else "") != null
        store.remove(uuid, id, false)
        if (alsoType) store.remove(uuid, id, true)
        store.saveAsync(RenameStore.file())
        RenameApplier.reapplyAll()
        diagnostics.event("RESET id=$id alsoType=$alsoType removed=$had")
        return had
    }

    fun existing(stack: ItemStack): RenameOverride? {
        val real = RenameApplier.original(stack)
        return RenameStore.INSTANCE.lookup(SkyblockItems.itemUuid(real), SkyblockItems.skyBlockId(real))
    }

    fun say(text: String) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("[GeilerAddons] $text"))
    }
}
