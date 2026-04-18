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
 * <p><b>Design note.</b> A playit.gg account is <em>optional</em>. The zero-friction
 * flow is:
 * <ol>
 *   <li>User clicks "Host This World" in the pause menu.</li>
 *   <li>Picks max players / game mode / difficulty / cross-play, hits "Start Hosting".</li>
 *   <li>Conduit auto-generates an anonymous playit secret and brings up a tunnel.</li>
 * </ol>
 * <p>Power users who want their tunnels to persist across reinstalls (or who want to
 * manage them on playit.gg) can click "Link playit.gg Account" at the bottom, which
 * opens a one-click browser flow &mdash; no claim code to copy and paste.
 */
public class HostWorldScreen extends Screen {

	private final Screen parent;

	// ── Widgets ──
	private Checkbox crossplayBox;
	private Checkbox allowCheatsBox;
	private Checkbox pvpBox;
	private EditBox motdBox;
	private Button gameModeCycle;
	private Button difficultyCycle;
	private Button linkButton;
	private Button unlinkButton;
	private Button startButton;

	// ── Local edit state (not persisted until Start) ──
	private String chosenGameMode;
	private String chosenDifficulty;
	private int chosenMaxPlayers;
	private int chosenRenderDistance;

	private Component statusLine = Component.translatable("conduit.status.idle");

	private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};
	private static final String[] DIFFICULTIES = {"peaceful", "easy", "normal", "hard"};

	public HostWorldScreen(Screen parent) {
		super(Component.translatable("conduit.screen.host.title"));
		this.parent = parent;

		ConduitConfig.Values cfg = ConduitClient.get().config().values();
		this.chosenGameMode       = cfg.defaultGameMode;
		this.chosenDifficulty     = cfg.defaultDifficulty;
		this.chosenMaxPlayers     = cfg.defaultMaxPlayers;
		this.chosenRenderDistance = cfg.defaultRenderDistance;
	}

	@Override
	protected void init() {
		ConduitConfig.Values cfg = ConduitClient.get().config().values();
		int cx = width / 2;
		int y = 42;
		int colL = cx - 150;
		int colR = cx + 4;

		// ── Game mode cycle ──
		gameModeCycle = Button.builder(
						Component.translatable("conduit.screen.host.gamemode",
								displayName(chosenGameMode)),
						b -> {
							chosenGameMode = cycle(GAME_MODES, chosenGameMode);
							gameModeCycle.setMessage(Component.translatable(
									"conduit.screen.host.gamemode", displayName(chosenGameMode)));
						})
				.bounds(colL, y, 146, 20).build();
		addRenderableWidget(gameModeCycle);

		// ── Difficulty cycle ──
		difficultyCycle = Button.builder(
						Component.translatable("conduit.screen.host.difficulty",
								displayName(chosenDifficulty)),
						b -> {
							chosenDifficulty = cycle(DIFFICULTIES, chosenDifficulty);
							difficultyCycle.setMessage(Component.translatable(
									"conduit.screen.host.difficulty", displayName(chosenDifficulty)));
						})
				.bounds(colR, y, 146, 20).build();
		addRenderableWidget(difficultyCycle);
		y += 24;

		// ── Max players slider ──
		addRenderableWidget(new IntSlider(colL, y, 146, 20,
				chosenMaxPlayers, 2, 50,
				"conduit.screen.host.max_players",
				v -> chosenMaxPlayers = v));

		// ── Render distance slider ──
		addRenderableWidget(new IntSlider(colR, y, 146, 20,
				chosenRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> chosenRenderDistance = v));
		y += 24;

		// ── Checkboxes ──
		crossplayBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.crossplay"), font)
				.pos(colL, y)
				.selected(cfg.crossplayDefault)
				.build();
		addRenderableWidget(crossplayBox);

		allowCheatsBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.allow_cheats"), font)
				.pos(colR, y)
				.selected(cfg.defaultAllowCheats)
				.build();
		addRenderableWidget(allowCheatsBox);
		y += 24;

		pvpBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.pvp"), font)
				.pos(colL, y)
				.selected(cfg.defaultPvp)
				.build();
		addRenderableWidget(pvpBox);
		y += 24;

		// ── MOTD ──
		motdBox = new EditBox(font, colL, y, 300, 20,
				Component.translatable("conduit.screen.host.motd"));
		motdBox.setMaxLength(59);
		motdBox.setHint(Component.translatable("conduit.screen.host.motd_hint"));
		motdBox.setValue(cfg.motd);
		addRenderableWidget(motdBox);
		y += 30;

		// ── Start hosting (the primary action) ──
		startButton = Button.builder(
						Component.translatable("conduit.screen.host.start"),
						b -> startHosting())
				.bounds(cx - 100, y, 200, 20)
				.build();
		addRenderableWidget(startButton);
		y += 24;

		// ── Optional: link playit.gg account ──
		boolean linked = ConduitClient.get().playit().isLinkedAccount();
		linkButton = Button.builder(
						linked
								? Component.translatable("conduit.screen.host.linked")
								: Component.translatable("conduit.screen.host.link_account_optional"),
						b -> linkAccount())
				.bounds(cx - 150, y, linked ? 220 : 220, 20)
				.build();
		linkButton.active = !linked;
		addRenderableWidget(linkButton);

		unlinkButton = Button.builder(
						Component.translatable("conduit.screen.host.unlink"),
						b -> unlinkAccount())
				.bounds(cx + 76, y, 74, 20)
				.build();
		unlinkButton.visible = linked;
		unlinkButton.active = linked;
		addRenderableWidget(unlinkButton);
		y += 24;

		// ── Cancel ──
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - 100, y, 200, 20)
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
		cfg.defaultRenderDistance   = chosenRenderDistance;
		cfg.motd                    = motdBox.getValue();
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
						chosenRenderDistance)
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
		g.centeredText(font, title, width / 2, 10, 0xFFFFFF);

		Component subtitle = ConduitClient.get().playit().isLinkedAccount()
				? Component.translatable("conduit.screen.host.subtitle_linked")
				: Component.translatable("conduit.screen.host.subtitle_guest");
		g.centeredText(font, subtitle, width / 2, 24, 0xAAAAAA);

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
