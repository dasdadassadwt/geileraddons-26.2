package geiler.addons.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import geiler.addons.client.module.Module;
import geiler.addons.client.module.ModuleKeybind;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Card control hit areas, the key badge label, the WIP badge removal, and the smooth icon and heart textures. */
public final class ClickGuiHitRectChecks {
	private ClickGuiHitRectChecks() { }

	public static void run() throws Exception {
		checkHitRects();
		checkKeyBadge();
		checkCategoryHeart();
		checkWipRemoved();
		checkTextures();
	}

	private static void checkHitRects() {
		for (int cardH : new int[] {52, 64, 90}) {
			ClickGuiScreen.Rect card = new ClickGuiScreen.Rect(100, 200, 253, cardH);
			ClickGuiScreen.Rect toggle = ClickGuiScreen.cardSwitchRect(card);
			ClickGuiScreen.Rect heart = ClickGuiScreen.moduleFavoriteHitRect(card, true, toggle.y());
			ClickGuiScreen.Rect key = ClickGuiScreen.keySquareRect(card, true, 20);
			ClickGuiScreen.Rect[] hits = ClickGuiScreen.cardControlHitRects(card, heart, toggle, key);
			ClickGuiScreen.Rect[] visuals = {heart, toggle, key};
			for (int i = 0; i < 3; i++) {
				check(contains(hits[i], visuals[i]), "hit area " + i + " contains its visual rect at card height " + cardH);
				check(hits[i].x() >= card.x() && hits[i].y() >= card.y()
					&& hits[i].x() + hits[i].w() <= card.x() + card.w() && hits[i].y() + hits[i].h() <= card.y() + card.h(),
					"hit area " + i + " stays inside the card at height " + cardH);
				check(hits[i].x() <= visuals[i].x() - 6, "hit area " + i + " reaches at least 6 px left of its visual rect");
				check(hits[i].x() + hits[i].w() == card.x() + card.w(), "hit area " + i + " runs to the card's right edge");
				for (int j = i + 1; j < 3; j++) {
					check(!overlaps(hits[i], hits[j]), "hit areas " + i + " and " + j + " never overlap at height " + cardH);
				}
			}
		}
		ClickGuiScreen.Rect card = new ClickGuiScreen.Rect(100, 200, 253, 64);
		ClickGuiScreen.Rect heart = ClickGuiScreen.moduleFavoriteHitRect(card, false, -1);
		ClickGuiScreen.Rect key = ClickGuiScreen.keySquareRect(card, false, 20);
		ClickGuiScreen.Rect[] hits = ClickGuiScreen.cardControlHitRects(card, heart, null, key);
		check(hits[1] == null && contains(hits[0], heart) && contains(hits[2], key) && !overlaps(hits[0], hits[2]),
			"a card without a switch still gets two disjoint hit areas");
	}

	private static void checkKeyBadge() {
		ModuleKeybind plain = new ModuleKeybind(InputConstants.getKey("key.keyboard.k"), 0);
		ModuleKeybind combo = new ModuleKeybind(InputConstants.getKey("key.keyboard.k"),
			InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT);
		String keyName = plain.key().getDisplayName().getString();
		check(keyName.equals(ClickGuiScreen.keyBadgeLabel(plain)), "a plain bind shows its key");
		check(("C+S+" + keyName).equals(ClickGuiScreen.keyBadgeLabel(combo)), "a combo shows compact modifier letters: " + ClickGuiScreen.keyBadgeLabel(combo));
		check("–".equals(ClickGuiScreen.keyBadgeLabel(ModuleKeybind.NONE)), "an unbound key shows a dash");
		ClickGuiScreen.Rect card = new ClickGuiScreen.Rect(100, 200, 253, 52);
		ClickGuiScreen.Rect toggle = ClickGuiScreen.cardSwitchRect(card);
		ClickGuiScreen.Rect longBadge = ClickGuiScreen.keySquareRect(card, true, 56);
		check(longBadge.x() >= card.x() && longBadge.x() + longBadge.w() <= card.x() + card.w()
			&& longBadge.y() + longBadge.h() <= card.y() + card.h() && longBadge.y() >= toggle.y() + toggle.h(),
			"a long bind badge stays inside the card, under the switch");
		check(ClickGuiScreen.keySquareRect(card, true, 3).h() == 16, "the key badge is 16 px high");
	}

	private static void checkCategoryHeart() {
		ClickGuiScreen.Rect row = new ClickGuiScreen.Rect(10, 50, 100, 28);
		ClickGuiScreen.Rect heart = new ClickGuiScreen.Rect(row.x() + row.w() - 24 - 4, row.y() + 2, 24, 24);
		ClickGuiScreen.Rect hit = ClickGuiScreen.categoryHeartHitRect(row, heart);
		check(hit.y() == heart.y() && hit.h() == heart.h(), "the category heart grows horizontally only");
		check(hit.x() <= heart.x() - 6 && hit.x() + hit.w() == row.x() + row.w(), "the category heart reaches left and to the row's edge");
	}

	private static void checkWipRemoved() {
		try {
			Module.class.getMethod("isWorkInProgress");
			throw new AssertionError("the WIP marker is gone from Module");
		} catch (NoSuchMethodException expected) { }
	}

	private static void checkTextures() throws Exception {
		String base = "/assets/geileraddons/textures/gui/";
		for (String name : new String[] {"octicon-gear-16", "octicon-mark-github-16", "octicon-paintbrush-16",
			"move-arrows-16", "cheats-bolt"}) {
			check(blurMcmeta(base + name + ".png.mcmeta"), name + " is sampled with blur");
			check(size(base + name + ".png") == ClickGuiScreenRenderBehavior.TOOLBAR_ICON_TEXTURE_SIZE,
				name + " matches the texture size constant " + ClickGuiScreenRenderBehavior.TOOLBAR_ICON_TEXTURE_SIZE);
		}
		for (String name : new String[] {"favorite-heart-filled", "favorite-heart-outline"}) {
			check(blurMcmeta(base + name + ".png.mcmeta"), name + " is sampled with blur");
			check(size(base + name + ".png") == ClickGuiScreenRenderBehavior.FAVORITE_TEXTURE_SIZE,
				name + " matches the heart texture size constant");
		}
	}

	private static boolean blurMcmeta(String path) throws Exception {
		try (InputStream in = ClickGuiHitRectChecks.class.getResourceAsStream(path)) {
			if (in == null) return false;
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s", "");
			return text.contains("\"blur\":true");
		}
	}

	private static int size(String path) throws Exception {
		try (InputStream in = ClickGuiHitRectChecks.class.getResourceAsStream(path)) {
			BufferedImage image = ImageIO.read(in);
			check(image.getWidth() == image.getHeight(), path + " is square");
			return image.getWidth();
		}
	}

	private static boolean contains(ClickGuiScreen.Rect outer, ClickGuiScreen.Rect inner) {
		return outer.x() <= inner.x() && outer.y() <= inner.y()
			&& outer.x() + outer.w() >= inner.x() + inner.w() && outer.y() + outer.h() >= inner.y() + inner.h();
	}

	private static boolean overlaps(ClickGuiScreen.Rect a, ClickGuiScreen.Rect b) {
		return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
