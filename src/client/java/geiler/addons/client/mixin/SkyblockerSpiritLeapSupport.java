package geiler.addons.client.mixin;

import net.fabricmc.loader.api.FabricLoader;

/** Exact-version gate for suppressing Skyblocker's private-render callbacks in Leap POV feeds. */
public final class SkyblockerSpiritLeapSupport {
	public static final String MOD_ID = "skyblocker";
	public static final String SUPPORTED_VERSION = "6.10.4+26.2";
	public static final String RENDER_HELPER_MIXIN =
		"geiler.addons.client.mixin.SkyblockerRenderHelperSpiritLeapMixin";

	private static volatile String installedVersion = "not-checked";
	private static volatile boolean versionChecked;
	private static volatile boolean decisionRecorded;
	private static volatile boolean hookApplied;

	private SkyblockerSpiritLeapSupport() { }

	static void inspectInstalledVersion() {
		String version;
		try {
			version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("absent");
		} catch (RuntimeException | LinkageError unavailable) {
			version = "unavailable";
		}
		installedVersion = version == null || version.isBlank() ? "absent" : version;
		versionChecked = true;
	}

	public static String installedVersion() {
		if (!versionChecked) inspectInstalledVersion();
		return installedVersion;
	}

	public static boolean supportsVersion(String version) {
		return SUPPORTED_VERSION.equals(version);
	}

	public static boolean isHookMixin(String mixinClassName) {
		return RENDER_HELPER_MIXIN.equals(mixinClassName)
			|| mixinClassName != null && mixinClassName.endsWith(".SkyblockerRenderHelperSpiritLeapMixin");
	}

	public static boolean shouldApplyHook(String mixinClassName, String version) {
		return !isHookMixin(mixinClassName) || supportsVersion(version);
	}

	static void recordHookDecision(boolean applied) {
		decisionRecorded = true;
		hookApplied = applied;
	}

	public static boolean isHookApplied() {
		if (!versionChecked) inspectInstalledVersion();
		return supportsVersion(installedVersion) && decisionRecorded && hookApplied;
	}

	public static String diagnosticStatus() {
		if (!versionChecked) inspectInstalledVersion();
		if ("absent".equals(installedVersion)) return "absent";
		if (!supportsVersion(installedVersion)) return "blocked-version";
		if (!decisionRecorded) return "blocked-decision-unavailable";
		return hookApplied ? "applied" : "blocked-mixin";
	}
}
