package pullmepls.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes {@code $pull} requests one at a time:
 * walk to the trapdoor if needed -> look at it -> right-click it (closes it by default) -> wait ->
 * optionally click it again to re-arm -> optionally drop a spare pearl for the player -> walk back home.
 */
public final class PullManager {

	public record Result(boolean ok, String message) {
	}

	private enum Stage { START, WALK, ROTATE, INTERACT, WAIT_RESET, RESET, GIVE_WAIT, GIVE }

	private static final class Task {
		final String requester;
		final boolean whisper;
		final BotConfig.Chamber chamber;
		Stage stage = Stage.START;
		int ticks;
		int given;
		boolean wasOpen;

		Task(String requester, boolean whisper, BotConfig.Chamber chamber) {
			this.requester = requester;
			this.whisper = whisper;
			this.chamber = chamber;
		}
	}

	private record Aim(Vec3 hit, Direction face) {
	}

	private final ArrayDeque<Task> queue = new ArrayDeque<>();
	private final Map<String, Long> cooldowns = new HashMap<>();
	private final Walker walker = new Walker();
	private Task current;
	/** Where the bot stood before it first walked away; it returns here when the queue is empty. */
	private BlockPos home;
	private boolean returning;
	private long clock;

	public void clear() {
		queue.clear();
		current = null;
		home = null;
		returning = false;
		walker.stop(Minecraft.getInstance());
	}

	// ------------------------------------------------------------------ requesting

	/**
	 * @param index 1-based chamber number, or 0 to take the first chamber that currently holds a pearl
	 */
	public Result request(String requester, int index, boolean whisper) {
		BotConfig cfg = PullMePlsClient.config();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) return new Result(false, "Bot is not in a world.");

		String key = requester.toLowerCase(Locale.ROOT);
		long cooldownTicks = cfg.cooldownSeconds * 20L;
		Long last = cooldowns.get(key);
		if (last != null && clock - last < cooldownTicks) {
			long left = (cooldownTicks - (clock - last) + 19) / 20;
			return new Result(false, cfg.text("cooldown", "seconds", String.valueOf(left), "player", requester));
		}
		if (isQueued(requester)) return new Result(false, cfg.text("already", "player", requester));

		List<BotConfig.Chamber> mine = cfg.chambersOf(requester);
		if (mine.isEmpty()) return new Result(false, cfg.text("no_chamber", "player", requester));

		BotConfig.Chamber chosen = null;
		String problem = null;
		if (index > 0) {
			if (index > mine.size()) {
				return new Result(false, cfg.text("bad_index", "n", String.valueOf(mine.size()), "player", requester));
			}
			chosen = mine.get(index - 1);
			problem = validate(mc, cfg, chosen, requester);
		} else {
			for (BotConfig.Chamber c : mine) {
				String p = validate(mc, cfg, c, requester);
				if (p == null) {
					chosen = c;
					problem = null;
					break;
				}
				problem = p; // remember the last reason in case nothing is usable
			}
			if (chosen == null) {
				return new Result(false, cfg.text("cant_pull", "reason", String.valueOf(problem), "player", requester));
			}
		}
		if (problem != null) {
			return new Result(false, cfg.text("cant_pull", "reason", problem, "player", requester));
		}

		queue.add(new Task(requester, whisper, chosen));
		cooldowns.put(key, clock);
		return new Result(true, cfg.text("pulling", "player", requester));
	}

	private boolean isQueued(String requester) {
		if (current != null && current.requester.equalsIgnoreCase(requester)) return true;
		for (Task t : queue) {
			if (t.requester.equalsIgnoreCase(requester)) return true;
		}
		return false;
	}

	/** @return null when the chamber is usable right now, otherwise a short reason */
	private String validate(Minecraft mc, BotConfig cfg, BotConfig.Chamber c, String owner) {
		ClientLevel level = mc.level;
		LocalPlayer me = mc.player;
		if (!c.dimension.equals(PearlTracker.dimensionOf(level))) return cfg.text("r_dimension");

		BlockPos pos = new BlockPos(c.x, c.y, c.z);
		BlockState st = level.getBlockState(pos);
		if (!PearlTracker.isTrapdoor(st) || !st.hasProperty(BlockStateProperties.OPEN)) return cfg.text("r_unloaded");

		double dist = me.getEyePosition().distanceTo(center(pos));
		if (dist > cfg.maxReach + 0.9) {
			if (!cfg.walkToTrapdoor || dist > cfg.walkRange) return cfg.text("r_far");
		}

		if (cfg.requirePearlPresent) {
			PearlTracker.Pearl pearl = PullMePlsClient.tracker().pearlNear(
					pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, cfg.linkRadius + 1.5, owner);
			if (pearl == null) return cfg.text("r_nopearl");
		}
		return null;
	}

	private static Vec3 center(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	// ------------------------------------------------------------------ ticking

	public void tick(Minecraft mc) {
		clock++;
		if (current == null) {
			current = queue.poll();
			if (current != null && returning) {
				walker.stop(mc);
				returning = false;
			}
		}
		if (current == null) {
			walkHome(mc);
			return;
		}
		Task t = current;
		try {
			step(mc, t);
		} catch (Throwable e) {
			PullMePlsClient.LOG.error("Pull failed", e);
			finish(t, false, "Internal error, check the log.");
		}
	}

	/** Idle: walk back to the spot the bot left. */
	private void walkHome(Minecraft mc) {
		BotConfig cfg = PullMePlsClient.config();
		if (home == null || !cfg.returnHome || mc.player == null) {
			returning = false;
			return;
		}
		if (!returning) {
			double dx = mc.player.getX() - (home.getX() + 0.5);
			double dz = mc.player.getZ() - (home.getZ() + 0.5);
			if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0) {
				home = null;
				return;
			}
			if (!walker.startExact(mc, home)) {
				home = null;
				return;
			}
			returning = true;
		}
		if (walker.tick(mc) != Walker.Status.RUNNING) {
			walker.stop(mc);
			returning = false;
			home = null;
		}
	}

	private void step(Minecraft mc, Task t) {
		BotConfig cfg = PullMePlsClient.config();
		LocalPlayer me = mc.player;
		ClientLevel level = mc.level;
		if (me == null || level == null) {
			finish(t, false, null);
			return;
		}

		BlockPos pos = new BlockPos(t.chamber.x, t.chamber.y, t.chamber.z);
		BlockState st = level.getBlockState(pos);
		if (!PearlTracker.isTrapdoor(st) || !st.hasProperty(BlockStateProperties.OPEN)) {
			finish(t, false, cfg.text("cant_pull", "reason", cfg.text("r_unloaded"), "player", t.requester));
			return;
		}
		boolean open = st.getValue(BlockStateProperties.OPEN);

		switch (t.stage) {
			case START -> {
				if (me.getEyePosition().distanceTo(center(pos)) <= cfg.maxReach - 0.4) {
					t.stage = Stage.ROTATE;
					return;
				}
				if (!cfg.walkToTrapdoor) {
					finish(t, false, cfg.text("cant_pull", "reason", cfg.text("r_far"), "player", t.requester));
					return;
				}
				if (home == null) home = me.blockPosition();
				if (!walker.startReach(mc, center(pos), Math.max(1.0, cfg.maxReach - 0.5), cfg.walkRange)) {
					finish(t, false, cfg.text("walk_failed", "player", t.requester));
					return;
				}
				send(t, "walking");
				t.stage = Stage.WALK;
			}
			case WALK -> {
				Walker.Status s = walker.tick(mc);
				if (s == Walker.Status.FAILED) {
					finish(t, false, cfg.text("walk_failed", "player", t.requester));
				} else if (s == Walker.Status.ARRIVED) {
					t.stage = Stage.ROTATE;
					t.ticks = 0;
				}
			}
			case ROTATE -> {
				if (cfg.rotateToTrapdoor) {
					lookAt(me, aimFor(me, level, pos, st).hit());
					t.ticks++;
					if (t.ticks < 2) return; // let the new rotation reach the server first
				}
				t.stage = Stage.INTERACT;
				t.ticks = 0;
			}
			case INTERACT -> {
				boolean needed = switch (cfg.pullMode) {
					case CLOSE -> open;
					case OPEN -> !open;
					case TOGGLE -> true;
				};
				if (!needed) {
					String why = open ? "The trapdoor is already open." : "The trapdoor is already closed.";
					finish(t, false, why);
					return;
				}
				Aim aim = aimFor(me, level, pos, st);
				if (me.getEyePosition().distanceTo(aim.hit()) > cfg.maxReach) {
					finish(t, false, cfg.text("cant_pull", "reason", cfg.text("r_far"), "player", t.requester));
					return;
				}
				if (cfg.rotateToTrapdoor) lookAt(me, aim.hit());
				t.wasOpen = open;
				click(mc, me, pos, aim);
				t.ticks = 0;
				if (cfg.autoReset) t.stage = Stage.WAIT_RESET;
				else if (cfg.givePearl) t.stage = Stage.GIVE_WAIT;
				else finish(t, true, null);
			}
			case WAIT_RESET -> {
				t.ticks++;
				if (t.ticks >= cfg.resetDelayTicks) t.stage = Stage.RESET;
			}
			case RESET -> {
				if (open != t.wasOpen) {
					Aim aim = aimFor(me, level, pos, st);
					if (me.getEyePosition().distanceTo(aim.hit()) <= cfg.maxReach) {
						if (cfg.rotateToTrapdoor) lookAt(me, aim.hit());
						click(mc, me, pos, aim);
					}
				}
				if (cfg.givePearl) {
					t.stage = Stage.GIVE_WAIT;
					t.ticks = 0;
				} else {
					finish(t, true, null);
				}
			}
			case GIVE_WAIT -> {
				t.ticks++;
				Player target = findPlayer(level, t.requester);
				if (target != null && me.distanceTo(target) <= cfg.giveRadius) {
					t.stage = Stage.GIVE;
					t.ticks = 0;
				} else if (t.ticks > cfg.giveWaitTicks) {
					finish(t, true, null); // they never showed up
				}
			}
			case GIVE -> {
				Player target = findPlayer(level, t.requester);
				if (target == null) {
					finish(t, true, null);
					return;
				}
				lookAt(me, target.getEyePosition().add(0.0, -0.6, 0.0));
				t.ticks++;
				if (t.ticks < 3) return; // wait until the server knows where we are looking

				int slot = findPearlSlot(me);
				if (slot < 0) {
					send(t, "no_pearls");
					finish(t, true, null);
					return;
				}
				// button 0 = drop a single item
				mc.gameMode.handleContainerInput(me.inventoryMenu.containerId, slot, 0, ContainerInput.THROW, me);
				t.given++;
				t.ticks = 0;
				if (t.given >= cfg.givePearlCount) {
					send(t, "pearl_given");
					finish(t, true, null);
				}
			}
		}
	}

	private void finish(Task t, boolean ok, String failMessage) {
		if (current == t) current = null;
		walker.stop(Minecraft.getInstance());
		if (!ok && failMessage != null) {
			PullMePlsClient.chat().reply(t.requester, t.whisper, failMessage);
		}
	}

	private void send(Task t, String key) {
		BotConfig cfg = PullMePlsClient.config();
		PullMePlsClient.chat().reply(t.requester, t.whisper, cfg.text(key, "player", t.requester));
	}

	// ------------------------------------------------------------------ inventory + players

	private static Player findPlayer(ClientLevel level, String name) {
		for (Player p : level.players()) {
			if (p.getName().getString().equalsIgnoreCase(name)) return p;
		}
		return null;
	}

	/** Menu slot number of the first ender pearl stack in the main inventory, hotbar or offhand, or -1. */
	private static int findPearlSlot(LocalPlayer me) {
		var slots = me.inventoryMenu.slots;
		for (int i = 9; i < slots.size(); i++) { // 0-8 are crafting and armor
			ItemStack stack = slots.get(i).getItem();
			if (!stack.isEmpty() && stack.is(Items.ENDER_PEARL)) return i;
		}
		return -1;
	}

	// ------------------------------------------------------------------ geometry + interaction

	/** Aims at the middle of the trapdoor's real collision box, on the face that points at the bot. */
	private static Aim aimFor(LocalPlayer me, ClientLevel level, BlockPos pos, BlockState st) {
		AABB b = st.getShape(level, pos).bounds();
		double cx = pos.getX() + (b.minX + b.maxX) / 2.0;
		double cy = pos.getY() + (b.minY + b.maxY) / 2.0;
		double cz = pos.getZ() + (b.minZ + b.maxZ) / 2.0;

		Vec3 eye = me.getEyePosition();
		double dx = eye.x() - cx;
		double dy = eye.y() - cy;
		double dz = eye.z() - cz;
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);

		Direction face;
		double hx = cx;
		double hy = cy;
		double hz = cz;
		if (ay >= ax && ay >= az) {
			if (dy >= 0) {
				face = Direction.UP;
				hy = pos.getY() + b.maxY;
			} else {
				face = Direction.DOWN;
				hy = pos.getY() + b.minY;
			}
		} else if (ax >= az) {
			if (dx >= 0) {
				face = Direction.EAST;
				hx = pos.getX() + b.maxX;
			} else {
				face = Direction.WEST;
				hx = pos.getX() + b.minX;
			}
		} else {
			if (dz >= 0) {
				face = Direction.SOUTH;
				hz = pos.getZ() + b.maxZ;
			} else {
				face = Direction.NORTH;
				hz = pos.getZ() + b.minZ;
			}
		}
		return new Aim(new Vec3(hx, hy, hz), face);
	}

	private static void lookAt(LocalPlayer me, Vec3 point) {
		Vec3 eye = me.getEyePosition();
		double dx = point.x() - eye.x();
		double dy = point.y() - eye.y();
		double dz = point.z() - eye.z();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		me.setYRot(yaw);
		me.setXRot(pitch);
	}

	private static void click(Minecraft mc, LocalPlayer me, BlockPos pos, Aim aim) {
		BlockHitResult hit = new BlockHitResult(aim.hit(), aim.face(), pos, false);
		mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, hit);
	}
}
