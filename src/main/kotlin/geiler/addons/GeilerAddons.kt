package geiler.addons

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Common entrypoint; all gameplay and client registration remains on the client entrypoint. */
object GeilerAddons : ModInitializer {
    const val MOD_ID = "geileraddons"
    @JvmField
    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    override fun onInitialize() {
        LOGGER.info("GeilerAddons loaded")
    }

    @JvmStatic
    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
