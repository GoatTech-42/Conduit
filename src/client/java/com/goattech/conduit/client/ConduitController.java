package com.goattech.conduit.client;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.config.ConduitConfig;
import com.goattech.conduit.server.ServerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the "start hosting" and "stop hosting" flows across the integrated
 * server, playit agent, Geyser, and the multiplayer server list.
 *
 * <p>All public methods return {@link CompletableFuture}s so the UI thread is never
 * blocked.
 */
public final class ConduitController {

	private ConduitController() {}

	// ── Start hosting ────────────────────────────────────────────────────────

	/** Convenience overload with sensible defaults, preserved for any external callers. */
	public static CompletableFuture<Void> startHosting(
			GameType gameType,
			boolean allowCheats,
			boolean crossplay,
			int maxPlayers
	) {
		return startHosting(gameType, allowCheats, crossplay, maxPlayers,
				"normal", true, "A Conduit-hosted world", 10, 10);
	}

	/**
	 * Full-featured hosting entrypoint. Publishes the world to LAN, brings the playit
	 * tunnel up (auto-generating a guest secret if needed), optionally starts Geyser,
	 * applies server-side settings, and updates the multiplayer server list.
	 */
	public static CompletableFuture<Void> startHosting(
			GameType gameType,
			boolean allowCheats,
			boolean crossplay,
			int maxPlayers,
			String difficulty,
			boolean pvp,
			String motd,
			int renderDistance,
			int simulationDistance
	) {
		ConduitClient cc = ConduitClient.get();
		cc.session().setState(ConduitSessionHolder.State.STARTING);
		ConduitMod.LOGGER.info(
				"Start hosting (mode={}, difficulty={}, pvp={}, crossplay={}, maxPlayers={})",
				gameType, difficulty, pvp, crossplay, maxPlayers);

		return CompletableFuture
				.supplyAsync(() -> {
					try {
						return cc.server().publishIfNeeded(allowCheats, gameType);
					} catch (Throwable t) {
						fail(t);
						throw t;
					}
				})
				.thenCompose(javaPort ->
						cc.playit().startAsync(javaPort, crossplay)
								.thenCompose(tunnel -> {
									ConduitMod.LOGGER.info("Java tunnel up: {}", tunnel.address());
									applyServerSettings(cc.server(), cc.config().values(),
											difficulty, pvp, motd, renderDistance,
											simulationDistance, gameType);

									String worldName = Minecraft.getInstance()
											.getSingleplayerServer()
											.getWorldData()
											.getLevelName();

									CompletableFuture<String> bedrockFuture = crossplay
											? cc.geyser().startAsync(javaPort, 19132)
													.thenApply(p -> tunnel.publicHost() + ":" + p)
											: CompletableFuture.completedFuture(null);

									return bedrockFuture.thenAccept(bedrockAddress -> {
										cc.serverList().addOrUpdate(worldName, tunnel.address());

										var info = new ConduitSessionHolder.SessionInfo(
												worldName,
												tunnel.address(),
												bedrockAddress,
												javaPort,
												crossplay,
												Instant.now(),
												null);
										cc.session().setInfo(info);
										cc.session().setState(ConduitSessionHolder.State.RUNNING);
										chat(Component.translatable(
												"conduit.message.agent_ready", tunnel.address()));
									});
								}))
				.exceptionally(ex -> {
					fail(ex);
					chat(Component.translatable(
							"conduit.message.error_generic", ex.getMessage()));
					return null;
				});
	}

	private static void applyServerSettings(
			ServerBridge srv,
			ConduitConfig.Values cfg,
			String difficulty,
			boolean pvp,
			String motd,
			int renderDistance,
			int simulationDistance,
			GameType gameType
	) {
		try {
			srv.setDifficulty(difficulty);
			srv.setPvp(pvp);
			if (motd != null && !motd.isBlank()) {
				srv.setMotd(motd);
			}
			if (renderDistance > 0) {
				srv.setRenderDistance(renderDistance);
			}
			if (simulationDistance > 0) {
				srv.setSimulationDistance(simulationDistance);
			}
			if (gameType != null) {
				srv.setDefaultGameMode(gameType.getName());
			}

			// Apply extended settings from config.
			srv.applyExtendedSettings(
					cfg.allowFlight,
					cfg.forceGameMode,
					cfg.spawnNpcs,
					cfg.spawnAnimals,
					cfg.spawnMonsters,
					cfg.announceAdvancements,
					cfg.enableCommandBlock,
					cfg.spawnProtection,
					cfg.playerIdleTimeout
			);
		} catch (Throwable t) {
			ConduitMod.LOGGER.warn("Failed to apply some server settings: {}", t.toString());
		}
	}

	// ── Stop hosting ─────────────────────────────────────────────────────────

	public static CompletableFuture<Void> stopHosting() {
		ConduitClient cc = ConduitClient.get();
		cc.session().setState(ConduitSessionHolder.State.STOPPING);
		return CompletableFuture.runAsync(() -> {
			try {
				cc.playit().shutdownBlocking();
				cc.geyser().shutdownBlocking();
				cc.serverList().removeActive();
			} finally {
				cc.session().clear();
				chat(Component.translatable("conduit.message.stopped"));
			}
		});
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static void fail(Throwable t) {
		ConduitMod.LOGGER.error("Hosting failed", t);
		ConduitSessionHolder session = ConduitClient.get().session();
		session.setInfo(new ConduitSessionHolder.SessionInfo(
				"unknown", null, null, -1, false, Instant.now(), t.getMessage()));
		session.setState(ConduitSessionHolder.State.ERROR);
	}

	/** Sends a system-level chat message on the client thread. */
	private static void chat(Component msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addClientSystemMessage(msg));
	}
}
