package com.goattech.conduit.config;

import com.goattech.conduit.ConduitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny hand-rolled JSON config for Conduit.
 *
 * <p>We avoid pulling in Gson directly&mdash;Minecraft ships it transitively, but for
 * a mod with only a handful of config fields, a small serializer/parser is simpler and
 * produces more predictable output.
 *
 * <p>Thread-safety: {@link #load()} and {@link #save()} are synchronized so they can
 * be called from both the client thread and async completion callbacks.
 */
public final class ConduitConfig {

	/** All user-facing config values, with sensible defaults. */
	public static final class Values {
		// ── playit ──
		/** Persisted playit secret (either guest-mode or linked-account). Empty = no tunnel yet. */
		public String playitSecretKey = "";
		/**
		 * {@code true} if the stored secret was generated without a playit.gg account
		 * (anonymous tunnel). Still fully functional; the user can upgrade to a linked
		 * account at any time from the admin panel.
		 */
		public boolean playitGuestMode = false;
		public String lastTunnelId = "";

		// ── Hosting behaviour ──
		public boolean crossplayDefault = false;
		public boolean autoStartDefault = true;
		public boolean openToLanByDefault = true;

		// ── Server defaults ──
		public int defaultRenderDistance = 10;
		public int defaultSimulationDistance = 10;
		public int defaultMaxPlayers = 8;
		public boolean defaultPvp = true;
		public boolean defaultAllowCheats = false;
		/** One of: {@code peaceful}, {@code easy}, {@code normal}, {@code hard}. */
		public String defaultDifficulty = "normal";
		/** One of: {@code survival}, {@code creative}, {@code adventure}, {@code spectator}. */
		public String defaultGameMode = "survival";
		/** MOTD shown in the multiplayer server list. */
		public String motd = "A Conduit-hosted world";

		// ── Extended server settings ──
		public int spawnProtection = 0;
		public boolean allowFlight = false;
		public boolean forceGameMode = false;
		public boolean allowNether = true;
		public boolean spawnNpcs = true;
		public boolean spawnAnimals = true;
		public boolean spawnMonsters = true;
		public boolean announceAdvancements = true;
		public int playerIdleTimeout = 0;
		public boolean enableCommandBlock = false;
		public int maxWorldSize = 29999984;
		public int entityBroadcastRange = 100;
	}

	private final Values values = new Values();
	private Path configFile;
	private Path conduitDir;

	public Values values() { return values; }
	public Path conduitDir() { return conduitDir; }

	// ── Lifecycle ────────────────────────────────────────────────────────────

	/** Resolved lazily so unit tests can run without the Fabric loader. */
	private Path resolveConfigPath() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		conduitDir = gameDir.resolve("conduit");
		try {
			Files.createDirectories(conduitDir);
		} catch (IOException e) {
			ConduitMod.LOGGER.warn("Could not create conduit directory: {}", conduitDir, e);
		}
		return conduitDir.resolve("conduit.json");
	}

	public synchronized void load() {
		configFile = resolveConfigPath();
		if (!Files.isRegularFile(configFile)) {
			save(); // write defaults
			return;
		}
		try {
			String src = Files.readString(configFile, StandardCharsets.UTF_8);
			parseInto(src, values);
		} catch (IOException e) {
			ConduitMod.LOGGER.warn("Failed to read config, using defaults", e);
		}
	}

	public synchronized void save() {
		if (configFile == null) {
			configFile = resolveConfigPath();
		}
		try {
			Files.writeString(configFile, serialize(values), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ConduitMod.LOGGER.warn("Failed to write config", e);
		}
	}

	// ── Serialization ────────────────────────────────────────────────────────

	private static String serialize(Values v) {
		return """
				{
				  "playitSecretKey": "%s",
				  "playitGuestMode": %b,
				  "lastTunnelId": "%s",
				  "crossplayDefault": %b,
				  "autoStartDefault": %b,
				  "openToLanByDefault": %b,
				  "defaultRenderDistance": %d,
				  "defaultSimulationDistance": %d,
				  "defaultMaxPlayers": %d,
				  "defaultPvp": %b,
				  "defaultAllowCheats": %b,
				  "defaultDifficulty": "%s",
				  "defaultGameMode": "%s",
				  "motd": "%s",
				  "spawnProtection": %d,
				  "allowFlight": %b,
				  "forceGameMode": %b,
				  "allowNether": %b,
				  "spawnNpcs": %b,
				  "spawnAnimals": %b,
				  "spawnMonsters": %b,
				  "announceAdvancements": %b,
				  "playerIdleTimeout": %d,
				  "enableCommandBlock": %b,
				  "maxWorldSize": %d,
				  "entityBroadcastRange": %d
				}
				""".formatted(
				escape(v.playitSecretKey),
				v.playitGuestMode,
				escape(v.lastTunnelId),
				v.crossplayDefault,
				v.autoStartDefault,
				v.openToLanByDefault,
				v.defaultRenderDistance,
				v.defaultSimulationDistance,
				v.defaultMaxPlayers,
				v.defaultPvp,
				v.defaultAllowCheats,
				escape(v.defaultDifficulty),
				escape(v.defaultGameMode),
				escape(v.motd),
				v.spawnProtection,
				v.allowFlight,
				v.forceGameMode,
				v.allowNether,
				v.spawnNpcs,
				v.spawnAnimals,
				v.spawnMonsters,
				v.announceAdvancements,
				v.playerIdleTimeout,
				v.enableCommandBlock,
				v.maxWorldSize,
				v.entityBroadcastRange
		);
	}

	private static String escape(String s) {
		if (s == null) return "";
		var sb = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"'  -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default   -> sb.append(c);
			}
		}
		return sb.toString();
	}

	// ── Parsing ──────────────────────────────────────────────────────────────

	/**
	 * Dead-simple flat JSON parser: expects only string / boolean / int values at the
	 * top level. Unknown keys are silently ignored for forward-compatibility.
	 */
	private static void parseInto(String src, Values v) {
		String body = src.strip();
		if (body.startsWith("{")) body = body.substring(1);
		if (body.endsWith("}"))   body = body.substring(0, body.length() - 1);

		// Split on newlines and process each key-value pair individually.
		for (String rawLine : body.split("\\n")) {
			String line = rawLine.strip();
			if (line.isEmpty()) continue;

			int colon = line.indexOf(':');
			if (colon < 0) continue;

			String key = stripQuotes(line.substring(0, colon).strip());
			String val = line.substring(colon + 1).strip();

			// Remove trailing comma if present.
			if (val.endsWith(",")) {
				val = val.substring(0, val.length() - 1).strip();
			}

			try {
				switch (key) {
					case "playitSecretKey"          -> v.playitSecretKey = stripQuotes(val);
					case "playitGuestMode"          -> v.playitGuestMode = Boolean.parseBoolean(val);
					case "lastTunnelId"             -> v.lastTunnelId = stripQuotes(val);
					case "crossplayDefault"         -> v.crossplayDefault = Boolean.parseBoolean(val);
					case "autoStartDefault"         -> v.autoStartDefault = Boolean.parseBoolean(val);
					case "openToLanByDefault"       -> v.openToLanByDefault = Boolean.parseBoolean(val);
					case "defaultRenderDistance"    -> v.defaultRenderDistance = Integer.parseInt(val);
					case "defaultSimulationDistance" -> v.defaultSimulationDistance = Integer.parseInt(val);
					case "defaultMaxPlayers"        -> v.defaultMaxPlayers = Integer.parseInt(val);
					case "defaultPvp"               -> v.defaultPvp = Boolean.parseBoolean(val);
					case "defaultAllowCheats"       -> v.defaultAllowCheats = Boolean.parseBoolean(val);
					case "defaultDifficulty"        -> v.defaultDifficulty = stripQuotes(val);
					case "defaultGameMode"          -> v.defaultGameMode = stripQuotes(val);
					case "motd"                     -> v.motd = stripQuotes(val);
					case "spawnProtection"          -> v.spawnProtection = Integer.parseInt(val);
					case "allowFlight"              -> v.allowFlight = Boolean.parseBoolean(val);
					case "forceGameMode"            -> v.forceGameMode = Boolean.parseBoolean(val);
					case "allowNether"              -> v.allowNether = Boolean.parseBoolean(val);
					case "spawnNpcs"                -> v.spawnNpcs = Boolean.parseBoolean(val);
					case "spawnAnimals"             -> v.spawnAnimals = Boolean.parseBoolean(val);
					case "spawnMonsters"            -> v.spawnMonsters = Boolean.parseBoolean(val);
					case "announceAdvancements"     -> v.announceAdvancements = Boolean.parseBoolean(val);
					case "playerIdleTimeout"        -> v.playerIdleTimeout = Integer.parseInt(val);
					case "enableCommandBlock"       -> v.enableCommandBlock = Boolean.parseBoolean(val);
					case "maxWorldSize"             -> v.maxWorldSize = Integer.parseInt(val);
					case "entityBroadcastRange"     -> v.entityBroadcastRange = Integer.parseInt(val);
					default -> { /* forward-compatible: ignore unknown keys */ }
				}
			} catch (NumberFormatException ignored) {
				// Malformed numeric value; keep the default.
			}
		}
	}

	private static String stripQuotes(String s) {
		String t = s.strip();
		if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
			t = t.substring(1, t.length() - 1);
		}
		return t
				.replace("\\n", "\n")
				.replace("\\r", "\r")
				.replace("\\t", "\t")
				.replace("\\\"", "\"")
				.replace("\\\\", "\\");
	}
}
