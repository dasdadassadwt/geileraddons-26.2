package geiler.addons.client.module.impl;

import com.mojang.authlib.GameProfile;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import net.minecraft.core.ClientAsset;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/** Offline checks for the exact fake-player branch used by Hideyho Finder. */
public final class HideyhoChecks {
	private HideyhoChecks() {
	}

	public static void run() {
		checkIdentityAndSettings();
		checkDetectionBehavior();
	}

	private static void checkIdentityAndSettings() {
		HideyhoFinderModule module = HideyhoFinderModule.INSTANCE;
		if (module != HideyhoFinderModule.INSTANCE || !module.name().equals("Hideyho Finder")
			|| !module.configName().equals("Hideyho Finder")
			|| !module.description().equals("Finds the Hideyho in the Critter Safari.")
			|| module.category() != Category.HUNTING || module.defaultEnabled() || module.isEnabled()) {
			throw new AssertionError("Hideyho Finder identity or fresh-install state changed");
		}

		List<ColorSetting> colors = module.colorSettings();
		if (colors.size() != 1 || !colors.get(0).name().equals("Color")
			|| colors.get(0).red() != 255 || colors.get(0).green() != 0 || colors.get(0).blue() != 255
			|| colors.get(0).alpha() != 83 || !colors.get(0).alphaEditable()) {
			throw new AssertionError("Hideyho Finder color setting ID/default changed");
		}

		List<NumberSetting> numbers = module.numberSettings();
		if (numbers.size() != 1 || !numbers.get(0).name().equals("Scan Interval")
			|| !close(numbers.get(0).min(), 1.0f) || !close(numbers.get(0).max(), 200.0f)
			|| !close(numbers.get(0).value(), 20.0f) || !numbers.get(0).isInteger()) {
			throw new AssertionError("Hideyho Finder scan interval ID/bounds/default changed");
		}

		List<BooleanSetting> booleans = module.booleanSettings();
		if (booleans.size() != 1 || !booleans.get(0).name().equals("Debug Mode")
			|| booleans.get(0).rawValue() || !booleans.get(0).isDebugOnly()) {
			throw new AssertionError("Hideyho Finder debug setting ID/default/visibility changed");
		}
	}

	private static void checkDetectionBehavior() {
		assertTrue(HideyhoFinderModule.isDirectHideyhoMatch(allocate(LowercaseClientPlayer.class)),
			"case-insensitive name fallback still uses direct body resolution");
		assertTrue(HideyhoFinderModule.isDirectHideyhoMatch(allocate(MixedCaseClientPlayer.class)),
			"mixed-case name fallback still uses direct body resolution");
		assertFalse(HideyhoFinderModule.isDirectHideyhoMatch(allocate(OtherClientPlayer.class)),
			"unrelated fake players do not match");
		assertTrue(HideyhoFinderModule.hasHideyhoTextureHash(
			"https://textures.minecraft.net/texture/3504f1f2327a5110e643bb8667082512815fa434a29ed37f4ca83bb16d2db533"),
			"the exact Hideyho skin URL texture hash matches");
		assertFalse(HideyhoFinderModule.hasHideyhoTextureHash(
			"https://textures.minecraft.net/texture/3504f1f2327a5110e643bb8667082512815fa434a29ed37f4ca83bb16d2db532"),
			"a different skin URL texture hash does not match");
	}

	private abstract static class TestClientPlayer extends AbstractClientPlayer {
		private TestClientPlayer(String profileName) {
			super(null, new GameProfile(UUID.randomUUID(), profileName));
		}

		@Override
		public PlayerSkin getSkin() {
			return new PlayerSkin(
				new ClientAsset.ResourceTexture(Identifier.parse("geileraddons:hideyho_check")),
				null,
				null,
				PlayerModelType.WIDE,
				false);
		}

		@Override
		public boolean isAlive() {
			return true;
		}

		@Override
		public HumanoidArm getMainArm() {
			return HumanoidArm.RIGHT;
		}
	}

	private static final class LowercaseClientPlayer extends TestClientPlayer {
		private LowercaseClientPlayer() {
			super("hideyho");
		}

		@Override
		public Component getName() {
			return Component.literal("hideyho");
		}
	}

	private static final class MixedCaseClientPlayer extends TestClientPlayer {
		private MixedCaseClientPlayer() {
			super("HideYhO");
		}

		@Override
		public Component getName() {
			return Component.literal("HideYhO");
		}
	}

	private static final class OtherClientPlayer extends TestClientPlayer {
		private OtherClientPlayer() {
			super("Rockmite");
		}

		@Override
		public Component getName() {
			return Component.literal("Rockmite");
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) {
		try {
			Field field = Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			return (T) ((Unsafe) field.get(null)).allocateInstance(type);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not create an offline fake player fixture", e);
		}
	}

	private static void assertTrue(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}

	private static void assertFalse(boolean value, String message) {
		if (value) throw new AssertionError(message);
	}

	private static boolean close(float actual, float expected) {
		return Math.abs(actual - expected) < 0.0001f;
	}
}
