package geiler.addons.client.rename

import geiler.addons.client.utility.SkyblockItems
import net.minecraft.client.Minecraft
import net.minecraft.core.UUIDUtil
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.WeakHashMap

/**
 * Rewrites the components of a received stack so the rest of the client (slots, tooltips, hotbar, held item) shows the
 * override with no per-frame work. The real stack is kept in a weak side map keyed by the received instance, never in the
 * server's `custom_data`, so the Hypixel uuid and id stay intact and [original] can hand the real item to other modules.
 */
object RenameApplier {
    private val originals = WeakHashMap<ItemStack, ItemStack>()

    @JvmField
    var applied = 0L

    @JvmField
    var restored = 0L

    @JvmField
    var slowest = 0L

    fun originalCount(): Int = originals.size

    /** Components an override can touch; restoring copies exactly these back from the saved original. */
    private val TOUCHED: List<DataComponentType<*>> = listOf(
        DataComponents.CUSTOM_NAME, DataComponents.ITEM_MODEL, DataComponents.LORE,
        DataComponents.ENCHANTMENT_GLINT_OVERRIDE, DataComponents.DYED_COLOR, DataComponents.PROFILE,
    )

    /** The real item behind an overridden stack, or the stack itself when it is not overridden. */
    @JvmStatic
    fun original(stack: ItemStack): ItemStack = originals[stack] ?: stack

    /** Applies the matching override to [stack] in place (undoing an earlier one first). Returns true if it changed. */
    @JvmStatic
    fun apply(stack: ItemStack): Boolean {
        val store = RenameStore.INSTANCE
        if (!store.any || stack.isEmpty) return false
        val saved = originals[stack]
        val override = store.lookup(SkyblockItems.itemUuid(stack), SkyblockItems.skyBlockId(stack))
        if (override == null) {
            if (saved != null) {
                restore(stack, saved)
                originals.remove(stack)
                restored++
            }
            return false
        }
        if (saved != null) restore(stack, saved) else originals[stack] = stack.copy()
        write(stack, override)
        applied++
        return true
    }

    /** Puts the original components back on every overridden stack the player can currently see. */
    @JvmStatic
    fun restoreAll() {
        for (stack in visibleStacks()) {
            val saved = originals[stack] ?: continue
            restore(stack, saved)
            originals.remove(stack)
            restored++
        }
    }

    /** Re-evaluates every stack in the open menus after an override changed. */
    @JvmStatic
    fun reapplyAll() {
        for (stack in visibleStacks()) apply(stack)
    }

    /** A copy of [base] (its real item) with [override] applied, for the editor preview. */
    @JvmStatic
    fun preview(base: ItemStack, override: RenameOverride): ItemStack {
        val copy = original(base).copy()
        write(copy, override)
        return copy
    }

    private fun visibleStacks(): List<ItemStack> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        val out = ArrayList<ItemStack>()
        fun menu(m: AbstractContainerMenu) {
            for (slot in m.slots) if (!slot.item.isEmpty) out.add(slot.item)
            if (!m.carried.isEmpty) out.add(m.carried)
        }
        menu(player.inventoryMenu)
        if (player.containerMenu !== player.inventoryMenu) menu(player.containerMenu)
        return out
    }

    private fun <T : Any> restoreOne(stack: ItemStack, type: DataComponentType<T>, from: ItemStack) {
        val value = from.get(type)
        if (value != null) stack.set(type, value) else stack.remove(type)
    }

    private fun restore(stack: ItemStack, saved: ItemStack) {
        for (type in TOUCHED) restoreOne(stack, type, saved)
    }

    private fun write(stack: ItemStack, o: RenameOverride) {
        val flags = o.bold || o.italic || o.underline || o.strike || o.obfuscated
        if (o.name.isNotEmpty() || o.gradient.isNotEmpty() || flags) {
            val markup = o.name.ifEmpty { stack.hoverName.string }
            val segs = RenameText.segments(markup, o.gradient, o.bold, o.italic, o.underline, o.strike, o.obfuscated)
            if (segs.isNotEmpty()) stack.set(DataComponents.CUSTOM_NAME, component(segs))
        }
        writeIcon(stack, o.icon)
        writeLore(stack, o)
        when (o.glint) {
            "On" -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
            "Off" -> stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
        }
        if (o.dye >= 0) stack.set(DataComponents.DYED_COLOR, DyedItemColor(o.dye))
    }

    private fun writeIcon(stack: ItemStack, icon: String) {
        if (icon.isEmpty()) return
        when {
            icon.startsWith("head:") -> {
                val name = icon.substring(5).trim()
                if (name.isEmpty() || name.length > 16) return
                stack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("player_head"))
                stack.set(DataComponents.PROFILE, ResolvableProfile.createUnresolved(name))
            }
            icon.startsWith("texture:") -> {
                val profile = textureProfile(icon.substring(8).trim()) ?: return
                stack.set(DataComponents.ITEM_MODEL, Identifier.withDefaultNamespace("player_head"))
                stack.set(DataComponents.PROFILE, profile)
            }
            else -> Identifier.tryParse(icon.trim())?.let { stack.set(DataComponents.ITEM_MODEL, it) }
        }
    }

    /** A head profile carrying a Base64 `textures` property, built through the profile codec so no authlib internals are needed. */
    private fun textureProfile(base64: String): ResolvableProfile? {
        if (base64.isEmpty() || base64.length > 4096) return null
        val tag = CompoundTag()
        tag.putString("name", "GA")
        tag.put("id", IntArrayTag(UUIDUtil.uuidToIntArray(UUID.nameUUIDFromBytes(base64.toByteArray(StandardCharsets.UTF_8)))))
        val property = CompoundTag()
        property.putString("name", "textures")
        property.put("value", StringTag.valueOf(base64))
        val properties = ListTag()
        properties.add(property)
        tag.put("properties", properties)
        return ResolvableProfile.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null)
    }

    private fun writeLore(stack: ItemStack, o: RenameOverride) {
        if (o.lore.isEmpty() && o.rarityLine.isEmpty() && o.hide.isEmpty() && o.loreMode == "Keep") return
        var lines: List<Component> = stack.get(DataComponents.LORE)?.lines() ?: emptyList()
        val terms = o.hide.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (terms.isNotEmpty()) lines = lines.filter { line -> val s = line.string.lowercase(); terms.none { s.contains(it) } }
        val extra = if (o.lore.isEmpty()) emptyList() else o.lore.split('|').take(ItemLore.MAX_LINES).map { line(it) }
        lines = when (o.loreMode) {
            "Replace" -> extra
            "Append" -> lines + extra
            else -> lines
        }
        if (o.rarityLine.isNotEmpty() && lines.isNotEmpty()) lines = lines.dropLast(1) + line(o.rarityLine)
        stack.set(DataComponents.LORE, ItemLore(lines.take(ItemLore.MAX_LINES)))
    }

    private fun line(markup: String): Component =
        component(RenameText.segments(markup, "", false, false, false, false, false))

    /** Runs to a component; italic is always explicit so vanilla's italic custom-name default never leaks in. */
    private fun component(segs: List<RenameText.Seg>): Component {
        val root: MutableComponent = Component.empty()
        for (seg in segs) {
            var style = Style.EMPTY.withItalic(seg.italic)
            if (seg.rgb >= 0) style = style.withColor(seg.rgb)
            if (seg.bold) style = style.withBold(true)
            if (seg.underline) style = style.withUnderlined(true)
            if (seg.strike) style = style.withStrikethrough(true)
            if (seg.obfuscated) style = style.withObfuscated(true)
            root.append(Component.literal(seg.text).withStyle(style))
        }
        return root
    }
}
