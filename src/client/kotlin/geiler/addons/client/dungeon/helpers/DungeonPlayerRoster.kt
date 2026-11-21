package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.DungeonClass
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID

/** Tab roster preserving Odin's first-seen order and packet update behavior. */
object DungeonPlayerRoster {
    private val players = LinkedHashMap<String, DungeonPlayerMap.TabPlayer>()
    private val nameByProfile = LinkedHashMap<UUID, String>()
    private val profileByName = LinkedHashMap<String, UUID>()
    private var level: ClientLevel? = null

    @JvmStatic
    fun onPlayerInfoUpdate(packet: ClientboundPlayerInfoUpdatePacket?) {
        if (packet == null) return
        val client = Minecraft.getInstance()
        if (client.level !== level) observeLevel(client.level)
        for (entry in packet.entries()) {
            if (entry == null) continue
            val displayName = entry.displayName() ?: continue
            updateRow(entry.profileId(), displayName.getString())
        }
    }

    @JvmStatic
    fun onPlayerInfoRemove(packet: ClientboundPlayerInfoRemovePacket?) {
        if (packet != null) removeProfiles(packet.profileIds())
    }

    @JvmStatic
    fun observeLevel(nextLevel: ClientLevel?) {
        if (level === nextLevel) return
        level = nextLevel
        reset()
    }

    @JvmStatic
    fun reset() {
        players.clear()
        nameByProfile.clear()
        profileByName.clear()
    }

    @JvmStatic
    fun onDisconnect() {
        level = null
        reset()
    }

    @JvmStatic
    fun updateRows(rows: Iterable<String?>?) {
        if (rows == null) return
        for (text in rows) {
            val parsed = DungeonMapGrid.dungeonPlayerFromTabLine(text) ?: continue
            val name = parsed.name?.takeIf { it.isNotBlank() } ?: continue
            val syntheticProfile = UUID.nameUUIDFromBytes(
                name.lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8),
            )
            updateParsedRow(syntheticProfile, parsed)
        }
    }

    @JvmStatic
    fun updateRow(profileId: UUID?, text: String?) {
        if (profileId == null) return
        val parsed = DungeonMapGrid.dungeonPlayerFromTabLine(text) ?: return
        updateParsedRow(profileId, parsed)
    }

    @JvmStatic
    fun removeProfiles(profileIds: Iterable<UUID?>?) {
        if (profileIds == null) return
        for (profileId in profileIds) {
            if (profileId == null) continue
            val key = nameByProfile.remove(profileId) ?: continue
            val mappedProfile = profileByName[key]
            if (profileId == mappedProfile) {
                profileByName.remove(key)
                players.remove(key)
            }
        }
    }

    @JvmStatic
    fun snapshot(): List<DungeonPlayerMap.TabPlayer> = java.util.List.copyOf(players.values)

    /** Parses current display order while retaining cached class data for DEAD rows. */
    @JvmStatic
    fun currentDungeonRows(
        currentTabLines: Iterable<String?>?,
        cachedRoster: List<DungeonPlayerMap.TabPlayer?>?,
    ): List<DungeonPlayerMap.TabPlayer> {
        val cachedByName = LinkedHashMap<String, DungeonPlayerMap.TabPlayer>()
        if (cachedRoster != null) {
            for (cached in cachedRoster) {
                if (cached == null) continue
                val key = cached.name()?.lowercase(Locale.ROOT) ?: continue
                if (key.isNotBlank()) cachedByName.putIfAbsent(key, cached)
            }
        }

        if (currentTabLines == null) return java.util.List.of()
        val current = ArrayList<DungeonPlayerMap.TabPlayer>()
        val seen = HashSet<String>()
        for (line in currentTabLines) {
            val parsed = DungeonMapGrid.dungeonPlayerFromTabLine(line) ?: continue
            val name = parsed.name?.takeIf { it.isNotBlank() } ?: continue
            val key = name.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue
            if (parsed.dead) {
                val cached = cachedByName[key]
                val dungeonClass = cached?.dungeonClass() ?: ""
                current.add(DungeonPlayerMap.TabPlayer(name, dungeonClass, true))
            } else if (DungeonClass.parse(parsed.dungeonClass) != null) {
                current.add(DungeonPlayerMap.TabPlayer(name, parsed.dungeonClass, false))
            }
        }
        return java.util.List.copyOf(current)
    }

    private fun updateParsedRow(profileId: UUID, parsed: DungeonMapGrid.DungeonTabPlayer) {
        val name = parsed.name?.takeIf { it.isNotBlank() } ?: return
        val key = name.lowercase(Locale.ROOT)
        val previous = players[key]
        if (previous == null) {
            if (parsed.dead) {
                players[key] = DungeonPlayerMap.TabPlayer(name, "", true)
            } else if (DungeonClass.parse(parsed.dungeonClass) != null) {
                players[key] = DungeonPlayerMap.TabPlayer(name, parsed.dungeonClass, false)
            }
        } else {
            val className = if (parsed.dead) previous.dungeonClass() else parsed.dungeonClass
            players[key] = DungeonPlayerMap.TabPlayer(previous.name(), className, parsed.dead)
        }
        val previousProfile = profileByName.put(key, profileId)
        if (previousProfile != null && previousProfile != profileId) nameByProfile.remove(previousProfile)
        nameByProfile[profileId] = key
    }
}
