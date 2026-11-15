package geiler.addons.client.module.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

/** Offline checks for Compact Damage: the number rules mirror Skyblocker's CompactDamageTest vectors. */
public final class CompactDamageChecks {
	private CompactDamageChecks() { }

	public static void main(String[] args) {
		run();
	}

	public static void run() {
		checkDigits();
		checkFormatToPrecision();
		checkPrettify();
		checkNames();
		checkOkLab();
	}

	private static void checkDigits() {
		require(CompactDamageLogic.baseTenDigits(4) == 1 && CompactDamageLogic.baseTenDigits(68) == 2
			&& CompactDamageLogic.baseTenDigits(99) == 2 && CompactDamageLogic.baseTenDigits(100) == 3
			&& CompactDamageLogic.baseTenDigits(99_999_999) == 8 && CompactDamageLogic.baseTenDigits(100_000_000) == 9
			&& CompactDamageLogic.baseTenDigits(0) == 0, "base ten digit counts");
	}

	private static void checkFormatToPrecision() {
		eq("103.6", CompactDamageLogic.formatToPrecision(103.632d, 4));
		eq("103.600", CompactDamageLogic.formatToPrecision(103.6001d, 6));
		eq("9000.001", CompactDamageLogic.formatToPrecision(9000.00149d, 7));
		eq("9000", CompactDamageLogic.formatToPrecision(9000.00149d, 3));
		eq("9000", CompactDamageLogic.formatToPrecision(9001d, 3));
		eq("9001", CompactDamageLogic.formatToPrecision(9001d, 4));
	}

	private static void checkPrettify() {
		eq("999", CompactDamageLogic.prettifyDamageNumber(999, 9));
		eq("100", CompactDamageLogic.prettifyDamageNumber(95, 1));
		eq("95", CompactDamageLogic.prettifyDamageNumber(95, 2));
		eq("300", CompactDamageLogic.prettifyDamageNumber(253, 1));
		eq("1.0k", CompactDamageLogic.prettifyDamageNumber(996, 2));
		eq("68.68k", CompactDamageLogic.prettifyDamageNumber(68_682, 4));
		eq("1.00M", CompactDamageLogic.prettifyDamageNumber(999_999, 3));
		eq("999.999k", CompactDamageLogic.prettifyDamageNumber(999_999, 7));
		eq("999.999k", CompactDamageLogic.prettifyDamageNumber(999_999, 1000));
		eq("99.999999M", CompactDamageLogic.prettifyDamageNumber(99_999_999, 1000));
		eq("100.0000M", CompactDamageLogic.prettifyDamageNumber(99_999_999, 7));
		eq("3.825k", CompactDamageLogic.prettifyDamageNumber(3825, 4));
		eq("10.0k", CompactDamageLogic.prettifyDamageNumber(9995, 3));
		eq("179.482B", CompactDamageLogic.prettifyDamageNumber(179_481_824_995L, 6));
		eq("35.5189T", CompactDamageLogic.prettifyDamageNumber(35_518_885_661_733L, 6));
		eq("180.000000B", CompactDamageLogic.prettifyDamageNumber(179_999_999_995L, 9));
	}

	private static MutableComponent input(String damage, boolean crit, String symbol) {
		MutableComponent text = Component.empty();
		if (crit) {
			for (char c : ("✧" + damage + "✧").toCharArray()) text.append(String.valueOf(c));
		} else {
			text.append(damage).withStyle(style -> style.withColor(ChatFormatting.GRAY));
		}
		if (symbol != null) text.append(Component.literal(symbol).withStyle(ChatFormatting.GOLD));
		return text;
	}

	private static void checkNames() {
		int white = 0xFFFFFF;
		Component normal = CompactDamageLogic.compact(input("3,825", false, null), 4, white, 0xFFFF55, 0xFF5555);
		require(normal != null && normal.getString().equals("3.825k") && normal.getStyle().getColor().getValue() == white,
			"normal hit is shortened and takes the normal color");
		Component crit = CompactDamageLogic.compact(input("7,214", true, null), 4, white, 0, 0);
		require(crit != null && crit.getString().equals("✧7.214k✧"), "crit keeps its symbols");
		for (Component part : crit.getSiblings()) require(part.getStyle().getColor().getValue() == 0, "black gradient is black");
		Component withSymbol = CompactDamageLogic.compact(input("133,972", false, "⚔"), 3, white, 0, 0);
		int gold = TextColor.fromLegacyFormat(ChatFormatting.GOLD).getValue();
		require(withSymbol != null && withSymbol.getString().equals("134k⚔")
			&& withSymbol.getSiblings().get(0).getStyle().getColor().getValue() == gold,
			"a trailing symbol is appended with its own style");
		require(CompactDamageLogic.compact(Component.literal("Zombie"), 4, white, 0, 0) == null, "other names are untouched");
		require(CompactDamageLogic.compact(Component.literal("1,234"), 4, white, 0, 0) == null,
			"a name without styled siblings is not rewritten");
	}

	private static void checkOkLab() {
		require(OkLab.interpolate(0xFFFF55, 0xFF5555, 0f) == 0xFFFF55 && OkLab.interpolate(0xFFFF55, 0xFF5555, 1f) == 0xFF5555,
			"gradient endpoints round trip");
		int mid = OkLab.interpolate(0x000000, 0xFFFFFF, 0.5f);
		int r = mid >> 16 & 0xFF;
		require(r >= 97 && r <= 101 && (mid & 0xFF) == r, "OkLab midpoint of black and white is 0x63 (perceptual half lightness): " + Integer.toHexString(mid));
	}

	private static void eq(String expected, String actual) {
		if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
