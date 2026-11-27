package geiler.addons.client.bazaar;

import java.util.List;

public final class FancyAuctionChecks {
	private FancyAuctionChecks() { }

	public static void run() {
		equal(Boolean.TRUE, AuctionMenus.isBrowser("Auctions Browser"), "browser title");
		equal(Boolean.TRUE, AuctionMenus.isBrowser("Auctions: \"hyperion\""), "search results title");
		equal(Boolean.FALSE, AuctionMenus.isBrowser("Auction House"), "the AH main menu stays vanilla");
		equal(Boolean.TRUE, AuctionMenus.isView("BIN Auction View"), "BIN view");
		equal(Boolean.TRUE, AuctionMenus.isView("Auction View"), "bid view");
		equal(Boolean.TRUE, AuctionMenus.isBin("BIN Auction View"), "is BIN");
		equal(Boolean.FALSE, AuctionMenus.isBin("Auction View"), "not BIN");

		equal(Boolean.TRUE, AuctionMenus.isListingSlot(11, 6), "first listing slot");
		equal(Boolean.TRUE, AuctionMenus.isListingSlot(43, 6), "last listing slot");
		equal(Boolean.FALSE, AuctionMenus.isListingSlot(9, 6), "category column");
		equal(Boolean.FALSE, AuctionMenus.isListingSlot(17, 6), "right border");
		equal(Boolean.FALSE, AuctionMenus.isListingSlot(47, 6), "bottom row");
		equal(24, AuctionMenus.listingSlots(6).size(), "6 by 4 listings");

		List<String> bin = List.of("Seller: [MVP+] Someone", "Buy it now: 1,234,567 coins", "", "Ends in: 2h 3m");
		AuctionMenus.Listing l = AuctionMenus.listing(bin);
		equal(1_234_567L, l.getPrice(), "BIN price");
		equal(Boolean.TRUE, l.getBin(), "BIN");
		equal("2h 3m", l.getEnds(), "ends");
		AuctionMenus.Listing bid = AuctionMenus.listing(List.of("Starting bid: 500 coins", "Ends in: 5m"));
		equal(500L, bid.getPrice(), "starting bid");
		equal(Boolean.FALSE, bid.getBin(), "bid auction");
		equal(900L, AuctionMenus.listing(List.of("Starting bid: 500 coins", "Top bid: 900 coins")).getPrice(), "top bid wins");
		equal(null, AuctionMenus.listing(List.of("nothing")).getPrice(), "no price");

		equal("Highest Price", AuctionMenus.option(List.of("Sort", "  Lowest Price", "▶ Highest Price", "  Ending soon", "", "Click to switch!")), "selected option");
		equal("", AuctionMenus.option(List.of("Click")), "no option");
		equal(Boolean.TRUE, AuctionMenus.selectedCategory(List.of("Examples", "Currently browsing!")), "selected category");
		equal(Boolean.FALSE, AuctionMenus.selectedCategory(List.of("Click to view items!")), "other category");
		equal(3, AuctionMenus.page(List.of("Page 3", "Click to turn page!")), "page number");

		equal(AuctionMenus.ViewAction.BUY, AuctionMenus.viewAction("minecraft:gold_nugget", "Buy Item Right Now"), "afford");
		equal(AuctionMenus.ViewAction.CANT_AFFORD, AuctionMenus.viewAction("minecraft:poisonous_potato", "Buy Item Right Now"), "too poor");
		equal(AuctionMenus.ViewAction.TOP_BID, AuctionMenus.viewAction("minecraft:gold_block", "Submit Bid"), "top bid");
		equal(AuctionMenus.ViewAction.CANCEL, AuctionMenus.viewAction("minecraft:red_terracotta", "Cancel Auction"), "cancel");
		equal(AuctionMenus.ViewAction.COLLECT, AuctionMenus.viewAction("minecraft:gold_nugget", "Collect Auction"), "collect");
		equal(AuctionMenus.ViewAction.GRACE, AuctionMenus.viewAction("minecraft:red_bed", "Buy Item Right Now"), "grace period");
		equal(null, AuctionMenus.viewAction("minecraft:black_stained_glass_pane", " "), "glass is nothing");

		equal(Boolean.TRUE, BazaarMenus.isBazaar("Bazaar ➜ Farming", List.of()), "category page");
		equal(Boolean.TRUE, BazaarMenus.isBazaar("Wheat ➜ Enchanted Bread", List.of("Buy Instantly", "Sell Instantly")), "product page");
		equal(Boolean.FALSE, BazaarMenus.isBazaar("Enchanted Bread ➜ Instant Buy", List.of("Buy only one!")), "amount pages stay vanilla");
		equal(Boolean.FALSE, BazaarMenus.isBazaar("Confirm Instant Buy", List.of()), "confirm stays vanilla");
		equal(List.of("Buy price: 5.2 coins", "Sell price: 4.9 coins"),
			BazaarMenus.priceLines(List.of("Enchanted Bread", "", "Buy price: 5.2 coins", "Sell price: 4.9 coins", "", "Click to view details!")), "price lines");
		equal(Boolean.TRUE, BazaarMenus.isFiller("minecraft:black_stained_glass_pane", " "), "glass pane is filler");
		equal(Boolean.FALSE, BazaarMenus.isFiller("minecraft:wheat", "Wheat"), "item is not filler");
		equal("5.9h", AuctionMenus.shortTime("05h 55m 12s"), "hours as one decimal");
		equal("13d", AuctionMenus.shortTime("13d"), "whole days stay whole");
		equal("1.5d", AuctionMenus.shortTime("1d 12h"), "days with a decimal");
		equal("45s", AuctionMenus.shortTime("45s"), "seconds stay seconds");
		equal("Ended", AuctionMenus.shortTime("Ended"), "text without times is unchanged");
		equal(new kotlin.Pair<>(2, 57), AuctionMenus.pageOf(List.of("Page 3", "(2/57)")), "page out of total");
		equal(null, AuctionMenus.pageOf(List.of("Page 3", "Click to turn the page!")), "no total in the lore");
		equal(Boolean.TRUE, BazaarMenus.isGeneric("Co-op Bazaar Orders", null, false), "orders page is skinned");
		equal(Boolean.TRUE, BazaarMenus.isGeneric("Oddities ➜ Modifiers", "To Bazaar", false), "group page leading back to the Bazaar");
		equal(Boolean.TRUE, BazaarMenus.isGeneric("Are you sure?", null, true), "confirm right after an AH or Bazaar menu");
		equal(Boolean.FALSE, BazaarMenus.isGeneric("Are you sure?", null, false), "confirm elsewhere stays vanilla");
		equal(Boolean.FALSE, BazaarMenus.isGeneric("SkyBlock Menu", "To SkyBlock Menu", true), "other menus stay vanilla");
		equal(geiler.addons.client.gui.FancySignOverlay.Kind.AMOUNT,
			geiler.addons.client.gui.FancySignOverlay.kind(List.of("^^^^^^^^^^^^^^^", "Enter amount", "to order")), "Bazaar amount sign");
		equal(geiler.addons.client.gui.FancySignOverlay.Kind.SEARCH,
			geiler.addons.client.gui.FancySignOverlay.kind(List.of("^^^^^^^^^^^^^^^", "Enter query", "")), "AH search sign");
		equal(null, geiler.addons.client.gui.FancySignOverlay.kind(List.of("", "", "")), "a plain sign is left alone");
	}

	private static void equal(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
	}
}
