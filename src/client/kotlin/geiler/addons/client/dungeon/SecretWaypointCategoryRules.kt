package geiler.addons.client.dungeon

import java.util.regex.Pattern

/** Visibility rules for categorized built-in room markers. */
class SecretWaypointCategoryRules private constructor() {
    enum class Category { CHEST, ITEM, ESSENCE, BAT, REDSTONE_KEY, LEVER, ITEM_FRAME }

    companion object {
        private val ROOM_MARKER_ID = Pattern.compile(
            "^catalog:room:(?:Entrance|F[1-7]|M[1-7]):[0-5],[0-5]:devonian:[a-z0-9-]{1,64}:(chest|item|essence|bat|redstone|lever):[0-9]{1,3}$",
        )

        /**
         * Resolves only room-catalog IDs, whose stable template ID carries an authored category.
         * Personal markers and older fixed catalog entries have no category metadata and remain visible.
         */
        @JvmStatic
        fun categoryOf(markerId: String?): Category? {
            if (markerId == null) return null
            val matcher = ROOM_MARKER_ID.matcher(markerId)
            if (!matcher.matches()) return null
            return when (matcher.group(1)) {
                "chest" -> Category.CHEST
                "item" -> Category.ITEM
                "essence" -> Category.ESSENCE
                "bat" -> Category.BAT
                "redstone" -> Category.REDSTONE_KEY
                "lever" -> Category.LEVER
                else -> null
            }
        }

        /** Unknown or user-authored markers are not hidden by a built-in category switch. */
        @JvmStatic
        fun visible(
            markerId: String?,
            selection: SecretWaypointClickFeedbackRules.Selection?,
        ): Boolean {
            if (markerId == null) return false
            val category = categoryOf(markerId)
            if (category == null) return true
            return enabled(category, selection)
        }

        /** Returns whether a previously discovered live marker should remain after a toggle change. */
        @JvmStatic
        fun candidateVisible(
            label: String?,
            selection: SecretWaypointClickFeedbackRules.Selection?,
        ): Boolean {
            val category = when (label ?: "") {
                "Chest", "Chest candidate" -> Category.CHEST
                "Dropped Item", "Dropped item candidate" -> Category.ITEM
                "Essence", "Essence drop candidate" -> Category.ESSENCE
                "Bat", "Bat candidate" -> Category.BAT
                "Redstone Key", "Redstone key candidate" -> Category.REDSTONE_KEY
                "Lever", "Lever candidate" -> Category.LEVER
                "Item Frame", "Item frame candidate" -> Category.ITEM_FRAME
                else -> null
            }
            return category == null || enabled(category, selection)
        }

        /** Keeps the broad dropped-item switch from bypassing disabled essence or redstone categories. */
        @JvmStatic
        fun itemCandidateLabel(
            normalizedName: String?,
            selection: SecretWaypointClickFeedbackRules.Selection?,
        ): String? {
            if (normalizedName == null || selection == null) return null
            if (containsPhrase(normalizedName, "redstone key")) {
                return if (selection.redstoneKeys()) "Redstone key candidate" else null
            }
            if (containsPhrase(normalizedName, "essence")) {
                return if (selection.essenceDrops()) "Essence drop candidate" else null
            }
            return if (selection.droppedItems()) "Dropped item candidate" else null
        }

        private fun containsPhrase(value: String, phrase: String): Boolean =
            (" $value ").contains(" $phrase ")

        private fun enabled(
            category: Category,
            selection: SecretWaypointClickFeedbackRules.Selection?,
        ): Boolean {
            if (selection == null) return false
            return when (category) {
                Category.CHEST -> selection.chests()
                Category.ITEM -> selection.droppedItems()
                Category.ESSENCE -> selection.essenceDrops()
                Category.BAT -> selection.bats()
                Category.REDSTONE_KEY -> selection.redstoneKeys()
                Category.LEVER -> selection.levers()
                Category.ITEM_FRAME -> selection.droppedItems()
            }
        }
    }
}
