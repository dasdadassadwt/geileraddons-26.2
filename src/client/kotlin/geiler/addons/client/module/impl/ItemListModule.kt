package geiler.addons.client.module.impl

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import geiler.addons.client.dungeon.ClientJsonFile
import geiler.addons.client.gui.InventorySurfaces
import geiler.addons.client.inventory.SkyblockRarity
import geiler.addons.client.itemlist.ItemListData
import geiler.addons.client.itemlist.ItemListPanel
import geiler.addons.client.itemlist.ItemListRepository
import geiler.addons.client.module.BooleanSetting
import geiler.addons.client.module.Category
import geiler.addons.client.module.ChoiceSetting
import geiler.addons.client.module.ClientKeyMappingRegistrar
import geiler.addons.client.module.Module
import geiler.addons.client.module.NumberSetting
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.resources.Identifier
import java.nio.file.Path

/**
 * A REI-style item and recipe viewer for SkyBlock: the NotEnoughUpdates item repository as a searchable grid right of every
 * inventory screen, with recipes, uses, forge, NPC shop, trade, mob drops, Kat and essence views, favorites and a price
 * breakdown. Behavior follows REI/JEI and SkyBlock item viewers (Skyblocker's item list, LGPL-3.0; Enhanced SkyRecipes'
 * search syntax and aliases, GPL-3.0, behavior only); the code is written independently.
 */
class ItemListModule private constructor() : Module(
    "Item List",
    "REI-style SkyBlock item list beside inventories: search, recipes (R), uses (U), favorites (F) and prices. Double-click the search bar to search the open inventory.",
    Category.MISCELLANEOUS,
    false,
    style, priceMode, columns, rows, rarity,
), ItemListPanel.Host {
    private val diagnostics = ModuleDiagnostics(Category.MISCELLANEOUS, { name() }, 8, 100L, 480)
    val panel = ItemListPanel(this)
    private var loadedFavorites = false

    override fun onEnable() {
        PriceDisplayModule.INSTANCE.setItemListDemand(true)
        loadSaved()
        diagnostics.enable("columns=${columns()} rows=${rows()} style=${style.value()} priceMode=${priceMode.value()} rarityBackgrounds=${rarity.value()} sort=${panel.sort} favorites=${panel.favorites.size} open=${panel.open}")
    }

    override fun onDisable() {
        PriceDisplayModule.INSTANCE.setItemListDemand(false)
        diagnostics.event("DISABLE ${panel.diagnosticsLine()}")
        diagnostics.disable()
    }

    fun tick() {
        if (!isEnabled()) return
        ItemListRepository.request()
        diagnostics.advance()
        diagnostics.statusOnChange { "repo=${ItemListRepository.status} stacks=${ItemListRepository.stackSource} columns=${columns()} rows=${rows()} ${panel.diagnosticsLine()}" }
        val failed = panel.stackFailureSample
        if (failed.isNotEmpty()) diagnostics.statusOnChangeBy(600L, { "f${panel.stackFailures}" }) { "STACK_FAILURES count=${panel.stackFailures} sample=$failed" }
    }

    override fun minecraftStyle(): Boolean = InventorySurfaces.isMinecraft(style.value())

    override fun buyInstantly(): Boolean = priceMode.value() == "BUY_ORDER"

    override fun columns(): Int = columns.value().toInt()

    override fun rows(): Int = rows.value().toInt()

    override fun event(text: String) = diagnostics.event(text)

    override fun rarityBackgrounds(): Boolean = rarity.value()

    override fun drawRarity(g: GuiGraphicsExtractor, rarity: SkyblockRarity, x: Int, y: Int) = ItemRarityBackgroundsModule.INSTANCE.drawRarity(g, rarity, x, y)

    override fun saveFavorites(favorites: List<String>, open: Boolean, sort: String) {
        val root = JsonObject()
        root.addProperty("open", open)
        root.addProperty("sort", sort)
        root.add("favorites", JsonArray().also { a -> favorites.forEach { a.add(it) } })
        ClientJsonFile.writeAsync(PATH, root.toString())
    }

    private fun loadSaved() {
        if (loadedFavorites) return
        loadedFavorites = true
        val root = ClientJsonFile.read(PATH, JsonObject::class.java) ?: return
        root.get("open")?.takeIf { it.isJsonPrimitive }?.let { panel.open = it.asBoolean }
        root.get("sort")?.takeIf { it.isJsonPrimitive && it.asString in ItemListData.SORTS }?.let { panel.sort = it.asString }
        root.getAsJsonArray("favorites")?.forEach { if (it.isJsonPrimitive && panel.favorites.size < MAX_FAVORITES) panel.favorites.add(it.asString) }
    }

    private fun active(screen: AbstractContainerScreen<*>) = isEnabled() && !StorageOverlayModule.INSTANCE.ownsScreen(screen)

    /** Hooks every container screen; they do nothing while the module is off or the Storage Overlay owns the screen. */
    fun registerScreenEvents() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*> && screen !is CreativeModeInventoryScreen) {
                // afterForeground, not afterExtract: afterExtract runs after the frame's tooltips were already drawn, so tooltips set
                // there never show. afterForeground runs inside the screen, before its own tooltip pass.
                ScreenEvents.afterForeground(screen).register { _, graphics, mouseX, mouseY, _ ->
                    if (active(screen)) panel.render(screen, graphics, mouseX, mouseY)
                }
                ScreenMouseEvents.allowMouseClick(screen).register { _, event -> !(active(screen) && panel.handleClick(screen, event)) }
                ScreenMouseEvents.allowMouseScroll(screen).register { _, x, y, _, vertical -> !(active(screen) && panel.handleScroll(x, y, vertical)) }
                ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
                    !(active(screen) && panel.handleKey(event, recipeKey, usesKey, favoriteKey))
                }
                ScreenKeyboardEvents.allowCharType(screen).register { _, event -> !(active(screen) && panel.handleChar(event)) }
            }
        }
    }

    companion object {
        private const val MAX_FAVORITES = 200
        private val PATH: Path = FabricLoader.getInstance().configDir.resolve("geileraddons").resolve("item-list.json")
        private val style = InventorySurfaces.styleSetting()
        private val columns = NumberSetting("Columns", 4f, 20f, 11f, true)
        private val rows = NumberSetting("Rows (0 = fill height)", 0f, 30f, 0f, true)
        private val rarity = BooleanSetting("Rarity Backgrounds", true)
        private val priceMode = ChoiceSetting(
            "Craft Cost Prices", "SELL_ORDER",
            mapOf("SELL_ORDER" to "Buy Order", "BUY_ORDER" to "Buy Instantly"),
            "SELL_ORDER", "BUY_ORDER",
        )

        private val CATEGORY: KeyMapping.Category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("geileraddons", "item_list"))

        private fun key(name: String, code: Int) = ClientKeyMappingRegistrar.register(KeyMapping("key.geileraddons.item_list.$name", InputConstants.Type.KEYSYM, code, CATEGORY))

        val recipeKey: KeyMapping = key("recipe", InputConstants.KEY_R)
        val usesKey: KeyMapping = key("uses", InputConstants.KEY_U)
        val favoriteKey: KeyMapping = key("favorite", InputConstants.KEY_F)

        @JvmField
        val INSTANCE = ItemListModule()
    }
}
