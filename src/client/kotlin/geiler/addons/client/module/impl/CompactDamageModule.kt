package geiler.addons.client.module.impl

import geiler.addons.client.module.Category
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.WeakHashMap

/**
 * Shortens the damage numbers Skyblock shows above mobs (Skyblocker's Compact Damage, LGPL-3.0): `3,825` becomes
 * `3.825k`, crits get a gradient. Settings and defaults follow Skyblocker; written independently.
 */
class CompactDamageModule private constructor(private val s: Settings) : Module(
    "Compact Damage",
    "Shortens damage numbers and colors critical hits with a gradient.",
    Category.VISUAL,
    false,
    s.precision, s.normalColor, s.critStart, s.critEnd,
) {
    private val names = WeakHashMap<Entity, Component>()
    private var rewritten = 0L
    private var failures = 0L
    private val diagnostics = ModuleDiagnostics(Category.VISUAL, { name() }, 6, 100L, 300)

    override fun settingTooltip(setting: Setting): String? =
        if (setting === s.precision) "The number of significant digits." else null

    override fun onEnable() {
        names.clear()
        diagnostics.enable(
            "precision=${s.precision.intValue()} normal=${s.normalColor.hex()} " +
                "gradient=${s.critStart.hex()}..${s.critEnd.hex()}",
        )
    }

    override fun onDisable() {
        names.clear()
        diagnostics.disable()
    }

    /** Called after an entity-data update was applied to [entity]; only invisible named armor stands qualify. */
    fun onArmorStand(entity: ArmorStand) {
        if (!isEnabled()) return
        diagnostics.advance()
        if (!entity.isInvisible || !entity.hasCustomName() || !entity.isCustomNameVisible) {
            names.remove(entity)
            return
        }
        val name = entity.customName ?: return
        try {
            val compact = CompactDamageLogic.compact(
                name, s.precision.intValue(), rgb(s.normalColor), rgb(s.critStart), rgb(s.critEnd),
            )
            if (compact == null) {
                names.remove(entity)
                return
            }
            names[entity] = compact
            rewritten++
            diagnostics.event("COMPACTED from=${name.string.take(24)} to=${compact.string.take(24)}")
        } catch (error: RuntimeException) {
            failures++
            names.remove(entity)
            diagnostics.event("FAILED name=${name.string.take(24)} error=${error.javaClass.simpleName}")
        }
        diagnostics.status { "tracked=${names.size} rewritten=$rewritten failures=$failures suppressedEvents=$it" }
    }

    /** The name tag to draw for [entity]: the shortened damage number, else [original]. */
    fun nameFor(entity: Entity, original: Component?): Component? =
        if (isEnabled() && entity is ArmorStand) names[entity] ?: original else original

    private fun rgb(color: ColorSetting) = (color.red() shl 16) or (color.green() shl 8) or color.blue()

    class Settings {
        val precision = NumberSetting("Precision", 3f, 10f, 4f, true)
        val normalColor = ColorSetting("Normal Damage Color", 255, 255, 255)
        val critStart = ColorSetting("Crit Damage Gradient Start Color", 255, 255, 85)
        val critEnd = ColorSetting("Crit Damage Gradient End Color", 255, 85, 85)
    }

    companion object {
        @JvmField
        val INSTANCE = CompactDamageModule(Settings())
    }
}
