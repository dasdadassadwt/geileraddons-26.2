package geiler.addons.client.module.impl;

import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Standalone parity checks for Safari Floor Drops identity, settings, and deterministic rules. */
public final class SafariFloorDropsChecks {
	private SafariFloorDropsChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		checkIdentityAndSettings();
		checkParticleAndDisplayRules();
		checkLifetimeAndReconfirmation();
		checkIslandAndClickRules();
		System.out.println("Safari Floor Drops checks passed");
	}

	private static void checkIdentityAndSettings() {
		SafariFloorDropsModule module = SafariFloorDropsModule.INSTANCE;
		if (module != SafariFloorDropsModule.INSTANCE
			|| !module.name().equals("Safari Floor Drops")
			|| !module.configName().equals("Safari Floor Drops")
			|| !module.description().equals("Highlights floor drops in the Critter Safari.")
			|| module.category() != Category.HUNTING
			|| module.defaultEnabled()) {
			throw new AssertionError("Safari Floor Drops identity or fresh-install state changed");
		}

		List<NumberSetting> numbers = module.numberSettings();
		if (numbers.size() != 1) throw new AssertionError("Expected exactly one number setting");
		NumberSetting scanDelay = numbers.get(0);
		if (!scanDelay.name().equals("Scan Delay") || !close(scanDelay.min(), 1.0f)
			|| !close(scanDelay.max(), 200.0f) || !close(scanDelay.value(), 20.0f) || !scanDelay.isInteger()) {
			throw new AssertionError("Scan Delay setting name, range, default, or integer mode changed");
		}

		List<ColorSetting> colors = module.colorSettings();
		if (colors.size() != 1) throw new AssertionError("Expected exactly one color setting");
		ColorSetting color = colors.get(0);
		if (!color.name().equals("Color") || color.red() != 255 || color.green() != 0
			|| color.blue() != 0 || color.alpha() != 107 || color.argb() != 0x6BFF0000) {
			throw new AssertionError("Color setting identity or default changed");
		}

		List<BooleanSetting> booleans = module.booleanSettings();
		if (booleans.size() != 1) throw new AssertionError("Expected exactly one boolean setting");
		BooleanSetting depth = booleans.get(0);
		if (!depth.name().equals("Depth Check") || depth.rawValue() || depth.value() || depth.isDebugOnly()) {
			throw new AssertionError("Depth Check identity, default, or visibility changed");
		}
	}

	private static void checkParticleAndDisplayRules() {
		BlockPos marker = SafariFloorDropsRules.markerPosition(1.9, 22.4, -0.1);
		if (!marker.equals(new BlockPos(1, 21, -1))) {
			throw new AssertionError("Particle marker must floor its position and move down one block: " + marker);
		}
		if (SafariFloorDropsRules.REQUIRED_STRING_DISPLAYS != 3
			|| !SafariFloorDropsRules.isConfirmedStringDisplayCount(3)) {
			throw new AssertionError("Exactly three string displays must confirm a floor drop");
		}
		for (int count : new int[]{-1, 0, 1, 2, 4, 5}) {
			if (SafariFloorDropsRules.isConfirmedStringDisplayCount(count)) {
				throw new AssertionError("Unexpected string display count confirmed a floor drop: " + count);
			}
		}
	}

	private static void checkLifetimeAndReconfirmation() {
		if (SafariFloorDropsRules.LIFETIME_MILLIS != 5_000L
			|| SafariFloorDropsRules.RECONFIRM_MILLIS != 2_000L) {
			throw new AssertionError("Floor drop timing constants changed");
		}
		if (SafariFloorDropsRules.shouldReconfirm(1_999L, 0L)
			|| !SafariFloorDropsRules.shouldReconfirm(2_000L, 0L)) {
			throw new AssertionError("Reconfirmation must begin at two seconds");
		}
		if (SafariFloorDropsRules.isExpired(4_999L, 0L)
			|| !SafariFloorDropsRules.isExpired(5_000L, 0L)) {
			throw new AssertionError("Unconfirmed floor drops must expire at five seconds");
		}
	}

	private static void checkIslandAndClickRules() {
		if (!SafariFloorDropsRules.isActive(true, true)
			|| SafariFloorDropsRules.isActive(false, true)
			|| SafariFloorDropsRules.isActive(true, false)) {
			throw new AssertionError("The module must require both enabled state and the Safari island");
		}

		BlockPos marker = new BlockPos(5, 6, 7);
		Map<BlockPos, Long> nodes = new HashMap<>();
		if (!SafariFloorDropsRules.rememberIfAbsent(nodes, marker, 100L)
			|| SafariFloorDropsRules.rememberIfAbsent(nodes, marker, 200L)
			|| !Long.valueOf(100L).equals(nodes.get(marker))) {
			throw new AssertionError("Duplicate particle confirmation must not restart the marker lifetime");
		}
		if (!SafariFloorDropsRules.removeNode(nodes, marker)
			|| SafariFloorDropsRules.removeNode(nodes, marker)
			|| SafariFloorDropsRules.removeNode(nodes, null)) {
			throw new AssertionError("Click removal must remove a marker once and ignore missing targets");
		}
	}

	private static boolean close(float left, float right) {
		return Math.abs(left - right) < 0.0001f;
	}
}
