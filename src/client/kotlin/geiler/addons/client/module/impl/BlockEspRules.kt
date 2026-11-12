package geiler.addons.client.module.impl

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block

/** Registered-block identity rules; block-state properties intentionally do not participate. */
object BlockEspRules {
    @JvmStatic
    fun resolve(selectedId: String?): Block? {
        val id = Identifier.tryParse(selectedId.orEmpty()) ?: return null
        return if (BuiltInRegistries.BLOCK.keySet().contains(id)) BuiltInRegistries.BLOCK.getValue(id) else null
    }

    @JvmStatic
    fun matches(selectedId: String?, actualBlock: Block?): Boolean {
        if (selectedId.isNullOrBlank() || actualBlock == null || resolve(selectedId) == null) return false
        val actualId = BuiltInRegistries.BLOCK.getKey(actualBlock)
        return actualId != null && selectedId == actualId.toString()
    }
}
