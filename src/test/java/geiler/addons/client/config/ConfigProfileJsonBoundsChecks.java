package geiler.addons.client.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Focused allocation-bound checks for the streaming profile JSON preflight. */
public final class ConfigProfileJsonBoundsChecks {
	private ConfigProfileJsonBoundsChecks() { }

	public static void main(String[] args) throws Exception { run(); }

	public static void run() {
		try {
			ConfigProfileCodec.validateJsonBounds(flatArray(ConfigProfileCodec.MAX_JSON_NODES - 1));
			assertRejected(() -> ConfigProfileCodec.validateJsonBounds(flatArray(ConfigProfileCodec.MAX_JSON_NODES)),
				"a flat array one value above the node budget is rejected");
			ConfigProfileCodec.validateJsonBounds(flatObject((ConfigProfileCodec.MAX_JSON_TOKENS - 4) / 2));
			assertRejected(() -> ConfigProfileCodec.validateJsonBounds(flatObject((ConfigProfileCodec.MAX_JSON_TOKENS - 3) / 2 + 1)),
				"object names and values exceeding the token budget are rejected before the node budget");
			String atDepth = "[".repeat(ConfigProfileCodec.MAX_JSON_DEPTH) + "0" + "]".repeat(ConfigProfileCodec.MAX_JSON_DEPTH);
			ConfigProfileCodec.validateJsonBounds(bytes(atDepth));
			String overDepth = "[".repeat(ConfigProfileCodec.MAX_JSON_DEPTH + 1) + "0" + "]".repeat(ConfigProfileCodec.MAX_JSON_DEPTH + 1);
			assertRejected(() -> ConfigProfileCodec.validateJsonBounds(bytes(overDepth)), "nesting beyond the prior depth cap is rejected");
			assertRejected(() -> ConfigProfileCodec.validateJsonBounds(bytes("{} {}")), "trailing root values are rejected");
			assertRejected(() -> ConfigProfileCodec.validateJsonBounds(bytes("{\"x\":]")), "malformed container contents are rejected");

			byte[] oversizedMember = flatArray(ConfigProfileCodec.MAX_JSON_NODES);
			assertRejected(() -> ConfigProfileCodec.decode(archive(manifest(oversizedMember, false), oversizedMember)),
				"an over-budget JSON archive member is rejected before tree parsing");
			byte[] ordinaryMember = bytes("{}");
			assertRejected(() -> ConfigProfileCodec.decode(archive(manifest(ordinaryMember, true), ordinaryMember)),
				"an over-budget manifest is rejected before manifest tree parsing");
		} catch (IOException failure) {
			throw new IllegalStateException("A valid within-budget profile JSON fixture was rejected.", failure);
		}
	}

	private static byte[] flatArray(int valueCount) {
		StringBuilder json = new StringBuilder(valueCount * 2 + 2).append('[');
		for (int i = 0; i < valueCount; i++) {
			if (i != 0) json.append(',');
			json.append('0');
		}
		return bytes(json.append(']').toString());
	}

	private static byte[] flatObject(int fieldCount) {
		StringBuilder json = new StringBuilder(fieldCount * 12 + 2).append('{');
		for (int i = 0; i < fieldCount; i++) {
			if (i != 0) json.append(',');
			json.append("\"k").append(i).append("\":0");
		}
		return bytes(json.append('}').toString());
	}

	private static byte[] manifest(byte[] member, boolean addOversizedUnknownArray) {
		String entry = "{\"path\":\"config.json\",\"size\":" + member.length + ",\"sha256\":\"" + sha256(member) + "\"}";
		String json = "{\"format\":\"geileraddons-config\",\"version\":1,\"createdAt\":\"2026-10-09T00:00:00Z\","
			+ "\"totalBytes\":" + member.length + ",\"entries\":[" + entry + "]";
		if (addOversizedUnknownArray) json += ",\"padding\":" + new String(flatArray(ConfigProfileCodec.MAX_JSON_NODES), StandardCharsets.UTF_8);
		return bytes(json + "}");
	}

	private static byte[] archive(byte[] manifest, byte[] member) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(output)) {
			put(zip, "manifest.json", manifest);
			put(zip, "config.json", member);
		}
		return output.toByteArray();
	}

	private static void put(ZipOutputStream zip, String name, byte[] contents) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(contents);
		zip.closeEntry();
	}

	private static String sha256(byte[] data) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}

	private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

	private static void assertRejected(IoAction action, String message) {
		try { action.run(); }
		catch (IOException expected) { return; }
		throw new IllegalStateException(message);
	}

	@FunctionalInterface private interface IoAction { void run() throws IOException; }
}
