package geiler.addons.client.config;

import geiler.addons.client.module.Category;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Focused no-I/O checks for diagnostic writer recovery scheduling. */
public final class GeilerAddonsLogRecoveryChecks {
	private GeilerAddonsLogRecoveryChecks() { }

	public static void run() {
		checkJavaFacade();
		GeilerAddonsLogRecoveryBackoff backoff = new GeilerAddonsLogRecoveryBackoff();
		long failedAt = 1_000_000L;
		long delay = GeilerAddonsLogRecoveryBackoff.INITIAL_DELAY_NANOS;
		check(backoff.shouldStartWriter(false, true, false, failedAt),
			"a missing writer without a recorded failure starts immediately");
		check(backoff.shouldStartWriter(true, true, true, failedAt),
			"explicitly changing the diagnostic source allows an immediate restart");

		backoff.failedAt(failedAt);
		for (int attempt = 0; attempt < 12; attempt++) {
			check(!backoff.shouldStartWriter(false, true, true, failedAt)
				&& !backoff.shouldStartWriter(false, true, true, failedAt + delay - 1L),
				"a failed writer cannot restart on every tick before its backoff expires");
			long retryAt = failedAt + delay;
			check(backoff.isDueAt(retryAt)
				&& backoff.shouldStartWriter(false, true, true, retryAt),
				"an unchanged active module can restart its writer after the scheduled delay");
			if (attempt < 11) {
				check(backoff.beginRetryAt(retryAt) && !backoff.beginRetryAt(retryAt),
					"only one background recovery attempt is scheduled when the retry deadline is due");
				backoff.failedAt(retryAt);
				backoff.finishRetry();
			}
			failedAt = retryAt;
			delay = Math.min(GeilerAddonsLogRecoveryBackoff.MAX_DELAY_NANOS, delay * 2L);
		}
		check(delay == GeilerAddonsLogRecoveryBackoff.MAX_DELAY_NANOS,
			"persistent failures cap the retry interval instead of growing without bound");
		backoff.recovered();
		check(!backoff.isDueAt(failedAt + GeilerAddonsLogRecoveryBackoff.MAX_DELAY_NANOS)
			&& backoff.shouldStartWriter(false, true, false, failedAt),
			"successful recovery clears the pending schedule and resets immediate availability");
	}

	private static void checkJavaFacade() {
		checkMethod("start", void.class, true);
		checkMethod("setModuleDiagnosticsEnabled", void.class, true,
			Category.class, String.class, boolean.class);
		checkMethod("stop", void.class, true);
		checkMethod("close", void.class, false);
		checkMethod("enabled", boolean.class, false);
		checkMethod("failure", String.class, false);
		checkMethod("sessionDirectory", Path.class, false);
		checkMethod("path", Path.class, false, Category.class, String.class);
		checkMethod("write", void.class, false, Category.class, String.class, long.class, String.class);
		checkMethod("writeWithCompletion", boolean.class, false, Category.class, String.class,
			long.class, String.class, Consumer.class);
	}

	private static void checkMethod(String name, Class<?> returnType, boolean synchronizedMethod,
		Class<?>... parameterTypes) {
		try {
			Method method = GeilerAddonsLog.class.getDeclaredMethod(name, parameterTypes);
			int modifiers = method.getModifiers();
			check(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
				&& Modifier.isSynchronized(modifiers) == synchronizedMethod
				&& method.getReturnType() == returnType,
				"diagnostic log Java facade retains the " + name + " method ABI");
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("diagnostic log Java facade lost " + name, error);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
