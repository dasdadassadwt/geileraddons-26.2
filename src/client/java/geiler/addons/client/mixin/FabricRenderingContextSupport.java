package geiler.addons.client.mixin;

import net.fabricmc.loader.api.FabricLoader;

/** Fail-closed version gate shared by the mixin plugin and Leap POV renderer. */
public final class FabricRenderingContextSupport {
	public static final String FABRIC_RENDERING_MOD_ID = "fabric-rendering-v1";
	public static final String SUPPORTED_VERSION = "25.3.3+515ac5339e";
	public static final String ACCESSOR_MIXIN =
		"geiler.addons.client.mixin.LevelExtractorSpiritLeapContextAccessor";

	private static volatile String installedVersion = "not-checked";
	private static volatile boolean versionChecked;
	private static volatile boolean accessorDecisionRecorded;
	private static volatile boolean accessorApplied;

	private FabricRenderingContextSupport() { }

	static void inspectInstalledVersion() {
		String version;
		try {
			version = FabricLoader.getInstance().getModContainer(FABRIC_RENDERING_MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("absent");
		} catch (RuntimeException | LinkageError unavailable) {
			version = "unavailable";
		}
		recordInstalledVersion(version);
	}

	private static void recordInstalledVersion(String version) {
		installedVersion = version == null || version.isBlank() ? "absent" : version;
		versionChecked = true;
	}

	public static boolean isAccessorAvailable() {
		if (!versionChecked) inspectInstalledVersion();
		return supportsVersion(installedVersion) && accessorDecisionRecorded && accessorApplied;
	}

	public static String installedVersion() {
		if (!versionChecked) inspectInstalledVersion();
		return installedVersion;
	}

	public static String diagnosticStatus() {
		if (!versionChecked) inspectInstalledVersion();
		if (!supportsVersion(installedVersion)) return "blocked-unsupported-version";
		if (!accessorDecisionRecorded) return "blocked-mixin-decision-unavailable";
		return accessorApplied ? "applied" : "blocked-mixin";
	}

	public static boolean supportsVersion(String version) {
		return SUPPORTED_VERSION.equals(version);
	}

	static boolean isAccessorMixin(String mixinClassName) {
		return ACCESSOR_MIXIN.equals(mixinClassName)
			|| mixinClassName != null && mixinClassName.endsWith(".LevelExtractorSpiritLeapContextAccessor");
	}

	public static boolean shouldApplyAccessor(String mixinClassName, String version) {
		return !isAccessorMixin(mixinClassName) || supportsVersion(version);
	}

	static void recordAccessorDecision(boolean applied) {
		accessorDecisionRecorded = true;
		accessorApplied = applied;
	}
}
