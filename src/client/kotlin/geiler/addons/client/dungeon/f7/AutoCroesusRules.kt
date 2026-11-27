package geiler.addons.client.dungeon.f7

/**
 * Auto Croesus without Minecraft types: what to click on each screen of a claim run. The menu stays open the whole time:
 * Croesus list, a run's chest preview, a chest, and back with the menu's own Go Back arrow, never by closing the screen
 * and clicking the NPC again.
 *
 * On the list: the first run with no chest opened that was not skipped, else the next page, else done. On a preview:
 * Kismet the Bedrock chest when wanted, claim the best chest (or skip the run when it is below the minimum profit), then
 * the key chest when wanted, then back. On a chest: claim or reroll, then back.
 */
object AutoCroesusRules {
    enum class Goal { NONE, CLAIM, KEY, KISMET }

    enum class Action { RUN, PAGE, DONE, CHEST, CLAIM, KISMET, BACK, SKIP, WAIT }

    /** What happened in the run being claimed. */
    class Run {
        var goal = Goal.NONE
        var claimedSlot = -1
        var keyed = false
        var kismetted = false

        fun claimed(): Boolean = claimedSlot >= 0

        companion object {
            /** A run picked from the menu; one already rerolled never rerolls again. */
            @JvmStatic
            fun from(decision: Decision): Run = Run().also { it.kismetted = decision.reason.endsWith("rerolled") }
        }
    }

    class Decision(val action: Action, val slot: Int, val reason: String) {
        override fun toString(): String = "$action slot=$slot $reason"
    }

    class Settings(
        val minProfit: Double,
        val useKeys: Boolean,
        val keyMinProfit: Double,
        val keyPrice: Double,
        val kismet: Boolean,
        val kismetMinProfit: Double,
        val kismetFloors: String,
    )

    /**
     * A run head on the Croesus list: its slot, whether no chest of it was opened yet, and whether its Kismet Feather is
     * struck through (a chest of it was already rerolled; rerolling does not open a chest, so the run stays fresh).
     */
    class MenuRun @JvmOverloads constructor(val slot: Int, val fresh: Boolean, val rerolled: Boolean = false)

    const val FRESH_LINE = "No chests opened yet!"

    @JvmStatic
    fun menu(runs: List<MenuRun>, skipped: Set<Int>, nextPageSlot: Int): Decision {
        val run = runs.firstOrNull { it.fresh && it.slot !in skipped }
        if (run != null) return Decision(Action.RUN, run.slot, if (run.rerolled) "fresh run rerolled" else "fresh run")
        if (nextPageSlot >= 0) return Decision(Action.PAGE, nextPageSlot, "next page")
        return Decision(Action.DONE, -1, "no fresh runs left")
    }

    /** The floor of a preview title: "F7", "M7", "E" for the Entrance, or null. */
    @JvmStatic
    fun floorCode(title: String): String? {
        val master = title.startsWith("Master ")
        val part = title.substringAfter(" - ", "").trim()
        if (part == "Entrance") return "E"
        val roman = part.removePrefix("Floor ").trim()
        val number = ROMAN[roman] ?: roman.toIntOrNull() ?: return null
        return (if (master) "M" else "F") + number
    }

    /** Whether [floor] is in the comma-separated [list] ("M7, M6"); an empty list allows every floor. */
    @JvmStatic
    fun floorAllowed(floor: String?, list: String): Boolean {
        val wanted = list.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return wanted.isEmpty() || (floor != null && floor.uppercase() in wanted)
    }

    @JvmStatic
    fun preview(chests: List<CroesusRules.ChestResult>, run: Run, s: Settings, floor: String?, backSlot: Int): Decision {
        val ranked = CroesusRules.sorted(chests)
        val bedrock = chests.firstOrNull { it.name.startsWith("Bedrock") }
        if (s.kismet && !run.claimed() && !run.kismetted && bedrock != null && bedrock.profit < s.kismetMinProfit &&
            floorAllowed(floor, s.kismetFloors)
        ) {
            run.goal = Goal.KISMET
            return Decision(Action.CHEST, bedrock.slot, "kismet bedrock profit=${CroesusRules.money(bedrock.profit)}")
        }
        if (!run.claimed()) {
            val best = ranked.firstOrNull()
            if (best == null || best.profit < s.minProfit) {
                return Decision(Action.SKIP, backSlot, "best ${best?.let { CroesusRules.money(it.profit) } ?: "none"} below minimum")
            }
            run.goal = Goal.CLAIM
            return Decision(Action.CHEST, best.slot, "claim ${best.name} profit=${CroesusRules.money(best.profit)}")
        }
        if (s.useKeys && !run.keyed) {
            val second = ranked.firstOrNull { it.slot != run.claimedSlot && it.profit - s.keyPrice >= s.keyMinProfit }
            if (second != null) {
                run.goal = Goal.KEY
                return Decision(Action.CHEST, second.slot, "key ${second.name} profit=${CroesusRules.money(second.profit - s.keyPrice)}")
            }
        }
        return Decision(Action.BACK, backSlot, "run done")
    }

    /** On a chest: claim or reroll for the run's goal, then back. */
    @JvmStatic
    fun chest(run: Run, chestSlot: Int, claimSlot: Int, kismetSlot: Int, kismetAvailable: Boolean, backSlot: Int): Decision =
        when (run.goal) {
            Goal.CLAIM, Goal.KEY ->
                if (claimSlot >= 0) Decision(Action.CLAIM, claimSlot, "open ${run.goal}") else Decision(Action.WAIT, -1, "no claim button")
            Goal.KISMET ->
                if (kismetAvailable && kismetSlot >= 0) Decision(Action.KISMET, kismetSlot, "reroll")
                else {
                    run.kismetted = true
                    run.goal = Goal.NONE
                    Decision(Action.BACK, backSlot, "no kismet feather")
                }
            Goal.NONE -> Decision(Action.BACK, backSlot, "chest done")
        }.also { if (it.action == Action.CLAIM && run.goal == Goal.CLAIM) run.claimedSlot = chestSlot }

    /** After a click was sent: the run moves on. */
    @JvmStatic
    fun afterClick(run: Run, decision: Decision) {
        when (decision.action) {
            Action.CLAIM -> {
                if (run.goal == Goal.KEY) run.keyed = true
                run.goal = Goal.NONE
            }
            Action.KISMET -> {
                run.kismetted = true
                run.goal = Goal.NONE
            }
            else -> Unit
        }
    }

    private val ROMAN = mapOf("I" to 1, "II" to 2, "III" to 3, "IV" to 4, "V" to 5, "VI" to 6, "VII" to 7)
}
