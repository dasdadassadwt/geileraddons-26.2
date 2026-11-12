package geiler.addons.client.inventory

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.ArrayList
import java.util.HashSet
import java.util.Optional

/** Small versioned codec for standalone player-inventory control settings. */
object PlayerInventoryControlsCodec {
    const val MAX_JSON_CHARS = 16_384

    @JvmStatic
    fun encode(data: PlayerInventoryControlsData?): String {
        val safeData = data ?: PlayerInventoryControlsData.defaults()
        val root = JsonObject()
        root.addProperty("version", 3)
        val locked = JsonArray()
        for (slot in safeData.lockedSlots()) locked.add(slot)
        root.add("lockedSlots", locked)
        val bindings = JsonArray()
        for (binding in safeData.hotbarBindings()) {
            val entry = JsonObject()
            entry.addProperty("key", binding.key())
            entry.addProperty("modifiers", binding.modifiers())
            bindings.add(entry)
        }
        root.add("hotbarBindings", bindings)
        root.addProperty("selectedProfile", safeData.selectedProfile())
        val profiles = JsonArray()
        for (profile in safeData.bindingProfiles()) {
            val profileData = JsonObject()
            profileData.addProperty("name", profile.name())
            val links = JsonArray()
            for (link in profile.links()) {
                val linkData = JsonObject()
                linkData.addProperty("inventorySlot", link.inventorySlot())
                linkData.addProperty("hotbarSlot", link.hotbarSlot())
                linkData.addProperty("inventoryPriority", link.inventoryPriority())
                linkData.addProperty("hotbarPriority", link.hotbarPriority())
                links.add(linkData)
            }
            profileData.add("links", links)
            profiles.add(profileData)
        }
        root.add("bindingProfiles", profiles)
        return root.toString()
    }

    @JvmStatic
    fun decode(json: String?): Optional<PlayerInventoryControlsData> {
        if (json.isNullOrBlank() || json.length > MAX_JSON_CHARS) return Optional.empty()
        try {
            val parsed = JsonParser.parseString(json)
            if (!parsed.isJsonObject) return Optional.empty()
            val root = parsed.asJsonObject
            val version = root.get("version")
            if (version == null || !version.isJsonPrimitive) return Optional.empty()
            val formatVersion = version.asInt
            if (formatVersion < 1 || formatVersion > 3) return Optional.empty()

            val locks = HashSet<Int>()
            val lockData = root.get("lockedSlots")
            if (lockData != null && lockData.isJsonArray) {
                val array = lockData.asJsonArray
                if (array.size() > PlayerInventoryControlsData.PLAYER_SLOT_COUNT) return Optional.empty()
                for (entry in array) {
                    if (!entry.isJsonPrimitive) continue
                    val slot = entry.asInt
                    if (slot >= 0 && slot < PlayerInventoryControlsData.PLAYER_SLOT_COUNT) locks.add(slot)
                }
            }

            val bindings = ArrayList<PlayerInventoryControlsData.Binding>()
            val bindingData = root.get("hotbarBindings")
            if (bindingData != null && bindingData.isJsonArray) {
                val array = bindingData.asJsonArray
                if (array.size() > PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) return Optional.empty()
                for (entry in array) {
                    if (entry == null || !entry.isJsonObject) {
                        bindings.add(PlayerInventoryControlsData.Binding.UNBOUND)
                        continue
                    }
                    val binding = entry.asJsonObject
                    bindings.add(
                        PlayerInventoryControlsData.Binding(
                            string(binding, "key", ""),
                            integer(binding, "modifiers", 0),
                        ),
                    )
                }
            }
            if (formatVersion == 1) return Optional.of(PlayerInventoryControlsData(locks, bindings))

            val profiles = ArrayList<PlayerInventoryControlsData.BindingProfile>()
            val profilesElement = root.get("bindingProfiles")
            if (profilesElement != null && profilesElement.isJsonArray) {
                val profileArray = profilesElement.asJsonArray
                if (profileArray.size() > PlayerInventoryControlsData.MAX_PROFILES) return Optional.empty()
                for (profileElement in profileArray) {
                    if (profileElement == null || !profileElement.isJsonObject) continue
                    val profileObject = profileElement.asJsonObject
                    val name = string(profileObject, "name", "")
                    val links = ArrayList<PlayerInventoryControlsData.SlotLink>()
                    val linksElement = profileObject.get("links")
                    if (linksElement != null && linksElement.isJsonArray) {
                        val linkArray = linksElement.asJsonArray
                        if (linkArray.size() > PlayerInventoryControlsData.MAX_LINKS_PER_PROFILE) {
                            return Optional.empty()
                        }
                        for (linkElement in linkArray) {
                            if (linkElement == null || !linkElement.isJsonObject) continue
                            val linkObject = linkElement.asJsonObject
                            val legacyPriority = links.size
                            links.add(
                                PlayerInventoryControlsData.SlotLink(
                                    integer(linkObject, "inventorySlot", -1),
                                    integer(linkObject, "hotbarSlot", -1),
                                    integer(linkObject, "inventoryPriority", legacyPriority),
                                    integer(linkObject, "hotbarPriority", legacyPriority),
                                ),
                            )
                        }
                    }
                    profiles.add(PlayerInventoryControlsData.BindingProfile(name, links))
                }
            }
            val selectedProfile = string(root, "selectedProfile", "Default")
            return Optional.of(PlayerInventoryControlsData(locks, bindings, profiles, selectedProfile))
        } catch (invalid: RuntimeException) {
            return Optional.empty()
        }
    }

    private fun string(objectValue: JsonObject, name: String, fallback: String): String {
        val value: JsonElement? = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asString
        } catch (_: RuntimeException) {
            fallback
        }
    }

    private fun integer(objectValue: JsonObject, name: String, fallback: Int): Int {
        val value: JsonElement? = objectValue.get(name)
        if (value == null || !value.isJsonPrimitive) return fallback
        return try {
            value.asInt
        } catch (_: RuntimeException) {
            fallback
        }
    }
}
