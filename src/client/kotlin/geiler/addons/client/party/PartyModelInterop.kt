package geiler.addons.client.party

import java.util.UUID

/** Kotlin implementations for the behavior attached to the Java record ABI. */
object PartyModelInterop {
    @JvmStatic
    fun withUuid(member: PartyMember, value: UUID?): PartyMember =
        PartyMember(member.name(), value, member.dungeonClass())

    @JvmStatic
    fun withClass(member: PartyMember, value: geiler.addons.client.dungeon.DungeonClass?): PartyMember =
        PartyMember(member.name(), member.uuid(), value)

    @JvmStatic
    fun isLeader(snapshot: PartySnapshot, localName: String?): Boolean {
        val leaderName = snapshot.leaderName()
        return snapshot.inParty() && leaderName != null && localName != null &&
            leaderName.equals(localName, ignoreCase = true)
    }
}
