package geiler.addons.client.module.impl

import geiler.addons.client.config.GeilerAddonsLog
import geiler.addons.client.cosmetics.BodyShape
import geiler.addons.client.cosmetics.CosmeticLoadout
import geiler.addons.client.cosmetics.CosmeticPiece
import geiler.addons.client.cosmetics.CosmeticProfileCodec
import geiler.addons.client.cosmetics.CosmeticSlot
import geiler.addons.client.cosmetics.CosmeticStyle
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ColorSetting
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.presentation.PlayerPresentationProfileStore
import geiler.addons.client.presentation.PlayerPresentationProfiles
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.Locale

/**
 * Client-side cosmetics (wings, horns, ears, halo, tails, auras, body shape). Not a module card: the Click GUI's
 * top-bar wing button opens [geiler.addons.client.gui.CosmeticsScreen], and these settings only store the
 * choices. Your own choices are seen only by you; cosmetics the owner assigns in the hosted player profile are
 * seen by everyone with the mod.
 */
class CosmeticsModule private constructor(private val settings: Settings) :
    Module(
        "Cosmetics",
        "Wings, horns, cat ears, a halo, tails, auras and body shape on your player.",
        Category.VISUAL,
        false,
        *settings.all().toTypedArray(),
    ) {
    private var diagnosticTick = 0L
    private var diagnosticsStarted = false
    private var lastLocalStatus: String? = null
    private var hostedSource: PlayerPresentationProfiles? = null
    private var hosted: Map<String, CosmeticLoadout> = emptyMap()
    private var renderedPlayers = 0
    private var renderFailures = 0
    private val reportedFailures = HashSet<String>()

    init {
        group(
            *(settings.slots.map { SettingGroup(it.slot.label, *it.all().toTypedArray()) } +
                SettingGroup("Body", settings.chest, settings.waist) + SettingGroup("Other Players", settings.showHosted))
                .toTypedArray(),
        )
    }

    // No card and no on/off switch: equipping a cosmetic in the menu is the switch.
    override fun showsToggleControl(): Boolean = false

    override fun showsKeybindControl(): Boolean = false

    fun slots(): List<SlotSettings> = settings.slots

    fun slot(slot: CosmeticSlot): SlotSettings = settings.slots.first { it.slot == slot }

    fun chestSetting(): NumberSetting = settings.chest

    fun waistSetting(): NumberSetting = settings.waist

    fun showHostedSetting(): BooleanSetting = settings.showHosted

    fun tick() {
        if (settings.showHosted.value()) PlayerPresentationProfileStore.INSTANCE.refreshIfDue()
        if (!diagnosticsStarted) {
            // Always on, like Theme: there is no module switch to tie the trace to.
            diagnosticsStarted = true
            GeilerAddonsLog.setModuleDiagnosticsEnabled(Category.VISUAL, name(), true)
        }
        diagnosticTick++
        val off = settings.slots.filter { it.isOff() }.joinToString(",") { it.slot.id }
        val local = "LOCAL ${localLoadout()} hosted=${settings.showHosted.value()} off=[$off]"
        if (local != lastLocalStatus) {
            lastLocalStatus = local
            write(local)
        }
        if (diagnosticTick % HEARTBEAT_TICKS == 0L && (renderedPlayers > 0 || renderFailures > 0)) {
            write("RENDER players=$renderedPlayers failures=$renderFailures hostedPlayers=${hosted.size}")
            renderedPlayers = 0
            renderFailures = 0
        }
    }

    /**
     * Your own settings win per slot; slots you left at None fall back to what the hosted profile gives you, and
     * slots you switched off ([OFF]) stay empty even when the hosted profile fills them.
     */
    fun loadoutFor(playerName: String?): CosmeticLoadout {
        if (playerName == null) return CosmeticLoadout.EMPTY
        val remote = if (settings.showHosted.value()) hostedLoadouts()[playerName.lowercase(Locale.ROOT)] else null
        val own = Minecraft.getInstance().user.name
        if (!own.equals(playerName, ignoreCase = true)) return remote ?: CosmeticLoadout.EMPTY
        return merge(localLoadout(), remote, settings.slots.filter { it.isOff() }.mapTo(HashSet()) { it.slot })
    }

    /** The piece the hosted profile gives you in [slot] (shown while Show Hosted Cosmetics is on), or null. */
    fun hostedPiece(slot: CosmeticSlot): CosmeticPiece? {
        if (!settings.showHosted.value()) return null
        // No client in the offline checks; no hosted piece then.
        val own = Minecraft.getInstance()?.user?.name ?: return null
        return hostedLoadouts()[own.lowercase(Locale.ROOT)]?.piece(slot)
    }

    /** What you wear in [slot]: your own pick, else the hosted piece unless you switched the slot off. */
    fun worn(slot: CosmeticSlot): CosmeticStyle? {
        val settings = slot(slot)
        return settings.selected() ?: if (settings.isOff()) null else hostedPiece(slot)?.style
    }

    /** Takes [slot] off: switched off when a hosted piece would otherwise show, else plain None. */
    fun takeOff(slot: CosmeticSlot) {
        slot(slot).style.setValue(if (hostedPiece(slot) != null) OFF else NONE)
    }

    /** For the torso and armor mixin: the body shape of the player with this entity id, or null. */
    fun bodyShape(entityId: Int): BodyShape? {
        val player = Minecraft.getInstance().level?.getEntity(entityId) as? Player ?: return null
        return loadoutFor(player.gameProfile.name).body()
    }

    fun localLoadout(): CosmeticLoadout =
        CosmeticLoadout.of(settings.slots.mapNotNull { it.piece() }, BodyShape(settings.chest.value(), settings.waist.value()))

    /** Menu equip toggle: wears [style], or takes it off if it is already worn. Returns whether it is now worn. */
    fun toggle(style: CosmeticStyle): Boolean {
        val wear = worn(style.slot) != style
        if (wear) slot(style.slot).style.setValue(style.id) else takeOff(style.slot)
        write("UI ${if (wear) "EQUIP" else "UNEQUIP"} ${style.id}")
        return wear
    }

    fun note(line: String) = write(line)

    fun noteRendered() {
        renderedPlayers++
    }

    /** Render errors are logged once per distinct message so a broken frame cannot flood the log. */
    fun noteRenderFailure(error: Throwable) {
        renderFailures++
        val key = "${error.javaClass.simpleName}: ${error.message}"
        if (reportedFailures.size < MAX_REPORTED_FAILURES && reportedFailures.add(key)) write("RENDER_FAILURE $key")
    }

    private fun hostedLoadouts(): Map<String, CosmeticLoadout> {
        val profiles = PlayerPresentationProfileStore.INSTANCE.current()
        if (profiles === hostedSource) return hosted
        val rebuilt = HashMap<String, CosmeticLoadout>()
        val problems = ArrayList<String>()
        for ((key, entry) in profiles.entries()) {
            val parsed = CosmeticProfileCodec.parse(entry.effectMetadata())
            if (!parsed.loadout.isEmpty()) rebuilt[key.lowercase(Locale.ROOT)] = parsed.loadout
            // Player names stay out of the log, like Player Size; the problem text names the slot and field.
            for (problem in parsed.problems) if (problems.size < MAX_REPORTED_PROBLEMS) problems.add(problem)
        }
        hostedSource = profiles
        hosted = rebuilt
        write("HOSTED players=${rebuilt.size} problems=${problems.size} ${problems.joinToString("; ")}")
        return rebuilt
    }

    private fun write(line: String) {
        if (!diagnosticsStarted) return
        GeilerAddonsLog.write(Category.VISUAL, name(), diagnosticTick, line.take(MAX_LINE))
    }

    /** One slot's settings: style, colors (the style's own unless overridden), size, offsets and speed. */
    class SlotSettings(val slot: CosmeticSlot, val speedLabel: String) {
        private val styles = slot.styles()
        val style = ChoiceSetting(
            "${slot.label} Style", NONE,
            mapOf(NONE to "None", OFF to "Off (hides a hosted piece)") + styles.associate { it.id to it.label },
            NONE, OFF, *styles.map { it.id }.toTypedArray(),
        )
        val styleColors = BooleanSetting("${slot.label} Style Colors", "Use Style Colors", true)
        val color = ColorSetting("${slot.label} Color", "Color", 255, 255, 255)
        val color2 = ColorSetting("${slot.label} Color 2", "Second Color", 200, 200, 200)
        val size = NumberSetting("${slot.label} Size", "Size", 0.25f, 2.0f, slot.defaultSize)
        val up = NumberSetting("${slot.label} Height", "Height", -8f, 8f, slot.defaultUp)
        val back = NumberSetting("${slot.label} Depth", "Depth", -4f, 6f, slot.defaultBack)
        val speed = NumberSetting("${slot.label} Speed", speedLabel, 0f, 3f, slot.defaultSpeed)

        fun all(): List<Setting> = listOf(style, styleColors, color, color2, size, up, back, speed)

        fun selected(): CosmeticStyle? = CosmeticStyle.byId(slot, style.value())

        /** Switched off on purpose: no piece here, not even a hosted one. */
        fun isOff(): Boolean = style.value() == OFF

        fun piece(): CosmeticPiece? = selected()?.let { piece(it) }

        /** The piece as [style] would be worn with this slot's settings (the menu previews every style this way). */
        fun piece(style: CosmeticStyle): CosmeticPiece {
            val own = !styleColors.value()
            return CosmeticPiece(
                style,
                if (own) color.opaqueArgb() and 0xFFFFFF else style.color,
                if (own) color2.opaqueArgb() and 0xFFFFFF else style.color2,
                size.value(), up.value(), back.value(), speed.value(),
            )
        }
    }

    private class Settings {
        val slots = listOf(
            SlotSettings(CosmeticSlot.WINGS, "Flap Speed"),
            SlotSettings(CosmeticSlot.HEAD, "Twitch Speed"),
            SlotSettings(CosmeticSlot.HALO, "Spin Speed"),
            SlotSettings(CosmeticSlot.TAIL, "Sway Speed"),
            SlotSettings(CosmeticSlot.AURA, "Drift Speed"),
            SlotSettings(CosmeticSlot.HAT, "Motion Speed"),
            SlotSettings(CosmeticSlot.FACE, "Shine Speed"),
            SlotSettings(CosmeticSlot.BACK, "Sway Speed"),
            SlotSettings(CosmeticSlot.PET, "Animation Speed"),
            SlotSettings(CosmeticSlot.SKIRT, "Sway Speed"),
            SlotSettings(CosmeticSlot.TRAIL, "Animation Speed"),
            SlotSettings(CosmeticSlot.CAPE, "Shimmer Speed"),
        )
        val chest = NumberSetting("Body Chest", "Chest", 0f, 1f, 0f)
        val waist = NumberSetting("Body Waist", "Waist", 0f, 1f, 0f)
        val showHosted = BooleanSetting("Show Hosted Cosmetics", "Show Hosted Cosmetics", true)

        fun all(): List<Setting> = slots.flatMap { it.all() } + chest + waist + showHosted
    }

    companion object {
        /** No own pick: a hosted piece for this slot shows. */
        const val NONE = "none"

        /** Switched off: hides the hosted piece for this slot too. */
        const val OFF = "off"
        private const val HEARTBEAT_TICKS = 200L
        private const val MAX_LINE = 256
        private const val MAX_REPORTED_FAILURES = 8
        private const val MAX_REPORTED_PROBLEMS = 8

        @JvmField
        val INSTANCE = CosmeticsModule(Settings())

        /** Per slot: the local piece if set, else the hosted one unless the slot is in [off]; the same for the body shape. */
        @JvmStatic
        fun merge(local: CosmeticLoadout, remote: CosmeticLoadout?, off: Set<CosmeticSlot>): CosmeticLoadout {
            if (remote == null || remote.isEmpty()) return local
            return CosmeticLoadout.of(
                CosmeticSlot.entries.mapNotNull { local.piece(it) ?: if (it in off) null else remote.piece(it) },
                local.body() ?: remote.body(),
            )
        }
    }
}
