package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.client.ConduitSessionHolder;
import com.goattech.conduit.server.ServerBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * In-game Conduit admin panel with four tabs: Players, Settings, Console, and Network.
 *
 * <p>The live status area stays visible while you tweak settings. All rendering uses
 * the 26.1 {@link GuiGraphicsExtractor} API.
 */
public class AdminPanelScreen extends Screen {

	private enum Tab { PLAYERS, SETTINGS, CONSOLE, NETWORK }

	private static final int TAB_WIDTH   = 80;
	private static final int TAB_HEIGHT  = 20;
	private static final int TAB_GAP     = 4;
	private static final int HEADER_Y    = 28;
	private static final int CONTENT_Y   = HEADER_Y + 32;

	private final Screen parent;
	private Tab activeTab = Tab.PLAYERS;

	private EditBox sayBox;
	private String consoleSnapshot = "";

	public AdminPanelScreen(Screen parent) {
		super(Component.translatable("conduit.screen.admin.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rebuild();
	}

	// ── Layout ───────────────────────────────────────────────────────────────

	private void rebuild() {
		clearWidgets();

		// Tab bar
		int tx = 10;
		for (Tab tab : Tab.values()) {
			Button btn = Button.builder(
							Component.translatable("conduit.screen.admin.tab."
									+ tab.name().toLowerCase()),
							b -> { activeTab = tab; rebuild(); })
					.bounds(tx, HEADER_Y, TAB_WIDTH, TAB_HEIGHT)
					.build();
			btn.active = (activeTab != tab);
			addRenderableWidget(btn);
			tx += TAB_WIDTH + TAB_GAP;
		}

		// Stop hosting button (always visible, top-right)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.stop_hosting"),
						b -> ConduitController.stopHosting().whenComplete((v, e) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(width - 120, HEADER_Y, 110, 20)
				.build());

		// Active tab content
		switch (activeTab) {
			case PLAYERS  -> buildPlayersTab(CONTENT_Y);
			case SETTINGS -> buildSettingsTab(CONTENT_Y);
			case CONSOLE  -> buildConsoleTab();
			case NETWORK  -> { /* drawn entirely in extractRenderState */ }
		}
	}

	// ── Players tab ──────────────────────────────────────────────────────────

	private void buildPlayersTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		int y = y0;

		// Whitelist toggle
		Component wlLabel = srv.isWhitelistEnabled()
				? Component.translatable("conduit.screen.admin.whitelist_on")
				: Component.translatable("conduit.screen.admin.whitelist_off");
		addRenderableWidget(Button.builder(wlLabel,
						b -> { srv.setWhitelistEnabled(!srv.isWhitelistEnabled()); rebuild(); })
				.bounds(10, y, 180, 20)
				.build());
		y += 26;

		// Per-player controls
		for (var ps : srv.listPlayers()) {
			int py = y;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.kick"),
							b -> srv.kick(ps.uuid(), "Kicked via Conduit"))
					.bounds(220, py, 60, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.ban"),
							b -> srv.ban(ps.uuid(), ps.name(), "Banned via Conduit"))
					.bounds(284, py, 60, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.op"),
							b -> srv.opPlayer(ps.uuid(), ps.name()))
					.bounds(348, py, 50, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.deop"),
							b -> srv.deopPlayer(ps.uuid(), ps.name()))
					.bounds(402, py, 60, 20).build());
			y += 24;
		}
	}

	// ── Settings tab ─────────────────────────────────────────────────────────

	private void buildSettingsTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		var cfg = ConduitClient.get().config().values();

		addRenderableWidget(new IntSlider(10, y0, 300, 20,
				cfg.defaultRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> { cfg.defaultRenderDistance = v; srv.setRenderDistance(v); }));

		addRenderableWidget(new IntSlider(10, y0 + 24, 300, 20,
				cfg.defaultSimulationDistance, 2, 32,
				"conduit.screen.admin.simulation_distance",
				v -> { cfg.defaultSimulationDistance = v; srv.setSimulationDistance(v); }));

		sayBox = new EditBox(font, 10, y0 + 60, 240, 20,
				Component.translatable("conduit.screen.admin.say_placeholder"));
		sayBox.setMaxLength(200);
		sayBox.setHint(Component.translatable("conduit.screen.admin.say_placeholder"));
		addRenderableWidget(sayBox);

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.say_send"),
						b -> { srv.say(sayBox.getValue()); sayBox.setValue(""); })
				.bounds(254, y0 + 60, 60, 20)
				.build());
	}

	// ── Console tab ──────────────────────────────────────────────────────────

	private void buildConsoleTab() {
		var sb = new StringBuilder();
		for (String line : ConduitClient.get().playit().logTail(40))  sb.append(line).append('\n');
		for (String line : ConduitClient.get().geyser().logTail(20))  sb.append(line).append('\n');
		consoleSnapshot = sb.toString();
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFF);

		switch (activeTab) {
			case CONSOLE -> renderConsole(g);
			case NETWORK -> renderNetwork(g);
			case PLAYERS -> renderPlayerHeader(g);
			default -> {}
		}
	}

	private void renderConsole(GuiGraphicsExtractor g) {
		int y = CONTENT_Y;
		for (String line : consoleSnapshot.split("\n")) {
			g.text(font, line, 10, y, 0x9EB9FF, false);
			y += 10;
			if (y > height - 10) break;
		}
	}

	private void renderNetwork(GuiGraphicsExtractor g) {
		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int y = CONTENT_Y;
		if (info != null) {
			g.text(font, Component.translatable("conduit.screen.admin.tunnel_address"),
					10, y, 0xAAFFFF, false);
			g.text(font, info.tunnelJavaAddress() != null ? info.tunnelJavaAddress() : "-",
					180, y, 0xFFFFFF, false);
			y += 14;

			if (info.tunnelBedrockAddress() != null) {
				g.text(font, "Bedrock:", 10, y, 0xAAFFFF, false);
				g.text(font, info.tunnelBedrockAddress(), 180, y, 0xFFFFFF, false);
				y += 14;
			}

			g.text(font, Component.translatable("conduit.screen.admin.local_address"),
					10, y, 0xAAFFFF, false);
			g.text(font, "localhost:" + info.localPort(), 180, y, 0xFFFFFF, false);
			y += 14;

			g.text(font, "Started:", 10, y, 0xAAFFFF, false);
			g.text(font, info.startedAt().toString(), 180, y, 0xFFFFFF, false);
			y += 14;
		}

		ConduitSessionHolder.State state = ConduitClient.get().session().state();
		g.text(font, "State: " + state, 10, y, 0xFFFF55, false);
	}

	private void renderPlayerHeader(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		Component counter = Component.translatable("conduit.screen.admin.player_count",
				srv.playerCount(), srv.maxPlayers());
		g.text(font, counter, 10, CONTENT_Y - 4, 0xFFFFFF, false);

		int y = CONTENT_Y + 22;
		for (var ps : srv.listPlayers()) {
			g.text(font, ps.name() + "  (" + ps.gameMode() + ")",
					10, y + 4, 0xFFFFFF, false);
			y += 24;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() { return true; }

	@Override
	public void onClose() { minecraft.setScreen(parent); }

	// ── IntSlider ────────────────────────────────────────────────────────────

	/** Concrete integer slider backed by {@link AbstractSliderButton}. */
	private static final class IntSlider extends AbstractSliderButton {
		private final int min;
		private final int max;
		private final String i18nKey;
		private final IntConsumer onChange;

		IntSlider(int x, int y, int w, int h, int initial, int min, int max,
				  String i18nKey, IntConsumer onChange) {
			super(x, y, w, h, Component.empty(), (double) (initial - min) / (max - min));
			this.min = min;
			this.max = max;
			this.i18nKey = i18nKey;
			this.onChange = onChange;
			updateMessage();
		}

		private int currentValue() {
			return (int) Math.round(min + value * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(i18nKey, currentValue()));
		}

		@Override
		protected void applyValue() {
			onChange.accept(currentValue());
		}
	}
}
