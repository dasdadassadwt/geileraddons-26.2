package geiler.addons.client.module.impl;

/** Offline checks for No Render's detection rules and its module declaration. */
public final class NoRenderChecks {
	private NoRenderChecks() { }

	public static void run() {
		// Dead Poof: one particle, no speed, small non-zero offsets.
		check(NoRenderRules.isPoofParticle(1, 0.0f, 0.05f, -0.05f, 0.02f), "a still one-particle poof with small offsets is a death poof");
		check(!NoRenderRules.isPoofParticle(1, 0.0f, 0.0f, 0.05f, 0.05f), "a zero offset on any axis is not a death poof");
		check(!NoRenderRules.isPoofParticle(1, 0.0f, 0.1f, 0.05f, 0.05f) && !NoRenderRules.isPoofParticle(1, 0.0f, 0.05f, -0.1f, 0.05f),
			"an offset of 0.1 or more on any axis is not a death poof");
		check(!NoRenderRules.isPoofParticle(2, 0.0f, 0.05f, 0.05f, 0.05f) && !NoRenderRules.isPoofParticle(1, 0.1f, 0.05f, 0.05f, 0.05f),
			"a poof needs exactly one particle and zero speed");

		// Powder Coating: white or orange dust whose packet repeats the color as the offset.
		check(NoRenderRules.isCoatingColor(1.0f, 1.0f, 1.0f) && NoRenderRules.isCoatingColor(1.0f, 0.6f, 0.0f)
			&& !NoRenderRules.isCoatingColor(1.0f, 0.5f, 0.0f) && !NoRenderRules.isCoatingColor(0.0f, 1.0f, 1.0f),
			"coating dust is white or orange only");
		check(NoRenderRules.isCoatingPacketShape(0, 1.0f, 1.0f, 1.0f, 1.0f) && NoRenderRules.isCoatingPacketShape(0, 1.0f, 1.0f, 0.6f, 0.0f)
			&& !NoRenderRules.isCoatingPacketShape(1, 1.0f, 1.0f, 1.0f, 1.0f) && !NoRenderRules.isCoatingPacketShape(0, 0.5f, 1.0f, 1.0f, 1.0f),
			"coating dust packets have count 0, speed 1 and the color as offset");

		// Dead Entities: health lines at 0 hearts, with the vampirism mark ignored.
		check(NoRenderRules.isDeadNameTag("Zombie 0❤") && NoRenderRules.isDeadNameTag("Zombie 0/100❤")
			&& NoRenderRules.isDeadNameTag("Zombie 0҉❤") && !NoRenderRules.isDeadNameTag("Zombie 5❤")
			&& !NoRenderRules.isDeadNameTag("Zombie 10❤") && !NoRenderRules.isDeadNameTag(null),
			"only a health line showing zero hearts hides a name tag");

		check(NoRenderRules.isGuidedSheepHealth(8.0f) && !NoRenderRules.isGuidedSheepHealth(8.5f), "a guided sheep has exactly 8 health");
		check(NoRenderRules.isBonePlatingDrop(true, "Bone Meal") && !NoRenderRules.isBonePlatingDrop(true, "Bone Meal x2")
			&& !NoRenderRules.isBonePlatingDrop(false, "Bone Meal"), "bone plating hides only the plain bone meal item");

		String soulWeaver = java.util.Base64.getEncoder().encodeToString(
			("{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/2f24ed6875304fa4a1f0c785b2cb6a6a72563e9f3e24ea55e18178452119aa66\"}}}")
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		check(NoRenderRules.isSoulWeaverTexture(soulWeaver) && !NoRenderRules.isSoulWeaverTexture("")
			&& !NoRenderRules.isSoulWeaverTexture(null) && !NoRenderRules.isSoulWeaverTexture("not base64 !!")
			&& !NoRenderRules.isSoulWeaverTexture(java.util.Base64.getEncoder().encodeToString("{\"url\":\"other\"}".getBytes())),
			"only the Soul Weaver skull skin is recognised");

		// Module declaration: twenty-seven switches (four of them the old No Fog toggles), all off, one per flag.
		NoRenderModule module = NoRenderModule.INSTANCE;
		check("No Render".equals(module.name()) && !module.defaultEnabled() && module.booleanSettings().size() == 27
			&& NoRenderModule.Flag.values().length == 27, "No Render declares twenty-seven switches and is off by default");
		check(NoRenderModule.Flag.CLOUDS.getId().equals("clouds") && NoRenderModule.Flag.SOULWEAVER_SKULLS.getId().equals("soulweaverSkulls")
			&& !module.active(NoRenderModule.Flag.CLOUDS) && !module.active(NoRenderModule.Flag.SOULWEAVER_SKULLS),
			"No Render has Clouds and Soul Weaver Skulls switches that are inert while the module is off");
		check(NoRenderModule.Flag.DARKNESS.getId().equals("darkness") && !module.active(NoRenderModule.Flag.DARKNESS), "No Render has a Darkness switch that is inert while the module is off");
		check(module.booleanSettings().stream().anyMatch(setting -> setting.name().equals("Blindness"))
			&& NoRenderModule.Flag.BLINDNESS.getId().equals("blindness"), "No Render has a Blindness switch");
		check(!module.active(NoRenderModule.Flag.BLINDNESS), "a disabled No Render module never hides blindness");
		check(module.booleanSettings().stream().noneMatch(setting -> setting.value()), "every No Render switch is off by default");
		check(!module.active(NoRenderModule.Flag.FIRE_OVERLAY), "a disabled No Render module never reports a switch as active");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
