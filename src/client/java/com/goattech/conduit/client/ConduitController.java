package com.goattech.conduit.client;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.playit.PlayitTunnel;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates "start hosting" and "stop hosting" flows across the integrated server,
 * playit, Geyser, and the multiplayer server list.
 */
public final class ConduitController {

	private ConduitController() {}

	public static CompletableFuture<Void> startHosting(
			GameType gameType,
			boolean allowCheats,
			boolean crossplay,
			int maxPlayers
	) {
		ConduitClient cc = ConduitClient.get();
		cc.session().setState(ConduitSessionHolder.State.STARTING);
		ConduitMod.LOGGER.info("[Conduit] Start hosting (crossplay={}, maxPlayers={})", crossplay, maxPlayers);

		return CompletableFuture.supplyAsync(() -> {
			try {
				int javaPort = cc.server().publishIfNeeded(allowCheats, gameType);
				return javaPort;
			} catch (Throwable t) {
				fail(t);
				throw t;
			}
		}).thenCompose(javaPort -> cc.playit().startAsync(javaPort, crossplay)
				.thenCompose(tunnel -> {
					ConduitMod.LOGGER.info("[Conduit] Java tunnel up: {}", tunnel.address());
					String worldName = Minecraft.getInstance().getSingleplayerServer().getWorldData().getLevelName();

					CompletableFuture<String> bedrockFuture;
					if (crossplay) {
						int localBedrock = 19132;
						bedrockFuture = cc.geyser().startAsync(javaPort, localBedrock)
								.thenApply(p -> "bedrock://" + tunnel.publicHost() + ":" + p);
					} else {
						bedrockFuture = CompletableFuture.completedFuture(null);
					}

					return bedrockFuture.thenApply(bedrockAddress -> {
						cc.serverList().addOrUpdate(worldName, tunnel.address());
						var info = new ConduitSessionHolder.SessionInfo(
								worldName,
								tunnel.address(),
								bedrockAddress,
								javaPort,
								crossplay,
								Instant.now(),
								null
						);
						cc.session().setInfo(info);
						cc.session().setState(ConduitSessionHolder.State.RUNNING);
						toast(Component.translatable("conduit.message.agent_ready", tunnel.address()));
						return (Void) null;
					});
				}))
				.exceptionally(ex -> {
					fail(ex);
					toast(Component.translatable("conduit.message.error_generic", ex.getMessage()));
					return null;
				});
	}

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
				toast(Component.translatable("conduit.message.stopped"));
			}
		});
	}

	private static void fail(Throwable t) {
		ConduitMod.LOGGER.error("[Conduit] Hosting failed", t);
		var session = ConduitClient.get().session();
		session.setInfo(new ConduitSessionHolder.SessionInfo(
				"unknown", null, null, -1, false, Instant.now(), t.getMessage()));
		session.setState(ConduitSessionHolder.State.ERROR);
	}

	private static void toast(Component msg) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.getChat().addClientSystemMessage(msg));
	}

	@SuppressWarnings("unused")
	private static String formatTunnel(PlayitTunnel t) {
		return t == null ? "-" : t.address();
	}
}
