package geiler.addons.client.itemlist

import com.google.common.collect.ImmutableMultimap
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import java.util.UUID

/**
 * Since July 2026 NotEnoughUpdates lists about a thousand former skull items as plain `minecraft:paper` with a
 * `hypixel_skyblock:` item model; they only look right with Hypixel's SkyBlock resource pack. This table gives each of them
 * the head texture (or vanilla item) they had before, for when that pack is not loaded. Data: Noamm9's PackDisabler
 * `skyblock-items.json` (CC0), cut down to the ids that are paper in the NEU repository (see docs/provenance/external-sources.md).
 * Values are a base64 skin texture, or `minecraft:<item>` for a vanilla stand-in.
 */
object ItemFallbacks {
    private const val RESOURCE = "/geileraddons/item-fallbacks.json"
    private const val PAPER = "minecraft:paper"

    private val table: Map<String, String> by lazy {
        val text = ItemFallbacks::class.java.getResourceAsStream(RESOURCE)?.use { String(it.readAllBytes(), Charsets.UTF_8) } ?: ""
        parse(text)
    }

    val size: Int get() = table.size

    /** Id to value from [json]; anything that is not a non-empty string is skipped, bad json gives an empty table. */
    @JvmStatic
    fun parse(json: String): Map<String, String> {
        val root = try {
            JsonParser.parseString(json)
        } catch (_: RuntimeException) {
            return emptyMap()
        }
        if (!root.isJsonObject) return emptyMap()
        val result = HashMap<String, String>()
        for ((id, value) in root.asJsonObject.entrySet()) {
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotEmpty()) result[id] = value.asString
        }
        return result
    }

    /** A paper item that only looks right with Hypixel's pack. */
    @JvmStatic
    fun isPaperModel(entry: Entry): Boolean = entry.mcId == PAPER && entry.nbt.contains("hypixel_skyblock:")

    @JvmStatic
    fun covers(entry: Entry): Boolean = isPaperModel(entry) && table.containsKey(entry.id)

    /** [stack] (the converted NEU item of [entry]) as its pre-July look when it is a paper item with a fallback, else [stack] itself. */
    @JvmStatic
    fun apply(entry: Entry, stack: ItemStack): ItemStack {
        if (entry.mcId != PAPER) return stack
        val value = table[entry.id] ?: return stack
        if (value.startsWith("minecraft:")) {
            val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(value)).orElse(null) ?: return stack
            return stack.transmuteCopy(item)
        }
        val head = stack.transmuteCopy(Items.PLAYER_HEAD)
        val properties = PropertyMap(ImmutableMultimap.of("textures", Property("textures", value)))
        head.set(DataComponents.PROFILE, ResolvableProfile.createResolved(GameProfile(UUID.nameUUIDFromBytes("geileraddons:${entry.id}".toByteArray()), "", properties)))
        return head
    }
}
