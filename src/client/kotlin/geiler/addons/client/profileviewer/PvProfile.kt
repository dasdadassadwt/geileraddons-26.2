package geiler.addons.client.profileviewer

/** One SkyBlock profile of the viewed player, read from the public profiles API. Plain data, any thread. */
class PvProfile(
    val id: String,
    val cuteName: String,
    val selected: Boolean,
    val gameMode: String,
    val bank: Double?,
    val purse: Double,
    val fairySouls: Int,
    val skyblockLevel: Double,
    /** Skill XP by name without the `SKILL_` prefix (`COMBAT`, `MINING`...). */
    val skillXp: Map<String, Long>,
    val slayerXp: Map<String, Long>,
    /** Boss kills per slayer, index 0 is tier 1. */
    val slayerKills: Map<String, List<Int>>,
    val catacombsXp: Long,
    val classXp: Map<String, Long>,
    val selectedClass: String?,
    val secrets: Long,
    val completions: Map<Int, Int>,
    val masterCompletions: Map<Int, Int>,
    /** Fastest S+ per floor in milliseconds. */
    val fastestSPlus: Map<Int, Long>,
    val masterFastestSPlus: Map<Int, Long>,
    val pets: List<Pet>,
    val collections: Map<String, Long>,
    /** Skill trees, foraging, Crimson Isle, Rift, trophy fish, chocolate and other newer data. */
    val sections: PvSections,
    /** False when the player turned the inventory API off. */
    val inventoryApi: Boolean,
    /** Base64 gzipped NBT per container (`inv_contents`, `backpack_3`, `talisman_bag`...). */
    val inventories: Map<String, String>,
    /** Crafted minion tiers of every profile member (`WHEAT_11`); minions are shared on co-ops. */
    val craftedMinions: Set<String> = emptySet(),
    /** Co-op amount per collection and member uuid (collections are summed over members in [collections]). */
    val collectionsByMember: Map<String, Map<String, Long>> = emptyMap(),
    /** Wardrobe armor set ids with at least one piece (`wardrobe_<id>_<HELMET|CHESTPLATE|LEGGINGS|BOOTS>` in [inventories]). */
    val armorSets: List<Int> = emptyList(),
    /** Equipment set ids with at least one piece (`equipment_<id>_<1..4>` in [inventories]). */
    val equipmentSets: List<Int> = emptyList(),
    val loadouts: List<Loadout> = emptyList(),
    val memberIds: List<String> = emptyList(),
    /** Sack contents (`inventory.sacks_counts`): item id to amount. */
    val sacks: Map<String, Long> = emptyMap(),
    /** The member's own bank account (`profile.bank_account`), apart from the shared profile [bank]. */
    val personalBank: Double? = null,
) {
    class Pet(
        val type: String, val tier: String, val exp: Long, val active: Boolean, val heldItem: String?, val skin: String?,
        val uuid: String? = null, val candyUsed: Int = 0,
    ) {
        val level: Int get() = PvLevels.pet(tier, exp)
    }

    class Loadout(val id: Int, val name: String, val armorSet: Int?, val equipmentSet: Int?, val pet: String?, val powerStone: String?)

    /** Total runs over normal and Master Mode floors. */
    val totalRuns: Int get() = completions.values.sum() + masterCompletions.values.sum()

    fun skillLevel(skill: String): Double = minOf(PvLevels.skillByName(skill, skillXp[skill] ?: 0L), skillCap(skill).toDouble())

    fun skillCap(skill: String): Int = PvLevels.cap(skill, sections)

    fun classLevel(name: String): Double = PvLevels.catacombs(classXp[name] ?: 0L)

    val catacombsLevel: Double get() = PvLevels.catacombs(catacombsXp)

    /** Skill average over the skills that count for it (no runecrafting, social). */
    val skillAverage: Double
        get() = AVERAGE_SKILLS.sumOf { skillLevel(it).toInt().toDouble() } / AVERAGE_SKILLS.size

    companion object {
        /** Secrets per completed run; 0 without runs. */
        @JvmStatic
        fun secretAverage(secrets: Long, runs: Int): Double = if (runs <= 0) 0.0 else secrets.toDouble() / runs

        val SKILLS = listOf("FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "ENCHANTING", "ALCHEMY", "TAMING", "CARPENTRY", "HUNTING", "RUNECRAFTING", "SOCIAL")
        val AVERAGE_SKILLS = SKILLS.filter { it != "RUNECRAFTING" && it != "SOCIAL" }
        val SLAYERS = listOf("zombie", "spider", "wolf", "enderman", "blaze", "vampire")
        val CLASSES = listOf("healer", "mage", "berserk", "archer", "tank")
    }
}
