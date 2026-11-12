package geiler.addons.client.enchanting

/** Diagnostic formatting behind the Java record's stable public API. */
object SequenceProbeLogic {
    @JvmStatic
    fun describe(probe: SequenceProbe): String {
        val lastStatus = probe.lastStatus()
        val modelState = probe.modelState()
        if (lastStatus.isEmpty() && modelState.isEmpty()) return "none"
        return "status=\"$lastStatus\" model=$modelState" +
            " openingClick=${probe.openingClickOwed()} nextSlot=${probe.nextSlotId()}" +
            " solveWindow=${probe.solveWindowOpen()}"
    }
}
