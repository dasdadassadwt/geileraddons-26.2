package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.NumberSetting
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot

/** Fades or hides the armor players wear, per slot, for your own armor and other players' armor. */
class ArmorTransparencyModule private constructor(private val settings: Settings) : Module(
    "Armor Transparency",
    "Fades or hides worn armor per slot, on you and on other players.",
    Category.VISUAL,
    false,
    *settings.all.toTypedArray(),
) {
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 8, 100L, 400)
    private val seen = LongArray(EquipmentSlot.values().size)
    private var hidden = 0L
    private var faded = 0L
    private var lastLogged = ""

    init {
        group(*settings.spec.groups.toTypedArray())
    }

    override fun onEnable() {
        seen.fill(0L)
        hidden = 0L
        faded = 0L
        lastLogged = ""
        diagnostics.enable(summary())
    }

    override fun onDisable() {
        diagnostics.event("DISABLE ${summary()},hiddenLayers=$hidden,fadedLayers=$faded")
        diagnostics.disable()
    }

    /** Called by the shared client tick registration for the bounded status heartbeat. */
    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "${summary()},hiddenLayers=$hidden,fadedLayers=$faded,suppressedEvents=$it" }
    }

    /**
     * Opacity (0-1) of the armor in [slot] on the player with entity id [entityId]; 1 means untouched. Called by the
     * armor layer mixin once per armor layer drawn, so it stays allocation-free.
     */
    fun opacityFor(slot: EquipmentSlot, entityId: Int): Float {
        if (!isEnabled()) return 1.0f
        val percent = when (slot) {
            EquipmentSlot.HEAD -> settings.helmet
            EquipmentSlot.CHEST -> settings.chestplate
            EquipmentSlot.LEGS -> settings.leggings
            EquipmentSlot.FEET -> settings.boots
            else -> return 1.0f
        }.intValue()
        val self = Minecraft.getInstance().player?.id == entityId
        val opacity = ArmorTransparencyRules.opacity(percent, self, settings.ownArmor.value(), settings.otherPlayers.value())
        if (opacity < 1.0f) note(slot, self, opacity)
        return opacity
    }

    private fun note(slot: EquipmentSlot, self: Boolean, opacity: Float) {
        seen[slot.ordinal]++
        if (opacity <= 0.0f) hidden++ else faded++
        // One line per change of slot/wearer/opacity, never per frame.
        val key = "${slot.name}:$self:$opacity"
        if (key == lastLogged) return
        lastLogged = key
        diagnostics.event("LAYER slot=${slot.name},wearer=${if (self) "self" else "other"},opacity=$opacity")
    }

    private fun summary(): String =
        "helmet=${settings.helmet.intValue()},chestplate=${settings.chestplate.intValue()}" +
            ",leggings=${settings.leggings.intValue()},boots=${settings.boots.intValue()}" +
            ",ownArmor=${settings.ownArmor.value()},otherPlayers=${settings.otherPlayers.value()}"

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Armor Transparency")
        val helmet: NumberSetting = spec.slider("helmet")
        val chestplate: NumberSetting = spec.slider("chestplate")
        val leggings: NumberSetting = spec.slider("leggings")
        val boots: NumberSetting = spec.slider("boots")
        val ownArmor: BooleanSetting = spec.toggle("ownArmor")
        val otherPlayers: BooleanSetting = spec.toggle("otherPlayers")
        val all = spec.settings
    }

    companion object {
        @JvmField
        val INSTANCE = ArmorTransparencyModule(Settings())
    }
}
