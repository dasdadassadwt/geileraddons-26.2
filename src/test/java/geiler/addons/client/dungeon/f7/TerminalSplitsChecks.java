package geiler.addons.client.dungeon.f7;

import java.util.List;

/** Offline checks for the Terminal Splits state machine (Odin's Terminal Times splits, real time only). */
public final class TerminalSplitsChecks {
	private TerminalSplitsChecks() {
	}

	public static void main(String[] arguments) {
		run();
	}

	public static void run() {
		TerminalSplits splits = new TerminalSplits();
		require(splits.observe("Steve completed a terminal! (1/7)", 500L).getSplit() == null,
			"nothing is timed before Goldor's phase starts");

		require(splits.observe("[BOSS] Goldor: Who dares trespass into my domain?", 1000L).getSplit() == null,
			"the Goldor line starts the phase and adds no split");
		TerminalSplits.Split first = splits.observe("Steve completed a terminal! (1/7)", 3000L).getSplit();
		require(first != null && first.getSectionSeconds() == 2.0f && first.getPhaseSeconds() == 2.0f,
			"the first terminal shows section and phase time");
		TerminalSplits.Split second = splits.observe("Alex activated a lever! (2/7)", 5000L).getSplit();
		require(second != null && second.getSectionSeconds() == 4.0f, "a lever counts like a terminal");

		require(splits.observe("The gate has been destroyed!", 6000L).getSplit() == null, "the gate line adds no split");
		TerminalSplits.Split last = splits.observe("Steve completed a device! (7/7)", 7000L).getSplit();
		require(last != null && last.getSectionSeconds() == 6.0f && last.getPhaseSeconds() == 6.0f,
			"the last task is timed from the section start");

		TerminalSplits.Split next = splits.observe("Steve completed a terminal! (1/7)", 9000L).getSplit();
		require(next != null && next.getSectionSeconds() == 2.0f && next.getPhaseSeconds() == 8.0f,
			"the section restarts after the gate and the final task, the phase keeps running");

		TerminalSplits.Summary summary = splits.observe("The Core entrance is opening!", 10000L).getSummary();
		require(summary != null && summary.getSectionTimes().equals(List.of(6.0f, 3.0f)) && summary.getTotalSeconds() == 9.0f,
			"the core opening closes the section and sums the phase");

		splits.observe("[BOSS] Goldor: Who dares trespass into my domain?", 20000L);
		splits.observe("Steve completed a terminal! (3/7)", 21000L);
		TerminalSplits.Split drop = splits.observe("Steve completed a terminal! (1/7)", 22000L).getSplit();
		require(drop != null && drop.getSectionSeconds() == 2.0f, "a lower count than before is timed, then restarts the section");
		TerminalSplits.Split afterDrop = splits.observe("Steve completed a terminal! (2/7)", 24000L).getSplit();
		require(afterDrop != null && afterDrop.getSectionSeconds() == 2.0f && afterDrop.getPhaseSeconds() == 4.0f,
			"the restarted section is measured from the drop");

		require(splits.observe("Some other message", 25000L).getSplit() == null, "unrelated chat is ignored");
		require(splits.observe("A player completed a terminal! (1/7)", 26000L).getSplit() == null,
			"names with spaces are not a player completion line");
		require("3.14".equals(TerminalSplits.seconds(3.14159f)) && "2.00".equals(TerminalSplits.seconds(2.0f)),
			"seconds print with two decimals regardless of locale");
		System.out.println("Terminal splits checks passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
