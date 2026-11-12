package geiler.addons.client.farming

import geiler.addons.client.tree.ChatText
import java.util.Locale

/** Name-based held-item filtering for the pest timer HUD. */
class PestToolFilter private constructor() {
    enum class Group { FARMING_TOOL, VACUUM, LASSO, OTHER }

    companion object {
        @JvmStatic
        fun classify(itemName: String?): Group {
            val name = ChatText.stripForMatch(itemName ?: throw NullPointerException()).lowercase(Locale.ROOT)
            if (name.isBlank()) return Group.OTHER
            if (name.contains("vacuum")) return Group.VACUUM
            if (name.contains("lasso")) return Group.LASSO
            if (name.contains("hoe") || name.contains("chopper") || name.contains("dicer") ||
                name.contains("scythe") || name.contains("knife") || name.contains("cutter") ||
                name.contains("sunder") || name.contains("melonking") || name.contains("farming axe")
            ) {
                return Group.FARMING_TOOL
            }
            return Group.OTHER
        }

        /** A disabled filter always shows; an enabled filter shows only selected item groups. */
        @JvmStatic
        fun shouldShow(
            filterEnabled: Boolean,
            farmingTool: Boolean,
            vacuum: Boolean,
            lasso: Boolean,
            mainHand: String?,
            offHand: String?,
        ): Boolean {
            if (!filterEnabled) return true
            return selected(classify(mainHand), farmingTool, vacuum, lasso) ||
                selected(classify(offHand), farmingTool, vacuum, lasso)
        }

        private fun selected(group: Group, farmingTool: Boolean, vacuum: Boolean, lasso: Boolean): Boolean =
            when (group) {
                Group.FARMING_TOOL -> farmingTool
                Group.VACUUM -> vacuum
                Group.LASSO -> lasso
                Group.OTHER -> false
            }
    }
}
