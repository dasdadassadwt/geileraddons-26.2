package geiler.addons.client.inventory

import java.util.WeakHashMap

/**
 * Keeps a slot's last rarity/medal/attribute info for [graceTicks] when the same item type shows up without SkyBlock
 * data for a moment (the server resends container contents, and armor can arrive before its lore). Pure state.
 */
class SlotInfoGrace<K : Any>(private val graceTicks: Long) {
    private class Memo(val item: Any, val info: ItemBackgrounds.StackInfo, val seenAt: Long)

    private val memos = WeakHashMap<K, Memo>()

    /** How many times a remembered info replaced a blank one (diagnostics). */
    var used = 0
        private set

    /** [info] when it carries a background (and is remembered), else the slot's remembered info if still fresh. */
    fun resolve(slot: K, item: Any, info: ItemBackgrounds.StackInfo, now: Long): ItemBackgrounds.StackInfo {
        if (info.rarity != null || info.jacobColor >= 0 || info.hasAttributes) {
            memos[slot] = Memo(item, info, now)
            return info
        }
        val memo = memos[slot] ?: return info
        if (memo.item != item || now - memo.seenAt > graceTicks) {
            memos.remove(slot)
            return info
        }
        used++
        return memo.info
    }

    fun clear() {
        memos.clear()
    }
}
