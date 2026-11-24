package geiler.addons.client.cosmetics;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Extra pictures for the trail and cape cosmetics: a simulated walk through the real history and placement code
 * (trail_walk.png), the cape at rest, leaning and flapping (cape_poses.png) and the cape textures (cape_textures.png).
 */
public final class CosmeticFPreview {
	private static final int CELL = 320;
	private static final float SCALE = 5.2f;

	private CosmeticFPreview() { }

	public static void run(File out) throws IOException {
		trailWalk(new File(out, "trail_walk.png"));
		capePoses(new File(out, "cape_poses.png"));
		capeTextures(new File(out, "cape_textures.png"));
		spriteFrames(new File(out, "sprite_frames.png"));
	}

	private static void cell(Graphics2D g, int[] pixels, int col, int row, String label) {
		BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
		cell.setRGB(0, 0, CELL, CELL, pixels, 0, CELL);
		g.drawImage(cell, col * CELL, row * CELL, null);
		g.setColor(new Color(0x303030));
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		g.drawString(label, col * CELL + 8, row * CELL + 16);
	}

	private static BufferedImage sheet(int cols, int rows) {
		BufferedImage image = new BufferedImage(CELL * cols, CELL * rows, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.dispose();
		return image;
	}

	/** The player walks 3.5 s along a gentle S curve; the picture is the last frame, seen from three sides. */
	private static void trailWalk(File file) throws IOException {
		List<CosmeticStyle> styles = CosmeticSlot.TRAIL.styles();
		BufferedImage image = sheet(3, styles.size());
		Graphics2D g = image.createGraphics();
		String[] names = {"Back", "3/4 back", "Side (camera on the left)"};
		float[][] cameras = {{(float) Math.PI, 0f}, {(float) Math.toRadians(145), -0.35f}, {(float) (Math.PI / 2), 0f}};
		for (int row = 0; row < styles.size(); row++) {
			CosmeticStyle style = styles.get(row);
			CosmeticPiece piece = CosmeticPiece.of(style);
			TrailHistory history = new TrailHistory();
			double x = 0;
			double z = 0;
			double t = 0;
			double heading = 0;
			double end = 4.0;
			double speed = 4.3;
			while (t <= end) {
				heading = 0.55 * Math.sin(t * 0.9);
				x += Math.sin(heading) * speed / 60.0;
				z += Math.cos(heading) * speed / 60.0;
				history.update(1, style, 100 + t, x, 64.0, z, true, 7, 0f);
				t += 1.0 / 60.0;
			}
			float bodyRot = (float) Math.toDegrees(-heading);
			for (int view = 0; view < 3; view++) {
				TrailRig.Frame frame = new TrailRig.Frame().set(x, 64.0, z, bodyRot, 1f, 1f, 1f, 1f, 100 + end);
				if (view == 2) {
					// Camera 6 blocks to the player's left, slightly above.
					double yaw = Math.toRadians(bodyRot);
					frame.camera(x + 6 * -Math.cos(yaw), 65.6, z + 6 * -Math.sin(yaw));
				}
				List<CosmeticRig.Placement> placements = new ArrayList<>();
				TrailHistory.Track track = history.update(1, style, 100 + end, x, 64.0, z, true, 7, 0f);
				TrailRig.live(piece, track, CosmeticRig.Motion.standing(100f + (float) end, 7), frame, placements);
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.FULL, CELL,
					cameras[view][0], cameras[view][1], 2.6f, 14f);
				cell(g, pixels, view, row, style.getLabel() + " (" + names[view] + ") " + placements.size() + " parts");
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	private static void capePoses(File file) throws IOException {
		List<CosmeticStyle> styles = CosmeticSlot.CAPE.styles();
		BufferedImage image = sheet(4, styles.size());
		Graphics2D g = image.createGraphics();
		// {lean, flap, lean2}, yaw, pitch
		float[][] poses = {{0f, 0f, 0f, (float) Math.PI, 0f}, {25f, 8f, 0f, (float) Math.toRadians(145), -0.3f},
			{25f, 8f, 0f, (float) (Math.PI / 2), 0f}, {60f, 20f, 25f, (float) Math.toRadians(215), -0.3f}};
		String[] names = {"Back, at rest", "3/4 back, walking", "Side, walking", "3/4 back, fast + sway"};
		for (int row = 0; row < styles.size(); row++) {
			CosmeticStyle style = styles.get(row);
			for (int col = 0; col < poses.length; col++) {
				List<CosmeticRig.Placement> placements = new ArrayList<>();
				CapeRig.place(CosmeticPiece.of(style), CosmeticRig.Motion.standing(0.8f, 3), poses[col][0], poses[col][1], poses[col][2],
					CosmeticRig.Anchor.CAPE, placements);
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.FULL, CELL,
					poses[col][3], poses[col][4], SCALE, 9f);
				cell(g, pixels, col, row, style.getLabel() + " (" + names[col] + ")");
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	/** Every frame of every sprite, flat and large, to compare with the reference sheet. */
	private static void spriteFrames(File file) throws IOException {
		VoxelMesh[][] sets = {TrailSprites.INSTANCE.getFLAME(), TrailSprites.INSTANCE.getFLOWER(), TrailSprites.INSTANCE.getSPARKLE(),
			TrailSprites.INSTANCE.getBUBBLE(), TrailSprites.INSTANCE.getENDER()};
		CosmeticStyle[] styles = {CosmeticStyle.FLAME_FOOTPRINTS, CosmeticStyle.FLOWER_STEPS, CosmeticStyle.SPARKLE_TRAIL,
			CosmeticStyle.BUBBLE_TRAIL, CosmeticStyle.ENDER_SPARKS};
		int cell = 150;
		BufferedImage image = new BufferedImage(cell * TrailSprites.FRAMES, cell * sets.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int row = 0; row < sets.length; row++) {
			for (int frame = 0; frame < TrailSprites.FRAMES; frame++) {
				CosmeticStyle style = styles[row];
				boolean glass = style == CosmeticStyle.BUBBLE_TRAIL;
				List<CosmeticRig.Placement> placements = List.of(new CosmeticRig.Placement(CosmeticRig.Anchor.WORLD, sets[row][frame],
					new org.joml.Matrix4f(), style.getColor(), style.getColor2(), glass ? CosmeticRig.Kind.TRANSLUCENT : CosmeticRig.Kind.SOLID,
					glass ? 205 : 255));
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.NONE, cell, 0f, 0f, 9f, row < 2 ? -4f : 0f);
				BufferedImage tile = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
				tile.setRGB(0, 0, cell, cell, pixels, 0, cell);
				g.drawImage(tile, frame * cell, row * cell, null);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	/** Each cape's two faces as flat textures in the style's colors (outside left, inside right). */
	private static void capeTextures(File file) throws IOException {
		List<CosmeticStyle> styles = CosmeticSlot.CAPE.styles();
		int zoom = 8;
		int w = CapeRig.WIDTH * zoom;
		int h = CapeRig.HEIGHT * zoom;
		BufferedImage image = new BufferedImage((w + 16) * styles.size() * 2, h + 16, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int s = 0; s < styles.size(); s++) {
			for (int inner = 0; inner < 2; inner++) {
				int left = (s * 2 + inner) * (w + 16) + 8;
				for (int y = 0; y < CapeRig.HEIGHT; y++) {
					for (int x = 0; x < CapeRig.WIDTH; x++) {
						int argb = CapeRig.colorAt(styles.get(s), inner == 1, x, y, styles.get(s).getColor(), styles.get(s).getColor2());
						g.setColor(new Color(argb));
						g.fillRect(left + x * zoom, 8 + y * zoom, zoom, zoom);
					}
				}
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}
}
