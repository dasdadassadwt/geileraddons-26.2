package geiler.addons.client.collections

import geiler.addons.client.chat.ChatStringSemantics
import java.util.HashSet
import java.util.LinkedHashMap

/** Persistent folder hierarchy shared by configurable, multi-entry feature lists. */
class FolderTree {
    private val folderEntries = mutableListOf<Folder>()
    private var nextId = 0L

    fun folders(): List<Folder> = java.util.List.copyOf(folderEntries)

    fun create(name: String?, parentId: String?): Folder? {
        val cleanName = cleanName(name)
        if (cleanName.isEmpty()) return null
        val parent = if (contains(parentId)) parentId else null
        var id: String
        do {
            id = "folder-${nextId++}"
        } while (contains(id))
        val folder = Folder(id, cleanName, parent)
        folderEntries.add(folder)
        return folder
    }

    fun rename(id: String?, name: String?): Boolean {
        val cleanName = cleanName(name)
        if (cleanName.isEmpty()) return false
        val index = indexOf(id)
        if (index < 0) return false
        val old = folderEntries[index]
        folderEntries[index] = Folder(old.id, cleanName, old.parentId)
        return true
    }

    fun move(id: String?, newParentId: String?): Boolean {
        val index = indexOf(id)
        if (index < 0) return false
        val parent = if (newParentId == null || ChatStringSemantics.isBlank(newParentId)) null else newParentId
        if (parent != null && (!contains(parent) || parent == id || isDescendant(parent, id))) return false
        val old = folderEntries[index]
        if (old.parentId == parent) return true
        folderEntries[index] = Folder(old.id, old.name, parent)
        return true
    }

    /** Removes a folder and promotes its direct children. Returns the folder's former parent. */
    fun delete(id: String?): String? {
        val index = indexOf(id)
        if (index < 0) return null
        val removed = folderEntries.removeAt(index)
        for (i in folderEntries.indices) {
            val child = folderEntries[i]
            if (id == child.parentId) {
                folderEntries[i] = Folder(child.id, child.name, removed.parentId)
            }
        }
        return removed.parentId
    }

    fun contains(id: String?): Boolean = indexOf(id) >= 0

    fun folder(id: String?): Folder? {
        val index = indexOf(id)
        return if (index < 0) null else folderEntries[index]
    }

    fun childrenOf(parentId: String?): List<Folder> = java.util.List.copyOf(
        folderEntries.filter { it.parentId == parentId },
    )

    fun isDescendant(candidateId: String?, ancestorId: String?): Boolean {
        if (candidateId == null || ancestorId == null) return false
        var current = folder(candidateId)
        val visited = HashSet<String>()
        while (current != null) {
            val parent = current.parentId ?: break
            val currentId = current.id ?: break
            if (!visited.add(currentId)) break
            if (ancestorId == parent) return true
            current = folder(parent)
        }
        return false
    }

    /** Restores known-good folders while ignoring duplicate ids, invalid parents, and cycles. */
    fun restore(saved: List<Folder?>?) {
        folderEntries.clear()
        nextId = 0
        if (saved == null) return
        val unique = LinkedHashMap<String, Folder>()
        for (folder in saved) {
            if (folder == null) continue
            val id = folder.id ?: continue
            if (ChatStringSemantics.isBlank(id)) continue
            val name = cleanName(folder.name)
            if (name.isEmpty() || unique.containsKey(id)) continue
            unique[id] = Folder(id, name, folder.parentId)
            if (id.startsWith("folder-")) {
                try {
                    nextId = maxOf(nextId, id.substring(7).toLong() + 1)
                } catch (_: NumberFormatException) {
                    // Keep the counter derived from the valid ids seen so far.
                }
            }
        }
        for ((candidateId, candidate) in unique) {
            var parent = candidate.parentId
            if (parent != null && (!unique.containsKey(parent) || parent == candidateId ||
                    savedCycle(candidateId, parent, unique))) {
                parent = null
            }
            folderEntries.add(Folder(candidateId, candidate.name, parent))
        }
    }

    private fun savedCycle(id: String, parent: String, saved: Map<String, Folder>): Boolean {
        val visited = HashSet<String>()
        var current: String? = parent
        while (current != null && visited.add(current)) {
            if (id == current) return true
            current = saved[current]?.parentId
        }
        return current != null
    }

    private fun indexOf(id: String?): Int {
        if (id == null) return -1
        return folderEntries.indexOfFirst { id == it.id }
    }

    private fun cleanName(name: String?): String =
        name?.let { ChatStringSemantics.trim(it) }?.take(48).orEmpty()

    /** Keeps the Java record shape used by the config schema and Java callers. */
    @JvmRecord
    data class Folder(val id: String?, val name: String?, val parentId: String?) {
        override fun toString(): String = "Folder[id=$id, name=$name, parentId=$parentId]"
    }
}
