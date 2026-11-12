package geiler.addons.client.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Guards channel tab routing, inbound capture, and disconnect cleanup against config-only regressions. */
public final class ChatChannelMixinRegistrationChecks {
	private static final Path CLIENT_MIXIN_CONFIG = Path.of("src", "client", "resources", "geileraddons.client.mixins.json");
	private static final Path MOD_CONFIG = Path.of("src", "main", "resources", "fabric.mod.json");
	private static final Path MIXIN_SOURCE_DIRECTORY = Path.of("src", "client", "java", "geiler", "addons", "client", "mixin");
	private static final List<String> REQUIRED_MIXINS = List.of(
		"ChatScreenMixin",
		"ChatComponentChannelsMixin",
		"ChatChannelsDisconnectMixin");

	private ChatChannelMixinRegistrationChecks() { }

	public static void run() {
		try {
			JsonObject config = JsonParser.parseString(Files.readString(CLIENT_MIXIN_CONFIG, StandardCharsets.UTF_8))
				.getAsJsonObject();
			JsonObject modConfig = JsonParser.parseString(Files.readString(MOD_CONFIG, StandardCharsets.UTF_8))
				.getAsJsonObject();
			JsonArray clientMixins = config.getAsJsonArray("client");
			if (clientMixins == null) throw new AssertionError("client mixin list is missing");
			JsonArray registeredConfigs = modConfig.getAsJsonArray("mixins");
			boolean registeredForClient = registeredConfigs != null && registeredConfigs.asList().stream()
				.anyMatch(entry -> entry.isJsonObject()
					&& entry.getAsJsonObject().has("config")
					&& entry.getAsJsonObject().get("config").getAsString().equals("geileraddons.client.mixins.json")
					&& entry.getAsJsonObject().has("environment")
					&& entry.getAsJsonObject().get("environment").getAsString().equals("client"));
			if (!registeredForClient) throw new AssertionError("client mixin config is not registered in fabric.mod.json");
			for (String required : REQUIRED_MIXINS) {
				long registrations = clientMixins.asList().stream()
					.filter(entry -> entry.isJsonPrimitive() && entry.getAsString().equals(required)).count();
				if (registrations != 1) {
					throw new AssertionError(required + " must appear exactly once in the client mixin list; found " + registrations);
				}
				Path source = MIXIN_SOURCE_DIRECTORY.resolve(required + ".java");
				if (!Files.isRegularFile(source)) throw new AssertionError(required + " source is missing: " + source);
			}
		} catch (IOException exception) {
			throw new AssertionError("Could not read " + CLIENT_MIXIN_CONFIG, exception);
		}
	}
}
