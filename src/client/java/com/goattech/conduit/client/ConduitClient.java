package com.goattech.conduit.client;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.client.screen.HostWorldScreen;
import com.goattech.conduit.client.screen.ManageServersScreen;
import com.goattech.conduit.config.ConduitConfig;
import com.goattech.conduit.geyser.GeyserManager;
import com.goattech.conduit.playit.PlayitAgentManager;
import com.goattech.conduit.server.ServerBridge;
import com.goattech.conduit.server.ServerEntryManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Client-side entrypoint. Wires up the UI hooks and singletons.
 */
public final class ConduitClient implements ClientModInitializer {

	private static ConduitClient INSTANCE;

	private final ConduitConfig config = new ConduitConfig();
	private final PlayitAgentManager playit = new PlayitAgentManager(config);
	private final GeyserManager geyser = new GeyserManager(config);
	private final ServerBridge server = new ServerBridge();
	private final ServerEntryManager serverList = new ServerEntryManager();
	private final ConduitSessionHolder session = new ConduitSessionHolder();

	public static ConduitClient get() {
		return INSTANCE;
	}

	public ConduitConfig config() { return config; }
	public PlayitAgentManager playit() { return playit; }
	public GeyserManager geyser() { return geyser; }
	public ServerBridge server() { return server; }
	public ServerEntryManager serverList() { return serverList; }
	public ConduitSessionHolder session() { return session; }

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		ConduitMod.LOGGER.info("[Conduit] Client init starting");

		// Load config early so playit/geyser manager have their paths ready.
		config.load();

		// Inject "Host This World" into the pause menu, "Manage Conduit Servers" into the
		// Multiplayer screen, and disable render / simulation distance sliders in Video
		// Settings while we're hosting. All three are done via Fabric's ScreenEvents rather
		// than mixins — that's the idiomatic, mapping-stable approach in 26.1.
		ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof PauseScreen pause) {
				injectHostWorldButton(pause, sw, sh);
			} else if (screen instanceof JoinMultiplayerScreen mp) {
				injectManageServersButton(mp, sw, sh);
			} else if (screen instanceof VideoSettingsScreen vs) {
				lockDistanceOptionsIfHosting(vs);
			}
		});

		// Cleanup on shutdown.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			try {
				playit.shutdownBlocking();
				geyser.shutdownBlocking();
				serverList.restoreSnapshotIfAny();
			} catch (Throwable t) {
				ConduitMod.LOGGER.warn("[Conduit] Error during shutdown", t);
			}
		});

		ConduitMod.LOGGER.info("[Conduit] Client init complete");
	}

	private static void injectHostWorldButton(PauseScreen screen, int sw, int sh) {
		// Only show in single-player worlds (where an integrated server is running).
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null) {
			return;
		}

		int w = 120;
		int h = 20;
		int x = sw - w - 8;
		int y = 8;
		Button btn = Button.builder(
				Component.translatable("conduit.button.host_this_world"),
				b -> mc.setScreen(new HostWorldScreen(screen))
		).bounds(x, y, w, h).build();
		net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(btn);
	}

	private static void injectManageServersButton(JoinMultiplayerScreen screen, int sw, int sh) {
		if (!ConduitClient.get().session().isAnythingRunning()) {
			return;
		}
		int w = 160;
		int h = 20;
		int x = sw - w - 8;
		int y = 8;
		Button btn = Button.builder(
				Component.translatable("conduit.button.manage_servers"),
				b -> net.minecraft.client.Minecraft.getInstance()
						.setScreen(new ManageServersScreen(screen))
		).bounds(x, y, w, h).build();
		net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(screen).add(btn);
	}

	/**
	 * Walks the Video Settings screen's widgets and disables the ones whose visible label
	 * contains "render distance" or "simulation distance" — those are controlled by the
	 * admin panel whenever we're hosting so the client's local setting stays in sync with
	 * what remote players see.
	 */
	private static void lockDistanceOptionsIfHosting(VideoSettingsScreen screen) {
		if (!ConduitClient.get().session().isAnythingRunning()) return;

		for (GuiEventListener child : screen.children()) {
			if (!(child instanceof AbstractWidget w)) continue;
			Component msg = w.getMessage();
			if (msg == null) continue;
			String lower = msg.getString().toLowerCase();
			if (lower.contains("render distance") || lower.contains("simulation distance")) {
				w.active = false;
				w.setTooltip(Tooltip.create(
						Component.translatable("conduit.message.render_locked")));
			}
		}
		// Also run again against any widgets Fabric added (Screens#getWidgets is the
		// canonical accessor for that list):
		for (AbstractWidget w : Screens.getWidgets(screen)) {
			Component msg = w.getMessage();
			if (msg == null) continue;
			String lower = msg.getString().toLowerCase();
			if (lower.contains("render distance") || lower.contains("simulation distance")) {
				w.active = false;
				w.setTooltip(Tooltip.create(
						Component.translatable("conduit.message.render_locked")));
			}
		}
	}
}
