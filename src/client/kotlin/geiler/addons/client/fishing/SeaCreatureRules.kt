package geiler.addons.client.fishing

/**
 * The rare sea creature catalog and alert rules, after SkyHanni's SeaCreatureFeatures and its `SeaCreatures`
 * repo constant (rare entries only; common creatures never alert). Names, catch lines, rarities and chat colors
 * are game facts; the code is local.
 */
object SeaCreatureRules {
    class Creature(
        private val name: String,
        private val rarityColor: String,
        private val chatColor: String,
        private val catchMessage: String,
        private val oldNames: List<String> = emptyList(),
    ) {
        fun name(): String = name
        fun rarityColor(): String = rarityColor
        fun chatColor(): String = chatColor
        fun catchMessage(): String = catchMessage
        internal val pattern: Regex = Regex(
            (listOf(name) + oldNames).joinToString("|", "(?<![A-Za-z])(?:", ")(?![A-Za-z])") { Regex.escape(it) },
        )
    }

    private const val LEGENDARY = "§6"
    private const val MYTHIC = "§d"
    private const val RARE_COLOR = "§9"
    private const val WATER = "§b"
    private const val WINTER = "§f"
    private const val SPOOKY = "§5"
    private const val SHARK = "§3"
    private const val LAVA = "§c"
    private const val BAYOU = "§2"
    private const val MARSH = "§2"
    private const val LOTUS = "§3"
    private const val HOTSPOT = "§d"
    private const val TORRHUS = "§6"

    private val RARE = listOf(
        Creature("Carrot King", RARE_COLOR, WATER, "Is this even a fish? It's the Carrot King!"),
        Creature("Water Hydra", LEGENDARY, WATER, "The Water Hydra has come to test your strength."),
        Creature("Yeti", LEGENDARY, WINTER, "What is this creature!?"),
        Creature("Reindrake", MYTHIC, WINTER, "A Reindrake forms from the depths."),
        Creature("Phantom Fisher", LEGENDARY, SPOOKY, "The spirit of a long lost Phantom Fisher has come to haunt you."),
        Creature("Grim Reaper", LEGENDARY, SPOOKY, "This can't be! The manifestation of death himself!"),
        Creature(
            "Great White Shark", LEGENDARY, SHARK,
            "Hide no longer, a Great White Shark has tracked your scent and thirsts for your blood!",
        ),
        Creature("Abyssal Miner", LEGENDARY, WATER, "An Abyssal Miner breaks out of the water!"),
        Creature("Thunder", MYTHIC, LAVA, "You hear a massive rumble as Thunder emerges."),
        Creature("Lord Jawbus", MYTHIC, LAVA, "You have angered a legendary creature... Lord Jawbus has arrived."),
        Creature("Plhlegblast", MYTHIC, LAVA, "WOAH! A Plhlegblast appeared.", listOf("Phlhlegblast")),
        Creature("Alligator", LEGENDARY, BAYOU, "A long snout breaks the surface of the water. It's an Alligator!"),
        Creature(
            "Titanoboa", MYTHIC, BAYOU,
            "A massive Titanoboa surfaces. Its body stretches as far as the eye can see.",
        ),
        Creature(
            "Ragnarok", MYTHIC, HOTSPOT,
            "The sky darkens and the air thickens. The end times are upon us: Ragnarok is here.",
        ),
        Creature(
            "Fiery Scuttler", LEGENDARY, HOTSPOT,
            "A Fiery Scuttler inconspicuously waddles up to you, friends in tow.",
        ),
        Creature(
            "Wiki Tiki", MYTHIC, HOTSPOT,
            "The water bubbles and froths. A massive form emerges- you have disturbed the Wiki Tiki! You shall pay the price.",
        ),
        Creature(
            "Blue Ringed Octopus", LEGENDARY, HOTSPOT,
            "A garish set of tentacles arise. It's a Blue Ringed Octopus!",
        ),
        Creature(
            "The Loch Emperor", LEGENDARY, MARSH, "The Loch Emperor arises from the depths.",
            listOf("The Sea Emperor", "Sea Emperor"),
        ),
        Creature("Nessie", MYTHIC, MARSH, "You've caused a disturbance in the loch. Could it be... Nessie?"),
        Creature(
            "Puddle Jumper", LEGENDARY, LOTUS,
            "A Puddle Jumper is preparing for liftoff—cast your rod into it and hold on tight!",
        ),
        Creature("Frog Prince", MYTHIC, LOTUS, "Bow down before the Frog Prince... or pay the hefty price!"),
        Creature("Silkbreeze", LEGENDARY, TORRHUS, "Something zips through the air - it's a Silkbreeze!"),
        Creature("Giant Isopod", MYTHIC, TORRHUS, "A Giant Isopod was dredged up from the depths!"),
    )

    private val BY_MESSAGE = RARE.associateBy { it.catchMessage() }
    private val DOUBLE_HOOK = Regex("It's a Double Hook!(?: Woot woot!)?")

    /** A creature seen right after your catch, close to where your bobber was, is yours. */
    private const val OWN_WINDOW_NANOS = 2_500_000_000L
    private const val OWN_RADIUS = 6.0

    @JvmStatic
    fun rare(): List<Creature> = RARE

    /** The rare creature a clean chat line announces, matched exactly, or null. */
    @JvmStatic
    fun byCatchMessage(clean: String): Creature? = BY_MESSAGE[clean]

    @JvmStatic
    fun isDoubleHook(clean: String): Boolean = DOUBLE_HOOK.matches(clean)

    /** The rare creature a mob name tag such as `[Lv600] Lord Jawbus 100M❤` names, or null. */
    @JvmStatic
    fun byNametag(plain: String): Creature? {
        if (!plain.startsWith("[Lv")) return null
        return RARE.firstOrNull { it.pattern.containsMatchIn(plain) }
    }

    @JvmStatic
    fun ownTitle(creature: Creature, showName: Boolean): String =
        if (showName) "${creature.chatColor()}${creature.name()}!" else "${creature.rarityColor()}RARE CATCH!"

    @JvmStatic
    fun nearbyTitle(creature: Creature, showName: Boolean): String =
        if (showName) "${creature.chatColor()}${creature.name()} NEARBY!" else "${creature.rarityColor()}RARE SEA CREATURE!"

    @JvmStatic
    fun partyMessage(creature: Creature, doubleHook: Boolean): String {
        val name = creature.name()
        val article = when {
            name.startsWith("The ") -> ""
            name.first() in "AEIOU" -> "an "
            else -> "a "
        }
        return (if (doubleHook) "DOUBLE HOOK: " else "") + "I caught $article$name!"
    }

    @JvmStatic
    fun isOwn(catchNanos: Long, seenNanos: Long, distanceToBobber: Double): Boolean {
        if (catchNanos == Long.MIN_VALUE) return false
        val age = seenNanos - catchNanos
        return age in 0..OWN_WINDOW_NANOS && distanceToBobber <= OWN_RADIUS
    }

    /** Entity ids already alerted on, each remembered for [ttlNanos]; SkyHanni keeps them six minutes. */
    class SeenSet(private val ttlNanos: Long) {
        private val seen = HashMap<Int, Long>()

        fun firstSight(id: Int, nowNanos: Long): Boolean {
            val at = seen[id]
            if (at != null && nowNanos - at < ttlNanos) return false
            if (seen.size >= MAX_ENTRIES) seen.entries.removeIf { nowNanos - it.value >= ttlNanos }
            if (seen.size < MAX_ENTRIES) seen[id] = nowNanos
            return true
        }

        fun clear() = seen.clear()

        fun size(): Int = seen.size

        private companion object {
            const val MAX_ENTRIES = 512
        }
    }
}
