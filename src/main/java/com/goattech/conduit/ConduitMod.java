package com.goattech.conduit;

import com.goattech.conduit.util.ConsoleLog;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both-sides) entrypoint for Conduit.
 *
 * <p>Conduit is a <em>client</em>-focused mod; everything user-facing lives in the client
 * entrypoint ({@code ConduitClient}). This class exists so the jar is harmless on dedicated
 * servers that include it by accident&mdash;it simply logs its presence and returns.
 *
 * <p>The {@link #LOGGER} writes to the standard Minecraft log file as usual;
 * the new {@link #console()} helper mirrors a formatted copy into
 * {@link ConsoleLog} so the in-game Console tab always has a coherent view of
 * what every subsystem is doing.
 */
public final class ConduitMod implements ModInitializer {

	/** The Fabric mod id, matching {@code fabric.mod.json}. */
	public static final String MOD_ID = "conduit";

	/** Mod version (kept in sync with {@code gradle.properties}). */
	public static final String VERSION = "1.0.0";

	/** Shared SLF4J logger used across all Conduit classes. */
	public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

	@Override
	public void onInitialize() {
		LOGGER.info("Conduit common init (mod id: {}, version: {})", MOD_ID, VERSION);
		ConsoleLog.INSTANCE.info("Conduit " + VERSION + " initializing...");
	}

	/** Returns the shared, thread-safe in-memory console buffer. */
	public static ConsoleLog console() {
		return ConsoleLog.INSTANCE;
	}
}
