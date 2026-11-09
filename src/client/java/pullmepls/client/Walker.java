package pullmepls.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Walks the bot to a place by pressing its movement keys, like a player would.
 *
 * <p>The route is found with A* over the blocks the client has loaded. It handles flat ground, one-block
 * step-ups (it jumps), drops of up to three blocks, and avoids water, lava, fire, cactus and other hazards.
 * It does not open doors, swim, climb ladders or bridge gaps.
 */
public final class Walker {

	public enum Status { RUNNING, ARRIVED, FAILED }

	private record Node(int x, int y, int z) {
	}

	private record Open(Node node, double g, double f) {
	}

	/** Names (block translation keys) the bot must never stand in or on. */
	private static final String[] HAZARDS = {
			"lava", "fire", "cactus", "magma", "sweet_berry", "wither_rose", "powder_snow",
			"cobweb", "portal", "dripstone", "campfire", "end_gateway", "trial_spawner"
	};

	private static final int MAX_NODES = 12000;
	private static final long MAX_SEARCH_NANOS = 60_000_000L;

	// goal (kept so the route can be re-planned when the bot gets stuck)
	private Vec3 reachTarget;
	private double reachDist;
	private BlockPos exactGoal;
	private int range;

	private List<Node> path = new ArrayList<>();
	private int index;
	private int ticks;
	private int maxTicks;
	private int stuck;
	private int replans;
	private double lastX;
	private double lastZ;
	private boolean active;

	public boolean isActive() {
		return active;
	}

	// ------------------------------------------------------------------ starting / stopping

	/** Walk until {@code target} is within {@code reach} blocks of the bot's eyes. */
	public boolean startReach(Minecraft mc, Vec3 target, double reach, int maxDistance) {
		this.reachTarget = target;
		this.reachDist = reach;
		this.exactGoal = null;
		this.range = maxDistance + 4;
		this.replans = 0;
		return plan(mc);
	}

	/** Walk to a specific block (the bot's feet end up in that block's column). */
	public boolean startExact(Minecraft mc, BlockPos goal) {
		this.reachTarget = null;
		this.exactGoal = goal;
		this.range = 64;
		this.replans = 0;
		return plan(mc);
	}

	public void stop(Minecraft mc) {
		active = false;
		path = new ArrayList<>();
		releaseKeys(mc);
	}

	private static void releaseKeys(Minecraft mc) {
		if (mc == null || mc.options == null) return;
		mc.options.keyUp.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keySprint.setDown(false);
	}

	private boolean plan(Minecraft mc) {
		LocalPlayer me = mc.player;
		ClientLevel level = mc.level;
		if (me == null || level == null) return false;
		List<Node> found = search(level, me.blockPosition());
		if (found == null) {
			active = false;
			return false;
		}
		path = found;
		index = 0;
		ticks = 0;
		stuck = 0;
		maxTicks = path.size() * 14 + 120;
		lastX = me.getX();
		lastZ = me.getZ();
		active = true;
		return true;
	}

	// ------------------------------------------------------------------ following the route

	public Status tick(Minecraft mc) {
		LocalPlayer me = mc.player;
		if (!active || me == null || mc.level == null) return Status.FAILED;

		if (++ticks > maxTicks) {
			stop(mc);
			return Status.FAILED;
		}

		// reached the end of the route?
		if (index >= path.size()) {
			if (finalCheck(me)) {
				stop(mc);
				return Status.ARRIVED;
			}
			return replanOrFail(mc);
		}

		Node wp = path.get(index);
		double dx = wp.x() + 0.5 - me.getX();
		double dz = wp.z() + 0.5 - me.getZ();
		double flat = Math.sqrt(dx * dx + dz * dz);

		if (flat < 0.32 && Math.abs(me.getY() - wp.y()) < 1.2) {
			index++;
			return tick(mc);
		}

		// fell or got pushed far away from the route
		if (me.getY() < wp.y() - 4.5 || flat > 6.0) return replanOrFail(mc);

		// face the waypoint and press forward
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		me.setYRot(yaw);
		me.setXRot(0.0F);

		boolean needsJump = wp.y() > me.getY() + 0.15 || stuck > 20;
		mc.options.keyUp.setDown(true);
		mc.options.keyJump.setDown(needsJump);
		mc.options.keySprint.setDown(PullMePlsClient.config().walkSprint && flat > 1.5 && !needsJump);

		// stuck detection
		double moved = Math.hypot(me.getX() - lastX, me.getZ() - lastZ);
		lastX = me.getX();
		lastZ = me.getZ();
		if (moved < 0.02) stuck++;
		else stuck = 0;
		if (stuck > 60) return replanOrFail(mc);

		return Status.RUNNING;
	}

	private boolean finalCheck(LocalPlayer me) {
		if (exactGoal != null) {
			return Math.abs(me.getX() - (exactGoal.getX() + 0.5)) < 1.2
					&& Math.abs(me.getZ() - (exactGoal.getZ() + 0.5)) < 1.2;
		}
		return me.getEyePosition().distanceTo(reachTarget) <= reachDist + 0.8;
	}

	private Status replanOrFail(Minecraft mc) {
		releaseKeys(mc);
		if (++replans > 2 || !plan(mc)) {
			stop(mc);
			return Status.FAILED;
		}
		if (path.isEmpty()) {
			stop(mc);
			return Status.ARRIVED;
		}
		return Status.RUNNING;
	}

	// ------------------------------------------------------------------ A* search

	private boolean isGoal(Node n) {
		if (exactGoal != null) {
			return n.x() == exactGoal.getX() && n.z() == exactGoal.getZ() && Math.abs(n.y() - exactGoal.getY()) <= 1;
		}
		return eyeDistance(n) <= reachDist;
	}

	private double eyeDistance(Node n) {
		double ex = n.x() + 0.5 - reachTarget.x();
		double ey = n.y() + 1.62 - reachTarget.y();
		double ez = n.z() + 0.5 - reachTarget.z();
		return Math.sqrt(ex * ex + ey * ey + ez * ez);
	}

	/**
	 * Lower bound on the cost to the goal. The bot only moves along the four horizontal directions, so distances are
	 * measured the Manhattan way; for the reach goal we subtract the largest Manhattan distance that still lies inside
	 * the reach circle (radius * sqrt(2)) so the estimate never over-shoots.
	 */
	private double heuristic(Node n) {
		if (exactGoal != null) {
			return Math.abs(n.x() - exactGoal.getX()) + Math.abs(n.z() - exactGoal.getZ());
		}
		double dy = n.y() + 1.62 - reachTarget.y();
		double horizontalReach = Math.sqrt(Math.max(0.0, reachDist * reachDist - dy * dy));
		double manhattan = Math.abs(n.x() + 0.5 - reachTarget.x()) + Math.abs(n.z() + 0.5 - reachTarget.z());
		return Math.max(0.0, manhattan - horizontalReach * 1.4143);
	}

	/** @return the steps to take (start excluded, possibly empty), or null when no route was found */
	private List<Node> search(ClientLevel level, BlockPos startPos) {
		Node start = new Node(startPos.getX(), startPos.getY(), startPos.getZ());
		if (isGoal(start)) return new ArrayList<>();

		Cache cache = new Cache(level);
		PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::f));
		Map<Node, Double> best = new HashMap<>();
		Map<Node, Node> parent = new HashMap<>();
		best.put(start, 0.0);
		open.add(new Open(start, 0.0, heuristic(start)));

		long deadline = System.nanoTime() + MAX_SEARCH_NANOS;
		int expanded = 0;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

		while (!open.isEmpty()) {
			Open cur = open.poll();
			Node n = cur.node();
			if (cur.g() > best.getOrDefault(n, Double.MAX_VALUE) + 1e-9) continue;
			if (isGoal(n)) return rebuild(parent, n, start);
			if (++expanded > MAX_NODES || System.nanoTime() > deadline) return null;

			for (int[] d : dirs) {
				int x = n.x() + d[0];
				int z = n.z() + d[1];
				if (Math.abs(x - start.x()) > range || Math.abs(z - start.z()) > range) continue;

				Node next = null;
				double cost = 0;
				if (cache.canStand(x, n.y(), z)) {
					next = new Node(x, n.y(), z);
					cost = 1.0;
				} else if (cache.canStand(x, n.y() + 1, z) && cache.passable(n.x(), n.y() + 2, n.z())) {
					next = new Node(x, n.y() + 1, z);
					cost = 1.7;
				} else if (cache.passable(x, n.y(), z) && cache.passable(x, n.y() + 1, z)) {
					for (int drop = 1; drop <= 3; drop++) {
						int fy = n.y() - drop;
						if (!cache.passable(x, fy, z)) break;
						if (cache.solidGround(x, fy - 1, z)) {
							next = new Node(x, fy, z);
							cost = 1.0 + 0.4 * drop;
							break;
						}
					}
				}
				if (next == null) continue;

				double g = cur.g() + cost;
				if (g < best.getOrDefault(next, Double.MAX_VALUE)) {
					best.put(next, g);
					parent.put(next, n);
					open.add(new Open(next, g, g + heuristic(next)));
				}
			}
		}
		return null;
	}

	private static List<Node> rebuild(Map<Node, Node> parent, Node goal, Node start) {
		List<Node> out = new ArrayList<>();
		Node n = goal;
		while (n != null && !n.equals(start)) {
			out.add(n);
			n = parent.get(n);
		}
		Collections.reverse(out);
		return out;
	}

	// ------------------------------------------------------------------ world queries (cached per search)

	private static final class Cache {
		private final ClientLevel level;
		private final Map<Long, Boolean> pass = new HashMap<>();
		private final Map<Long, Boolean> ground = new HashMap<>();

		Cache(ClientLevel level) {
			this.level = level;
		}

		private static long key(int x, int y, int z) {
			return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFF);
		}

		/** A cell the bot's body can occupy: no collision, no fluid, no hazard. */
		boolean passable(int x, int y, int z) {
			return pass.computeIfAbsent(key(x, y, z), k -> computePassable(x, y, z));
		}

		/** A cell the bot can stand on top of. */
		boolean solidGround(int x, int y, int z) {
			return ground.computeIfAbsent(key(x, y, z), k -> computeGround(x, y, z));
		}

		boolean canStand(int x, int y, int z) {
			return passable(x, y, z) && passable(x, y + 1, z) && solidGround(x, y - 1, z);
		}

		private boolean computePassable(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState s = level.getBlockState(pos);
			if (!s.getCollisionShape(level, pos).isEmpty()) return false;
			if (!s.getFluidState().isEmpty()) return false;
			return !hazard(s);
		}

		private boolean computeGround(int x, int y, int z) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState s = level.getBlockState(pos);
			if (s.getCollisionShape(level, pos).isEmpty()) return false;
			return !hazard(s);
		}

		private static boolean hazard(BlockState s) {
			String id = s.getBlock().getDescriptionId();
			for (String h : HAZARDS) {
				if (id.contains(h)) return true;
			}
			return false;
		}
	}
}
