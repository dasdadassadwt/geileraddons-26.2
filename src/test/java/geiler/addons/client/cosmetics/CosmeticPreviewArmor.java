package geiler.addons.client.cosmetics;

import java.util.List;

/** A torso-sized armor box (8 x 12 x 4 inflated) in the shape the armor warp receives, for previews. */
final class CosmeticPreviewArmor {
	private CosmeticPreviewArmor() { }

	static ArmorBodyWarp.CubeFaces box(float inflate) {
		float x0 = -4 - inflate, x1 = 4 + inflate, y0 = -inflate, y1 = 12 + inflate, z0 = -2 - inflate, z1 = 2 + inflate;
		return new ArmorBodyWarp.CubeFaces(inflate, List.of(
			new float[][]{{x0, y0, z0, 0, 0}, {x1, y0, z0, 1, 0}, {x1, y1, z0, 1, 1}, {x0, y1, z0, 0, 1}},
			new float[][]{{x1, y0, z1, 0, 0}, {x0, y0, z1, 1, 0}, {x0, y1, z1, 1, 1}, {x1, y1, z1, 0, 1}},
			new float[][]{{x0, y0, z1, 0, 0}, {x0, y0, z0, 1, 0}, {x0, y1, z0, 1, 1}, {x0, y1, z1, 0, 1}},
			new float[][]{{x1, y0, z0, 0, 0}, {x1, y0, z1, 1, 0}, {x1, y1, z1, 1, 1}, {x1, y1, z0, 0, 1}},
			new float[][]{{x0, y0, z1, 0, 0}, {x1, y0, z1, 1, 0}, {x1, y0, z0, 1, 1}, {x0, y0, z0, 0, 1}},
			new float[][]{{x0, y1, z0, 0, 0}, {x1, y1, z0, 1, 0}, {x1, y1, z1, 1, 1}, {x0, y1, z1, 0, 1}}));
	}
}
