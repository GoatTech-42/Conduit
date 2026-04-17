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

/**
 * The in-game Conduit admin panel. Single screen with four tab buttons across the top so the
 * live status area (connections, console) stays visible while you tweak settings.
 *
 * <p>All rendering uses the new 26.1 {@link GuiGraphicsExtractor} — there is no
 * {@code GuiGraphics}/{@code render()} anymore, and draw calls go through
 * {@link GuiGraphicsExtractor#text} / {@link GuiGraphicsExtractor#centeredText}.
 */
public class AdminPanelScreen extends Screen {

	private enum Tab { PLAYERS, SETTINGS, CONSOLE, NETWORK }

	private final Screen parent;
	private Tab tab = Tab.PLAYERS;

	private EditBox sayBox;
	private String consoleCache = "";

	public AdminPanelScreen(Screen parent) {
		super(Component.translatable("conduit.screen.admin.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		int y = 28;

		int tx = 10;
		for (Tab t : Tab.values()) {
			Tab target = t;
			Button b = Button.builder(
							Component.translatable("conduit.screen.admin.tab."
									+ t.name().toLowerCase()),
							btn -> { tab = target; rebuild(); })
					.bounds(tx, y, 80, 20).build();
			b.active = tab != t;
			addRenderableWidget(b);
			tx += 84;
		}

		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.stop_hosting"),
						b -> ConduitController.stopHosting().whenComplete((v, e) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(width - 120, y, 110, 20).build());

		y += 32;
		switch (tab) {
			case PLAYERS -> buildPlayers(y);
			case SETTINGS -> buildSettings(y);
			case CONSOLE -> buildConsole(y);
			case NETWORK -> buildNetwork(y);
		}
	}

	private void buildPlayers(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		int y = y0;
		addRenderableWidget(Button.builder(
						srv.isWhitelistEnabled()
								? Component.translatable("conduit.screen.admin.whitelist_on")
								: Component.translatable("conduit.screen.admin.whitelist_off"),
						b -> { srv.setWhitelistEnabled(!srv.isWhitelistEnabled()); rebuild(); })
				.bounds(10, y, 180, 20).build());
		y += 26;

		for (var ps : srv.listPlayers()) {
			int pyStart = y;
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.kick"),
							b -> srv.kick(ps.uuid(), "Kicked via Conduit"))
					.bounds(220, pyStart, 60, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.ban"),
							b -> srv.ban(ps.uuid(), ps.name(), "Banned via Conduit"))
					.bounds(284, pyStart, 60, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.op"),
							b -> srv.opPlayer(ps.uuid(), ps.name()))
					.bounds(348, pyStart, 50, 20).build());
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.admin.deop"),
							b -> srv.deopPlayer(ps.uuid(), ps.name()))
					.bounds(402, pyStart, 60, 20).build());
			y += 24;
		}
	}

	private void buildSettings(int y0) {
		ServerBridge srv = ConduitClient.get().server();
		var cfg = ConduitClient.get().config().values();

		addRenderableWidget(new IntSlider(10, y0, 300, 20, cfg.defaultRenderDistance, 2, 32,
				"conduit.screen.admin.render_distance",
				v -> { cfg.defaultRenderDistance = v; srv.setRenderDistance(v); }));
		addRenderableWidget(new IntSlider(10, y0 + 24, 300, 20, cfg.defaultSimulationDistance, 2, 32,
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
				.bounds(254, y0 + 60, 60, 20).build());
	}

	private void buildConsole(int y0) {
		StringBuilder sb = new StringBuilder();
		for (String l : ConduitClient.get().playit().logTail(40)) sb.append(l).append('\n');
		for (String l : ConduitClient.get().geyser().logTail(20)) sb.append(l).append('\n');
		this.consoleCache = sb.toString();
	}

	private void buildNetwork(int y0) {
		// Most of this is drawn in extractRenderState()
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFF);

		if (tab == Tab.CONSOLE) {
			int y = 80;
			for (String line : consoleCache.split("\n")) {
				g.text(font, line, 10, y, 0x9EB9FF, false);
				y += 10;
				if (y > height - 10) break;
			}
		}
		if (tab == Tab.NETWORK) {
			var info = ConduitClient.get().session().info();
			int y = 80;
			if (info != null) {
				g.text(font,
						Component.translatable("conduit.screen.admin.tunnel_address"),
						10, y, 0xAAFFFF, false);
				g.text(font, info.tunnelJavaAddress(), 180, y, 0xFFFFFF, false);
				y += 14;
				if (info.tunnelBedrockAddress() != null) {
					g.text(font, "Bedrock:", 10, y, 0xAAFFFF, false);
					g.text(font, info.tunnelBedrockAddress(), 180, y, 0xFFFFFF, false);
					y += 14;
				}
				g.text(font, "Local java port:", 10, y, 0xAAFFFF, false);
				g.text(font, String.valueOf(info.localPort()), 180, y, 0xFFFFFF, false);
				y += 14;
				g.text(font, "Started:", 10, y, 0xAAFFFF, false);
				g.text(font, info.startedAt().toString(), 180, y, 0xFFFFFF, false);
				y += 14;
			}
			ConduitSessionHolder.State s = ConduitClient.get().session().state();
			g.text(font, "State: " + s, 10, y, 0xFFFF55, false);
		}
		if (tab == Tab.PLAYERS) {
			ServerBridge srv = ConduitClient.get().server();
			Component counter = Component.translatable("conduit.screen.admin.player_count",
					srv.playerCount(), srv.maxPlayers());
			g.text(font, counter, 10, 64, 0xFFFFFF, false);

			int y = 90;
			for (var ps : srv.listPlayers()) {
				g.text(font, ps.name() + "  (" + ps.gameMode() + ")",
						10, y + 4, 0xFFFFFF, false);
				y += 24;
			}
		}
	}

	@Override
	public boolean shouldCloseOnEsc() { return true; }

	@Override
	public void onClose() { minecraft.setScreen(parent); }

	/** Tiny integer slider helper — the vanilla {@link AbstractSliderButton} is abstract. */
	private static final class IntSlider extends AbstractSliderButton {
		private final int min, max;
		private final String i18nKey;
		private final java.util.function.IntConsumer onChange;

		IntSlider(int x, int y, int w, int h, int initial, int min, int max,
				  String i18nKey, java.util.function.IntConsumer onChange) {
			super(x, y, w, h, Component.empty(), (double) (initial - min) / (max - min));
			this.min = min;
			this.max = max;
			this.i18nKey = i18nKey;
			this.onChange = onChange;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int v = (int) Math.round(min + value * (max - min));
			setMessage(Component.translatable(i18nKey, v));
		}

		@Override
		protected void applyValue() {
			int v = (int) Math.round(min + value * (max - min));
			onChange.accept(v);
		}
	}
}
