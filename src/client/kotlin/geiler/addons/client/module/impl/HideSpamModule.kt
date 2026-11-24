package geiler.addons.client.module.impl

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec

/**
 * Drops repetitive feedback lines from chat: ability cooldown, blocks in the way, ability damage, heal and Dungeon
 * Breaker, kill combo, teleport pad, mimic, autopet, Lottery and Sky Mall. Wording follows Skyblocker's chat filters
 * (LGPL-3.0, behavior only, regexes written independently). Chat only: action bar text is never touched.
 */
class HideSpamModule private constructor(private val s: Settings) : Module(
    "Hide Spam",
    "Hides repetitive ability, combo, teleport pad, mimic, autopet, Lottery and Sky Mall chat lines.",
    Category.MISCELLANEOUS,
    false,
    *s.all,
) {
    /** [id] is the setting id in `module-settings.json`. */
    enum class Line(val id: String) {
        COOLDOWN("cooldown"),
        BLOCKS("blocks"),
        DAMAGE("damage"),
        HEAL_BREAKER("healBreaker"),
        COMBO("combo"),
        TELEPORT_PAD("teleportPad"),
        MIMIC("mimic"),
        AUTOPET("autopet"),
        LOTTERY("lottery"),
        SKY_MALL("skyMall"),
    }

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 240)
    private val hidden = LongArray(Line.entries.size)

    override fun onEnable() {
        hidden.fill(0L)
        diagnostics.enable(flags())
    }

    override fun onDisable() {
        diagnostics.event("COUNTS ${counters()}")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "${flags()} ${counters()} suppressedEvents=$it" }
    }

    /** True when [plain] is a spam line whose toggle is on; the chat listener then drops the message. */
    fun shouldHide(plain: String): Boolean {
        if (!isEnabled()) return false
        val line = match(plain) ?: return false
        if (!s.toggles.getValue(line).value()) return false
        // shortcut: Skyblocker also needs the floor to have a mimic; here any dungeon run counts, upgrade if it hides too much.
        if (line == Line.MIMIC && !DungeonContextTracker.inDungeon()) return false
        if (hidden[line.ordinal] < Long.MAX_VALUE) hidden[line.ordinal]++
        diagnostics.event("HIDDEN line=$line count=${hidden[line.ordinal]}")
        return true
    }

    private fun flags(): String = Line.entries.joinToString(" ") { "${it.id}=${s.toggles.getValue(it).value()}" }

    private fun counters(): String = Line.entries.joinToString(" ") { "${it.id}Hidden=${hidden[it.ordinal]}" }

    /** Declared in `module-settings.json`; this module only binds behaviour to the setting ids. */
    private class Settings {
        val spec = ModuleSettingsSpec.of("Hide Spam")
        val toggles: Map<Line, BooleanSetting> = Line.entries.associateWith { spec.toggle(it.id) }
        val all: Array<BooleanSetting> = toggles.values.toTypedArray()
    }

    companion object {
        @JvmField
        val INSTANCE = HideSpamModule(Settings())

        private const val NUM = "[0-9][0-9,]*(?:\\.[0-9]+)?"
        private const val COMBAT_WISDOM = "☯"
        private val RULES: List<Pair<Line, Regex>> = listOf(
            Line.COOLDOWN to Regex("^(?:This ability is on cooldown for ${NUM}s\\.|No more charges, next one in ${NUM}s!)$"),
            Line.BLOCKS to Regex("^There are blocks in the way!$"),
            Line.DAMAGE to Regex("^Your (?:Implosion|Molten Wave|Spirit Sceptre) hit $NUM enem(?:y|ies) for $NUM damage\\.$"),
            Line.HEAL_BREAKER to Regex(
                "^(?:You healed yourself for $NUM health!|[A-Za-z0-9_]{2,16} healed you for $NUM health!" +
                    "|A mystical force prevents you (?:from digging that block|digging in this room|digging there)!" +
                    "|You don't have enough charges to break this block right now!)$",
            ),
            Line.COMBO to Regex(
                "^(?:\\+[0-9]+ Kill Combo(?: \\+[0-9]+(?:% \\S+ Magic Find| coins per kill|$COMBAT_WISDOM Combat Wisdom))?" +
                    "|Your Kill Combo has expired! You reached a [0-9]+ Kill Combo!)$",
            ),
            Line.TELEPORT_PAD to Regex(
                "^(?:Warped from the .* Teleport Pad to the .* Teleport Pad!|This Teleport Pad does not have a destination set!)$",
            ),
            // Skyblocker's wording: also matches a party line ending in the phrase, such as "Party > Name: Mimic Killed!".
            Line.MIMIC to Regex("^.*?(?:Mimic dead!?|Mimic Killed!|\\\$SKYTILS-DUNGEON-SCORE-MIMIC\\\$)$"),
            Line.AUTOPET to Regex("^Autopet equipped your .*! VIEW RULE$"),
            Line.LOTTERY to Regex(
                "^(?:New day! Your Lottery buff changed!" +
                    "|New buff: Gain .+ (?:Fig Fortune|Mangrove Fortune|Helix Fortune|Sweep)\\." +
                    "|You can disable this messaging by toggling Lottery in your /hotf!)$",
            ),
            Line.SKY_MALL to Regex(
                "^(?:New day! Your Sky Mall buff changed!" +
                    "|New buff: (?:Gain )?.+ (?:Mining Speed|Mining Fortune|more Powder while mining|Pickaxe Ability cooldowns" +
                    "|chance to find Golden and Diamond Goblins|Titanium drops)\\." +
                    "|You can disable this messaging by toggling Sky Mall in your /hotm!)$",
            ),
        )

        /** Pure rule: which spam line [plain] (trimmed, formatting stripped) is, or null for anything else. */
        @JvmStatic
        fun match(plain: String): Line? = RULES.firstOrNull { it.second.matches(plain) }?.first
    }
}
