package com.goattech.conduit.client;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the current Conduit hosting session (or the lack of one).
 *
 * <p>Kept deliberately simple so the multiplayer screen / pause menu can poll it cheaply every
 * init. State is mutated only from the client thread (or the manager's completion callbacks)
 * but reads are fine from anywhere since fields are volatile.
 */
public final class ConduitSessionHolder {

	public enum State {
		IDLE, STARTING, RUNNING, STOPPING, ERROR
	}

	public record SessionInfo(
			String worldName,
			String tunnelJavaAddress,     // host:port for Java clients
			String tunnelBedrockAddress,  // host:port (UDP) for Bedrock clients, or null
			int localPort,
			boolean crossplay,
			Instant startedAt,
			String lastError
	) { }

	private volatile State state = State.IDLE;
	private final AtomicReference<SessionInfo> info = new AtomicReference<>(null);

	public State state() { return state; }
	public void setState(State s) { this.state = s; }

	public SessionInfo info() { return info.get(); }
	public void setInfo(SessionInfo i) { this.info.set(i); }

	public boolean isAnythingRunning() {
		State s = state;
		return s == State.STARTING || s == State.RUNNING;
	}

	public void clear() {
		state = State.IDLE;
		info.set(null);
	}
}
