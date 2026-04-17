package com.goattech.conduit;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both-sides) entrypoint for Conduit.
 *
 * <p>Conduit is a <em>client</em>-focused mod; everything user-facing lives in the client
 * entrypoint ({@code ConduitClient}). This class exists so the jar is harmless on dedicated
 * servers that include it by accident&mdash;it simply logs its presence and returns.
 */
public final class ConduitMod implements ModInitializer {

	/** The Fabric mod id, matching {@code fabric.mod.json}. */
	public static final String MOD_ID = "conduit";

	/** Shared logger used across all Conduit classes. */
	public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

	@Override
	public void onInitialize() {
		LOGGER.info("Conduit common init (mod id: {})", MOD_ID);
	}
}
