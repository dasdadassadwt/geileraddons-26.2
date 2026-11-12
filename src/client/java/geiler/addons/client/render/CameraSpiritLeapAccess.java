package geiler.addons.client.render;

/** Internal bridge used to seed the private POV camera's eye-height interpolation history. */
public interface CameraSpiritLeapAccess {
	void geiler$primeEyeHeightHistory(float eyeHeight);
}
