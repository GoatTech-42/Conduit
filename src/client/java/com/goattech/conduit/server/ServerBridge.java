package com.goattech.conduit.server;

import com.goattech.conduit.ConduitMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Thin adapter around Minecraft's integrated server so the Conduit UI never has to
 * interact with raw vanilla APIs directly.
 *
 * <p>All write-side calls are dispatched via {@link IntegratedServer#execute} so they
 * always land on the server thread. Read-side calls snapshot the current state and
 * return immediately.
 *
 * <p><strong>Mappings:</strong> targets Mojang's official 26.1 mappings. Notable 26.1
 * changes:
 * <ul>
 *   <li>{@code GameProfile} &rarr; {@link NameAndId} for whitelist / ban entries.</li>
 *   <li>{@code ServerPlayer#latency()} removed (column dropped).</li>
 * </ul>
 */
public final class ServerBridge {

	// ── Publish ──────────────────────────────────────────────────────────────

	/**
	 * Returns an already-published LAN port, or publishes the world on a free port.
	 *
	 * @throws IllegalStateException if no integrated server is running or publishing fails
	 */
	public int publishIfNeeded(boolean allowCheats, GameType gameType) {
		IntegratedServer srv = requireServer();
		int existing = srv.getPort();
		if (existing > 0) return existing;

		int freePort = HttpUtil.getAvailablePort();
		if (!srv.publishServer(gameType, allowCheats, freePort)) {
			throw new IllegalStateException(
					"Failed to open world to LAN on port " + freePort);
		}
		ConduitMod.LOGGER.info("Published integrated server on port {}", freePort);
		return freePort;
	}

	// ── Read-side queries ────────────────────────────────────────────────────

	public int currentPort() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv == null ? -1 : srv.getPort();
	}

	public int playerCount() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv == null ? 0 : srv.getPlayerCount();
	}

	public int maxPlayers() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv == null ? 0 : srv.getMaxPlayers();
	}

	public boolean isWhitelistEnabled() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		return srv != null && srv.isUsingWhitelist();
	}

	/**
	 * Snapshots the current player list. The returned list is safe to iterate from any
	 * thread.
	 */
	public List<PlayerSnap> listPlayers() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return Collections.emptyList();
		PlayerList list = srv.getPlayerList();
		var out = new ArrayList<PlayerSnap>();
		list.getPlayers().forEach(p -> out.add(new PlayerSnap(
				p.getUUID(),
				p.getGameProfile().name(),
				p.gameMode.getGameModeForPlayer().getName())));
		return Collections.unmodifiableList(out);
	}

	// ── Write-side commands ──────────────────────────────────────────────────

	public void kick(UUID uuid, String reason) {
		runOnServer(srv -> srv.getPlayerList().getPlayers().stream()
				.filter(p -> p.getUUID().equals(uuid))
				.findFirst()
				.ifPresent(p -> p.connection.disconnect(Component.literal(reason))));
	}

	public void ban(UUID uuid, String name, String reason) {
		runOnServer(srv -> {
			var id = new NameAndId(uuid, name);
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
			srv.setUsingWhitelist(on);
			srv.setEnforceWhitelist(on);
		});
	}

	public void addToWhitelist(UUID uuid, String name) {
		runOnServer(srv -> {
			UserWhiteList wl = srv.getPlayerList().getWhiteList();
			wl.add(new UserWhiteListEntry(new NameAndId(uuid, name)));
		});
	}

	public void setRenderDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setViewDistance(chunks));
	}

	public void setSimulationDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setSimulationDistance(chunks));
	}

	/** Broadcasts a {@code /say}-style message via the server command source. */
	public void say(String message) {
		if (message == null || message.isBlank()) return;
		runOnServer(srv -> {
			var src = srv.createCommandSourceStack();
			srv.getCommands().performPrefixedCommand(src, "say " + message);
		});
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static IntegratedServer requireServer() {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) {
			throw new IllegalStateException("No integrated server is running");
		}
		return srv;
	}

	private static void runOnServer(Consumer<IntegratedServer> task) {
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return;
		srv.execute(() -> task.accept(srv));
	}

	// ── Data classes ─────────────────────────────────────────────────────────

	/** Immutable snapshot of a connected player. */
	public record PlayerSnap(UUID uuid, String name, String gameMode) {}
}
