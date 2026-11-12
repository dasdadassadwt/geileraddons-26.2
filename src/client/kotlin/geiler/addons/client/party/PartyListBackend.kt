package geiler.addons.client.party

import geiler.addons.GeilerAddons
import geiler.addons.client.dungeon.DungeonClass
import geiler.addons.client.tree.ChatText
import net.minecraft.client.Minecraft
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/** Data-only party state built from Hypixel's party chat and list messages. */
object PartyListBackend {
    private val joined = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) joined the party\\.$")
    private val left = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) has left the party\\.$")
    private val removed = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) has been removed from the party\\.$")
    private val joinedSelf = Pattern.compile("^You have joined .*?([A-Za-z0-9_]{1,16})'?s party!$")
    private val leaderLine = Pattern.compile("^Party Leader:\\s*(.+)$")
    private val membersLine = Pattern.compile("^Party (?:Moderators|Members):\\s*(.*)$")
    private val partyChat = Pattern.compile("^Party > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}):")
    private val leaderTransfer = Pattern.compile("^.*?([A-Za-z0-9_]{1,16}) is now the party leader!$")
    private val classInJoin = Pattern.compile(
        "^Party Finder > (?:\\[[^]]*]\\s*)?([A-Za-z0-9_]{1,16}) joined the dungeon group! \\((?:MM )?([A-Za-z]+) Level \\d+\\)$",
    )

    private val members = LinkedHashMap<String, PartyMember>()

    /** Changes only when a name leaves and joins again, not when metadata is refreshed. */
    private val membershipIdentities = LinkedHashMap<String, Long>()
    private var listMetadata: Map<String, PartyMember> = emptyMap()
    private var listMembershipIdentities: Map<String, Long> = emptyMap()
    private var leaderName: String? = null
    private var inParty = false

    /** Session token: ordinary joins/leaves and repeated /p list snapshots do not invalidate work. */
    private var generation = 0L
    private var tick = 0L
    private var lastListMessageTick = Long.MIN_VALUE
    private var listMessageDirty = false
    private var stableListObserved = false
    private var lastUpdatedNanos = 0L
    private var nextMembershipIdentity = 1L
    private val sessionLifecycle = PartySessionLifecycle()

    @JvmStatic
    fun onChatMessage(rawMessage: String) {
        val message = ChatText.plain(rawMessage).trim()
        if (message.isEmpty()) return
        if (message.startsWith("Party ") || message.startsWith("Party Finder >") ||
            message.contains("party was disbanded") || message.contains("has left the party")
        ) {
            GeilerAddons.LOGGER.debug("[Party List] Chat: {}", message)
        }

        if (message.startsWith("Party Members (")) {
            beginList()
            return
        }
        if (message.contains("The party was disbanded") || message.contains("has disbanded the party") ||
            message == "You left the party." || message.startsWith("You have been kicked from the party") ||
            message == "You are not currently in a party."
        ) {
            GeilerAddons.LOGGER.debug("[Party List] Clearing party state because of: {}", message)
            clear()
            stableListObserved = true
            lastUpdatedNanos = System.nanoTime()
            return
        }

        val self = joinedSelf.matcher(message)
        if (self.matches()) {
            // A confirmed self-join starts a new membership session even if a leave line was missed.
            if (PartySessionLifecycle.shouldResetForConfirmedSelfJoin(inParty)) clear()
            addMember(localName())
            addMember(extractName(self.group(1)))
            leaderName = extractName(self.group(1))
            return
        }

        val join = joined.matcher(message)
        if (join.matches()) {
            addMember(join.group(1))
            GeilerAddons.LOGGER.debug("[Party List] Added {} from party join", join.group(1))
            return
        }
        val departedMember = departedMemberName(message)
        if (departedMember != null) {
            removeMember(departedMember)
            if (removed.matcher(message).matches()) {
                GeilerAddons.LOGGER.debug("[Party List] Removed {} because they were kicked", departedMember)
            } else {
                GeilerAddons.LOGGER.debug("[Party List] Removed {} because they left", departedMember)
            }
            return
        }

        val leader = leaderLine.matcher(message)
        if (leader.matches()) {
            val name = extractName(leader.group(1))
            if (name != null) thisLeader(name)
            markListMessage()
            return
        }
        val list = membersLine.matcher(message)
        if (list.matches()) {
            for (entry in list.group(1).split(Regex("\\s*●\\s*"))) {
                addMember(extractName(entry))
            }
            markListMessage()
            return
        }

        val chat = partyChat.matcher(message)
        if (chat.find()) {
            addMember(chat.group(1))
            return
        }
        val transferred = leaderTransfer.matcher(message)
        if (transferred.matches()) {
            thisLeader(transferred.group(1))
            return
        }

        val classJoin = classInJoin.matcher(message)
        if (classJoin.matches()) {
            addMember(classJoin.group(1))
            setClass(classJoin.group(1), DungeonClass.parse(classJoin.group(2)))
            GeilerAddons.LOGGER.debug(
                "[Party List] Added {} from dungeon join as {}",
                classJoin.group(1),
                classJoin.group(2),
            )
        }
    }

    /** Returns the player named by a confirmed party-leave or kick chat message. */
    @JvmStatic
    fun departedMemberName(rawMessage: String?): String? {
        val message = ChatText.plain(rawMessage ?: "").trim()
        val leftMember = left.matcher(message)
        if (leftMember.matches()) return leftMember.group(1)
        val removedMember = removed.matcher(message)
        return if (removedMember.matches()) removedMember.group(1) else null
    }

    @JvmStatic
    fun tick() {
        val connection = Minecraft.getInstance().connection
        if (sessionLifecycle.updateConnection(connection)) {
            if (connection == null) {
                GeilerAddons.LOGGER.debug("[Party List] Clearing state after disconnect")
            } else {
                GeilerAddons.LOGGER.debug("[Party List] Clearing state after server connection change")
            }
            clear()
        }
        tick++
    }

    /** Returns true once a /p list response has been quiet for two client ticks. */
    @JvmStatic
    fun consumeStableListResponse(): Boolean {
        if (!listMessageDirty || tick - lastListMessageTick < 2) return false
        listMessageDirty = false
        listMetadata = emptyMap()
        listMembershipIdentities = emptyMap()
        if (members.isEmpty()) clear()
        stableListObserved = true
        lastUpdatedNanos = System.nanoTime()
        return true
    }

    /** True after a complete list snapshot has been observed in the current party session. */
    @JvmStatic
    fun hasStableList(): Boolean = stableListObserved

    /** Requires a recent complete roster; an old party list cannot decide a macro condition. */
    @JvmStatic
    fun hasFreshSnapshot(): Boolean {
        val age = System.nanoTime() - lastUpdatedNanos
        return stableListObserved && lastUpdatedNanos > 0L && age >= 0L && age <= 120_000_000_000L
    }

    @JvmStatic
    fun snapshot(): PartySnapshot = PartySnapshot(ArrayList(members.values), leaderName, inParty, generation)

    /** Identity of this continuous membership episode, or {@code 0} when the name is not tracked. */
    @JvmStatic
    fun membershipIdentity(name: String?): Long {
        if (name == null || name.isBlank()) return 0L
        return membershipIdentities.getOrDefault(key(name), 0L)
    }

    @JvmStatic
    fun setUuid(name: String?, uuid: UUID?) {
        if (name == null || uuid == null) return
        val memberKey = key(name)
        val member = members[memberKey]
        if (member != null) members[memberKey] = member.withUuid(uuid)
    }

    @JvmStatic
    fun setClass(name: String?, dungeonClass: DungeonClass?) {
        if (name == null || dungeonClass == null) return
        val memberKey = key(name)
        val member = members[memberKey]
        if (member != null) {
            members[memberKey] = member.withClass(dungeonClass)
            GeilerAddons.LOGGER.debug("[Party List] Set {} class to {}", member.name(), dungeonClass.displayName())
        }
    }

    /** Uses profile data only as a fallback; a Party Finder join class is more authoritative. */
    @JvmStatic
    fun setClassIfUnknown(name: String?, dungeonClass: DungeonClass?) {
        if (name == null || dungeonClass == null) return
        val memberKey = key(name)
        val member = members[memberKey]
        if (member != null && member.dungeonClass() == null) {
            members[memberKey] = member.withClass(dungeonClass)
            GeilerAddons.LOGGER.debug(
                "[Party List] Filled unknown class for {} with profile class {}",
                member.name(),
                dungeonClass.displayName(),
            )
        }
    }

    @JvmStatic
    fun clear() {
        GeilerAddons.LOGGER.debug("[Party List] State cleared (generation {})", generation + 1)
        members.clear()
        membershipIdentities.clear()
        leaderName = null
        inParty = false
        generation++
        listMessageDirty = false
        stableListObserved = false
        lastUpdatedNanos = 0L
        listMetadata = emptyMap()
        listMembershipIdentities = emptyMap()
    }

    private fun beginList() {
        GeilerAddons.LOGGER.debug("[Party List] Beginning /p list response")
        listMetadata = LinkedHashMap(members)
        listMembershipIdentities = LinkedHashMap(membershipIdentities)
        members.clear()
        membershipIdentities.clear()
        leaderName = null
        inParty = true
        stableListObserved = false
        lastUpdatedNanos = System.nanoTime()
        markListMessage()
    }

    private fun addMember(name: String?) {
        if (name == null || name.isBlank()) return
        val normalized = extractName(name) ?: return
        val memberKey = key(normalized)
        val previous = listMetadata[memberKey]
        var identity = membershipIdentities.getOrDefault(memberKey, 0L)
        if (identity == 0L) identity = listMembershipIdentities.getOrDefault(memberKey, 0L)
        if (identity == 0L) identity = nextMembershipIdentity++
        val member = if (previous == null) PartyMember(normalized, null, null)
        else PartyMember(normalized, previous.uuid(), previous.dungeonClass())
        val added = members.putIfAbsent(memberKey, member) == null
        membershipIdentities.putIfAbsent(memberKey, identity)
        inParty = true
        lastUpdatedNanos = System.nanoTime()
        if (added) GeilerAddons.LOGGER.debug("[Party List] Member now tracked: {}", normalized)
    }

    private fun removeMember(name: String?) {
        if (name == null) return
        val memberKey = key(name)
        members.remove(memberKey)
        membershipIdentities.remove(memberKey)
        if (listMetadata is LinkedHashMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (listMetadata as LinkedHashMap<String, PartyMember>).remove(memberKey)
        }
        if (listMembershipIdentities is LinkedHashMap<*, *>) {
            @Suppress("UNCHECKED_CAST")
            (listMembershipIdentities as LinkedHashMap<String, Long>).remove(memberKey)
        }
        lastUpdatedNanos = System.nanoTime()
        if (leaderName != null && leaderName.equals(name, ignoreCase = true)) leaderName = null
        inParty = true
        // An empty roster can mean the local player is alone; only explicit leave/disband lines end it.
    }

    private fun thisLeader(name: String) {
        leaderName = name
        addMember(name)
    }

    private fun markListMessage() {
        lastListMessageTick = tick
        listMessageDirty = true
        GeilerAddons.LOGGER.debug("[Party List] Received party list line at tick {}", tick)
    }

    private fun localName(): String? {
        val player = Minecraft.getInstance().player ?: return null
        return player.getGameProfile().name()
    }

    private fun extractName(value: String?): String? {
        if (value == null) return null
        val matcher = Pattern.compile("([A-Za-z0-9_]{1,16})(?:\\s*●)?\\s*$").matcher(value.trim())
        if (!matcher.find()) return null
        val name = matcher.group(1)
        return if (name.equals("none", ignoreCase = true) || name.equals("nobody", ignoreCase = true)) null else name
    }

    private fun key(name: String): String = name.trim().lowercase(Locale.ROOT)
}
