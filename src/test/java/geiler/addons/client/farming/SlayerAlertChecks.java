package geiler.addons.client.farming;

import geiler.addons.client.farming.SlayerAlertRules.BossObservation;
import geiler.addons.client.farming.SlayerAlertRules.BossType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

/** Focused pure checks for Slayer identity filters and alert presentation. */
public final class SlayerAlertChecks {
	private SlayerAlertChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		checkFacadeAbi();
		checkPublicRecordMetadata();
		checkExistingDefaults();
		checkTypeObservationAndFilters();
		checkPresentation();
		checkRecordFormattingAndNullableComponents();
		checkQuestSidebarLifecycle();
	}

	private static void checkFacadeAbi() {
		checkConstructor(SlayerAlertRules.class, false);
		checkConstructor(SlayerQuestSidebarLifecycle.class, true);
		checkMethod(SlayerAlertRules.BossType.class, "displayName", String.class, false);
		checkMethod(SlayerAlertRules.BossType.class, "settingName", String.class, false);
		checkMethod(SlayerAlertRules.BossObservation.class, "recognized", boolean.class, false);
		checkMethod(SlayerAlertRules.class, "observeBossNames", SlayerAlertRules.BossObservation.class,
			true, java.util.Collection.class);
		checkMethod(SlayerAlertRules.class, "classifyBossName", BossType.class, true, String.class);
		checkMethod(SlayerAlertRules.class, "bossFilterAllows", boolean.class, true,
			BossObservation.class, java.util.Map.class, boolean.class);
		checkMethod(SlayerAlertRules.class, "presentation", SlayerAlertRules.Presentation.class, true,
			boolean.class, boolean.class, boolean.class, int.class, String.class, BossType.class,
			String.class, String.class);
		checkMethod(SlayerAlertRules.class, "allEnabledFilters", EnumMap.class, true);
		checkMethod(SlayerQuestSidebarLifecycle.class, "observe", SlayerQuestSidebarLifecycle.Update.class,
			false, SlayerScoreboardParser.State.class);
		checkMethod(SlayerQuestSidebarLifecycle.class, "onQuestStarted", SlayerQuestSidebarLifecycle.Update.class,
			false);
		checkMethod(SlayerQuestSidebarLifecycle.class, "onQuestCompleted", SlayerQuestSidebarLifecycle.Update.class,
			false);
		checkMethod(SlayerQuestSidebarLifecycle.class, "onQuestFailed", SlayerQuestSidebarLifecycle.Update.class,
			false);
		checkMethod(SlayerQuestSidebarLifecycle.class, "reset", void.class, false);
		checkMethod(SlayerQuestSidebarLifecycle.class, "consecutiveIdleSnapshots", int.class, false);
		checkMethod(SlayerQuestSidebarLifecycle.class, "questActive", boolean.class, false);
		checkMethod(SlayerAlertRules.BossObservation.class, "type", BossType.class, false);
		checkMethod(SlayerAlertRules.BossObservation.class, "ambiguous", boolean.class, false);
		checkMethod(SlayerAlertRules.Presentation.class, "showChat", boolean.class, false);
		checkMethod(SlayerAlertRules.Presentation.class, "showTitle", boolean.class, false);
		checkMethod(SlayerAlertRules.Presentation.class, "playSound", boolean.class, false);
		checkMethod(SlayerAlertRules.Presentation.class, "durationTicks", int.class, false);
		checkMethod(SlayerAlertRules.Presentation.class, "message", String.class, false);
		checkMethod(SlayerQuestSidebarLifecycle.Update.class, "questActive", boolean.class, false);
		checkMethod(SlayerQuestSidebarLifecycle.Update.class, "bossSlain", boolean.class, false);
		checkMethod(SlayerQuestSidebarLifecycle.Update.class, "questFailed", boolean.class, false);

		for (String name : List.of("DEFAULT_BOSS_ALERT", "DEFAULT_MINIBOSS_ALERT", "DEFAULT_BOSS_TYPE_FILTER",
			"DEFAULT_UNCLASSIFIED_BOSS_ALERT", "DEFAULT_CHAT_ALERT", "DEFAULT_TITLE_ALERT", "DEFAULT_SOUND_ALERT")) {
			checkField(SlayerAlertRules.class, name, boolean.class);
		}
		for (String name : List.of("DEFAULT_TITLE_DURATION_TICKS", "MIN_TITLE_DURATION_TICKS",
			"MAX_TITLE_DURATION_TICKS")) {
			checkField(SlayerAlertRules.class, name, int.class);
		}
		checkField(SlayerAlertRules.class, "DEFAULT_BOSS_TEXT", String.class);
		checkField(SlayerAlertRules.class, "DEFAULT_MINIBOSS_TEXT", String.class);
		checkField(SlayerQuestSidebarLifecycle.class, "STABLE_IDLE_SNAPSHOTS", int.class);
		checkConstructor(SlayerAlertRules.BossObservation.class, true, BossType.class, boolean.class);
		checkConstructor(SlayerAlertRules.Presentation.class, true,
			boolean.class, boolean.class, boolean.class, int.class, String.class);
		checkConstructor(SlayerQuestSidebarLifecycle.Update.class, true,
			boolean.class, boolean.class, boolean.class);
		equals(List.of("REVENANT_HORROR", "ATONED_HORROR", "TARANTULA_BROODFATHER", "SVEN_PACKMASTER",
			"VOIDGLOOM_SERAPH", "INFERNO_DEMONLORD", "RIFTSTALKER_BLOODFIEND"),
			Arrays.stream(BossType.values()).map(BossType::name).toList(),
			"boss enum preserves its names and declaration order");
	}

	private static void checkConstructor(Class<?> type, boolean isPublic, Class<?>... parameterTypes) {
		try {
			Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
			check((isPublic ? Modifier.isPublic(constructor.getModifiers())
				: Modifier.isPrivate(constructor.getModifiers())),
				type.getSimpleName() + " preserves constructor visibility");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(type.getSimpleName() + " preserves its constructor descriptor", error);
		}
	}

	private static void checkField(Class<?> owner, String name, Class<?> type) {
		try {
			Field field = owner.getDeclaredField(name);
			check(field.getType() == type && Modifier.isPublic(field.getModifiers())
				&& Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers()),
				owner.getSimpleName() + "." + name + " remains a public static final field of the same type");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(owner.getSimpleName() + "." + name + " preserves its field descriptor", error);
		}
	}

	private static void checkMethod(Class<?> owner, String name, Class<?> returnType, boolean isStatic,
		Class<?>... parameterTypes) {
		try {
			Method method = owner.getDeclaredMethod(name, parameterTypes);
			check(method.getReturnType() == returnType && Modifier.isPublic(method.getModifiers())
				&& Modifier.isStatic(method.getModifiers()) == isStatic,
				owner.getSimpleName() + "." + name + " preserves its public method descriptor");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError(owner.getSimpleName() + "." + name + " preserves its method descriptor", error);
		}
	}

	private static void checkPublicRecordMetadata() {
		checkRecordComponents(SlayerQuestSidebarLifecycle.Update.class,
			List.of("questActive", "bossSlain", "questFailed"));
		checkRecordComponents(SlayerAlertRules.BossObservation.class,
			List.of("type", "ambiguous"));
		checkRecordComponents(SlayerAlertRules.Presentation.class,
			List.of("showChat", "showTitle", "playSound", "durationTicks", "message"));
	}

	private static void checkRecordComponents(Class<?> recordType, List<String> expectedComponents) {
		check(recordType.isRecord(), recordType.getSimpleName() + " remains a native Java record");
		List<String> actualComponents = Arrays.stream(recordType.getRecordComponents())
			.map(component -> component.getName())
			.toList();
		equals(expectedComponents, actualComponents,
			recordType.getSimpleName() + " preserves its Java record component names and order");
	}

	private static void checkExistingDefaults() {
		check(!SlayerAlertRules.DEFAULT_BOSS_ALERT, "boss alert remains disabled by default");
		check(!SlayerAlertRules.DEFAULT_MINIBOSS_ALERT, "miniboss alert remains disabled by default");
		check(SlayerAlertRules.DEFAULT_UNCLASSIFIED_BOSS_ALERT, "unclassified boss fallback remains enabled by default");
		check(SlayerAlertRules.DEFAULT_CHAT_ALERT, "chat remains the default alert output");
		check(!SlayerAlertRules.DEFAULT_TITLE_ALERT, "title output defaults off");
		check(!SlayerAlertRules.DEFAULT_SOUND_ALERT, "sound output defaults off");
		check(SlayerAlertRules.DEFAULT_BOSS_TYPE_FILTER, "individual recognized boss filters default on");
		equals(40, SlayerAlertRules.DEFAULT_TITLE_DURATION_TICKS, "default title duration remains 40 ticks");
		equals("Slayer boss spawned.", SlayerAlertRules.DEFAULT_BOSS_TEXT, "default boss alert text remains unchanged");
		equals("Slayer miniboss: {miniboss}.", SlayerAlertRules.DEFAULT_MINIBOSS_TEXT,
			"default miniboss alert text remains unchanged");
		equals(1, SlayerAlertRules.MIN_TITLE_DURATION_TICKS, "minimum title duration remains one tick");
		equals(600, SlayerAlertRules.MAX_TITLE_DURATION_TICKS, "maximum title duration remains 600 ticks");
		equals(2, SlayerQuestSidebarLifecycle.STABLE_IDLE_SNAPSHOTS,
			"sidebar lifecycle still requires two stable idle snapshots");
	}

	private static void checkTypeObservationAndFilters() {
		BossObservation revenant = SlayerAlertRules.observeBossNames(List.of(
			"§cRevenant Horror", "Revenant Horror"));
		check(revenant.recognized() && revenant.type() == BossType.REVENANT_HORROR,
			"formatted repeated names resolve to one recognized boss type");
		check(SlayerAlertRules.classifyBossName("[Lv100] §5Voidgloom Seraph") == BossType.VOIDGLOOM_SERAPH,
			"formatted visible name resolves to a boss type");

		EnumMap<BossType, Boolean> filters = SlayerAlertRules.allEnabledFilters();
		check(SlayerAlertRules.bossFilterAllows(revenant, filters, true),
			"enabled per-boss filter allows its matching event");
		filters.put(BossType.REVENANT_HORROR, false);
		check(!SlayerAlertRules.bossFilterAllows(revenant, filters, true),
			"disabled per-boss filter suppresses the matching event");
		check(SlayerAlertRules.bossFilterAllows(SlayerAlertRules.observeBossNames(
			List.of("Sven Packmaster")), filters, true), "a different boss uses its own filter");

		BossObservation unknown = SlayerAlertRules.observeBossNames(List.of("Slayer boss"));
		check(!unknown.recognized() && !unknown.ambiguous(), "unknown entity name remains unclassified");
		check(SlayerAlertRules.bossFilterAllows(unknown, filters, true),
			"unclassified fallback preserves generic boss alert behavior");
		check(!SlayerAlertRules.bossFilterAllows(unknown, filters, false),
			"unclassified fallback can be independently disabled");
		BossObservation ambiguous = SlayerAlertRules.observeBossNames(
			List.of("Revenant Horror", "Tarantula Broodfather"));
		check(ambiguous.ambiguous(), "conflicting nearby boss identities are marked ambiguous");
		check(!SlayerAlertRules.bossFilterAllows(ambiguous, filters, true),
			"ambiguous identity fails closed even when generic fallback is enabled");
	}

	private static void checkPresentation() {
		SlayerAlertRules.Presentation defaults = SlayerAlertRules.presentation(
			SlayerAlertRules.DEFAULT_CHAT_ALERT, SlayerAlertRules.DEFAULT_TITLE_ALERT,
			SlayerAlertRules.DEFAULT_SOUND_ALERT, SlayerAlertRules.DEFAULT_TITLE_DURATION_TICKS,
			SlayerAlertRules.DEFAULT_BOSS_TEXT, BossType.REVENANT_HORROR, null, null);
		check(defaults.showChat() && !defaults.showTitle() && !defaults.playSound(),
			"default alert outputs preserve the existing chat-only behavior");
		equals("Slayer boss spawned.", defaults.message(), "default boss alert text is preserved");
		equals(40, defaults.durationTicks(), "default alert title duration is two seconds");

		SlayerAlertRules.Presentation custom = SlayerAlertRules.presentation(false, true, true, 95,
			"{boss} and {miniboss}", BossType.VOIDGLOOM_SERAPH, null, "§cVoidling Devotee");
		check(!custom.showChat() && custom.showTitle() && custom.playSound(),
			"chat, title, and sound outputs are independently selectable");
		equals("Voidgloom Seraph and Voidling Devotee", custom.message(),
			"custom alert templates substitute stripped observed names");
		equals(95, custom.durationTicks(), "custom title duration is preserved");
		equals(SlayerAlertRules.MIN_TITLE_DURATION_TICKS,
			SlayerAlertRules.presentation(true, false, false, 0, "", null, null, null).durationTicks(),
			"title duration is clamped to at least one tick");
		equals(SlayerAlertRules.MAX_TITLE_DURATION_TICKS,
			SlayerAlertRules.presentation(true, false, false, 900, "ok", null, null, null).durationTicks(),
			"title duration is clamped to the supported maximum");
	}

	private static void checkRecordFormattingAndNullableComponents() {
		BossObservation unknown = new BossObservation(null, false);
		check(!unknown.recognized(), "a null boss type remains unrecognized");
		equals("BossObservation[type=null, ambiguous=false]", unknown.toString(),
			"BossObservation keeps Java record formatting");
		SlayerAlertRules.Presentation presentation = new SlayerAlertRules.Presentation(false, false, false, 0, null);
		check(presentation.message() == null, "the public record constructor continues to accept a null message");
		equals("Presentation[showChat=false, showTitle=false, playSound=false, durationTicks=0, message=null]",
			presentation.toString(), "Presentation keeps Java record formatting");
		equals("Update[questActive=true, bossSlain=false, questFailed=false]",
			new SlayerQuestSidebarLifecycle.Update(true, false, false).toString(),
			"Update keeps Java record formatting");
	}

	private static void checkQuestSidebarLifecycle() {
		check(SlayerScoreboardParser.parse("Slayer Quest\nRevenant Horror\nCombat XP: 1,200/2,000")
			== SlayerScoreboardParser.State.QUEST_ACTIVE,
			"Slayer quest XP in the sidebar activates tracking before a boss spawns");
		check(SlayerScoreboardParser.parse("11/23/26 m12AB\nSlayer Quest\nCleanup\nPurse: 5") == SlayerScoreboardParser.State.INCOMPLETE,
			"the sidebar date row is not Slayer quest progress");
		check("1,200/2,000".equals(SlayerScoreboardParser.progress("11/23/26 m12AB\nSlayer Quest\nRevenant Horror IV\n(1,200/2,000) Combat XP")),
			"quest progress is read from the rows under the quest header, not the date row");
		check(SlayerScoreboardParser.progress("11/23/26 m12AB\nVillage\nPurse: 1,234") == null,
			"no quest header means no quest progress");
		check(SlayerScoreboardParser.parse("Slayer Quest\nRiftstalker Bloodfiend III\nNothing") == SlayerScoreboardParser.State.QUEST_ACTIVE,
			"the Rift vampire quest counts as an active quest");
		check(SlayerScoreboardParser.parse("Slayer Quest\n") == SlayerScoreboardParser.State.INCOMPLETE,
			"a partial sidebar snapshot does not look like an idle quest");
		check(SlayerScoreboardParser.parse("Village\nPurse: 1,234\nBits: 50")
			== SlayerScoreboardParser.State.NO_QUEST,
			"a usable sidebar without a Slayer header is classified as idle");

		SlayerQuestSidebarLifecycle lifecycle = new SlayerQuestSidebarLifecycle();
		check(lifecycle.observe(SlayerScoreboardParser.State.QUEST_ACTIVE).questActive(),
			"progress snapshot activates the lifecycle");
		check(lifecycle.observe(SlayerScoreboardParser.State.INCOMPLETE).questActive(),
			"transient empty or incomplete snapshots preserve the active quest");
		check(lifecycle.observe(SlayerScoreboardParser.State.NO_QUEST).questActive(),
			"one usable sidebar without the quest header is not enough to clear state");
		check(lifecycle.observe(SlayerScoreboardParser.State.INCOMPLETE).questActive(),
			"an incomplete poll resets the consecutive-idle count without clearing the quest");
		check(lifecycle.observe(SlayerScoreboardParser.State.NO_QUEST).questActive(),
			"idle snapshots must be consecutive");
		var idle = lifecycle.observe(SlayerScoreboardParser.State.NO_QUEST);
		check(!idle.questActive() && idle.questFailed(),
			"a repeated stable idle sidebar deactivates the tracker and ends the quest timer");

		lifecycle.onQuestStarted();
		var slain = lifecycle.observe(SlayerScoreboardParser.State.BOSS_SLAIN);
		check(slain.bossSlain() && !slain.questActive(),
			"the slain sidebar state records completion and immediately deactivates the HUD");
		check(!lifecycle.observe(SlayerScoreboardParser.State.BOSS_SLAIN).bossSlain()
			&& !lifecycle.onQuestCompleted().bossSlain(),
			"repeated sidebar and chat completion events are emitted only once");
		check(!lifecycle.observe(SlayerScoreboardParser.State.NO_QUEST).questFailed()
			&& !lifecycle.observe(SlayerScoreboardParser.State.QUEST_ACTIVE).questActive(),
			"idle snapshots after a slain boss do not create a second failure transition");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void equals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
