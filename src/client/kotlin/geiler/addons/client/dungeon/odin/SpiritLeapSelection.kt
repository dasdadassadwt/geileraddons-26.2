package geiler.addons.client.dungeon.odin

import geiler.addons.client.tree.ChatText
import geiler.addons.client.chat.ChatStringSemantics
import java.util.Locale
import java.util.Optional
import java.util.Set
import java.util.regex.Pattern

/** Safe target resolution for direct inputs against an already-open Spirit Leap menu. */
class SpiritLeapSelection private constructor() {
    @JvmRecord
    data class MenuHead(val containerSlot: Int, val displayName: String?, val playerHead: Boolean) {
        override fun toString() =
            "MenuHead[containerSlot=$containerSlot, displayName=$displayName, playerHead=$playerHead]"
    }

    @JvmRecord
    data class Target(val playerName: String?, val dungeonClass: String?, val containerSlot: Int) {
        override fun toString() =
            "Target[playerName=$playerName, dungeonClass=$dungeonClass, containerSlot=$containerSlot]"
    }

    @JvmRecord
    data class MouseAction(val consumed: Boolean, val target: Optional<Target>?) {
        override fun toString() = "MouseAction[consumed=$consumed, target=$target]"
    }

    enum class KeyMode { CORNERS, CLASS }
    enum class Quadrant { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    enum class MouseTiming { PRESS, RELEASE }
    enum class MousePhase { PRESS, RELEASE }

    companion object {
        private val MENU_TITLES = Set.of("spirit leap", "teleport to player")
        private val PLAYER_NAME = Pattern.compile("^(?:\\[[^]]+]\\s*)*([A-Za-z0-9_]{1,16})$")

        /** Resolves a user key press to the real player-head slot in the current menu. */
        @JvmStatic
        fun targetForKey(
            menuOpen: Boolean,
            menuTitle: String?,
            key: String?,
            mode: KeyMode?,
            cornerKeys: Map<Quadrant, String>?,
            classKeys: Map<String, String>?,
            cards: List<SpiritLeapRoster.Card?>?,
            menuHeads: List<MenuHead?>?,
        ): Optional<Target> {
            if (!menuOpen || !isLeapMenu(menuTitle) || key == null || ChatStringSemantics.isBlank(key) ||
                mode == null || cards == null || menuHeads == null
            ) {
                return Optional.empty()
            }
            val selected = if (mode == KeyMode.CORNERS) {
                val quadrant = Quadrant.entries.firstOrNull { candidate ->
                    normalizeKey(key) == normalizeKey(cornerKeys?.get(candidate))
                }
                cards.firstOrNull { card ->
                    card != null && quadrant != null && card.quadrant() == quadrant.ordinal && card.member() != null
                }
                    ?.member()
            } else {
                val binding = classKeys.orEmpty().entries.firstOrNull { entry ->
                    normalizeKey(key) == normalizeKey(entry.value)
                }
                if (binding == null) null else cards.firstOrNull { card ->
                    val member = card?.member()
                    member != null && !member.empty() &&
                        normalizeClass(member.dungeonClass()) == normalizeClass(binding.key)
                }?.member()
            }
            return targetForMember(selected, menuHeads)
        }

        /** Resolves Odin's mouse-selected screen quadrant to its current menu head. */
        @JvmStatic
        fun targetForQuadrant(
            menuOpen: Boolean,
            menuTitle: String?,
            quadrant: Int,
            cards: List<SpiritLeapRoster.Card?>?,
            menuHeads: List<MenuHead?>?,
        ): Optional<Target> {
            if (!menuOpen || !isLeapMenu(menuTitle) || quadrant !in 0..3 || cards == null || menuHeads == null) {
                return Optional.empty()
            }
            val selected = cards.firstOrNull { card ->
                card != null && card.quadrant() == quadrant && card.member() != null
            }?.member()
            return targetForMember(selected, menuHeads)
        }

        /** Any mouse button is consumed over a displayed card; the selected phase may resolve a target. */
        @JvmStatic
        fun mouseAction(
            mouseButton: Int,
            menuOpen: Boolean,
            menuTitle: String?,
            quadrant: Int,
            cards: List<SpiritLeapRoster.Card?>?,
            menuHeads: List<MenuHead?>?,
            timing: MouseTiming?,
            phase: MousePhase?,
        ): MouseAction {
            if (!menuOpen || !isLeapMenu(menuTitle) || quadrant !in 0..3 || cards == null || timing == null ||
                phase == null || !hasDisplayedTeammateCard(quadrant, cards)
            ) return MouseAction(false, Optional.empty())
            val shouldTrigger = timing == if (phase == MousePhase.PRESS) MouseTiming.PRESS else MouseTiming.RELEASE
            val target = if (shouldTrigger) targetForQuadrant(true, menuTitle, quadrant, cards, menuHeads)
                else Optional.empty()
            return MouseAction(true, target)
        }

        private fun hasDisplayedTeammateCard(quadrant: Int, cards: List<SpiritLeapRoster.Card?>): Boolean =
            cards.any { card ->
                val member = card?.takeIf { it.quadrant() == quadrant }?.member()
                member != null && !member.empty()
            }

        /** Odin assigns the upper/lower and left/right screen halves to the four card quadrants. */
        @JvmStatic
        fun quadrantAt(mouseX: Double, mouseY: Double, screenWidth: Int, screenHeight: Int): Int {
            if (screenWidth <= 0 || screenHeight <= 0 || mouseX < 0 || mouseY < 0 ||
                mouseX >= screenWidth || mouseY >= screenHeight
            ) return -1
            // Match Odin's Java-era integer split: odd sizes put the center pixel in the right/bottom half.
            val column = if (mouseX >= (screenWidth / 2).toDouble()) 1 else 0
            val row = if (mouseY >= (screenHeight / 2).toDouble()) 1 else 0
            return row * 2 + column
        }

        private fun targetForMember(selected: SpiritLeapRoster.Member?, menuHeads: List<MenuHead?>): Optional<Target> {
            if (selected == null || selected.empty() || selected.dead()) return Optional.empty()
            for (head in menuHeads) {
                val name = validatedPlayerName(head)
                if (name != null && name.equals(selected.name(), ignoreCase = true)) {
                    return Optional.of(Target(name, selected.dungeonClass(), head!!.containerSlot))
                }
            }
            return Optional.empty()
        }

        /** Returns a name only for a real player head in one of the five Spirit Leap target slots. */
        @JvmStatic
        fun validatedPlayerName(head: MenuHead?): String? {
            if (head == null || !head.playerHead || head.containerSlot !in 11..15) return null
            return parsePlayerName(head.displayName)
        }

        @JvmStatic
        fun isLeapMenu(title: String?): Boolean =
            title != null && MENU_TITLES.contains(trimLikeJava(stripFormatting(title)).lowercase(Locale.ROOT))

        /** Extracts only the stable user-facing completion line; caller controls whether to announce. */
        @JvmStatic
        fun teleportedPlayer(plainChat: String?): String? =
            SpiritLeapArrivalRules.parse(plainChat).map { it.playerName }.orElse(null)

        @JvmStatic
        fun announcement(playerName: String?): String? {
            if (playerName == null || !ChatStringSemantics.isMinecraftUsername(playerName)) return null
            return "pc Leaped to $playerName!"
        }

        private fun parsePlayerName(displayName: String?): String? {
            if (displayName == null) return null
            val matcher = PLAYER_NAME.matcher(trimLikeJava(stripFormatting(displayName)))
            return if (matcher.matches()) matcher.group(1) else null
        }

        private fun stripFormatting(text: String) = ChatText.stripFormattingCodes(text)
        private fun trimLikeJava(text: String): String = text.trim { it <= ' ' }
        private fun normalize(text: String?): String = text?.let(::trimLikeJava)?.lowercase(Locale.ROOT).orEmpty()
        private fun normalizeKey(text: String?): String {
            val normalized = normalize(text)
            return if (normalized.startsWith("key.keyboard.")) normalized.removePrefix("key.keyboard.") else normalized
        }

        private fun normalizeClass(text: String?): String {
            val normalized = normalize(text)
            return if (normalized == "berserker") "berserk" else normalized
        }
    }
}
