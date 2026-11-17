package geiler.addons.client.fishing

import java.util.Locale

/** Bait reading, display text and warning rules, after SkyHanni's FishingBaitDisplay and FishingBaitWarnings. */
object FishingBaitRules {
    /** Bait sits in the last hotbar slot. */
    const val BAIT_HOTBAR_SLOT = 8

    private val REMAINING = Regex("Bait Remaining: ([\\d,]+)")

    /** The bait count from a stack's plain lore, or null when the lore has no `Bait Remaining` line. */
    @JvmStatic
    fun amount(lore: List<String>): Int? {
        for (line in lore) {
            val match = REMAINING.find(line) ?: continue
            return match.groupValues[1].replace(",", "").toIntOrNull()
        }
        return null
    }

    /** `§b1,234x Name`, the bare name when the amount is unknown, or `§cNo Bait`. */
    @JvmStatic
    fun displayText(name: String?, amount: Int?): String {
        if (name == null) return NO_BAIT_TEXT
        return amountPrefix(amount) + name
    }

    /** `§b1,234x ` or nothing when the amount is unknown. */
    @JvmStatic
    fun amountPrefix(amount: Int?): String =
        if (amount == null) "" else "§b${String.format(Locale.ROOT, "%,d", amount)}x "

    const val NO_BAIT_TEXT = "§cNo Bait"

    enum class Kind { CHANGED, NO_BAIT }

    class Warning(val kind: Kind, val title: String, val chat: String) {
        fun kind(): Kind = kind
        fun title(): String = title
        fun chat(): String = chat
    }

    /** The change / no-bait state machine. Bait is compared by its identity string, not its count. */
    class Warnings {
        private var lastBait: String? = null
        private var wasUsingBait = true

        fun reset() {
            lastBait = null
            wasUsingBait = true
        }

        /** One bait update: the held-rod flag and the current bait identity, or null for none. */
        fun onUpdate(holdingRod: Boolean, bait: String?): Warning? {
            if (!holdingRod) {
                wasUsingBait = false
                lastBait = null
                return null
            }
            val before = lastBait
            val changed = before != null && before != bait
            wasUsingBait = bait != null
            lastBait = bait
            if (!changed) return null
            return Warning(
                Kind.CHANGED,
                "§eBait changed!",
                "Fishing Bait changed: ${before ?: "None"} §e-> ${bait ?: "None"}",
            )
        }

        /** A bobber was cast; warns when the last update said no bait was in use. */
        fun onCast(): Warning? =
            if (wasUsingBait) null
            else Warning(Kind.NO_BAIT, "§cNo bait is used!", "You're not using any fishing baits!")
    }
}
