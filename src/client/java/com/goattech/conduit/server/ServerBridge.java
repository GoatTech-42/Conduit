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
import java.util.Locale;
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
 * <p>Wherever possible, config mutations are routed through the vanilla command
 * dispatcher ({@code /difficulty}, {@code /gamerule}, {@code /defaultgamemode}, &hellip;).
 * Compared to calling the server's Java setters directly, this keeps us decoupled from
 * mapping churn <em>and</em> automatically propagates changes to every connected
 * client.
 *
 * <p><strong>Mappings:</strong> targets Mojang's official 26.1 mappings. Notable 26.1
 * changes:
 * <ul>
 *   <li>{@code GameProfile} &rarr; {@link NameAndId} for whitelist / ban entries.</li>
 *   <li>{@code ServerPlayer#latency()} removed (column dropped).</li>
 * </ul>
 */
public final class ServerBridge {

	// Mirrored copies of the last values we set through commands, so the UI can show
	// the current state without having to reach back into mapping-unstable getters.
	private volatile String currentDifficultyCache = "normal";
	private volatile String currentGameModeCache = "survival";
	private volatile boolean pvpCache = true;
	private volatile String motdCache = "";
	private volatile boolean allowFlightCache = false;
	private volatile boolean forceGameModeCache = false;
	private volatile boolean announceAdvancementsCache = true;
	private volatile boolean spawnNpcsCache = true;
	private volatile boolean spawnAnimalsCache = true;
	private volatile boolean spawnMonstersCache = true;
	private volatile boolean enableCommandBlockCache = false;
	private volatile int spawnProtectionCache = 0;
	private volatile int playerIdleTimeoutCache = 0;

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
		currentGameModeCache = gameType == null ? "survival" : gameType.getName();
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

	public boolean isPvpEnabled()              { return pvpCache; }
	public String  currentDifficulty()         { return currentDifficultyCache; }
	public String  currentGameMode()           { return currentGameModeCache; }
	public String  currentMotd()               { return motdCache; }
	public boolean isAllowFlightEnabled()      { return allowFlightCache; }
	public boolean isForceGameModeEnabled()    { return forceGameModeCache; }
	public boolean isAnnounceAdvancementsOn()  { return announceAdvancementsCache; }
	public boolean isSpawnNpcsEnabled()        { return spawnNpcsCache; }
	public boolean isSpawnAnimalsEnabled()     { return spawnAnimalsCache; }
	public boolean isSpawnMonstersEnabled()    { return spawnMonstersCache; }
	public boolean isCommandBlockEnabled()     { return enableCommandBlockCache; }
	public int     getSpawnProtection()        { return spawnProtectionCache; }
	public int     getPlayerIdleTimeout()      { return playerIdleTimeoutCache; }

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

	/** Remove a name from the ban list (does nothing if the name isn't banned). */
	public void pardon(String name) {
		runVanillaCommand("pardon " + name);
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

	/**
	 * Adds a player to the whitelist by name via the server command stack (resolves the
	 * Mojang UUID asynchronously on the server side).
	 */
	public void addToWhitelistByName(String name) {
		if (name == null || name.isBlank()) return;
		runVanillaCommand("whitelist add " + name);
	}

	public void removeFromWhitelistByName(String name) {
		if (name == null || name.isBlank()) return;
		runVanillaCommand("whitelist remove " + name);
	}

	public void setRenderDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setViewDistance(chunks));
	}

	public void setSimulationDistance(int chunks) {
		runOnServer(srv -> srv.getPlayerList().setSimulationDistance(chunks));
	}

	public void setPvp(boolean on) {
		pvpCache = on;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setPvpAllowed", boolean.class).invoke(srv, on);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setPvpAllowed not reachable, UI-only: {}", e.toString());
			}
		});
	}

	public void setDifficulty(String key) {
		String normalized = switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
			case "peaceful" -> "peaceful";
			case "easy"     -> "easy";
			case "hard"     -> "hard";
			default         -> "normal";
		};
		currentDifficultyCache = normalized;
		runVanillaCommand("difficulty " + normalized);
	}

	public void setDefaultGameMode(String key) {
		String normalized = switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
			case "creative"  -> "creative";
			case "adventure" -> "adventure";
			case "spectator" -> "spectator";
			default          -> "survival";
		};
		currentGameModeCache = normalized;
		runVanillaCommand("defaultgamemode " + normalized);
	}

	public void setMotd(String motd) {
		if (motd == null) return;
		motdCache = motd;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setMotd", String.class).invoke(srv, motd);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setMotd not reachable: {}", e.toString());
			}
		});
	}

	public void setAllowFlight(boolean on) {
		allowFlightCache = on;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setFlightAllowed", boolean.class).invoke(srv, on);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setFlightAllowed not reachable: {}", e.toString());
			}
		});
	}

	public void setForceGameMode(boolean on) {
		forceGameModeCache = on;
		runVanillaCommand("gamerule forceGameMode " + on);
	}

	public void setAnnounceAdvancements(boolean on) {
		announceAdvancementsCache = on;
		runVanillaCommand("gamerule announceAdvancements " + on);
	}

	public void setSpawnNpcs(boolean on) {
		spawnNpcsCache = on;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setSpawnNPCs", boolean.class).invoke(srv, on);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setSpawnNPCs not reachable: {}", e.toString());
			}
		});
	}

	public void setSpawnAnimals(boolean on) {
		spawnAnimalsCache = on;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setSpawnAnimals", boolean.class).invoke(srv, on);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setSpawnAnimals not reachable: {}", e.toString());
			}
		});
	}

	public void setSpawnMonsters(boolean on) {
		spawnMonstersCache = on;
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setSpawnMonsters", boolean.class).invoke(srv, on);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setSpawnMonsters not reachable: {}", e.toString());
			}
		});
	}

	public void setEnableCommandBlock(boolean on) {
		enableCommandBlockCache = on;
		runVanillaCommand("gamerule commandBlockOutput " + on);
	}

	public void setSpawnProtection(int radius) {
		spawnProtectionCache = Math.max(0, Math.min(radius, 256));
		runOnServer(srv -> {
			try {
				srv.getClass().getMethod("setSpawnProtection", int.class)
						.invoke(srv, spawnProtectionCache);
			} catch (ReflectiveOperationException e) {
				ConduitMod.LOGGER.debug("setSpawnProtection not reachable: {}", e.toString());
			}
		});
	}

	public void setPlayerIdleTimeout(int minutes) {
		playerIdleTimeoutCache = Math.max(0, minutes);
		runOnServer(srv -> srv.setPlayerIdleTimeout(playerIdleTimeoutCache));
	}

	/** Broadcasts a {@code /say}-style message via the server command source. */
	public void say(String message) {
		if (message == null || message.isBlank()) return;
		runVanillaCommand("say " + message);
	}

	/** Saves all loaded worlds immediately via the vanilla {@code /save-all} command. */
	public void saveAll() {
		runVanillaCommand("save-all");
	}

	/**
	 * Public entrypoint for the in-game console &mdash; runs an arbitrary
	 * vanilla command as if the server operator had typed it. A leading
	 * slash is stripped if present.
	 *
	 * <p>Use with caution from the UI; callers are expected to be operators
	 * (the Admin Panel is only reachable from the local host).
	 *
	 * @return {@code true} if the command was dispatched (the server was
	 *         running), {@code false} otherwise.
	 */
	public boolean runConsoleCommand(String command) {
		if (command == null) return false;
		String trimmed = command.strip();
		if (trimmed.isEmpty()) return false;
		if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
		IntegratedServer srv = Minecraft.getInstance().getSingleplayerServer();
		if (srv == null) return false;
		final String cmd = trimmed;
		srv.execute(() -> {
			try {
				var src = srv.createCommandSourceStack();
				srv.getCommands().performPrefixedCommand(src, cmd);
			} catch (Throwable t) {
				ConduitMod.LOGGER.warn("Console command failed: /{} \u2014 {}", cmd, t.toString());
				ConduitMod.console().warn("/" + cmd + " failed: " + t.getMessage());
			}
		});
		return true;
	}

	/**
	 * Applies all extended settings from config to the running server in one go.
	 * Called after the server is published and basic settings have been applied.
	 */
	public void applyExtendedSettings(
			boolean allowFlight,
			boolean forceGameMode,
			boolean spawnNpcs,
			boolean spawnAnimals,
			boolean spawnMonsters,
			boolean announceAdvancements,
			boolean enableCommandBlock,
			int spawnProtection,
			int playerIdleTimeout
	) {
		setAllowFlight(allowFlight);
		setForceGameMode(forceGameMode);
		setSpawnNpcs(spawnNpcs);
		setSpawnAnimals(spawnAnimals);
		setSpawnMonsters(spawnMonsters);
		setAnnounceAdvancements(announceAdvancements);
		setEnableCommandBlock(enableCommandBlock);
		setSpawnProtection(spawnProtection);
		setPlayerIdleTimeout(playerIdleTimeout);
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

	/**
	 * Run {@code command} (no leading slash) as if a server operator had typed it in the
	 * console. Logs and swallows failures so UI callers never have to try/catch.
	 */
	private static void runVanillaCommand(String command) {
		runOnServer(srv -> {
			try {
				var src = srv.createCommandSourceStack();
				srv.getCommands().performPrefixedCommand(src, command);
			} catch (Throwable t) {
				ConduitMod.LOGGER.warn("Conduit command failed: /{} — {}", command, t.toString());
			}
		});
	}

	// ── Data classes ─────────────────────────────────────────────────────────

	/** Immutable snapshot of a connected player. */
	public record PlayerSnap(UUID uuid, String name, String gameMode) {}
}
