package geiler.addons.client.inventory

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TreeSet

/** Normalization and immutable edit operations for the player-inventory settings model. */
object PlayerInventoryControlsDataLogic {
    private val validProfileName = Regex("[A-Za-z0-9 _-]{1,32}")

    @JvmStatic
    fun normalizeLockedSlots(slots: Set<Int?>?): Set<Int> {
        val normalized = TreeSet<Int>()
        slots?.forEach { slot ->
            if (slot != null && slot >= 0 && slot < PlayerInventoryControlsData.PLAYER_SLOT_COUNT) {
                normalized.add(slot)
            }
        }
        return Collections.unmodifiableSet(normalized)
    }

    @JvmStatic
    fun normalizeHotbarBindings(bindings: List<PlayerInventoryControlsData.Binding?>?): List<PlayerInventoryControlsData.Binding> {
        val normalized = ArrayList<PlayerInventoryControlsData.Binding>(PlayerInventoryControlsData.HOTBAR_SLOT_COUNT)
        for (index in 0 until PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) {
            normalized.add(bindings?.getOrNull(index) ?: PlayerInventoryControlsData.Binding.UNBOUND)
        }
        return java.util.List.copyOf(normalized)
    }

    @JvmStatic
    fun normalizeBindingProfiles(
        profiles: List<PlayerInventoryControlsData.BindingProfile?>?,
    ): List<PlayerInventoryControlsData.BindingProfile> {
        val unique = LinkedHashMap<String, PlayerInventoryControlsData.BindingProfile>()
        for (profile in profiles.orEmpty()) {
            if (profile == null || !isValidProfileName(profile.name())) continue
            unique.putIfAbsent(profileKey(profile.name()), profile)
            if (unique.size >= PlayerInventoryControlsData.MAX_PROFILES) break
        }
        if (unique.size >= PlayerInventoryControlsData.MAX_PROFILES && !unique.containsKey(profileKey("Default"))) {
            val lastKey = unique.keys.lastOrNull()
            if (lastKey != null) unique.remove(lastKey)
        }
        unique.putIfAbsent(profileKey("Default"), PlayerInventoryControlsData.BindingProfile.empty("Default"))
        return java.util.List.copyOf(unique.values)
    }

    @JvmStatic
    fun normalizeSelectedProfile(
        profiles: List<PlayerInventoryControlsData.BindingProfile>,
        selectedProfile: String?,
    ): String {
        val requested = trimLikeJava(selectedProfile ?: "")
        val selected = profiles.firstOrNull { profileKey(it.name()) == profileKey(requested) }
        return selected?.name() ?: profiles.first().name()
    }

    @JvmStatic
    fun defaultBindingProfiles(): List<PlayerInventoryControlsData.BindingProfile> =
        java.util.List.of(PlayerInventoryControlsData.BindingProfile.empty("Default"))

    @JvmStatic
    fun defaultSelectedProfile(): String = "Default"

    @JvmStatic
    fun defaults(): PlayerInventoryControlsData = PlayerInventoryControlsData(emptySet(), emptyList())

    @JvmStatic
    fun isLocked(data: PlayerInventoryControlsData, inventorySlot: Int): Boolean =
        inventorySlot >= 0 && inventorySlot < PlayerInventoryControlsData.PLAYER_SLOT_COUNT &&
            data.lockedSlots().contains(inventorySlot)

    @JvmStatic
    fun withSlotLocked(
        data: PlayerInventoryControlsData,
        inventorySlot: Int,
        locked: Boolean,
    ): PlayerInventoryControlsData {
        if (inventorySlot < 0 || inventorySlot >= PlayerInventoryControlsData.PLAYER_SLOT_COUNT) return data
        val next = TreeSet(data.lockedSlots())
        if (locked) next.add(inventorySlot) else next.remove(inventorySlot)
        return PlayerInventoryControlsData(next, data.hotbarBindings(), data.bindingProfiles(), data.selectedProfile())
    }

    @JvmStatic
    fun withBinding(
        data: PlayerInventoryControlsData,
        hotbarSlot: Int,
        binding: PlayerInventoryControlsData.Binding?,
    ): PlayerInventoryControlsData {
        if (hotbarSlot < 0 || hotbarSlot >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT) return data
        val next = ArrayList(data.hotbarBindings())
        val clean = binding ?: PlayerInventoryControlsData.Binding.UNBOUND
        for (index in next.indices) {
            if (index != hotbarSlot && clean.conflictsWith(next[index])) {
                next[index] = PlayerInventoryControlsData.Binding.UNBOUND
            }
        }
        next[hotbarSlot] = clean
        return PlayerInventoryControlsData(data.lockedSlots(), next, data.bindingProfiles(), data.selectedProfile())
    }

    @JvmStatic
    fun selectedBindingProfile(data: PlayerInventoryControlsData): PlayerInventoryControlsData.BindingProfile =
        data.bindingProfiles().firstOrNull { it.name().equals(data.selectedProfile(), ignoreCase = true) }
            ?: data.bindingProfiles().first()

    @JvmStatic
    fun slotLinks(data: PlayerInventoryControlsData): List<PlayerInventoryControlsData.SlotLink> =
        selectedBindingProfile(data).links()

    @JvmStatic
    fun hotbarPartners(data: PlayerInventoryControlsData, inventorySlot: Int): List<Int> =
        java.util.List.copyOf(
            slotLinks(data).asSequence()
                .filter { it.inventorySlot() == inventorySlot }
                .sortedBy { it.inventoryPriority() }
                .map { it.hotbarSlot() }
                .toList(),
        )

    @JvmStatic
    fun inventoryPartners(data: PlayerInventoryControlsData, hotbarSlot: Int): List<Int> =
        java.util.List.copyOf(
            slotLinks(data).asSequence()
                .filter { it.hotbarSlot() == hotbarSlot }
                .sortedBy { it.hotbarPriority() }
                .map { it.inventorySlot() }
                .toList(),
        )

    @JvmStatic
    fun hasLinkAt(data: PlayerInventoryControlsData, playerInventorySlot: Int): Boolean =
        slotLinks(data).any { it.inventorySlot() == playerInventorySlot || it.hotbarSlot() == playerInventorySlot }

    @JvmStatic
    fun withLink(
        data: PlayerInventoryControlsData,
        inventorySlot: Int,
        hotbarSlot: Int,
    ): PlayerInventoryControlsData {
        if (inventorySlot < PlayerInventoryControlsData.HOTBAR_SLOT_COUNT ||
            inventorySlot >= PlayerInventoryControlsData.PLAYER_SLOT_COUNT ||
            hotbarSlot < 0 || hotbarSlot >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT
        ) {
            return data
        }
        val current = slotLinks(data)
        if (current.any { it.inventorySlot() == inventorySlot && it.hotbarSlot() == hotbarSlot }) return data
        if (current.size >= PlayerInventoryControlsData.MAX_LINKS_PER_PROFILE) return data
        val inventoryPriority = current.asSequence()
            .filter { it.inventorySlot() == inventorySlot }
            .maxOfOrNull { it.inventoryPriority() }
            ?.plus(1) ?: 0
        val hotbarPriority = current.asSequence()
            .filter { it.hotbarSlot() == hotbarSlot }
            .maxOfOrNull { it.hotbarPriority() }
            ?.plus(1) ?: 0
        val nextLinks = ArrayList(current)
        nextLinks.add(
            PlayerInventoryControlsData.SlotLink(
                inventorySlot,
                hotbarSlot,
                inventoryPriority,
                hotbarPriority,
            ),
        )
        return replaceSelectedProfile(data, PlayerInventoryControlsData.BindingProfile(data.selectedProfile(), nextLinks))
    }

    @JvmStatic
    fun withoutLinksAt(data: PlayerInventoryControlsData, playerInventorySlot: Int): PlayerInventoryControlsData {
        if (playerInventorySlot < 0 || playerInventorySlot >= PlayerInventoryControlsData.PLAYER_SLOT_COUNT) return data
        val current = slotLinks(data)
        val next = current.filterNot {
            it.inventorySlot() == playerInventorySlot || it.hotbarSlot() == playerInventorySlot
        }
        if (next.size == current.size) return data
        return replaceSelectedProfile(data, PlayerInventoryControlsData.BindingProfile(data.selectedProfile(), next))
    }

    @JvmStatic
    fun withoutLink(
        data: PlayerInventoryControlsData,
        link: PlayerInventoryControlsData.SlotLink?,
    ): PlayerInventoryControlsData {
        if (link == null) return data
        val current = slotLinks(data)
        if (current.none { samePair(it, link) }) return data
        val next = current.filterNot { samePair(it, link) }
        return replaceSelectedProfile(data, PlayerInventoryControlsData.BindingProfile(data.selectedProfile(), next))
    }

    /** Moves a selected connection to the front for the clicked endpoint, matching profile priority semantics. */
    @JvmStatic
    fun prioritizeLink(
        data: PlayerInventoryControlsData,
        link: PlayerInventoryControlsData.SlotLink?,
        inventoryEndpoint: Boolean,
    ): PlayerInventoryControlsData {
        if (link == null) return data
        val current = slotLinks(data)
        val endpointOrder = current.filter {
            if (inventoryEndpoint) it.inventorySlot() == link.inventorySlot() else it.hotbarSlot() == link.hotbarSlot()
        }.sortedBy { if (inventoryEndpoint) it.inventoryPriority() else it.hotbarPriority() }
        if (endpointOrder.isEmpty() || endpointOrder.none { samePair(it, link) }) return data
        val next = ArrayList(current)
        endpointOrder.forEachIndexed { index, endpointLink ->
            val actualIndex = next.indexOfFirst { samePair(it, endpointLink) }
            if (actualIndex < 0) return@forEachIndexed
            var inventoryPriority = endpointLink.inventoryPriority()
            var hotbarPriority = endpointLink.hotbarPriority()
            if (inventoryEndpoint) {
                inventoryPriority = if (samePair(endpointLink, link)) 0 else index + 1
            } else {
                hotbarPriority = if (samePair(endpointLink, link)) 0 else index + 1
            }
            next[actualIndex] = PlayerInventoryControlsData.SlotLink(
                endpointLink.inventorySlot(),
                endpointLink.hotbarSlot(),
                inventoryPriority,
                hotbarPriority,
            )
        }
        return replaceSelectedProfile(data, PlayerInventoryControlsData.BindingProfile(data.selectedProfile(), next))
    }

    @JvmStatic
    fun selectProfile(data: PlayerInventoryControlsData, name: String?): PlayerInventoryControlsData {
        val match = findProfile(data, name) ?: return data
        return if (match.name().equals(data.selectedProfile())) data else
            PlayerInventoryControlsData(data.lockedSlots(), data.hotbarBindings(), data.bindingProfiles(), match.name())
    }

    @JvmStatic
    fun createProfile(data: PlayerInventoryControlsData, name: String?): PlayerInventoryControlsData {
        val clean = normalizeProfileName(name) ?: return data
        if (data.bindingProfiles().size >= PlayerInventoryControlsData.MAX_PROFILES || findProfile(data, clean) != null) {
            return data
        }
        val next = ArrayList(data.bindingProfiles())
        next.add(PlayerInventoryControlsData.BindingProfile.empty(clean))
        return PlayerInventoryControlsData(data.lockedSlots(), data.hotbarBindings(), next, clean)
    }

    @JvmStatic
    fun renameSelectedProfile(data: PlayerInventoryControlsData, name: String?): PlayerInventoryControlsData {
        val clean = normalizeProfileName(name) ?: return data
        val current = selectedBindingProfile(data)
        val conflict = findProfile(data, clean)
        if (conflict != null && conflict !== current) return data
        val next = ArrayList(data.bindingProfiles())
        for (index in next.indices) {
            if (next[index].name().equals(current.name(), ignoreCase = true)) {
                next[index] = PlayerInventoryControlsData.BindingProfile(clean, current.links())
                break
            }
        }
        return PlayerInventoryControlsData(data.lockedSlots(), data.hotbarBindings(), next, clean)
    }

    @JvmStatic
    fun deleteSelectedProfile(data: PlayerInventoryControlsData): PlayerInventoryControlsData {
        if (data.bindingProfiles().size <= 1) return data
        val next = data.bindingProfiles().filterNot {
            it.name().equals(data.selectedProfile(), ignoreCase = true)
        }.toMutableList()
        if (next.isEmpty()) next.add(PlayerInventoryControlsData.BindingProfile.empty("Default"))
        return PlayerInventoryControlsData(data.lockedSlots(), data.hotbarBindings(), next, next.first().name())
    }

    @JvmStatic
    fun normalizePriority(priority: Int): Int =
        priority.coerceIn(0, PlayerInventoryControlsData.MAX_LINKS_PER_PROFILE)

    @JvmStatic
    fun isValidLink(link: PlayerInventoryControlsData.SlotLink): Boolean =
        link.inventorySlot() >= PlayerInventoryControlsData.HOTBAR_SLOT_COUNT &&
            link.inventorySlot() < PlayerInventoryControlsData.PLAYER_SLOT_COUNT &&
            link.hotbarSlot() >= 0 && link.hotbarSlot() < PlayerInventoryControlsData.HOTBAR_SLOT_COUNT

    @JvmStatic
    fun normalizeProfileRecordName(name: String?): String = if (name == null) "Default" else trimLikeJava(name)

    @JvmStatic
    fun normalizeProfileLinks(
        links: List<PlayerInventoryControlsData.SlotLink?>?,
    ): List<PlayerInventoryControlsData.SlotLink> {
        val clean = LinkedHashMap<String, PlayerInventoryControlsData.SlotLink>()
        for (link in links.orEmpty()) {
            if (link != null && isValidLink(link)) {
                clean.putIfAbsent("${link.inventorySlot()}:${link.hotbarSlot()}", link)
            }
            if (clean.size >= PlayerInventoryControlsData.MAX_LINKS_PER_PROFILE) break
        }
        return java.util.List.copyOf(clean.values)
    }

    @JvmStatic
    fun normalizeBindingKey(key: String?): String {
        val stripped = if (key == null) "" else stripJavaWhitespace(key)
        return stripped.substring(0, minOf(128, stripped.length))
    }

    @JvmStatic
    fun normalizeBindingModifiers(key: String, modifiers: Int): Int = if (key.isEmpty()) 0 else modifiers and 15

    @JvmStatic
    fun conflictsWith(left: PlayerInventoryControlsData.Binding, right: PlayerInventoryControlsData.Binding?): Boolean =
        right != null && left.key().isNotEmpty() && left.key() == right.key() && left.modifiers() == right.modifiers()

    private fun replaceSelectedProfile(
        data: PlayerInventoryControlsData,
        replacement: PlayerInventoryControlsData.BindingProfile,
    ): PlayerInventoryControlsData {
        val next = ArrayList(data.bindingProfiles())
        for (index in next.indices) {
            if (next[index].name().equals(data.selectedProfile(), ignoreCase = true)) {
                next[index] = replacement
                return PlayerInventoryControlsData(data.lockedSlots(), data.hotbarBindings(), next, data.selectedProfile())
            }
        }
        return data
    }

    private fun findProfile(
        data: PlayerInventoryControlsData,
        name: String?,
    ): PlayerInventoryControlsData.BindingProfile? {
        if (name == null) return null
        val requested = trimLikeJava(name)
        return data.bindingProfiles().firstOrNull { it.name().equals(requested, ignoreCase = true) }
    }

    private fun normalizeProfileName(value: String?): String? {
        if (value == null) return null
        val clean = trimLikeJava(value)
        return if (isValidProfileName(clean)) clean else null
    }

    private fun isValidProfileName(value: String?): Boolean =
        value != null && validProfileName.matches(trimLikeJava(value))

    private fun profileKey(value: String): String = trimLikeJava(value).lowercase(Locale.ROOT)

    /** Java String.trim() removes only code units at or below U+0020. */
    private fun trimLikeJava(value: String): String {
        var start = 0
        var end = value.length
        while (start < end && value[start].code <= 0x20) start++
        while (start < end && value[end - 1].code <= 0x20) end--
        return value.substring(start, end)
    }

    /** Matches Java 11 String.strip() over Unicode code points using Character.isWhitespace. */
    private fun stripJavaWhitespace(value: String): String {
        var start = 0
        var end = value.length
        while (start < end) {
            val codePoint = value.codePointAt(start)
            if (!Character.isWhitespace(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = value.codePointBefore(end)
            if (!Character.isWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return value.substring(start, end)
    }

    private fun samePair(
        left: PlayerInventoryControlsData.SlotLink?,
        right: PlayerInventoryControlsData.SlotLink?,
    ): Boolean = left != null && right != null &&
        left.inventorySlot() == right.inventorySlot() && left.hotbarSlot() == right.hotbarSlot()
}
