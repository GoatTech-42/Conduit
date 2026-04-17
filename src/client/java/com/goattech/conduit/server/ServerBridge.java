package com.goattech.conduit.server;

import com.goattech.conduit.ConduitMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Thin adapter around Minecraft's integrated server so the UI never has to know about raw
 * vanilla APIs. All write-side calls are dispatched via {@link IntegratedServer#execute} so
 * they always land on the server thread.
 *
 * <p>Everything here targets Mojang's official 26.1 mappings (no Yarn). The two biggest
 * shape changes in 26.1 vs older versions are:
 * <ul>
 *   <li>{@code GameProfile} is no longer accepted by whitelist/ban entries — they take
 *       {@link NameAndId} instead, which has a convenient
 *       {@link NameAndId#createOffline(String)} factory for names we don't have a UUID for.</li>
 *   <li>{@code ServerPlayer#latency()} is gone; we just drop the column.</li>
 * </ul>
 */
public final class ServerBridge {

	/** Return an already-published LAN port, or publish the world on a free port and return it. */
	public int publishIfNeeded(boolean allowCheats, GameType gameType) {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) throw new IllegalStateException("No integrated server is running");
		int existing = srv.getPort();
		if (existing > 0) return existing;
		int freePort = HttpUtil.getAvailablePort();
		if (!srv.publishServer(gameType, allowCheats, freePort)) {
			throw new IllegalStateException("Failed to open world to LAN on port " + freePort);
		}
		ConduitMod.LOGGER.info("[Conduit] Published integrated server on port {}", freePort);
		return freePort;
	}

	public int currentPort() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv == null ? -1 : srv.getPort();
	}

	public int playerCount() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return 0;
		return srv.getPlayerCount();
	}

	public int maxPlayers() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return 0;
		return srv.getMaxPlayers();
	}

	public List<PlayerSnap> listPlayers() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return List.of();
		PlayerList list = srv.getPlayerList();
		var out = new ArrayList<PlayerSnap>();
		list.getPlayers().forEach(p -> out.add(new PlayerSnap(
				p.getUUID(),
				p.getGameProfile().name(),
				p.gameMode.getGameModeForPlayer().getName()
		)));
		return out;
	}

	public void kick(UUID uuid, String reason) {
		runOnServer(srv -> srv.getPlayerList().getPlayers().stream()
				.filter(p -> p.getUUID().equals(uuid)).findFirst()
				.ifPresent(p -> p.connection.disconnect(
						net.minecraft.network.chat.Component.literal(reason))));
	}

	public void ban(UUID uuid, String name, String reason) {
		runOnServer(srv -> {
			NameAndId id = new NameAndId(uuid, name);
			var entry = new UserBanListEntry(id, null, "Conduit", null, reason);
			srv.getPlayerList().getBans().add(entry);
			kick(uuid, reason);
		});
	}

	public void opPlayer(UUID uuid, String name) {
		runOnServer(srv -> srv.getPlayerList().op(new NameAndId(uuid, name)));
	}

	public void deopPlayer(UUID uuid, String name) {
		runOnServer(srv -> srv.getPlayerList().deop(new NameAndId(uuid, name)));
	}

	public void setWhitelistEnabled(boolean on) {
		runOnServer(srv -> {
			// Whitelist toggles are on MinecraftServer, not PlayerList.
			srv.setUsingWhitelist(on);
			srv.setEnforceWhitelist(on);
		});
	}

	public boolean isWhitelistEnabled() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv != null && srv.isUsingWhitelist();
	}

	public void addToWhitelist(UUID uuid, String name) {
		runOnServer(srv -> {
			UserWhiteList list = srv.getPlayerList().getWhiteList();
			list.add(new UserWhiteListEntry(new NameAndId(uuid, name)));
		});
	}

	public void setRenderDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setViewDistance(chunks));
	}

	public void setSimulationDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setSimulationDistance(chunks));
	}

	/** Broadcast a {@code /say} style message via the server command source. */
	public void say(String message) {
		runOnServer(srv -> {
			var src = srv.createCommandSourceStack();
			srv.getCommands().performPrefixedCommand(src, "say " + message);
		});
	}

	private void runOnServer(java.util.function.Consumer<IntegratedServer> task) {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return;
		srv.execute(() -> task.accept(srv));
	}

	/**
	 * Snapshot of a connected player. We intentionally don't include latency because MC 26.1
	 * removed the accessor from {@code ServerPlayer}.
	 */
	public record PlayerSnap(UUID uuid, String name, String gameMode) {}
}
