package geiler.addons.client.inventory;

/** Direct-JDK acceptance checks for custom hotbar selection screen and lifecycle gating. */
public final class PlayerHotbarSelectionGateChecks {
	private PlayerHotbarSelectionGateChecks() { }

	public static void main(String[] args) {
		check(PlayerHotbarSelectionGate.allowsSelection(true, true, false),
			"an in-world key press remains available when no screen is open");
		check(!PlayerHotbarSelectionGate.allowsSelection(true, true, true),
			"container, inventory, chat, and binding-capture screens keep their key presses");
		check(!PlayerHotbarSelectionGate.allowsSelection(true, false, false),
			"a missing player or world cannot select a hotbar slot");
		check(!PlayerHotbarSelectionGate.allowsSelection(false, true, false),
			"release and repeat actions do not select a hotbar slot");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
