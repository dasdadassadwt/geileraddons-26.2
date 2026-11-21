package geiler.addons.client.config;

/** Offline checks for the lag-spike watchdog's stack summary. */
public final class SpikeWatchChecks {
	private SpikeWatchChecks() { }

	public static void run() {
		check(SpikeWatch.describe(new StackTraceElement[0]).equals("stack=empty"), "an empty stack is reported as empty");

		StackTraceElement[] frames = new StackTraceElement[30];
		for (int i = 0; i < frames.length; i++) {
			String owner = i == 20 ? "geiler.addons.client.module.impl.PlayerNamesModule" : "net.minecraft.client.Minecraft" + i;
			frames[i] = new StackTraceElement(owner, "work" + i, "File.java", 100 + i);
		}
		String text = SpikeWatch.describe(frames);
		check(text.startsWith("top=Minecraft0.work0:100 < Minecraft1.work1:101"), "the top frames come first, class names shortened");
		check(text.contains(" mod=PlayerNamesModule.work20:120"), "a mod frame deep in the stack is listed separately");
		check(!text.contains("Minecraft15"), "frames past the top ten that are not the mod's are left out");

		StackTraceElement[] huge = new StackTraceElement[200];
		for (int i = 0; i < huge.length; i++) {
			huge[i] = new StackTraceElement("geiler.addons.client.module.impl.VeryLongModuleNameForTheStackLimit" + i, "someLongMethodName" + i, "File.java", i + 1);
		}
		check(SpikeWatch.describe(huge).length() <= 900, "one stall line stays within the line limit");
		check(SpikeWatch.THRESHOLD_MS >= 100L, "only real stalls are reported, not ordinary slow ticks");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
