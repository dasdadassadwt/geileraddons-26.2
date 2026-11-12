package geiler.addons.client.chat;

import geiler.addons.client.dungeon.ClientJsonFile;
import geiler.addons.client.module.impl.ChatModule;

/** Focused pure checks for native chat sizing, viewport clamps, and legacy layout persistence. */
public final class ChatWindowLayoutChecks {
	private ChatWindowLayoutChecks() { }

	public static void run() {
		legacyLayoutJavaRecordAndConstantsRemainStable();
		equals(0, ChatModule.INSTANCE.extraWidth(), "extra width defaults to vanilla size");
		equals(0, ChatModule.INSTANCE.extraFocusedHeight(), "extra focused height defaults to vanilla size");
		equals(0, ChatModule.INSTANCE.extraUnfocusedHeight(), "extra unfocused height defaults to vanilla size");
		check(ChatModule.INSTANCE.copyOnRightClick(), "right-click message copying defaults on");

		equals(320, ChatNativeSizeRules.effectiveWidth(320, 0, 1_920),
			"zero extra width preserves vanilla's current width");
		equals(480, ChatNativeSizeRules.effectiveWidth(320, 160, 1_920),
			"extra width adds scaled GUI pixels to the vanilla width");
		equals(500, ChatNativeSizeRules.effectiveWidth(480, 100, 500),
			"effective width clamps to the scaled viewport");
		equals(312, ChatNativeSizeRules.availableWidth(320, 1.0),
			"native width viewport reserves vanilla's background insets at unit scale");
		equals(304, ChatNativeSizeRules.availableWidth(320, 2.0),
			"native width viewport scales vanilla's background insets");
		equals(320, ChatNativeSizeRules.effectiveHeight(180, 140, 1_080, 1.0),
			"extra focused height is added at unit chat scale");
		equals(160, ChatNativeSizeRules.effectiveHeight(120, 40, 1_080, 1.0),
			"extra unfocused height is calculated independently");
		equals(200, ChatNativeSizeRules.effectiveHeight(180, 40, 500, 2.0),
			"extra height is measured in GUI pixels through chat scale");
		equals(125, ChatNativeSizeRules.effectiveHeight(180, 100, 250, 2.0),
			"effective height clamps to the scaled viewport");
		equals(160, ChatNativeSizeRules.migrateLegacyWidthExtra(480, 320),
			"legacy width migrates to the amount above vanilla");
		equals(140, ChatNativeSizeRules.migrateLegacyHeightExtra(320, 180, 1.0),
			"legacy height migrates to the amount above focused vanilla height");
		equals(0, ChatNativeSizeRules.migrateLegacyWidthExtra(300, 320),
			"a legacy size below vanilla does not become a negative additive setting");
		equals(ChatNativeSizeRules.MAX_EXTRA_PIXELS, ChatNativeSizeRules.clampExtra(20_000),
			"extra dimensions stay under their setting ceiling");
		equals(0, ChatNativeSizeRules.clampExtra(-5), "extra dimensions cannot become negative");
		equals(400, ChatNativeSizeRules.scaledHeight(200, 2.0), "logical chat height scales into GUI pixels");
		equals(0, ChatNativeSizeRules.scaledHeight(-5, 2.0), "negative logical height stays at zero");
		equals(140, ChatNativeSizeRules.migrateLegacyHeightExtra(320, 180, Double.NaN),
			"invalid chat scale uses vanilla's safe unit scale during migration");
		equals(9, ChatNativeCopyRules.lineIndexFromBottom(10, 0, 0),
			"right-click on the bottom row selects the newest native chat line");
		equals(8, ChatNativeCopyRules.lineIndexFromBottom(10, 0, 1),
			"right-click one row higher selects the preceding native chat line");
		equals(7, ChatNativeCopyRules.lineIndexFromBottom(10, 2, 0),
			"native chat copy maps rows correctly while scrolled");
		equals(-1, ChatNativeCopyRules.lineIndexFromBottom(10, 0, 10),
			"copy hit testing rejects a row below the visible native history");

		ChatChannelsLocalStore.Data data = new ChatChannelsLocalStore.Data();
		data.unfocusedChatHudEnabled = true;
		data.focusedChatX = 42;
		data.focusedChatY = 84;
		data.chatWindowWidth = 440;
		data.chatWindowHeight = 280;
		String json = ClientJsonFile.GSON.toJson(data);
		ChatChannelsLocalStore.Data restored = ClientJsonFile.GSON.fromJson(json, ChatChannelsLocalStore.Data.class);
		ChatWindowLayoutRules.Settings restoredSettings = new ChatWindowLayoutRules.Settings(
			Boolean.TRUE.equals(restored.unfocusedChatHudEnabled), restored.focusedChatX, restored.focusedChatY,
			restored.chatWindowWidth, restored.chatWindowHeight);
		check(restoredSettings.hudEnabled(), "legacy HUD preference survives for one-time migration");
		equals(42, restoredSettings.focusedX(), "legacy position remains available to old config readers");
		equals(84, restoredSettings.focusedY(), "legacy position is independent of migrated size");
		equals(440, restoredSettings.width(), "legacy width survives the local settings JSON round-trip");
		equals(280, restoredSettings.height(), "legacy height survives the local settings JSON round-trip");
	}

	private static void legacyLayoutJavaRecordAndConstantsRemainStable() {
		Class<ChatWindowLayoutRules.Settings> settings = ChatWindowLayoutRules.Settings.class;
		check(settings.isRecord(), "legacy layout settings retain their Java record ABI");
		equals(5, settings.getRecordComponents().length, "legacy layout record component count");
		equals("hudEnabled", settings.getRecordComponents()[0].getName(), "legacy HUD component name and order");
		equals("focusedX", settings.getRecordComponents()[1].getName(), "legacy x component name and order");
		equals("focusedY", settings.getRecordComponents()[2].getName(), "legacy y component name and order");
		equals("width", settings.getRecordComponents()[3].getName(), "legacy width component name and order");
		equals("height", settings.getRecordComponents()[4].getName(), "legacy height component name and order");
		equals(Integer.class, settings.getRecordComponents()[1].getType(), "legacy x position stays nullable");
		equals(Integer.class, settings.getRecordComponents()[2].getType(), "legacy y position stays nullable");
		equals(Integer.class, settings.getRecordComponents()[3].getType(), "legacy width stays nullable");
		equals(Integer.class, settings.getRecordComponents()[4].getType(), "legacy height stays nullable");
		equals(300, ChatWindowLayoutRules.MIN_WIDTH, "legacy minimum width remains public");
		equals(150, ChatWindowLayoutRules.MIN_HEIGHT, "legacy minimum height remains public");
		equals(760, ChatWindowLayoutRules.MAX_WIDTH, "legacy maximum width remains public");
		equals(560, ChatWindowLayoutRules.MAX_HEIGHT, "legacy maximum height remains public");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (expected == null ? actual != null : !expected.equals(actual))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
