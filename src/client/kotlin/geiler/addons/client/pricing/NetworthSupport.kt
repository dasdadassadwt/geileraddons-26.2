package geiler.addons.client.pricing

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.serialization.Dynamic
import com.mojang.serialization.JsonOps
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.objects.Object2ObjectMap
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps
import net.azureaaron.networth.ItemCalculator
import net.azureaaron.networth.data.SkyblockItemData
import net.azureaaron.networth.item.ItemMetadataRetriever
import net.azureaaron.networth.item.SkyblockItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import java.io.ByteArrayInputStream
import java.util.function.ToDoubleFunction

/**
 * Estimated Item Value through Aaron's networth-calculator library (Apache-2.0), fed the way Skyblocker feeds it: the
 * Hypixel items resource for item data and Bazaar buy / 3-day average / lowest BIN prices.
 */
object NetworthSupport {
    const val ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"

    @Volatile
    var itemData: Object2ObjectMap<String, SkyblockItemData> = Object2ObjectMaps.emptyMap()
        private set

    /** Parses the body of the items resource; returns the entry count or null when it is not usable. */
    @JvmStatic
    fun load(body: String): Int? {
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return null
            val items = (root as JsonObject).getAsJsonArray("items") ?: return null
            val parsed = SkyblockItemData.MAP_CODEC.parse(JsonOps.INSTANCE, items).getOrThrow()
            itemData = parsed
            parsed.size
        } catch (_: Exception) {
            null
        }
    }

    /** The estimated value of [count] items described by [tag], or 0 when it cannot be estimated. */
    @JvmStatic
    fun estimate(tag: CompoundTag, count: Int, price: ToDoubleFunction<String>): Double {
        return try {
            val id = tag.getStringOr("id", "")
            if (id.isEmpty() || itemData.isEmpty()) return 0.0
            val stack = SkyblockItemStack.of(id, count, Dynamic<Tag>(NbtOps.INSTANCE, tag), Retriever(tag, id))
            ItemCalculator.calculate(stack, price, itemData).price()
        } catch (_: Exception) {
            0.0
        }
    }

    /** Bazaar buy price, else the 3-day average, else lowest BIN. */
    @JvmStatic
    fun priceOf(data: PriceSnapshot, id: String): Double =
        data.bazaar?.get(id)?.buyPrice ?: data.threeDayAverage?.get(id) ?: data.lowestBin?.get(id) ?: 0.0

    private class Retriever(tag: CompoundTag, id: String) : ItemMetadataRetriever {
        private val years: IntList = cakeYears(tag, id)

        override fun cakeBagCakeYears(): IntList = years

        private fun cakeYears(tag: CompoundTag, id: String): IntList {
            if (id != "NEW_YEAR_CAKE_BAG" || !tag.contains("new_year_cake_bag_data")) return IntList.of()
            return try {
                val data = tag.getByteArray("new_year_cake_bag_data").orElse(ByteArray(0))
                val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtAccounter.unlimitedHeap())
                val years = IntArrayList()
                for (element in root.getListOrEmpty("i")) {
                    val item = element as? CompoundTag ?: continue
                    val extra = item.getCompoundOrEmpty("tag")
                    if (extra.contains("ExtraAttributes")) years.add(extra.getCompoundOrEmpty("ExtraAttributes").getIntOr("new_years_cake", 0))
                }
                years
            } catch (_: Exception) {
                IntList.of()
            }
        }
    }
}
