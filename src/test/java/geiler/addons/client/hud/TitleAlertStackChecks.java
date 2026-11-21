package geiler.addons.client.hud;

/** Smoke checks the fixed subtitle adapter's blank-text and clear paths without booting Minecraft. */
public final class TitleAlertStackChecks {
	private TitleAlertStackChecks() { }

	public static void run() {
		TitleAlertStack stack = TitleAlertStack.INSTANCE;
		stack.submit("room.clear", " ", 40, 0xFFFFFFFF);
		stack.submitCountdown("invincibility.phoenix", " ", "4.0s", 4, 0xFFFFFFFF);
		stack.clearSource("room.clear");
		stack.clear();
	}
}
