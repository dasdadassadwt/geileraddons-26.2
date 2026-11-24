package geiler.addons.client.cosmetics;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders every cosmetic on a grey stand-in player from the front, back, side and a 3/4 angle with the
 * same models, rig and software renderer the Cosmetics menu uses. Run with the `cosmeticPreviews` Gradle
 * task; output goes to the given folder (one sheet per slot, plus body shapes).
 */
public final class CosmeticPreviewRenderer {
	private static final int CELL = 300;
	private static final float SCALE = 6f;
	private static final String[] VIEWS = {"Front", "Back", "Side", "3/4 Back"};
	private static final float[][] CAMERAS = {{0f, 0f}, {(float) Math.PI, 0f}, {(float) (Math.PI / 2), 0f},
		{(float) Math.toRadians(145), -0.35f}};

	private CosmeticPreviewRenderer() { }

	public static void main(String[] args) throws IOException {
		File out = new File(args.length > 0 ? args[0] : "build/cosmetic-previews");
		if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + out);
		float seconds = args.length > 1 ? Float.parseFloat(args[1]) : 0.4f;
		// Optional 3rd and 4th argument: moving and air (0..1), to look at walking and jumping poses.
		float moving = args.length > 2 ? Float.parseFloat(args[2]) : 0f;
		float air = args.length > 3 ? Float.parseFloat(args[3]) : 0f;
		float phase = seconds * (1f + moving * 0.8f) * (float) (2 * Math.PI);
		CosmeticRig.Motion motion = args.length > 2 ? new CosmeticRig.Motion(seconds, 7, moving, air, phase, phase)
			: CosmeticRig.Motion.standing(seconds, 7);
		for (CosmeticSlot slot : CosmeticSlot.values()) {
			List<String> labels = new ArrayList<>();
			List<CosmeticLoadout> rows = new ArrayList<>();
			for (CosmeticStyle style : slot.styles()) {
				labels.add(style.getLabel());
				rows.add(CosmeticLoadout.of(List.of(CosmeticPiece.of(style))));
			}
			write(new File(out, slot.getId() + ".png"), labels, rows, motion);
		}
		write(new File(out, "body.png"), List.of("Chest 0.5", "Chest 1, Waist 1"),
			List.of(CosmeticLoadout.of(List.of(), new BodyShape(0.5f, 0f)),
				CosmeticLoadout.of(List.of(), new BodyShape(1f, 1f))), motion);
		cards(new File(out, "cards.png"), motion);
		armor(new File(out, "armor.png"));
		CosmeticPreviewD.run(out, seconds);
		CosmeticFPreview.run(out);
	}

	/** A chestplate bent over two body shapes (blue), to check it wraps the chest and waist. */
	private static void armor(File file) throws IOException {
		BodyShape[] shapes = {new BodyShape(0.31f, 1f), new BodyShape(1f, 1f)};
		BufferedImage image = new BufferedImage(CELL * VIEWS.length, CELL * shapes.length, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int row = 0; row < shapes.length; row++) {
			BodyShape shape = shapes[row];
			float[] plate = ArmorBodyWarp.bend(List.of(CosmeticPreviewArmor.box(1f)), shape);
			for (int view = 0; view < VIEWS.length; view++) {
				int[] pixels = CosmeticIconRenderer.render(List.of(), shape, CosmeticIconRenderer.Stand.FULL, CELL,
					CAMERAS[view][0], CAMERAS[view][1], SCALE, 5f, plate, 0x6F8FB0);
				BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
				cell.setRGB(0, 0, CELL, CELL, pixels, 0, CELL);
				g.drawImage(cell, view * CELL, row * CELL, null);
				g.setColor(new Color(0x303030));
				g.drawString("Chestplate, chest " + shape.chest() + " waist " + shape.waist() + " (" + VIEWS[view] + ")",
					view * CELL + 8, row * CELL + 18);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	/** Every style as the Cosmetics menu card draws it (fitted, 3/4 view), at a large size. */
	private static void cards(File file, CosmeticRig.Motion motion) throws IOException {
		CosmeticStyle[] styles = CosmeticStyle.values();
		int size = 220;
		int columns = 4;
		int rows = (styles.length + columns - 1) / columns;
		BufferedImage image = new BufferedImage(size * columns, size * rows, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0x2A2D35));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		for (int i = 0; i < styles.length; i++) {
			CosmeticStyle style = styles[i];
			int[] pixels = CosmeticIconRenderer.render(
				CosmeticRig.place(CosmeticLoadout.of(List.of(CosmeticPiece.of(style))), motion), null,
				CosmeticIcons.stand(style), size, CosmeticIcons.yaw(style), CosmeticIcons.PITCH);
			BufferedImage cell = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			cell.setRGB(0, 0, size, size, pixels, 0, size);
			g.drawImage(cell, (i % columns) * size, (i / columns) * size, null);
			g.setColor(Color.WHITE);
			g.drawString(style.getLabel(), (i % columns) * size + 8, (i / columns) * size + 16);
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}

	private static void write(File file, List<String> labels, List<CosmeticLoadout> rows, CosmeticRig.Motion motion)
		throws IOException {
		BufferedImage image = new BufferedImage(CELL * VIEWS.length, CELL * rows.size(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(0xC8C8C8));
		g.fillRect(0, 0, image.getWidth(), image.getHeight());
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		for (int row = 0; row < rows.size(); row++) {
			CosmeticLoadout loadout = rows.get(row);
			List<CosmeticRig.Placement> placements = CosmeticRig.place(loadout, motion);
			for (int view = 0; view < VIEWS.length; view++) {
				int[] pixels = CosmeticIconRenderer.render(placements, loadout.body(), CosmeticIconRenderer.Stand.FULL,
					CELL, CAMERAS[view][0], CAMERAS[view][1], SCALE, 5f);
				BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
				cell.setRGB(0, 0, CELL, CELL, pixels, 0, CELL);
				g.drawImage(cell, view * CELL, row * CELL, null);
				g.setColor(new Color(0x303030));
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
				g.drawString(labels.get(row) + " (" + VIEWS[view] + ")", view * CELL + 8, row * CELL + 18);
			}
		}
		g.dispose();
		ImageIO.write(image, "png", file);
	}
}
