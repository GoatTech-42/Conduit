package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.config.ConduitConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;

import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Configuration screen shown when the user clicks "Host This World" in the pause menu.
 *
 * <p>The screen is organized into clearly separated sections with proper centering and
 * consistent spacing. All widgets are aligned to a two-column grid centered on the
 * screen, with clear section labels.
 *
 * <h3>Sections</h3>
 * <ol>
 *   <li><b>Core Settings</b> &mdash; game mode, difficulty, max players, render/sim distance.</li>
 *   <li><b>Gameplay Rules</b> &mdash; PvP, allow cheats, allow flight, force game mode, crossplay.</li>
 *   <li><b>World Rules</b> &mdash; spawn protection, spawn NPCs/animals/monsters, announce advancements.</li>
 *   <li><b>Server Info</b> &mdash; MOTD, idle timeout, command blocks.</li>
 *   <li><b>Actions</b> &mdash; start hosting, link account, cancel.</li>
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

	private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};
	private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};

	public HostWorldScreen(Screen parent) {
		super(Component.translatable("conduit.screen.host.title"));
		this.parent = parent;

		ConduitConfig.Values cfg = ConduitClient.get().config().values();
		this.chosenGameMode           = cfg.defaultGameMode;
		this.chosenDifficulty         = cfg.defaultDifficulty;
		this.chosenMaxPlayers         = cfg.defaultMaxPlayers;
		this.chosenRenderDistance      = cfg.defaultRenderDistance;
		this.chosenSimulationDistance  = cfg.defaultSimulationDistance;
		this.chosenSpawnProtection    = cfg.spawnProtection;
		this.chosenPlayerIdleTimeout  = cfg.playerIdleTimeout;
	}

	@Override
	protected void init() {
		ConduitConfig.Values cfg = ConduitClient.get().config().values();

		// ── Grid geometry ──
		int cx   = width / 2;
		int colL = cx - COL_W - COL_GAP / 2;
		int colR = cx + COL_GAP / 2;
		int y    = 40;

		// ────────────────────────────── Core Settings ──────────────────────────────

		// Row 1: Game mode + Difficulty
		gameModeCycle = Button.builder(
						Component.translatable("conduit.screen.host.gamemode",
								displayName(chosenGameMode)),
						b -> {
							chosenGameMode = cycle(GAME_MODES, chosenGameMode);
							gameModeCycle.setMessage(Component.translatable(
									"conduit.screen.host.gamemode", displayName(chosenGameMode)));
						})
				.bounds(colL, y, COL_W, 20).build();
		addRenderableWidget(gameModeCycle);

		difficultyCycle = Button.builder(
						Component.translatable("conduit.screen.host.difficulty",
								displayName(chosenDifficulty)),
						b -> {
							chosenDifficulty = cycle(DIFFICULTIES, chosenDifficulty);
							difficultyCycle.setMessage(Component.translatable(
									"conduit.screen.host.difficulty", displayName(chosenDifficulty)));
						})
				.bounds(colR, y, COL_W, 20).build();
		addRenderableWidget(difficultyCycle);
		y += ROW_H;

		// Row 2: Max players slider + Render distance slider
		addRenderableWidget(new IntSlider(colL, y, COL_W, 20,
				chosenMaxPlayers, 2, 50,
				"conduit.screen.host.max_players",
				v -> chosenMaxPlayers = v));

		addRenderableWidget(new IntSlider(colR, y, COL_W, 20,
				chosenRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> chosenRenderDistance = v));
		y += ROW_H;

		// Row 3: Simulation distance slider + Spawn protection slider
		addRenderableWidget(new IntSlider(colL, y, COL_W, 20,
				chosenSimulationDistance, 2, 32,
				"conduit.screen.admin.simulation_distance",
				v -> chosenSimulationDistance = v));

		addRenderableWidget(new IntSlider(colR, y, COL_W, 20,
				chosenSpawnProtection, 0, 64,
				"conduit.screen.host.spawn_protection",
				v -> chosenSpawnProtection = v));
		y += ROW_H;

		// ────────────────────────────── Gameplay Rules ─────────────────────────────

		// Row 4: PvP + Allow Cheats
		pvpBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.pvp"), font)
				.pos(colL, y).selected(cfg.defaultPvp).build();
		addRenderableWidget(pvpBox);

		allowCheatsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_cheats"), font)
				.pos(colR, y).selected(cfg.defaultAllowCheats).build();
		addRenderableWidget(allowCheatsBox);
		y += ROW_H;

		// Row 5: Allow Flight + Force Game Mode
		allowFlightBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_flight"), font)
				.pos(colL, y).selected(cfg.allowFlight).build();
		addRenderableWidget(allowFlightBox);

		forceGameModeBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.force_gamemode"), font)
				.pos(colR, y).selected(cfg.forceGameMode).build();
		addRenderableWidget(forceGameModeBox);
		y += ROW_H;

		// Row 6: Crossplay + Announce Advancements
		crossplayBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.crossplay"), font)
				.pos(colL, y).selected(cfg.crossplayDefault).build();
		addRenderableWidget(crossplayBox);

		announceAdvancementsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.announce_advancements"), font)
				.pos(colR, y).selected(cfg.announceAdvancements).build();
		addRenderableWidget(announceAdvancementsBox);
		y += ROW_H;

		// ────────────────────────────── World Rules ────────────────────────────────

		// Row 7: Spawn NPCs + Spawn Animals
		spawnNpcsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_npcs"), font)
				.pos(colL, y).selected(cfg.spawnNpcs).build();
		addRenderableWidget(spawnNpcsBox);

		spawnAnimalsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_animals"), font)
				.pos(colR, y).selected(cfg.spawnAnimals).build();
		addRenderableWidget(spawnAnimalsBox);
		y += ROW_H;

		// Row 8: Spawn Monsters + Enable Command Blocks
		spawnMonstersBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.spawn_monsters"), font)
				.pos(colL, y).selected(cfg.spawnMonsters).build();
		addRenderableWidget(spawnMonstersBox);

		enableCommandBlockBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.enable_command_block"), font)
				.pos(colR, y).selected(cfg.enableCommandBlock).build();
		addRenderableWidget(enableCommandBlockBox);
		y += ROW_H;

		// ────────────────────────────── Server Info ────────────────────────────────

		// Row 9: MOTD (full width)
		int motdW = COL_W * 2 + COL_GAP;
		motdBox = new EditBox(font, colL, y, motdW, 20,
				Component.translatable("conduit.screen.host.motd"));
		motdBox.setMaxLength(59);
		motdBox.setHint(Component.translatable("conduit.screen.host.motd_hint"));
		motdBox.setValue(cfg.motd);
		addRenderableWidget(motdBox);
		y += ROW_H;

		// Row 10: Player idle timeout slider (full width)
		addRenderableWidget(new IntSlider(colL, y, motdW, 20,
				chosenPlayerIdleTimeout, 0, 60,
				"conduit.screen.host.idle_timeout",
				v -> chosenPlayerIdleTimeout = v));
		y += ROW_H + 4;

		// ────────────────────────────── Action Buttons ─────────────────────────────

		// Start Hosting (prominent, centered)
		int btnW = 200;
		startButton = Button.builder(
						Component.translatable("conduit.screen.host.start"),
						b -> startHosting())
				.bounds(cx - btnW / 2, y, btnW, 20)
				.build();
		addRenderableWidget(startButton);
		y += ROW_H;

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
		addRenderableWidget(linkButton);

		unlinkButton = Button.builder(
						Component.translatable("conduit.screen.host.unlink"),
						b -> unlinkAccount())
				.bounds(linkX + linkW + COL_GAP, y, unlinkW, 20)
				.build();
		unlinkButton.visible = linked;
		unlinkButton.active = linked;
		addRenderableWidget(unlinkButton);
		y += ROW_H;

		// Cancel
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - btnW / 2, y, btnW, 20)
				.build());
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
		cfg.crossplayDefault        = crossplayBox.selected();
		cfg.defaultAllowCheats      = allowCheatsBox.selected();
		cfg.defaultPvp              = pvpBox.selected();
		cfg.defaultGameMode         = chosenGameMode;
		cfg.defaultDifficulty       = chosenDifficulty;
		cfg.defaultMaxPlayers       = chosenMaxPlayers;
		cfg.defaultRenderDistance    = chosenRenderDistance;
		cfg.defaultSimulationDistance = chosenSimulationDistance;
		cfg.motd                    = motdBox.getValue();
		cfg.allowFlight             = allowFlightBox.selected();
		cfg.forceGameMode           = forceGameModeBox.selected();
		cfg.spawnNpcs               = spawnNpcsBox.selected();
		cfg.spawnAnimals            = spawnAnimalsBox.selected();
		cfg.spawnMonsters           = spawnMonstersBox.selected();
		cfg.announceAdvancements    = announceAdvancementsBox.selected();
		cfg.enableCommandBlock      = enableCommandBlockBox.selected();
		cfg.spawnProtection         = chosenSpawnProtection;
		cfg.playerIdleTimeout       = chosenPlayerIdleTimeout;
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
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFF);

		Component subtitle = ConduitClient.get().playit().isLinkedAccount()
				? Component.translatable("conduit.screen.host.subtitle_linked")
				: Component.translatable("conduit.screen.host.subtitle_guest");
		g.centeredText(font, subtitle, width / 2, 22, 0xAAAAAA);

		Component status = Component.translatable("conduit.screen.host.status",
				statusLine != null ? statusLine : Component.literal("idle"));
		g.centeredText(font, status, width / 2, height - 14, 0xFFFF55);
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
