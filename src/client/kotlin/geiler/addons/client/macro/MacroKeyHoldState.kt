package geiler.addons.client.macro

/** Client-thread synthetic key state retained between vanilla input polls. */
class MacroKeyHoldState {
    private var heldKeyCode = -1
    private var releaseAtNanos = 0L
    private var scheduled = false

    fun begin(keyCode: Int, releaseAtNanos: Long) {
        heldKeyCode = keyCode
        this.releaseAtNanos = releaseAtNanos
        scheduled = keyCode >= 0
    }

    fun isScheduled(): Boolean = scheduled

    fun isDown(queriedKeyCode: Int, nowNanos: Long): Boolean =
        scheduled && heldKeyCode == queriedKeyCode && nowNanos < releaseAtNanos

    fun isDue(nowNanos: Long): Boolean = scheduled && nowNanos >= releaseAtNanos

    fun keyCode(): Int = heldKeyCode

    fun clear() {
        heldKeyCode = -1
        releaseAtNanos = 0L
        scheduled = false
    }
}
