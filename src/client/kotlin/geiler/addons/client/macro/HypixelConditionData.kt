package geiler.addons.client.macro

import geiler.addons.client.dungeon.DungeonContextTracker
import geiler.addons.client.dungeon.DungeonFloor
import geiler.addons.client.farming.GardenPlotGrid
import geiler.addons.client.farming.GardenPlotState
import geiler.addons.client.location.HypixelModApi
import geiler.addons.client.location.Island
import geiler.addons.client.module.impl.GardenPlotBordersModule
import geiler.addons.client.party.PartyListBackend
import geiler.addons.client.party.PartySnapshot
import net.minecraft.client.Minecraft

/** Typed, client-observed values for macro conditions; missing and stale facts stay unavailable. */
object HypixelConditionData {
    @JvmStatic
    fun read(condition: MacroCondition.Hypixel?, minecraft: Minecraft?): Value {
        if (condition == null || minecraft == null || !minecraft.isSameThread) return Value.unavailable()
        return when (condition.field()) {
            MacroCondition.Hypixel.Field.ISLAND -> island()
            MacroCondition.Hypixel.Field.DUNGEON_FLOOR -> dungeonFloor()
            MacroCondition.Hypixel.Field.PARTY_MEMBER -> partyMember()
            MacroCondition.Hypixel.Field.PARTY_HAS_MEMBER -> partyHasMember(condition.argument())
            MacroCondition.Hypixel.Field.PARTY_SIZE -> partySize()
            MacroCondition.Hypixel.Field.PARTY_LEADER -> partyLeader()
            MacroCondition.Hypixel.Field.GARDEN_PEST_STATUS -> gardenStatus(condition.plotId(), minecraft)
            MacroCondition.Hypixel.Field.GARDEN_PEST_COUNT -> gardenCount(condition.plotId(), minecraft)
        }
    }

    private fun island(): Value {
        if (!HypixelModApi.hasLocation()) return Value.unavailable()
        val island = HypixelModApi.currentIsland()
        return if (island == Island.NONE) Value.unavailable() else Value.available(island.name)
    }

    private fun dungeonFloor(): Value {
        if (!HypixelModApi.hasLocation()) return Value.unavailable()
        if (HypixelModApi.currentIsland() != Island.CATACOMBS) return Value.available("NONE")
        val floor: DungeonFloor? = DungeonContextTracker.currentFloor()
        return if (floor == null) Value.unavailable() else Value.available(floor.displayName())
    }

    private fun partyMember(): Value {
        if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable()
        return Value.available(PartyListBackend.snapshot().inParty())
    }

    private fun partyHasMember(wanted: String?): Value {
        if (!PartyListBackend.hasFreshSnapshot() || MacroJavaStrings.isBlank(wanted)) return Value.unavailable()
        val party: PartySnapshot = PartyListBackend.snapshot()
        val normalizedWanted = MacroJavaStrings.trim(wanted)!!
        return Value.available(party.members().stream().anyMatch { member -> member.name().equals(normalizedWanted, ignoreCase = true) })
    }

    private fun partySize(): Value {
        if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable()
        return Value.available(PartyListBackend.snapshot().members().size)
    }

    private fun partyLeader(): Value {
        if (!PartyListBackend.hasFreshSnapshot()) return Value.unavailable()
        val party = PartyListBackend.snapshot()
        if (!party.inParty()) return Value.available("NONE")
        val leaderName = party.leaderName()
        return if (leaderName == null) Value.unavailable() else Value.available(leaderName)
    }

    private fun gardenStatus(requestedPlotId: Int, minecraft: Minecraft): Value {
        val status = gardenPlot(requestedPlotId, minecraft)
        val gardenStatus = status?.status ?: return Value.unavailable()
        return Value.available(gardenStatus.name)
    }

    private fun gardenCount(requestedPlotId: Int, minecraft: Minecraft): Value {
        val status = gardenPlot(requestedPlotId, minecraft)
        val pestCount = status?.pestCount
        return if (pestCount == null) Value.unavailable() else Value.available(pestCount)
    }

    private fun gardenPlot(requestedPlotId: Int, minecraft: Minecraft): GardenPlotState.PlotStatus? {
        if (!HypixelModApi.onGarden()) return null
        val player = minecraft.player ?: return null
        var plotId = requestedPlotId
        if (plotId < 0) {
            plotId = GardenPlotGrid.plotAt(player.x, player.y, player.z)
                .map { plot -> plot.id }.orElse(-1)
        }
        return GardenPlotBordersModule.freshPlotStatus(plotId).orElse(null)
    }

    @JvmRecord
    data class Value(val available: Boolean, val value: Any?) {
        companion object {
            @JvmStatic
            fun available(value: Any?): Value = Value(value != null, value)

            @JvmStatic
            fun unavailable(): Value = Value(false, null)
        }
    }
}
