package com.goattech.conduit.config;

import com.goattech.conduit.ConduitMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny hand-rolled JSON config. We avoid pulling in Gson directly — Minecraft already ships it
 * transitively and Fabric's class loader will expose it at runtime, but for a mod that only has
 * ~6 config fields, a 50-line parser is simpler + harder to break.
 */
public final class ConduitConfig {

	public static final class Values {
		public String playitSecretKey = "";       // filled after first claim
		public String lastTunnelId = "";          // last tunnel used, for convenience
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

	/** Resolved lazily so tests can run without Fabric. */
	private Path resolveConfigPath() {
		Path gameDir = FabricLoader.getInstance().getGameDir();
		this.conduitDir = gameDir.resolve("conduit");
		try { Files.createDirectories(conduitDir); } catch (IOException ignored) {}
		return conduitDir.resolve("conduit.json");
	}

	public synchronized void load() {
		this.configFile = resolveConfigPath();
		if (!Files.isRegularFile(configFile)) {
			save();
			return;
		}
		try {
			String src = Files.readString(configFile, StandardCharsets.UTF_8);
			parseInto(src, values);
		} catch (IOException e) {
			ConduitMod.LOGGER.warn("[Conduit] Failed to read config, using defaults", e);
		}
	}

	public synchronized void save() {
		if (configFile == null) configFile = resolveConfigPath();
		try {
			Files.writeString(configFile, serialize(values), StandardCharsets.UTF_8);
		} catch (IOException e) {
			ConduitMod.LOGGER.warn("[Conduit] Failed to write config", e);
		}
	}

	// --- tiny JSON helpers -------------------------------------------------

	private static String serialize(Values v) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("{\n");
		appendString(sb, "playitSecretKey", v.playitSecretKey, true);
		appendString(sb, "lastTunnelId", v.lastTunnelId, true);
		appendBool(sb, "crossplayDefault", v.crossplayDefault, true);
		appendBool(sb, "autoStartDefault", v.autoStartDefault, true);
		appendInt(sb, "defaultRenderDistance", v.defaultRenderDistance, true);
		appendInt(sb, "defaultSimulationDistance", v.defaultSimulationDistance, true);
		appendInt(sb, "defaultMaxPlayers", v.defaultMaxPlayers, false);
		sb.append("}\n");
		return sb.toString();
	}

	private static void appendString(StringBuilder sb, String k, String val, boolean comma) {
		sb.append("  \"").append(k).append("\": \"").append(escape(val)).append('"');
		if (comma) sb.append(',');
		sb.append('\n');
	}
	private static void appendBool(StringBuilder sb, String k, boolean val, boolean comma) {
		sb.append("  \"").append(k).append("\": ").append(val);
		if (comma) sb.append(',');
		sb.append('\n');
	}
	private static void appendInt(StringBuilder sb, String k, int val, boolean comma) {
		sb.append("  \"").append(k).append("\": ").append(val);
		if (comma) sb.append(',');
		sb.append('\n');
	}

	private static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	/**
	 * Dead simple flat-JSON parser: expects only string/bool/int values at top level. Good
	 * enough for our config which we also fully control.
	 */
	private static void parseInto(String src, Values v) {
		String body = src.trim();
		if (body.startsWith("{")) body = body.substring(1);
		if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
		for (String rawLine : body.split(",\\s*\\n")) {
			String line = rawLine.trim();
			if (line.isEmpty()) continue;
			int colon = line.indexOf(':');
			if (colon < 0) continue;
			String key = stripQuotes(line.substring(0, colon).trim());
			String val = line.substring(colon + 1).trim();
			if (val.endsWith(",")) val = val.substring(0, val.length() - 1).trim();
			try {
				switch (key) {
					case "playitSecretKey" -> v.playitSecretKey = stripQuotes(val);
					case "lastTunnelId" -> v.lastTunnelId = stripQuotes(val);
					case "crossplayDefault" -> v.crossplayDefault = Boolean.parseBoolean(val);
					case "autoStartDefault" -> v.autoStartDefault = Boolean.parseBoolean(val);
					case "defaultRenderDistance" -> v.defaultRenderDistance = Integer.parseInt(val);
					case "defaultSimulationDistance" -> v.defaultSimulationDistance = Integer.parseInt(val);
					case "defaultMaxPlayers" -> v.defaultMaxPlayers = Integer.parseInt(val);
					default -> { /* forward-compatible: ignore unknown keys */ }
				}
			} catch (NumberFormatException ignored) { }
		}
	}

	private static String stripQuotes(String s) {
		String t = s.trim();
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
