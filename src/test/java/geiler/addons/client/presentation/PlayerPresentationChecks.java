package geiler.addons.client.presentation;

import com.mojang.brigadier.StringReader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.CompilableString;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Narrow, standalone acceptance checks for profile validation and independent presentation fields. */
public final class PlayerPresentationChecks {
	private static final int GOLD_RGB = 0xFFAA00;
	private static final int AQUA_RGB = 0x55FFFF;
	private static final int RED_RGB = 0xFF5555;

	private PlayerPresentationChecks() { }

	public static void main(String[] args) throws Exception {
		checkProfileAbi();
		PlayerNameGradientMathChecks.main(new String[0]);
		PlayerPresentationProfileSourceChecks.run();
		checkTooltipComponentMapper();
		PlayerPresentationProfiles profile = PlayerPresentationProfiles.parse("""
			{"schemaVersion":1,"players":[
			  {"name":"ExamplePlayer","alias":"Example Alias","size":1.25,"effect":{"tag":"visual-only"}},
			  {"name":"SizeOnly","size":0.75}
			]}
			""");
		check(profile.entries().size() == 2, "valid profile entries are retained");
		assertUnsupported(profile.entries()::clear, "profile entry maps are immutable snapshots");
		var gradientInput = new java.util.ArrayList<>(List.of(0xFF0000, 0x00FF00));
		var immutableGradient = new PlayerPresentationProfiles.Entry(
			"Immutable", null, null, null, null, null, null, gradientInput, null);
		gradientInput.add(0x0000FF);
		check(immutableGradient.gradient().equals(List.of(0xFF0000, 0x00FF00)),
			"Entry copies caller-owned gradient lists");
		assertUnsupported(() -> immutableGradient.gradient().add(0x0000FF), "Entry gradients are immutable");
		var alias = profile.find("exampleplayer");
		check(alias != null && "Example Alias".equals(alias.alias()), "name lookup is case-insensitive");
		check(alias.size() != null && Math.abs(alias.size() - 1.25f) < 0.0001f,
			"size is independent of alias presence");
		check(alias.effectMetadata() != null && alias.effectMetadata().contains("visual-only"),
			"effect metadata is retained as opaque data");
		var sizeOnly = profile.find("SizeOnly");
		check(sizeOnly != null && sizeOnly.alias() == null && sizeOnly.size() == 0.75f,
			"size-only entries do not imply an alias");
		check(PlayerPresentationProfiles.parse("{\"schemaVersion\":1,\"players\":[]}").entries().isEmpty(),
			"empty hosted profile is valid");
		Component original = Component.literal("[VIP] ")
			.append(Component.literal("ExamplePlayer").withStyle(ChatFormatting.GOLD))
			.append(Component.literal(" joined"));
		Component renamed = PlayerNameTextTransform.replace(original, "ExamplePlayer", "Alias");
		check("[VIP] Alias joined".equals(renamed.getString()), "name transform retains adjacent formatted text");
		check(renamed.getSiblings().get(0).getStyle().getColor() != null,
			"name transform preserves the original leaf style");
		check(PlayerNameTextTransform.replace(original, "NoMatch", "Alias") == original,
			"unmatched names retain the original immutable component");
		Component generic = PlayerNameTextTransform.replaceKnown(original,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null);
		check("[VIP] Alias joined".equals(generic.getString()),
			"generic team-formatted names use the same alias transform");
		String legacyFormatted = "\u00a76ExamplePlayer\u00a7r joined";
		check("\u00a76Alias\u00a7r joined".equals(PlayerNameTextTransform.replaceKnownLegacyText(
			legacyFormatted, text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null)),
			"legacy formatted-string alias replacement preserves surrounding format codes and text");
		Component displayedTabName = PlayerNameTextTransform.replace(
			Component.literal("ExamplePlayer"), "ExamplePlayer", "Alias ExamplePlayer");
		check("Alias ExamplePlayer".equals(displayedTabName.getString())
			&& "[MVP+] ExamplePlayer".equals(PlayerTabNameSource.rawLine(null, "ExamplePlayer",
				Component.literal("[MVP+] "), null)),
			"tab rendering can show an alias while raw team-prefix parsing retains the server username");
		check("[MVP+] ExamplePlayer".equals(PlayerTabNameSource.rawLine(
			Component.literal("[MVP+] ExamplePlayer"), "IgnoredProfileName", Component.literal("IgnoredPrefix"), null)),
			"an explicit server tab component remains the parser source");
		Component scoreDisplay = Component.literal("[VIP] ")
			.append(Component.literal("ExamplePlayer").withStyle(ChatFormatting.AQUA))
			.append(Component.literal(" Level 50"));
		Component renamedScoreDisplay = PlayerNameTextTransform.replace(scoreDisplay,
			"ExamplePlayer", "Alias");
		check("[VIP] Alias Level 50".equals(renamedScoreDisplay.getString())
			&& renamedScoreDisplay.getSiblings().get(0).getStyle().getColor() != null,
			"score display components preserve surrounding text and the player's style while aliasing the owner leaf");
		Component splitName = Component.literal("From ")
			.append(Component.literal("Exa").withStyle(ChatFormatting.GOLD))
			.append(Component.literal("mple").withStyle(ChatFormatting.AQUA))
			.append(Component.literal("Player!").withStyle(ChatFormatting.RED));
		Component splitAlias = PlayerNameTextTransform.replace(splitName, "ExamplePlayer", "Nickname");
		check("From Nickname!".equals(splitAlias.getString()),
			"a username split across adjacent plain components is replaced without changing surrounding text");
		Component firstSplitPart = splitAlias.getSiblings().get(0);
		Component secondSplitPart = splitAlias.getSiblings().get(1);
		Component thirdSplitPart = splitAlias.getSiblings().get(2);
		check("Ni".equals(firstSplitPart.getString())
			&& firstSplitPart.getSiblings().get(0).getStyle().getColor().getValue() == GOLD_RGB,
			"replacement characters mapped to the first source leaf retain its original style");
		check("ckn".equals(secondSplitPart.getString())
			&& secondSplitPart.getSiblings().get(0).getStyle().getColor().getValue() == AQUA_RGB,
			"replacement characters mapped to the second source leaf retain its original style");
		check("ame!".equals(thirdSplitPart.getString())
			&& thirdSplitPart.getStyle().getColor().getValue() == RED_RGB,
			"text after the split username remains in its original styled leaf");
		check(PlayerNameTextTransform.replace(splitName, "ExamplePlayer", "Nickname") != splitName,
			"the split-name transform detects a username across leaf boundaries");
		Component nearMatch = Component.literal("Examp").append(Component.literal("lePlayer2"));
		check(PlayerNameTextTransform.replace(nearMatch, "ExamplePlayer", "Nickname") == nearMatch,
			"a longer neighboring token does not match a username split across leaves");
		var styledProfile = PlayerPresentationProfiles.parse("""
			{"schemaVersion":1,"players":[{"name":"ExamplePlayer","alias":"Alias","color":"#00FF00"}]}
			""");
		Component knownProfile = PlayerNameTextTransform.applyKnownProfiles(original,
			styledProfile::find, true, true);
		check("[VIP] Alias joined".equals(knownProfile.getString()) && hasColor(knownProfile, 0x00FF00),
			"known hosted profiles apply aliases and configured name styles to team-formatted components");
		check(PlayerNameTextTransform.hasNamePresentation(splitName, styledProfile::find),
			"presentation detection recognizes a styled profile name split across leaves");
		Component translatedSplit = Component.translatable("presentation.test",
			Component.literal("Example").withStyle(ChatFormatting.GOLD)
				.append(Component.literal("Player").withStyle(ChatFormatting.RED)));
		Component translatedAlias = PlayerNameTextTransform.replace(translatedSplit, "ExamplePlayer", "Alias");
		Object translatedArgument = ((TranslatableContents) translatedAlias.getContents()).getArgs()[0];
		check(translatedArgument instanceof Component component && "Alias".equals(component.getString()),
			"an exposed translatable Component argument is rewritten safely");
		Component translatedString = Component.translatable("presentation.test", "ExamplePlayer");
		Component rewrittenString = PlayerNameTextTransform.replace(translatedString, "ExamplePlayer", "Alias");
		Object stringArgument = ((TranslatableContents) rewrittenString.getContents()).getArgs()[0];
		check(stringArgument instanceof Component component && "Alias".equals(component.getString()),
			"an exposed translatable String argument is rewritten as a styled Component");
		Component localizedFallback = Component.translatableWithFallback(
			"presentation.example.player-name", "ExamplePlayer");
		Component localizedAlias = PlayerNameTextTransform.replace(localizedFallback, "ExamplePlayer", "Alias");
		check("Alias".equals(localizedAlias.getString()),
			"a username in the resolved localized template is rewritten for display");
		Component hoverSource = Component.literal("Hover me").withStyle(
			Style.EMPTY.withHoverEvent(new HoverEvent.ShowText(
				Component.literal("ExamplePlayer").withStyle(ChatFormatting.GOLD))));
		Component hoverAlias = PlayerNameTextTransform.replace(hoverSource, "ExamplePlayer", "Alias");
		check(hoverAlias.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hover
			&& "Alias".equals(hover.value().getString())
			&& hover.value().getSiblings().get(0).getStyle().getColor().getValue()
				== GOLD_RGB,
			"ShowText hover payloads are rewritten while their text styling remains intact");
		check(hoverSource.getStyle().getHoverEvent() instanceof HoverEvent.ShowText originalHover
			&& "ExamplePlayer".equals(originalHover.value().getString()),
			"hover transformation does not mutate the source component");
		Component entityHoverSource = Component.literal("Entity info").withStyle(
			Style.EMPTY.withHoverEvent(new HoverEvent.ShowEntity(new HoverEvent.EntityTooltipInfo(
				BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("player")),
				UUID.fromString("00000000-0000-0000-0000-000000000001"),
				Component.literal("ExamplePlayer").withStyle(ChatFormatting.GOLD)))));
		Component entityHoverAlias = PlayerNameTextTransform.replace(entityHoverSource, "ExamplePlayer", "Alias");
		check(entityHoverAlias.getStyle().getHoverEvent() instanceof HoverEvent.ShowEntity entityHover
			&& "Alias".equals(entityHover.entity().name.orElseThrow().getString()),
			"ShowEntity hover names are transformed while entity identity metadata remains intact");
		Component splitAroundTranslation = Component.literal("Example")
			.append(Component.translatable("presentation.test", "Player"));
		check(PlayerNameTextTransform.replace(splitAroundTranslation, "ExamplePlayer", "Alias") == splitAroundTranslation,
			"plain text is not joined across a translation template boundary");
		Component mapPlayerLine = Component.literal("Players: ")
			.append(PlayerNameTextTransform.applyProfile(Component.literal("ExamplePlayer"), "ExamplePlayer",
				styledProfile.find("ExamplePlayer"), true, true))
			.append(" (3,4)");
		check("Players: Alias (3,4)".equals(mapPlayerLine.getString()),
			"dungeon map player lines keep coordinates while transforming the name component");
		Component leapCardName = PlayerNameTextTransform.applyProfile(Component.literal("ExamplePlayer"),
			"ExamplePlayer", styledProfile.find("ExamplePlayer"), true, true);
		check("Alias".equals(leapCardName.getString())
			&& hasColor(leapCardName, 0x00FF00),
			"Spirit Leap card components render the alias with the configured profile color");
		String longAlias = "LongAlias".repeat(7) + "1";
		check(longAlias.length() == 64, "long alias fixture covers the maximum configured profile length");
		var longAliasProfile = PlayerPresentationProfiles.parse("""
			{"schemaVersion":1,"players":[{"name":"ExamplePlayer","alias":"%s","color":"#45B6FE"}]}
			""".formatted(longAlias));
		Component longLeapName = PlayerNameTextTransform.applyProfile(Component.literal("ExamplePlayer"),
			"ExamplePlayer", longAliasProfile.find("ExamplePlayer"), true, true);
		check(longAlias.equals(longLeapName.getString()), "the maximum-length alias is present before fitting");
		Component fittedLeapName = ComponentWidthLimiter.fit(longLeapName, 132,
			component -> codePointWidth(component, 8));
		check(codePointWidth(fittedLeapName, 8) <= 132 && fittedLeapName.getString().endsWith("…"),
			"maximum-length leap aliases are truncated with an ellipsis within the card text width");
		check(hasColor(fittedLeapName, 0x45B6FE),
			"truncating a leap alias preserves its configured text color");
		Component fittedInitial = ComponentWidthLimiter.fit(Component.literal("EXPANDEDINITIAL"), 40,
			component -> codePointWidth(component, 8));
		check(codePointWidth(fittedInitial, 8) <= 40,
			"fallback initials stay inside the 40-pixel player-face box");
		String[] leapClassLines = {"Tank", "Healer", "Mage", "Berserk", "Archer", "DEAD", "Class unknown"};
		for (String line : leapClassLines) {
			Component fittedClassLine = ComponentWidthLimiter.fit(Component.literal(line), 132,
				component -> codePointWidth(component, 8));
			check(codePointWidth(fittedClassLine, 8) <= 132 && line.equals(fittedClassLine.getString()),
				"leap card class/status text fits without truncation: " + line);
		}
		Component styledClassLine = Component.literal("DEAD").withStyle(
			Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)));
		Component fittedStyledClassLine = ComponentWidthLimiter.fit(styledClassLine, 16,
			component -> codePointWidth(component, 8));
		check(codePointWidth(fittedStyledClassLine, 8) <= 16
			&& hasColor(fittedStyledClassLine, 0xFF5555),
			"fitted leap status text preserves its class/status color");
		Component arrivalTitle = Component.literal("Leap arrival: ")
			.append(PlayerNameTextTransform.applyProfile(Component.literal("ExamplePlayer"), "ExamplePlayer",
				styledProfile.find("ExamplePlayer"), true, true))
			.append(" · Mage");
		check("Leap arrival: Alias · Mage".equals(arrivalTitle.getString()),
			"incoming leap titles transform the teammate name without changing the class");
		check(hasColor(arrivalTitle, 0x00FF00),
			"incoming leap titles retain the teammate profile's configured name color");
		check("Alias".equals(PlayerNameTextTransform.replaceKnown(Component.literal("ExamplePlayer"),
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null).getString()),
			"materialized literal output is eligible for display-only alias replacement");
		Component unresolvedScore = Component.score("ExamplePlayer", "dummy");
		check(PlayerNameTextTransform.replaceKnown(unresolvedScore,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null) == unresolvedScore,
			"unresolved score components are not guessed or rewritten from their owner syntax");
		Component unresolvedSelector = selectorComponent("@a");
		check(PlayerNameTextTransform.replaceKnown(unresolvedSelector,
			text -> "a".equalsIgnoreCase(text) ? "Alias" : null) == unresolvedSelector,
			"raw selector syntax is not treated as a username token");
		var singleLetterProfile = PlayerPresentationProfiles.parse(
			"{\"schemaVersion\":1,\"players\":[{\"name\":\"a\",\"alias\":\"Alias\"}]}");
		check(!PlayerNameTextTransform.hasNamePresentation(unresolvedSelector, singleLetterProfile::find),
			"raw selector source is also excluded from presentation detection");
		Component unresolvedNbt = Component.nbt(null, false, false, Optional.empty(), null);
		check(PlayerNameTextTransform.replaceKnown(unresolvedNbt,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null) == unresolvedNbt,
			"unresolved NBT components are left for their normal server-context resolution");
		Component localizedTemplate = Component.translatableWithFallback(
			"presentation.player-name.mixed", "ExamplePlayer joined");
		Component mixedDisplay = localizedTemplate.copy().append(unresolvedScore);
		Component transformedMixedDisplay = PlayerNameTextTransform.replaceKnown(mixedDisplay,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null);
		check(("Alias joined" + unresolvedScore.getString()).equals(transformedMixedDisplay.getString())
			&& hasSiblingContents(transformedMixedDisplay, unresolvedScore.getContents()),
			"localized sibling templates are transformed while an adjacent unresolved score lookup stays intact");
		Component selectorMixedDisplay = localizedTemplate.copy().append(unresolvedSelector);
		Component transformedSelectorMixedDisplay = PlayerNameTextTransform.replaceKnown(selectorMixedDisplay,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null);
		check(("Alias joined" + unresolvedSelector.getString()).equals(transformedSelectorMixedDisplay.getString())
			&& hasSiblingContents(transformedSelectorMixedDisplay, unresolvedSelector.getContents()),
			"localized sibling templates are transformed without rewriting raw selector lookup syntax");
		Component nbtMixedDisplay = localizedTemplate.copy().append(unresolvedNbt);
		Component transformedNbtMixedDisplay = PlayerNameTextTransform.replaceKnown(nbtMixedDisplay,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null);
		check(("Alias joined" + unresolvedNbt.getString()).equals(transformedNbtMixedDisplay.getString())
			&& hasSiblingContents(transformedNbtMixedDisplay, unresolvedNbt.getContents()),
			"localized sibling templates are transformed without rewriting raw NBT lookup syntax");
		Component dynamicTranslation = Component.translatableWithFallback(
			"presentation.player-name.dynamic", "ExamplePlayer score: %s", unresolvedScore);
		check(PlayerNameTextTransform.replaceKnown(dynamicTranslation,
			text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Alias" : null) == dynamicTranslation,
			"a template with unresolved server-context arguments stays intact instead of rewriting lookup semantics");
		fails("{\"schemaVersion\":1,\"players\":[{\"name\":\"bad name\"}]}",
			"invalid account names are rejected");
		fails("{\"schemaVersion\":1,\"players\":[{\"name\":\" ExamplePlayer\"}]}",
			"account names with surrounding whitespace are rejected");
		fails("{\"schemaVersion\":1,\"players\":[{\"name\":\"One\",\"size\":9}]}",
			"unsafe sizes are rejected");
		fails("{\"schemaVersion\":1,\"players\":[{\"name\":\"One\"},{\"name\":\"one\"}]}",
			"duplicate names are rejected case-insensitively");
		fails("{\"schemaVersion\":1,\"unexpected\":true,\"players\":[]}",
			"unknown top-level fields are rejected");
	}

	private static void checkProfileAbi() throws Exception {
		checkIntConstant("SCHEMA_VERSION", 1);
		checkIntConstant("MAX_JSON_BYTES", 256 * 1024);
		checkIntConstant("MAX_JSON_CHARS", 256 * 1024);
		checkIntConstant("MAX_JSON_DEPTH", 8);
		checkIntConstant("MAX_JSON_TOKENS", 32_768);
		checkIntConstant("MAX_JSON_NODES", 16_384);
		checkIntConstant("MAX_PLAYERS", 512);
		checkIntConstant("MAX_EFFECT_METADATA_CHARS", 2 * 1024);
		checkIntConstant("MIN_GRADIENT_STOPS", 2);
		checkIntConstant("MAX_GRADIENT_STOPS", 8);
		checkDoubleConstant("MIN_ANIMATION_SPEED", 0.05);
		checkDoubleConstant("MAX_ANIMATION_SPEED", 2.0);

		Method empty = assertMethod("empty", PlayerPresentationProfiles.class, true);
		assertMethod("decodeUtf8", String.class, true, byte[].class);
		assertMethod("parseUtf8", PlayerPresentationProfiles.class, true, byte[].class);
		assertMethod("parse", PlayerPresentationProfiles.class, true, String.class);
		assertThrowsIOException("decodeUtf8", byte[].class);
		assertThrowsIOException("parseUtf8", byte[].class);
		assertThrowsIOException("parse", String.class);
		assertMethod("find", PlayerPresentationProfiles.Entry.class, false, String.class);
		assertMethod("entries", java.util.Map.class, false);
		assertMethod("hasAnimatedNameStyles", boolean.class, false);
		check(PlayerPresentationProfiles.empty() == PlayerPresentationProfiles.parse(
			"{\"schemaVersion\":1,\"players\":[]}"),
			"empty parsed profiles retain the shared empty singleton");
		check(empty.getReturnType() == PlayerPresentationProfiles.class,
			"the empty factory keeps its declared return type");

		Class<?> animationType = PlayerPresentationProfiles.Animation.class;
		check(animationType.isRecord(), "Animation retains Java record metadata");
		assertRecordComponents(animationType, new String[] { "cyclesPerSecond" }, new Class<?>[] { float.class });
		check(animationType.getConstructor(float.class).getParameterCount() == 1
			&& animationType.getMethod("cyclesPerSecond").getReturnType() == float.class,
			"Animation retains its canonical constructor and component accessor");
		check("Animation[cyclesPerSecond=0.5]".equals(
			new PlayerPresentationProfiles.Animation(0.5f).toString()),
			"Animation retains the prior Java record-shaped diagnostic string");

		Class<?> entryType = PlayerPresentationProfiles.Entry.class;
		check(!entryType.isRecord(), "Entry value behavior is implemented in Kotlin without record metadata");
		entryType.getConstructor(String.class, String.class, String.class, Float.class, Float.class, Float.class,
			Integer.class, List.class, animationType);
		entryType.getConstructor(String.class, String.class, String.class, Float.class, Integer.class,
			List.class, animationType);
		assertEntryAccessor(entryType, "playerName", String.class);
		assertEntryAccessor(entryType, "alias", String.class);
		assertEntryAccessor(entryType, "effectMetadata", String.class);
		assertEntryAccessor(entryType, "sizeX", Float.class);
		assertEntryAccessor(entryType, "sizeY", Float.class);
		assertEntryAccessor(entryType, "sizeZ", Float.class);
		assertEntryAccessor(entryType, "color", Integer.class);
		assertEntryAccessor(entryType, "gradient", List.class);
		assertEntryAccessor(entryType, "animation", animationType);
		check(entryType.getMethod("size").getReturnType() == Float.class
			&& entryType.getMethod("hasSizeOverride").getReturnType() == boolean.class
			&& entryType.getMethod("hasNameStyle").getReturnType() == boolean.class,
			"Entry retains compatibility and derived accessors");
		PlayerPresentationProfiles.Entry legacy = new PlayerPresentationProfiles.Entry(
			"ExamplePlayer", "Alias", null, 1.5f, null, null, null);
		check(legacy.sizeX() == 1.5f && legacy.sizeY() == 1.5f && legacy.sizeZ() == 1.5f
			&& legacy.gradient().isEmpty(),
			"the legacy scalar-size constructor still expands size and normalizes a null gradient");
		PlayerPresentationProfiles.Entry nullable = new PlayerPresentationProfiles.Entry(
			null, null, null, null, null, null, null, null, null);
		check(nullable.playerName() == null && nullable.gradient().isEmpty(),
			"the canonical Entry constructor retains Java null handling while normalizing its gradient");
		PlayerPresentationProfiles.Entry valueA = new PlayerPresentationProfiles.Entry(
			"ExamplePlayer", "Alias", null, 1.5f, 1.5f, 1.5f, null, List.of(1, 2), null);
		PlayerPresentationProfiles.Entry valueB = new PlayerPresentationProfiles.Entry(
			"ExamplePlayer", "Alias", null, 1.5f, 1.5f, 1.5f, null, List.of(1, 2), null);
		check(valueA.equals(valueB) && valueA.hashCode() == valueB.hashCode(),
			"Kotlin Entry values retain component-based equality and matching hashes");
		check(("Entry[playerName=ExamplePlayer, alias=Alias, effectMetadata=null, sizeX=1.5, sizeY=1.5, sizeZ=1.5, "
			+ "color=null, gradient=[1, 2], animation=null]").equals(valueA.toString()),
			"Entry retains the prior record-shaped diagnostic string");
	}

	private static void assertEntryAccessor(Class<?> type, String name, Class<?> returnType)
		throws ReflectiveOperationException {
		Method accessor = type.getMethod(name);
		check(accessor.getReturnType() == returnType && Modifier.isPublic(accessor.getModifiers())
			&& !Modifier.isStatic(accessor.getModifiers()),
			"Entry retains Java component accessor " + name + " with its public instance descriptor");
	}

	private static void assertRecordComponents(Class<?> type, String[] names, Class<?>[] types) {
		RecordComponent[] components = type.getRecordComponents();
		check(components.length == names.length, type.getSimpleName() + " record component count is stable");
		for (int i = 0; i < names.length; i++) {
			check(names[i].equals(components[i].getName()) && types[i] == components[i].getType(),
				type.getSimpleName() + " record component " + i + " retains its name and type");
			try {
				check(type.getMethod(names[i]).getReturnType() == types[i],
					type.getSimpleName() + " record accessor " + names[i] + " is stable");
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError(type.getSimpleName() + " record accessor is missing: " + names[i], exception);
			}
		}
	}

	private static void checkIntConstant(String name, int expected) throws ReflectiveOperationException {
		Field field = PlayerPresentationProfiles.class.getField(name);
		check(field.getType() == int.class && Modifier.isPublic(field.getModifiers())
			&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
			&& field.getInt(null) == expected,
			"public int constant " + name + " retains its value and modifiers");
	}

	private static void checkDoubleConstant(String name, double expected) throws ReflectiveOperationException {
		Field field = PlayerPresentationProfiles.class.getField(name);
		check(field.getType() == double.class && Modifier.isPublic(field.getModifiers())
			&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
			&& Double.compare(field.getDouble(null), expected) == 0,
			"public double constant " + name + " retains its value and modifiers");
	}

	private static Method assertMethod(String name, Class<?> returnType, boolean isStatic, Class<?>... parameterTypes)
		throws ReflectiveOperationException {
		Method method = PlayerPresentationProfiles.class.getMethod(name, parameterTypes);
		check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
			&& Modifier.isStatic(method.getModifiers()) == isStatic,
			"profile API method " + name + " retains its public JVM descriptor");
		return method;
	}

	private static void assertThrowsIOException(String name, Class<?>... parameterTypes)
		throws ReflectiveOperationException {
		Method method = PlayerPresentationProfiles.class.getMethod(name, parameterTypes);
		check(Arrays.equals(method.getExceptionTypes(), new Class<?>[] { IOException.class }),
			"profile API method " + name + " retains its IOException declaration");
	}

	private static void checkTooltipComponentMapper() {
		Component sellerLine = Component.literal("Seller: ").append(Component.literal("ExamplePlayer")
			.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55))))
			.append(Component.literal(" · Price: 1m"));
		Component unchangedLine = Component.literal("Price: 1m")
			.withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)));
		List<Component> original = Arrays.asList(sellerLine, unchangedLine, null);
		List<Component> mapped = TooltipComponentMapper.mapComponents(original,
			line -> PlayerNameTextTransform.replaceKnown(line,
				text -> "ExamplePlayer".equalsIgnoreCase(text) ? "Example Alias" : null));
		check(mapped != original && mapped.size() == 3 && mapped.get(2) == null,
			"tooltip component mapping is null-safe and retains all line positions");
		check("Seller: Example Alias · Price: 1m".equals(mapped.get(0).getString())
			&& hasColor(mapped.get(0), 0x55FF55),
			"tooltip name mapping preserves surrounding text and the name style");
		check(mapped.get(1) == unchangedLine && hasColor(mapped.get(1), 0xFF5555),
			"unmatched tooltip lines retain their original component and style");
		check(TooltipComponentMapper.mapComponents(null, line -> line) == null
			&& TooltipComponentMapper.mapComponents(original, null) == original,
			"null tooltip lists and null mapping functions are returned unchanged");
	}

	private static Component selectorComponent(String selectorSource) throws Exception {
		EntitySelector compiled = new EntitySelectorParser(new StringReader(selectorSource), true).parse();
		Constructor<CompilableString> constructor = CompilableString.class
			.getDeclaredConstructor(String.class, Object.class);
		constructor.setAccessible(true);
		@SuppressWarnings("unchecked")
		CompilableString<EntitySelector> selector = constructor.newInstance(selectorSource, compiled);
		return Component.selector(selector, Optional.empty());
	}

	private static boolean hasColor(Component component, int expectedRgb) {
		boolean[] found = {false};
		component.visit((style, text) -> {
			if (style.getColor() != null && style.getColor().getValue() == expectedRgb) found[0] = true;
			return Optional.empty();
		}, Style.EMPTY);
		return found[0];
	}

	private static boolean hasSiblingContents(Component component,
		net.minecraft.network.chat.ComponentContents expectedContents) {
		return component.getSiblings().stream().anyMatch(sibling -> sibling.getContents() == expectedContents);
	}

	private static int codePointWidth(Component component, int pixelsPerCodePoint) {
		String text = component.getString();
		return text.codePointCount(0, text.length()) * pixelsPerCodePoint;
	}

	private static void fails(String json, String message) throws Exception {
		try {
			PlayerPresentationProfiles.parse(json);
			throw new AssertionError(message);
		} catch (java.io.IOException expected) {
			// Invalid input is expected to fail closed.
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void assertUnsupported(Runnable operation, String message) {
		try {
			operation.run();
			throw new AssertionError(message);
		} catch (UnsupportedOperationException expected) {
			// Public profile data is immutable.
		}
	}
}
