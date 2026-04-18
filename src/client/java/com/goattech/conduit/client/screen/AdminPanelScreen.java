package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.client.ConduitSessionHolder;
import com.goattech.conduit.server.ServerBridge;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * In-game Conduit admin panel.
 *
 * <p>Five tabs covering the full hosting surface:
 * <ul>
 *   <li><b>Players</b> &mdash; kick / ban / op / deop, whitelist add-by-name.</li>
 *   <li><b>World</b>   &mdash; difficulty, default game mode, PvP, allow-cheats, save-all.</li>
 *   <li><b>Settings</b> &mdash; render/simulation distance, MOTD, broadcast /say.</li>
 *   <li><b>Console</b> &mdash; merged playit + geyser log tail (auto-refreshing).</li>
 *   <li><b>Network</b> &mdash; public/local address, copy-to-clipboard, playit account status.</li>
 * </ul>
 *
 * <p>All rendering uses the 26.1 {@link GuiGraphicsExtractor} API.
 */
public class AdminPanelScreen extends Screen {

	private enum Tab { PLAYERS, WORLD, SETTINGS, CONSOLE, NETWORK }

	private static final int TAB_WIDTH   = 64;
	private static final int TAB_HEIGHT  = 20;
	private static final int TAB_GAP     = 4;
	private static final int HEADER_Y    = 28;
	private static final int CONTENT_Y   = HEADER_Y + 32;

	private final Screen parent;
	private Tab activeTab = Tab.PLAYERS;

	// Settings tab widgets
	private EditBox sayBox;
	private EditBox motdBox;

	// Players tab widgets
	private EditBox whitelistAddBox;

	// Console tab auto-refresh
	private String consoleSnapshot = "";
	private long lastConsoleRefresh;

	private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};
	private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};

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
			case WORLD    -> buildWorldTab(CONTENT_Y);
			case SETTINGS -> buildSettingsTab(CONTENT_Y);
			case CONSOLE  -> refreshConsole();
			case NETWORK  -> buildNetworkTab(CONTENT_Y);
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
				.bounds(10, y, 120, 20)
				.build());

		// Whitelist add-by-name input
		whitelistAddBox = new EditBox(font, 136, y, 170, 20,
				Component.translatable("conduit.screen.admin.whitelist_add_placeholder"));
		whitelistAddBox.setMaxLength(16);
		whitelistAddBox.setHint(Component.translatable(
				"conduit.screen.admin.whitelist_add_placeholder"));
		addRenderableWidget(whitelistAddBox);

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.whitelist_add"),
						b -> {
							String name = whitelistAddBox.getValue().strip();
							if (!name.isEmpty()) {
								srv.addToWhitelistByName(name);
								whitelistAddBox.setValue("");
							}
						})
				.bounds(312, y, 60, 20).build());
		y += 28;

		// Per-player controls
		for (var ps : srv.listPlayers()) {
			int py = y;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.kick"),
							b -> srv.kick(ps.uuid(), "Kicked via Conduit"))
					.bounds(220, py, 46, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.ban"),
							b -> srv.ban(ps.uuid(), ps.name(), "Banned via Conduit"))
					.bounds(270, py, 46, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.op"),
							b -> srv.opPlayer(ps.uuid(), ps.name()))
					.bounds(320, py, 36, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.deop"),
							b -> srv.deopPlayer(ps.uuid(), ps.name()))
					.bounds(360, py, 46, 20).build());
			y += 22;
		}
	}

	// ── World tab ────────────────────────────────────────────────────────────

	private void buildWorldTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		var cfg = ConduitClient.get().config().values();
		int y = y0;

		// Difficulty cycle
		final Button[] diffHolder = new Button[1];
		diffHolder[0] = Button.builder(
						Component.translatable("conduit.screen.admin.difficulty",
								displayName(srv.currentDifficulty())),
						b -> {
							String next = cycle(DIFFICULTIES, srv.currentDifficulty());
							srv.setDifficulty(next);
							cfg.defaultDifficulty = next;
							ConduitClient.get().config().save();
							diffHolder[0].setMessage(Component.translatable(
									"conduit.screen.admin.difficulty", displayName(next)));
						})
				.bounds(10, y, 200, 20).build();
		addRenderableWidget(diffHolder[0]);

		// Game-mode cycle
		final Button[] gmHolder = new Button[1];
		gmHolder[0] = Button.builder(
						Component.translatable("conduit.screen.admin.gamemode",
								displayName(srv.currentGameMode())),
						b -> {
							String next = cycle(GAME_MODES, srv.currentGameMode());
							srv.setDefaultGameMode(next);
							cfg.defaultGameMode = next;
							ConduitClient.get().config().save();
							gmHolder[0].setMessage(Component.translatable(
									"conduit.screen.admin.gamemode", displayName(next)));
						})
				.bounds(220, y, 200, 20).build();
		addRenderableWidget(gmHolder[0]);
		y += 24;

		// PvP checkbox
		Checkbox pvpBox = Checkbox.builder(
						Component.translatable("conduit.screen.admin.pvp_toggle"), font)
				.pos(10, y)
				.selected(srv.isPvpEnabled())
				.onValueChange((c, v) -> { srv.setPvp(v); cfg.defaultPvp = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(pvpBox);
		y += 24;

		// Save-all
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.save_all"),
						b -> srv.saveAll())
				.bounds(10, y, 200, 20).build());
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

		// MOTD
		motdBox = new EditBox(font, 10, y0 + 56, 240, 20,
				Component.translatable("conduit.screen.host.motd"));
		motdBox.setMaxLength(59);
		motdBox.setValue(cfg.motd);
		addRenderableWidget(motdBox);
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.set_motd"),
						b -> {
							String m = motdBox.getValue();
							srv.setMotd(m);
							cfg.motd = m;
							ConduitClient.get().config().save();
						})
				.bounds(254, y0 + 56, 60, 20).build());

		// /say broadcast
		sayBox = new EditBox(font, 10, y0 + 88, 240, 20,
				Component.translatable("conduit.screen.admin.say_placeholder"));
		sayBox.setMaxLength(200);
		sayBox.setHint(Component.translatable("conduit.screen.admin.say_placeholder"));
		addRenderableWidget(sayBox);

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.say_send"),
						b -> { srv.say(sayBox.getValue()); sayBox.setValue(""); })
				.bounds(254, y0 + 88, 60, 20)
				.build());
	}

	// ── Console tab ──────────────────────────────────────────────────────────

	private void refreshConsole() {
		var sb = new StringBuilder();
		for (String line : ConduitClient.get().playit().logTail(30))  sb.append(line).append('\n');
		for (String line : ConduitClient.get().geyser().logTail(20))  sb.append(line).append('\n');
		consoleSnapshot = sb.toString();
		lastConsoleRefresh = System.currentTimeMillis();
	}

	// ── Network tab ──────────────────────────────────────────────────────────

	private void buildNetworkTab(int y0) {
		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int y = y0 + 56; // leave room for the rendered stats

		if (info != null && info.tunnelJavaAddress() != null) {
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.button.copy_ip"),
							b -> {
								minecraft.keyboardHandler.setClipboard(info.tunnelJavaAddress());
								minecraft.gui.getChat().addClientSystemMessage(
										Component.translatable("conduit.message.copied"));
							})
					.bounds(10, y, 140, 20).build());

			if (info.tunnelBedrockAddress() != null) {
				addRenderableWidget(Button.builder(
								Component.translatable("conduit.button.copy_bedrock_ip"),
								b -> {
									minecraft.keyboardHandler.setClipboard(info.tunnelBedrockAddress());
									minecraft.gui.getChat().addClientSystemMessage(
											Component.translatable("conduit.message.copied"));
								})
						.bounds(160, y, 180, 20).build());
			}
			y += 26;
		}

		// playit account status + link/unlink
		boolean linked = ConduitClient.get().playit().isLinkedAccount();
		Component label = linked
				? Component.translatable("conduit.screen.admin.account_linked")
				: Component.translatable("conduit.screen.admin.account_guest");

		addRenderableWidget(Button.builder(
						linked
								? Component.translatable("conduit.screen.host.unlink")
								: Component.translatable("conduit.screen.host.link_account_optional"),
						b -> {
							if (linked) {
								ConduitClient.get().playit().unlink();
								rebuild();
							} else {
								ConduitClient.get().playit().linkAccountInteractiveAsync(url ->
										minecraft.execute(() -> Util.getPlatform().openUri(url)))
										.whenComplete((r, e) -> minecraft.execute(this::rebuild));
							}
						})
				.bounds(10, y, 220, 20).build());
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFF);

		// Subtitle: hosting state + player count
		ServerBridge srv = ConduitClient.get().server();
		Component sub = Component.translatable("conduit.screen.admin.player_count",
				srv.playerCount(), srv.maxPlayers());
		g.centeredText(font, sub, width / 2, 18, 0xAAAAAA);

		// Auto-refresh console once per second while on the console tab.
		if (activeTab == Tab.CONSOLE
				&& System.currentTimeMillis() - lastConsoleRefresh > 1000) {
			refreshConsole();
		}

		switch (activeTab) {
			case CONSOLE -> renderConsole(g);
			case NETWORK -> renderNetwork(g);
			case PLAYERS -> renderPlayerList(g);
			case WORLD   -> renderWorldHeader(g);
			default      -> {}
		}
	}

	private void renderConsole(GuiGraphicsExtractor g) {
		int y = CONTENT_Y;
		int maxLines = Math.max(8, (height - CONTENT_Y - 20) / 10);
		String[] lines = consoleSnapshot.split("\n");
		int from = Math.max(0, lines.length - maxLines);
		for (int i = from; i < lines.length; i++) {
			g.text(font, lines[i], 10, y, 0x9EB9FF, false);
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
					180, y, 0x55FF55, false);
			y += 14;

			if (info.tunnelBedrockAddress() != null) {
				g.text(font, "Bedrock:", 10, y, 0xAAFFFF, false);
				g.text(font, info.tunnelBedrockAddress(), 180, y, 0x55FFFF, false);
				y += 14;
			}

			g.text(font, Component.translatable("conduit.screen.admin.local_address"),
					10, y, 0xAAFFFF, false);
			g.text(font, "localhost:" + info.localPort(), 180, y, 0xFFFFFF, false);
			y += 14;

			g.text(font, Component.translatable("conduit.screen.admin.started"),
					10, y, 0xAAFFFF, false);
			g.text(font, info.startedAt().toString(), 180, y, 0xFFFFFF, false);
			y += 14;

			boolean linked = ConduitClient.get().playit().isLinkedAccount();
			g.text(font, Component.translatable("conduit.screen.admin.account"),
					10, y, 0xAAFFFF, false);
			g.text(font, linked
					? Component.translatable("conduit.screen.admin.account_linked").getString()
					: Component.translatable("conduit.screen.admin.account_guest").getString(),
					180, y, linked ? 0x55FF55 : 0xFFAA55, false);
		} else {
			g.text(font, Component.translatable("conduit.screen.manage.empty"),
					10, y, 0xAAAAAA, false);
		}
	}

	private void renderPlayerList(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		int y = CONTENT_Y + 30;
		for (var ps : srv.listPlayers()) {
			g.text(font, ps.name() + "  (" + ps.gameMode() + ")",
					10, y + 4, 0xFFFFFF, false);
			y += 22;
		}
	}

	private void renderWorldHeader(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		int y = CONTENT_Y + 52;
		g.text(font, Component.translatable("conduit.screen.admin.world_info",
						displayName(srv.currentDifficulty()),
						displayName(srv.currentGameMode())),
				10, y, 0xAAAAAA, false);
	}

	@Override
	public boolean shouldCloseOnEsc() { return true; }

	@Override
	public void onClose() { minecraft.setScreen(parent); }

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static String cycle(String[] values, String current) {
		String norm = current == null ? "" : current.toLowerCase(Locale.ROOT);
		for (int i = 0; i < values.length; i++) {
			if (values[i].equals(norm)) return values[(i + 1) % values.length];
		}
		return values[0];
	}

	private static String displayName(String key) {
		if (key == null || key.isEmpty()) return "?";
		return Character.toUpperCase(key.charAt(0)) + key.substring(1);
	}

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
