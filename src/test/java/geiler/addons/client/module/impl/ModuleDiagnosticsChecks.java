package geiler.addons.client.module.impl;

import geiler.addons.client.module.Category;

import java.util.ArrayList;
import java.util.List;

/** Verifies the shared diagnostics helper: bookends, per-tick budget, suppression count, and heartbeat. */
public final class ModuleDiagnosticsChecks {
	private ModuleDiagnosticsChecks() { }

	public static void run() {
		List<String> lines = new ArrayList<>();
		List<Boolean> activeCalls = new ArrayList<>();
		ModuleDiagnostics diagnostics = new ModuleDiagnostics(Category.DEV, () -> "Sample", 2, 3L, 40,
			(category, module, tick, line) -> lines.add(module + "@" + tick + " " + line),
			(category, module, on) -> activeCalls.add(on));

		diagnostics.event("before enable");
		check(lines.isEmpty(), "events before enable are dropped");
		diagnostics.enable("a=1");
		check(lines.size() == 1 && lines.get(0).equals("Sample@0 EVENT FEATURE ENABLED a=1"), "enable writes the bookend");
		check(activeCalls.equals(List.of(true)), "enable activates the module log source");

		diagnostics.event("one");
		diagnostics.event("two");
		diagnostics.event("three");
		diagnostics.event("four");
		check(lines.size() == 2, "only maxEventsPerTick lines per tick are written, got " + lines);
		diagnostics.advance();
		diagnostics.event("next tick");
		check(lines.get(lines.size() - 1).equals("Sample@1 EVENT next tick"), "budget resets on the next tick");

		lines.clear();
		diagnostics.status(suppressed -> "suppressed=" + suppressed);
		check(lines.equals(List.of("Sample@1 STATUS suppressed=3")), "first status reports suppressed events: " + lines);
		diagnostics.advance();
		diagnostics.status(suppressed -> "again");
		check(lines.size() == 1, "status honors the heartbeat interval");
		diagnostics.advance();
		diagnostics.advance();
		diagnostics.status(suppressed -> "suppressed=" + suppressed + " very long text that gets cut");
		check(lines.size() == 2 && lines.get(1).length() <= "Sample@3 ".length() + 40,
			"lines are truncated to maxLineChars");

		checkStatusOnChange();

		lines.clear();
		diagnostics.disable();
		check(lines.get(0).equals("Sample@4 EVENT FEATURE DISABLED"), "disable writes the closing bookend even though budget remains");
		check(activeCalls.equals(List.of(true, false)), "disable releases the log source");
		diagnostics.event("after disable");
		check(lines.size() == 1, "events after disable are dropped");
	}

	/** statusOnChange builds its text lazily and writes on change after the gap, or at the heartbeat. */
	private static void checkStatusOnChange() {
		List<String> lines = new ArrayList<>();
		ModuleDiagnostics d = new ModuleDiagnostics(Category.DEV, () -> "Lazy", 2, 10L, 200,
			(category, module, tick, line) -> lines.add(tick + " " + line), (category, module, on) -> { });
		int[] built = {0};
		String[] state = {"a"};
		d.statusOnChange(3L, () -> { built[0]++; return state[0]; });
		check(lines.isEmpty() && built[0] == 0, "statusOnChange does nothing before enable");
		d.enable("x");
		lines.clear();
		d.statusOnChange(3L, () -> { built[0]++; return state[0]; });
		check(lines.equals(List.of("0 STATUS a suppressedEvents=0")), "first call writes: " + lines);
		state[0] = "b";
		d.advance();
		built[0] = 0;
		d.statusOnChange(3L, () -> { built[0]++; return state[0]; });
		check(built[0] == 0 && lines.size() == 1, "inside the minimum gap the text is never built");
		d.advance();
		d.advance();
		d.statusOnChange(3L, () -> { built[0]++; return state[0]; });
		check(lines.size() == 2 && lines.get(1).equals("3 STATUS b suppressedEvents=0"), "a change after the gap writes: " + lines);
		for (int i = 0; i < 4; i++) d.advance();
		d.statusOnChange(3L, () -> state[0]);
		check(lines.size() == 2, "an unchanged state stays quiet before the heartbeat");
		for (int i = 0; i < 6; i++) d.advance();
		d.statusOnChange(3L, () -> state[0]);
		check(lines.size() == 3 && lines.get(2).startsWith("13 STATUS b"), "an unchanged state still writes at the heartbeat: " + lines);
		lines.clear();
		int[] counter = {0};
		for (int i = 0; i < 12; i++) d.advance();
		d.statusOnChangeBy(3L, () -> state[0], () -> state[0] + " n=" + (++counter[0]));
		check(lines.size() == 1 && lines.get(0).equals("25 STATUS b n=1 suppressedEvents=0"), "a changed counter alone is not a change: " + lines);
		for (int i = 0; i < 4; i++) d.advance();
		d.statusOnChangeBy(3L, () -> state[0], () -> state[0] + " n=" + (++counter[0]));
		check(lines.size() == 1, "counters in the written line do not trigger an early write: " + lines);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
