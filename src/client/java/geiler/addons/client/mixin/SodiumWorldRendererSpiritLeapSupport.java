package geiler.addons.client.mixin;

import net.fabricmc.loader.api.FabricLoader;

import java.util.Set;

/** Exact-version gate for the version-sensitive Sodium Spirit Leap renderer hooks. */
public final class SodiumWorldRendererSpiritLeapSupport {
	public static final String MOD_ID = "sodium";
	public static final String SUPPORTED_VERSION = "0.9.2+mc26.2";
	public static final String VERIFIED_0_9_1_VERSION = "0.9.1+mc26.2";
	private static final Set<String> SUPPORTED_VERSIONS = Set.of(SUPPORTED_VERSION, VERIFIED_0_9_1_VERSION);
	public static final String WORLD_RENDERER_MIXIN =
		"geiler.addons.client.mixin.SodiumWorldRendererSpiritLeapMixin";
	public static final String TERRAIN_RENDER_PASS_MIXIN =
		"geiler.addons.client.mixin.SodiumTerrainRenderPassSpiritLeapMixin";

	private static volatile String installedVersion = "not-checked";
	private static volatile boolean versionChecked;
	private static volatile boolean decisionRecorded;
	private static volatile boolean hookApplied;

	private SodiumWorldRendererSpiritLeapSupport() { }

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
		return SUPPORTED_VERSIONS.contains(version);
	}

	public static boolean isHookMixin(String mixinClassName) {
		return WORLD_RENDERER_MIXIN.equals(mixinClassName)
			|| TERRAIN_RENDER_PASS_MIXIN.equals(mixinClassName)
			|| mixinClassName != null && (mixinClassName.endsWith(".SodiumWorldRendererSpiritLeapMixin")
				|| mixinClassName.endsWith(".SodiumTerrainRenderPassSpiritLeapMixin"));
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
