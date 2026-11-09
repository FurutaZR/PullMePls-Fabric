package pullmepls.client.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import pullmepls.client.BotConfig;
import pullmepls.client.PearlTracker;
import pullmepls.client.PullManager;
import pullmepls.client.PullMePlsClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The config GUI, drawn in a "liquid glass" style: translucent rounded panes over the blurred world.
 *
 * <p>Opens with Right Shift (rebindable). Controls are drawn with {@link Glass}; clicks are handled by a small
 * hit-test list, and text input uses vanilla {@link EditBox}es without their border, drawn on glass fields.
 */
public final class PullMePlsScreen extends Screen {

	private enum Tab {
		GENERAL("General"), PULL("Pull"), WALK("Walk"), GIVE("Give"),
		CHAT("Chat"), COMMANDS("Commands"), MESSAGES("Messages"), PLAYERS("Players"), PEARLS("Pearls");

		final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private static final int SIDE = 108;
	private static final int ROW = 21;
	private static final int RADIUS = 14;

	// text colors
	private static final int C_TEXT = 0xFFF4F8FF;
	private static final int C_SOFT = 0xFFD2DCEC;
	private static final int C_DIM = 0xFF9DAAC2;
	private static final int C_ACCENT = 0xFF8FDCFF;
	private static final int C_OK = 0xFF9BF0B8;
	private static final int C_BAD = 0xFFFF9C9C;
	// glass controls
	private static final int C_CTRL = 0x2EFFFFFF;
	private static final int C_CTRL_HOVER = 0x4AFFFFFF;
	private static final int C_CTRL_EDGE = 0x58FFFFFF;
	private static final int C_ON = 0xAA3FB6FF;
	private static final int C_ON_HOVER = 0xCC5CC4FF;
	private static final int C_ON_EDGE = 0xDDBFE9FF;

	private Tab tab = Tab.GENERAL;
	private boolean listening;
	private int playerPage;
	private int pearlPage;
	private int msgPage;
	private String nameText = "";
	private String notice = "";
	private List<PearlTracker.Pearl> pearlRows = List.of();
	private final List<EditBox> boxes = new ArrayList<>();
	private final List<Hit> hits = new ArrayList<>();

	// layout
	private int px, py, pw, ph, cx, cw, rowY, cardBottom;

	public PullMePlsScreen() {
		super(Component.literal("PullMePls"));
	}

	private static BotConfig cfg() {
		return PullMePlsClient.config();
	}

	// ------------------------------------------------------------------ lifecycle + input

	@Override
	protected void init() {
		pw = Math.min(this.width - 16, 520);
		ph = Math.min(this.height - 16, 292);
		px = (this.width - pw) / 2;
		py = (this.height - ph) / 2;
		refreshPearls();
		layoutTab();
	}

	@Override
	public void onClose() {
		cfg().save();
		super.onClose();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening) {
			listening = false;
			if (event.key() != InputConstants.KEY_ESCAPE) {
				applyKey(InputConstants.getKey(event));
			}
			return true;
		}
		boolean typing = false;
		for (EditBox box : boxes) {
			if (box.isFocused()) typing = true;
		}
		// The key that opened the GUI closes it again (unless you are typing in a text box).
		if (!typing && PullMePlsClient.openGuiKey.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int i = hits.size() - 1; i >= 0; i--) {
				Hit hit = hits.get(i);
				if (hit.contains(event.x(), event.y())) {
					this.setFocused(null);
					hit.action().run();
					return true;
				}
			}
			this.setFocused(null); // clicking empty glass leaves any text box
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void applyKey(InputConstants.Key key) {
		PullMePlsClient.openGuiKey.setKey(key);
		KeyMapping.resetMapping();
		this.minecraft.options.save();
	}

	// ------------------------------------------------------------------ layout

	private void layoutTab() {
		this.clearWidgets();
		boxes.clear();
		hits.clear();

		cx = px + SIDE + 20;
		cw = pw - SIDE - 40;
		rowY = py + 42;

		addDraw((g, mx, my, dt) -> drawWindow(g));

		// sidebar tabs
		int ty = py + 40;
		for (Tab t : Tab.values()) {
			button(px + 14, ty, SIDE - 24, 16, () -> t.title, t == tab, () -> {
				tab = t;
				listening = false;
				playerPage = 0;
				pearlPage = 0;
				msgPage = 0;
				if (t == Tab.PEARLS) refreshPearls();
				layoutTab();
			});
			ty += 19;
		}

		// master switch at the bottom of the sidebar
		int sy = py + ph - 34;
		label(px + 20, sy + 5, "Bot", C_TEXT);
		switchAt(px + SIDE - 40, sy, () -> cfg().enabled, () -> cfg().enabled = !cfg().enabled);

		// content card (its height is known once the tab has been built)
		addDraw((g, mx, my, dt) -> Glass.pane(g, cx - 10, py + 34, cw + 20, cardBottom - (py + 34), 11,
				0x22FFFFFF, 0x0CFFFFFF, 0x3AFFFFFF));

		cardBottom = 0;
		switch (tab) {
			case GENERAL -> buildGeneral();
			case PULL -> buildPull();
			case WALK -> buildWalk();
			case GIVE -> buildGive();
			case CHAT -> buildChat();
			case COMMANDS -> buildCommands();
			case MESSAGES -> buildMessages();
			case PLAYERS -> buildPlayers();
			case PEARLS -> buildPearls();
		}
		if (cardBottom == 0) cardBottom = rowY + 6;

		addDraw((g, mx, my, dt) -> {
			if (!notice.isEmpty()) g.text(this.font, notice, cx - 4, py + ph - 13, C_OK, false);
		});
	}

	private void drawWindow(GuiGraphicsExtractor g) {
		Glass.fill(g, px - 2, py - 2, pw + 4, ph + 4, RADIUS + 2, 0x24000000);                       // soft shadow
		Glass.pane(g, px, py, pw, ph, RADIUS, 0x54FFFFFF, 0x26A8CCFF, 0x99FFFFFF);                  // window glass
		Glass.pane(g, px + 8, py + 34, SIDE - 4, ph - 42, 11, 0x30FFFFFF, 0x10FFFFFF, 0x4DFFFFFF); // sidebar
		g.fill(px + 16, py + 28, px + pw - 16, py + 29, 0x2EFFFFFF);                                 // header divider

		Glass.fill(g, px + 16, py + 9, 11, 11, 5, C_ON);                                             // logo bead
		Glass.fill(g, px + 19, py + 12, 5, 5, 2, 0xE6FFFFFF);
		g.text(this.font, "PullMePls", px + 33, py + 11, C_TEXT, true);
		g.text(this.font, "pearl stasis bot - by Ijau", px + 41 + this.font.width("PullMePls"), py + 11, C_DIM, false);

		String loaded = PullMePlsClient.tracker().count() + " pearls loaded";
		g.text(this.font, loaded, px + pw - 16 - this.font.width(loaded), py + 11, C_SOFT, false);
	}

	// ------------------------------------------------------------------ settings tabs

	private void buildGeneral() {
		BotConfig c = cfg();
		header("General");
		toggle("Owner labels on pearls", () -> c.showOwnerLabels, v -> {
			c.showOwnerLabels = v;
			PullMePlsClient.tracker().refreshLabels();
		});
		toggle("Auto-register pearl owners", () -> c.autoRegister, v -> c.autoRegister = v);
		keybindRow();
		note("Right Shift by default. Also in Options > Controls.");
	}

	private void buildPull() {
		BotConfig c = cfg();
		header("Pull behaviour");
		cycle("Trapdoor action", () -> c.pullMode, v -> c.pullMode = v, BotConfig.PullMode.values());
		toggle("Auto-reset trapdoor", () -> c.autoReset, v -> c.autoReset = v);
		stepper("Reset delay",
				() -> String.format(Locale.ROOT, "%.1fs", c.resetDelayTicks / 20.0),
				() -> c.resetDelayTicks = Math.max(5, c.resetDelayTicks - 5),
				() -> c.resetDelayTicks = Math.min(600, c.resetDelayTicks + 5));
		toggle("Look at trapdoor first", () -> c.rotateToTrapdoor, v -> c.rotateToTrapdoor = v);
		stepper("Max reach",
				() -> String.format(Locale.ROOT, "%.1f", c.maxReach),
				() -> c.maxReach = Math.max(1.0, Math.round((c.maxReach - 0.1) * 10.0) / 10.0),
				() -> c.maxReach = Math.min(6.0, Math.round((c.maxReach + 0.1) * 10.0) / 10.0));
		stepper("Cooldown per player",
				() -> c.cooldownSeconds + "s",
				() -> c.cooldownSeconds = Math.max(0, c.cooldownSeconds - 1),
				() -> c.cooldownSeconds = Math.min(600, c.cooldownSeconds + 1));
		toggle("Require pearl in chamber", () -> c.requirePearlPresent, v -> c.requirePearlPresent = v);
		stepper("Link radius",
				() -> c.linkRadius + " blocks",
				() -> c.linkRadius = Math.max(1, c.linkRadius - 1),
				() -> c.linkRadius = Math.min(6, c.linkRadius + 1));
	}

	private void buildWalk() {
		BotConfig c = cfg();
		header("Walking");
		toggle("Walk to the trapdoor", () -> c.walkToTrapdoor, v -> c.walkToTrapdoor = v);
		stepper("Max walk distance",
				() -> c.walkRange + " blocks",
				() -> c.walkRange = Math.max(4, c.walkRange - 4),
				() -> c.walkRange = Math.min(128, c.walkRange + 4));
		toggle("Sprint while walking", () -> c.walkSprint, v -> c.walkSprint = v);
		toggle("Walk back afterwards", () -> c.returnHome, v -> c.returnHome = v);
		note("Needs loaded chunks and a clear route. Doors are not opened.");
	}

	private void buildGive() {
		BotConfig c = cfg();
		header("Pearl refill");
		toggle("Drop a pearl for the player", () -> c.givePearl, v -> c.givePearl = v);
		stepper("Pearls to drop",
				() -> String.valueOf(c.givePearlCount),
				() -> c.givePearlCount = Math.max(1, c.givePearlCount - 1),
				() -> c.givePearlCount = Math.min(16, c.givePearlCount + 1));
		stepper("Wait for the player",
				() -> (c.giveWaitTicks / 20) + "s",
				() -> c.giveWaitTicks = Math.max(20, c.giveWaitTicks - 20),
				() -> c.giveWaitTicks = Math.min(600, c.giveWaitTicks + 20));
		stepper("Player distance",
				() -> String.format(Locale.ROOT, "%.0f blocks", c.giveRadius),
				() -> c.giveRadius = Math.max(2.0, c.giveRadius - 1.0),
				() -> c.giveRadius = Math.min(16.0, c.giveRadius + 1.0));
		note("Drops ender pearls from the bot's own inventory at the player's feet.");
	}

	private void buildChat() {
		BotConfig c = cfg();
		header("Chat");
		cycle("Reply mode", () -> c.replyMode, v -> c.replyMode = v, BotConfig.ReplyMode.values());
		toggle("Listen to public chat", () -> c.listenPublic, v -> c.listenPublic = v);
		toggle("Listen to whispers", () -> c.listenWhispers, v -> c.listenWhispers = v);
		toggle("Reply to unregistered", () -> c.replyToUnregistered, v -> c.replyToUnregistered = v);
		textRow("Whisper command", () -> c.whisperCommand, s -> {
			if (!s.isBlank()) c.whisperCommand = s.strip();
		}, 16, 80);
		textRow("Reply prefix", () -> c.replyPrefix, s -> c.replyPrefix = s, 24, 110);
		textRow("Admins (a, b, c)", () -> String.join(",", c.admins), s -> c.admins = parseNames(s), 120, 150);
	}

	private void buildCommands() {
		BotConfig c = cfg();
		header("Chat commands");
		textRow("Prefix", () -> c.prefix, s -> {
			if (!s.isBlank()) c.prefix = s.strip();
		}, 4, 56);
		for (String[] def : BotConfig.COMMAND_DEFS) {
			final String key = def[0];
			textRow(def[1], () -> c.commands.getOrDefault(key, def[2]), s -> c.commands.put(key, s), 40, 150);
		}
		note("Separate several names with commas, e.g.  pull, pullme, tp");
		note("The bot answers to the prefix + any of the names.");
	}

	private void buildMessages() {
		BotConfig c = cfg();
		String[][] defs = BotConfig.MESSAGE_DEFS;
		int per = Math.max(2, (ph - 136) / 38);
		int pages = (defs.length + per - 1) / per;
		msgPage = Math.max(0, Math.min(msgPage, pages - 1));

		header("Reply texts  (empty = send nothing)");
		int from = msgPage * per;
		int to = Math.min(defs.length, from + per);
		for (int i = from; i < to; i++) {
			final String key = defs[i][0];
			final String title = defs[i][1];
			final String fallback = defs[i][2];
			label(cx, rowY, title, C_SOFT);
			field(cx, rowY + 11, cw, fallback, c.messages.getOrDefault(key, fallback), 120, s -> c.messages.put(key, s));
			rowY += 38;
		}

		pager(pages, msgPage, p -> {
			msgPage = p;
			layoutTab();
		});
		label(cx + 120, py + ph - 56, "{player} {seconds} {n} {reason} {prefix} {pull}", C_DIM);
		cardBottom = py + ph - 66;
	}

	// ------------------------------------------------------------------ list tabs

	private void buildPlayers() {
		BotConfig c = cfg();
		int per = Math.max(3, (ph - 146) / ROW);
		int pages = Math.max(1, (c.players.size() + per - 1) / per);
		playerPage = Math.max(0, Math.min(playerPage, pages - 1));

		header("Registered players (" + c.players.size() + ")");
		int from = playerPage * per;
		int to = Math.min(c.players.size(), from + per);
		if (c.players.isEmpty()) note("Nobody registered yet. Add a name below.");
		for (int i = from; i < to; i++) {
			final String name = c.players.get(i);
			final int chambers = c.chambersOf(name).size();
			int y = rowY;
			rowY += ROW;
			label(cx, y + 5, name, C_TEXT);
			label(cx + 112, y + 5, chambers + (chambers == 1 ? " chamber" : " chambers"), chambers == 0 ? C_BAD : C_DIM);
			int bx = cx + cw - 132;
			button(bx, y, 42, 18, () -> "Pull", false, () -> pullTest(name));
			button(bx + 45, y, 42, 18, () -> "Link", false, () -> linkLooked(name));
			button(bx + 90, y, 42, 18, () -> "Del", false, () -> removePlayer(name));
		}

		pager(pages, playerPage, p -> {
			playerPage = p;
			layoutTab();
		});
		footerField("player name");
		button(cx + 148, py + ph - 36, 76, 18, () -> "Register", true, this::registerTyped);
		label(cx + 232, py + ph - 31, "Link = trapdoor under your crosshair", C_DIM);
		cardBottom = py + ph - 66;
	}

	private void buildPearls() {
		int per = Math.max(3, (ph - 146) / ROW);
		int pages = Math.max(1, (pearlRows.size() + per - 1) / per);
		pearlPage = Math.max(0, Math.min(pearlPage, pages - 1));

		header("Loaded ender pearls (" + pearlRows.size() + ")");
		int from = pearlPage * per;
		int to = Math.min(pearlRows.size(), from + per);
		if (pearlRows.isEmpty()) note("No ender pearls are loaded near the bot.");
		LocalPlayer me = this.minecraft.player;
		for (int i = from; i < to; i++) {
			final PearlTracker.Pearl pearl = pearlRows.get(i);
			final boolean known = pearl.owner != null;
			final String owner = known ? pearl.owner : "unknown";
			final String pos = (int) Math.floor(pearl.entity.getX()) + ", "
					+ (int) Math.floor(pearl.entity.getY()) + ", "
					+ (int) Math.floor(pearl.entity.getZ());
			final String dist = me == null ? "" : String.format(Locale.ROOT, "%.1fm", (double) me.distanceTo(pearl.entity));
			int y = rowY;
			rowY += ROW;
			label(cx, y + 5, owner, known ? C_ACCENT : C_DIM);
			label(cx + 104, y + 5, pos, C_TEXT);
			label(cx + 226, y + 5, dist, C_DIM);
			button(cx + cw - 58, y, 58, 18, () -> "Assign", false, () -> assignPearl(pearl));
		}

		pager(pages, pearlPage, p -> {
			pearlPage = p;
			layoutTab();
		});
		footerField("owner name");
		button(cx + 148, py + ph - 36, 76, 18, () -> "Refresh", false, () -> {
			refreshPearls();
			layoutTab();
		});
		label(cx + 232, py + ph - 31, "Assign = the name in the box", C_DIM);
		cardBottom = py + ph - 66;
	}

	// ------------------------------------------------------------------ actions

	private void refreshPearls() {
		List<PearlTracker.Pearl> rows = new ArrayList<>(PullMePlsClient.tracker().snapshot());
		LocalPlayer me = this.minecraft == null ? null : this.minecraft.player;
		if (me != null) rows.sort(Comparator.comparingDouble(p -> p.entity.distanceToSqr(me)));
		pearlRows = rows;
	}

	private void registerTyped() {
		String name = nameText.strip();
		if (!VALID_NAME.matcher(name).matches()) {
			notice = "Enter a valid player name first.";
			return;
		}
		notice = cfg().register(name) ? "Registered " + name + "." : name + " is already registered.";
		PullMePlsClient.tracker().recheckOwner(name);
		cfg().save();
		nameText = "";
		layoutTab();
	}

	private void removePlayer(String name) {
		cfg().unregister(name);
		cfg().save();
		notice = "Removed " + name + " and their chambers.";
		layoutTab();
	}

	private void pullTest(String name) {
		PullManager.Result r = PullMePlsClient.puller().request(name, 0, false);
		notice = r.ok() ? "Pulling for " + name + "." : (r.message().isEmpty() ? "Could not pull for " + name + "." : r.message());
	}

	private void linkLooked(String name) {
		BlockPos pos = lookedAtTrapdoor();
		ClientLevel level = this.minecraft.level;
		if (pos == null || level == null) {
			notice = "Look at a trapdoor (crosshair), then press Link.";
			return;
		}
		cfg().linkChamber(name, PearlTracker.dimensionOf(level), pos.getX(), pos.getY(), pos.getZ(), true);
		cfg().save();
		notice = "Linked " + name + " -> " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
		layoutTab();
	}

	private void assignPearl(PearlTracker.Pearl pearl) {
		String name = nameText.strip();
		if (!VALID_NAME.matcher(name).matches()) {
			notice = "Type the owner's name in the box first.";
			return;
		}
		PullMePlsClient.tracker().assign(pearl, name);
		notice = "Assigned " + name + " to that pearl.";
		refreshPearls();
		layoutTab();
	}

	private BlockPos lookedAtTrapdoor() {
		LocalPlayer me = this.minecraft.player;
		ClientLevel level = this.minecraft.level;
		if (me == null || level == null) return null;
		HitResult hit = me.pick(6.0, 1.0F, false);
		if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = block.getBlockPos();
			if (PearlTracker.isTrapdoor(level.getBlockState(pos))) return pos;
		}
		return null;
	}

	private static List<String> parseNames(String csv) {
		List<String> out = new ArrayList<>();
		for (String part : csv.split(",")) {
			String n = part.strip();
			if (VALID_NAME.matcher(n).matches()) out.add(n);
		}
		return out;
	}

	// ------------------------------------------------------------------ glass controls

	private void addDraw(Renderable renderable) {
		this.addRenderableOnly(renderable);
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private void label(int x, int y, String text, int color) {
		label(x, y, () -> text, color);
	}

	private void label(int x, int y, Supplier<String> text, int color) {
		addDraw((g, mx, my, dt) -> g.text(this.font, text.get(), x, y, color, false));
	}

	private void header(String text) {
		label(cx, rowY + 1, text, C_ACCENT);
		rowY += 18;
	}

	private void note(String text) {
		label(cx, rowY + 3, text, C_DIM);
		rowY += 14;
	}

	/** A pill-shaped glass button. {@code active} paints it lit (selected tab, primary action). */
	private void button(int x, int y, int w, int h, Supplier<String> text, boolean active, Runnable action) {
		addDraw((g, mx, my, dt) -> {
			boolean hover = inside(mx, my, x, y, w, h);
			int fill = active ? (hover ? C_ON_HOVER : C_ON) : (hover ? C_CTRL_HOVER : C_CTRL);
			Glass.fill(g, x, y, w, h, h / 2, fill);
			Glass.border(g, x, y, w, h, h / 2, active ? C_ON_EDGE : C_CTRL_EDGE);
			String s = text.get();
			g.text(this.font, s, x + (w - this.font.width(s)) / 2, y + (h - 8) / 2, active ? C_TEXT : C_SOFT, false);
		});
		hits.add(new Hit(x, y, w, h, action));
	}

	/** A glass on/off switch with a sliding knob. */
	private void switchAt(int x, int y, BooleanSupplier get, Runnable flip) {
		final int w = 34;
		final int h = 16;
		addDraw((g, mx, my, dt) -> {
			boolean on = get.getAsBoolean();
			boolean hover = inside(mx, my, x, y, w, h);
			Glass.fill(g, x, y, w, h, h / 2, on ? (hover ? C_ON_HOVER : C_ON) : (hover ? C_CTRL_HOVER : C_CTRL));
			Glass.border(g, x, y, w, h, h / 2, on ? C_ON_EDGE : C_CTRL_EDGE);
			int knobX = on ? x + w - h + 2 : x + 2;
			Glass.fill(g, knobX, y + 2, h - 4, h - 4, (h - 4) / 2, 0xF5FFFFFF);
		});
		hits.add(new Hit(x - 2, y - 1, w + 4, h + 2, () -> {
			flip.run();
			cfg().save();
		}));
	}

	private void toggle(String name, BooleanSupplier get, Consumer<Boolean> set) {
		int y = rowY;
		rowY += ROW;
		label(cx, y + 5, name, C_TEXT);
		switchAt(cx + cw - 34, y + 1, get, () -> set.accept(!get.getAsBoolean()));
	}

	private <E extends Enum<E>> void cycle(String name, Supplier<E> get, Consumer<E> set, E[] values) {
		int y = rowY;
		rowY += ROW;
		label(cx, y + 5, name, C_TEXT);
		button(cx + cw - 96, y, 96, 18, () -> get.get().name(), false, () -> {
			set.accept(values[(get.get().ordinal() + 1) % values.length]);
			cfg().save();
		});
	}

	private void stepper(String name, Supplier<String> value, Runnable dec, Runnable inc) {
		int y = rowY;
		rowY += ROW;
		label(cx, y + 5, name, C_TEXT);
		int x0 = cx + cw - 104;
		button(x0, y, 18, 18, () -> "-", false, () -> {
			dec.run();
			cfg().save();
		});
		addDraw((g, mx, my, dt) -> {
			String s = value.get();
			g.text(this.font, s, x0 + 18 + (68 - this.font.width(s)) / 2, y + 5, C_TEXT, false);
		});
		button(x0 + 86, y, 18, 18, () -> "+", false, () -> {
			inc.run();
			cfg().save();
		});
	}

	/** Dark glass field with a borderless vanilla EditBox on top (the box handles typing, we handle the look). */
	private void field(int x, int y, int w, String hint, String value, int maxLength, Consumer<String> onChange) {
		EditBox box = new EditBox(this.font, x + 7, y + 5, w - 14, 10, Component.literal(hint));
		box.setBordered(false);
		box.setMaxLength(maxLength);
		box.setValue(value);
		box.setHint(Component.literal(hint));
		box.setResponder(onChange);
		addDraw((g, mx, my, dt) -> {
			Glass.fill(g, x, y, w, 18, 7, 0x46000000);
			Glass.border(g, x, y, w, 18, 7, box.isFocused() ? C_ON_EDGE : 0x4AFFFFFF);
		});
		hits.add(new Hit(x, y, w, 18, () -> this.setFocused(box)));
		this.addRenderableWidget(box);
		boxes.add(box);
	}

	private void textRow(String name, Supplier<String> get, Consumer<String> set, int maxLength, int boxWidth) {
		int y = rowY;
		rowY += ROW;
		label(cx, y + 5, name, C_TEXT);
		field(cx + cw - boxWidth, y, boxWidth, name, get.get(), maxLength, set);
	}

	private void keybindRow() {
		int y = rowY;
		rowY += ROW;
		label(cx, y + 5, "Open GUI key", C_TEXT);
		button(cx + cw - 150, y, 100, 18,
				() -> listening ? "press a key..." : PullMePlsClient.openGuiKey.getTranslatedKeyMessage().getString(),
				listening, () -> listening = true);
		button(cx + cw - 46, y, 46, 18, () -> "Reset", false, () -> {
			listening = false;
			applyKey(PullMePlsClient.openGuiKey.getDefaultKey());
		});
	}

	private void pager(int pages, int current, IntConsumer go) {
		int y = py + ph - 62;
		button(cx, y, 20, 18, () -> "<", false, () -> go.accept(Math.max(0, current - 1)));
		button(cx + 24, y, 20, 18, () -> ">", false, () -> go.accept(Math.min(pages - 1, current + 1)));
		label(cx + 52, y + 5, "Page " + (current + 1) + "/" + pages, C_DIM);
	}

	private void footerField(String hint) {
		field(cx, py + ph - 36, 140, hint, nameText, 16, s -> nameText = s);
	}
}
