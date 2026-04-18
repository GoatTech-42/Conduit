package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.client.ConduitSessionHolder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight "Manage Conduit Servers" screen, reachable from the Multiplayer screen
 * whenever a tunnel is still running.
 *
 * <p>Lets the user copy the public IP (Java &amp; Bedrock), open the admin panel, or
 * cleanly shut everything down without rejoining the world.
 */
public class ManageServersScreen extends Screen {

	private final Screen parent;

	public ManageServersScreen(Screen parent) {
		super(Component.translatable("conduit.screen.manage.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int cx = width / 2;
		int fw = 320;
		int halfW = (fw - 8) / 2;
		int lx = cx - fw / 2;

		// No active session — offer a single "Back" button.
		if (info == null) {
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.host.cancel"),
							b -> onClose())
					.bounds(cx - 100, height - 30, 200, 20)
					.build());
			return;
		}

		int y = 80;

		// Row 1: Copy Java IP + Copy Bedrock IP (if available)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.copy_ip"),
						b -> {
							minecraft.keyboardHandler.setClipboard(info.tunnelJavaAddress());
							minecraft.gui.getChat().addClientSystemMessage(
									Component.translatable("conduit.message.copied"));
						})
				.bounds(lx, y, halfW, 20).build());

		Button bedrockBtn = Button.builder(
						Component.translatable("conduit.button.copy_bedrock_ip"),
						b -> {
							if (info.tunnelBedrockAddress() != null) {
								minecraft.keyboardHandler.setClipboard(info.tunnelBedrockAddress());
								minecraft.gui.getChat().addClientSystemMessage(
										Component.translatable("conduit.message.copied"));
							}
						})
				.bounds(lx + halfW + 8, y, halfW, 20)
				.build();
		bedrockBtn.active = info.tunnelBedrockAddress() != null;
		addRenderableWidget(bedrockBtn);
		y += 28;

		// Row 2: Admin Panel (full width)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.open_admin"),
						b -> minecraft.setScreen(new AdminPanelScreen(parent)))
				.bounds(lx, y, fw, 20).build());
		y += 28;

		// Row 3: Shut down all (prominent, full width)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.shutdown_all"),
						b -> ConduitController.stopHosting().whenComplete((v, err) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(lx, y, fw, 20).build());

		// Back (bottom-center)
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - 100, height - 30, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		int cx = width / 2;
		g.centeredText(font, title, cx, 14, 0xFFFFFF);

		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int y = 32;

		if (info == null) {
			g.centeredText(font,
					Component.translatable("conduit.screen.manage.empty"),
					cx, y, 0xAAAAAA);
			return;
		}

		g.centeredText(font,
				Component.translatable("conduit.screen.manage.world", info.worldName()),
				cx, y, 0xFFFFFF);
		y += 12;

		if (info.tunnelJavaAddress() != null) {
			g.centeredText(font,
					Component.literal("Java: " + info.tunnelJavaAddress()),
					cx, y, 0x55FF55);
			y += 12;
		}
		if (info.tunnelBedrockAddress() != null) {
			g.centeredText(font,
					Component.literal("Bedrock: " + info.tunnelBedrockAddress()),
					cx, y, 0x55FFFF);
			y += 12;
		}

		// Friendly "running for X" line under the addresses.
		String uptime = humanizeUptime(info.startedAt());
		g.centeredText(font,
				Component.translatable("conduit.screen.manage.started", uptime),
				cx, y, 0xAAAAAA);
	}

	private static String humanizeUptime(Instant since) {
		if (since == null) return "?";
		Duration d = Duration.between(since, Instant.now());
		if (d.isNegative()) d = Duration.ZERO;
		long total = d.getSeconds();
		long hours = total / 3600;
		long minutes = (total % 3600) / 60;
		long seconds = total % 60;
		if (hours > 0)   return "%dh %02dm".formatted(hours, minutes);
		if (minutes > 0) return "%dm %02ds".formatted(minutes, seconds);
		return seconds + "s";
	}

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
