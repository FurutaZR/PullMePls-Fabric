package pullmepls.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Parses incoming chat, runs the {@code $commands}, and sends rate-limited replies. */
public final class ChatHandler {

	private record Parsed(String name, String msg, boolean whisper) {
	}

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

	private final Map<String, Pattern> compiled = new HashMap<>();
	private final Map<String, Long> helpSeen = new HashMap<>();
	private final ArrayDeque<Runnable> outbox = new ArrayDeque<>();
	private int sendTimer;
	private long clock;

	public void clear() {
		outbox.clear();
	}

	public void tick() {
		clock++;
		if (sendTimer > 0) {
			sendTimer--;
			return;
		}
		Runnable next = outbox.poll();
		if (next != null) {
			next.run();
			sendTimer = 20; // at most one bot message per second, avoids spam kicks
		}
	}

	// ------------------------------------------------------------------ incoming

	public void onMessage(String raw) {
		BotConfig cfg = PullMePlsClient.config();
		if (!cfg.enabled || raw == null || cfg.prefix.isEmpty()) return;

		Parsed p = parse(cfg, raw.strip());
		if (p == null) return;
		String text = p.msg().strip();
		if (!text.startsWith(cfg.prefix)) return;

		String[] args = text.substring(cfg.prefix.length()).strip().split("\\s+");
		String cmd = cfg.commandFor(args[0]); // maps any configured name/alias to "pull", "help", ...
		if (cmd == null) return;
		boolean self = p.name().equalsIgnoreCase(selfName());

		switch (cmd) {
			case "pull" -> pull(cfg, p, args, self);
			case "register" -> register(cfg, p, args, self, true);
			case "unregister" -> register(cfg, p, args, self, false);
			case "status" -> status(cfg, p, self);
			case "help" -> help(cfg, p);
			default -> {
			}
		}
	}

	private void pull(BotConfig cfg, Parsed p, String[] args, boolean self) {
		if (self) return;
		if (!cfg.isRegistered(p.name())) {
			if (cfg.replyToUnregistered) reply(p.name(), p.whisper(), cfg.text("not_registered", "player", p.name()));
			return;
		}
		int index = 0;
		if (args.length > 1) {
			try {
				index = Integer.parseInt(args[1]);
			} catch (NumberFormatException ignored) {
				// "$pull" with junk after it just pulls the first chamber
			}
		}
		PullManager.Result r = PullMePlsClient.puller().request(p.name(), index, p.whisper());
		reply(p.name(), p.whisper(), r.message());
	}

	private void register(BotConfig cfg, Parsed p, String[] args, boolean self, boolean add) {
		if (!isAdmin(cfg, p.name(), self)) return;
		if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
			reply(p.name(), p.whisper(), "Usage: " + cfg.prefix + cfg.commandName(add ? "register" : "unregister") + " <player>");
			return;
		}
		String target = args[1];
		if (add) {
			boolean added = cfg.register(target);
			PullMePlsClient.tracker().recheckOwner(target);
			reply(p.name(), p.whisper(), added ? "Registered " + target + "." : target + " is already registered.");
		} else {
			boolean removed = cfg.unregister(target);
			reply(p.name(), p.whisper(), removed ? "Unregistered " + target + "." : target + " was not registered.");
		}
		cfg.save();
	}

	private void status(BotConfig cfg, Parsed p, boolean self) {
		if (!isAdmin(cfg, p.name(), self)) return;
		reply(p.name(), p.whisper(), cfg.players.size() + " player(s), " + cfg.chambers.size()
				+ " chamber(s), " + PullMePlsClient.tracker().snapshot().size() + " pearl(s) loaded.");
	}

	private void help(BotConfig cfg, Parsed p) {
		String key = p.name().toLowerCase(Locale.ROOT);
		Long last = helpSeen.get(key);
		if (last != null && clock - last < 200) return; // one help reply per 10s per player
		helpSeen.put(key, clock);
		reply(p.name(), p.whisper(), cfg.text("help", "prefix", cfg.prefix, "pull", cfg.commandName("pull"), "player", p.name()));
	}

	private static boolean isAdmin(BotConfig cfg, String name, boolean self) {
		return self || cfg.isAdmin(name);
	}

	// ------------------------------------------------------------------ parsing

	private Parsed parse(BotConfig cfg, String line) {
		if (cfg.listenWhispers) {
			Parsed p = match(cfg.whisperPatterns, line, true);
			if (p != null) return p;
		}
		if (cfg.listenPublic) {
			return match(cfg.publicPatterns, line, false);
		}
		return null;
	}

	private Parsed match(List<String> regexes, String line, boolean whisper) {
		for (String re : regexes) {
			Pattern pattern = compiled.computeIfAbsent(re, ChatHandler::compile);
			if (pattern == null) continue;
			Matcher m = pattern.matcher(line);
			if (m.matches()) return new Parsed(m.group("name"), m.group("msg"), whisper);
		}
		return null;
	}

	private static Pattern compile(String regex) {
		try {
			return Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			PullMePlsClient.LOG.warn("Ignoring invalid chat pattern: {}", regex);
			return null;
		}
	}

	// ------------------------------------------------------------------ outgoing

	public void reply(String to, boolean viaWhisper, String msg) {
		BotConfig cfg = PullMePlsClient.config();
		if (cfg.replyMode == BotConfig.ReplyMode.OFF || msg == null || msg.isBlank()) return;
		boolean whisper = viaWhisper || cfg.replyMode == BotConfig.ReplyMode.WHISPER;
		String text = whisper ? cfg.replyPrefix + msg : cfg.replyPrefix + to + ": " + msg;
		outbox.add(() -> send(whisper, to, text));
		while (outbox.size() > 8) outbox.poll();
	}

	private static void send(boolean whisper, String to, String msg) {
		var connection = Minecraft.getInstance().getConnection();
		if (connection == null) return;
		if (whisper) {
			connection.sendCommand(PullMePlsClient.config().whisperCommand + " " + to + " " + msg);
		} else {
			connection.sendChat(msg);
		}
	}

	private static String selfName() {
		LocalPlayer me = Minecraft.getInstance().player;
		return me == null ? "" : me.getName().getString();
	}
}
