package geiler.addons.client.tree

import kotlin.jvm.JvmRecord
import java.util.regex.Pattern

/**
 * Decides which tree chat lines to hide. Pure logic, so it can be exercised offline.
 *
 * A gift is a block of lines, not one line, and it opens with a separator that arrives before
 * anything identifies it. The separator is held until the next line determines whether it opens a
 * gift block. Nothing is held longer than a tick.
 */
class ChatSuppressor {
    enum class Action { SHOW, HIDE, HOLD }

    @JvmRecord
    data class Decision(val emitHeld: Boolean, val action: Action)

    private var inBlock = false
    private var holding = false
    private var blockLines = 0

    /** @return what to do, given which of the two hide settings are on */
    fun accept(message: String, hideGift: Boolean, hideFelled: Boolean): Decision {
        val emitHeld = holding
        holding = false
        val plain = ChatText.plain(message).trim()

        if (inBlock) {
            // Nothing can be held inside a block, so the closing separator needs no special case.
            if (isRule(plain) || ++blockLines >= MAX_BLOCK_LINES) inBlock = false
            return Decision(emitHeld, Action.HIDE)
        }

        if (hideGift && HEADER.matcher(plain).find()) {
            inBlock = true
            blockLines = 0
            // A held opening bar belongs to this block and is discarded with it.
            return Decision(false, Action.HIDE)
        }
        if (hideFelled && TreeFelled.matches(message)) return Decision(emitHeld, Action.HIDE)
        if (hideGift && isRule(plain)) {
            holding = true
            return Decision(emitHeld, Action.HOLD)
        }
        return Decision(emitHeld, Action.SHOW)
    }

    /** Closes out anything pending at the end of a client tick. */
    fun endOfTick(): Boolean {
        val pending = holding
        holding = false
        // A block always arrives inside one tick; a missing closer costs a few visible lines only.
        inBlock = false
        return pending
    }

    fun holding(): Boolean = holding

    private fun isRule(plain: String): Boolean {
        if (plain.length < MIN_RULE_LENGTH) return false
        for (character in plain) if (character != BAR) return false
        return true
    }

    companion object {
        /** U+25AC, the bar Hypixel rules its message blocks with. */
        private val BAR = 0x25ac.toChar()
        private const val MIN_RULE_LENGTH = 8
        private const val MAX_BLOCK_LINES = 12
        private val HEADER = Pattern.compile("^TREE\\s+GIFT\\b", Pattern.CASE_INSENSITIVE)
    }
}
