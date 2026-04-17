package com.goattech.conduit.client;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the current Conduit hosting session (or the lack of one).
 *
 * <p>Kept intentionally simple so the multiplayer screen and pause menu can poll it
 * cheaply every frame. State is mutated from the client thread or async completion
 * callbacks; reads are safe from any thread because the fields are volatile.
 */
public final class ConduitSessionHolder {

	/** Hosting lifecycle state. */
	public enum State {
		IDLE, STARTING, RUNNING, STOPPING, ERROR
	}

	/** Immutable snapshot of a hosting session. */
	public record SessionInfo(
			String worldName,
			String tunnelJavaAddress,
			String tunnelBedrockAddress,
			int localPort,
			boolean crossplay,
			Instant startedAt,
			String lastError
	) {}

	private volatile State state = State.IDLE;
	private final AtomicReference<SessionInfo> info = new AtomicReference<>();

	// ── Accessors ────────────────────────────────────────────────────────────

	public State       state() { return state; }
	public SessionInfo info()  { return info.get(); }

	public void setState(State s)    { this.state = s; }
	public void setInfo(SessionInfo i) { info.set(i); }

	/** Returns {@code true} when a hosting flow is in progress or actively running. */
	public boolean isAnythingRunning() {
		return state == State.STARTING || state == State.RUNNING;
	}

	/** Resets to the idle state. */
	public void clear() {
		state = State.IDLE;
		info.set(null);
	}
}
