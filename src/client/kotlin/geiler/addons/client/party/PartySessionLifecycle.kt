package geiler.addons.client.party

/** Tracks party-session boundaries independently from `ClientLevel` replacements. */
class PartySessionLifecycle {
    private var connection: Any? = null
    private var initialized = false

    /**
     * Records the current connection and reports when an established connection ended or changed.
     * A first observation and a connection starting after an observed disconnect do not invalidate
     * party state; the disconnect already did that.
     */
    fun updateConnection(currentConnection: Any?): Boolean {
        if (!initialized) {
            initialized = true
            connection = currentConnection
            return false
        }
        if (connection === currentConnection) return false

        val endedEstablishedConnection = connection != null
        connection = currentConnection
        return endedEstablishedConnection
    }

    companion object {
        /** A self-join confirmation starts a new session only when an older party is still tracked. */
        @JvmStatic
        fun shouldResetForConfirmedSelfJoin(alreadyInParty: Boolean): Boolean = alreadyInParty
    }
}
