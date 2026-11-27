package geiler.addons.client.profileviewer

import com.mojang.serialization.Dynamic
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.itemlist.LegacyItemStacks
import net.azureaaron.legacyitemdfu.LegacyItemStackFixer
import net.azureaaron.legacyitemdfu.TypeReferences
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/**
 * Profile API items (1.8 NBT with numeric ids) to stacks. First the Legacy Item DFU fixer, the route Skyblocker's profile
 * viewer takes; when that fails, the Item List stack for the SkyBlock id with the item's own name and lore; else a barrier.
 * Uses the static registries, so it runs off the client thread.
 */
object PvItems {
    class Result(val stack: ItemStack, val fixed: Boolean)

    @JvmStatic
    fun stack(tag: CompoundTag): Result {
        if (tag.isEmpty || !tag.contains("id")) return Result(ItemStack.EMPTY, true)
        try {
            val ops = LegacyItemStacks.registries().createSerializationContext(NbtOps.INSTANCE)
            val fixed: Dynamic<Tag> = LegacyItemStackFixer.getFixer().update(
                TypeReferences.LEGACY_ITEM_STACK, Dynamic(ops, tag.copy()), LegacyItemStackFixer.getFirstVersion(), LegacyItemStackFixer.getLatestVersion(),
            )
            val stack = ItemStack.CODEC.parse(fixed).result().orElse(ItemStack.EMPTY)
            if (!stack.isEmpty) return Result(detach(stack), true)
        } catch (_: RuntimeException) {
        }
        return Result(fallback(tag), false)
    }

    /** SkyBlock id of a profile item (`ExtraAttributes.id`), or "". */
    @JvmStatic
    fun skyblockId(tag: CompoundTag): String =
        tag.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "")

    /** Item List stack for an id such as `WHEAT`, `LOG:2` or a pet `GOLDEN_DRAGON;4`; null while the list is not loaded. */
    @JvmStatic
    fun byId(id: String): ItemStack? {
        val compiled = ItemListRepository.compiled ?: return null
        val entry = compiled.byId[id] ?: compiled.byId[id.replace(':', '-')] ?: return null
        return compiled.stacks.get(entry)?.takeIf { !it.isEmpty }?.copy()
    }

    /**
     * Drops enchantment components: they hold holders from the static registries, whose tags are never bound, and
     * the vanilla tooltip asks those tags (crash "Tags not bound"). SkyBlock lore already lists the enchantments; the
     * glint stays through the override.
     */
    @JvmStatic
    fun detach(stack: ItemStack): ItemStack {
        val enchanted = !(stack.get(DataComponents.ENCHANTMENTS)?.isEmpty ?: true) || !(stack.get(DataComponents.STORED_ENCHANTMENTS)?.isEmpty ?: true)
        stack.remove(DataComponents.ENCHANTMENTS)
        stack.remove(DataComponents.STORED_ENCHANTMENTS)
        if (enchanted) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    /** Soulbound items say so in their lore (`* Soulbound *` or `* Co-op Soulbound *`). */
    @JvmStatic
    fun soulbound(tag: CompoundTag): Boolean {
        val lore = tag.getCompoundOrEmpty("tag").getCompoundOrEmpty("display").getListOrEmpty("Lore")
        return (0 until lore.size).any { lore.getStringOr(it, "").contains("Soulbound") }
    }

    private fun fallback(tag: CompoundTag): ItemStack {
        val id = skyblockId(tag)
        val stack = (if (id.isEmpty()) null else byId(id)) ?: ItemStack(Items.BARRIER).also {
            it.set(DataComponents.CUSTOM_NAME, Component.literal("Unreadable item ${id.ifEmpty { "?" }}"))
        }
        val display = tag.getCompoundOrEmpty("tag").getCompoundOrEmpty("display")
        display.getString("Name").ifPresent { stack.set(DataComponents.CUSTOM_NAME, LegacyItemStacks.legacyText(it)) }
        val lore = display.getListOrEmpty("Lore")
        if (!lore.isEmpty()) stack.set(DataComponents.LORE, ItemLore((0 until lore.size).map { LegacyItemStacks.legacyText(lore.getStringOr(it, "")) }))
        stack.count = tag.getByteOr("Count", 1).toInt().coerceIn(1, 99)
        return stack
    }
}
