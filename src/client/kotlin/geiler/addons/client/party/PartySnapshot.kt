package geiler.addons.client.party

/** A read-only view of the transient party state. */
class PartySnapshot(
    members: List<PartyMember>,
    private val leaderNameValue: String?,
    private val inPartyValue: Boolean,
    private val generationValue: Long,
) {
    private val membersValue: List<PartyMember> = java.util.List.copyOf(members)

    fun members(): List<PartyMember> = membersValue

    fun leaderName(): String? = leaderNameValue

    fun inParty(): Boolean = inPartyValue

    fun generation(): Long = generationValue

    fun isLeader(localName: String?): Boolean = PartyModelInterop.isLeader(this, localName)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false

        other as PartySnapshot
        return membersValue == other.membersValue
            && leaderNameValue == other.leaderNameValue
            && inPartyValue == other.inPartyValue
            && generationValue == other.generationValue
    }

    override fun hashCode(): Int {
        var result = membersValue.hashCode()
        result = 31 * result + (leaderNameValue?.hashCode() ?: 0)
        result = 31 * result + inPartyValue.hashCode()
        result = 31 * result + generationValue.hashCode()
        return result
    }

    override fun toString(): String =
        "PartySnapshot[members=$membersValue, leaderName=$leaderNameValue, " +
            "inParty=$inPartyValue, generation=$generationValue]"
}
