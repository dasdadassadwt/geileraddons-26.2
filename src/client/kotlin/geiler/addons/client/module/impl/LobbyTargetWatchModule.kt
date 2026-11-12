package geiler.addons.client.module.impl

import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.Module
import geiler.addons.client.module.ModuleAction
import geiler.addons.client.module.Setting
import geiler.addons.client.module.SettingGroup
import geiler.addons.client.module.TextSetting
import geiler.addons.client.utility.LobbySniperStore
import geiler.addons.client.utility.LobbyTargetPresence
import geiler.addons.client.utility.LobbyTargetWatchState
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.UUID

/** Notifies when an enabled Lobby Sniper target is confirmed by the current connection roster. */
class LobbyTargetWatchModule private constructor(private val ui: Settings) : Module(
    MODULE_NAME,
    "Notifies when an enabled player is confirmed in your current lobby.",
    Category.MISCELLANEOUS,
    false,
    ui.watchedPlayers,
    ui.playerName,
    ui.addPlayer,
) {
    private val legacyWatchedPlayers = ui.watchedPlayers
    private val watchState = LobbyTargetWatchState()

    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { MODULE_NAME })
    private val rows = LinkedHashMap<String, PlayerRow>()
    private val playerNameField = ui.playerName
    private var lastConnection: Any? = null
    private var lastLevel: Any? = null
    private val rowDelete = HashMap<String, ModuleAction>()

    /** Per-player controls shown on the module page; the store stays the persisted source of truth. */
    private class PlayerRow(entry: LobbySniperStore.Entry) {
        val enabled = BooleanSetting("Enabled", entry.enabled())
        val title = TextSetting("Title", "Title (blank = default)", entry.title(), LobbySniperStore.MAX_TITLE_LENGTH)
    }

    override fun groups(): List<SettingGroup> {
        syncRows()
        val children = ArrayList<SettingGroup>()
        for (entry in LobbySniperStore.INSTANCE.entries()) {
            val row = rows.getOrPut(entry.playerName()) { PlayerRow(entry) }
            val delete = rowDelete.getOrPut(entry.playerName()) {
                ModuleAction("Delete Player", "Remove this player from the watch list.",
                    Runnable { remove(entry.playerName()) })
            }
            children.add(SettingGroup.switched(entry.playerName(), row.enabled, row.title, delete)
                .keyed("lobby-sniper:${entry.playerName().lowercase(Locale.ROOT)}"))
        }
        return listOf(
            SettingGroup("Add Player", ui.playerName, ui.addPlayer),
            SettingGroup("Players", null, false, emptyList<Setting>(), children, false, "lobby-sniper-players"),
        )
    }

    /** Writes row switch and title edits back to the store so they persist without a Save button. */
    private fun syncRows() {
        val store = LobbySniperStore.INSTANCE
        val live = HashSet<String>()
        for (entry in store.entries()) {
            live.add(entry.playerName())
            val row = rows[entry.playerName()] ?: continue
            val title = row.title.value().trim()
            if (row.enabled.value() != entry.enabled() || title != entry.title()) {
                if (store.put(entry.playerName(), row.enabled.value(), title)) {
                    diagnostics.event("PLAYER_EDITED name=${entry.playerName()} enabled=${row.enabled.value()} customTitle=${title.isNotEmpty()}")
                }
            }
        }
        rows.keys.retainAll(live)
        rowDelete.keys.retainAll(live)
    }

    private fun remove(name: String) {
        if (LobbySniperStore.INSTANCE.remove(name)) diagnostics.event("PLAYER_REMOVED name=$name")
        rows.remove(name)
        rowDelete.remove(name)
    }

    private fun addPlayer() {
        val name = playerNameField.value().trim()
        if (LobbySniperStore.INSTANCE.put(name, true, "")) {
            diagnostics.event("PLAYER_ADDED name=$name")
            playerNameField.setValue("")
        }
    }

    override fun isWorkInProgress(): Boolean = true

    /** Keep the old config key so existing enable state and comma-separated names survive migration. */
    override fun configName(): String = LEGACY_CONFIG_NAME

    override fun isSettingVisible(setting: Setting): Boolean = setting !== legacyWatchedPlayers

    override fun onEnable() {
        watchState.reset()
        lastConnection = null
        lastLevel = null
        diagnostics.enable("legacyTargetsImported=true")
    }

    override fun onDisable() {
        diagnostics.event("LIFECYCLE enabled=false presentTargets=${watchState.presentTargets().size}")
        onWorldChanged()
        diagnostics.disable()
    }

    /** Call once per client tick; names come from verified online profiles and matching player entities. */
    fun tick() {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute(this::tick)
            return
        }
        diagnostics.advance()
        syncRows()

        val store = LobbySniperStore.INSTANCE
        store.importLegacyTargets(legacyWatchedPlayers.value())
        if (!isEnabled()) {
            watchState.reset()
            lastConnection = null
            lastLevel = null
            return
        }

        val player = client.player
        val level = client.level
        val connection = player?.connection
        if (level == null || player == null || connection == null) {
            watchState.reset()
            lastConnection = null
            lastLevel = null
            statusIfDue(
                "no-client-context",
                targetCount = enabledTargetCount(store),
                listedCount = 0,
                onlineCount = 0,
                entityCount = 0,
                confirmedCount = 0,
                newlyPresent = 0,
                outputs = 0,
                titleOutputs = 0,
            )
            return
        }

        if (connection !== lastConnection || level !== lastLevel) watchState.reset()
        lastConnection = connection
        lastLevel = level

        val listedIds = HashSet<UUID>()
        val listedPlayers = connection.listedOnlinePlayers
        for (info in listedPlayers) listedIds.add(info.profile.id())
        val localId = player.uuid
        val candidates = ArrayList<LobbyTargetPresence.Candidate>()
        val onlinePlayers = connection.onlinePlayers
        for (info: PlayerInfo in onlinePlayers) {
            val profile = info.profile
            candidates.add(
                LobbyTargetPresence.Candidate(
                    profile.id(),
                    profile.name(),
                    true,
                    listedIds.contains(profile.id()),
                    false,
                    true,
                    profile.id() == localId,
                ),
            )
        }
        val levelPlayers = level.players()
        for (entity: AbstractClientPlayer in levelPlayers) {
            val profileId = entity.uuid
            val info = connection.getPlayerInfo(profileId) ?: continue
            val profile = info.profile
            candidates.add(
                LobbyTargetPresence.Candidate(
                    profile.id(),
                    profile.name(),
                    false,
                    listedIds.contains(profile.id()),
                    true,
                    true,
                    profile.id() == localId,
                ),
            )
        }

        val confirmedNames = LobbyTargetPresence.confirmedNames(candidates)
        val targets = store.entries().filter { it.enabled() }.map { it.playerName() }
        val gui = client.gui
        val newMatches = watchState.update(targets, confirmedNames, outputAvailable = gui != null)
        var outputs = 0
        var titleOutputs = 0
        if (gui != null) {
            for (name in newMatches) {
                val target = store.find(name)
                val customTitle = target?.title()?.takeIf { it.isNotBlank() }
                gui.hud.getChat().addClientSystemMessage(
                    Component.literal("[Lobby Sniper] $name is present in this lobby."),
                )
                gui.hud.setTimes(0, TITLE_DURATION_TICKS, 0)
                gui.hud.setTitle(Component.literal(customTitle ?: DEFAULT_TITLE))
                gui.hud.setSubtitle(Component.literal("$name is present in this lobby."))
                outputs++
                titleOutputs++
                matchDiagnostic("chat+title", customTitle = customTitle != null, durationTicks = TITLE_DURATION_TICKS)
            }
        }

        statusIfDue(
            if (gui == null) "gui-unavailable" else "active",
            targetCount = targets.size,
            listedCount = listedPlayers.size,
            onlineCount = onlinePlayers.size,
            entityCount = levelPlayers.size,
            confirmedCount = confirmedNames.size,
            newlyPresent = newMatches.size,
            outputs = outputs,
            titleOutputs = titleOutputs,
        )
    }

    fun onWorldChanged() {
        val client = Minecraft.getInstance()
        if (!client.isSameThread) {
            client.execute(this::onWorldChanged)
            return
        }
        watchState.reset()
        lastConnection = null
        lastLevel = null
        if (isEnabled()) diagnostics.event("WORLD_CHANGED presenceReset=true")
    }

    private fun enabledTargetCount(store: LobbySniperStore): Int =
        store.entries().count { it.enabled() }

    private fun statusIfDue(
        reason: String,
        targetCount: Int,
        listedCount: Int,
        onlineCount: Int,
        entityCount: Int,
        confirmedCount: Int,
        newlyPresent: Int,
        outputs: Int,
        titleOutputs: Int,
    ) = diagnostics.status {
        "enabled=${isEnabled()} reason=$reason targets=$targetCount listed=$listedCount " +
            "onlineProfiles=$onlineCount loadedPlayers=$entityCount confirmed=$confirmedCount " +
            "present=${watchState.presentTargets().size} new=$newlyPresent outputs=$outputs " +
            "titleOutputs=$titleOutputs pendingOutputs=${watchState.pendingTargetCount()}"
    }

    private fun matchDiagnostic(output: String, customTitle: Boolean, durationTicks: Int = 0) =
        diagnostics.event("MATCH output=$output customTitle=$customTitle titleDurationTicks=$durationTicks")

    private class Settings {
        val watchedPlayers = TextSetting("Watched Players", "", 512)
        val playerName = TextSetting("Player Name", "Minecraft username", "", 16)
        val addPlayer = ModuleAction(
            "Add Player",
            "Watch this player. Each player below has its own switch, optional title and Delete.",
            Runnable { INSTANCE.addPlayer() },
        )
    }

    companion object {
        private const val MODULE_NAME = "Lobby Sniper"
        private const val LEGACY_CONFIG_NAME = "Lobby Target Watch"
        private const val TITLE_DURATION_TICKS = 60
        private const val DEFAULT_TITLE = "Lobby Sniper"

        @JvmField
        val INSTANCE = LobbyTargetWatchModule(Settings())
    }
}
