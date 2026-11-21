package geiler.addons.client.farming

import geiler.addons.GeilerAddons
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The plots read from the Configure Plots menu, one gzip NBT file per player under `config/geileraddons/garden-plots/`.
 * A plot is its grid cell, its name and its menu icon; icons go through the game's item codec so player heads survive.
 */
object GardenPlotStore {
    private const val VERSION = 1
    private const val MAX_BYTES = 262_144L

    class Entry(val name: String, val icon: ItemStack)

    private fun file(playerId: UUID): Path? = try {
        FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("garden-plots").resolve("$playerId.nbt")
    } catch (_: NullPointerException) {
        // A bare offline harness can reach module singletons before Fabric installs its config path.
        null
    }

    /** The saved plots of [playerId] by grid cell (25 slots, null = unknown); all null when there is no usable file. */
    fun load(playerId: UUID, registries: HolderLookup.Provider): Array<Entry?> {
        val plots = arrayOfNulls<Entry>(GardenPlotsRules.GRID * GardenPlotsRules.GRID)
        val path = file(playerId) ?: return plots
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return plots
            val root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES * 8))
            if (root.getIntOr("version", 0) != VERSION) return plots
            val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
            val list = root.getListOrEmpty("plots")
            for (i in 0 until list.size) {
                val tag = list.getCompound(i).orElse(null) ?: continue
                val index = tag.getIntOr("index", -1)
                val name = tag.getStringOr("name", "")
                if (index !in plots.indices || index == GardenPlotsRules.BARN || !GardenPlotsRules.validName(name)) continue
                val icon = ItemStack.OPTIONAL_CODEC.parse(ops, tag.get("icon") ?: continue).result().orElse(null) ?: continue
                if (!icon.isEmpty) plots[index] = Entry(name, icon)
            }
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not read the Garden Plots file {}", path, error)
        } catch (error: RuntimeException) {
            GeilerAddons.LOGGER.warn("Could not read the Garden Plots file {}", path, error)
        }
        return plots
    }

    /** Writes [plots] through a temp file so a crash never leaves a half-written file; false when it could not be saved. */
    fun save(playerId: UUID, plots: Array<Entry?>, registries: HolderLookup.Provider): Boolean {
        val path = file(playerId) ?: return false
        val ops = RegistryOps.create(NbtOps.INSTANCE, registries)
        val list = ListTag()
        for ((index, entry) in plots.withIndex()) {
            if (entry == null) continue
            val icon: Tag = ItemStack.OPTIONAL_CODEC.encodeStart(ops, entry.icon).result().orElse(null) ?: continue
            val tag = CompoundTag()
            tag.putInt("index", index)
            tag.putString("name", entry.name)
            tag.put("icon", icon)
            list.add(tag)
        }
        val root = CompoundTag()
        root.putInt("version", VERSION)
        root.put("plots", list)
        var temp: Path? = null
        try {
            Files.createDirectories(path.parent)
            temp = Files.createTempFile(path.parent, "plots-", ".tmp")
            NbtIo.writeCompressed(root, temp)
            if (Files.size(temp) > MAX_BYTES) return false
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            temp = null
            return true
        } catch (error: IOException) {
            GeilerAddons.LOGGER.warn("Could not save the Garden Plots file {}", path, error)
            return false
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}
