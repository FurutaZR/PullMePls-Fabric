package pullmepls.client;

import com.mojang.blaze3d.platform.InputConstants;
import pullmepls.client.gui.PullMePlsScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PullMePlsClient implements ClientModInitializer {

	public static final String MOD_ID = "pullmepls";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	/** GUI key. Default Right Shift; rebindable in the GUI or in Options > Controls. */
	public static KeyMapping openGuiKey;

	private static BotConfig config;
	private static PearlTracker tracker;
	private static PullManager puller;
	private static ChatHandler chat;

	public static BotConfig config() {
		return config;
	}

	public static PearlTracker tracker() {
		return tracker;
	}

	public static PullManager puller() {
		return puller;
	}

	public static ChatHandler chat() {
		return chat;
	}

	@Override
	public void onInitializeClient() {
		config = BotConfig.load();
		tracker = new PearlTracker();
		puller = new PullManager();
		chat = new ChatHandler();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openGuiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key." + MOD_ID + ".open_gui",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_RSHIFT,
				category));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				onTick(mc);
			} catch (Throwable t) {
				LOG.error("PullMePls tick failed", t);
			}
		});

		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			try {
				tracker.onLoad(entity);
			} catch (Throwable t) {
				LOG.error("PullMePls entity load failed", t);
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> tracker.onUnload(entity));

		// System-style chat (most servers and plugins) and signed player chat (vanilla). Both end up in the same parser.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) handleChat(message.getString());
		});
		ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, receivedAt) ->
				handleChat(message.getString()));

		LOG.info("PullMePls loaded");
	}

	private static void handleChat(String line) {
		try {
			chat.onMessage(line);
		} catch (Throwable t) {
			LOG.error("PullMePls chat handling failed", t);
		}
	}

	private static void onTick(Minecraft mc) {
		while (openGuiKey.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new PullMePlsScreen());
		}

		if (mc.player == null || mc.level == null) {
			tracker.clear();
			puller.clear();
			chat.clear();
			return;
		}
		tracker.tick(mc);
		puller.tick(mc);
		chat.tick();
	}

	/** Prints a client-side-only line in the bot's own chat. */
	public static void say(String text) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal("[PullMePls] ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
	}
}
