package pullmepls.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Settings and the player/chamber registry. Persisted to {@code config/pullmepls.json}. */
public final class BotConfig {

	public enum PullMode { CLOSE, OPEN, TOGGLE }

	public enum ReplyMode { OFF, WHISPER, PUBLIC }

	/** One stasis chamber: the trapdoor that releases {@code owner}'s pearl. */
	public static final class Chamber {
		public String owner = "";
		public String dimension = "";
		public int x, y, z;

		public Chamber() {
		}

		public Chamber(String owner, String dimension, int x, int y, int z) {
			this.owner = owner;
			this.dimension = dimension;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	// ---- general ----
	public boolean enabled = true;
	public String prefix = "$";
	public boolean showOwnerLabels = true;
	/** Link + register anybody whose pearl settles next to a trapdoor (off = only registered players). */
	public boolean autoRegister = false;

	// ---- pull ----
	public PullMode pullMode = PullMode.CLOSE;
	public boolean autoReset = true;
	public int resetDelayTicks = 40;
	public boolean rotateToTrapdoor = true;
	public double maxReach = 4.2;
	public int cooldownSeconds = 5;
	public boolean requirePearlPresent = true;
	public int linkRadius = 3;

	// ---- chat ----
	public ReplyMode replyMode = ReplyMode.WHISPER;
	public boolean listenPublic = true;
	public boolean listenWhispers = true;
	public boolean replyToUnregistered = false;
	public String whisperCommand = "w";
	public List<String> admins = new ArrayList<>();
	/** Regexes with named groups (?<name>..) and (?<msg>..). Anchored on purpose so players cannot spoof a sender. */
	public List<String> publicPatterns = new ArrayList<>(List.of(
			"^<(?<name>[A-Za-z0-9_]{1,16})>\\s+(?<msg>.+)$"));
	public List<String> whisperPatterns = new ArrayList<>(List.of(
			"^(?<name>[A-Za-z0-9_]{1,16}) whispers( to you)?:\\s+(?<msg>.+)$",
			"^\\[(?<name>[A-Za-z0-9_]{1,16}) -> me\\]\\s+(?<msg>.+)$",
			"^From (?<name>[A-Za-z0-9_]{1,16}):\\s+(?<msg>.+)$"));

	// ---- walking ----
	/** Walk to the trapdoor when the bot is out of reach (needs loaded chunks and a walkable route). */
	public boolean walkToTrapdoor = true;
	/** Longest straight-line distance (blocks) the bot will walk for one pull. */
	public int walkRange = 48;
	public boolean walkSprint = true;
	/** After the last queued pull, walk back to where the bot stood before it started walking. */
	public boolean returnHome = true;

	// ---- pearl refill ----
	/** After a pull, drop ender pearls from the bot's inventory at the player's feet. */
	public boolean givePearl = false;
	public int givePearlCount = 1;
	/** How long (ticks) to wait for the player to arrive after the pull. */
	public int giveWaitTicks = 100;
	public double giveRadius = 6.0;

	// ---- reply texts ----
	/** Put in front of every reply the bot sends, e.g. "[Bot] ". */
	public String replyPrefix = "";
	public Map<String, String> messages = new LinkedHashMap<>();

	/** key, label shown in the GUI, default text. An empty text means "send nothing". */
	public static final String[][] MESSAGE_DEFS = {
			{"pulling", "Pull accepted", "Pulling your pearl!"},
			{"walking", "Walking to chamber", ""},
			{"cooldown", "Cooldown", "Cooldown, wait {seconds}s."},
			{"already", "Already pulling", "Already pulling your pearl."},
			{"no_chamber", "No chamber linked", "No chamber is linked to you."},
			{"bad_index", "Bad chamber number", "You only have {n} chamber(s)."},
			{"cant_pull", "Can't pull", "Can't pull: {reason}."},
			{"not_registered", "Not registered", "You are not registered."},
			{"pearl_given", "Pearl given", "Here is a fresh pearl, throw it back in!"},
			{"no_pearls", "Out of pearls", "I am out of spare pearls, sorry."},
			{"walk_failed", "No path found", "I can't find a way to your chamber."},
			{"help", "Help text", "Registered players: {prefix}{pull} [n] releases your pearl."},
			{"r_dimension", "Reason: dimension", "wrong dimension"},
			{"r_unloaded", "Reason: not loaded", "trapdoor not loaded"},
			{"r_far", "Reason: too far", "chamber is too far away"},
			{"r_nopearl", "Reason: no pearl", "no pearl of yours is in the chamber"},
	};

	/**
	 * Fills {@code {name}} style placeholders. Unknown keys fall back to the default text.
	 *
	 * @param vars pairs of placeholder name and value, e.g. {@code "seconds", "5"}
	 */
	public String text(String key, String... vars) {
		String t = messages.get(key);
		if (t == null) {
			t = "";
			for (String[] def : MESSAGE_DEFS) {
				if (def[0].equals(key)) t = def[2];
			}
		}
		for (int i = 0; i + 1 < vars.length; i += 2) {
			t = t.replace("{" + vars[i] + "}", vars[i + 1]);
		}
		return t;
	}

	// ---- commands ----
	/** key, label shown in the GUI, default name. A command may have several names, separated by commas. */
	public static final String[][] COMMAND_DEFS = {
			{"pull", "Pull your pearl", "pull"},
			{"help", "Help", "help"},
			{"register", "Register player", "register"},
			{"unregister", "Unregister player", "unregister"},
			{"status", "Status", "status"},
	};

	/** command key -> comma separated names (typed after the prefix). */
	public Map<String, String> commands = new LinkedHashMap<>();

	/** Every name (lower case, without the prefix) that triggers the command. Never empty. */
	public List<String> commandNames(String key) {
		List<String> out = new ArrayList<>();
		String raw = commands == null ? null : commands.get(key);
		if (raw != null) {
			String pre = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
			for (String part : raw.split(",")) {
				String n = part.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
				if (!pre.isEmpty() && n.startsWith(pre)) n = n.substring(pre.length()); // "$pull" typed by mistake
				if (!n.isEmpty() && !out.contains(n)) out.add(n);
			}
		}
		if (out.isEmpty()) {
			for (String[] def : COMMAND_DEFS) {
				if (def[0].equals(key)) out.add(def[2]);
			}
		}
		return out;
	}

	/** The main (first) name of a command, e.g. what the help text shows. */
	public String commandName(String key) {
		List<String> names = commandNames(key);
		return names.isEmpty() ? key : names.get(0);
	}

	/** Which command a typed word belongs to ({@code "pull"}, {@code "help"}, ...), or null. */
	public String commandFor(String word) {
		String w = word.toLowerCase(Locale.ROOT);
		for (String[] def : COMMAND_DEFS) {
			if (commandNames(def[0]).contains(w)) return def[0];
		}
		return null;
	}

	// ---- registry ----
	public List<String> players = new ArrayList<>();
	public List<Chamber> chambers = new ArrayList<>();

	// ------------------------------------------------------------------ registry helpers

	public boolean isRegistered(String name) {
		if (name == null) return false;
		for (String p : players) {
			if (p.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	/** @return true if the player was newly added */
	public boolean register(String name) {
		if (isRegistered(name)) return false;
		players.add(name);
		return true;
	}

	/** Removes the player and every chamber linked to them. */
	public boolean unregister(String name) {
		boolean removed = players.removeIf(p -> p.equalsIgnoreCase(name));
		chambers.removeIf(c -> c.owner.equalsIgnoreCase(name));
		return removed;
	}

	public boolean isAdmin(String name) {
		for (String a : admins) {
			if (a.equalsIgnoreCase(name)) return true;
		}
		return false;
	}

	public List<Chamber> chambersOf(String owner) {
		List<Chamber> out = new ArrayList<>();
		for (Chamber c : chambers) {
			if (c.owner.equalsIgnoreCase(owner)) out.add(c);
		}
		return out;
	}

	public Chamber chamberAt(String dimension, int x, int y, int z) {
		for (Chamber c : chambers) {
			if (c.x == x && c.y == y && c.z == z && c.dimension.equals(dimension)) return c;
		}
		return null;
	}

	/**
	 * Links {@code owner} to the trapdoor at the given position.
	 *
	 * @param overwrite when false (automatic linking) a trapdoor that already belongs to somebody else is left alone,
	 *                  so a stranger throwing a pearl next to your chamber cannot steal the link
	 * @return true if the registry changed
	 */
	public boolean linkChamber(String owner, String dimension, int x, int y, int z, boolean overwrite) {
		Chamber existing = chamberAt(dimension, x, y, z);
		if (existing != null) {
			if (existing.owner.equalsIgnoreCase(owner)) return false;
			if (!overwrite) return false;
			chambers.remove(existing);
		}
		chambers.add(new Chamber(owner, dimension, x, y, z));
		return true;
	}

	// ------------------------------------------------------------------ persistence

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("pullmepls.json");
	}

	public static BotConfig load() {
		Path f = file();
		if (Files.exists(f)) {
			try (Reader r = Files.newBufferedReader(f)) {
				BotConfig c = GSON.fromJson(r, BotConfig.class);
				if (c != null) {
					c.sanitize();
					return c;
				}
			} catch (Exception e) {
				PullMePlsClient.LOG.error("Could not read {}, falling back to defaults", f, e);
			}
		}
		BotConfig c = new BotConfig();
		c.sanitize();
		c.save();
		return c;
	}

	public void save() {
		Path f = file();
		try {
			Files.createDirectories(f.getParent());
			try (Writer w = Files.newBufferedWriter(f)) {
				GSON.toJson(this, w);
			}
		} catch (IOException e) {
			PullMePlsClient.LOG.error("Could not write {}", f, e);
		}
	}

	private void sanitize() {
		BotConfig d = new BotConfig();
		if (prefix == null || prefix.isBlank()) prefix = d.prefix;
		if (pullMode == null) pullMode = d.pullMode;
		if (replyMode == null) replyMode = d.replyMode;
		if (whisperCommand == null || whisperCommand.isBlank()) whisperCommand = d.whisperCommand;
		if (admins == null) admins = new ArrayList<>();
		if (publicPatterns == null) publicPatterns = d.publicPatterns;
		if (whisperPatterns == null) whisperPatterns = d.whisperPatterns;
		if (players == null) players = new ArrayList<>();
		if (chambers == null) chambers = new ArrayList<>();
		chambers.removeIf(c -> c == null || c.owner == null || c.dimension == null);
		if (messages == null) messages = new LinkedHashMap<>();
		for (String[] def : MESSAGE_DEFS) messages.putIfAbsent(def[0], def[2]);
		if (replyPrefix == null) replyPrefix = "";
		if (commands == null) commands = new LinkedHashMap<>();
		for (String[] def : COMMAND_DEFS) commands.putIfAbsent(def[0], def[2]);
		// configs written by an older version still hold the help text with a hard-coded "pull"
		if ("Registered players: {prefix}pull [n] releases your pearl.".equals(messages.get("help"))) {
			for (String[] def : MESSAGE_DEFS) {
				if (def[0].equals("help")) messages.put("help", def[2]);
			}
		}
		walkRange = Math.max(4, Math.min(128, walkRange));
		givePearlCount = Math.max(1, Math.min(16, givePearlCount));
		giveWaitTicks = Math.max(20, Math.min(600, giveWaitTicks));
		giveRadius = Math.max(2.0, Math.min(16.0, giveRadius));
		resetDelayTicks = Math.max(5, Math.min(600, resetDelayTicks));
		cooldownSeconds = Math.max(0, Math.min(600, cooldownSeconds));
		linkRadius = Math.max(1, Math.min(6, linkRadius));
		maxReach = Math.max(1.0, Math.min(6.0, maxReach));
	}
}
