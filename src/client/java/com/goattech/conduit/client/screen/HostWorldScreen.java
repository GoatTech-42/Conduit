package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

/**
 * The configuration screen that opens when the user clicks "Host This World" in the pause
 * menu. Handles optional account linking, tunnel/crossplay toggles, then kicks off the
 * hosting flow.
 */
public class HostWorldScreen extends Screen {

	private final Screen parent;

	private EditBox claimCodeBox;
	private Checkbox crossplayBox;
	private Button linkButton;
	private Button startButton;
	private Component statusLine = Component.translatable("conduit.status.idle");

	public HostWorldScreen(Screen parent) {
		super(Component.translatable("conduit.screen.host.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = 60;

		claimCodeBox = new EditBox(font, cx - 150, y, 300, 20,
				Component.translatable("conduit.screen.host.claim_code"));
		claimCodeBox.setMaxLength(64);
		claimCodeBox.setHint(Component.translatable("conduit.screen.host.claim_code"));
		addRenderableWidget(claimCodeBox);

		y += 26;
		linkButton = Button.builder(
				accountLinked()
						? Component.translatable("conduit.screen.host.linked")
						: Component.translatable("conduit.screen.host.link_account"),
				b -> linkAccount())
				.bounds(cx - 100, y, 200, 20)
				.build();
		linkButton.active = !accountLinked();
		addRenderableWidget(linkButton);

		y += 30;
		crossplayBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.crossplay"), font)
				.pos(cx - 150, y)
				.selected(ConduitClient.get().config().values().crossplayDefault)
				.build();
		addRenderableWidget(crossplayBox);

		y += 30;
		startButton = Button.builder(
				Component.translatable("conduit.screen.host.start"),
				b -> startHosting())
				.bounds(cx - 100, y, 200, 20)
				.build();
		startButton.active = accountLinked();
		addRenderableWidget(startButton);

		y += 30;
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - 100, y, 200, 20)
				.build());
	}

	private boolean accountLinked() {
		String k = ConduitClient.get().config().values().playitSecretKey;
		return k != null && !k.isBlank();
	}

	private void linkAccount() {
		String code = claimCodeBox.getValue().trim();
		if (code.isEmpty()) {
			statusLine = Component.literal("Paste your claim code from https://playit.gg/claim first.");
			return;
		}
		statusLine = Component.translatable("conduit.status.linking");
		linkButton.active = false;
		ConduitClient.get().playit().linkAccountAsync(code).whenComplete((secret, err) -> {
			if (err != null) {
				statusLine = Component.literal("Link failed: " + err.getMessage());
				linkButton.active = true;
				return;
			}
			statusLine = Component.translatable("conduit.screen.host.linked");
			linkButton.setMessage(Component.translatable("conduit.screen.host.linked"));
			startButton.active = true;
		});
	}

	private void startHosting() {
		startButton.active = false;
		statusLine = Component.translatable("conduit.status.starting");
		boolean crossplay = crossplayBox.selected();
		ConduitClient.get().config().values().crossplayDefault = crossplay;
		ConduitClient.get().config().save();

		ConduitController.startHosting(GameType.SURVIVAL, false, crossplay,
				ConduitClient.get().config().values().defaultMaxPlayers)
				.whenComplete((v, err) -> {
					if (err != null) {
						statusLine = Component.literal("Error: " + err.getMessage());
						startButton.active = true;
						return;
					}
					minecraft.execute(() -> minecraft.setScreen(new AdminPanelScreen(parent)));
				});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
		g.centeredText(font,
				Component.translatable("conduit.screen.host.subtitle"),
				width / 2, 34, 0xAAAAAA);
		Component tail = Component.translatable("conduit.screen.host.status",
				statusLine != null ? statusLine : Component.literal("idle"));
		g.centeredText(font, tail, width / 2, height - 18, 0xFFFF55);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
