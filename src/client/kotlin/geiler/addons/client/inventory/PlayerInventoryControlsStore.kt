package geiler.addons.client.inventory

import kotlin.jvm.JvmName
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmStatic

/** Java-facing persistence entrypoints for the Kotlin-backed local inventory-controls store. */
class PlayerInventoryControlsStore private constructor() {
    @JvmRecord
    internal data class LoadResult(val success: Boolean, val data: PlayerInventoryControlsData?)

    companion object {
        @JvmStatic
        fun load(): PlayerInventoryControlsData = PlayerInventoryControlsStoreLogic.load()

        @JvmStatic
        @JvmName("readForReload")
        internal fun readForReload(): LoadResult = PlayerInventoryControlsStoreLogic.readForReload()

        @JvmStatic
        @JvmName("flushForProfileImport")
        internal fun flushForProfileImport(data: PlayerInventoryControlsData?): Boolean =
            PlayerInventoryControlsStoreLogic.flushForProfileImport(data)

        @JvmStatic
        fun save(data: PlayerInventoryControlsData?): Boolean = PlayerInventoryControlsStoreLogic.save(data)

        @JvmStatic
        fun saveLater(data: PlayerInventoryControlsData?) = PlayerInventoryControlsStoreLogic.saveLater(data)

        @JvmStatic
        fun awaitIdle(timeoutMillis: Long): Boolean = PlayerInventoryControlsStoreLogic.awaitIdle(timeoutMillis)
    }
}

// Keep the former Java record accessor call syntax available to the Kotlin core.
internal fun PlayerInventoryControlsStore.LoadResult.success(): Boolean = success

internal fun PlayerInventoryControlsStore.LoadResult.data(): PlayerInventoryControlsData? = data
