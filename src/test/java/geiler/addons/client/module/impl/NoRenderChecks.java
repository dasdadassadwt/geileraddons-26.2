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

		// Module declaration: nineteen switches, all off, one per flag.
		NoRenderModule module = NoRenderModule.INSTANCE;
		check("No Render".equals(module.name()) && !module.defaultEnabled() && module.booleanSettings().size() == 19
			&& NoRenderModule.Flag.values().length == 19, "No Render declares nineteen switches and is off by default");
		check(module.booleanSettings().stream().noneMatch(setting -> setting.value()), "every No Render switch is off by default");
		check(!module.active(NoRenderModule.Flag.FIRE_OVERLAY), "a disabled No Render module never reports a switch as active");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
