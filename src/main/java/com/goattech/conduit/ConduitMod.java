package com.goattech.conduit;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both-sides) entrypoint for Conduit.
 *
 * <p>Conduit is a <em>client</em>-focused mod — everything user-facing lives in the client
 * entrypoint. This class only exists so the mod is still usable on dedicated servers that
 * ship the jar accidentally (it just does nothing there).
 */
public final class ConduitMod implements ModInitializer {
	public static final String MOD_ID = "conduit";
	public static final Logger LOGGER = LoggerFactory.getLogger("Conduit");

	@Override
	public void onInitialize() {
		LOGGER.info("[Conduit] Common init — {}", MOD_ID);
	}
}
