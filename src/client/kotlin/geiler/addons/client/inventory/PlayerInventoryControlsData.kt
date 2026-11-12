package geiler.addons.client.inventory

/** Immutable inventory settings with the Java-callable accessors used by codecs, UI, and checks. */
class PlayerInventoryControlsData(
    lockedSlots: Set<Int?>?,
    hotbarBindings: List<Binding?>?,
    bindingProfiles: List<BindingProfile?>?,
    selectedProfile: String?,
) {
    private val lockedSlotsValue = PlayerInventoryControlsDataLogic.normalizeLockedSlots(lockedSlots)
    private val hotbarBindingsValue = PlayerInventoryControlsDataLogic.normalizeHotbarBindings(hotbarBindings)
    private val bindingProfilesValue = PlayerInventoryControlsDataLogic.normalizeBindingProfiles(bindingProfiles)
    private val selectedProfileValue =
        PlayerInventoryControlsDataLogic.normalizeSelectedProfile(bindingProfilesValue, selectedProfile)

    constructor(lockedSlots: Set<Int?>?, hotbarBindings: List<Binding?>?) : this(
        lockedSlots,
        hotbarBindings,
        PlayerInventoryControlsDataLogic.defaultBindingProfiles(),
        PlayerInventoryControlsDataLogic.defaultSelectedProfile(),
    )

    fun lockedSlots(): Set<Int> = lockedSlotsValue

    fun hotbarBindings(): List<Binding> = hotbarBindingsValue

    fun bindingProfiles(): List<BindingProfile> = bindingProfilesValue

    fun selectedProfile(): String = selectedProfileValue

    fun isLocked(inventorySlot: Int): Boolean = PlayerInventoryControlsDataLogic.isLocked(this, inventorySlot)

    fun withSlotLocked(inventorySlot: Int, locked: Boolean): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.withSlotLocked(this, inventorySlot, locked)

    fun withBinding(hotbarSlot: Int, binding: Binding?): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.withBinding(this, hotbarSlot, binding)

    fun selectedBindingProfile(): BindingProfile = PlayerInventoryControlsDataLogic.selectedBindingProfile(this)

    fun slotLinks(): List<SlotLink> = PlayerInventoryControlsDataLogic.slotLinks(this)

    fun hotbarPartners(inventorySlot: Int): List<Int> =
        PlayerInventoryControlsDataLogic.hotbarPartners(this, inventorySlot)

    fun inventoryPartners(hotbarSlot: Int): List<Int> =
        PlayerInventoryControlsDataLogic.inventoryPartners(this, hotbarSlot)

    fun hasLinkAt(playerInventorySlot: Int): Boolean =
        PlayerInventoryControlsDataLogic.hasLinkAt(this, playerInventorySlot)

    fun withLink(inventorySlot: Int, hotbarSlot: Int): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.withLink(this, inventorySlot, hotbarSlot)

    fun withoutLinksAt(playerInventorySlot: Int): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.withoutLinksAt(this, playerInventorySlot)

    fun withoutLink(link: SlotLink?): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.withoutLink(this, link)

    /** Moves a selected connection to the front for the clicked endpoint, matching profile priority semantics. */
    fun prioritizeLink(link: SlotLink?, inventoryEndpoint: Boolean): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.prioritizeLink(this, link, inventoryEndpoint)

    fun selectProfile(name: String?): PlayerInventoryControlsData = PlayerInventoryControlsDataLogic.selectProfile(this, name)

    fun createProfile(name: String?): PlayerInventoryControlsData = PlayerInventoryControlsDataLogic.createProfile(this, name)

    fun renameSelectedProfile(name: String?): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.renameSelectedProfile(this, name)

    fun deleteSelectedProfile(): PlayerInventoryControlsData =
        PlayerInventoryControlsDataLogic.deleteSelectedProfile(this)

    override fun equals(other: Any?): Boolean = other is PlayerInventoryControlsData &&
        lockedSlotsValue == other.lockedSlotsValue && hotbarBindingsValue == other.hotbarBindingsValue &&
        bindingProfilesValue == other.bindingProfilesValue && selectedProfileValue == other.selectedProfileValue

    override fun hashCode(): Int {
        var result = lockedSlotsValue.hashCode()
        result = 31 * result + hotbarBindingsValue.hashCode()
        result = 31 * result + bindingProfilesValue.hashCode()
        result = 31 * result + selectedProfileValue.hashCode()
        return result
    }

    override fun toString(): String =
        "PlayerInventoryControlsData[lockedSlots=$lockedSlotsValue, hotbarBindings=$hotbarBindingsValue, " +
            "bindingProfiles=$bindingProfilesValue, selectedProfile=$selectedProfileValue]"

    class SlotLink(
        inventorySlot: Int,
        hotbarSlot: Int,
        inventoryPriority: Int,
        hotbarPriority: Int,
    ) {
        private val inventorySlotValue = inventorySlot
        private val hotbarSlotValue = hotbarSlot
        private val inventoryPriorityValue = PlayerInventoryControlsDataLogic.normalizePriority(inventoryPriority)
        private val hotbarPriorityValue = PlayerInventoryControlsDataLogic.normalizePriority(hotbarPriority)

        constructor(inventorySlot: Int, hotbarSlot: Int) : this(inventorySlot, hotbarSlot, 0, 0)

        fun inventorySlot(): Int = inventorySlotValue

        fun hotbarSlot(): Int = hotbarSlotValue

        fun inventoryPriority(): Int = inventoryPriorityValue

        fun hotbarPriority(): Int = hotbarPriorityValue

        fun isValid(): Boolean = PlayerInventoryControlsDataLogic.isValidLink(this)

        override fun equals(other: Any?): Boolean = other is SlotLink &&
            inventorySlotValue == other.inventorySlotValue && hotbarSlotValue == other.hotbarSlotValue &&
            inventoryPriorityValue == other.inventoryPriorityValue && hotbarPriorityValue == other.hotbarPriorityValue

        override fun hashCode(): Int {
            var result = inventorySlotValue
            result = 31 * result + hotbarSlotValue
            result = 31 * result + inventoryPriorityValue
            result = 31 * result + hotbarPriorityValue
            return result
        }

        override fun toString(): String =
            "SlotLink[inventorySlot=$inventorySlotValue, hotbarSlot=$hotbarSlotValue, " +
                "inventoryPriority=$inventoryPriorityValue, hotbarPriority=$hotbarPriorityValue]"
    }

    class BindingProfile(name: String?, links: List<SlotLink?>?) {
        private val nameValue = PlayerInventoryControlsDataLogic.normalizeProfileRecordName(name)
        private val linksValue = PlayerInventoryControlsDataLogic.normalizeProfileLinks(links)

        fun name(): String = nameValue

        fun links(): List<SlotLink> = linksValue

        override fun equals(other: Any?): Boolean = other is BindingProfile &&
            nameValue == other.nameValue && linksValue == other.linksValue

        override fun hashCode(): Int = 31 * nameValue.hashCode() + linksValue.hashCode()

        override fun toString(): String = "BindingProfile[name=$nameValue, links=$linksValue]"

        companion object {
            @JvmStatic
            fun empty(name: String?): BindingProfile = BindingProfile(name, emptyList())
        }
    }

    class Binding(key: String?, modifiers: Int) {
        private val keyValue = PlayerInventoryControlsDataLogic.normalizeBindingKey(key)
        private val modifiersValue = PlayerInventoryControlsDataLogic.normalizeBindingModifiers(keyValue, modifiers)

        fun key(): String = keyValue

        fun modifiers(): Int = modifiersValue

        fun conflictsWith(other: Binding?): Boolean = PlayerInventoryControlsDataLogic.conflictsWith(this, other)

        override fun equals(other: Any?): Boolean = other is Binding &&
            keyValue == other.keyValue && modifiersValue == other.modifiersValue

        override fun hashCode(): Int = 31 * keyValue.hashCode() + modifiersValue

        override fun toString(): String = "Binding[key=$keyValue, modifiers=$modifiersValue]"

        companion object {
            @JvmField
            val UNBOUND = Binding("", 0)
        }
    }

    companion object {
        const val PLAYER_SLOT_COUNT = 41
        const val HOTBAR_SLOT_COUNT = 9
        const val MAX_PROFILES = 32
        const val MAX_LINKS_PER_PROFILE = 256

        @JvmStatic
        fun defaults(): PlayerInventoryControlsData = PlayerInventoryControlsDataLogic.defaults()
    }
}
