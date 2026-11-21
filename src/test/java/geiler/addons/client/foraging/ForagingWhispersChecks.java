package geiler.addons.client.foraging;

import java.util.Arrays;
import java.util.List;

/** Offline checks for reading Forest and Desert Whispers from the tab list and tracking their session gain. */
public final class ForagingWhispersChecks {
	private static final long SECOND = 1_000_000_000L;

	private ForagingWhispersChecks() { }

	public static void run() {
		ForagingWhispers.Reading both = ForagingWhispers.read(List.of("Info", "§e§lForest Whispers: §3§l12,345", "Desert Whispers: 6,789", "Area: Galatea"));
		check(both != null && both.getForest() != null && both.getForest() == 12_345L && both.getDesert() != null && both.getDesert() == 6_789L,
			"both whisper rows are read, formatting stripped");
		ForagingWhispers.Reading forestOnly = ForagingWhispers.read(Arrays.asList("Forest Whispers: 5", null));
		check(forestOnly != null && forestOnly.getForest() == 5L && forestOnly.getDesert() == null, "a missing row stays unknown, not zero");
		check(ForagingWhispers.read(List.of("Powders:", "Mithril: 5")) == null && ForagingWhispers.read(List.of()) == null
			&& ForagingWhispers.read(null) == null, "no whisper row gives no reading");
		check(ForagingWhispers.read(List.of("Forest Whispers: lots")) == null, "an unreadable amount gives no reading");

		ForagingWhispers.Session session = new ForagingWhispers.Session();
		session.observe(new ForagingWhispers.Reading(100L, 50L), 0L);
		session.observe(new ForagingWhispers.Reading(160L, 50L), 10 * SECOND);
		check(session.gainForest() == 60L && session.gainDesert() == 0L, "gain is the growth since the first reading");
		session.observe(new ForagingWhispers.Reading(20L, 50L), 11 * SECOND);
		check(session.gainForest() == 60L, "spending whispers is never counted as a loss");
		session.observe(new ForagingWhispers.Reading(50L, 80L), 12 * SECOND);
		check(session.gainForest() == 90L && session.gainDesert() == 30L, "gain continues from the lowered reading");
		check(session.perHour(90L, 3_600L * SECOND) == 90L && session.perHour(90L, 2 * SECOND) == 0L,
			"per hour scales the gain by elapsed time and reads zero in the first seconds");
		session.observe(new ForagingWhispers.Reading(null, 80L), 13 * SECOND);
		session.observe(new ForagingWhispers.Reading(500L, 80L), 14 * SECOND);
		check(session.gainForest() == 90L, "a gap in one reading does not turn the next value into gain");
		session.reset();
		check(session.gainForest() == 0L && session.gainDesert() == 0L, "reset clears the session");

		List<String> lines = ForagingWhispers.lines(new ForagingWhispers.Reading(1_000L, null), new ForagingWhispers.Session(), 0L, true, true);
		check(lines.size() == 1 && lines.get(0).contains("Forest") && lines.get(0).contains("1,000"), "only whispers that are known are listed");
		check(ForagingWhispers.lines(new ForagingWhispers.Reading(1L, 2L), new ForagingWhispers.Session(), 0L, false, false).stream()
			.noneMatch(l -> l.contains("(")), "gain and rate can be switched off");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
