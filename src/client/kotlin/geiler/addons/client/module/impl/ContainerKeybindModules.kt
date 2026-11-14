package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.TextSetting
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.inventory.ContainerInput

/** Clicks the container slot a rule chose, as a plain left click, and reports what happened. */
private fun clickSlot(screen: AbstractContainerScreen<*>, slot: Int): Boolean {
    val client = Minecraft.getInstance()
    val player = client.player ?: return false
    val gameMode = client.gameMode ?: return false
    if (!client.isSameThread || slot !in screen.menu.slots.indices) return false
    gameMode.handleContainerInput(screen.menu.containerId, slot, 0, ContainerInput.PICKUP, player)
    return true
}

private fun key(setting: TextSetting): String = ContainerKeybindRules.normalize(setting.value())

/** Keys for Next/Previous page, Unequip and each set in the Wardrobe menu, after Odin's Wardrobe Keybinds. */
class WardrobeKeybindsModule private constructor() : Module(
    "Wardrobe Keybinds",
    "Allows you to use keybinds to navigate the wardrobe.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val nextPage = spec.text("nextPage")
    private val previousPage = spec.text("previousPage")
    private val unequip = spec.text("unequip")
    private val disableUnequip = spec.toggle("disableUnequip")
    private val setKeys = (1..9).map { spec.text("wardrobe$it") }
    private var clicks = 0L
    private var blocked = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        clicks = 0L
        blocked = 0L
        diagnostics.enable("disableUnequip=${disableUnequip.value()}")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE clicks=$clicks blocked=$blocked")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true clicks=$clicks alreadyEquipped=$blocked suppressedEvents=$it" }
    }

    /** A key press in a container screen; true means the press was used and must not reach the screen. */
    fun onKey(screen: AbstractContainerScreen<*>, pressedKeyName: String): Boolean {
        if (!isEnabled()) return false
        val title = screen.title.string
        if (!ContainerKeybindRules.isWardrobeTitle(title)) return false
        val slots = screen.menu.slots
        val equipped = ContainerKeybindRules.equippedWardrobeSlot(slots.map { it.index to it.item.hoverName.string })
        val outcome = ContainerKeybindRules.wardrobe(
            title, pressedKeyName, key(nextPage), key(previousPage), key(unequip), setKeys.map(::key), equipped,
            disableUnequip.value(),
        ) { slots.getOrNull(it)?.item?.isEmpty ?: true }
        outcome.message?.let {
            blocked++
            PlayerAlerts.chat(it)
            diagnostics.event("alreadyEquipped key=$pressedKeyName")
        }
        if (!outcome.handled) return false
        val clicked = clickSlot(screen, outcome.slot)
        if (clicked) {
            clicks++
            diagnostics.event("click slot=${outcome.slot} key=$pressedKeyName")
        }
        return clicked
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Wardrobe Keybinds")

        @JvmField
        val INSTANCE = WardrobeKeybindsModule()
    }
}

/** Keys for Next/Previous page and each of the twelve slots in the Loadout menu, after Odin's Loadout Keybinds. */
class LoadoutKeybindsModule private constructor() : Module(
    "Loadout Keybinds",
    "Allows you to use keybinds to navigate the loadout.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val nextPage = spec.text("nextPage")
    private val previousPage = spec.text("previousPage")
    private val loadoutKeys = (1..12).map { spec.text("loadout$it") }
    private var clicks = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        clicks = 0L
        diagnostics.enable("")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE clicks=$clicks")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true clicks=$clicks suppressedEvents=$it" }
    }

    /** A key press in a container screen; true means the press was used and must not reach the screen. */
    fun onKey(screen: AbstractContainerScreen<*>, pressedKeyName: String): Boolean {
        if (!isEnabled()) return false
        val title = screen.title.string
        if (!ContainerKeybindRules.isLoadoutTitle(title)) return false
        val outcome = ContainerKeybindRules.loadout(
            title, pressedKeyName, key(nextPage), key(previousPage), loadoutKeys.map(::key),
        )
        if (!outcome.handled) return false
        val clicked = clickSlot(screen, outcome.slot)
        if (clicked) {
            clicks++
            diagnostics.event("click slot=${outcome.slot} key=$pressedKeyName")
        }
        return clicked
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Loadout Keybinds")

        @JvmField
        val INSTANCE = LoadoutKeybindsModule()
    }
}
