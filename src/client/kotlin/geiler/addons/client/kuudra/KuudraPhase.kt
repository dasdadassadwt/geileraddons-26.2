package geiler.addons.client.kuudra

/**
 * The phases of a Kuudra run, after the IQ mod's phase model (https://github.com/iqaddons/IQ, Apache-2.0): Elle's chat
 * lines and a few events move a run from one phase to the next. BOSS has no line; it starts when the player drops below y 10.
 */
enum class KuudraPhase(val displayName: String) {
    NONE("None"),
    SUPPLIES("Supplies"),
    BUILD("Build"),
    EATEN("Eaten"),
    STUN("Stun"),
    DPS("DPS"),
    SKIP("Skip"),
    BOSS("Boss"),
    COMPLETED("Completed");

    fun isInRun(): Boolean = this != NONE && this != COMPLETED

    fun isCombat(): Boolean = this == STUN || this == DPS || this == SKIP || this == BOSS

    companion object {
        /** The phase a plain chat line starts, or null when it starts none. NONE means the run is left. */
        @JvmStatic
        fun fromMessage(message: String): KuudraPhase? = when {
            message.contains("Sending to server") || message.contains("Starting in 4 seconds...") -> NONE
            message.contains("[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!") -> SUPPLIES
            message.contains("[NPC] Elle: OMG! Great work collecting my supplies!") -> BUILD
            message.contains("[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!") -> EATEN
            message.contains("has been eaten by Kuudra!") && !message.contains("Elle") -> STUN
            message.contains("destroyed one of Kuudra's pods!") -> DPS
            message.contains("[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!") -> SKIP
            message.trim() == "KUUDRA DOWN!" -> COMPLETED
            else -> null
        }

        @JvmStatic
        fun byName(name: String): KuudraPhase? = values().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
