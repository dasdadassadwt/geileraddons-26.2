package geiler.addons.client.sim

/** A shortbow's shot cooldown in ticks: a click inside it is held, and fires the tick it ends. */
class ShotCooldown {
    private var ready = Long.MIN_VALUE
    private var held = false

    /** A click on tick [now]; true when it fires now. */
    fun click(now: Long, cooldown: Int): Boolean {
        if (now < ready) {
            held = true
            return false
        }
        ready = now + cooldown
        held = false
        return true
    }

    /** Tick [now]; true when a held click fires. */
    fun tick(now: Long, cooldown: Int): Boolean = held && click(now, cooldown)
}
