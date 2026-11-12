package geiler.addons.client.module.impl;

import geiler.addons.client.config.GeilerAddonsLog;
import geiler.addons.client.module.Category;
import geiler.addons.client.module.DebugState;
import geiler.addons.client.module.Module;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Focused checks for Debug module identity, defaults, and Java-visible singleton access. */
public final class DebugModuleChecks {
	private DebugModuleChecks() { }

	public static void main(String[] arguments) {
		run();
		System.out.println("Debug module checks passed");
	}

	public static void run() {
		DebugModule module = DebugModule.INSTANCE;
		check(module == DebugModule.INSTANCE, "the Java-visible singleton remains stable");

		Module javaApi = DebugModule.INSTANCE;
		check(javaApi == module, "Java callers can use DebugModule as a Module");
		try {
			Field instance = DebugModule.class.getField("INSTANCE");
			int modifiers = instance.getModifiers();
			check(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers),
				"the Kotlin singleton remains a public static final Java field");
			check(instance.get(null) == module, "the public singleton field resolves to the registered instance");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Java callers can reflect the public singleton field", error);
		}

		check(module.name().equals("Debug") && module.configName().equals("Debug"),
			"the module and persisted config identity remain stable");
		check(module.description().equals("Enables diagnostic logs and developer diagnostics across the mod."),
			"the module description is preserved");
		check(module.category() == Category.DEV && !module.defaultEnabled(),
			"Debug remains a disabled-by-default Dev module");
		checkModuleLifecycle(module);
		checkDebugStateInterop();
		check(!module.hasSettings() && module.colorSettings().isEmpty() && module.numberSettings().isEmpty()
			&& module.booleanSettings().isEmpty() && module.choiceSettings().isEmpty()
			&& module.textSettings().isEmpty() && module.actions().isEmpty(),
			"Debug remains a module switch without extra settings or actions");
	}

	private static void checkModuleLifecycle(DebugModule module) {
		boolean originalEnabled = module.isEnabled();
		boolean originalDebugState = DebugState.enabled();
		try {
			module.setEnabled(false);
			DebugState.setEnabled(false);

			module.setEnabled(true);
			check(module.isEnabled() && DebugState.enabled(),
				"enabling Debug sets the optional-diagnostics gate");
			awaitDiagnosticWriter();
			check(GeilerAddonsLog.sessionDirectory() != null,
				"enabling Debug starts a diagnostics session");

			module.setEnabled(false);
			check(!module.isEnabled() && !DebugState.enabled(),
				"disabling Debug clears the gate after a real module transition");
		} finally {
			module.setEnabled(originalEnabled);
			DebugState.setEnabled(originalDebugState);
		}
	}

	private static void awaitDiagnosticWriter() {
		long deadline = System.nanoTime() + 2_000_000_000L;
		while (!GeilerAddonsLog.enabled() && GeilerAddonsLog.failure() == null
			&& System.nanoTime() - deadline < 0L) {
			try {
				Thread.sleep(10L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new AssertionError("interrupted while waiting for the diagnostic writer", interrupted);
			}
		}
		check(GeilerAddonsLog.enabled(), "enabling Debug starts the diagnostic writer");
	}

	private static void checkDebugStateInterop() {
		boolean original = DebugState.enabled();
		try {
			DebugState.setEnabled(!original);
			check(DebugState.enabled() == !original, "Java callers can update the Kotlin debug-state gate");
		} finally {
			DebugState.setEnabled(original);
		}
		check(DebugState.enabled() == original, "the debug-state gate is restored after the interop check");
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
	}
}
