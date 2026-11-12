package geiler.addons.client.module

/** Named module groups shown as section headings inside their category's Click GUI grid. */
class ModuleCategoryFilter(
    id: String?,
    label: String?,
    category: Category?,
    includedNames: Set<String>?,
    excludedNames: Set<String>?,
) {
    private val idValue = id
    private val labelValue = label
    private val categoryValue = category
    private val includedNamesValue: Set<String> = java.util.Set.copyOf(includedNames ?: emptySet())
    private val excludedNamesValue: Set<String> = java.util.Set.copyOf(excludedNames ?: emptySet())

    /** Java record-compatible component accessors retained for GUI and check callers. */
    fun id(): String? = idValue
    fun label(): String? = labelValue
    fun category(): Category? = categoryValue
    fun includedNames(): Set<String> = includedNamesValue
    fun excludedNames(): Set<String> = excludedNamesValue

    fun matches(module: Module?): Boolean {
        if (module == null) return false
        val category = categoryValue ?: return true
        if (module.category() != category) return false
        val name = module.name()
        return if (includedNamesValue.isNotEmpty()) name in includedNamesValue
        else name !in excludedNamesValue
    }

    override fun equals(other: Any?): Boolean = other is ModuleCategoryFilter &&
        idValue == other.idValue &&
        labelValue == other.labelValue &&
        categoryValue == other.categoryValue &&
        includedNamesValue == other.includedNamesValue &&
        excludedNamesValue == other.excludedNamesValue

    override fun hashCode(): Int {
        var result = idValue?.hashCode() ?: 0
        result = 31 * result + (labelValue?.hashCode() ?: 0)
        result = 31 * result + (categoryValue?.hashCode() ?: 0)
        result = 31 * result + includedNamesValue.hashCode()
        result = 31 * result + excludedNamesValue.hashCode()
        return result
    }

    override fun toString(): String =
        "ModuleCategoryFilter[id=$idValue, label=$labelValue, category=$categoryValue, " +
            "includedNames=$includedNamesValue, excludedNames=$excludedNamesValue]"

    companion object {
        private val all = ModuleCategoryFilter("all", "All", null, emptySet(), emptySet())

        /** Subcategories per category from `module-layout.json`; first entry of each list is always "All". */
        private val layout: Map<Category, List<ModuleCategoryFilter>> by lazy {
            val resource = ModuleCategoryFilter::class.java.getResourceAsStream("/geileraddons/module-layout.json")
            val parsed = resource?.use { parseLayout(String(it.readAllBytes(), Charsets.UTF_8)) } ?: emptyMap()
            parsed.mapValues { (_, filters) -> java.util.List.copyOf(listOf(all) + filters) }
        }

        /** Parses subcategory definitions; unknown categories and malformed data are ignored. Entries keep file order. */
        @JvmStatic
        fun parseLayout(json: String): Map<Category, List<ModuleCategoryFilter>> {
            val result = java.util.EnumMap<Category, MutableList<ModuleCategoryFilter>>(Category::class.java)
            try {
                val entries = com.google.gson.JsonParser.parseString(json).asJsonObject.getAsJsonArray("subcategories")
                for (element in entries) {
                    val entry = element.asJsonObject
                    val categoryName = entry.get("category")?.asString ?: continue
                    val category = Category.values().firstOrNull { it.name == categoryName } ?: continue
                    val id = entry.get("id")?.asString ?: continue
                    val label = entry.get("label")?.asString ?: continue
                    fun names(key: String): Set<String> =
                        entry.getAsJsonArray(key)?.map { it.asString }?.toSet() ?: emptySet()
                    result.getOrPut(category) { ArrayList() }
                        .add(ModuleCategoryFilter(id, label, category, names("modules"), names("except")))
                }
            } catch (_: RuntimeException) {
                return emptyMap()
            }
            return result
        }

        @JvmStatic
        fun options(category: Category?): List<ModuleCategoryFilter> =
            if (category == null) java.util.List.of(all) else layout[category] ?: java.util.List.of(all)

        /** Filters shown as children of the category parent; the parent itself uses the all filter. */
        @JvmStatic
        fun children(category: Category?): List<ModuleCategoryFilter> = options(category).drop(1)

        @JvmStatic
        fun resolve(category: Category?, id: String?): ModuleCategoryFilter =
            options(category).firstOrNull { it.id() == id } ?: all
    }
}
