package com.goattech.conduit.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared in-memory ring buffer that aggregates log lines from every Conduit
 * subsystem &mdash; the playit agent, Geyser, and Conduit itself &mdash; plus
 * the console tab's own command echoes.
 *
 * <p>The buffer is accessed from many threads (the client thread, the agent
 * output pumps, async completion callbacks) so it uses a lock-free
 * {@link CopyOnWriteArrayList}. Entries are stored with a monotonically
 * increasing sequence number so the UI can detect new lines cheaply.
 *
 * <p>This class is also designed to be a drop-in sink for SLF4J-style log
 * messages: {@link #info}, {@link #warn}, and {@link #error} each prepend a
 * short tag and a timestamp. The existing Conduit {@code LOGGER} continues
 * to write to the standard Minecraft log as well.
 */
public final class ConsoleLog {

	/** Maximum number of lines retained in memory before the oldest entries are dropped. */
	private static final int CAPACITY = 4_000;

	/** Process-wide singleton; every subsystem writes to the same buffer. */
	public static final ConsoleLog INSTANCE = new ConsoleLog();

	private static final DateTimeFormatter TIME_FMT =
			DateTimeFormatter.ofPattern("HH:mm:ss");

	/** A single log entry with its source tag and monotonically-increasing id. */
	public record Entry(long id, String source, String message) {
		public String formatted() {
			return "[" + source + "] " + message;
		}
	}

	private final CopyOnWriteArrayList<Entry> buffer = new CopyOnWriteArrayList<>();
	private final AtomicLong nextId = new AtomicLong(0);

	private ConsoleLog() {}

	// ── Writing ──────────────────────────────────────────────────────────────

	/**
	 * Append a line tagged with {@code source}. Automatically prepends a
	 * timestamp so the UI need not compute one itself.
	 */
	public void append(String source, String message) {
		if (message == null) return;
		String stamped = "[" + LocalTime.now().format(TIME_FMT) + "] " + message;
		Entry e = new Entry(nextId.incrementAndGet(), source, stamped);
		buffer.add(e);
		while (buffer.size() > CAPACITY) {
			buffer.removeFirst();
		}
	}

	/** Shortcut for {@code append("conduit", message)}. */
	public void info(String message)  { append("conduit",  message); }
	public void warn(String message)  { append("warn",     message); }
	public void error(String message) { append("error",    message); }

	/** Clears every entry from the buffer. */
	public void clear() {
		buffer.clear();
	}

	// ── Reading ──────────────────────────────────────────────────────────────

	/** Returns the current number of entries in the buffer. */
	public int size() {
		return buffer.size();
	}

	/** Returns all entries with {@code id > afterId}, in order, as a new list. */
	public List<Entry> entriesSince(long afterId) {
		List<Entry> snapshot = List.copyOf(buffer);
		var out = new ArrayList<Entry>(snapshot.size());
		for (Entry e : snapshot) {
			if (e.id > afterId) out.add(e);
		}
		return out;
	}

	/** Returns the last {@code max} entries. */
	public List<Entry> tail(int max) {
		List<Entry> snapshot = List.copyOf(buffer);
		int from = Math.max(0, snapshot.size() - max);
		return snapshot.subList(from, snapshot.size());
	}

	/** The id of the most recent entry, or 0 if the buffer is empty. */
	public long latestId() {
		List<Entry> snapshot = List.copyOf(buffer);
		return snapshot.isEmpty() ? 0 : snapshot.getLast().id;
	}
}
