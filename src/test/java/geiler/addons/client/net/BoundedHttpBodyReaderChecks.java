package geiler.addons.client.net;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;

/** Focused checks for the bounded HTTP reader's Java entrypoint and input limits. */
public final class BoundedHttpBodyReaderChecks {
	private BoundedHttpBodyReaderChecks() { }

	public static void run() throws Exception {
		Method send = BoundedHttpBodyReader.class.getMethod("send", HttpClient.class, HttpRequest.class,
			int.class, Duration.class);
		Set<Class<?>> declared = Set.copyOf(Arrays.asList(send.getExceptionTypes()));
		check(Modifier.isStatic(send.getModifiers())
			&& declared.contains(java.io.IOException.class)
			&& declared.contains(InterruptedException.class),
			"send keeps a Java static entrypoint and checked exception contract");

		HttpClient client = HttpClient.newHttpClient();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/unused")).GET().build();
		expectInvalid(() -> BoundedHttpBodyReader.send(null, request, 0, Duration.ofSeconds(1)));
		expectInvalid(() -> BoundedHttpBodyReader.send(client, null, 0, Duration.ofSeconds(1)));
		expectInvalid(() -> BoundedHttpBodyReader.send(client, request, -1, Duration.ofSeconds(1)));
		expectInvalid(() -> BoundedHttpBodyReader.send(client, request, 0, null));
		expectInvalid(() -> BoundedHttpBodyReader.send(client, request, 0, Duration.ZERO));
		expectInvalid(() -> BoundedHttpBodyReader.send(client, request, 0, Duration.ofNanos(-1)));
	}

	private static void expectInvalid(CheckedCall call) {
		try {
			call.run();
		} catch (IllegalArgumentException expected) {
			return;
		} catch (Exception unexpected) {
			throw new AssertionError("invalid body limits should fail before a request is sent", unexpected);
		}
		throw new AssertionError("invalid body limits should be rejected");
	}

	@FunctionalInterface
	private interface CheckedCall {
		void run() throws Exception;
	}

	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError("BoundedHttpBodyReaderChecks: " + message);
	}
}
