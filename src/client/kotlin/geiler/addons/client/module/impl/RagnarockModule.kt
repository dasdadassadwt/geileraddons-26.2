package geiler.addons.client.module.impl

import geiler.addons.client.inventory.ItemBackgrounds
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.Setting
import geiler.addons.client.utility.PlayerAlerts
import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents

/** Pure rules of the Ragnarock alerts. */
object RagnarockRules {
    const val AXE_ID = "RAGNAROCK_AXE"
    const val CAST_PITCH = 1.4920635f
    private val CANCEL = Regex("Ragnarock was cancelled due to (?:being hit|taking damage)!")
    private val STRENGTH = Regex("Strength: \\+(\\d+)")

    @JvmStatic
    fun isCancelLine(plain: String): Boolean = CANCEL.matches(plain)

    /** The base strength on the axe's lore, or 0. */
    @JvmStatic
    fun loreStrength(lore: List<String>): Int =
        lore.firstOrNull { it.startsWith("Strength:") }
            ?.let { STRENGTH.find(it)?.groups?.get(1)?.value?.toIntOrNull() } ?: 0

    /** Strength gained from a cast: 1.5 times the axe's strength, truncated. */
    @JvmStatic
    fun strengthGained(baseStrength: Int): Int = (baseStrength * 1.5).toInt()

    /** A cast is the exact pitch, with the axe in hand, and a wolf's death sound. */
    @JvmStatic
    fun isCast(pitch: Float, holdingAxe: Boolean, wolfDeathSound: Boolean): Boolean =
        pitch == CAST_PITCH && holdingAxe && wolfDeathSound
}

/** Alerts when Ragnarock is cast or cancelled, after Odin's Ragnarock. */
class RagnarockModule private constructor() : Module(
    "Ragnarock",
    "Alerts when you cast the Ragnarock or it gets cancelled.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val castAlert = spec.toggle("castAlert")
    private val cancelAlert = spec.toggle("cancelAlert")
    private val strengthGained = spec.toggle("strengthGained")
    private val announceStrength = spec.toggle("announceStrength")

    private var casts = 0L
    private var cancels = 0L
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 300)

    override fun onEnable() {
        casts = 0L
        cancels = 0L
        diagnostics.enable(
            "cast=${castAlert.value()} cancel=${cancelAlert.value()} strength=${strengthGained.value()} " +
                "announce=${announceStrength.value()}",
        )
    }

    override fun onDisable() {
        diagnostics.event("DISABLE casts=$casts cancels=$cancels")
        diagnostics.disable()
    }

    override fun isSettingVisible(setting: Setting): Boolean =
        if (setting === announceStrength) strengthGained.value() else true

    fun tick() {
        if (!isEnabled()) return
        diagnostics.advance()
        diagnostics.status { "enabled=true casts=$casts cancels=$cancels suppressedEvents=$it" }
    }

    fun onChatMessage(plain: String) {
        if (!isEnabled() || !cancelAlert.value() || !RagnarockRules.isCancelLine(plain)) return
        cancels++
        diagnostics.event("cancelled total=$cancels")
        PlayerAlerts.alert("§cRagnarock Cancelled!")
    }

    /** `handleSoundEvent` tail: the cast is recognised from the sound packet. */
    fun onSound(pitch: Float, sound: Identifier) {
        if (!isEnabled() || pitch != RagnarockRules.CAST_PITCH) return
        val held = Minecraft.getInstance().player?.mainHandItem ?: return
        val holding = !held.isEmpty && ItemBackgrounds.info(held).skyblockId == RagnarockRules.AXE_ID
        if (!RagnarockRules.isCast(pitch, holding, sound in wolfDeathSounds())) return
        casts++
        val gained = RagnarockRules.strengthGained(RagnarockRules.loreStrength(SkyblockItems.loreLines(held)))
        diagnostics.event("cast total=$casts strength=$gained")
        if (castAlert.value()) PlayerAlerts.alert("§aCasted Rag")
        if (strengthGained.value()) {
            PlayerAlerts.chat("§7Gained strength: §4$gained")
            if (announceStrength.value()) PlayerAlerts.command("pc Gained strength from Ragnarock: $gained")
        }
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Ragnarock")

        /** The death-sound ids of every wolf sound set, read once from the game's registry of sound sets. */
        private val wolfDeathCache: Set<Identifier> by lazy {
            SoundEvents.WOLF_SOUNDS.values.map { it.adultSounds().deathSound().value().location() }.toSet()
        }

        private fun wolfDeathSounds(): Set<Identifier> = wolfDeathCache

        @JvmField
        val INSTANCE = RagnarockModule()
    }
}
