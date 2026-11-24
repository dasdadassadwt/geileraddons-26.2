package geiler.addons.client.dungeon.score

import geiler.addons.client.dungeon.DungeonContextTracker
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket

/**
 * The live dungeon score for the current world: feeds [DungeonScoreCore] from the same packets Odin reads (tab rows,
 * team prefix/suffix, chat) and the player position. Always collecting, so values are already there when a consumer
 * (Score Titles, Map Info) turns on mid-run. Everything runs on the client thread.
 */
object DungeonScoreState {
    @JvmField
    val core = DungeonScoreCore()

    /** Called after the state was cleared for a new world. */
    @Volatile
    var onReset: (() -> Unit)? = null

    private var level: Any? = null
    private var generation = 0
    private var paulStatus = "not requested"

    init {
        core.onScore = { score -> onScore?.invoke(score) }
    }

    /** Called with every new score while the player is in a dungeon. */
    @Volatile
    var onScore: ((Int) -> Unit)? = null

    @JvmStatic
    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket?) {
        if (packet == null) return
        syncLevel()
        val rows = ArrayList<String>()
        for (entry in packet.entries()) {
            val name = entry?.displayName() ?: continue
            rows.add(name.string)
        }
        if (rows.isNotEmpty()) core.onTabRows(rows)
    }

    /** Team prefix and suffix joined without a separator, as Odin reads them. */
    @JvmStatic
    fun onTeamUpdate(text: String?) {
        if (text.isNullOrEmpty()) return
        syncLevel()
        if (core.onTeamText(text)) startPaulLookup()
    }

    @JvmStatic
    fun onChat(plain: String?) {
        if (plain.isNullOrBlank()) return
        syncLevel()
        if (DungeonContextTracker.inDungeon()) core.onChat(plain)
    }

    /** Boss-room flag from the player position, once per client tick while in a dungeon. */
    @JvmStatic
    fun tick(client: Minecraft?) {
        syncLevel()
        val player = client?.player ?: return
        if (!DungeonContextTracker.inDungeon()) return
        if (core.floor == null && core.adoptFloor(DungeonContextTracker.currentFloor())) startPaulLookup()
        val number = core.floor?.number() ?: return
        core.setBoss(DungeonScoreCore.inBossAt(number, player.x, player.z))
    }

    @JvmStatic
    fun paulStatus(): String = paulStatus

    private fun syncLevel() {
        val current = Minecraft.getInstance().level
        if (current === level) return
        level = current
        generation++
        paulStatus = "not requested"
        core.reset()
        onReset?.invoke()
    }

    private fun startPaulLookup() {
        val requested = generation
        paulStatus = "requested"
        DungeonPaulLookup.fetch { result ->
            Minecraft.getInstance().execute {
                if (requested != generation) return@execute
                paulStatus = when (result) {
                    null -> "failed"
                    else -> "paul=$result"
                }
                if (result != null) core.paul = result
            }
        }
    }
}
