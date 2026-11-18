package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.TextSetting
import geiler.addons.client.utility.PlayerAlerts
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
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

/** Keys for Next/Previous page, Unequip and nine pets in the Pets menu, after Odin Pet Keybinds. */
class PetKeybindsModule private constructor() : Module(
    "Pet Keybinds",
    "Allows you to use keybinds to navigate and pick pets in the Pets menu.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val nextPage = spec.text("nextPage")
    private val previousPage = spec.text("previousPage")
    private val unequip = spec.text("unequip")
    private val disableUnequip = spec.toggle("disableUnequip")
    private val closeIfEquipped = spec.toggle("closeIfEquipped")
    private val petKeys = (1..9).map { spec.text("pet$it") }
    private var clicks = 0L
    private var blocked = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        clicks = 0L
        blocked = 0L
        diagnostics.enable("disableUnequip=${disableUnequip.value()} closeIfEquipped=${closeIfEquipped.value()}")
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
        if (!ContainerKeybindRules.isPetsTitle(screen.title.string)) return false
        val equipped = ContainerKeybindRules.equippedPetSlot(screen.menu.slots.map { slot ->
            slot.index to (slot.item.get(DataComponents.LORE)?.lines()?.map { it.string } ?: emptyList())
        })
        val outcome = ContainerKeybindRules.pets(
            screen.title.string, pressedKeyName, key(nextPage), key(previousPage), key(unequip), petKeys.map(::key),
            equipped, disableUnequip.value(), closeIfEquipped.value(),
        )
        if (!outcome.handled) return false
        outcome.message?.let {
            blocked++
            PlayerAlerts.chat(it)
            diagnostics.event("alreadyEquipped key=$pressedKeyName close=${outcome.close}")
            if (outcome.close) Minecraft.getInstance().gui.setScreen(null)
            return true
        }
        val clicked = clickSlot(screen, outcome.slot)
        if (clicked) {
            clicks++
            diagnostics.event("click slot=${outcome.slot} key=$pressedKeyName")
        }
        return clicked
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Pet Keybinds")

        @JvmField
        val INSTANCE = PetKeybindsModule()
    }
}

/** One key per preset command (pets, storage, wardrobe ...), after Odin Command Keybinds. Works in the world view only. */
class CommandKeybindsModule private constructor() : Module(
    "Command Keybinds",
    "Binds keys to common SkyBlock commands such as /pets, /storage and /stats.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val keys = ContainerKeybindRules.COMMAND_PRESETS.map { spec.text(it.id) }
    private var sent = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    // No group(): module-settings.json declares no groups for these keys, and an empty list would hide them all.

    override fun onEnable() {
        sent = 0L
        diagnostics.enable("presets=${keys.count { it.value().isNotBlank() }} bound")
    }

    override fun onDisable() {
        diagnostics.event("DISABLE sent=$sent")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true sent=$sent suppressedEvents=$it" }
    }

    /** A key press with no screen open; true means a preset command was sent and the press is used. */
    fun onKey(pressedKeyName: String): Boolean {
        if (!isEnabled()) return false
        val command = ContainerKeybindRules.commandFor(pressedKeyName, keys.map(::key)) ?: return false
        PlayerAlerts.command(command)
        sent++
        diagnostics.event("COMMAND key=$pressedKeyName command=$command")
        return true
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Command Keybinds")

        @JvmField
        val INSTANCE = CommandKeybindsModule()
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
