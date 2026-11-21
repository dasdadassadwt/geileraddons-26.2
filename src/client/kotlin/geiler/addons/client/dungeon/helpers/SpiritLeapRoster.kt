package geiler.addons.client.dungeon.helpers

import geiler.addons.client.dungeon.helpers.SpiritLeapRoster.Card
import geiler.addons.client.dungeon.helpers.SpiritLeapRoster.Member
import geiler.addons.client.dungeon.helpers.SpiritLeapRoster.Sort
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.Locale

/** Kotlin implementation behind the Java record-compatible roster API. */
class SpiritLeapRosterLogic {
    private var observed: List<Member> = emptyList()

    @JvmSuppressWildcards
    fun update(members: List<Member?>?, localPlayerName: String?) {
        if (members == null) {
            observed = emptyList()
            return
        }
        val local = normalize(localPlayerName)
        val unique = LinkedHashMap<String, Member>()
        for (member in members) {
            if (member == null || normalize(member.name()) == local || member.name().isBlank()) continue
            unique.putIfAbsent(normalize(member.name()), member)
        }
        observed = java.util.List.copyOf(unique.values)
    }

    @JvmSuppressWildcards
    fun cards(sort: Sort?, customOrder: List<String>?): List<Card> {
        val sorted = ArrayList(observed)
        if (sort == Sort.ODIN) return odinCards(sorted)
        when (sort) {
            Sort.HEALTH -> sorted.sortWith(Comparator { first, second ->
                val healthOrder = second.healthPercent().compareTo(first.healthPercent())
                if (healthOrder != 0) healthOrder
                else String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name())
            })
            Sort.CLASS -> sorted.sortWith(Comparator { first, second ->
                val classOrder = String.CASE_INSENSITIVE_ORDER.compare(first.dungeonClass(), second.dungeonClass())
                if (classOrder != 0) classOrder else String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name())
            })
            Sort.CUSTOM -> {
                val order = customOrder.orEmpty().map(::normalize)
                sorted.sortWith(Comparator { first, second ->
                    val firstIndex = order.indexOf(normalize(first.name())).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    val secondIndex = order.indexOf(normalize(second.name())).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    val custom = firstIndex.compareTo(secondIndex)
                    if (custom != 0) custom else String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name())
                })
            }
            else -> sorted.sortWith(Comparator { first, second ->
                String.CASE_INSENSITIVE_ORDER.compare(first.name(), second.name())
            })
        }
        val quadrants = emptyQuadrants()
        for (index in 0 until minOf(4, sorted.size)) quadrants[index] = sorted[index]
        return cardsOf(quadrants)
    }

    @JvmSuppressWildcards
    fun observed(): List<Member> = observed
    fun hasObservedTeammates() = observed.isNotEmpty()
    fun clear() { observed = emptyList() }

    companion object {
        private val EMPTY = Member("", "", 0, false, false)

        /** Resolves only validated leap-menu heads, enriching them with matching live roster data. */
        @JvmStatic
        @JvmSuppressWildcards
        fun resolveMenuHeads(
            menuHeads: List<SpiritLeapSelection.MenuHead?>?,
            knownMembers: List<Member?>?,
            localPlayerName: String?,
        ): List<Member> {
            if (menuHeads.isNullOrEmpty()) return java.util.List.of()
            val local = normalize(localPlayerName)
            val knownByName = HashMap<String, Member>()
            knownMembers?.forEach { member ->
                if (member != null && !member.empty()) knownByName.putIfAbsent(normalize(member.name()), member)
            }
            val seen = HashSet<String>()
            val resolved = ArrayList<Member>(4)
            for (head in menuHeads) {
                val name = SpiritLeapSelection.validatedPlayerName(head) ?: continue
                val key = normalize(name)
                if (key == local || !seen.add(key)) continue
                val known = knownByName[key]
                resolved += if (known == null) Member(name, "", 0, false, false)
                    else Member(name, known.dungeonClass(), known.healthPercent(), known.online(), known.dead())
            }
            return java.util.List.copyOf(resolved)
        }

        /** Odin 0.3.6 reserves each class's default corner by priority, then fills holes in order. */
        @JvmStatic
        @JvmSuppressWildcards
        fun odinCards(members: List<Member?>?): List<Card> {
            val quadrants = emptyQuadrants()
            val remaining = ArrayList<Member>()
            members.orEmpty().filterNotNull().sortedBy { classPriority(it.dungeonClass()) }.forEach { member ->
                val quadrant = defaultQuadrant(member.dungeonClass())
                if (quadrant >= 0 && quadrants[quadrant].empty()) quadrants[quadrant] = member
                else remaining += member
            }
            var nextRemaining = 0
            for (index in quadrants.indices) {
                if (quadrants[index].empty() && nextRemaining < remaining.size) {
                    quadrants[index] = remaining[nextRemaining++]
                }
            }
            return cardsOf(quadrants)
        }

        private fun classPriority(dungeonClass: String?): Int = when (normalize(dungeonClass)) {
            "berserk", "berserker" -> 0
            "tank" -> 1
            else -> 2
        }

        private fun defaultQuadrant(dungeonClass: String?): Int = when (normalize(dungeonClass)) {
            "archer" -> 0
            "berserk", "berserker" -> 1
            "healer" -> 2
            "mage", "tank" -> 3
            else -> -1
        }

        private fun emptyQuadrants() = Array(4) { EMPTY }

        private fun cardsOf(quadrants: Array<Member>): List<Card> =
            java.util.List.copyOf(quadrants.mapIndexed { index, member -> Card(index, member) })

        /** Minecraft legacy color code used by Odin's player-class card style. */
        @JvmStatic
        fun classColorCode(dungeonClass: String?): Char = when (normalize(dungeonClass)) {
            "archer" -> '6'
            "berserk", "berserker" -> '4'
            "healer" -> 'd'
            "mage" -> 'b'
            "tank" -> '2'
            else -> 'f'
        }

        /** Packed ARGB palette used by Odin's leap menu and shared party highlights. */
        @JvmStatic
        fun classColorArgb(dungeonClass: String?): Int = when (classColorCode(dungeonClass)) {
            '6' -> 0xFFFFAA00.toInt()
            '4' -> 0xFFAA0000.toInt()
            'd' -> 0xFFFF55FF.toInt()
            'b' -> 0xFF55FFFF.toInt()
            '2' -> 0xFF00AA00.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

        private fun normalize(text: String?): String = text?.trim()?.lowercase(Locale.ROOT).orEmpty()
    }
}
