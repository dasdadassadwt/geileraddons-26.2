package geiler.addons.client.cosmetics;

import org.joml.Matrix4f;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Close-up and animation sheets for the pets and the new auras: pets-closeup.png (four views each),
 * pets-anim.png (standing and moving over time) and auras-anim.png. Called by the preview renderer.
 */
public final class CosmeticPreviewD {
	private CosmeticPreviewD() { }

	private static BufferedImage cell(int[] pixels, int size) {
		BufferedImage cell = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		cell.setRGB(0, 0, size, size, pixels, 0, size);
		return cell;
	}

	private static List<CosmeticRig.Placement> centered(List<CosmeticRig.Placement> placements) {
		List<CosmeticRig.Placement> out = new ArrayList<>();
		for (CosmeticRig.Placement p : placements) {
			out.add(new CosmeticRig.Placement(p.anchor(), p.mesh(), new Matrix4f().translate(-PetPose.REST_X, -PetPose.REST_Y, -PetPose.REST_Z).mul(p.transform()),
				p.color(), p.color2(), p.kind(), p.alpha()));
		}
		return out;
	}

	private static CosmeticRig.Motion walking(float seconds, float moving, float air) {
		float phase = seconds * (PetPose.BASE_RATE * (1f + moving * 0.7f)) * (float) (2 * Math.PI);
		PetPose pose = new PetPose(PetPose.REST_X, PetPose.REST_Y, PetPose.REST_Z + 6f * moving, 0f, 0.3f * moving, 0f, 0f, 0f, 3f * moving, phase);
		return new CosmeticRig.Motion(seconds, 7, moving, air, phase, phase, pose);
	}

	public static void run(File out, float seconds) throws IOException {
		List<CosmeticStyle> pets = CosmeticSlot.PET.styles();
		int size = 300;
		float[] yaws = {0.5f, -0.6f, 1.57f, 2.7f, 0f};
		BufferedImage close = new BufferedImage(size * yaws.length, size * pets.size(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = close.createGraphics();
		g.setColor(new Color(0x2A2D35));
		g.fillRect(0, 0, close.getWidth(), close.getHeight());
		for (int row = 0; row < pets.size(); row++) {
			CosmeticPiece piece = CosmeticPiece.of(pets.get(row));
			List<CosmeticRig.Placement> placements = CosmeticRig.place(CosmeticLoadout.of(List.of(piece)), CosmeticRig.Motion.standing(seconds, 7));
			for (int view = 0; view < yaws.length; view++) {
				int[] px = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.NONE, size, yaws[view], view == 4 ? 0f : -0.25f);
				g.drawImage(cell(px, size), view * size, row * size, null);
			}
			g.setColor(Color.WHITE);
			g.drawString(piece.style().getLabel(), 6, row * size + 16);
		}
		g.dispose();
		ImageIO.write(close, "png", new File(out, "pets-closeup.png"));

		int small = 170;
		int frames = 8;
		BufferedImage anim = new BufferedImage(small * frames, small * pets.size() * 2, BufferedImage.TYPE_INT_RGB);
		g = anim.createGraphics();
		g.setColor(new Color(0x2A2D35));
		g.fillRect(0, 0, anim.getWidth(), anim.getHeight());
		for (int row = 0; row < pets.size() * 2; row++) {
			CosmeticPiece piece = CosmeticPiece.of(pets.get(row / 2));
			float moving = row % 2;
			for (int f = 0; f < frames; f++) {
				float t = seconds + f * 0.13f;
				List<CosmeticRig.Placement> placements = centered(CosmeticRig.place(CosmeticLoadout.of(List.of(piece)), walking(t, moving, 0f)));
				int[] px = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.NONE, small, 0.55f, -0.25f, 9f, 0f);
				g.drawImage(cell(px, small), f * small, row * small, null);
			}
			g.setColor(Color.WHITE);
			g.drawString(piece.style().getLabel() + (moving > 0 ? " (moving)" : ""), 6, row * small + 14);
		}
		g.dispose();
		ImageIO.write(anim, "png", new File(out, "pets-anim.png"));

		List<CosmeticStyle> auras = List.of(CosmeticStyle.STORM_CLOUD, CosmeticStyle.FLAME_RING, CosmeticStyle.HEART_AURA);
		int cell = 260;
		int cols = 6;
		BufferedImage aura = new BufferedImage(cell * cols, cell * auras.size(), BufferedImage.TYPE_INT_RGB);
		g = aura.createGraphics();
		g.setColor(new Color(0x2A2D35));
		g.fillRect(0, 0, aura.getWidth(), aura.getHeight());
		for (int row = 0; row < auras.size(); row++) {
			CosmeticPiece piece = CosmeticPiece.of(auras.get(row));
			for (int f = 0; f < cols; f++) {
				List<CosmeticRig.Placement> placements = CosmeticRig.place(CosmeticLoadout.of(List.of(piece)),
					CosmeticRig.Motion.standing(seconds + f * 0.9f, 7));
				int[] px = CosmeticIconRenderer.render(placements, null, CosmeticIconRenderer.Stand.FULL, cell, f % 2 == 0 ? 0.5f : 2.6f, -0.2f, 5.2f, 6f);
				g.drawImage(cell(px, cell), f * cell, row * cell, null);
			}
			g.setColor(Color.WHITE);
			g.drawString(piece.style().getLabel(), 6, row * cell + 14);
		}
		g.dispose();
		ImageIO.write(aura, "png", new File(out, "auras-anim.png"));
	}
}
