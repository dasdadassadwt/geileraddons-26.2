package geiler.addons.client.profileviewer

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayInputStream
import java.util.Base64

/** Turns the profiles API answer (`{profiles:[...]}`) into [PvProfile]s. Pure; bounded; any thread. */
object PvParser {
    private const val MAX_PROFILES = 16
    private const val MAX_PETS = 600
    private const val MAX_COLLECTIONS = 1_024
    private const val MAX_CONTAINERS = 256
    private const val MAX_SETS = 64
    private const val MAX_NBT_BYTES = 4L * 1024 * 1024
    private const val MAX_DATA_CHARS = 1_024 * 1_024
    private const val MAX_MINION_CRAFTS = 2_000
    private const val MAX_SACKS = 4_000

    @JvmStatic
    fun profiles(root: JsonObject, uuid: String): List<PvProfile> {
        val compact = uuid.replace("-", "")
        val dashed = if (compact.length == 32) "${compact.substring(0, 8)}-${compact.substring(8, 12)}-${compact.substring(12, 16)}-${compact.substring(16, 20)}-${compact.substring(20)}" else compact
        val result = ArrayList<PvProfile>()
        val array = root.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray ?: return result
        for (element in array) {
            if (result.size >= MAX_PROFILES) break
            val profile = element.obj() ?: continue
            val members = profile.obj("members") ?: continue
            val member = members.obj(compact) ?: members.obj(dashed) ?: continue
            result.add(parse(profile, member, members))
        }
        return result
    }

    /** The profile marked selected, else the first one. */
    @JvmStatic
    fun selected(list: List<PvProfile>): PvProfile? = list.firstOrNull { it.selected } ?: list.firstOrNull()

    private fun parse(profile: JsonObject, m: JsonObject, members: JsonObject): PvProfile {
        val skills = HashMap<String, Long>()
        m.obj("player_data")?.obj("experience")?.entrySet()?.forEach { (key, value) ->
            if (key.startsWith("SKILL_")) value.long()?.let { skills[key.removePrefix("SKILL_")] = it }
        }
        val slayerXp = HashMap<String, Long>()
        val slayerKills = HashMap<String, List<Int>>()
        m.obj("slayer")?.obj("slayer_bosses")?.entrySet()?.forEach { (boss, value) ->
            val data = value.obj() ?: return@forEach
            slayerXp[boss] = data.long("xp") ?: 0L
            slayerKills[boss] = (0 until 5).map { data.long("boss_kills_tier_$it")?.toInt() ?: 0 }
        }
        val dungeons = m.obj("dungeons")
        val cata = dungeons?.obj("dungeon_types")?.obj("catacombs")
        val master = dungeons?.obj("dungeon_types")?.obj("master_catacombs")
        val classXp = HashMap<String, Long>()
        dungeons?.obj("player_classes")?.entrySet()?.forEach { (name, value) -> value.obj()?.long("experience")?.let { classXp[name] = it } }
        val pets = ArrayList<PvProfile.Pet>()
        m.obj("pets_data")?.get("pets")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            if (pets.size >= MAX_PETS) return@forEach
            val pet = element.obj() ?: return@forEach
            pets.add(
                PvProfile.Pet(
                    pet.string("type") ?: "UNKNOWN", pet.string("tier") ?: "COMMON", pet.long("exp") ?: 0L,
                    pet.bool("active"), pet.string("heldItem"), pet.string("skin"), pet.string("uuid"),
                    pet.long("candyUsed")?.toInt() ?: 0,
                ),
            )
        }
        pets.sortWith(compareByDescending<PvProfile.Pet> { it.active }.thenByDescending { it.level }.thenByDescending { it.exp })
        // Collections are per member; show the co-op sum and keep each member's share for the hover.
        val collections = LinkedHashMap<String, Long>()
        val byMember = LinkedHashMap<String, LinkedHashMap<String, Long>>()
        for ((memberId, memberJson) in members.entrySet()) {
            memberJson.obj()?.obj("collection")?.entrySet()?.forEach { (id, value) ->
                if (collections.size >= MAX_COLLECTIONS && id !in collections) return@forEach
                val amount = value.long() ?: return@forEach
                collections.merge(id, amount, Long::plus)
                byMember.getOrPut(id) { LinkedHashMap() }[memberId.replace("-", "")] = amount
            }
        }
        val inventory = m.obj("inventory")
        val sacks = LinkedHashMap<String, Long>()
        inventory?.obj("sacks_counts")?.entrySet()?.forEach { (id, value) ->
            val amount = value.long() ?: return@forEach
            if (amount > 0 && sacks.size < MAX_SACKS) sacks[id] = amount
        }
        val containers = LinkedHashMap<String, String>()
        fun keep(key: String, value: JsonElement?) {
            val data = value?.obj()?.string("data") ?: return
            if (containers.size < MAX_CONTAINERS && data.length <= MAX_DATA_CHARS) containers[key] = data
        }
        inventory?.entrySet()?.forEach { (key, value) ->
            when (key) {
                "bag_contents" -> value.obj()?.entrySet()?.forEach { (bag, data) -> keep(bag, data) }
                "backpack_contents" -> value.obj()?.entrySet()?.forEach { (slot, data) -> keep("backpack_$slot", data) }
                "backpack_icons" -> value.obj()?.entrySet()?.forEach { (slot, data) -> keep("backpack_icon_$slot", data) }
                else -> keep(key, value)
            }
        }
        // Wardrobe and equipment sets now live in `loadout` (one blob per piece); keys name the set and the slot.
        val loadout = m.obj("loadout")
        val armorSets = sets(loadout?.obj("armor"), ARMOR_PIECES.map { it to it }) { id, piece, value -> keep("wardrobe_${id}_$piece", value) }
        val equipmentSets = sets(loadout?.obj("equipment"), (1..4).map { "EQUIPMENT_SLOT_$it" to it.toString() }) { id, slot, value -> keep("equipment_${id}_$slot", value) }
        val loadouts = loadout?.obj("loadouts")?.entrySet()?.mapNotNull { (_, value) ->
            val l = value.obj() ?: return@mapNotNull null
            val armor = l.long("armor_set_id")?.toInt()
            val equipment = l.long("equipment_set_id")?.toInt()
            val pet = l.string("pet")
            if (armor == null && equipment == null && pet == null) return@mapNotNull null
            PvProfile.Loadout(l.long("id")?.toInt() ?: 0, l.string("name") ?: "Loadout", armor, equipment, pet, l.string("power_stone"))
        }?.sortedBy { it.id }?.take(MAX_SETS) ?: emptyList()
        return PvProfile(
            id = profile.string("profile_id") ?: "",
            cuteName = profile.string("cute_name") ?: "Profile",
            selected = profile.bool("selected"),
            gameMode = profile.string("game_mode") ?: "normal",
            bank = profile.obj("banking")?.double("balance"),
            purse = m.obj("currencies")?.double("coin_purse") ?: 0.0,
            fairySouls = m.obj("fairy_soul")?.long("total_collected")?.toInt() ?: 0,
            skyblockLevel = (m.obj("leveling")?.long("experience") ?: 0L) / 100.0,
            skillXp = skills,
            slayerXp = slayerXp,
            slayerKills = slayerKills,
            catacombsXp = cata?.long("experience") ?: 0L,
            classXp = classXp,
            selectedClass = dungeons?.string("selected_dungeon_class"),
            secrets = dungeons?.long("secrets") ?: 0L,
            completions = floors(cata?.obj("tier_completions")) { it.toInt() },
            masterCompletions = floors(master?.obj("tier_completions")) { it.toInt() },
            fastestSPlus = floors(cata?.obj("fastest_time_s_plus")) { it },
            masterFastestSPlus = floors(master?.obj("fastest_time_s_plus")) { it },
            pets = pets,
            collections = collections,
            sections = PvSections.parse(m),
            inventoryApi = containers.containsKey("inv_contents"),
            inventories = containers,
            craftedMinions = crafted(profile),
            collectionsByMember = byMember,
            armorSets = armorSets,
            equipmentSets = equipmentSets,
            loadouts = loadouts,
            memberIds = members.keySet().map { it.replace("-", "") }.take(MAX_PROFILES),
            sacks = sacks,
            personalBank = m.obj("profile")?.double("bank_account"),
        )
    }

    private val ARMOR_PIECES = listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")

    /** Set ids (sorted) that have at least one of [slots] (json key to stored name); [keep] stores each piece. */
    private fun sets(json: JsonObject?, slots: List<Pair<String, String>>, keep: (Int, String, JsonElement) -> Unit): List<Int> {
        val out = ArrayList<Int>()
        json?.entrySet()?.forEach { (key, value) ->
            val id = key.toIntOrNull() ?: return@forEach
            val set = value.obj() ?: return@forEach
            if (out.size >= MAX_SETS) return@forEach
            var any = false
            for ((jsonKey, name) in slots) set.get(jsonKey)?.let { keep(id, name, it); any = true }
            if (any) out.add(id)
        }
        return out.sorted()
    }

    /** Every member's crafted minion tiers (`player_data.crafted_generators`), bounded. */
    private fun crafted(profile: JsonObject): Set<String> {
        val out = HashSet<String>()
        profile.obj("members")?.entrySet()?.forEach { (_, member) ->
            member.obj()?.obj("player_data")?.get("crafted_generators")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
                if (it.isJsonPrimitive && out.size < MAX_MINION_CRAFTS) out.add(it.asString)
            }
        }
        return out
    }

    /** Item compounds of one container; empty slots are empty compounds. Null when the data is unreadable. */
    @JvmStatic
    fun decode(data: String): List<CompoundTag>? = try {
        val bytes = Base64.getDecoder().decode(data.trim())
        val root = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.create(MAX_NBT_BYTES))
        val list: ListTag = root.getListOrEmpty("i")
        (0 until list.size).map { list.getCompoundOrEmpty(it) }
    } catch (_: Exception) {
        null
    }

    @JvmStatic
    fun decodeCount(data: String): Int = decode(data)?.size ?: -1

    private fun <T> floors(json: JsonObject?, map: (Long) -> T): Map<Int, T> {
        val out = HashMap<Int, T>()
        json?.entrySet()?.forEach { (key, value) ->
            val floor = key.toIntOrNull() ?: return@forEach
            value.long()?.let { out[floor] = map(it) }
        }
        return out
    }

    private fun JsonElement.obj(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.obj()
    private fun JsonElement.long(): Long? = try { if (isJsonPrimitive && asJsonPrimitive.isNumber) asDouble.toLong() else null } catch (_: RuntimeException) { null }
    private fun JsonObject.long(key: String): Long? = get(key)?.long()
    private fun JsonObject.double(key: String): Double? =
        get(key)?.let { try { if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asDouble else null } catch (_: RuntimeException) { null } }
    private fun JsonObject.string(key: String): String? = get(key)?.let { if (it.isJsonPrimitive) it.asString else null }
    private fun JsonObject.bool(key: String): Boolean = get(key)?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } ?: false
}
