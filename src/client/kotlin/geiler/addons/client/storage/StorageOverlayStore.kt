package geiler.addons.client.storage

import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.nbt.ListTag
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The on-disk copy of the Storage Overlay cache, one gzip NBT file per player under `config/geileraddons/storage-overlay/`.
 * Stacks go through the game's own item codec, so lore and custom data survive. Encoding and decoding run on the client
 * thread (they read live stacks and registries); only the file write runs on a worker.
 */
object StorageOverlayStore {
    private const val VERSION = 1

    /** The pages read back, how many were dropped as unreadable, and the file size. */
    class Loaded(val pages: Map<Int, List<ItemStack>>, val dropped: Int, val bytes: Long)

    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "geileraddons-storage-overlay").apply { isDaemon = true } }

    private fun file(playerId: UUID): Path? = try {
        FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("storage-overlay")
            .resolve(StorageOverlayRules.storeFileName(playerId.toString()))
    } catch (_: NullPointerException) {
        // A bare offline harness can reach module singletons before Fabric installs its config path.
        null
    }

    /** The saved pages of [playerId]; null when there is no file, an empty result when the file was unusable. */
    fun load(playerId: UUID, registries: HolderLookup.Provider): Loaded? {
        val path = file(playerId) ?: return null
        try {
            if (!Files.isRegularFile(path)) return null
            val size = Files.size(path)
            if (!StorageOverlayRules.storeSizeOk(size)) return Loaded(emptyMap(), 0, size)
            val root = NbtIo.readCompressed(path, NbtAccounter.create(StorageOverlayRules.MAX_STORE_BYTES * 8))
            if (root.getIntOr("version", 0) != VERSION) return Loaded(emptyMap(), 0, size)
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val pages = HashMap<Int, List<ItemStack>>()
            var dropped = 0
            val list = root.getListOrEmpty("pages")
            for (i in 0 until list.size) {
                val entry = list.getCompound(i).orElse(null)
                val page = entry?.let { decodePage(it, ops) }
                if (page == null) dropped++ else pages[page.first] = page.second
            }
            return Loaded(pages, dropped, size)
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not read the Storage Overlay cache {}", path, error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not read the Storage Overlay cache {}", path, error)
        }
        return Loaded(emptyMap(), 0, 0)
    }

    private fun decodePage(entry: CompoundTag, ops: RegistryOps<Tag>): Pair<Int, List<ItemStack>>? {
        val index = entry.getIntOr("index", -1)
        val tags = entry.getListOrEmpty("items")
        if (!StorageOverlayRules.validPageIndex(index) || !StorageOverlayRules.validPageItems(tags.size)) return null
        val items = ArrayList<ItemStack>(tags.size)
        for (tag in tags) items.add(ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(null) ?: return null)
        return index to items
    }

    /** Encodes [pages] (index to item slots) on the calling thread; null when a stack refuses to encode. */
    fun encode(pages: List<Pair<Int, List<ItemStack>>>, registries: HolderLookup.Provider): CompoundTag? {
        val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
        val list = ListTag()
        for ((index, items) in pages) {
            val tags = ListTag()
            for (stack in items) tags.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().orElse(null) ?: return null)
            val entry = CompoundTag()
            entry.putInt("index", index)
            entry.put("items", tags)
            list.add(entry)
        }
        val root = CompoundTag()
        root.putInt("version", VERSION)
        root.put("pages", list)
        return root
    }

    /** Writes [root] for [playerId] on the worker through a temp file, so a crash never leaves a half-written cache. */
    fun saveAsync(playerId: UUID, root: CompoundTag) {
        val path = file(playerId) ?: return
        writer.execute {
            var temp: Path? = null
            try {
                Files.createDirectories(path.parent)
                temp = Files.createTempFile(path.parent, "storage-", ".tmp")
                NbtIo.writeCompressed(root, temp)
                if (!StorageOverlayRules.storeSizeOk(Files.size(temp))) {
                    GeilerAddons.LOGGER.warn("Storage Overlay cache is larger than {} bytes; not saved", StorageOverlayRules.MAX_STORE_BYTES)
                    return@execute
                }
                try {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                }
                temp = null
            } catch (error: IOException) {
                GeilerAddons.LOGGER.warn("Could not save the Storage Overlay cache {}", path, error)
            } finally {
                temp?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    /** Waits (bounded) for queued writes; called when the client stops. */
    fun flush() {
        runCatching { writer.submit { }.get(5, TimeUnit.SECONDS) }
    }
}
