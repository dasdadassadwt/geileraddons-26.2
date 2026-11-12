package geiler.addons.client;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.GameProvider;

import java.lang.reflect.Proxy;
import java.nio.file.Path;

/** Supplies Fabric's config-path contract to acceptance checks that run outside a game launch. */
public final class FabricCheckEnvironment {
	private FabricCheckEnvironment() { }

	public static void initializeConfigDirectory() {
		FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
		if (loader.tryGetGameProvider() != null) return;

		String configuredGameDir = System.getProperty("geileraddons.checkGameDir");
		Path gameDir = configuredGameDir == null || configuredGameDir.isBlank()
			? Path.of(System.getProperty("java.io.tmpdir"),
				"geileraddons-checks-" + Long.toUnsignedString(System.nanoTime()))
			: Path.of(configuredGameDir).toAbsolutePath().normalize();
		GameProvider provider = (GameProvider) Proxy.newProxyInstance(
			GameProvider.class.getClassLoader(),
			new Class<?>[]{GameProvider.class},
			(proxy, method, arguments) -> {
				if (method.getName().equals("getLaunchDirectory")) return gameDir;
				if (method.getName().equals("toString")) return "GeilerAddonsChecksGameProvider";
				if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
				if (method.getName().equals("equals")) return proxy == arguments[0];
				if (method.getReturnType() == boolean.class) return false;
				if (method.getReturnType() == int.class) return 0;
				if (method.getReturnType() == long.class) return 0L;
				return null;
			});
		loader.setGameProvider(provider);
	}
}
