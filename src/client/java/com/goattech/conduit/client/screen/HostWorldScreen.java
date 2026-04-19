package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.config.ConduitConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Configuration screen shown when the user clicks "Host This World" in the pause menu.
 *
 * <p>The screen is organized into clearly separated sections with proper centering and
 * consistent spacing. All widgets are aligned to a two-column grid centered on the
 * screen, with visible section labels between groups.
 *
 * <h3>Layout</h3>
 * <ul>
 *   <li><b>Header</b> (pinned top)   &mdash; title + account subtitle.</li>
 *   <li><b>Scrollable content</b>    &mdash; every settings section (see below). The
 *       mouse wheel, <kbd>PgUp</kbd>/<kbd>PgDn</kbd>, <kbd>Home</kbd>/<kbd>End</kbd>
 *       and a scrollbar on the right all work to navigate when the window is too
 *       short to show everything at once.</li>
 *   <li><b>Footer</b> (pinned bottom) &mdash; status line + Start / Cancel buttons,
 *       always visible regardless of window size.</li>
 * </ul>
 *
 * <h3>Sections</h3>
 * <ol>
 *   <li><b>Core Settings</b> &mdash; game mode, difficulty, max players, render/sim distance.</li>
 *   <li><b>Gameplay Rules</b> &mdash; PvP, allow cheats, allow flight, force game mode, crossplay.</li>
 *   <li><b>World Rules</b> &mdash; spawn protection, spawn NPCs/animals/monsters, announce advancements.</li>
 *   <li><b>Server Info</b> &mdash; MOTD, idle timeout, command blocks.</li>
 *   <li><b>Account</b> &mdash; link / unlink playit.gg (optional).</li>
 * </ol>
 */
public class HostWorldScreen extends Screen {

	private final Screen parent;

	// ── Widgets ──
	private Button gameModeCycle;
	private Button difficultyCycle;
	private Button startButton;
	private Button linkButton;
	private Button unlinkButton;

	// ── Local edit state (not persisted until Start) ──
	private String chosenGameMode;
	private String chosenDifficulty;
	private int chosenMaxPlayers;
	private int chosenRenderDistance;
	private int chosenSimulationDistance;
	private int chosenSpawnProtection;
	private int chosenPlayerIdleTimeout;

	// Checkbox state (read on start)
	private Checkbox crossplayBox;
	private Checkbox allowCheatsBox;
	private Checkbox pvpBox;
	private Checkbox allowFlightBox;
	private Checkbox forceGameModeBox;
	private Checkbox spawnNpcsBox;
	private Checkbox spawnAnimalsBox;
	private Checkbox spawnMonstersBox;
	private Checkbox announceAdvancementsBox;
	private Checkbox enableCommandBlockBox;
	private EditBox motdBox;

	private Component statusLine = Component.translatable("conduit.status.idle");

	/** Column width for widgets in each half of the two-column layout. */
	private static final int COL_W = 152;
	/** Horizontal gap between the two columns. */
	private static final int COL_GAP = 6;
	/** Row height (widget height + vertical gap). */
	private static final int ROW_H = 24;
	/** Vertical space reserved for a section header (rendered text). */
	private static final int SECTION_H = 14;

	/** Height of the fixed header strip (title + subtitle). */
	private static final int HEADER_H = 34;
	/** Height of the fixed footer strip (status + action buttons). */
	private static final int FOOTER_H = 66;
	/** Width of the scrollbar gutter on the right side of the scrollable area. */
	private static final int SCROLLBAR_W = 6;

	// ── Section headers (designed-Y in the virtual content space). ──
	private int headerCoreY;
	private int headerRulesY;
	private int headerWorldY;
	private int headerServerY;
	private int headerAccountY;

	/** Widgets that participate in the scrolling viewport (everything except footer). */
	private final List<AbstractWidget> scrollable = new ArrayList<>();
	/** Each scrollable widget's <em>design</em> Y (its position before scroll offset). */
	private final List<Integer> designY = new ArrayList<>();
	/** Current scroll offset in pixels. 0 = top. */
	private int scrollOffset = 0;
	/** Total height of the virtual content (tallest designed Y + widget height). */
	private int contentHeight = 0;

	private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};
	private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};

	public HostWorldScreen(Screen parent) {
		super(Component.translatable("conduit.screen.host.title"));
		this.parent = parent;

		ConduitConfig.Values cfg = ConduitClient.get().config().values();
		this.chosenGameMode           = cfg.defaultGameMode;
		this.chosenDifficulty         = cfg.defaultDifficulty;
		this.chosenMaxPlayers         = cfg.defaultMaxPlayers;
		this.chosenRenderDistance     = cfg.defaultRenderDistance;
		this.chosenSimulationDistance = cfg.defaultSimulationDistance;
		this.chosenSpawnProtection    = cfg.spawnProtection;
		this.chosenPlayerIdleTimeout  = cfg.playerIdleTimeout;
	}

	// ── Viewport helpers ─────────────────────────────────────────────────────

	private int viewportTop()    { return HEADER_H; }
	private int viewportBottom() { return height - FOOTER_H; }
	private int viewportHeight() { return Math.max(0, viewportBottom() - viewportTop()); }

	/** Register a scrollable widget at its <em>design</em> y and remember it. */
	private <T extends AbstractWidget> T addScrollable(T widget, int dy) {
		scrollable.add(widget);
		designY.add(dy);
		addRenderableWidget(widget);
		return widget;
	}

	private void setScrollOffset(int v) {
		int maxScroll = Math.max(0, contentHeight - viewportHeight());
		scrollOffset = Math.max(0, Math.min(v, maxScroll));
		applyScroll();
	}

	/** Apply the current scroll offset to every scrollable widget's Y and visibility. */
	private void applyScroll() {
		int top = viewportTop();
		int bottom = viewportBottom();
		for (int i = 0; i < scrollable.size(); i++) {
			AbstractWidget w = scrollable.get(i);
			int y = designY.get(i) - scrollOffset;
			w.setY(y);
			// Hide (and disable) widgets whose top edge is above the viewport or whose
			// bottom edge is below it &mdash; keeps clicks from hitting offscreen widgets.
			boolean visible = y + w.getHeight() > top && y < bottom;
			w.visible = visible;
			// Don't toggle active here; we don't know the caller's intent. We just rely
			// on visibility to stop pointer/keyboard events outside the viewport.
		}
	}

	@Override
	protected void init() {
		scrollable.clear();
		designY.clear();

		ConduitConfig.Values cfg = ConduitClient.get().config().values();

		// ── Grid geometry ──
		int cx   = width / 2;
		int colL = cx - COL_W - COL_GAP / 2;
		int colR = cx + COL_GAP / 2;
		// Design-Y starts just below the header with a small breathing gap.
		int y    = HEADER_H + 4;

		// ────────────────────────────── Core Settings ──────────────────────────────
		headerCoreY = y; y += SECTION_H;

		// Row: Game mode + Difficulty
		gameModeCycle = Button.builder(
						Component.translatable("conduit.screen.host.gamemode",
								displayName(chosenGameMode)),
						b -> {
							chosenGameMode = cycle(GAME_MODES, chosenGameMode);
							gameModeCycle.setMessage(Component.translatable(
									"conduit.screen.host.gamemode", displayName(chosenGameMode)));
						})
				.bounds(colL, y, COL_W, 20).build();
		addScrollable(gameModeCycle, y);

		difficultyCycle = Button.builder(
						Component.translatable("conduit.screen.host.difficulty",
								displayName(chosenDifficulty)),
						b -> {
							chosenDifficulty = cycle(DIFFICULTIES, chosenDifficulty);
							difficultyCycle.setMessage(Component.translatable(
									"conduit.screen.host.difficulty", displayName(chosenDifficulty)));
						})
				.bounds(colR, y, COL_W, 20).build();
		addScrollable(difficultyCycle, y);
		y += ROW_H;

		// Row: Max players + Render distance
		addScrollable(new IntSlider(colL, y, COL_W, 20,
				chosenMaxPlayers, 2, 50,
				"conduit.screen.host.max_players",
				v -> chosenMaxPlayers = v), y);

		addScrollable(new IntSlider(colR, y, COL_W, 20,
				chosenRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> chosenRenderDistance = v), y);
		y += ROW_H;

		// Row: Simulation distance + Spawn protection
		addScrollable(new IntSlider(colL, y, COL_W, 20,
				chosenSimulationDistance, 2, 32,
				"conduit.screen.admin.simulation_distance",
				v -> chosenSimulationDistance = v), y);

		addScrollable(new IntSlider(colR, y, COL_W, 20,
				chosenSpawnProtection, 0, 64,
				"conduit.screen.host.spawn_protection",
				v -> chosenSpawnProtection = v), y);
		y += ROW_H + 4;

		// ────────────────────────────── Gameplay Rules ─────────────────────────────
		headerRulesY = y; y += SECTION_H;

		// Row: PvP + Allow Cheats
		pvpBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.pvp"), font)
				.pos(colL, y).selected(cfg.defaultPvp).build();
		addScrollable(pvpBox, y);

		allowCheatsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_cheats"), font)
				.pos(colR, y).selected(cfg.defaultAllowCheats).build();
		addScrollable(allowCheatsBox, y);
		y += ROW_H;

		// Row: Allow Flight + Force Game Mode
		allowFlightBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_flight"), font)
				.pos(colL, y).selected(cfg.allowFlight).build();
		addScrollable(allowFlightBox, y);

		forceGameModeBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.force_gamemode"), font)
				.pos(colR, y).selected(cfg.forceGameMode).build();
		addScrollable(forceGameModeBox, y);
		y += ROW_H;

		// Row: Crossplay + Announce Advancements
		crossplayBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.crossplay"), font)
				.pos(colL, y).selected(cfg.crossplayDefault).build();
		crossplayBox.setTooltip(Tooltip.create(
				Component.translatable("conduit.tooltip.crossplay")));
		addScrollable(crossplayBox, y);

		announceAdvancementsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.announce_advancements"), font)
				.pos(colR, y).selected(cfg.announceAdvancements).build();
		addScrollable(announceAdvancementsBox, y);
		y += ROW_H + 4;

		// ────────────────────────────── World Rules ────────────────────────────────
		headerWorldY = y; y += SECTION_H;

		// Row: Spawn NPCs + Spawn Animals
		spawnNpcsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_npcs"), font)
				.pos(colL, y).selected(cfg.spawnNpcs).build();
		addScrollable(spawnNpcsBox, y);

		spawnAnimalsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_animals"), font)
				.pos(colR, y).selected(cfg.spawnAnimals).build();
		addScrollable(spawnAnimalsBox, y);
		y += ROW_H;

		// Row: Spawn Monsters + Command Blocks
		spawnMonstersBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_monsters"), font)
				.pos(colL, y).selected(cfg.spawnMonsters).build();
		addScrollable(spawnMonstersBox, y);

		enableCommandBlockBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.enable_command_block"), font)
				.pos(colR, y).selected(cfg.enableCommandBlock).build();
		addScrollable(enableCommandBlockBox, y);
		y += ROW_H + 4;

		// ────────────────────────────── Server Info ────────────────────────────────
		headerServerY = y; y += SECTION_H;

		// Row: MOTD (full width)
		int motdW = COL_W * 2 + COL_GAP;
		motdBox = new EditBox(font, colL, y, motdW, 20,
				Component.translatable("conduit.screen.host.motd"));
		motdBox.setMaxLength(59);
		motdBox.setHint(Component.translatable("conduit.screen.host.motd_hint"));
		motdBox.setValue(cfg.motd);
		addScrollable(motdBox, y);
		y += ROW_H;

		// Row: Player idle timeout (full width)
		addScrollable(new IntSlider(colL, y, motdW, 20,
				chosenPlayerIdleTimeout, 0, 60,
				"conduit.screen.host.idle_timeout",
				v -> chosenPlayerIdleTimeout = v), y);
		y += ROW_H + 4;

		// ────────────────────────────── Account ────────────────────────────────────
		headerAccountY = y; y += SECTION_H;

		// Link / Unlink playit.gg account (centered row)
		boolean linked = ConduitClient.get().playit().isLinkedAccount();
		int linkW = 220;
		int unlinkW = 80;
		int linkRowW = linked ? linkW + COL_GAP + unlinkW : linkW;
		int linkX = cx - linkRowW / 2;

		linkButton = Button.builder(
						linked
								? Component.translatable("conduit.screen.host.linked")
								: Component.translatable("conduit.screen.host.link_account_optional"),
						b -> linkAccount())
				.bounds(linkX, y, linkW, 20)
				.build();
		linkButton.active = !linked;
		addScrollable(linkButton, y);

		unlinkButton = Button.builder(
						Component.translatable("conduit.screen.host.unlink"),
						b -> unlinkAccount())
				.bounds(linkX + linkW + COL_GAP, y, unlinkW, 20)
				.build();
		unlinkButton.visible = linked;
		unlinkButton.active = linked;
		addScrollable(unlinkButton, y);
		y += ROW_H + 6;

		// Virtual content reaches to the last row (include its height).
		contentHeight = y + 2;

		// ────────────────────────────── Footer (pinned) ────────────────────────────
		int btnW = 200;
		int footerTop = height - FOOTER_H + 10;
		int footerY = footerTop + 12;   // leaves room for the status line above.

		startButton = Button.builder(
						Component.translatable("conduit.screen.host.start"),
						b -> startHosting())
				.bounds(cx - btnW / 2, footerY, btnW, 20)
				.build();
		addRenderableWidget(startButton);

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - btnW / 2, footerY + ROW_H, btnW, 20)
				.build());

		// Clamp scroll (e.g. on resize) and apply.
		setScrollOffset(scrollOffset);
	}

	// ── Actions ──────────────────────────────────────────────────────────────

	private void linkAccount() {
		statusLine = Component.translatable("conduit.screen.host.linking_opening_browser");
		linkButton.active = false;
		startButton.active = false;

		ConduitClient.get().playit().linkAccountInteractiveAsync(url -> {
			// Called on the worker thread as soon as the agent prints a claim URL.
			minecraft.execute(() -> {
				Util.getPlatform().openUri(url);
				statusLine = Component.translatable(
						"conduit.screen.host.linking_waiting_accept");
			});
		}).whenComplete((result, err) -> minecraft.execute(() -> {
			if (err != null) {
				statusLine = Component.literal("Link failed: " + rootMessage(err));
				linkButton.active = true;
				startButton.active = true;
				return;
			}
			statusLine = Component.translatable("conduit.screen.host.linked");
			linkButton.setMessage(Component.translatable("conduit.screen.host.linked"));
			linkButton.active = false;
			unlinkButton.visible = true;
			unlinkButton.active = true;
			startButton.active = true;
		}));
	}

	private void unlinkAccount() {
		ConduitClient.get().playit().unlink();
		statusLine = Component.translatable("conduit.screen.host.unlinked");
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		init();
	}

	private void startHosting() {
		startButton.active = false;
		linkButton.active = false;
		statusLine = Component.translatable("conduit.status.starting");

		// Persist all choices up-front so reconnect/admin panel reflect them.
		ConduitConfig.Values cfg = ConduitClient.get().config().values();
		cfg.crossplayDefault          = crossplayBox.selected();
		cfg.defaultAllowCheats        = allowCheatsBox.selected();
		cfg.defaultPvp                = pvpBox.selected();
		cfg.defaultGameMode           = chosenGameMode;
		cfg.defaultDifficulty         = chosenDifficulty;
		cfg.defaultMaxPlayers         = chosenMaxPlayers;
		cfg.defaultRenderDistance     = chosenRenderDistance;
		cfg.defaultSimulationDistance = chosenSimulationDistance;
		cfg.motd                      = motdBox.getValue();
		cfg.allowFlight               = allowFlightBox.selected();
		cfg.forceGameMode             = forceGameModeBox.selected();
		cfg.spawnNpcs                 = spawnNpcsBox.selected();
		cfg.spawnAnimals              = spawnAnimalsBox.selected();
		cfg.spawnMonsters             = spawnMonstersBox.selected();
		cfg.announceAdvancements      = announceAdvancementsBox.selected();
		cfg.enableCommandBlock        = enableCommandBlockBox.selected();
		cfg.spawnProtection           = chosenSpawnProtection;
		cfg.playerIdleTimeout         = chosenPlayerIdleTimeout;
		ConduitClient.get().config().save();

		GameType type = switch (chosenGameMode.toLowerCase(Locale.ROOT)) {
			case "creative"  -> GameType.CREATIVE;
			case "adventure" -> GameType.ADVENTURE;
			case "spectator" -> GameType.SPECTATOR;
			default          -> GameType.SURVIVAL;
		};

		ConduitController.startHosting(
						type,
						allowCheatsBox.selected(),
						crossplayBox.selected(),
						chosenMaxPlayers,
						chosenDifficulty,
						pvpBox.selected(),
						motdBox.getValue(),
						chosenRenderDistance,
						chosenSimulationDistance)
				.whenComplete((v, err) -> minecraft.execute(() -> {
					if (err != null) {
						statusLine = Component.literal("Error: " + rootMessage(err));
						startButton.active = true;
						linkButton.active = !ConduitClient.get().playit().isLinkedAccount();
					} else {
						minecraft.setScreen(new AdminPanelScreen(parent));
					}
				}));
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
	}

	private static String cycle(String[] values, String current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i].equalsIgnoreCase(current)) {
				return values[(i + 1) % values.length];
			}
		}
		return values[0];
	}

	private static String displayName(String key) {
		if (key == null || key.isEmpty()) return "?";
		return Character.toUpperCase(key.charAt(0)) + key.substring(1);
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		// Header backdrop (prevents scrolled widgets from visibly clipping through).
		g.fill(0, 0, width, viewportTop(), 0x80000000);
		// Footer backdrop.
		g.fill(0, viewportBottom(), width, height, 0x80000000);

		super.extractRenderState(g, mx, my, dt);
		int cx = width / 2;

		// Title and subtitle (pinned).
		g.centeredText(font, title, cx, 8, 0xFFFFFF);
		Component subtitle = ConduitClient.get().playit().isLinkedAccount()
				? Component.translatable("conduit.screen.host.subtitle_linked")
				: Component.translatable("conduit.screen.host.subtitle_guest");
		g.centeredText(font, subtitle, cx, 22, 0xAAAAAA);

		// Section headers are drawn at their design-Y minus the current scrollOffset,
		// but only if they are visible within the viewport.
		drawSectionIfVisible(g, headerCoreY,    Component.translatable("conduit.screen.host.section_core"));
		drawSectionIfVisible(g, headerRulesY,   Component.translatable("conduit.screen.host.section_rules"));
		drawSectionIfVisible(g, headerWorldY,   Component.translatable("conduit.screen.host.section_world"));
		drawSectionIfVisible(g, headerServerY,  Component.translatable("conduit.screen.host.section_server"));
		drawSectionIfVisible(g, headerAccountY, Component.translatable("conduit.screen.host.section_account"));

		// Scrollbar (only drawn when the content is taller than the viewport).
		drawScrollbar(g);

		// Scroll hint when applicable.
		if (contentHeight > viewportHeight()) {
			String hint = scrollOffset == 0
					? "\u00A77Scroll for more \u25BE"
					: "\u00A77Scroll \u25B4\u25BE for more";
			g.text(font, Component.literal(hint), 6, viewportTop() - 10, 0x888888, false);
		}

		// Status line in the footer.
		Component status = Component.translatable("conduit.screen.host.status",
				statusLine != null ? statusLine : Component.literal("idle"));
		int statusY = viewportBottom() + 4;
		g.centeredText(font, status, cx, statusY, 0xFFFF55);
	}

	private void drawSectionIfVisible(GuiGraphicsExtractor g, int designYHeader, Component label) {
		int y = designYHeader - scrollOffset;
		if (y + SECTION_H < viewportTop() || y > viewportBottom()) return;
		drawSection(g, y, label);
	}

	private void drawSection(GuiGraphicsExtractor g, int y, Component label) {
		int cx = width / 2;
		int half = COL_W + COL_GAP / 2;
		int lx = cx - half;
		int rx = cx + half;
		// Faint horizontal line to the left and right of the label.
		g.fill(lx, y + 5, lx + 60, y + 6, 0x40FFFFFF);
		g.fill(rx - 60, y + 5, rx, y + 6, 0x40FFFFFF);
		g.centeredText(font, label, cx, y, 0xFFFFFF);
	}

	private void drawScrollbar(GuiGraphicsExtractor g) {
		int viewH = viewportHeight();
		if (contentHeight <= viewH) return;

		int top = viewportTop();
		int bottom = viewportBottom();
		int trackX = width - SCROLLBAR_W - 2;

		// Track
		g.fill(trackX, top, trackX + SCROLLBAR_W, bottom, 0x30FFFFFF);

		// Thumb
		float ratio = (float) viewH / contentHeight;
		int thumbH = Math.max(16, (int) (viewH * ratio));
		int travel = viewH - thumbH;
		int maxScroll = Math.max(1, contentHeight - viewH);
		int thumbY = top + (int) ((long) scrollOffset * travel / maxScroll);
		g.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH, 0xC0FFFFFF);
	}

	// ── Input: scrolling ────────────────────────────────────────────────────

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY >= viewportTop() && mouseY <= viewportBottom()
				&& contentHeight > viewportHeight()) {
			int step = (int) (scrollY * -ROW_H); // wheel-up -> content moves up (scroll down).
			setScrollOffset(scrollOffset + step);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		int keyCode = event.key();
		// 266 = PAGE_UP, 267 = PAGE_DOWN, 268 = HOME, 269 = END (GLFW)
		int page = Math.max(20, viewportHeight() - ROW_H);
		switch (keyCode) {
			case 266 -> { setScrollOffset(scrollOffset - page); return true; }
			case 267 -> { setScrollOffset(scrollOffset + page); return true; }
			case 268 -> { setScrollOffset(0); return true; }
			case 269 -> { setScrollOffset(Integer.MAX_VALUE); return true; }
			default -> { /* fall-through */ }
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	// ── IntSlider ────────────────────────────────────────────────────────────

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
