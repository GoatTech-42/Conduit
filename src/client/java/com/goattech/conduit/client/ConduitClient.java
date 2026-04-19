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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;

/**
 * Client-side entrypoint. Wires up UI hooks and manages the singleton services that
 * back the hosting flow (playit agent, Geyser, config).
 */
public final class ConduitClient implements ClientModInitializer {

	private static ConduitClient INSTANCE;

	private final ConduitConfig config         = new ConduitConfig();
	private final PlayitAgentManager playit    = new PlayitAgentManager(config);
	private final GeyserManager geyser         = new GeyserManager(config);
	private final ServerBridge server          = new ServerBridge();
	private final ServerEntryManager serverList = new ServerEntryManager();
	private final ConduitSessionHolder session = new ConduitSessionHolder();

	/** Returns the singleton instance (available after client init). */
	public static ConduitClient get() { return INSTANCE; }

	// ── Accessors ────────────────────────────────────────────────────────────

	public ConduitConfig       config()     { return config; }
	public PlayitAgentManager  playit()     { return playit; }
	public GeyserManager       geyser()     { return geyser; }
	public ServerBridge        server()     { return server; }
	public ServerEntryManager  serverList() { return serverList; }
	public ConduitSessionHolder session()   { return session; }

	// ── Init ─────────────────────────────────────────────────────────────────

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		ConduitMod.LOGGER.info("Client init starting");

		config.load();

		// Inject Conduit buttons via Fabric's screen event (mapping-stable, no mixins).
		ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof PauseScreen pause) {
				injectHostWorldButton(pause, sw);
			} else if (screen instanceof JoinMultiplayerScreen mp) {
				injectManageServersButton(mp, sw);
			} else if (screen instanceof VideoSettingsScreen vs) {
				lockDistanceSlidersIfHosting(vs);
			}
		});

		// Clean shutdown: stop subprocesses and remove temp server-list entries.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			try {
				playit.shutdownBlocking();
				geyser.shutdownBlocking();
				serverList.restoreSnapshotIfAny();
			} catch (Throwable t) {
				ConduitMod.LOGGER.warn("Error during client shutdown", t);
			}
		});

		ConduitMod.LOGGER.info("Client init complete");
	}

	// ── Button injection ─────────────────────────────────────────────────────

	private static void injectHostWorldButton(PauseScreen screen, int screenWidth) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getSingleplayerServer() == null) return; // not in a SP world

		int w = 120, h = 20;
		Button btn = Button.builder(
						Component.translatable("conduit.button.host_this_world"),
						b -> mc.setScreen(new HostWorldScreen(screen)))
				.bounds(screenWidth - w - 8, 8, w, h)
				.build();
		Screens.getWidgets(screen).add(btn);
	}

	private static void injectManageServersButton(JoinMultiplayerScreen screen, int screenWidth) {
		if (!ConduitClient.get().session().isAnythingRunning()) return;

		int w = 160, h = 20;
		Button btn = Button.builder(
						Component.translatable("conduit.button.manage_servers"),
						b -> Minecraft.getInstance().setScreen(new ManageServersScreen(screen)))
				.bounds(screenWidth - w - 8, 8, w, h)
				.build();
		Screens.getWidgets(screen).add(btn);
	}

	/**
	 * Disables the render-distance and simulation-distance sliders in Video Settings
	 * while Conduit is hosting, since those values are controlled from the admin panel
	 * and must stay in sync with what remote players see.
	 *
	 * <p>We match both the English substring (works in any locale that falls back to
	 * English) and the underlying Minecraft translation key (works when a localized
	 * resource pack is active), so the lock is applied regardless of the UI language.
	 */
	private static void lockDistanceSlidersIfHosting(VideoSettingsScreen screen) {
		if (!ConduitClient.get().session().isAnythingRunning()) return;

		Component lockTooltip = Component.translatable("conduit.message.render_locked");

		for (AbstractWidget widget : Screens.getWidgets(screen)) {
			Component msg = widget.getMessage();
			if (msg == null) continue;

			// Try the translation-key path first (locale-independent).
			boolean match = false;
			if (msg.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
				String key = tc.getKey();
				if (key != null && (key.startsWith("options.renderDistance")
						|| key.startsWith("options.simulationDistance"))) {
					match = true;
				}
			}

			if (!match) {
				String label = msg.getString().toLowerCase(java.util.Locale.ROOT);
				match = label.contains("render distance") || label.contains("simulation distance");
			}

			if (match) {
				widget.active = false;
				widget.setTooltip(Tooltip.create(lockTooltip));
			}
		}
	}
}
