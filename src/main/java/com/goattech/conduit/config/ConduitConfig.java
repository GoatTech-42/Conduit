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
		public String playitSecretKey = "";
		public String lastTunnelId = "";
		public boolean crossplayDefault = false;
		public boolean autoStartDefault = true;
		public int defaultRenderDistance = 10;
		public int defaultSimulationDistance = 10;
		public int defaultMaxPlayers = 8;
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
				  "lastTunnelId": "%s",
				  "crossplayDefault": %b,
				  "autoStartDefault": %b,
				  "defaultRenderDistance": %d,
				  "defaultSimulationDistance": %d,
				  "defaultMaxPlayers": %d
				}
				""".formatted(
				escape(v.playitSecretKey),
				escape(v.lastTunnelId),
				v.crossplayDefault,
				v.autoStartDefault,
				v.defaultRenderDistance,
				v.defaultSimulationDistance,
				v.defaultMaxPlayers
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
					case "lastTunnelId"             -> v.lastTunnelId = stripQuotes(val);
					case "crossplayDefault"         -> v.crossplayDefault = Boolean.parseBoolean(val);
					case "autoStartDefault"         -> v.autoStartDefault = Boolean.parseBoolean(val);
					case "defaultRenderDistance"     -> v.defaultRenderDistance = Integer.parseInt(val);
					case "defaultSimulationDistance" -> v.defaultSimulationDistance = Integer.parseInt(val);
					case "defaultMaxPlayers"        -> v.defaultMaxPlayers = Integer.parseInt(val);
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
