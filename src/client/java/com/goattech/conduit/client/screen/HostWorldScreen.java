package com.goattech.conduit.client.screen;

import com.goattech.conduit.client.ConduitClient;
import com.goattech.conduit.client.ConduitController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;

/**
 * Configuration screen shown when the user clicks "Host This World" in the pause menu.
 *
 * <p>Handles optional playit.gg account linking, crossplay toggle, then kicks off the
 * hosting flow through {@link ConduitController}.
 */
public class HostWorldScreen extends Screen {

	private final Screen parent;

	private EditBox claimCodeBox;
	private Checkbox crossplayBox;
	private Button fetchCodeButton;
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

		// Claim code input
		claimCodeBox = new EditBox(font, cx - 150, y, 300, 20,
				Component.translatable("conduit.screen.host.claim_code"));
		claimCodeBox.setMaxLength(64);
		claimCodeBox.setHint(Component.translatable("conduit.screen.host.claim_code"));
		addRenderableWidget(claimCodeBox);
		y += 26;

		// Claim-code helper actions
		fetchCodeButton = Button.builder(
						Component.translatable("conduit.screen.host.fetch_claim_code"),
						b -> fetchClaimCode())
				.bounds(cx - 150, y, 146, 20)
				.build();
		addRenderableWidget(fetchCodeButton);
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.open_claim_page"),
						b -> openClaimPage())
				.bounds(cx + 4, y, 146, 20)
				.build());
		y += 26;
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.paste_clipboard"),
						b -> pasteClaimCodeFromClipboard())
				.bounds(cx - 100, y, 200, 20)
				.build());
		y += 26;

		// Link account button
		boolean linked = isAccountLinked();
		linkButton = Button.builder(
						linked
								? Component.translatable("conduit.screen.host.linked")
								: Component.translatable("conduit.screen.host.link_account"),
						b -> linkAccount())
				.bounds(cx - 100, y, 200, 20)
				.build();
		linkButton.active = !linked;
		addRenderableWidget(linkButton);
		y += 30;

		// Crossplay checkbox
		crossplayBox = Checkbox.builder(
						Component.translatable("conduit.screen.host.crossplay"), font)
				.pos(cx - 150, y)
				.selected(ConduitClient.get().config().values().crossplayDefault)
				.build();
		addRenderableWidget(crossplayBox);
		y += 30;

		// Start button
		startButton = Button.builder(
						Component.translatable("conduit.screen.host.start"),
						b -> startHosting())
				.bounds(cx - 100, y, 200, 20)
				.build();
		startButton.active = linked;
		addRenderableWidget(startButton);
		y += 30;

		// Cancel button
		addRenderableWidget(Button.builder(
						Component.translatable("conduit.screen.host.cancel"),
						b -> onClose())
				.bounds(cx - 100, y, 200, 20)
				.build());
	}

	// ── Actions ──────────────────────────────────────────────────────────────

	private boolean isAccountLinked() {
		String key = ConduitClient.get().config().values().playitSecretKey;
		return key != null && !key.isBlank();
	}

	private void linkAccount() {
		String code = claimCodeBox.getValue().strip();
		if (code.isEmpty()) {
			statusLine = Component.translatable("conduit.screen.host.need_claim_code");
			return;
		}
		statusLine = Component.translatable("conduit.status.linking");
		setClaimActionsActive(false);

		ConduitClient.get().playit().linkAccountAsync(code)
				.whenComplete((secret, err) -> minecraft.execute(() -> {
					if (err != null) {
						statusLine = Component.literal("Link failed: " + rootMessage(err));
						setClaimActionsActive(true);
					} else {
						statusLine = Component.translatable("conduit.screen.host.linked");
						linkButton.setMessage(
								Component.translatable("conduit.screen.host.linked"));
						linkButton.active = false;
						fetchCodeButton.active = false;
						startButton.active = true;
					}
				}));
	}

	private void fetchClaimCode() {
		statusLine = Component.translatable("conduit.screen.host.fetching_claim_code");
		setClaimActionsActive(false);

		ConduitClient.get().playit().fetchClaimCodeAsync()
				.whenComplete((info, err) -> minecraft.execute(() -> {
					if (err != null) {
						statusLine = Component.literal("Claim code fetch failed: " + rootMessage(err));
						setClaimActionsActive(true);
						return;
					}

					if (info.claimCode() != null && !info.claimCode().isBlank()) {
						claimCodeBox.setValue(info.claimCode().strip());
						claimCodeBox.setCursorPosition(claimCodeBox.getValue().length());
					}

					if (info.claimUrl() != null && !info.claimUrl().isBlank()) {
						Util.getPlatform().openUri(info.claimUrl());
						statusLine = Component.translatable("conduit.screen.host.claim_code_ready_with_url");
					} else {
						statusLine = Component.translatable("conduit.screen.host.claim_code_ready");
					}
					setClaimActionsActive(true);
				}));
	}

	private void openClaimPage() {
		Util.getPlatform().openUri("https://playit.gg/claim");
		statusLine = Component.translatable("conduit.screen.host.claim_page_opened");
	}

	private void pasteClaimCodeFromClipboard() {
		String clipboard = minecraft.keyboardHandler.getClipboard();
		if (clipboard == null || clipboard.isBlank()) {
			statusLine = Component.translatable("conduit.screen.host.clipboard_empty");
			return;
		}
		claimCodeBox.setValue(clipboard.strip());
		claimCodeBox.setCursorPosition(claimCodeBox.getValue().length());
		statusLine = Component.translatable("conduit.screen.host.clipboard_pasted");
	}

	private void setClaimActionsActive(boolean active) {
		if (fetchCodeButton != null) {
			fetchCodeButton.active = active;
		}
		if (linkButton != null && !isAccountLinked()) {
			linkButton.active = active;
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable cur = t;
		while (cur.getCause() != null) {
			cur = cur.getCause();
		}
		String msg = cur.getMessage();
		return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
	}

	private void startHosting() {
		startButton.active = false;
		statusLine = Component.translatable("conduit.status.starting");

		boolean crossplay = crossplayBox.selected();
		ConduitClient.get().config().values().crossplayDefault = crossplay;
		ConduitClient.get().config().save();

		int maxPlayers = ConduitClient.get().config().values().defaultMaxPlayers;
		ConduitController.startHosting(GameType.SURVIVAL, false, crossplay, maxPlayers)
				.whenComplete((v, err) -> minecraft.execute(() -> {
					if (err != null) {
						statusLine = Component.literal("Error: " + err.getMessage());
						startButton.active = true;
					} else {
						minecraft.setScreen(new AdminPanelScreen(parent));
					}
				}));
	}

	// ── Rendering ────────────────────────────────────────────────────────────

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
		super.extractRenderState(g, mx, my, dt);
		g.centeredText(font, title, width / 2, 16, 0xFFFFFF);
		g.centeredText(font,
				Component.translatable("conduit.screen.host.subtitle"),
				width / 2, 34, 0xAAAAAA);

		Component status = Component.translatable("conduit.screen.host.status",
				statusLine != null ? statusLine : Component.literal("idle"));
		g.centeredText(font, status, width / 2, height - 18, 0xFFFF55);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
