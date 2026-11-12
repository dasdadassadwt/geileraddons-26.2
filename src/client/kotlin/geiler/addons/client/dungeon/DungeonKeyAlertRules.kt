package geiler.addons.client.dungeon

import geiler.addons.client.chat.ChatStringSemantics
import geiler.addons.client.tree.ChatText
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord

/** Pure parsing, attribution, and duplicate suppression for key pickup alerts. */
object DungeonKeyAlertRules {
    private val NAMED_PICKUP = Pattern.compile(
        "^(?:\\[[A-Za-z0-9+ _-]{1,24}\\] )?([A-Za-z0-9_]{1,16}) has obtained (?:a )?(Wither|Blood) Key!",
    )
    private const val DUPLICATE_WINDOW_NANOS = 750_000_000L
    private const val MAX_REMEMBERED_LINES = 64
    const val POST_SPAWN_PICKUP_ARBITRATION_NANOS = 350_000_000L
    private const val KEY_ENTITY_SEEN_WINDOW_NANOS = 1_500_000_000L
    private const val CHAT_AFTER_PACKET_DEBOUNCE_NANOS = 750_000_000L
    private const val MAX_REMEMBERED_SPAWN_UUIDS = 1_024
    private const val MAX_TRACKED_KEY_ENTITIES = 256

    enum class KeyKind { WITHER, BLOOD }
    enum class ActorKind { UNATTRIBUTED, SELF, PARTY, UNKNOWN }

    /** Odin-style entity detection: only the exact ArmorStand display names qualify. */
    @JvmStatic
    fun matchArmorStandName(armorStand: Boolean, displayName: String?): KeyKind? {
        if (!armorStand || displayName == null) return null
        return when (displayName) {
            "Wither Key" -> KeyKind.WITHER
            "Blood Key" -> KeyKind.BLOOD
            else -> null
        }
    }

    @JvmStatic
    fun shouldCollectRenderTargets(highlight: Boolean, tracer: Boolean): Boolean = highlight || tracer

    /** A null actor is reserved for the exact system pickup announcement. */
    @JvmRecord
    data class PickupEvent(val keyKind: KeyKind?, val actor: String?) {
        override fun toString(): String = "PickupEvent[keyKind=$keyKind, actor=$actor]"
    }

    @JvmRecord
    data class PickupAlert(
        val actorKind: ActorKind?,
        val text: String?,
        val exactSystemLine: Boolean,
    ) {
        override fun toString(): String =
            "PickupAlert[actorKind=$actorKind, text=$text, exactSystemLine=$exactSystemLine]"
    }

    /** Produces a safe alert label; an unverified name is discarded rather than guessed or displayed. */
    @JvmStatic
    fun resolveAlert(pickup: PickupEvent?, localPlayerName: String?, partyNames: Iterable<String>?): PickupAlert? {
        if (pickup == null) return null
        val keyLabel = if (pickup.keyKind == KeyKind.WITHER) "Wither Key" else "Blood Key"
        val actor = pickup.actor
        if (actor == null) {
            return PickupAlert(ActorKind.UNATTRIBUTED, "$keyLabel was picked up", true)
        }
        val actorKind = classifyActor(actor, localPlayerName, partyNames)
        return when (actorKind) {
            ActorKind.SELF -> PickupAlert(actorKind, "You picked up $keyLabel", false)
            ActorKind.PARTY -> PickupAlert(actorKind, "$actor picked up $keyLabel", false)
            ActorKind.UNKNOWN, ActorKind.UNATTRIBUTED ->
                PickupAlert(ActorKind.UNATTRIBUTED, "$keyLabel was picked up", false)
        }
    }

    /** Reports each metadata-detected key stand at most once per dungeon context. */
    class SpawnAnnouncementTracker {
        private val remembered = LinkedHashSet<java.util.UUID>()

        fun shouldAnnounce(entityId: java.util.UUID?): Boolean = remember(entityId)

        /** Existing entities found by a bootstrap scan must never be announced as new spawns. */
        fun rememberExisting(entityId: java.util.UUID?) {
            remember(entityId)
        }

        private fun remember(entityId: java.util.UUID?): Boolean {
            if (entityId == null || !remembered.add(entityId)) return false
            while (remembered.size > MAX_REMEMBERED_SPAWN_UUIDS) {
                val oldest = remembered.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            return true
        }

        fun clear() = remembered.clear()
    }

    /** Correlates a real key item entity with its pickup packet while giving the server chat line priority. */
    class KeyPickupArbitrator {
        private val observedKeys = HashMap<Int, KeyEntity>()
        private val pendingPackets = LinkedHashMap<Int, PendingPickup>()
        private val recentPacketAlerts = HashMap<KeyKind, Long>()
        private val recentChatConfirmations = HashMap<KeyKind, Long>()

        /** Called while scanning live item entities; non-key animation items never enter this table. */
        fun observeKeyEntity(entityId: Int, kind: KeyKind?, nowNanos: Long) {
            if (entityId < 0 || kind == null) return
            prune(nowNanos)
            observedKeys[entityId] = KeyEntity(kind, nowNanos)
            while (observedKeys.size > MAX_TRACKED_KEY_ENTITIES) {
                val oldest = observedKeys.keys.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
        }

        /** Accepts one local pickup packet only when it matches a freshly observed real key entity. */
        fun beginPickupPacket(itemEntityId: Int, localCollector: Boolean, nowNanos: Long): Boolean {
            if (!localCollector || itemEntityId < 0 || pendingPackets.containsKey(itemEntityId)) return false
            prune(nowNanos)
            val observed = observedKeys.remove(itemEntityId) ?: return false
            if (nowNanos < observed.lastSeenNanos ||
                nowNanos - observed.lastSeenNanos > KEY_ENTITY_SEEN_WINDOW_NANOS
            ) return false
            val chatAt = recentChatConfirmations[observed.kind]
            if (chatAt != null && nowNanos >= chatAt &&
                nowNanos - chatAt <= CHAT_AFTER_PACKET_DEBOUNCE_NANOS
            ) return false
            pendingPackets[itemEntityId] = PendingPickup(
                itemEntityId,
                observed.kind,
                nowNanos + POST_SPAWN_PICKUP_ARBITRATION_NANOS,
            )
            return true
        }

        /** A matching real chat line cancels the packet fallback, so only one source alerts. */
        fun observeChatPickup(kind: KeyKind?, actor: String?, localName: String?, nowNanos: Long): ChatPickupDecision {
            if (kind == null) return ChatPickupDecision(false, false)
            val localOrUnattributed = actor == null || sameUsername(actor, localName)
            if (localOrUnattributed) {
                recentChatConfirmations[kind] = nowNanos
                for (pending in pendingPackets.values.toList()) {
                    if (pending.kind != kind) continue
                    pendingPackets.remove(pending.itemEntityId)
                    return ChatPickupDecision(true, false)
                }
                val packetAlert = recentPacketAlerts[kind]
                if (packetAlert != null && nowNanos >= packetAlert &&
                    nowNanos - packetAlert <= CHAT_AFTER_PACKET_DEBOUNCE_NANOS
                ) return ChatPickupDecision(false, true)
            }
            return ChatPickupDecision(false, false)
        }

        /** Returns packet-confirmed local pickups only after the chat arbitration window expires. */
        fun takeReadyPacketPickups(nowNanos: Long): List<PickupConfirmation> {
            prune(nowNanos)
            val ready = ArrayList<PickupConfirmation>()
            for (pending in pendingPackets.values.toList()) {
                if (nowNanos < pending.deadlineNanos) continue
                pendingPackets.remove(pending.itemEntityId)
                recentPacketAlerts[pending.kind] = nowNanos
                ready.add(PickupConfirmation(pending.itemEntityId, pending.kind))
            }
            return java.util.List.copyOf(ready)
        }

        fun clear() {
            observedKeys.clear()
            pendingPackets.clear()
            recentPacketAlerts.clear()
            recentChatConfirmations.clear()
        }

        fun pendingPickupCount(): Int = pendingPackets.size

        private fun prune(nowNanos: Long) {
            observedKeys.entries.removeIf { (_, entity) ->
                nowNanos < entity.lastSeenNanos || nowNanos - entity.lastSeenNanos > KEY_ENTITY_SEEN_WINDOW_NANOS
            }
            recentPacketAlerts.entries.removeIf { (_, timestamp) ->
                nowNanos < timestamp || nowNanos - timestamp > CHAT_AFTER_PACKET_DEBOUNCE_NANOS
            }
            recentChatConfirmations.entries.removeIf { (_, timestamp) ->
                nowNanos < timestamp || nowNanos - timestamp > CHAT_AFTER_PACKET_DEBOUNCE_NANOS
            }
        }
    }

    @JvmRecord
    data class ChatPickupDecision(
        val canceledPendingPacket: Boolean,
        val suppressDuplicateChat: Boolean,
    ) {
        override fun toString(): String =
            "ChatPickupDecision[canceledPendingPacket=$canceledPendingPacket, suppressDuplicateChat=$suppressDuplicateChat]"
    }

    @JvmRecord
    data class PickupConfirmation(val itemEntityId: Int, val kind: KeyKind?) {
        override fun toString(): String = "PickupConfirmation[itemEntityId=$itemEntityId, kind=$kind]"
    }

    private class KeyEntity(val kind: KeyKind, val lastSeenNanos: Long)
    private class PendingPickup(val itemEntityId: Int, val kind: KeyKind, val deadlineNanos: Long)

    private fun sameUsername(first: String?, second: String?): Boolean =
        first != null && second != null && first.equals(second, ignoreCase = true)

    /** Parses only the server announcement or exact named `X has obtained ... Key!` form. */
    @JvmStatic
    fun parsePickup(line: String?): PickupEvent? {
        if (line == null) return null
        val clean = ChatStringSemantics.trim(ChatText.plain(line)).replace(Regex("\\s+"), " ")
        if (clean == "A Wither Key was picked up!") return PickupEvent(KeyKind.WITHER, null)
        if (clean == "A Blood Key was picked up!") return PickupEvent(KeyKind.BLOOD, null)
        val named = NAMED_PICKUP.matcher(clean)
        if (!named.matches()) return null
        val kind = if (named.group(2) == "Wither") KeyKind.WITHER else KeyKind.BLOOD
        return PickupEvent(kind, named.group(1))
    }

    /** Compatibility helper for callers that only consume actorless server announcements. */
    @JvmStatic
    fun parseConfirmedPickup(line: String?): KeyKind? {
        val event = parsePickup(line)
        return if (event != null && event.actor == null) event.keyKind else null
    }

    /** Resolves names only against a fresh, complete party roster; an unlisted local name is not enough. */
    @JvmStatic
    fun classifyActor(actor: String?, localPlayerName: String?, partyNames: Iterable<String>?): ActorKind {
        if (actor == null || !isUsername(actor)) return ActorKind.UNKNOWN
        if (partyNames != null) {
            for (partyName in partyNames) {
                if (!sameName(actor, partyName)) continue
                return if (sameName(actor, localPlayerName)) ActorKind.SELF else ActorKind.PARTY
            }
        }
        return ActorKind.UNKNOWN
    }

    /** A roster is trusted only after a recent complete party-list snapshot in an active party. */
    @JvmStatic
    fun trustedPartyNames(snapshotFresh: Boolean, inParty: Boolean, partyNames: List<String>?): List<String> {
        val names = partyNames ?: return emptyList()
        if (!snapshotFresh || !inParty || names.isEmpty()) return emptyList()
        val trusted = ArrayList<String>(names.size)
        for (name in names) {
            if (isUsername(name) && trusted.none { existing -> sameName(existing, name) }) trusted.add(name)
        }
        return java.util.List.copyOf(trusted)
    }

    /** Suppresses immediate duplicate chat callbacks without hiding a later pickup of the same type. */
    class PickupDeduplicator {
        private val recent = LinkedHashMap<String, Long>()

        fun shouldSuppress(line: String?, nowNanos: Long): Boolean {
            if (line == null) return false
            val key = ChatStringSemantics.trim(ChatText.plain(line))
                .replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            if (key.isEmpty()) return false
            val previous = recent[key]
            val duplicate = previous != null && nowNanos >= previous &&
                nowNanos - previous <= DUPLICATE_WINDOW_NANOS
            recent[key] = nowNanos
            while (recent.size > MAX_REMEMBERED_LINES) {
                val oldest = recent.keys.iterator()
                if (!oldest.hasNext()) break
                oldest.next()
                oldest.remove()
            }
            return duplicate
        }

        fun clear() = recent.clear()
    }

    private fun isUsername(name: String?): Boolean = ChatStringSemantics.isMinecraftUsername(name)

    private fun sameName(first: String?, second: String?): Boolean =
        first != null && second != null && isUsername(first) && isUsername(second) &&
            first.equals(second, ignoreCase = true)
}
