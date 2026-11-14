package geiler.addons.client.macros.killer

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobCategory
import java.util.Locale

/** Entity ids as the player types and reads them: short for vanilla mobs, checked against the registry. */
object MobIds {
    /**
     * The entry as it is stored. A type is lower case and with a namespace; a name is `name:` and the words of the name,
     * see [MobNames].
     */
    @JvmStatic
    fun normalize(id: String): String {
        val trimmed = id.trim().lowercase(Locale.ROOT)
        if (MobNames.isNameEntry(trimmed)) return MobNames.PREFIX + MobNames.normalize(trimmed.substring(MobNames.PREFIX.length))
        return if (trimmed.contains(":")) trimmed else "minecraft:$trimmed"
    }

    /** The kill list entry for a name, or null if the text has no words in it. */
    @JvmStatic
    fun nameEntry(text: String): String? {
        val name = MobNames.normalize(
            if (text.trim().lowercase(Locale.ROOT).startsWith(MobNames.PREFIX)) text.trim().substring(MobNames.PREFIX.length) else text,
        )
        return if (name.isEmpty()) null else MobNames.PREFIX + name
    }

    /** Whether this is a real entity type that is a creature rather than an item, projectile or the like. */
    @JvmStatic
    fun isHuntable(id: String): Boolean {
        val identifier = Identifier.tryParse(normalize(id))
        if (identifier == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(identifier)) return false
        return BuiltInRegistries.ENTITY_TYPE.getValue(identifier).category != MobCategory.MISC
    }

    /** A readable name for a stored entry: `minecraft:cave_spider` becomes `Cave Spider`, so does `name:cave spider`. */
    @JvmStatic
    fun label(id: String): String {
        val path = if (id.contains(":")) id.substring(id.indexOf(':') + 1) else id
        val label = StringBuilder()
        for (word in path.split(Regex("[_ ]"))) {
            if (word.isEmpty()) continue
            if (label.isNotEmpty()) label.append(' ')
            label.append(Character.toUpperCase(word[0])).append(word.substring(1))
        }
        return label.toString()
    }

    /** What to call this creature: its name if it goes by one, otherwise what it is. */
    @JvmStatic
    fun label(entity: Entity): String {
        val level = Minecraft.getInstance().level
        val name = if (level == null) "" else MobNames.nameOf(level, entity)
        return if (name.isEmpty()) label(BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).path) else label(name)
    }
}
