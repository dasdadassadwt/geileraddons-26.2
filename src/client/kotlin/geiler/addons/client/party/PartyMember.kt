package geiler.addons.client.party

import geiler.addons.client.dungeon.DungeonClass
import java.util.UUID

/** Immutable party-member data shared by the dungeon modules. */
class PartyMember(
    name: String?,
    uuid: UUID?,
    dungeonClass: DungeonClass?,
) {
    private val nameValue = name
    private val uuidValue = uuid
    private val dungeonClassValue = dungeonClass

    fun name(): String? = nameValue

    fun uuid(): UUID? = uuidValue

    fun dungeonClass(): DungeonClass? = dungeonClassValue

    fun withUuid(value: UUID?): PartyMember = PartyModelInterop.withUuid(this, value)

    fun withClass(value: DungeonClass?): PartyMember = PartyModelInterop.withClass(this, value)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as PartyMember
        return nameValue == other.nameValue
            && uuidValue == other.uuidValue
            && dungeonClassValue == other.dungeonClassValue
    }

    override fun hashCode(): Int {
        var result = nameValue?.hashCode() ?: 0
        result = 31 * result + (uuidValue?.hashCode() ?: 0)
        result = 31 * result + (dungeonClassValue?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PartyMember[name=$nameValue, uuid=$uuidValue, dungeonClass=$dungeonClassValue]"
}
