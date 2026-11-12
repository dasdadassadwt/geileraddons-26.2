package geiler.addons.client.dungeon

/** Bounded shutdown drain for versioned asynchronous guide snapshots. */
class DungeonGuideFlush private constructor() {
    interface Writer {
        /** Waits for the current write, returning false when it could not be resolved. */
        fun awaitPending(): Boolean
        fun dirty(): Boolean
        fun persistenceBlocked(): Boolean
        fun hasPending(): Boolean
        fun writeSnapshot()
    }

    companion object {
        private const val MAX_SNAPSHOT_WRITES = 2

        @JvmStatic
        fun drain(writer: Writer) {
            for (write in 0 until MAX_SNAPSHOT_WRITES) {
                if (!writer.awaitPending()) return
                if (writer.persistenceBlocked() || !writer.dirty()) return
                if (writer.hasPending()) return
                writer.writeSnapshot()
            }
            writer.awaitPending()
        }
    }
}
