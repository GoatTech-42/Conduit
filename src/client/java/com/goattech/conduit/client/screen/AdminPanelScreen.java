package com.goattech.conduit.client.screen;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.client.ConduitSessionHolder;
import com.goattech.conduit.server.ServerBridge;
import com.goattech.conduit.util.ConsoleLog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
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
 *   <li><b>Console</b> &mdash; interleaved live log from the playit agent, Geyser, and
 *       Conduit itself, with an input box at the bottom for typing server commands
 *       (they run on the integrated server as if typed by an operator).</li>
 *   <li><b>Network</b> &mdash; public/local address, copy-to-clipboard, playit account
 *       status + full agent controls (link / unlink / reset / show secret path).</li>
 * </ul>
 *
 * <p>All rendering uses the 26.1 {@link GuiGraphicsExtractor} API. The layout is built
 * on a consistent two-column grid centered on screen, with proper spacing and alignment.
 */
public class AdminPanelScreen extends Screen {

	private enum Tab { PLAYERS, WORLD, SETTINGS, CONSOLE, NETWORK }

	// ── Layout constants ────────────────────────────────────────────────────
	private static final int TAB_HEIGHT  = 20;
	private static final int TAB_GAP     = 4;
	private static final int HEADER_Y    = 28;
	private static final int CONTENT_Y   = HEADER_Y + 28;

	/** Column width for each half in a two-column layout. */
	private static final int COL_W   = 152;
	private static final int COL_GAP = 6;
	private static final int ROW_H   = 24;

	/** Line height for the console text area. */
	private static final int CONSOLE_LINE_H = 10;

	private final Screen parent;
	private Tab activeTab = Tab.PLAYERS;

	// ── Widgets ──
	private EditBox sayBox;
	private EditBox motdBox;
	private EditBox whitelistAddBox;

	// Console tab widgets
	private EditBox consoleInput;
	private Button consoleSendBtn;

	// ── Console state ──
	/** Lines currently displayed, derived from {@link ConsoleLog}. */
	private final java.util.ArrayList<ConsoleLog.Entry> consoleView = new java.util.ArrayList<>();
	/** How many lines up the user has scrolled (0 = pinned to the tail). */
	private int consoleScrollOffset = 0;
	/** Input history (↑/↓ to recall previous commands). */
	private final Deque<String> commandHistory = new ArrayDeque<>();
	private int historyCursor = -1;
	/** The id of the last entry rendered, used to detect new lines for auto-scroll. */
	private long lastSeenConsoleId = 0;
	private long lastConsolePoll;

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

		// Leave room on the right for the "Stop Hosting" button so it never overlaps
		// the tab strip, even on narrow Minecraft windows.
		int stopBtnW = 110;
		int stopReserve = stopBtnW + 12;

		// Tab bar — centered within the available space.
		Tab[] tabs = Tab.values();
		int tabCount = tabs.length;
		int availableW = Math.max(200, width - stopReserve * 2);
		int tabWidth = Math.min(76, availableW / tabCount - TAB_GAP);
		tabWidth = Math.max(40, tabWidth);
		int totalTabW = tabCount * tabWidth + (tabCount - 1) * TAB_GAP;
		int tabStartX = cx - totalTabW / 2;

		int tx = tabStartX;
		for (Tab tab : tabs) {
			Button btn = Button.builder(
							Component.translatable("conduit.screen.admin.tab."
									+ tab.name().toLowerCase(Locale.ROOT)),
							b -> { activeTab = tab; rebuild(); })
					.bounds(tx, HEADER_Y, tabWidth, TAB_HEIGHT)
					.build();
			btn.active = (activeTab != tab);
			addRenderableWidget(btn);
			tx += tabWidth + TAB_GAP;
		}

		// Stop hosting button (top-right). On very narrow windows where it would
		// collide with the tab bar we drop it to a second row instead.
		int stopX = width - stopBtnW - 6;
		int tabsRightEdge = tabStartX + totalTabW;
		int stopY = (tabsRightEdge + 6 > stopX) ? HEADER_Y + TAB_HEIGHT + 4 : HEADER_Y;
		Button stopBtn = Button.builder(
						Component.translatable("conduit.button.stop_hosting"),
						b -> ConduitController.stopHosting().whenComplete((v, e) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(stopX, stopY, stopBtnW, 20)
				.build();
		stopBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
				Component.translatable("conduit.tooltip.stop_hosting")));
		addRenderableWidget(stopBtn);

		// Active tab content
		switch (activeTab) {
			case PLAYERS  -> buildPlayersTab(CONTENT_Y);
			case WORLD    -> buildWorldTab(CONTENT_Y);
			case SETTINGS -> buildSettingsTab(CONTENT_Y);
			case CONSOLE  -> buildConsoleTab();
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
								ConduitMod.console().info("Added '" + name + "' to whitelist");
								whitelistAddBox.setValue("");
							}
						})
				.bounds(lx + fw - 56, y, 56, 20).build());
		y += ROW_H + 4;

		// Per-player controls — each row: name label (rendered in renderPlayerList)
		// + Kick/Ban/Op/Deop. We stop rendering rows that would fall off the bottom
		// of the screen so the controls never draw on top of chat / below the window.
		int rowsBottom = height - 12;
		int maxRows = Math.max(1, (rowsBottom - y) / 22);
		var players = srv.listPlayers();
		int shown = Math.min(players.size(), maxRows);
		for (int i = 0; i < shown; i++) {
			var ps = players.get(i);
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
		// Store the visible count so renderPlayerList agrees with the widget layout.
		this.playersShown = shown;
		this.playersTotal = players.size();
	}

	/** How many player rows were laid out last rebuild (for renderPlayerList). */
	private int playersShown = 0;
	private int playersTotal = 0;

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
						b -> {
							srv.saveAll();
							ConduitMod.console().info("/save-all issued");
						})
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
							ConduitMod.console().info("MOTD set to '" + m + "'");
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

	private void buildConsoleTab() {
		int lx = colL();
		int fw = fullW();

		// Input bar sits at the bottom — everything above it is text.
		int inputY = height - 56;
		int btnW = 56;

		consoleInput = new EditBox(font, lx, inputY, fw - btnW - COL_GAP, 20,
				Component.translatable("conduit.screen.admin.console_input_hint")) {
			@Override
			public boolean keyPressed(KeyEvent event) {
				// 264 = DOWN, 265 = UP  (GLFW)
				int keyCode = event.key();
				if (keyCode == 265) { recallHistory(-1); return true; }
				if (keyCode == 264) { recallHistory(+1); return true; }
				return super.keyPressed(event);
			}
		};
		consoleInput.setMaxLength(256);
		consoleInput.setHint(Component.translatable("conduit.screen.admin.console_input_hint"));
		addRenderableWidget(consoleInput);
		setInitialFocus(consoleInput);

		consoleSendBtn = Button.builder(
						Component.translatable("conduit.screen.admin.console_send"),
						b -> submitConsoleCommand())
				.bounds(lx + fw - btnW, inputY, btnW, 20)
				.build();
		addRenderableWidget(consoleSendBtn);

		// Row beneath the input: clear, pause-autoscroll, copy-all
		int toolY = inputY + 24;
		int toolW = (fw - COL_GAP * 2) / 3;

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.console_clear"),
						b -> {
							ConsoleLog.INSTANCE.clear();
							consoleScrollOffset = 0;
							lastSeenConsoleId = 0;
						})
				.bounds(lx, toolY, toolW, 20).build());

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.console_jump_end"),
						b -> consoleScrollOffset = 0)
				.bounds(lx + toolW + COL_GAP, toolY, toolW, 20).build());

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.console_copy"),
						b -> {
							var sb = new StringBuilder();
							for (ConsoleLog.Entry e : ConsoleLog.INSTANCE.tail(1000)) {
								sb.append(e.formatted()).append('\n');
							}
							minecraft.keyboardHandler.setClipboard(sb.toString());
							minecraft.gui.getChat().addClientSystemMessage(
									Component.translatable("conduit.message.copied_logs"));
						})
				.bounds(lx + (toolW + COL_GAP) * 2, toolY, toolW, 20).build());
	}

	/** Push the current input to the history + dispatch it. */
	private void submitConsoleCommand() {
		String raw = consoleInput.getValue();
		if (raw == null) return;
		String cmd = raw.strip();
		if (cmd.isEmpty()) return;

		commandHistory.addFirst(cmd);
		while (commandHistory.size() > 64) commandHistory.removeLast();
		historyCursor = -1;

		// Echo the typed line into the console.
		ConduitMod.console().append("you", "> " + cmd);

		// Decide whether this is a playit instruction or a server command.
		// Lines that start with `playit ` are sent to the agent's stdin (if
		// it's running). Everything else is treated as a vanilla server
		// command; the UI strips the leading slash.
		if (cmd.toLowerCase(Locale.ROOT).startsWith("playit ")) {
			String tail = cmd.substring("playit ".length()).strip();
			boolean sent = ConduitClient.get().playit().writeStdin(tail);
			if (!sent) {
				ConduitMod.console().warn(
						"playit agent is not running; cannot forward '" + tail + "'");
			}
		} else {
			boolean ok = ConduitClient.get().server().runConsoleCommand(cmd);
			if (!ok) {
				ConduitMod.console().warn(
						"No integrated server is running; cannot execute '" + cmd + "'");
			}
		}

		consoleInput.setValue("");
		consoleScrollOffset = 0; // jump to tail after sending.
	}

	/** Navigate ↑/↓ through recent commands. Direction: -1 = older, +1 = newer. */
	private void recallHistory(int direction) {
		if (commandHistory.isEmpty()) return;
		int max = commandHistory.size();
		if (direction < 0) {
			historyCursor = Math.min(historyCursor + 1, max - 1);
		} else {
			historyCursor = Math.max(historyCursor - 1, -1);
		}
		if (historyCursor < 0) {
			consoleInput.setValue("");
			return;
		}
		// The deque is addFirst'd, so index 0 == most recent.
		var iter = commandHistory.iterator();
		String value = "";
		for (int i = 0; i <= historyCursor && iter.hasNext(); i++) {
			value = iter.next();
		}
		consoleInput.setValue(value);
		consoleInput.moveCursorToEnd(false);
	}

	// ── Network tab ──────────────────────────────────────────────────────────

	private void buildNetworkTab(int y0) {
		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int lx = colL();
		int fw = fullW();
		int y = y0 + 80; // leave room for the rendered stats above

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

		// playit account controls
		boolean linked = ConduitClient.get().playit().isLinkedAccount();

		addRenderableWidget(Button.builder(
						linked
								? Component.translatable("conduit.screen.host.unlink")
								: Component.translatable("conduit.screen.host.link_account_optional"),
						b -> {
							if (linked) {
								ConduitClient.get().playit().unlink();
								ConduitMod.console().info("playit.gg account unlinked");
								rebuild();
							} else {
								ConduitMod.console().info("Opening playit.gg link flow...");
								ConduitClient.get().playit().linkAccountInteractiveAsync(url ->
										minecraft.execute(() -> Util.getPlatform().openUri(url)))
										.whenComplete((r, e) -> minecraft.execute(this::rebuild));
							}
						})
				.bounds(lx, y, fw, 20).build());
		y += ROW_H;

		// Advanced playit commands row: Show secret path + Reset
		int halfW = (fw - COL_GAP) / 2;
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.playit_secret_path"),
						b -> {
							ConduitMod.console().info("Resolving playit secret-path...");
							ConduitClient.get().playit().secretPathAsync()
									.whenComplete((out, err) -> {
										if (err != null) {
											ConduitMod.console().warn(
													"secret-path failed: " + err.getMessage());
										} else {
											ConduitMod.console().info(
													"secret-path: " + (out == null ? "(no output)" : out));
										}
									});
						})
				.bounds(lx, y, halfW, 20).build());

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.admin.playit_reset"),
						b -> {
							ConduitMod.console().warn("Resetting playit agent state...");
							ConduitClient.get().playit().resetAgentAsync()
									.whenComplete((out, err) -> {
										if (err != null) {
											ConduitMod.console().warn(
													"reset failed: " + err.getMessage());
										} else {
											ConduitMod.console().info("playit reset complete.");
											if (out != null && !out.isBlank()) {
												ConduitMod.console().append("playit", out);
											}
											minecraft.execute(this::rebuild);
										}
									});
						})
				.bounds(lx + halfW + COL_GAP, y, halfW, 20).build());
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 6, 0xFFFFFF);

		// Subtitle: player count
		ServerBridge srv = ConduitClient.get().server();
		Component sub = Component.translatable("conduit.screen.admin.player_count",
				srv.playerCount(), srv.maxPlayers());
		g.centeredText(font, sub, width / 2, 17, 0xAAAAAA);

		switch (activeTab) {
			case CONSOLE -> renderConsole(g);
			case NETWORK -> renderNetwork(g);
			case PLAYERS -> renderPlayerList(g);
			case WORLD   -> renderWorldHeader(g);
			default      -> {}
		}
	}

	private void renderConsole(GuiGraphicsExtractor g) {
		int lx = colL();
		int fw = fullW();
		int top = CONTENT_Y;
		int bottom = height - 60;
		int maxLines = Math.max(6, (bottom - top) / CONSOLE_LINE_H);

		// Poll at ~10 Hz to pick up new lines.
		long now = System.currentTimeMillis();
		if (now - lastConsolePoll > 100) {
			lastConsolePoll = now;
			List<ConsoleLog.Entry> snapshot = ConsoleLog.INSTANCE.tail(Math.max(maxLines + 200, 500));
			consoleView.clear();
			consoleView.addAll(snapshot);
			lastSeenConsoleId = ConsoleLog.INSTANCE.latestId();
		}

		int total = consoleView.size();
		int end = total - consoleScrollOffset;
		int start = Math.max(0, end - maxLines);

		// Background panel for readability.
		g.fill(lx - 2, top - 2, lx + fw + 2, bottom + 2, 0x80000000);

		int y = top;
		for (int i = start; i < end && y + CONSOLE_LINE_H <= bottom; i++) {
			ConsoleLog.Entry e = consoleView.get(i);
			int color = colorFor(e.source());
			// Wrap long lines naively so we don't spill off the right edge.
			String text = truncate(e.formatted(), fw - 4);
			g.text(font, text, lx, y, color, false);
			y += CONSOLE_LINE_H;
		}

		// Small scrollback indicator in the bottom-right.
		if (consoleScrollOffset > 0) {
			Component indicator = Component.translatable(
					"conduit.screen.admin.console_scrollback", consoleScrollOffset);
			g.text(font, indicator, lx + fw - font.width(indicator) - 4, bottom - 10,
					0xFFAA55, false);
		}

		// Hint row
		String hint = "\u00A78playit <cmd> -> agent stdin   \u00A77|   <cmd> -> /server command";
		g.text(font, Component.literal(hint), lx, bottom + 2, 0x777777, false);
	}

	private int colorFor(String source) {
		return switch (source == null ? "" : source) {
			case "playit"   -> 0x9EB9FF; // light blue
			case "geyser"   -> 0x66FFAA; // mint
			case "you"      -> 0xFFDD55; // yellow
			case "conduit"  -> 0xCCCCCC; // grey
			case "warn"     -> 0xFFAA55; // orange
			case "error"    -> 0xFF5555; // red
			default         -> 0xFFFFFF;
		};
	}

	private String truncate(String s, int maxPx) {
		int w = font.width(s);
		if (w <= maxPx) return s;
		String ell = "\u2026";
		while (s.length() > 1 && font.width(s + ell) > maxPx) {
			s = s.substring(0, s.length() - 1);
		}
		return s + ell;
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
			y += 14;

			// Show tunnel status (running vs idle).
			boolean agentUp = ConduitClient.get().playit().isRunning();
			g.text(font, Component.translatable("conduit.screen.admin.agent_status"),
					labelX, y, 0xAAFFFF, false);
			g.text(font, agentUp
					? Component.translatable("conduit.screen.admin.agent_running").getString()
					: Component.translatable("conduit.screen.admin.agent_idle").getString(),
					valueX, y, agentUp ? 0x55FF55 : 0xAAAAAA, false);
		} else {
			g.text(font, Component.translatable("conduit.screen.manage.empty"),
					lx, y, 0xAAAAAA, false);
		}
	}

	private void renderPlayerList(GuiGraphicsExtractor g) {
		ServerBridge srv = ConduitClient.get().server();
		int lx = colL();
		int y = CONTENT_Y + 30;
		var players = srv.listPlayers();
		int shown = Math.min(playersShown, players.size());
		for (int i = 0; i < shown; i++) {
			var ps = players.get(i);
			g.text(font, ps.name() + "  (" + ps.gameMode() + ")",
					lx, y + 4, 0xFFFFFF, false);
			y += 22;
		}
		if (playersTotal > shown) {
			g.text(font,
					Component.literal("\u00A77+" + (playersTotal - shown)
							+ " more \u2014 resize window to show"),
					lx, y + 4, 0xAAAAAA, false);
		}
		if (players.isEmpty()) {
			g.text(font, Component.translatable("conduit.screen.admin.no_players"),
					lx, y + 4, 0x888888, false);
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

	// ── Input forwarding ─────────────────────────────────────────────────────

	@Override
	public boolean keyPressed(KeyEvent event) {
		int keyCode = event.key();
		// Console tab: Enter submits, mousewheel/PgUp/PgDn scroll back.
		if (activeTab == Tab.CONSOLE) {
			// 257 = ENTER, 335 = KP_ENTER, 266 = PAGE_UP, 267 = PAGE_DOWN
			if ((keyCode == 257 || keyCode == 335)
					&& consoleInput != null && consoleInput.isFocused()) {
				submitConsoleCommand();
				return true;
			}
			if (keyCode == 266) {
				consoleScrollOffset = Math.min(consoleScrollOffset + 10, consoleView.size());
				return true;
			}
			if (keyCode == 267) {
				consoleScrollOffset = Math.max(consoleScrollOffset - 10, 0);
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (activeTab == Tab.CONSOLE) {
			int step = 3;
			if (scrollY > 0) {
				consoleScrollOffset = Math.min(consoleScrollOffset + step,
						Math.max(0, consoleView.size() - 4));
			} else if (scrollY < 0) {
				consoleScrollOffset = Math.max(consoleScrollOffset - step, 0);
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
