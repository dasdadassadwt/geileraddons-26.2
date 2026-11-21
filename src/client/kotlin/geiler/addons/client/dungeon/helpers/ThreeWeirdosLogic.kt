package geiler.addons.client.dungeon.helpers

import java.util.ArrayList
import java.util.Locale
import java.util.OptionalInt
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmSuppressWildcards

/** Stable Java-callable API for the Kotlin-backed Three Weirdos solver. */
object ThreeWeirdosLogic {
    private const val NPC_NAME = "[a-z0-9_]{1,16}"
    private val NAME_CHEST = Pattern.compile("the reward is (not )?in ([a-z0-9_]{1,16})['’]s chest")
    private val NAME_HAS_REWARD = Pattern.compile(
        "([a-z0-9_]{1,16}) (has|does not have|doesn't have) the reward(?: in (?:their|his|her) chest)?",
    )
    private val NAME_CHEST_HAS_REWARD = Pattern.compile(
        "([a-z0-9_]{1,16})['’]s chest (has|does not have|doesn't have) the reward",
    )
    private val NAMED_TRUTH = Pattern.compile("([a-z0-9_]{1,16}) is telling the truth")
    private val NAMED_LYING = Pattern.compile("([a-z0-9_]{1,16}) is lying")
    private val NAMED_TRUTH_WITH_REWARD = Pattern.compile(
        "([a-z0-9_]{1,16}) is telling the truth and the reward is in his chest",
    )
    private val SUPPORTED_STATEMENT = Pattern.compile(
        "^(?:" +
            "the reward is (?:not )?in my chest|my chest (?:does not have|doesn't have|has) the reward|" +
            "the reward is (?:not )?in any of our chests|the reward is (?:not )?in " + NPC_NAME + "['’]s chest|" +
            NPC_NAME + " (?:has|does not have|doesn't have) the reward(?: in (?:their|his|her) chest)?|" +
            NPC_NAME + "['’]s chest (?:has|does not have|doesn't have) the reward|" +
            "we are all telling the truth|both of them are telling the truth|they are both telling the truth|" +
            "at least one of them is lying|one of the others is lying|at least one of the others is telling the truth|" +
            "one of us is telling the truth|they are both lying|both of them are lying|" +
            NPC_NAME + " is telling the truth|" + NPC_NAME + " is lying|" +
            "at least one of them is lying and the reward is not in " + NPC_NAME + "['’]s chest|" +
            "my chest (?:does not have|doesn't have) the reward we are all telling the truth|" +
            "my chest has the reward and i(?:'m| am) telling the truth|" +
            "both of them are telling the truth also " + NPC_NAME + " has the reward in their chest|" +
            "they are both telling the truth the reward is not in " + NPC_NAME + "['’]s chest|" +
            NPC_NAME + " is telling the truth and the reward is in his chest|" +
            "my chest (?:does not have|doesn't have) the reward at least one of the others is telling the truth|" +
            "they are both telling the truth the reward is in " + NPC_NAME + "['’]s chest|" +
            "they are both lying the reward is in my chest|" +
            "the reward is not in my chest they are both lying)$",
    )
    private val COLOR_CODE = Pattern.compile("§[0-9a-fk-or]")
    private val UNSUPPORTED_CHARACTERS = Pattern.compile("[^a-z0-9_’' ]")
    private val WHITESPACE = Pattern.compile("\\s+")

    /** Accepts only complete, independently supported clue phrases; unknown compound text stays unknown. */
    @JvmStatic
    @JvmSuppressWildcards
    fun supportsStatement(statement: String?): Boolean {
        if (statement == null) return false
        val line = normalize(statement).replace('’', '\'').replace("isn't in", "is not in")
        return SUPPORTED_STATEMENT.matcher(line).matches()
    }

    /** Returns a suspect only when every consistent truth assignment agrees on the reward chest. */
    @JvmStatic
    @JvmSuppressWildcards
    fun solve(speakers: List<String?>?, statements: List<String?>?): OptionalInt {
        if (speakers == null || speakers.size != 3 || statements == null || statements.size != 3) {
            return OptionalInt.empty()
        }
        val names = speakers.map(::normalize)
        if (names.any { it.isBlank() } || names.distinct().size != 3) return OptionalInt.empty()
        val parsed = ArrayList<List<Claim>>(3)
        for (speaker in 0 until 3) parsed.add(parse(speaker, statements[speaker], names))
        if (parsed.any { it.isEmpty() }) return OptionalInt.empty()

        var agreedHolder = -1
        for (holder in 0 until 3) {
            for (truthMask in 0 until 8) {
                val truths = booleanArrayOf(
                    (truthMask and 1) != 0,
                    (truthMask and 2) != 0,
                    (truthMask and 4) != 0,
                )
                var consistent = true
                for (speaker in 0 until 3) {
                    if (!consistent) break
                    var statementTruth = true
                    for (claim in parsed[speaker]) {
                        if (!claim.holds(speaker, holder, truths)) {
                            statementTruth = false
                            break
                        }
                    }
                    consistent = statementTruth == truths[speaker]
                }
                if (!consistent) continue
                if (agreedHolder >= 0 && agreedHolder != holder) return OptionalInt.empty()
                agreedHolder = holder
            }
        }
        return if (agreedHolder < 0) OptionalInt.empty() else OptionalInt.of(agreedHolder)
    }

    private fun parse(speaker: Int, statement: String?, names: List<String>): List<Claim> {
        val line = normalize(statement).replace('’', '\'').replace("isn't in", "is not in")
        if (!supportsStatement(line)) return emptyList()
        val claims = ArrayList<Claim>()
        if (line.contains("the reward is not in any of our chests") || line.contains("the reward isn't in any of our chests")) {
            claims.add(Claim { _, _, _ -> false })
        }
        if (line.contains("my chest doesn't have the reward") || line.contains("my chest does not have the reward") ||
            line.contains("the reward is not in my chest") || line.contains("the reward isn't in my chest")
        ) {
            claims.add(Claim { index, holder, _ -> holder != index })
        }
        if (line.contains("my chest has the reward") || line.contains("the reward is in my chest")) {
            claims.add(Claim { index, holder, _ -> holder == index })
        }
        if (line.contains("i'm telling the truth") || line.contains("i am telling the truth")) {
            claims.add(Claim { index, _, truths -> truths[index] })
        }

        val namedChest = NAME_CHEST.matcher(line)
        while (namedChest.find()) {
            val target = names.indexOf(namedChest.group(2))
            if (target < 0) return emptyList()
            val negated = namedChest.group(1) != null
            claims.add(Claim { _, holder, _ -> (holder == target) != negated })
        }
        val namedHasReward = NAME_HAS_REWARD.matcher(line)
        while (namedHasReward.find()) {
            val target = names.indexOf(namedHasReward.group(1))
            if (target < 0) return emptyList()
            val negated = namedHasReward.group(2) != "has"
            claims.add(Claim { _, holder, _ -> (holder == target) != negated })
        }
        val namedChestHasReward = NAME_CHEST_HAS_REWARD.matcher(line)
        while (namedChestHasReward.find()) {
            val target = names.indexOf(namedChestHasReward.group(1))
            if (target < 0) return emptyList()
            val negated = namedChestHasReward.group(2) != "has"
            claims.add(Claim { _, holder, _ -> (holder == target) != negated })
        }
        if (line.contains("we are all telling the truth")) {
            claims.add(Claim { _, _, truths -> truths[0] && truths[1] && truths[2] })
        }
        if (line.contains("both of them are telling the truth") || line.contains("they are both telling the truth")) {
            claims.add(Claim { index, _, truths -> truths[(index + 1) % 3] && truths[(index + 2) % 3] })
        }
        if (line.contains("at least one of them is lying") || line.contains("one of the others is lying")) {
            claims.add(Claim { index, _, truths -> !truths[(index + 1) % 3] || !truths[(index + 2) % 3] })
        }
        if (line.contains("at least one of the others is telling the truth")) {
            claims.add(Claim { index, _, truths -> truths[(index + 1) % 3] || truths[(index + 2) % 3] })
        }
        if (line.contains("one of us is telling the truth")) {
            claims.add(Claim { _, _, truths -> truths.count { it } == 1 })
        }
        if (line.contains("they are both lying") || line.contains("both of them are lying")) {
            claims.add(Claim { index, _, truths -> !truths[(index + 1) % 3] && !truths[(index + 2) % 3] })
        }

        val namedTruth = NAMED_TRUTH.matcher(line)
        while (namedTruth.find()) {
            val target = names.indexOf(namedTruth.group(1))
            if (target < 0) return emptyList()
            claims.add(Claim { _, _, truths -> truths[target] })
        }
        val namedTruthWithReward = NAMED_TRUTH_WITH_REWARD.matcher(line)
        while (namedTruthWithReward.find()) {
            val target = names.indexOf(namedTruthWithReward.group(1))
            if (target < 0) return emptyList()
            claims.add(Claim { _, holder, _ -> holder == target })
        }
        val namedLying = NAMED_LYING.matcher(line)
        while (namedLying.find()) {
            val target = names.indexOf(namedLying.group(1))
            if (target < 0) return emptyList()
            claims.add(Claim { _, _, truths -> !truths[target] })
        }
        return claims.toList()
    }

    private fun normalize(text: String?): String {
        if (text == null) return ""
        val lower = text.lowercase(Locale.ROOT)
        val withoutColors = COLOR_CODE.matcher(lower).replaceAll("")
        val normalizedCharacters = UNSUPPORTED_CHARACTERS.matcher(withoutColors).replaceAll(" ")
        return WHITESPACE.matcher(normalizedCharacters).replaceAll(" ").trim()
    }

    private fun interface Claim {
        fun holds(speaker: Int, holder: Int, truths: BooleanArray): Boolean
    }
}
