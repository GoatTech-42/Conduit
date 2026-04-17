package com.goattech.conduit.playit;

/**
 * Minimal description of a tunnel as reported by the playit agent (or as inferred from its
 * startup logs / `playit tunnels list`). Conduit only needs to know the public address and
 * whether it's TCP or UDP.
 */
public record PlayitTunnel(
		String id,
		String name,
		Protocol protocol,
		String publicHost,
		int publicPort,
		int localPort
) {
	public enum Protocol { TCP, UDP, BOTH }

	public String address() {
		return publicHost + ":" + publicPort;
	}
}
