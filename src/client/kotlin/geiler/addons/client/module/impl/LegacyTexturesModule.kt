package geiler.addons.client.module.impl

import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * Brings back the pre-0.26 SkyBlock look: declines Hypixel's forced server resource pack (Detexturify's approach) and
 * draws pack-modelled items with their plain vanilla model instead (NoFrills' Legacy Textures), choosing a matching
 * vanilla item for ones whose base item is only a placeholder. Behaviour reference only; no code is copied.
 */
class LegacyTexturesModule private constructor(private val settings: Settings) : Module(
    "Legacy Textures",
    "Declines the Hypixel server pack and shows SkyBlock items with their old vanilla models.",
    Category.VISUAL,
    false,
    *settings.all.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)
    private val modelsLogged = HashSet<String>()
    private var packsBlocked = 0L
    private var itemsRemapped = 0L

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    override fun onEnable() {
        modelsLogged.clear()
        packsBlocked = 0L
        itemsRemapped = 0L
        diagnostics.enable(summary())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE packsBlocked=$packsBlocked,itemsRemapped=$itemsRemapped")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "${summary()},packsBlocked=$packsBlocked,itemsRemapped=$itemsRemapped,suppressedEvents=$it" }
    }

    /** True when a resource pack pushed by the server at [address] must be declined instead of applied. */
    fun shouldBlockPack(address: String?): Boolean =
        isEnabled() && settings.blockServerPack.value() && LegacyTexturesRules.isHypixelAddress(address)

    /** Logs a declined pack; the id and a short host are enough to tell sessions apart. */
    fun notePackBlocked(id: UUID, address: String?) {
        packsBlocked++
        diagnostics.event("PACK result=declined,id=$id,server=${(address ?: "?").take(64)}")
    }

    fun oldModelsActive(): Boolean = isEnabled() && settings.oldModels.value()

    /**
     * The stack to draw in place of [stack]: the same stack when it is not a pack item, its group is switched off, or the
     * module is off; otherwise a copy that points at the plain vanilla model (or a matching vanilla item for a
     * placeholder). Runs for every item drawn, so the untouched case does no allocation.
     */
    fun remap(stack: ItemStack): ItemStack {
        if (!oldModelsActive() || stack.isEmpty) return stack
        val model = stack.get(DataComponents.ITEM_MODEL)
        val hasCustomModelData = stack.has(DataComponents.CUSTOM_MODEL_DATA)
        if (!LegacyTexturesRules.isPackModel(model?.namespace, hasCustomModelData)) return stack
        val group = LegacyTexturesRules.groupOf(
            stack.has(DataComponents.EQUIPPABLE), stack.`is`(Items.PLAYER_HEAD),
            stack.has(DataComponents.WEAPON) || stack.has(DataComponents.TOOL),
        )
        if (!groupEnabled(group)) return stack

        val base = BuiltInRegistries.ITEM.getKey(stack.item)
        val fallback = LegacyTexturesRules.fallbackItem(ItemBackgrounds.info(stack).skyblockId, base.toString())
        val target = fallback?.let { Identifier.tryParse(it) } ?: stack.item.components().get(DataComponents.ITEM_MODEL) ?: base
        val copy = stack.copy()
        copy.set(DataComponents.ITEM_MODEL, target)
        copy.remove(DataComponents.CUSTOM_MODEL_DATA)
        itemsRemapped++
        val key = "$model->$target"
        if (modelsLogged.size < MAX_LOGGED_MODELS && modelsLogged.add(key)) {
            diagnostics.event("MODEL from=$model,to=$target,group=${group.name},customModelData=$hasCustomModelData,base=$base")
        }
        return copy
    }

    private fun groupEnabled(group: LegacyTexturesRules.Group): Boolean = when (group) {
        LegacyTexturesRules.Group.WEAPONS -> settings.weapons.value()
        LegacyTexturesRules.Group.ARMOR -> settings.armor.value()
        LegacyTexturesRules.Group.HEADS -> settings.heads.value()
        LegacyTexturesRules.Group.OTHER -> settings.other.value()
    }

    private fun summary(): String =
        "blockServerPack=${settings.blockServerPack.value()},oldModels=${settings.oldModels.value()}" +
            ",weapons=${settings.weapons.value()},armor=${settings.armor.value()},heads=${settings.heads.value()}" +
            ",other=${settings.other.value()},server=${Minecraft.getInstance()?.currentServer?.ip ?: "none"}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Legacy Textures")
        val blockServerPack: BooleanSetting = spec.toggle("blockServerPack")
        val oldModels: BooleanSetting = spec.toggle("oldModels")
        val weapons: BooleanSetting = spec.toggle("weapons")
        val armor: BooleanSetting = spec.toggle("armor")
        val heads: BooleanSetting = spec.toggle("heads")
        val other: BooleanSetting = spec.toggle("other")
        val all = spec.settings
    }

    companion object {
        private const val MAX_LOGGED_MODELS = 32

        @JvmField
        val INSTANCE = LegacyTexturesModule(Settings())
    }
}
