package geiler.addons.client.chat;

import java.lang.reflect.Modifier;

/** Focused checks for client-observed TPS estimates and explicit incomplete-window states. */
public final class ChatTpsTrackerChecks {
	private static final long SECOND = 1_000_000_000L;

	private ChatTpsTrackerChecks() { }

	public static void main(String[] args) { run(); }

	public static void run() {
		javaInteropRemainsStable();
		emptyStateExplainsUnavailableMetrics();
		latestEstimateAndTenSecondCoverage();
		rollingAverageUsesLatestTenSeconds();
		oneMinuteRequiresFullCoverage();
		staleAndSparseSamplesAreUnavailable();
		resetsOnGameTimeDiscontinuities();
		shortIntervalsAccumulateBeforeEstimation();
		unchangedGameTimeResetsObservationSequence();
	}

	private static void javaInteropRemainsStable() {
		try {
			var record = ChatTpsTracker.class.getMethod("record", long.class, long.class);
			var reset = ChatTpsTracker.class.getMethod("reset");
			var metrics = ChatTpsTracker.class.getMethod("metrics", long.class);
			check(Modifier.isPublic(record.getModifiers()) && Modifier.isStatic(record.getModifiers())
				&& record.getReturnType() == void.class,
				"packet callback keeps the public static Java record method");
			check(Modifier.isPublic(reset.getModifiers()) && Modifier.isStatic(reset.getModifiers())
				&& reset.getReturnType() == void.class,
				"connection lifecycle keeps the public static Java reset method");
			check(Modifier.isPublic(metrics.getModifiers()) && Modifier.isStatic(metrics.getModifiers())
				&& metrics.getReturnType() == ChatTpsTracker.Metrics.class,
				"chat replies keep the public static Java metrics method");
			var recordType = ChatTpsTracker.Metrics.class;
			check(recordType.isRecord(), "TPS metrics preserve the Java record ABI");
			var components = recordType.getRecordComponents();
			check(components.length == 6
				&& components[0].getName().equals("currentTps")
				&& components[1].getName().equals("tenSecondTps")
				&& components[2].getName().equals("oneMinuteTps")
				&& components[3].getName().equals("currentUnavailableReason")
				&& components[4].getName().equals("tenSecondUnavailableReason")
				&& components[5].getName().equals("oneMinuteUnavailableReason"),
				"TPS metric record components retain their names and order");
			check(components[0].getType() == Double.class && components[1].getType() == Double.class
				&& components[2].getType() == Double.class,
				"unavailable TPS values remain nullable boxed doubles");
			var format = recordType.getMethod("format");
			check(Modifier.isPublic(format.getModifiers()) && format.getReturnType() == String.class,
				"TPS metrics keep their Java formatting method");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("TPS tracker Java entrypoint changed", exception);
		}
	}

	private static void emptyStateExplainsUnavailableMetrics() {
		ChatTpsTracker.reset();
		var metrics = ChatTpsTracker.metrics(0L);
		check(metrics.currentTps() == null && metrics.tenSecondTps() == null && metrics.oneMinuteTps() == null,
			"empty tracker has no inferred values");
		String formatted = metrics.format();
		check(formatted.contains("current N/A (waiting for samples)"), "current N/A explains missing samples");
		check(formatted.contains("10s N/A (need full 10s coverage)"), "10s N/A explains coverage requirement");
		check(formatted.contains("1m N/A (need full 60s coverage)"), "1m N/A explains coverage requirement");
	}

	private static void latestEstimateAndTenSecondCoverage() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		ChatTpsTracker.record(20L, SECOND);
		var metrics = ChatTpsTracker.metrics(SECOND);
		equals(20.0, metrics.currentTps(), "latest estimate uses observed game-time delta");
		check(metrics.tenSecondTps() == null && metrics.oneMinuteTps() == null,
			"one interval cannot stand in for uncovered windows");
	}

	private static void rollingAverageUsesLatestTenSeconds() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		for (int second = 1; second < 9; second++) {
			ChatTpsTracker.record(second * 20L, second * SECOND);
		}
		ChatTpsTracker.record(170L, 9L * SECOND);
		var atNine = ChatTpsTracker.metrics(9L * SECOND);
		equals(10.0, atNine.currentTps(), "current value reports the latest interval");
		check(atNine.tenSecondTps() == null, "nine seconds of samples do not cover a ten second window");

		ChatTpsTracker.record(190L, 10L * SECOND);
		var atTen = ChatTpsTracker.metrics(10L * SECOND);
		equals(20.0, atTen.currentTps(), "current value reports the latest interval");
		equals(19.0, atTen.tenSecondTps(), "10s value aggregates the complete trailing window");
		check(atTen.oneMinuteTps() == null, "minute remains unavailable before full minute coverage");

		ChatTpsTracker.record(210L, 11L * SECOND);
		var atEleven = ChatTpsTracker.metrics(11L * SECOND);
		equals(20.0, atEleven.currentTps(), "current estimate updates to the latest interval");
		equals(19.0, atEleven.tenSecondTps(), "rolling 10s window advances with the latest sample");
	}

	private static void oneMinuteRequiresFullCoverage() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		for (int second = 1; second <= 60; second++) {
			ChatTpsTracker.record(second * 20L, second * SECOND);
		}
		var metrics = ChatTpsTracker.metrics(60L * SECOND);
		equals(20.0, metrics.tenSecondTps(), "10s is available when its whole window is sampled");
		equals(20.0, metrics.oneMinuteTps(), "1m is available after a fully covered minute");
	}

	private static void staleAndSparseSamplesAreUnavailable() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		ChatTpsTracker.record(20L, SECOND);
		var stale = ChatTpsTracker.metrics(7L * SECOND);
		check(stale.currentTps() == null && stale.tenSecondTps() == null && stale.oneMinuteTps() == null,
			"stale observations do not appear current");
		check(stale.format().contains("stale samples"), "stale N/A carries a reason");

		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		ChatTpsTracker.record(120L, 6L * SECOND);
		check(ChatTpsTracker.metrics(6L * SECOND).currentTps() == null,
			"a gap beyond the accepted bound resets the observation sequence");
	}

	private static void resetsOnGameTimeDiscontinuities() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(100L, 0L);
		ChatTpsTracker.record(120L, SECOND);
		ChatTpsTracker.record(1L, 2L * SECOND);
		check(ChatTpsTracker.metrics(2L * SECOND).currentTps() == null,
			"backward game time resets prior samples");
		ChatTpsTracker.record(21L, 3L * SECOND);
		equals(20.0, ChatTpsTracker.metrics(3L * SECOND).currentTps(), "sampling resumes after the reset");

		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		ChatTpsTracker.record(100L, 250_000_000L);
		check(ChatTpsTracker.metrics(250_000_000L).currentTps() == null,
			"implausible forward game-time jump resets prior samples");
		ChatTpsTracker.record(120L, 1_250_000_000L);
		equals(20.0, ChatTpsTracker.metrics(1_250_000_000L).currentTps(), "sampling resumes after a forward jump");
	}

	private static void shortIntervalsAccumulateBeforeEstimation() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(0L, 0L);
		ChatTpsTracker.record(2L, 100_000_000L);
		check(ChatTpsTracker.metrics(100_000_000L).currentTps() == null,
			"a too-short interval does not create a noisy current estimate");
		ChatTpsTracker.record(10L, 500_000_000L);
		equals(20.0, ChatTpsTracker.metrics(500_000_000L).currentTps(),
			"short packet intervals accumulate from the original anchor");
	}

	private static void unchangedGameTimeResetsObservationSequence() {
		ChatTpsTracker.reset();
		ChatTpsTracker.record(20L, 0L);
		ChatTpsTracker.record(20L, SECOND);
		check(ChatTpsTracker.metrics(SECOND).currentTps() == null,
			"unchanged game time resets the interval instead of reporting fabricated zero TPS");
		ChatTpsTracker.record(40L, 2L * SECOND);
		equals(20.0, ChatTpsTracker.metrics(2L * SECOND).currentTps(),
			"sampling resumes after a strictly increasing game-time interval");
	}

	private static void equals(double expected, Double actual, String message) {
		if (actual == null || Math.abs(expected - actual) > 0.0001) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
