package geiler.addons.client.update;

/**
 * Offline checks for the update UI's untrusted input handling.
 *
 * <p>The release body and its URL both come from a remote response, so neither is allowed to reach
 * the renderer or the browser as-is. These checks pin the sanitizing and the URL allow-list, plus
 * the version comparison the whole feature hangs on.
 */
public final class ReleaseNotesChecks {
	private ReleaseNotesChecks() {
	}

	public static void run() {
		checkSanitizing();
		checkBounds();
		checkUrlValidation();
		checkVersionComparison();
		checkUpdateCheckerInterop();
		checkReleaseNotesInterop();
	}

	private static void checkSanitizing() {
		String notes = ReleaseNotes.sanitize("""
			## Added
			- **Chat triggers** now fire from `chat`
			- A [link](https://example.invalid/evil) and an ![image](https://example.invalid/i.png)
			<details><summary>more</summary>
			> quoted line

			Plain  text""");
		assertFalse(notes.contains("#"), "heading markers are stripped");
		assertFalse(notes.contains("**"), "bold markers are stripped");
		assertFalse(notes.contains("`"), "inline code markers are stripped");
		assertFalse(notes.contains("<"), "html tags are stripped");
		assertFalse(notes.contains(">"), "quote markers are stripped");
		assertFalse(notes.contains("https://"), "link targets never survive into the panel");
		assertTrue(notes.contains("• Chat triggers now fire from chat"), "bullets become plain bullets");
		assertTrue(notes.contains("A link and an image"), "link and image text is kept without its target");
		assertTrue(notes.contains("more"), "text inside html tags is kept as plain text");
		assertFalse(notes.contains("\n\n\n"), "blank runs collapse to one blank line");
		assertTrue(notes.strip().equals(notes), "the result has no leading or trailing blank lines");

		assertEquals("This release has no notes.", ReleaseNotes.sanitize(null), "a missing body reads as empty");
		assertEquals("This release has no notes.", ReleaseNotes.sanitize("   \n\n "), "a blank body reads as empty");
		assertEquals("\u00a0", ReleaseNotes.sanitize("\u00a0"),
			"Java whitespace rules do not drop a non-breaking-space release note");
		assertTrue(ReleaseNotes.sanitize("plain\u0007line").indexOf('\u0007') < 0,
			"control characters never reach the renderer");
	}

	private static void checkBounds() {
		String longLine = ReleaseNotes.sanitize("x".repeat(5_000));
		assertTrue(longLine.length() <= ReleaseNotes.MAX_LINE_CHARS + 1,
			"an oversized single line stays within the per-line bound");

		StringBuilder body = new StringBuilder();
		for (int index = 0; index < 2_000; index++) body.append("line ").append(index).append('\n');
		String bounded = ReleaseNotes.sanitize(body.toString());
		assertTrue(bounded.lines().count() <= ReleaseNotes.MAX_NOTES_LINES + 1,
			"an oversized body is bounded by line count");
		assertTrue(bounded.length() <= ReleaseNotes.MAX_NOTES_CHARS + ReleaseNotes.MAX_LINE_CHARS,
			"an oversized body is bounded by character count");
		assertTrue(bounded.endsWith("… release notes truncated"), "a truncated body says so");

		String short1 = ReleaseNotes.sanitize("just one line");
		assertTrue(!short1.contains("truncated"), "a short body is not marked as truncated");
	}

	private static void checkUrlValidation() {
		assertEquals("https://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v9.9.9",
			ReleaseNotes.validateReleaseUrl("https://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v9.9.9"),
			"the project's own release page is accepted");
		assertEquals("https://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v9.9.9",
			ReleaseNotes.validateReleaseUrl("  " + "https://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v9.9.9"),
			"surrounding whitespace is trimmed before the check");
		assertTrue(ReleaseNotes.validateReleaseUrl(null) == null, "a missing url is rejected");
		assertTrue(ReleaseNotes.validateReleaseUrl("https://example.invalid/releases/tag/v1") == null,
			"another host is rejected");
		assertTrue(ReleaseNotes.validateReleaseUrl("https://github.com/someone/else/releases/tag/v1") == null,
			"another repository is rejected");
		assertTrue(ReleaseNotes.validateReleaseUrl("http://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v1") == null,
			"a non-https url is rejected");
		assertTrue(ReleaseNotes.validateReleaseUrl("https://github.com/dasdadassadwt/geileraddons-26.2/releases/tag/v1 x") == null,
			"an embedded space is rejected");
		assertTrue(ReleaseNotes.validateReleaseUrl("https://github.com/dasdadassadwt/geileraddons-26.2/releases/"
			+ "x".repeat(300)) == null, "an over-long url is rejected");
	}

	private static void checkVersionComparison() {
		assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"), "1.10.0 is newer than 1.9.0");
		assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"), "a major bump is newer");
		assertTrue(UpdateChecker.isNewer("1.5.2", "1.5.1"), "a patch bump is newer");
		assertFalse(UpdateChecker.isNewer("1.5.1", "1.5.1"), "an identical version is not newer");
		assertFalse(UpdateChecker.isNewer("1.5.0", "1.5.1"), "an older version is not newer");
		assertFalse(UpdateChecker.isNewer("1.4.0-rc1", "1.4.0"), "a pre-release suffix compares as its release");
		assertTrue(UpdateChecker.isNewer("1.6", "1.5.9"), "a shorter version still compares numerically");
	}

	private static void checkUpdateCheckerInterop() {
		try {
			assertStaticMethod("start", void.class);
			assertStaticMethod("tick", void.class);
			assertStaticMethod("bannerText", String.class);
			assertStaticMethod("currentVersion", String.class);
			assertStaticMethod("newerVersion", String.class);
			assertStaticMethod("repositoryPage", String.class);
			assertStaticMethod("releasesPage", String.class);
			assertStaticMethod("releaseNotes", String.class);
			assertStaticMethod("releaseUrl", String.class);
			assertStaticMethod("isNewer", boolean.class, String.class, String.class);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("UpdateChecker Java API changed", exception);
		}
	}

	private static void assertStaticMethod(String name, Class<?> returnType, Class<?>... parameterTypes)
		throws ReflectiveOperationException {
		java.lang.reflect.Method method = UpdateChecker.class.getMethod(name, parameterTypes);
		assertTrue(method.getReturnType() == returnType
			&& java.lang.reflect.Modifier.isStatic(method.getModifiers()),
			"UpdateChecker retains its public static " + name + " entrypoint");
	}

	private static void checkReleaseNotesInterop() {
		try {
			java.lang.reflect.Method sanitize = ReleaseNotes.class.getMethod("sanitize", String.class);
			java.lang.reflect.Method validateUrl = ReleaseNotes.class.getMethod("validateReleaseUrl", String.class);
			assertTrue(sanitize.getReturnType() == String.class
				&& java.lang.reflect.Modifier.isStatic(sanitize.getModifiers()),
				"ReleaseNotes retains its public static sanitizer");
			assertTrue(validateUrl.getReturnType() == String.class
				&& java.lang.reflect.Modifier.isStatic(validateUrl.getModifiers()),
				"ReleaseNotes retains its public static URL validator");
			assertPublicStaticField("MAX_NOTES_CHARS");
			assertPublicStaticField("MAX_NOTES_LINES");
			assertPublicStaticField("MAX_LINE_CHARS");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("ReleaseNotes Java API changed", exception);
		}
	}

	private static void assertPublicStaticField(String name) throws ReflectiveOperationException {
		java.lang.reflect.Field field = ReleaseNotes.class.getField(name);
		assertTrue(field.getType() == int.class && java.lang.reflect.Modifier.isStatic(field.getModifiers())
			&& java.lang.reflect.Modifier.isFinal(field.getModifiers()),
			"ReleaseNotes retains its public static final " + name + " bound");
	}

	private static void assertTrue(boolean value, String label) {
		if (!value) throw new AssertionError(label);
	}

	private static void assertFalse(boolean value, String label) {
		if (value) throw new AssertionError(label);
	}

	private static void assertEquals(Object expected, Object actual, String label) {
		if (!java.util.Objects.equals(expected, actual)) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}
}
