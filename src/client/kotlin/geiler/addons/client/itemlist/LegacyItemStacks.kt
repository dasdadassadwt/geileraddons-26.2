package geiler.addons.client.itemlist

import com.mojang.serialization.Dynamic
import net.azureaaron.legacyitemdfu.LegacyItemStackFixer
import net.azureaaron.legacyitemdfu.TypeReferences
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * NotEnoughUpdates items are 1.8 data (`itemid` + `damage` + SNBT `nbttag`). They go through the Legacy Item DFU library
 * (Apache-2.0, the fixer Skyblocker uses), because the game's own DataFixer cannot read 1.8 items: numeric damage values,
 * `SkullOwner` heads and the like. Then the name and lore are set from the repository text and `ExtraAttributes` becomes the
 * item's custom data, so Price Display reads the same `id` as on real items. Written independently; the steps follow
 * Skyblocker's `ItemStackBuilder` and `LegacyItemStackFixer` (LGPL-3.0).
 */
object LegacyItemStacks {
    private const val PET_LEVEL = "100"
    private const val COLOR_CODES = "0123456789abcdef"
    private const val BUDGET = 16
    private const val MAX_SAMPLE = 8
    private val MODEL = Regex("ItemModel:\"([a-z0-9_.-]+:[a-z0-9_./-]+)\"")

    /** The registries the item codec needs; built once, usable from any thread and independent of being in a world. */
    private val REGISTRIES: HolderLookup.Provider by lazy { VanillaRegistries.createLookup() }

    @JvmStatic
    fun registries(): HolderLookup.Provider = REGISTRIES

    /** A string with `§` colour and format codes as a styled [Component]; `{LVL}` becomes a typical pet level. */
    @JvmStatic
    fun legacyText(text: String): MutableComponent {
        val result = Component.empty()
        var style = Style.EMPTY.withItalic(false)
        val source = text.replace("{LVL}", PET_LEVEL)
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) result.append(Component.literal(buffer.toString()).withStyle(style))
            buffer.setLength(0)
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '§' && i + 1 < source.length) {
                val code = source[i + 1].lowercaseChar()
                val color = if (code in COLOR_CODES) ChatFormatting.getByCode(code) else null
                val styled = when {
                    color != null -> Style.EMPTY.withItalic(false).withColor(color)
                    code == 'r' -> Style.EMPTY.withItalic(false)
                    code == 'l' -> style.withBold(true)
                    code == 'm' -> style.withStrikethrough(true)
                    code == 'n' -> style.withUnderlined(true)
                    code == 'o' -> style.withItalic(true)
                    code == 'k' -> style.withObfuscated(true)
                    else -> null
                }
                if (styled != null) {
                    flush()
                    style = styled
                    i += 2
                    continue
                }
            }
            buffer.append(c)
            i++
        }
        flush()
        return result
    }

    /** The stack for [entry], or null when the fixer or the item codec rejects it. Any thread. */
    @JvmStatic
    fun build(entry: Entry, registries: HolderLookup.Provider): ItemStack? = try {
        buildOrThrow(entry, registries)
    } catch (_: RuntimeException) {
        null
    }

    /** Like [build] but throws with the reason. */
    @JvmStatic
    fun buildOrThrow(entry: Entry, registries: HolderLookup.Provider): ItemStack {
        if (entry.category == ItemListData.CAT_VANILLA) {
            val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(entry.mcId)).orElse(null) ?: throw IllegalStateException("unknown item ${entry.mcId}")
            return ItemStack(item)
        }
        val tag = if (entry.nbt.isEmpty()) CompoundTag() else LegacySnbt.parse(entry.nbt)
        // The text comes from the repository's name and lore; the rest of `display` (a leather colour) stays.
        tag.getCompound("display").ifPresent { display ->
            display.remove("Name")
            display.remove("Lore")
            if (display.isEmpty) tag.remove("display")
        }
        val root = CompoundTag()
        root.putString("id", entry.mcId)
        root.putByte("Count", 1)
        root.putShort("Damage", entry.damage.toShort())
        root.put("tag", tag)
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        val fixed: Dynamic<Tag> = LegacyItemStackFixer.getFixer().update(
            TypeReferences.LEGACY_ITEM_STACK, Dynamic(ops, root), LegacyItemStackFixer.getFirstVersion(), LegacyItemStackFixer.getLatestVersion(),
        )
        var problem = ""
        val converted = ItemStack.CODEC.parse(fixed).resultOrPartial { problem = it }.orElse(ItemStack.EMPTY)
        if (converted.isEmpty) throw IllegalStateException("codec rejected ${entry.mcId}:${entry.damage}: ${problem.take(300)} fixed=${fixed.value.toString().take(300)}")
        val stack = ItemFallbacks.apply(entry, converted)
        stack.set(DataComponents.CUSTOM_NAME, legacyText(entry.name))
        stack.set(DataComponents.LORE, ItemLore(entry.lore.map { legacyText(it) }))
        val extra = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompoundOrEmpty("ExtraAttributes") ?: CompoundTag()
        if (extra.isEmpty && (entry.category == ItemListData.CAT_MATERIAL || entry.category == ItemListData.CAT_WEAPON)) extra.putString("id", entry.id)
        if (extra.isEmpty) stack.remove(DataComponents.CUSTOM_DATA) else stack.set(DataComponents.CUSTOM_DATA, CustomData.of(extra))
        stack.set(
            DataComponents.TOOLTIP_DISPLAY,
            stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT)
                .withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true).withHidden(DataComponents.ENCHANTMENTS, true),
        )
        return stack
    }

    /**
     * Every built stack, shared by the draw thread and the background warm-up. [warm] builds the whole list off the client
     * thread so scrolling and searching never wait for the fixer; [get] builds one on demand (at most [BUDGET] per frame)
     * for anything the warm-up has not reached yet, and returns an empty stack (draws nothing) when over budget.
     */
    class Store(private val registries: HolderLookup.Provider) {
        private val stacks = ConcurrentHashMap<String, ItemStack>()
        /** Item model each entry asks for (NEU's `ItemModel`, the Hypixel pack's model); used only while the pack is loaded. */
        private val wanted = ConcurrentHashMap<String, Identifier>()
        private val modeled = ConcurrentHashMap<String, ItemStack>()
        private val failedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val failureCount = AtomicInteger()
        private val sample = Collections.synchronizedList(ArrayList<String>())
        private var budget = BUDGET

        @Volatile
        var warmed = false
            private set

        @Volatile
        private var cancelled = false

        /** Stops a running [warm]; used when a newer repository replaces this one. */
        fun cancel() {
            cancelled = true
        }

        val failures: Int get() = failureCount.get()
        val failedSample: List<String> get() = synchronized(sample) { ArrayList(sample) }
        val size: Int get() = stacks.size

        /** Client thread, once per frame. */
        fun beginFrame() {
            budget = BUDGET
        }

        /** The built stack of [id], or null. */
        fun peek(id: String): ItemStack? = stacks[id]?.let { styled(id, it) }

        fun get(entry: Entry): ItemStack {
            stacks[entry.id]?.let { return styled(entry.id, it) }
            if (budget <= 0) return ItemStack.EMPTY
            budget--
            return styled(entry.id, make(entry))
        }

        /** The stack with the Hypixel pack's model while that pack is loaded, else the plain stack. */
        private fun styled(id: String, base: ItemStack): ItemStack {
            val model = wanted[id] ?: return base
            if (!ItemModels.available(model)) return base
            return modeled.computeIfAbsent(id) { base.copy().also { it.set(DataComponents.ITEM_MODEL, model) } }
        }

        private fun register(entry: Entry) {
            MODEL.find(entry.nbt)?.groupValues?.get(1)?.let { Identifier.tryParse(it) }?.let { wanted[entry.id] = it }
        }

        /** Stacks restored from [path] for [entries]; 0 when the file is missing, unreadable or made for another [key]. Worker thread. */
        fun load(path: Path, key: String, entries: List<Entry>): Int {
            if (!Files.isRegularFile(path)) return 0
            val root = try {
                NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap())
            } catch (_: IOException) {
                return 0
            }
            if (root.getStringOr("key", "") != key) return 0
            val saved = root.getCompoundOrEmpty("stacks")
            val ops = registries.createSerializationContext(NbtOps.INSTANCE)
            var count = 0
            for (entry in entries) {
                if (cancelled) break
                val tag = saved.get(entry.id) ?: continue
                val stack = try {
                    ItemStack.CODEC.parse(ops, tag).result().orElse(null)
                } catch (_: RuntimeException) {
                    null
                } ?: continue
                if (stack.isEmpty) continue
                register(entry)
                if (stacks.putIfAbsent(entry.id, stack) == null) count++
            }
            return count
        }

        /** Writes every built stack (not vanilla, not failed) to [path]. Worker thread; a write error leaves the old file. */
        fun save(path: Path, key: String) {
            val ops = registries.createSerializationContext(NbtOps.INSTANCE)
            val saved = CompoundTag()
            for ((id, stack) in stacks) {
                if (id in failedIds || stack.`is`(Items.BARRIER)) continue
                ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null)?.let { saved.put(id, it) }
            }
            val root = CompoundTag()
            root.putString("key", key)
            root.put("stacks", saved)
            try {
                Files.createDirectories(path.parent)
                val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
                NbtIo.writeCompressed(root, temp)
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: IOException) {
                // the next launch rebuilds
            }
        }

        /** Builds every entry in order. Worker thread. */
        fun warm(entries: List<Entry>) {
            for (entry in entries) {
                if (cancelled || Thread.currentThread().isInterrupted) return
                if (!stacks.containsKey(entry.id)) make(entry)
            }
            warmed = true
        }

        private fun make(entry: Entry): ItemStack {
            val stack = try {
                buildOrThrow(entry, registries).also { register(entry) }
            } catch (exception: RuntimeException) {
                failedIds.add(entry.id)
                failureCount.incrementAndGet()
                if (sample.size < MAX_SAMPLE) sample.add("${entry.id}: ${exception.javaClass.simpleName} ${exception.message?.take(70)}")
                ItemStack(Items.BARRIER).also { it.set(DataComponents.CUSTOM_NAME, legacyText(entry.name)) }
            }
            return stacks.putIfAbsent(entry.id, stack) ?: stack
        }
    }
}

/** Which item models exist in the loaded resource packs; the answer resets when packs reload (Hypixel's pack arrives on join). */
object ItemModels {
    private var manager: Any? = null
    private val known = ConcurrentHashMap<Identifier, Boolean>()

    @JvmStatic
    fun available(model: Identifier): Boolean {
        val current = Minecraft.getInstance()?.resourceManager ?: return false
        if (current !== manager) {
            known.clear()
            manager = current
        }
        return known.computeIfAbsent(model) { current.getResource(Identifier.fromNamespaceAndPath(it.namespace, "items/${it.path}.json")).isPresent }
    }
}
