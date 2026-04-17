package com.goattech.conduit.playit;

/**
 * Immutable snapshot of a tunnel as reported by the playit agent.
 *
 * <p>Conduit only needs the public address and protocol to add the server-list entry
 * and display the connection info in the admin panel.
 */
public record PlayitTunnel(
		String id,
		String name,
		Protocol protocol,
		String publicHost,
		int publicPort,
		int localPort
) {

	/** Network protocol the tunnel forwards. */
	public enum Protocol { TCP, UDP, BOTH }

	/** Returns the public address in {@code host:port} form, suitable for the server list. */
	public String address() {
		return publicHost + ":" + publicPort;
	}

	@Override
	public String toString() {
		return "%s %s (%s -> localhost:%d)".formatted(protocol, address(), name, localPort);
	}
}
