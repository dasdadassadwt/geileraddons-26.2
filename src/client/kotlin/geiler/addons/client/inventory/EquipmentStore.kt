package geiler.addons.client.inventory

import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Remembered equipment, one gzip NBT file per account under `config/geileraddons/equipment/`, with one entry per
 * SkyBlock profile. Items go through the game's item codec on the client thread; the file is written off it.
 */
object EquipmentStore {
    private const val VERSION = 1
    private const val MAX_BYTES = 1_048_576L

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "GeilerAddons equipment").apply { isDaemon = true }
    }

    private fun file(account: UUID): Path? = try {
        FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("equipment").resolve("$account.nbt")
    } catch (_: NullPointerException) {
        // A bare offline harness can reach module singletons before Fabric installs its config path.
        null
    }

    /** The saved file of [account], or an empty one. */
    fun load(account: UUID): CompoundTag {
        val path = file(account) ?: return CompoundTag()
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return CompoundTag()
            val root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES * 8))
            return if (root.getIntOr("version", 0) == VERSION) root else CompoundTag()
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not read the equipment file {}", path, error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not read the equipment file {}", path, error)
        }
        return CompoundTag()
    }

    /** Puts [items] under [key] in [root]. */
    fun put(root: CompoundTag, key: String, items: List<ItemStack>, registries: HolderLookup.Provider) {
        val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
        val entry = CompoundTag()
        items.forEachIndexed { index, stack ->
            if (stack.isEmpty) return@forEachIndexed
            ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).result().ifPresent { entry.put("slot$index", it) }
        }
        val profiles = root.getCompoundOrEmpty("profiles")
        profiles.put(key, entry)
        root.put("profiles", profiles)
        root.putInt("version", VERSION)
    }

    /** The items saved under [key], or null when that profile was never read. */
    fun get(root: CompoundTag, key: String, registries: HolderLookup.Provider): List<ItemStack>? {
        val profiles = root.getCompoundOrEmpty("profiles")
        if (!profiles.contains(key)) return null
        val entry = profiles.getCompoundOrEmpty(key)
        val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
        return List(EquipmentRules.SLOTS) { index ->
            val tag = entry.get("slot$index")
            if (tag == null) ItemStack.EMPTY
            else ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY)
        }
    }

    /** Writes a copy of [root] through a temp file, so a crash never leaves a half-written file. */
    fun save(account: UUID, root: CompoundTag) {
        val path = file(account) ?: return
        val copy = root.copy()
        writer.execute {
            var temp: Path? = null
            try {
                Files.createDirectories(path.parent)
                temp = Files.createTempFile(path.parent, "equipment-", ".tmp")
                NbtIo.writeCompressed(copy, temp)
                if (Files.size(temp) <= MAX_BYTES) {
                    try {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
                    }
                    temp = null
                }
            } catch (error: IOException) {
                GeilerAddons.LOGGER.warn("Could not save the equipment file {}", path, error)
            } finally {
                temp?.let { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }
}
