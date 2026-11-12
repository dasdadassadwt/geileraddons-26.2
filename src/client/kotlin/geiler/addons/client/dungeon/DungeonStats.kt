package geiler.addons.client.dungeon

import java.util.EnumSet
import java.util.UUID

/** Profile data used by Party Finder Stats and Auto Kick. */
class DungeonStats(
    name: String,
    uuid: UUID,
    catacombsLevel: Int,
    selectedClass: DungeonClass?,
    classLevels: Map<DungeonClass, Int>,
    classAverage: Double,
    totalSecrets: Long,
    totalRuns: Long,
    magicalPower: Int,
    bank: Long,
    bankKnown: Boolean,
    knownGear: EnumSet<Gear>?,
    gear: EnumSet<Gear>,
    fastestSPlusSeconds: Map<DungeonFloor, Long>,
    availableFields: EnumSet<DataField>,
    catacombsExperience: Long,
    catacombsExperienceKnown: Boolean,
    itemDetails: List<ItemDetails>?,
    goldenDragonPets: List<GoldenDragonPet>?,
) {
    /** Individual profile facts that can be unavailable even when the profile object exists. */
    enum class DataField {
        CATACOMBS_LEVEL,
        SELECTED_CLASS,
        CLASS_AVERAGE,
        SECRETS,
        RUNS,
        PERSONAL_BESTS,
        MAGICAL_POWER,
        BANK,
        GEAR,
    }

    enum class Gear { TERMINATOR, HYPERION, GOLDEN_DRAGON }

    /** Profile-provided display text for one matching weapon. */
    class ItemDetails(
        gear: Gear?,
        identifier: String?,
        displayName: String?,
        source: String?,
        lore: List<String>?,
    ) {
        private val gearValue = gear ?: Gear.TERMINATOR
        private val identifierValue = clean(identifier)
        private val displayNameValue = clean(displayName)
        private val sourceValue = clean(source)
        private val loreValue = lore?.let { java.util.List.copyOf(it) } ?: java.util.List.of()

        fun gear(): Gear = gearValue
        fun identifier(): String = identifierValue
        fun displayName(): String = displayNameValue
        fun source(): String = sourceValue
        fun lore(): List<String> = loreValue

        override fun equals(other: Any?): Boolean = other is ItemDetails &&
            gearValue == other.gearValue && identifierValue == other.identifierValue &&
            displayNameValue == other.displayNameValue && sourceValue == other.sourceValue &&
            loreValue == other.loreValue

        override fun hashCode(): Int = java.util.Objects.hash(
            gearValue,
            identifierValue,
            displayNameValue,
            sourceValue,
            loreValue,
        )

        override fun toString(): String =
            "ItemDetails[gear=$gearValue, identifier=$identifierValue, displayName=$displayNameValue, source=$sourceValue, lore=$loreValue]"
    }

    /** Fields available for rendering a Golden Dragon pet's profile tooltip. */
    class GoldenDragonPet(
        rarity: String?,
        level: String?,
        experience: String?,
        heldItem: String?,
        skin: String?,
        active: Boolean?,
    ) {
        private val rarityValue = clean(rarity)
        private val levelValue = clean(level)
        private val experienceValue = clean(experience)
        private val heldItemValue = clean(heldItem)
        private val skinValue = clean(skin)
        private val activeValue = active

        fun rarity(): String = rarityValue
        fun level(): String = levelValue
        fun experience(): String = experienceValue
        fun heldItem(): String = heldItemValue
        fun skin(): String = skinValue
        fun active(): Boolean? = activeValue

        override fun equals(other: Any?): Boolean = other is GoldenDragonPet &&
            rarityValue == other.rarityValue && levelValue == other.levelValue &&
            experienceValue == other.experienceValue && heldItemValue == other.heldItemValue &&
            skinValue == other.skinValue && activeValue == other.activeValue

        override fun hashCode(): Int = java.util.Objects.hash(
            rarityValue,
            levelValue,
            experienceValue,
            heldItemValue,
            skinValue,
            activeValue,
        )

        override fun toString(): String =
            "GoldenDragonPet[rarity=$rarityValue, level=$levelValue, experience=$experienceValue, heldItem=$heldItemValue, skin=$skinValue, active=$activeValue]"
    }

    private val nameValue = name
    private val uuidValue = uuid
    private val catacombsLevelValue = catacombsLevel
    private val catacombsExperienceValue = maxOf(0L, catacombsExperience)
    private val catacombsExperienceKnownValue = catacombsExperienceKnown
    private val selectedClassValue = selectedClass
    private val classLevelsValue = java.util.Map.copyOf(classLevels)
    private val classAverageValue = classAverage
    private val totalSecretsValue = maxOf(0L, totalSecrets)
    private val totalRunsValue = maxOf(0L, totalRuns)
    private val secretAverageValue =
        if (totalRunsValue == 0L) 0.0 else totalSecretsValue.toDouble() / totalRunsValue
    private val magicalPowerValue = maxOf(0, magicalPower)
    private val bankValue = maxOf(0L, bank)
    private val bankKnownValue = bankKnown
    private val knownGearValue = knownGear?.clone() ?: EnumSet.noneOf(Gear::class.java)
    private val gearValue = gear.clone()
    private val itemDetailsValue = itemDetails?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
    private val goldenDragonPetsValue = goldenDragonPets?.let { java.util.List.copyOf(it) } ?: java.util.List.of()
    private val fastestSPlusSecondsValue = java.util.Map.copyOf(fastestSPlusSeconds)
    private val availableFieldsValue = availableFields.clone()

    constructor(
        name: String,
        uuid: UUID,
        catacombsLevel: Int,
        selectedClass: DungeonClass?,
        classLevels: Map<DungeonClass, Int>,
        classAverage: Double,
        totalSecrets: Long,
        totalRuns: Long,
        magicalPower: Int,
        bank: Long,
        bankKnown: Boolean,
        gearKnown: Boolean,
        gear: EnumSet<Gear>,
        fastestSPlusSeconds: Map<DungeonFloor, Long>,
    ) : this(
        name,
        uuid,
        catacombsLevel,
        selectedClass,
        classLevels,
        classAverage,
        totalSecrets,
        totalRuns,
        magicalPower,
        bank,
        bankKnown,
        if (gearKnown) EnumSet.allOf(Gear::class.java) else EnumSet.noneOf(Gear::class.java),
        gear,
        fastestSPlusSeconds,
        EnumSet.noneOf(DataField::class.java),
        0L,
        false,
        emptyList(),
        emptyList(),
    )

    constructor(
        name: String,
        uuid: UUID,
        catacombsLevel: Int,
        selectedClass: DungeonClass?,
        classLevels: Map<DungeonClass, Int>,
        classAverage: Double,
        totalSecrets: Long,
        totalRuns: Long,
        magicalPower: Int,
        bank: Long,
        bankKnown: Boolean,
        knownGear: EnumSet<Gear>,
        gear: EnumSet<Gear>,
        fastestSPlusSeconds: Map<DungeonFloor, Long>,
        availableFields: EnumSet<DataField>,
    ) : this(
        name,
        uuid,
        catacombsLevel,
        selectedClass,
        classLevels,
        classAverage,
        totalSecrets,
        totalRuns,
        magicalPower,
        bank,
        bankKnown,
        knownGear,
        gear,
        fastestSPlusSeconds,
        availableFields,
        0L,
        false,
        emptyList(),
        emptyList(),
    )

    fun name(): String = nameValue
    fun uuid(): UUID = uuidValue
    fun catacombsLevel(): Int = catacombsLevelValue
    fun catacombsExperience(): Long = catacombsExperienceValue
    fun catacombsExperienceKnown(): Boolean = catacombsExperienceKnownValue
    fun selectedClass(): DungeonClass? = selectedClassValue
    fun classLevels(): Map<DungeonClass, Int> = classLevelsValue
    fun classLevel(dungeonClass: DungeonClass): Int = classLevelsValue.getOrDefault(dungeonClass, 0)
    fun classAverage(): Double = classAverageValue
    fun totalSecrets(): Long = totalSecretsValue
    fun totalRuns(): Long = totalRunsValue
    fun secretAverage(): Double = secretAverageValue
    fun magicalPower(): Int = magicalPowerValue
    fun bank(): Long = bankValue
    fun bankKnown(): Boolean = bankKnownValue

    /** True only when every supported gear check has authoritative inventory/pet evidence. */
    fun gearKnown(): Boolean = knownGearValue.size == Gear.entries.size

    fun hasGearData(item: Gear?): Boolean = item != null && knownGearValue.contains(item)
    fun has(item: Gear?): Boolean = item != null && gearValue.contains(item)
    fun allItemDetails(): List<ItemDetails> = itemDetailsValue

    fun itemDetails(item: Gear?): List<ItemDetails> =
        if (item == null) java.util.List.of() else itemDetailsValue.stream().filter { it.gear() == item }.toList()

    fun goldenDragonPets(): List<GoldenDragonPet> = goldenDragonPetsValue
    fun has(field: DataField?): Boolean = field != null && availableFieldsValue.contains(field)
    fun hasClassLevel(dungeonClass: DungeonClass?): Boolean =
        dungeonClass != null && classLevelsValue.containsKey(dungeonClass)

    fun hasPersonalBest(floor: DungeonFloor?): Boolean =
        floor != null && fastestSPlusSecondsValue.containsKey(floor)

    fun cacheable(): Boolean = has(DataField.CATACOMBS_LEVEL) && has(DataField.CLASS_AVERAGE) &&
        has(DataField.SECRETS) && has(DataField.RUNS) && has(DataField.MAGICAL_POWER) &&
        has(DataField.BANK) && has(DataField.GEAR)

    fun fastestSPlusSeconds(floor: DungeonFloor): Long = fastestSPlusSecondsValue.getOrDefault(floor, 0L)

    fun selectedClassLine(selected: DungeonClass?): String =
        selected?.let { "${it.displayName()} ${classLevel(it)}" } ?: "Class ?"

    fun allClassLevels(): String = buildString {
        DungeonClass.entries.forEachIndexed { index, dungeonClass ->
            if (index > 0) append('\n')
            append(dungeonClass.displayName()).append(' ')
            append(if (hasClassLevel(dungeonClass)) classLevel(dungeonClass) else "Unavailable")
        }
    }
}

private fun clean(value: String?): String = value ?: ""
