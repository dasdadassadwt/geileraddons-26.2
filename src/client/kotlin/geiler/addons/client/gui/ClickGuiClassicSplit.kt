package geiler.addons.client.gui

import com.google.gson.JsonParser
import geiler.addons.client.module.Category

/**
 * Classic-menu-only split of a crowded category into extra columns, read from `classicColumns` in
 * `module-layout.json`. The Modern menu keeps its chips; modules not named here stay in the category's own column.
 */
object ClickGuiClassicSplit {
    class Group(val category: Category, val label: String, val modules: Set<String>)

    private val shipped: List<Group> by lazy {
        val resource = ClickGuiClassicSplit::class.java.getResourceAsStream("/geileraddons/module-layout.json")
        resource?.use { parse(String(it.readAllBytes(), Charsets.UTF_8)) } ?: emptyList()
    }

    @JvmStatic
    fun groups(): List<Group> = shipped

    /** Entries with an unknown category, no label or no module names are skipped; bad JSON gives no groups. */
    @JvmStatic
    fun parse(json: String): List<Group> = try {
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("classicColumns")?.mapNotNull { element ->
            val entry = element.asJsonObject
            val category = Category.values().firstOrNull { it.name == entry.get("category")?.asString } ?: return@mapNotNull null
            val label = entry.get("label")?.asString ?: return@mapNotNull null
            val names = entry.getAsJsonArray("modules")?.map { it.asString }?.toSet() ?: return@mapNotNull null
            Group(category, label, names)
        } ?: emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** First = items named in [group], second = the rest; both keep their order. */
    @JvmStatic
    fun <T> partition(items: List<T>, group: Set<String>, nameOf: (T) -> String): Pair<List<T>, List<T>> =
        items.partition { nameOf(it) in group }
}
