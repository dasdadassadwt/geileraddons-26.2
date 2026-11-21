package geiler.addons.client.module.impl

import geiler.addons.client.enchanting.EnchantRules
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import java.util.Optional

/**
 * Recolours enchantments in item tooltips by level, after SkyHanni's enchant parser: a maxed enchant is perfect
 * (rainbow by default), above the good level great, the good level good, anything lower poor, and ultimates their own
 * colour. Only enchants known to `data/skyblock-enchants.json` are touched.
 */
class EnchantColorsModule private constructor() : Module(
    "Enchant Colors",
    "Colours enchantments in item tooltips by how close their level is to the maximum.",
    Category.ENCHANTING,
    false,
    *spec.settings.toTypedArray(),
) {
    private val rainbow = spec.toggle("rainbowPerfect")
    private val boldPerfect = spec.toggle("boldPerfect")
    private val perfect = spec.color("perfect")
    private val great = spec.color("great")
    private val good = spec.color("good")
    private val poor = spec.color("poor")
    private val ultimate = spec.color("ultimate")

    private val diagnostics = ModuleDiagnostics(Category.ENCHANTING, { name() }, 8, 100L, 300)
    private var lastStack: ItemStack? = null
    private var tooltips = 0L
    private var recoloured = 0L

    private fun settingsText() = "rainbow=${rainbow.value()};bold=${boldPerfect.value()};perfect=${perfect.hex()};great=${great.hex()};" +
        "good=${good.hex()};poor=${poor.hex()};ultimate=${ultimate.hex()}"

    override fun onEnable() {
        tooltips = 0
        recoloured = 0
        lastStack = null
        diagnostics.enable(settingsText())
    }

    override fun onDisable() = diagnostics.disable()

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "tooltips=$tooltips recoloured=$recoloured suppressedEvents=$it" }
    }

    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || stack.isEmpty) return
        val data = EnchantRules.data()
        if (data.enchants.isEmpty()) return
        tooltips++
        var changed = 0
        for (i in lines.indices) {
            val line = lines[i]
            val tokens = EnchantRules.tokens(line.string, data)
            if (tokens.isEmpty()) continue
            lines[i] = recolour(line, tokens)
            changed += tokens.size
        }
        recoloured += changed
        if (changed > 0 && stack !== lastStack) {
            lastStack = stack
            diagnostics.event("TOOLTIP item=${stack.hoverName.string.take(40)} enchants=$changed")
        }
    }

    private fun recolour(line: Component, tokens: List<EnchantRules.Token>): Component {
        val text = StringBuilder()
        val styles = ArrayList<Style>()
        line.visit({ style, part ->
            for (c in part) {
                text.append(c)
                styles.add(style)
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        val now = System.currentTimeMillis()
        for (token in tokens) {
            val tier = EnchantRules.tier(token.enchant, token.level)
            for (index in token.start until token.end) {
                val base = styles[index]
                styles[index] = when (tier) {
                    EnchantRules.Tier.PERFECT ->
                        if (rainbow.value()) base.withColor(TextColor.fromRgb(rainbowColor(now, index))).withBold(if (boldPerfect.value()) true else null)
                        else base.withColor(TextColor.fromRgb(perfect.opaqueArgb() and 0xFFFFFF)).withBold(if (boldPerfect.value()) true else null)
                    EnchantRules.Tier.ULTIMATE -> base.withColor(TextColor.fromRgb(ultimate.opaqueArgb() and 0xFFFFFF)).withBold(true)
                    EnchantRules.Tier.GREAT -> base.withColor(TextColor.fromRgb(great.opaqueArgb() and 0xFFFFFF))
                    EnchantRules.Tier.GOOD -> base.withColor(TextColor.fromRgb(good.opaqueArgb() and 0xFFFFFF))
                    EnchantRules.Tier.POOR -> base.withColor(TextColor.fromRgb(poor.opaqueArgb() and 0xFFFFFF))
                }
            }
        }
        val result = Component.empty()
        var start = 0
        while (start < text.length) {
            var end = start + 1
            while (end < text.length && styles[end] == styles[start]) end++
            result.append(Component.literal(text.substring(start, end)).withStyle(styles[start]))
            start = end
        }
        return result
    }

    private fun rainbowColor(now: Long, index: Int): Int =
        Mth.hsvToRgb((((now / 25) + index * 12) % 360) / 360f, 0.75f, 1f) and 0xFFFFFF

    companion object {
        private val spec = ModuleSettingsSpec.of("Enchant Colors")

        @JvmField
        val INSTANCE = EnchantColorsModule()

        init {
            // Colours first, so Missing Enchants (registered after it) appends its line untouched.
            ItemTooltipCallback.EVENT.register { stack, _, _, lines -> INSTANCE.onTooltip(stack, lines) }
        }
    }
}

/** While Shift is held, lists the enchants an item could still take (from its type line) in gray under its tooltip. */
class MissingEnchantsModule private constructor() : Module(
    "Missing Enchants",
    "Hold Shift over an item to see the enchantments it could still have.",
    Category.ENCHANTING,
    false,
    *spec.settings.toTypedArray(),
) {
    private val color = spec.color("color")
    private val diagnostics = ModuleDiagnostics(Category.ENCHANTING, { name() }, 8, 100L, 300)
    private var lastStack: ItemStack? = null
    private var shown = 0L

    override fun onEnable() {
        shown = 0
        lastStack = null
        diagnostics.enable("color=${color.hex()}")
    }

    override fun onDisable() = diagnostics.disable()

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "shown=$shown suppressedEvents=$it" }
    }

    fun onTooltip(stack: ItemStack, lines: MutableList<Component>) {
        if (!isEnabled() || stack.isEmpty) return
        if (!Minecraft.getInstance().hasShiftDown()) return
        val data = EnchantRules.data()
        val type = EnchantRules.itemType(lines.map { it.string }, data) ?: return
        val present = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty("enchantments")
            ?.keySet()?.map { it.lowercase() }?.toSet().orEmpty()
        val missing = EnchantRules.missing(type, present, data)
        if (missing.isEmpty()) return
        val style = Style.EMPTY.withColor(TextColor.fromRgb(color.opaqueArgb() and 0xFFFFFF))
        lines.add(Component.literal(" "))
        lines.add(Component.literal("Missing:").withStyle(style))
        for (row in missing.chunked(ROW_SIZE)) lines.add(Component.literal(row.joinToString(", ")).withStyle(style))
        shown++
        if (stack !== lastStack) {
            lastStack = stack
            diagnostics.event("MISSING type=$type present=${present.size} missing=${missing.size}")
        }
    }

    companion object {
        private const val ROW_SIZE = 4
        private val spec = ModuleSettingsSpec.of("Missing Enchants")

        @JvmField
        val INSTANCE = MissingEnchantsModule()

        init {
            ItemTooltipCallback.EVENT.register { stack, _, _, lines -> INSTANCE.onTooltip(stack, lines) }
        }
    }
}
