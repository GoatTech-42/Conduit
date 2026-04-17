package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import com.goattech.conduit.client.ConduitSessionHolder;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lightweight "Manage Conduit Servers" screen, reachable from the Multiplayer screen
 * whenever a tunnel is still running.
 *
 * <p>Lets the user copy the public IP, open the admin panel, or cleanly shut everything
 * down without rejoining the world.
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

		if (info == null) {
			addRenderableWidget(Button.builder(
							Component.translatable("conduit.screen.host.cancel"),
							b -> onClose())
					.bounds(cx - 100, height - 30, 200, 20)
					.build());
			return;
		}

		int y = 60;

		// Copy IP
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.copy_ip"),
						b -> {
							minecraft.keyboardHandler.setClipboard(info.tunnelJavaAddress());
							minecraft.gui.getChat().addClientSystemMessage(
									Component.translatable("conduit.message.copied"));
						})
				.bounds(cx - 150, y, 140, 20).build());

		// Admin panel
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.open_admin"),
						b -> minecraft.setScreen(new AdminPanelScreen(parent)))
				.bounds(cx + 10, y, 140, 20).build());
		y += 40;

		// Shutdown everything
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.button.shutdown_all"),
						b -> ConduitController.stopHosting().whenComplete((v, err) ->
								minecraft.execute(() -> minecraft.setScreen(parent))))
				.bounds(cx - 150, y, 300, 20).build());

		// Cancel / back
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - 100, height - 30, 200, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 16, 0xFFFFFF);

		ConduitSessionHolder.SessionInfo info = ConduitClient.get().session().info();
		int y = 110;

		if (info == null) {
			g.centeredText(font,
					Component.translatable("conduit.screen.manage.empty"),
					width / 2, y, 0xAAAAAA);
			return;
		}

		g.centeredText(font,
				Component.translatable("conduit.screen.manage.world", info.worldName()),
				width / 2, y, 0xFFFFFF);
		y += 14;

		if (info.tunnelJavaAddress() != null) {
			g.centeredText(font, info.tunnelJavaAddress(), width / 2, y, 0x55FF55);
			y += 14;
		}

		if (info.tunnelBedrockAddress() != null) {
			g.centeredText(font, info.tunnelBedrockAddress(), width / 2, y, 0x55FFFF);
			y += 14;
		}

		g.centeredText(font,
				Component.translatable("conduit.screen.manage.started",
						info.startedAt().toString()),
				width / 2, y, 0xAAAAAA);
	}

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
