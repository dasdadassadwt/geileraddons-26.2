package geiler.addons.client.macros.killer

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import java.util.Locale
import java.util.regex.Pattern

/**
 * Mobs that are known by their name rather than by what they are.
 *
 * A server can make its own creatures out of anything: a player model with a skin, a zombie in armour, a floating armour
 * stand with a line of text over it. The entity type of such a thing says nothing - a hundred different creatures are
 * `minecraft:player` - and what tells them apart is the name on them. So the kill list can hold names as well as types,
 * as `name:treasure hoarder`.
 *
 * The name is not always on the creature itself. Servers put it on an invisible armour stand that floats over it, with the
 * level and the health in the same line: `[Lv61] Treasure Hoarder 15,000/15,000`. The words in that line are the name; the
 * level tag and the numbers are not, and are taken out before two names are compared. The numbers are not thrown away,
 * though: the health in it is the only health there is, since the client is told nothing true about the health of such a
 * creature.
 */
object MobNames {
    /** The start of a kill list entry that is a name and not a type. */
    const val PREFIX = "name:"

    /** How far from a creature a name tag may float and still be read as its own, in blocks. */
    private const val TAG_RADIUS = 1.0
    private const val TAG_BELOW = 0.6
    private const val TAG_ABOVE = 3.2

    private val BRACKETS = Pattern.compile("\\[[^\\]]*\\]")
    private const val AMOUNT = "\\d[\\d.,]*\\s*[kKmMbB]?"
    private val HEALTH = Pattern.compile("($AMOUNT)\\s*/\\s*($AMOUNT)")
    private val NUMBER = Pattern.compile("[\\d.,]+[kmb]?")
    private val LEVEL = Pattern.compile("lv\\.?\\d+")

    @JvmStatic
    fun isNameEntry(entry: String): Boolean = entry.startsWith(PREFIX)

    /** The words in a name, lower case, without formatting, level tag, health and symbols. Empty if there are none. */
    @JvmStatic
    fun normalize(text: String?): String {
        if (text == null) return ""
        var plain = ChatFormatting.stripFormatting(text) ?: return ""
        plain = BRACKETS.matcher(plain).replaceAll(" ")
        plain = HEALTH.matcher(plain).replaceAll(" ")

        val letters = StringBuilder(plain.length)
        for (c in plain) letters.append(if (Character.isLetterOrDigit(c) || c == '\'' || c == '-') c else ' ')

        val words = StringBuilder()
        for (token in letters.toString().lowercase(Locale.ROOT).trim().split(Regex("\\s+"))) {
            if (token.isEmpty() || NUMBER.matcher(token).matches() || LEVEL.matcher(token).matches() ||
                token.none { Character.isLetterOrDigit(it) }
            ) {
                continue
            }
            if (words.isNotEmpty()) words.append(' ')
            words.append(token)
        }
        return words.toString()
    }

    /** Whether this is a real person playing, as opposed to a creature a server has made out of a player entity. */
    @JvmStatic
    fun isRealPlayer(entity: Entity): Boolean {
        if (entity !is Player) return false
        // Servers make their creatures out of player entities with their own ids, which are not the version 4 (random)
        // ids an account has, and keep them out of the player list. A player with an account has both.
        val id = entity.uuid
        val connection = Minecraft.getInstance().connection
        val listed = connection != null && connection.getPlayerInfo(id) != null
        return id.version() == 4 && listed
    }

    /** Whether this entity could be a creature worth attacking at all: alive things that are neither a person nor a name tag. */
    @JvmStatic
    fun isCreature(entity: Entity): Boolean = entity is LivingEntity && entity !is ArmorStand && !isRealPlayer(entity)

    /** Every text that names this entity, the floating name tags first: what is over its head, then its own name. */
    @JvmStatic
    fun rawNames(level: ClientLevel, entity: Entity): List<String> {
        val names = ArrayList<String>(4)
        for (tag in tagsOf(level, entity)) names.add(tag.customName!!.string)
        val custom = entity.customName
        if (custom != null) names.add(custom.string)
        names.add(entity.displayName.string)
        return names
    }

    private fun tagsOf(level: ClientLevel, entity: Entity): List<Entity> {
        val around = entity.boundingBox.inflate(TAG_RADIUS, 0.0, TAG_RADIUS).expandTowards(0.0, TAG_ABOVE, 0.0)
            .expandTowards(0.0, -TAG_BELOW, 0.0)
        return level.getEntities(entity, around) { isTag(it) }
    }

    /** A name tag: something with a name of its own that is not a creature - in practice an armour stand. */
    private fun isTag(entity: Entity): Boolean =
        entity.customName != null && entity !is Player && (entity is ArmorStand || entity !is LivingEntity)

    /**
     * What to put on the kill list for this creature if it has a name of its own, as the words of the name, or an empty
     * string if it has none - a plain zombie is only ever "a zombie", and that is its type's business.
     */
    @JvmStatic
    fun nameOf(level: ClientLevel, entity: Entity): String {
        for (tag in tagsOf(level, entity)) {
            val name = normalize(tag.customName!!.string)
            if (name.isNotEmpty()) return name
        }
        val custom = entity.customName
        if (custom != null) {
            val name = normalize(custom.string)
            if (name.isNotEmpty()) return name
        }
        if (entity is Player) return normalize(entity.displayName.string)
        return ""
    }

    /** Whether the creature goes by the name, as whole words: "wolf" is a wolf and not a werewolf. */
    @JvmStatic
    fun matches(level: ClientLevel, entity: Entity, name: String): Boolean {
        val wanted = " $name "
        for (raw in rawNames(level, entity)) {
            if (" ${normalize(raw)} ".contains(wanted)) return true
        }
        return false
    }

    /**
     * The share of its health a creature has left, read off the numbers in its name tag - `15,000/15,000` - or NaN if no
     * tag shows any.
     */
    @JvmStatic
    fun healthFraction(level: ClientLevel, entity: Entity): Double = healthFractionOf(rawNames(level, entity))

    /** [healthFraction] for the texts that name a creature, in the order they are read. */
    @JvmStatic
    fun healthFractionOf(rawNames: List<String>): Double {
        for (raw in rawNames) {
            val plain = ChatFormatting.stripFormatting(raw) ?: continue
            val matcher = HEALTH.matcher(plain)
            while (matcher.find()) {
                val current = amount(matcher.group(1))
                val maximum = amount(matcher.group(2))
                if (maximum > 0.0 && current >= 0.0 && current <= maximum * 1.0001) return current / maximum
            }
        }
        return Double.NaN
    }

    /** `15,000` is 15000, `1.5M` is 1500000. NaN if it is neither. */
    private fun amount(text: String): Double {
        var compact = text.replace(",", "").replace(" ", "")
        var scale = 1.0
        val last = Character.toLowerCase(compact[compact.length - 1])
        if (last == 'k' || last == 'm' || last == 'b') {
            scale = if (last == 'k') 1.0E3 else if (last == 'm') 1.0E6 else 1.0E9
            compact = compact.substring(0, compact.length - 1)
        }
        return try {
            compact.toDouble() * scale
        } catch (_: NumberFormatException) {
            Double.NaN
        }
    }
}
