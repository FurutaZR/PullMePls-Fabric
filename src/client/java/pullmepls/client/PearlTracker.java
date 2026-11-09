package pullmepls.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps a list of every ender pearl the client currently knows about.
 *
 * <p>Owner detection: when a pearl spawns, vanilla sends the thrower's entity id, so {@code getOwner()} works as long
 * as the thrower was loaded on our client at that moment (i.e. they threw it near the bot). Pearls that were already
 * sitting in a chamber when the bot logged in have no known owner; those are restored from the saved chamber registry
 * or can be assigned by hand in the GUI.
 */
public final class PearlTracker {

	public static final class Pearl {
		public final Entity entity;
		/** Thrower's name, or null when unknown. */
		public String owner;
		private BlockPos lastBlock;
		private int stableTicks;
		private int age;
		private boolean linkChecked;
		private String labelShown;

		Pearl(Entity entity) {
			this.entity = entity;
		}
	}

	/** A pearl must sit in the same block this long before we treat it as "in stasis". */
	private static final int STABLE_TICKS = 20;

	private final Map<Integer, Pearl> pearls = new LinkedHashMap<>();

	// ------------------------------------------------------------------ static helpers

	public static boolean isPearl(Entity e) {
		// Matched by class name on purpose: the pearl class moved packages between 1.21.x and 26.x.
		return e.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("enderpearl");
	}

	public static boolean isTrapdoor(BlockState state) {
		return state.is(BlockTags.TRAPDOORS);
	}

	public static String dimensionOf(ClientLevel level) {
		return String.valueOf(level.dimension());
	}

	/** Nearest trapdoor to the given point within {@code radius} blocks, or null. */
	public static BlockPos findNearestTrapdoor(ClientLevel level, double px, double py, double pz, int radius) {
		int r = Math.max(1, Math.min(6, radius));
		int ox = (int) Math.floor(px);
		int oy = (int) Math.floor(py);
		int oz = (int) Math.floor(pz);
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					BlockPos pos = new BlockPos(ox + dx, oy + dy, oz + dz);
					if (!isTrapdoor(level.getBlockState(pos))) continue;
					double ex = pos.getX() + 0.5 - px;
					double ey = pos.getY() + 0.5 - py;
					double ez = pos.getZ() + 0.5 - pz;
					double d = ex * ex + ey * ey + ez * ez;
					if (d < bestDist) {
						bestDist = d;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ events

	public void onLoad(Entity e) {
		if (!isPearl(e)) return;
		Pearl p = new Pearl(e);
		p.owner = resolveOwner(e);
		pearls.put(e.getId(), p);
		applyLabel(p, PullMePlsClient.config());
	}

	public void onUnload(Entity e) {
		pearls.remove(e.getId());
	}

	public void clear() {
		pearls.clear();
	}

	private static String resolveOwner(Entity e) {
		if (e instanceof TraceableEntity traceable) {
			Entity owner = traceable.getOwner();
			if (owner != null) return owner.getName().getString();
		}
		return null;
	}

	// ------------------------------------------------------------------ tick

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) return;
		BotConfig cfg = PullMePlsClient.config();

		Iterator<Pearl> it = pearls.values().iterator();
		while (it.hasNext()) {
			Pearl p = it.next();
			if (p.entity.isRemoved()) {
				it.remove();
				continue;
			}
			// The owner reference can resolve a tick or two after the entity is added, so retry for a short while.
			if (p.owner == null && p.age++ < 40) p.owner = resolveOwner(p.entity);

			BlockPos bp = p.entity.blockPosition();
			if (bp.equals(p.lastBlock)) {
				p.stableTicks++;
			} else {
				p.lastBlock = bp;
				p.stableTicks = 0;
			}

			if (p.stableTicks >= STABLE_TICKS) {
				if (p.owner == null) restoreOwner(p, level, cfg);
				if (p.owner != null && !p.linkChecked) autoLink(p, level, cfg);
			}
			applyLabel(p, cfg);
		}
	}

	/** Pearl already in a chamber at login: take the owner from the saved registry. */
	private void restoreOwner(Pearl p, ClientLevel level, BotConfig cfg) {
		String dim = dimensionOf(level);
		double reach = cfg.linkRadius + 1.5;
		double best = reach * reach;
		String owner = null;
		for (BotConfig.Chamber c : cfg.chambers) {
			if (!c.dimension.equals(dim)) continue;
			double dx = c.x + 0.5 - p.entity.getX();
			double dy = c.y + 0.5 - p.entity.getY();
			double dz = c.z + 0.5 - p.entity.getZ();
			double d = dx * dx + dy * dy + dz * dz;
			if (d <= best) {
				best = d;
				owner = c.owner;
			}
		}
		if (owner != null) p.owner = owner;
	}

	/** Automatic registration/linking once a known-owner pearl has settled next to a trapdoor. */
	private void autoLink(Pearl p, ClientLevel level, BotConfig cfg) {
		p.linkChecked = true;
		BlockPos td = findNearestTrapdoor(level, p.entity.getX(), p.entity.getY(), p.entity.getZ(), cfg.linkRadius);
		if (td == null) return;

		boolean registered = cfg.isRegistered(p.owner);
		if (!registered && !cfg.autoRegister) return;
		if (!registered) cfg.register(p.owner);

		String dim = dimensionOf(level);
		BotConfig.Chamber taken = cfg.chamberAt(dim, td.getX(), td.getY(), td.getZ());
		if (taken != null && !taken.owner.equalsIgnoreCase(p.owner)) {
			PullMePlsClient.say("Ignoring " + p.owner + "'s pearl: that trapdoor already belongs to " + taken.owner + ".");
			return;
		}
		if (cfg.linkChamber(p.owner, dim, td.getX(), td.getY(), td.getZ(), false)) {
			cfg.save();
			PullMePlsClient.say("Linked " + p.owner + " -> trapdoor " + td.getX() + " " + td.getY() + " " + td.getZ());
		}
	}

	// ------------------------------------------------------------------ API used by the GUI / pull logic

	public List<Pearl> snapshot() {
		return new ArrayList<>(pearls.values());
	}

	public int count() {
		return pearls.size();
	}

	/** A registered player's name changed or they were just registered: let the pearls link again. */
	public void recheckOwner(String name) {
		for (Pearl p : pearls.values()) {
			if (p.owner != null && p.owner.equalsIgnoreCase(name)) p.linkChecked = false;
		}
	}

	/** Manually set a pearl's owner (GUI) and link the closest trapdoor to them. */
	public void assign(Pearl p, String name) {
		Minecraft mc = Minecraft.getInstance();
		BotConfig cfg = PullMePlsClient.config();
		p.owner = name;
		p.linkChecked = true;
		if (cfg.register(name)) PullMePlsClient.say("Registered " + name);

		ClientLevel level = mc.level;
		if (level != null) {
			BlockPos td = findNearestTrapdoor(level, p.entity.getX(), p.entity.getY(), p.entity.getZ(), cfg.linkRadius);
			if (td == null) {
				PullMePlsClient.say("Owner set, but no trapdoor within " + cfg.linkRadius
						+ " blocks of that pearl. Use Link in the Players tab.");
			} else {
				cfg.linkChamber(name, dimensionOf(level), td.getX(), td.getY(), td.getZ(), true);
				PullMePlsClient.say("Linked " + name + " -> trapdoor " + td.getX() + " " + td.getY() + " " + td.getZ());
			}
		}
		cfg.save();
		applyLabel(p, cfg);
	}

	/** The closest loaded pearl to a point whose owner is {@code owner} (or unknown). */
	public Pearl pearlNear(double x, double y, double z, double radius, String owner) {
		Pearl best = null;
		double bestDist = radius * radius;
		for (Pearl p : pearls.values()) {
			if (p.entity.isRemoved()) continue;
			if (p.owner != null && owner != null && !p.owner.equalsIgnoreCase(owner)) continue;
			double dx = p.entity.getX() - x;
			double dy = p.entity.getY() - y;
			double dz = p.entity.getZ() - z;
			double d = dx * dx + dy * dy + dz * dz;
			if (d <= bestDist) {
				bestDist = d;
				best = p;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ owner labels

	public void refreshLabels() {
		BotConfig cfg = PullMePlsClient.config();
		for (Pearl p : pearls.values()) applyLabel(p, cfg);
	}

	/**
	 * Owner labels reuse vanilla's name tag renderer: we only set a client-side custom name on the pearl entity,
	 * so no custom rendering code (which changes every Minecraft version) is needed.
	 */
	private static void applyLabel(Pearl p, BotConfig cfg) {
		String wanted = (cfg.showOwnerLabels && p.owner != null) ? p.owner : null;
		if (Objects.equals(wanted, p.labelShown)) return;
		if (wanted != null) {
			p.entity.setCustomName(Component.literal(wanted).withStyle(ChatFormatting.AQUA));
			p.entity.setCustomNameVisible(true);
		} else {
			p.entity.setCustomName(null);
			p.entity.setCustomNameVisible(false);
		}
		p.labelShown = wanted;
	}
}
