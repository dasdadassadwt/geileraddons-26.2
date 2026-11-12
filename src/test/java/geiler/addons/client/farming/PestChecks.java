package geiler.addons.client.farming;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import geiler.addons.client.module.BooleanSetting;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.ColorSetting;
import geiler.addons.client.module.NumberSetting;
import geiler.addons.client.module.impl.PestHighlighterModule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Offline checks for the pest roster, animation boundaries and user-facing defaults. */
public final class PestChecks {
	private static final String[] EXPECTED_ENUM_NAMES = {
		"BEETLE", "CRICKET", "DRAGONFLY", "EARTHWORM", "FIELD_MOUSE", "FIREFLY", "FLY", "LOCUST",
		"LUNAR_MOTH", "MITE", "MOSQUITO", "MOTH", "PRAYING_MANTIS", "RAT", "SLUG"
	};
	private static final String[] EXPECTED_DISPLAY_NAMES = {
		"Beetle", "Cricket", "Dragonfly", "Earthworm", "Field Mouse", "Firefly", "Fly", "Locust",
		"Lunar Moth", "Mite", "Mosquito", "Moth", "Praying Mantis", "Rat", "Slug"
	};
	private static final int[] EXPECTED_TEXTURE_COUNTS = {1, 1, 1, 2, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1};
	private static final String[] EXPECTED_TEXTURE_SHA256 = {
		"feb3a834baf781f93b88cb8e9d288c170c33057d4a07938d0b90ae433f9387fd",
		"802a9e374ed4e4fa45dc87cf1ed937289e47b5f3988c41cb8994c046b9966ea7",
		"1bc1a5a281d3b1747fe3dd21416852518f27fd138733053916c363c25d371684",
		"0d962b0fb4440e8307fc211a5f1c91f2fc896d0394c51bf9ed34f2c7f8551a69",
		"49105077050f4733f43ecb5080fcf7fba2ad83b0da11abdc472e45ab94b7b733",
		"24428893b5dc6e4f1f54f51185bd0f93518a7fcaf7dde7109b63b6badadb574d",
		"1b5797457ff7f86d88a26641000ea0d127d4a72ea5f1450701057e1d02bbf2db",
		"21f7dbdd7f2643d7d96c13d743fcc53a22645fbd29b3d6852162682e15d7bdf7",
		"b19750ee59d84da041b16cee92a8ad38c9c8ece8113c598723a15f2181055a34",
		"1fbce9db52dcc7ff0c1286612ce7f840529fd72e44bb7cf388c15bad9bb81cf0",
		"74ba88b1e5a24f581d4c2b0f1ea5d9476b01e815efb2e036fa84d5e9da7e6a3e",
		"923d1283ae2623e06b98cf9888e6830bd544994835db6801462df827fac9a1d2",
		"681e9573f230f265c882d6b34c06a9ecf1df7db4fdd4dd73fb3603348e4827f0",
		"925c5852cbbac84e7c10bd9ffac5948264c44b04a515e1c30068058523570507",
		"79a893c72c85aba8ade3e167f238b9b25ce9eb4ae533187c8043dfc195ac87c6"
	};

	private PestChecks() {
	}

	public static void run() {
		checkTextureRoster();
		checkJavaAbi();
		checkSpawnParserAndAbi();
		checkHeadProfiles();
		checkDetectionCache();
		checkAnimation();
		checkSettings();
	}

	private static void checkTextureRoster() {
		PestKind[] kinds = PestKind.values();
		assertEquals(EXPECTED_ENUM_NAMES.length, kinds.length, "the pest enum roster size is unchanged");
		int rawTextureCount = 0;
		for (int i = 0; i < kinds.length; i++) {
			PestKind kind = kinds[i];
			assertEquals(EXPECTED_ENUM_NAMES[i], kind.name(), "enum declaration order is unchanged");
			assertEquals(EXPECTED_DISPLAY_NAMES[i], kind.displayName(), "pest display name is unchanged");
			List<String> textures = kind.textures();
			assertEquals(EXPECTED_TEXTURE_COUNTS[i], textures.size(), "raw texture alias count is unchanged");
			assertEquals(EXPECTED_TEXTURE_SHA256[i], sha256(String.join("\0", textures)),
				"raw texture payload bytes are unchanged for " + kind.name());
			for (String texture : textures) {
				assertTrue(PestKind.knownTextures().contains(texture), "known texture set includes " + kind.name());
				assertSame(kind, PestKind.fromTexture(texture).orElse(null),
					"exact texture lookup resolves to " + kind.name());
				rawTextureCount++;
			}
		}
		assertEquals(17, rawTextureCount, "the roster contains all 17 raw texture variants");
		assertEquals(rawTextureCount, PestKind.textureCount(), "textureCount reports raw texture variants");
		assertEquals(rawTextureCount, PestKind.knownTextures().size(), "lookup keys contain each raw texture once");
		assertSame(PestKind.EARTHWORM,
			PestKind.fromTexture(PestKind.EARTHWORM.textures().get(1)).orElseThrow(),
			"Earthworm tail resolves to Earthworm");
		assertSame(PestKind.FIREFLY,
			PestKind.fromTexture(PestKind.FIREFLY.textures().get(1)).orElseThrow(),
			"Firefly flash resolves to Firefly");
		assertFalse(PestKind.fromTexture(null).isPresent(), "null texture is unavailable");
		assertFalse(PestKind.fromTexture("").isPresent(), "empty texture is unavailable");
		assertFalse(PestKind.fromTexture(" ").isPresent(), "blank texture is unavailable");
		assertFalse(PestKind.fromTexture(" \t\n").isPresent(), "whitespace-only texture is unavailable");
		assertFalse(PestKind.fromTexture("unknown-texture").isPresent(), "unknown texture is ignored");
		String flyTexture = PestKind.FLY.textures().getFirst();
		assertFalse(PestKind.fromTexture(flyTexture.substring(0, flyTexture.length() - 1)).isPresent(),
			"prefix-only texture matches are rejected");
		assertFalse(PestKind.fromTexture(flyTexture + "x").isPresent(), "extended texture values are rejected");
		try {
			PestKind.knownTextures().clear();
			throw new AssertionError("known texture set must be immutable");
		} catch (UnsupportedOperationException expected) {
			// Map.copyOf exposes an immutable key set.
		}
		try {
			PestKind.EARTHWORM.textures().clear();
			throw new AssertionError("per-kind texture lists must be immutable");
		} catch (UnsupportedOperationException expected) {
			// List.of exposes immutable texture lists.
		}
	}

	private static void checkJavaAbi() {
		assertTrue(PestKind.class.isEnum(), "PestKind remains a Java-visible enum");
		assertMethod("values", PestKind[].class, true);
		assertMethod("valueOf", PestKind.class, true, String.class);
		assertMethod("displayName", String.class, false);
		assertMethod("fromTexture", Optional.class, true, String.class);
		assertMethod("textureCount", int.class, true);
		assertMethod("knownTextures", Set.class, true);
		assertMethod("textures", List.class, false);
		for (String name : EXPECTED_ENUM_NAMES) {
			try {
				Field field = PestKind.class.getField(name);
				int modifiers = field.getModifiers();
				assertSame(PestKind.class, field.getType(), "enum constant field type is unchanged");
				assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
					"enum constant remains public static final: " + name);
			} catch (NoSuchFieldException exception) {
				throw new AssertionError("missing enum constant field " + name, exception);
			}
		}
		try {
			Constructor<PestKind> constructor = PestKind.class.getDeclaredConstructor(
				String.class, int.class, String.class, String[].class);
			assertTrue(Modifier.isPrivate(constructor.getModifiers()), "enum constructor remains private");
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("enum constructor descriptor is unchanged", exception);
		}
	}

	private static void assertMethod(String name, Class<?> returnType, boolean isStatic, Class<?>... parameters) {
		try {
			Method method = PestKind.class.getDeclaredMethod(name, parameters);
			assertSame(returnType, method.getReturnType(), "Java method return descriptor is unchanged: " + name);
			int modifiers = method.getModifiers();
			assertTrue(Modifier.isPublic(modifiers), "Java method remains accessible: " + name);
			assertEquals(isStatic, Modifier.isStatic(modifiers), "Java method staticness is unchanged: " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("missing Java method descriptor: " + name, exception);
		}
	}

	private static void checkSpawnParserAndAbi() {
		assertParserMethod("parseSpawn", Optional.class, String.class);
		assertParserMethod("parseCleanPlot", Optional.class, String.class);
		assertParserMethod("isPestKill", boolean.class, String.class);
		assertFalse(PestSpawnChatParser.Spawn.class.isRecord(), "Spawn is now a Kotlin value class");
		try {
			Constructor<PestSpawnChatParser.Spawn> constructor = PestSpawnChatParser.Spawn.class.getConstructor(
				int.class, List.class, boolean.class);
			assertTrue(Modifier.isPublic(constructor.getModifiers()), "Spawn keeps its public Java constructor");
			assertEquals(int.class, PestSpawnChatParser.Spawn.class.getMethod("amount").getReturnType(),
				"Spawn keeps its amount accessor");
			assertEquals(List.class, PestSpawnChatParser.Spawn.class.getMethod("plotNames").getReturnType(),
				"Spawn keeps its plot-list accessor");
			assertEquals(boolean.class, PestSpawnChatParser.Spawn.class.getMethod("whileOffline").getReturnType(),
				"Spawn keeps its offline accessor");
		} catch (ReflectiveOperationException missing) {
			throw new AssertionError("Spawn Java value API changed", missing);
		}

		PestSpawnChatParser.Spawn single = PestSpawnChatParser.parseSpawn(
			"§aGROSS! A Pest has appeared in Plot - East Plot!").orElseThrow();
		assertEquals(1, single.knownAmount(), "single spawn amount remains one after chat formatting is stripped");
		assertEquals(List.of("East Plot"), single.plotNames(), "single spawn keeps the named plot");
		PestSpawnChatParser.Spawn barn = PestSpawnChatParser.parseSpawn(
			"GROSS! a pest has appeared in plot - the barn!").orElseThrow();
		assertEquals(List.of("The Barn"), barn.plotNames(), "the special Barn plot keeps its canonical spelling");

		PestSpawnChatParser.Spawn multiple = PestSpawnChatParser.parseSpawn(
			"YUCK! 12 Pests have spawned in Plot - West Plot!").orElseThrow();
		assertEquals(12, multiple.knownAmount(), "two-digit spawn counts are retained");
		assertFalse(PestSpawnChatParser.parseSpawn(
			"YUCK! 100 Pests have spawned in Plot - West Plot!").isPresent(),
			"counts outside the original two-digit grammar are rejected");
		PestSpawnChatParser.Spawn offline = PestSpawnChatParser.parseSpawn(
			"GROSS! While you were offline, Pests spawned in Plots Plot - East Plot, West Plot and The Barn!")
			.orElseThrow();
		assertTrue(offline.whileOffline(), "offline spawn keeps its offline marker");
		assertEquals(0, offline.knownAmount(), "offline spawn amount remains unknown");
		assertEquals(List.of("East Plot", "West Plot", "The Barn"), offline.plotNames(),
			"offline plot separators and Plot prefixes preserve order");
		PestSpawnChatParser.Spawn controlAfterPrefix = PestSpawnChatParser.parseSpawn(
			"GROSS! While you were offline, Pests spawned in Plots Plot - " + (char) 1 + "East Plot!")
			.orElseThrow();
		assertEquals(List.of("East Plot"), controlAfterPrefix.plotNames(),
			"prefixed offline plot names retain Java String.trim handling after the prefix");
		PestSpawnChatParser.Spawn duplicates = PestSpawnChatParser.parseSpawn(
			"GROSS! While you were offline, Pests spawned in Plots East Plot, East Plot!").orElseThrow();
		assertEquals(List.of("East Plot", "East Plot"), duplicates.plotNames(),
			"the parser preserves duplicate plot mentions from the message");
		assertFalse(PestSpawnChatParser.parseSpawn(
			"GROSS! A Pest has appeared in Plot - Invalid! Plot!").isPresent(),
			"unsafe plot punctuation is rejected");

		assertEquals("North Plot", PestSpawnChatParser.parseCleanPlot("plot - North Plot is now clean!").orElseThrow(),
			"clean plot parsing remains case insensitive");
		assertFalse(PestSpawnChatParser.parseCleanPlot("Plot - Bad! Plot is now clean!").isPresent(),
			"clean plot parsing rejects unsafe names");
		for (PestKind kind : PestKind.values()) {
			assertTrue(PestSpawnChatParser.isPestKill(
				"You received 1 Pest Kill XP for killing a " + kind.displayName() + "!"),
				"kill messages cover the frozen pest roster: " + kind.displayName());
		}
		assertFalse(PestSpawnChatParser.isPestKill("You received XP for killing a Bat!"),
			"unrecognized mob kill lines are rejected");

		java.util.ArrayList<String> sourceNames = new java.util.ArrayList<>(List.of("East Plot"));
		PestSpawnChatParser.Spawn copied = new PestSpawnChatParser.Spawn(1, sourceNames, false);
		sourceNames.add("West Plot");
		assertEquals(List.of("East Plot"), copied.plotNames(), "Spawn defensively copies its plot list");
		PestSpawnChatParser.Spawn equalValue = new PestSpawnChatParser.Spawn(1, List.of("East Plot"), false);
		assertEquals(copied, equalValue, "Spawn keeps component-based value equality");
		assertEquals(copied.hashCode(), equalValue.hashCode(), "equal Spawn values keep matching hash codes");
		assertEquals("Spawn[amount=1, plotNames=[East Plot], whileOffline=false]", copied.toString(),
			"Spawn keeps the prior record string form");
		try {
			copied.plotNames().clear();
			throw new AssertionError("Spawn plot names must remain immutable");
		} catch (UnsupportedOperationException expected) {
			// List.copyOf exposes an immutable list.
		}
		PestSpawnChatParser.Spawn noNames = new PestSpawnChatParser.Spawn(-4, null, false);
		assertEquals(List.of(), noNames.plotNames(), "a null plot list remains normalized to an empty list");
		assertEquals(0, noNames.knownAmount(),
			"negative or unavailable counts remain clamped to zero");
	}

	private static void assertParserMethod(String name, Class<?> returnType, Class<?>... parameters) {
		try {
			Method method = PestSpawnChatParser.class.getMethod(name, parameters);
			assertSame(returnType, method.getReturnType(), "parser method descriptor is unchanged: " + name);
			int modifiers = method.getModifiers();
			assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers),
				"parser method remains public static: " + name);
		} catch (NoSuchMethodException exception) {
			throw new AssertionError("missing parser method descriptor: " + name, exception);
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("SHA-256 is unavailable", exception);
		}
	}

	private static void checkHeadProfiles() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		BuiltInRegistries.ITEM.listElements().forEach(holder -> {
			if (!holder.areComponentsBound()) holder.bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
		});
		assertFalse(PestDetector.detect(null).isPresent(), "null armor stands are unavailable");
		assertEquals("", PestDetector.headTexture(null), "null head stacks are unavailable");
		assertEquals("", PestDetector.headTexture(new ItemStack(Items.STONE)),
			"non-player-head items are ignored");
		assertEquals("", PestDetector.headTexture(new ItemStack(Items.PLAYER_HEAD)),
			"player heads without a profile are unavailable");

		String texture = PestKind.FLY.textures().getFirst();
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
		GameProfile profile = new GameProfile(UUID.randomUUID(), "pest", properties);
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile));
		assertEquals(texture, PestDetector.headTexture(stack), "profile texture is read from the head item");
	}

	private static void checkAnimation() {
		var ringComponents = PestAnimation.RingTarget.class.getRecordComponents();
		assertTrue(PestAnimation.RingTarget.class.isRecord() && ringComponents.length == 4
			&& ringComponents[0].getName().equals("x") && ringComponents[1].getName().equals("y")
			&& ringComponents[2].getName().equals("z") && ringComponents[3].getName().equals("ringIndex"),
			"animated ring targets retain the public Java record shape");
		assertEquals(10.0, PestAnimation.verticalPosition(10, 20, 0, 1), "animation starts at feet");
		assertEquals(15.0, PestAnimation.verticalPosition(10, 20, 5, 1), "animation reaches midpoint");
		assertEquals(20.0, PestAnimation.verticalPosition(10, 20, 10, 1), "animation reaches head");
		assertEquals(15.0, PestAnimation.verticalPosition(10, 20, 15, 1), "animation returns through midpoint");
		assertEquals(10.0, PestAnimation.verticalPosition(10, 20, 20, 1), "animation returns to feet");
		assertEquals(15.0, PestAnimation.verticalPosition(20, 10, 5, 1), "reversed endpoints are normalized");
		assertEquals(0.0, PestAnimation.verticalPosition(0.0, -0.0, 0.0, 1.0),
			"equal signed-zero endpoints retain the Java feet endpoint");
		double[] rings = PestAnimation.ringPositions(10, 20, 0, 1, 3);
		assertEquals(3, rings.length, "ring count is preserved");
		assertClose(10.0, rings[0], "first ring starts at the feet");
		assertClose(16.6666666667, rings[1], "second ring is phase shifted");
		assertClose(16.6666666667, rings[2], "third ring is phase shifted");
		PestAnimation.RingTarget target = PestAnimation.closestRingTarget(0, 0, 5, 12, 0, 1,
			new double[]{10, 20});
		assertClose(1.0, target.x(), "tracer lands on the camera-facing ring edge");
		assertClose(10.0, target.y(), "nearest ring is selected");
		assertClose(0.0, target.z(), "ring edge stays on the ring plane");
		assertEquals("RingTarget[x=1.0, y=2.0, z=3.0, ringIndex=4]",
			new PestAnimation.RingTarget(1, 2, 3, 4).toString(),
			"ring target keeps the Java record string form");
	}

	private static void checkDetectionCache() {
		PestDetectionCache cache = new PestDetectionCache();
		UUID entityId = UUID.randomUUID();
		ItemStack playerHead = new ItemStack(Items.PLAYER_HEAD);
		int[] detections = {0};

		assertSame(PestKind.FLY, cache.get(entityId, playerHead, () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "a new head is detected");
		assertSame(PestKind.FLY, cache.get(entityId, playerHead.copy(), () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "an unchanged head reuses its detection");
		assertEquals(1, detections[0], "unchanged pest heads are detected once");

		ItemStack changedHead = new ItemStack(Items.STONE);
		assertSame(PestKind.FLY, cache.get(entityId, changedHead, () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "a changed head invalidates detection");
		assertEquals(2, detections[0], "head changes trigger a fresh detection");

		cache.retainAll(Set.of());
		assertFalse(cache.get(entityId, changedHead, () -> {
			detections[0]++;
			return Optional.empty();
		}).isPresent(), "unclassified heads return an empty detection");
		assertEquals(3, detections[0], "removed entities are evicted from the detection cache");
		assertFalse(cache.get(entityId, changedHead.copy(), () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).isPresent(), "an unchanged head reuses its negative detection");
		assertEquals(3, detections[0], "negative detection results are cached");
		assertSame(PestKind.FLY, cache.get(entityId, playerHead, () -> {
			detections[0]++;
			return Optional.of(PestKind.FLY);
		}).orElseThrow(), "a changed head invalidates a cached negative detection");
		assertEquals(4, detections[0], "a head change after a negative result triggers detection");

		// Two modules scan the same armour stands in the same tick - the pest highlighter and the plot
		// border's fallback - so production uses one shared cache and classifies each head once.
		UUID sharedEntity = UUID.randomUUID();
		PestDetectionCache shared = PestDetectionCache.SHARED;
		shared.retainAll(Set.of());
		int[] sharedDetections = {0};
		for (int consumer = 0; consumer < 2; consumer++) {
			shared.get(sharedEntity, playerHead, () -> {
				sharedDetections[0]++;
				return Optional.of(PestKind.FLY);
			});
		}
		assertEquals(1, sharedDetections[0], "a head shared by two consumers is classified once");
		shared.retainAll(Set.of());
	}

	private static void checkSettings() {
		PestHighlighterModule module = PestHighlighterModule.INSTANCE;
		assertSame(Category.FARMING, module.category(), "Pest Highlighter is in Farming");
		assertFalse(booleanSetting(module, "Box").value(), "box is off by default");
		assertTrue(booleanSetting(module, "Circle").value(), "ring mode is enabled by default");
		assertTrue(booleanSetting(module, "Tracer").value(), "tracer is enabled by default");
		assertFalse(booleanSetting(module, "Show Name").value(), "name is off by default");
		BooleanSetting depthCheck = booleanSetting(module, "Depth Check");
		assertTrue(depthCheck.rawValue(), "depth check is stored on by default");
		assertTrue(depthCheck.value(), "depth check is enabled by default");
		assertEquals(20, numberSetting(module, "Scan Interval").intValue(), "scan interval default");
		assertEquals("B62F00FF", colorSetting(module, "Outline Color").hex(), "outline default color");
		assertEquals("B62F003C", colorSetting(module, "Fill Color").hex(), "fill default color");
		assertClose(2.0, numberSetting(module, "Outline Width").value(), "outline width default");
		assertClose(5.0, numberSetting(module, "Circle Width").value(), "circle width default");
		assertEquals("00A7FFFF", colorSetting(module, "Circle Color").hex(), "circle default color");
		assertClose(0.99761915, numberSetting(module, "Circle Radius").value(), "circle radius default");
		assertClose(0.20129871, numberSetting(module, "Circle Speed").value(), "circle speed default");
		assertEquals(1, numberSetting(module, "Ring Count").intValue(), "ring count default");
		assertEquals("00C9FFFF", colorSetting(module, "Tracer Color").hex(), "tracer default color");
		assertClose(2.0, numberSetting(module, "Tracer Width").value(), "tracer width default");

		BooleanSetting box = booleanSetting(module, "Box");
		BooleanSetting circle = booleanSetting(module, "Circle");
		BooleanSetting tracer = booleanSetting(module, "Tracer");
		try {
			box.setValue(true);
			assertTrue(box.value(), "box switch can be enabled independently");
			assertTrue(circle.value(), "enabling the box leaves ring mode enabled");
			assertTrue(tracer.value(), "enabling the box leaves the tracer enabled");
			circle.setValue(false);
			assertTrue(box.value(), "disabling ring mode leaves the box enabled");
			assertFalse(circle.value(), "ring mode switch can be disabled independently");
			assertTrue(tracer.value(), "disabling ring mode leaves the tracer enabled");
			tracer.setValue(false);
			assertTrue(box.value(), "disabling the tracer leaves the box enabled");
			assertFalse(circle.value(), "disabling the tracer leaves ring mode disabled");
			assertFalse(tracer.value(), "tracer switch can be disabled independently");
		} finally {
			box.setValue(false);
			circle.setValue(true);
			tracer.setValue(true);
		}
	}

	private static BooleanSetting booleanSetting(PestHighlighterModule module, String name) {
		return module.booleanSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static NumberSetting numberSetting(PestHighlighterModule module, String name) {
		return module.numberSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static ColorSetting colorSetting(PestHighlighterModule module, String name) {
		return module.colorSettings().stream().filter(setting -> setting.name().equals(name)).findFirst().orElseThrow();
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertFalse(boolean condition, String message) {
		assertTrue(!condition, message);
	}

	private static void assertSame(Object expected, Object actual, String message) {
		if (expected != actual) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
	}

	private static void assertEquals(double expected, double actual, String message) {
		if (Double.compare(expected, actual) != 0) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}

	private static void assertClose(double expected, double actual, String message) {
		if (Math.abs(expected - actual) > 0.0001) {
			throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
		}
	}
}
