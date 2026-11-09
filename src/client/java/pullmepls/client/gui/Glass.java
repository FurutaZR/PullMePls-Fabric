package pullmepls.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small toolkit for the "liquid glass" look: rounded, translucent shapes with soft gradients and bright rims,
 * built only from {@code fill()} calls so it does not depend on any rendering internals.
 *
 * <p>All colors are ARGB. Shapes are drawn from the top-left corner with the given width and height.
 */
final class Glass {

	private Glass() {
	}

	/** Horizontal inset of the row that is {@code dist} pixels away from the top/bottom edge, for corner radius r. */
	static int inset(int dist, int r) {
		double dy = r - dist - 0.5;
		double dx = Math.sqrt(Math.max(0.0, (double) r * r - dy * dy));
		return (int) Math.round(r - dx);
	}

	static int clampRadius(int r, int w, int h) {
		return Math.max(0, Math.min(r, Math.min(w, h) / 2));
	}

	static int lerp(int a, int b, float t) {
		int aa = a >>> 24;
		int ar = (a >> 16) & 255;
		int ag = (a >> 8) & 255;
		int ab = a & 255;
		int ba = b >>> 24;
		int br = (b >> 16) & 255;
		int bg = (b >> 8) & 255;
		int bb = b & 255;
		return ((int) (aa + (ba - aa) * t) << 24)
				| ((int) (ar + (br - ar) * t) << 16)
				| ((int) (ag + (bg - ag) * t) << 8)
				| (int) (ab + (bb - ab) * t);
	}

	/** Solid rounded rectangle. */
	static void fill(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		for (int d = 0; d < r; d++) {
			int o = inset(d, r);
			g.fill(x + o, y + d, x + w - o, y + d + 1, color);
			g.fill(x + o, y + h - 1 - d, x + w - o, y + h - d, color);
		}
		if (h - 2 * r > 0) g.fill(x, y + r, x + w, y + h - r, color);
	}

	/** Rounded rectangle with a vertical gradient. */
	static void gradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		int step = 2;
		for (int row = 0; row < h; row += step) {
			int rows = Math.min(step, h - row);
			float t = (row + rows / 2.0F) / h;
			int d = Math.min(Math.min(row, h - 1 - row), Math.min(row + rows - 1, h - 1 - (row + rows - 1)));
			int o = d < r ? inset(d, r) : 0;
			g.fill(x + o, y + row, x + w - o, y + row + rows, lerp(top, bottom, t));
		}
	}

	/** One pixel outline that follows the rounded corners. */
	static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int color) {
		if (w <= 0 || h <= 0) return;
		r = clampRadius(r, w, h);
		for (int row = 0; row < Math.min(r, h); row++) {
			int o = inset(row, r);
			if (row == 0) {
				g.fill(x + o, y, x + w - o, y + 1, color);
				g.fill(x + o, y + h - 1, x + w - o, y + h, color);
			} else {
				int seg = Math.max(o + 1, inset(row - 1, r));
				g.fill(x + o, y + row, x + seg, y + row + 1, color);
				g.fill(x + w - seg, y + row, x + w - o, y + row + 1, color);
				g.fill(x + o, y + h - 1 - row, x + seg, y + h - row, color);
				g.fill(x + w - seg, y + h - 1 - row, x + w - o, y + h - row, color);
			}
		}
		if (h - 2 * r > 0) {
			g.fill(x, y + r, x + 1, y + h - r, color);
			g.fill(x + w - 1, y + r, x + w, y + h - r, color);
		}
	}

	/** A glass pane: translucent gradient body, a defined rim and a bright specular line along the top. */
	static void pane(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int top, int bottom, int rim) {
		gradient(g, x, y, w, h, r, top, bottom);
		border(g, x, y, w, h, r, rim);
		r = clampRadius(r, w, h);
		if (w > 2 * r + 4 && h > 6) {
			g.fill(x + r, y + 1, x + w - r, y + 2, lerp(rim, 0xFFFFFFFF, 0.35F));
		}
	}
}
