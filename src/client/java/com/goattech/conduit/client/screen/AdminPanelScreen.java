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
 *   <li><b>World</b>   &mdash; difficulty, default game mode, PvP, allow-cheats, save-all,
 *       plus extended toggles (flight, spawn settings, advancements).</li>
 *   <li><b>Settings</b> &mdash; render/simulation distance, MOTD, broadcast /say,
 *       idle timeout, spawn protection, command blocks.</li>
 *   <li><b>Console</b> &mdash; merged playit + geyser log tail (auto-refreshing).</li>
 *   <li><b>Network</b> &mdash; public/local address, copy-to-clipboard, playit account status.</li>
 * </ul>
 *
 * <p>All rendering uses the 26.1 {@link GuiGraphicsExtractor} API. The layout is built
 * on a consistent two-column grid centered on screen, with proper spacing and alignment.
 */
public class AdminPanelScreen extends Screen {

	private enum Tab { PLAYERS, WORLD, SETTINGS, CONSOLE, NETWORK }

	private static final int TAB_HEIGHT  = 20;
	private static final int TAB_GAP     = 4;
	private static final int HEADER_Y    = 28;
	private static final int CONTENT_Y   = HEADER_Y + 28;

	/** Column width for each half in a two-column layout. */
	private static final int COL_W   = 152;
	private static final int COL_GAP = 6;
	private static final int ROW_H   = 24;

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

		int cx = width / 2;

		// Tab bar — centered
		Tab[] tabs = Tab.values();
		int tabCount = tabs.length;
		int tabWidth = Math.min(72, (width - 140) / tabCount - TAB_GAP);
		int totalTabW = tabCount * tabWidth + (tabCount - 1) * TAB_GAP;
		int tabStartX = cx - totalTabW / 2;

		int tx = tabStartX;
		for (Tab tab : tabs) {
			Button btn = Button.builder(
							Component.translatable("conduit.screen.admin.tab."
									+ tab.name().toLowerCase()),
							b -> { activeTab = tab; rebuild(); })
					.bounds(tx, HEADER_Y, tabWidth, TAB_HEIGHT)
					.build();
			btn.active = (activeTab != tab);
			addRenderableWidget(btn);
			tx += tabWidth + TAB_GAP;
		}

		// Stop hosting button (top-right)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.stop_hosting"),
						b -> ConduitController.stopHosting().whenComplete((v, e) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(width - 116, HEADER_Y, 110, 20)
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

	// ── Computed layout helpers ──────────────────────────────────────────────

	private int colL() { return width / 2 - COL_W - COL_GAP / 2; }
	private int colR() { return width / 2 + COL_GAP / 2; }
	private int fullW() { return COL_W * 2 + COL_GAP; }

	// ── Players tab ──────────────────────────────────────────────────────────

	private void buildPlayersTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		int y = y0;
		int lx = colL();
		int fw = fullW();

		// Whitelist toggle + add-by-name input on a single row
		int toggleW = 130;
		Component wlLabel = srv.isWhitelistEnabled()
				? Component.translatable("conduit.screen.admin.whitelist_on")
				: Component.translatable("conduit.screen.admin.whitelist_off");
		addRenderableWidget(Button.builder(wlLabel,
						b -> { srv.setWhitelistEnabled(!srv.isWhitelistEnabled()); rebuild(); })
				.bounds(lx, y, toggleW, 20)
				.build());

		int inputX = lx + toggleW + COL_GAP;
		int inputW = fw - toggleW - COL_GAP - 56 - COL_GAP;
		whitelistAddBox = new EditBox(font, inputX, y, inputW, 20,
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
				.bounds(lx + fw - 56, y, 56, 20).build());
		y += ROW_H + 4;

		// Per-player controls — each row: name label (rendered) + Kick/Ban/Op/Deop
		for (var ps : srv.listPlayers()) {
			int py = y;
			int btnW = 46;
			int opW  = 36;
			int gap  = 3;
			int bx = lx + fw - (btnW * 2 + opW * 2 + gap * 3);

			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.kick"),
							b -> srv.kick(ps.uuid(), "Kicked via Conduit"))
					.bounds(bx, py, btnW, 20).build());
			bx += btnW + gap;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.ban"),
							b -> srv.ban(ps.uuid(), ps.name(), "Banned via Conduit"))
					.bounds(bx, py, btnW, 20).build());
			bx += btnW + gap;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.op"),
							b -> srv.opPlayer(ps.uuid(), ps.name()))
					.bounds(bx, py, opW, 20).build());
			bx += opW + gap;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.deop"),
							b -> srv.deopPlayer(ps.uuid(), ps.name()))
					.bounds(bx, py, btnW, 20).build());
			y += 22;
		}
	}

	// ── World tab ────────────────────────────────────────────────────────────

	private void buildWorldTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		var cfg = ConduitClient.get().config().values();
		int y = y0;
		int lx = colL();
		int rx = colR();

		// Row 1: Difficulty + Game Mode cycle buttons
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
				.bounds(lx, y, COL_W, 20).build();
		addRenderableWidget(diffHolder[0]);

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
				.bounds(rx, y, COL_W, 20).build();
		addRenderableWidget(gmHolder[0]);
		y += ROW_H;

		// Row 2: PvP + Allow Flight
		Checkbox pvpBox = Checkbox.builder(
						Component.translatable("conduit.screen.admin.pvp_toggle"), font)
				.pos(lx, y).selected(srv.isPvpEnabled())
				.onValueChange((c, v) -> { srv.setPvp(v); cfg.defaultPvp = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(pvpBox);

		Checkbox flightBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_flight"), font)
				.pos(rx, y).selected(srv.isAllowFlightEnabled())
				.onValueChange((c, v) -> { srv.setAllowFlight(v); cfg.allowFlight = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(flightBox);
		y += ROW_H;

		// Row 3: Force Game Mode + Announce Advancements
		Checkbox forceGmBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.force_gamemode"), font)
				.pos(lx, y).selected(srv.isForceGameModeEnabled())
				.onValueChange((c, v) -> { srv.setForceGameMode(v); cfg.forceGameMode = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(forceGmBox);

		Checkbox advBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.announce_advancements"), font)
				.pos(rx, y).selected(srv.isAnnounceAdvancementsOn())
				.onValueChange((c, v) -> { srv.setAnnounceAdvancements(v); cfg.announceAdvancements = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(advBox);
		y += ROW_H;

		// Row 4: Spawn NPCs + Spawn Animals
		Checkbox npcsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_npcs"), font)
				.pos(lx, y).selected(srv.isSpawnNpcsEnabled())
				.onValueChange((c, v) -> { srv.setSpawnNpcs(v); cfg.spawnNpcs = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(npcsBox);

		Checkbox animalsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_animals"), font)
				.pos(rx, y).selected(srv.isSpawnAnimalsEnabled())
				.onValueChange((c, v) -> { srv.setSpawnAnimals(v); cfg.spawnAnimals = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(animalsBox);
		y += ROW_H;

		// Row 5: Spawn Monsters + Command Blocks
		Checkbox monstersBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_monsters"), font)
				.pos(lx, y).selected(srv.isSpawnMonstersEnabled())
				.onValueChange((c, v) -> { srv.setSpawnMonsters(v); cfg.spawnMonsters = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(monstersBox);

		Checkbox cmdBlockBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.enable_command_block"), font)
				.pos(rx, y).selected(srv.isCommandBlockEnabled())
				.onValueChange((c, v) -> { srv.setEnableCommandBlock(v); cfg.enableCommandBlock = v;
					ConduitClient.get().config().save(); })
				.build();
		addRenderableWidget(cmdBlockBox);
		y += ROW_H + 4;

		// Save-all (centered)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.save_all"),
						b -> srv.saveAll())
				.bounds(width / 2 - 100, y, 200, 20).build());
	}

	// ── Settings tab ─────────────────────────────────────────────────────────

	private void buildSettingsTab(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		var cfg = ConduitClient.get().config().values();
		int lx = colL();
		int rx = colR();
		int fw = fullW();
		int y = y0;

		// Row 1: Render distance + Simulation distance
		addRenderableWidget(new IntSlider(lx, y, COL_W, 20,
				cfg.defaultRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> { cfg.defaultRenderDistance = v; srv.setRenderDistance(v);
					ConduitClient.get().config().save(); }));

		addRenderableWidget(new IntSlider(rx, y, COL_W, 20,
				cfg.defaultSimulationDistance, 2, 32,
				"conduit.screen.admin.simulation_distance",
				v -> { cfg.defaultSimulationDistance = v; srv.setSimulationDistance(v);
					ConduitClient.get().config().save(); }));
		y += ROW_H;

		// Row 2: Spawn protection + Idle timeout
		addRenderableWidget(new IntSlider(lx, y, COL_W, 20,
				cfg.spawnProtection, 0, 64,
				"conduit.screen.host.spawn_protection",
				v -> { cfg.spawnProtection = v; srv.setSpawnProtection(v);
					ConduitClient.get().config().save(); }));

		addRenderableWidget(new IntSlider(rx, y, COL_W, 20,
				cfg.playerIdleTimeout, 0, 60,
				"conduit.screen.host.idle_timeout",
				v -> { cfg.playerIdleTimeout = v; srv.setPlayerIdleTimeout(v);
					ConduitClient.get().config().save(); }));
		y += ROW_H;

		// Row 3: MOTD input + Set button
		int setW = 56;
		motdBox = new EditBox(font, lx, y, fw - setW - COL_GAP, 20,
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
				.bounds(lx + fw - setW, y, setW, 20).build());
		y += ROW_H;

		// Row 4: /say broadcast input + Send button
		int sendW = 56;
		sayBox = new EditBox(font, lx, y, fw - sendW - COL_GAP, 20,
				Component.translatable("conduit.screen.admin.say_placeholder"));
		sayBox.setMaxLength(200);
		sayBox.setHint(Component.translatable("conduit.screen.admin.say_placeholder"));
		addRenderableWidget(sayBox);

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.say_send"),
						b -> { srv.say(sayBox.getValue()); sayBox.setValue(""); })
				.bounds(lx + fw - sendW, y, sendW, 20)
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
		int lx = colL();
		int fw = fullW();
		int y = y0 + 70; // leave room for the rendered stats above

		if (info != null && info.tunnelJavaAddress() != null) {
			int btnW = (fw - COL_GAP) / 2;

			addRenderableWidget(Button.builder(
							Component.translatable("conduit.button.copy_ip"),
							b -> {
								minecraft.keyboardHandler.setClipboard(info.tunnelJavaAddress());
								minecraft.gui.getChat().addClientSystemMessage(
										Component.translatable("conduit.message.copied"));
							})
					.bounds(lx, y, btnW, 20).build());

			if (info.tunnelBedrockAddress() != null) {
				addRenderableWidget(Button.builder(
								Component.translatable("conduit.button.copy_bedrock_ip"),
								b -> {
									minecraft.keyboardHandler.setClipboard(info.tunnelBedrockAddress());
									minecraft.gui.getChat().addClientSystemMessage(
											Component.translatable("conduit.message.copied"));
								})
						.bounds(lx + btnW + COL_GAP, y, btnW, 20).build());
			}
			y += ROW_H + 4;
		}

		// playit account status + link/unlink
		boolean linked = ConduitClient.get().playit().isLinkedAccount();

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
				.bounds(lx, y, fw, 20).build());
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 6, 0xFFFFFF);

		// Subtitle: hosting state + player count
		ServerBridge srv = ConduitClient.get().server();
		Component sub = Component.translatable("conduit.screen.admin.player_count",
				srv.playerCount(), srv.maxPlayers());
		g.centeredText(font, sub, width / 2, 17, 0xAAAAAA);

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
			g.text(font, lines[i], colL(), y, 0x9EB9FF, false);
			y += 10;
			if (y > height - 10) break;
		}
	}

	private void renderNetwork(GuiGraphicsExtractor g) {
		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int lx = colL();
		int y = CONTENT_Y;
		if (info != null) {
			int labelX = lx;
			int valueX = lx + 130;

			g.text(font, Component.translatable("conduit.screen.admin.tunnel_address"),
					labelX, y, 0xAAFFFF, false);
			g.text(font, info.tunnelJavaAddress() != null ? info.tunnelJavaAddress() : "-",
					valueX, y, 0x55FF55, false);
			y += 14;

			if (info.tunnelBedrockAddress() != null) {
				g.text(font, "Bedrock:", labelX, y, 0xAAFFFF, false);
				g.text(font, info.tunnelBedrockAddress(), valueX, y, 0x55FFFF, false);
				y += 14;
			}

			g.text(font, Component.translatable("conduit.screen.admin.local_address"),
					labelX, y, 0xAAFFFF, false);
			g.text(font, "localhost:" + info.localPort(), valueX, y, 0xFFFFFF, false);
			y += 14;

			g.text(font, Component.translatable("conduit.screen.admin.started"),
					labelX, y, 0xAAFFFF, false);
			g.text(font, info.startedAt().toString(), valueX, y, 0xFFFFFF, false);
			y += 14;

			boolean linked = ConduitClient.get().playit().isLinkedAccount();
			g.text(font, Component.translatable("conduit.screen.admin.account"),
					labelX, y, 0xAAFFFF, false);
			g.text(font, linked
					? Component.translatable("conduit.screen.admin.account_linked").getString()
					: Component.translatable("conduit.screen.admin.account_guest").getString(),
					valueX, y, linked ? 0x55FF55 : 0xFFAA55, false);
		} else {
			g.text(font, Component.translatable("conduit.screen.manage.empty"),
					lx, y, 0xAAAAAA, false);
		}
	}

	private void renderPlayerList(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		int lx = colL();
		int y = CONTENT_Y + 30;
		for (var ps : srv.listPlayers()) {
			g.text(font, ps.name() + "  (" + ps.gameMode() + ")",
					lx, y + 4, 0xFFFFFF, false);
			y += 22;
		}
	}

	private void renderWorldHeader(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		int y = CONTENT_Y - 4;
		g.centeredText(font, Component.translatable("conduit.screen.admin.world_info",
						displayName(srv.currentDifficulty()),
						displayName(srv.currentGameMode())),
				width / 2, y, 0xAAAAAA);
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
