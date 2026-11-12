package geiler.addons.client.module

import java.util.Collections
import java.util.IdentityHashMap

object ModuleManager {
    private val modules = ArrayList<Module>()
    private val registeredModules: MutableSet<Module> =
        Collections.newSetFromMap(IdentityHashMap<Module, Boolean>())

    @JvmStatic
    fun register(module: Module?) {
        if (module == null) throw IllegalArgumentException("module must not be null")
        if (modules.any { it === module || it.name() == module.name() }) {
            throw IllegalArgumentException("Module already registered: ${module.name()}")
        }
        modules.add(module)
        registeredModules.add(module)
    }

    @JvmStatic
    fun modules(): List<Module> = Collections.unmodifiableList(ArrayList(modules))

    @JvmStatic
    fun modules(category: Category?): List<Module> =
        Collections.unmodifiableList(modules.filterTo(ArrayList()) { it.category() == category })

    /** Tests registry membership without rebuilding and scanning a category list. */
    @JvmStatic
    fun contains(category: Category?, module: Module?): Boolean =
        module != null && registeredModules.contains(module) && module.category() == category
}
