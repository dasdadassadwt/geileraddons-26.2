package geiler.addons.client.waypoint

import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleSettingsSpec
import geiler.addons.client.module.impl.ModuleDiagnostics
import geiler.addons.client.utility.PlayerAlerts
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Chat Waypoints: short-lived waypoints created from coordinates players send in chat
 * (`x: 1, y: 2, z: 3`, Odin / NoFrills behaviour). Independent of the saved Waypoints.
 */
class ChatWaypointsModule private constructor() : Module(
    "Chat Waypoints",
    "Show a waypoint for coordinates sent in party, guild or all chat.",
    Category.MISCELLANEOUS,
    false,
    *spec.settings.toTypedArray(),
) {
    private val chatParty = spec.toggle("chatParty")
    private val chatGuild = spec.toggle("chatGuild")
    private val chatAll = spec.toggle("chatAll")
    private val chatPersonal = spec.toggle("chatPersonal")
    private val chatSeconds = spec.slider("chatSeconds")
    private val chatColor = spec.color("chatColor")
    private val showDistance = spec.toggle("showDistance")
    private val textScale = spec.slider("textScale")
    private val maxDistance = spec.slider("maxDistance")

    private val chatBook = ChatWaypointBook()
    private var targets: List<WaypointRenderer.Target> = emptyList()
    private var lastLevel: Any? = null
    private var added = 0
    private var ignored = 0
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 360)

    init {
        group(*spec.groups.toTypedArray())
    }

    override fun onEnable() {
        added = 0
        ignored = 0
        diagnostics.enable("party=${chatParty.value()} guild=${chatGuild.value()} all=${chatAll.value()} personal=${chatPersonal.value()}")
    }

    override fun onDisable() {
        chatBook.clear()
        targets = emptyList()
        diagnostics.event("DISABLE added=$added ignored=$ignored")
        diagnostics.disable()
    }

    /** Hypixel chat line without colour codes. */
    fun onChatMessage(plain: String) {
        if (!isEnabled()) return
        val parsed = ChatWaypointParser.parse(plain) ?: return
        val client = Minecraft.getInstance()
        val allowed = when (parsed.channel) {
            ChatWaypointParser.Channel.PARTY -> chatParty.value()
            ChatWaypointParser.Channel.GUILD -> chatGuild.value()
            ChatWaypointParser.Channel.ALL -> chatAll.value()
        }
        val own = parsed.sender.equals(client.player?.name?.string, ignoreCase = true)
        if (!allowed || (own && !chatPersonal.value())) {
            ignored++
            diagnostics.event("CHAT_IGNORED channel=${parsed.channel} own=$own allowed=$allowed")
            return
        }
        chatBook.put(parsed.sender, parsed.x, parsed.y, parsed.z, System.currentTimeMillis(), chatSeconds.intValue() * 1000L)
        added++
        diagnostics.event("CHAT_WAYPOINT channel=${parsed.channel} x=${parsed.x} y=${parsed.y} z=${parsed.z} total=${chatBook.size()}")
        PlayerAlerts.chat("§6[GeilerAddons] §aWaypoint from §e${parsed.sender}§a at §6${parsed.x}§r, §3${parsed.y}§r, §d${parsed.z}")
    }

    /** Called every client tick while the module is enabled. */
    fun tick() {
        if (!isEnabled()) return
        val client = Minecraft.getInstance()
        val level = client.level
        if (client.player == null || level == null) {
            targets = emptyList()
            return
        }
        diagnostics.advance()
        if (lastLevel !== level) {
            lastLevel = level
            chatBook.clear()
        }
        val argb = chatColor.argb()
        val now = System.currentTimeMillis()
        val built = ArrayList<WaypointRenderer.Target>()
        for (entry in chatBook.active(now)) {
            built.add(
                WaypointRenderer.Target(
                    entry.x, entry.y, entry.z, entry.sender, argb and 0xFFFFFF, ((argb ushr 24) and 0xFF) / 255f,
                    WaypointType.WAYPOINT, true, OrderedWaypointRules.NONE, 0, true,
                ),
            )
        }
        targets = built
        diagnostics.status { "enabled=true targets=${built.size} added=$added ignored=$ignored suppressedEvents=$it" }
    }

    fun render(context: LevelRenderContext) {
        if (!isEnabled()) return
        WaypointRenderer.draw(context, targets, maxDistance.value().toDouble(), 0, 0f)
    }

    fun renderHud(graphics: GuiGraphicsExtractor) {
        if (!isEnabled()) return
        WaypointRenderer.drawLabels(graphics, targets, maxDistance.value().toDouble(), showDistance.value(), textScale.value())
    }

    companion object {
        private val spec = ModuleSettingsSpec.of("Chat Waypoints")

        @JvmField
        val INSTANCE = ChatWaypointsModule()
    }
}
