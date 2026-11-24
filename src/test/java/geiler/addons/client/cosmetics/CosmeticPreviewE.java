package geiler.addons.client.cosmetics;

import org.joml.Matrix4f;

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
 * Skirt and aura previews beyond the generic slot sheets: every skirt on a shaped body (chest 1, waist 1) from the
 * front and back, and in a walking pose with real swinging legs; the auras at several times. Run with the
 * `cosmeticPreviewsE` Gradle task, optional argument: output folder.
 */
public final class CosmeticPreviewE {
	private static final int CELL = 360;
	private static final float SCALE = 9f;

	private CosmeticPreviewE() { }

	public static void main(String[] args) throws IOException {
		File out = new File(args.length > 0 ? args[0] : "build/cosmetic-previews");
		if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + out);
		skirts(new File(out, "skirts-e.png"));
		auras(new File(out, "auras-e.png"));
		closeups(new File(out, "skirts-close.png"));
		debug(new File(out, "debug-e.png"), args.length > 1 ? args[1] : "ruffled");
	}

	private static VoxelMesh box(float x0, float y0, float z0, float x1, float y1, float z1) {
		VoxelGrid grid = new VoxelGrid();
		grid.box(Math.round(x0), Math.round(y0), Math.round(z0), Math.round(x1), Math.round(y1), Math.round(z1), 0, 1f);
		return grid.mesh(1f);
	}

	/** A grey walking player: head, torso, swinging arms and legs about the hips. */
	static List<CosmeticRig.Placement> walker(float right, float left) {
		List<CosmeticRig.Placement> list = new ArrayList<>();
		int grey = 0x9A9A9A;
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, box(-4, -8, -4, 4, 0, 4), new Matrix4f(), grey, grey));
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, box(-4, 0, -2, 4, 12, 2), new Matrix4f(), grey, grey));
		VoxelMesh limb = box(-2, 0, -2, 2, 12, 2);
		// Arms swing opposite to the leg on their side; legs hang from y = 12.
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, limb, new Matrix4f().translate(6, 0, 0).rotateX(right * 0.9f), grey, grey));
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, limb, new Matrix4f().translate(-6, 0, 0).rotateX(left * 0.9f), grey, grey));
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, limb, new Matrix4f().translate(2, 12, 0).rotateX(left), grey, grey));
		list.add(new CosmeticRig.Placement(CosmeticRig.Anchor.BODY, limb, new Matrix4f().translate(-2, 12, 0).rotateX(right), grey, grey));
		return list;
	}

	/** The software renderer culls by the original face normals; a bent skirt needs every face drawn. */
	private static List<CosmeticRig.Placement> unculled(List<CosmeticRig.Placement> placements) {
		List<CosmeticRig.Placement> list = new ArrayList<>();
		for (CosmeticRig.Placement p : placements) {
			list.add(new CosmeticRig.Placement(p.anchor(), p.mesh(), p.transform(), p.color(), p.color2(), p.kind(), 254));
		}
		return list;
	}

	private static void skirts(File file) throws IOException {
		CosmeticStyle[] styles = CosmeticSlot.SKIRT.styles().toArray(new CosmeticStyle[0]);
		String[] views = {"Shaped front", "Shaped back", "Walk side", "Walk 3/4 back"};
		float[][] cameras = {{0f, 0f}, {(float) Math.PI, 0f}, {(float) (Math.PI / 2), 0f}, {(float) Math.toRadians(145), -0.35f}};
		BufferedImage image = new BufferedImage(CELL * 4, CELL * styles.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		BodyShape shape = new BodyShape(1f, 1f);
		CosmeticRig.Motion still = CosmeticRig.Motion.standing(0.4f, 7);
		CosmeticRig.Motion walking = new CosmeticRig.Motion(2f, 7, 0.9f, 0f, 0f, 1.2f);
		for (int row = 0; row < styles.length; row++) {
			CosmeticPiece piece = CosmeticPiece.of(styles[row]);
			List<CosmeticRig.Placement> shaped = CosmeticRig.place(CosmeticLoadout.of(List.of(piece), shape), still);
			SkirtRig.setLegs(-0.8f, 0.8f);
			List<CosmeticRig.Placement> stride = new ArrayList<>(walker(-0.8f, 0.8f));
			stride.addAll(unculled(CosmeticRig.place(CosmeticLoadout.of(List.of(piece)), walking)));
			SkirtRig.clearLegs();
			for (int view = 0; view < 4; view++) {
				int[] pixels = view < 2
					? CosmeticIconRenderer.render(shaped, shape, CosmeticIconRenderer.Stand.FULL, CELL, cameras[view][0], cameras[view][1], SCALE, 10f)
					: CosmeticIconRenderer.render(stride, null, CosmeticIconRenderer.Stand.NONE, CELL, cameras[view][0], cameras[view][1], SCALE, 10f);
				BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
				cell.setRGB(0, 0, CELL, CELL, pixels, 0, CELL);
				g.drawImage(cell, view * CELL, row * CELL, null);
				g.setColor(new Color(0x303030));
				g.drawString(styles[row].getLabel() + " (" + views[view] + ")", view * CELL + 8, row * CELL + 18);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	/** Large 3/4 views (front, then back) of every skirt on a flat body, for checking detail. */
	private static void closeups(File file) throws IOException {
		CosmeticStyle[] styles = CosmeticSlot.SKIRT.styles().toArray(new CosmeticStyle[0]);
		int cell = 520;
		BufferedImage image = new BufferedImage(cell * 3, cell * 4, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int i = 0; i < styles.length; i++) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(
				CosmeticLoadout.of(List.of(CosmeticPiece.of(styles[i]))), CosmeticRig.Motion.standing(0.4f, 7));
			for (int back = 0; back < 2; back++) {
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.FULL, cell,
					back == 0 ? 0.6f : 2.6f, -0.3f, 22f, 12f);
				BufferedImage c = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
				c.setRGB(0, 0, cell, cell, pixels, 0, cell);
				int x = (i % 3) * cell;
				int y = ((i / 3) * 2 + back) * cell;
				g.drawImage(c, x, y, null);
				g.setColor(new Color(0x303030));
				g.drawString(styles[i].getLabel() + (back == 0 ? " front" : " back"), x + 8, y + 18);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	private static void debug(File file, String ignored) throws IOException {
		CosmeticStyle[] styles = CosmeticSlot.SKIRT.styles().toArray(new CosmeticStyle[0]);
		int cell = 380;
		BufferedImage image = new BufferedImage(cell * 3, cell * 2 * 3, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int s = 0; s < styles.length; s++) {
			List<CosmeticRig.Placement> placements = CosmeticRig.place(
				CosmeticLoadout.of(List.of(CosmeticPiece.of(styles[s]))), CosmeticRig.Motion.standing(0.4f, 7));
			for (int back = 0; back < 2; back++) {
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.NONE, cell,
					back == 0 ? 0.5f : 2.7f, -0.35f, 24f, 12f);
				BufferedImage c = new BufferedImage(cell, cell, BufferedImage.TYPE_INT_ARGB);
				c.setRGB(0, 0, cell, cell, pixels, 0, cell);
				g.drawImage(c, (s % 3) * cell, ((s / 3) * 2 + back) * cell, null);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	private static void auras(File file) throws IOException {
		CosmeticStyle[] styles = {CosmeticStyle.MUSIC_AURA, CosmeticStyle.FROST_AURA, CosmeticStyle.BLOSSOM_AURA};
		float[] times = {1.5f, 4.1f, 7.7f, 11.3f};
		BufferedImage image = new BufferedImage(CELL * times.length, CELL * styles.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		for (int row = 0; row < styles.length; row++) {
			for (int col = 0; col < times.length; col++) {
				List<CosmeticRig.Placement> placements = CosmeticRig.place(
					CosmeticLoadout.of(List.of(CosmeticPiece.of(styles[row]))), CosmeticRig.Motion.standing(times[col], 7));
				int[] pixels = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.FULL, CELL,
					col % 2 == 0 ? 0.6f : 2.5f, -0.25f, 4.6f, 8f);
				BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
				cell.setRGB(0, 0, CELL, CELL, pixels, 0, CELL);
				g.drawImage(cell, col * CELL, row * CELL, null);
				g.setColor(new Color(0x303030));
				g.drawString(styles[row].getLabel() + " t=" + times[col], col * CELL + 8, row * CELL + 18);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}
}
