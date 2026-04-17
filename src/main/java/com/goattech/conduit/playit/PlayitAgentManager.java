package com.goattech.conduit.playit;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.config.ConduitConfig;
import com.goattech.conduit.util.Downloader;
import com.goattech.conduit.util.PlatformBinary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the playit.gg agent subprocess.
 *
 * <p>The raw binary is downloaded from GitHub releases into
 * {@code <gameDir>/conduit/bin/} and executed directly &mdash; no admin privileges, no
 * service install, fully portable. Authentication is handled via the
 * {@code PLAYIT_SECRET} environment variable.
 */
public final class PlayitAgentManager {

	public static final String PLAYIT_VERSION_TAG = "v0.17.1";
	public static final String RELEASE_URL_FMT =
			"https://github.com/playit-cloud/playit-agent/releases/download/%s/%s";

	/** Maximum number of log lines retained in memory. */
	private static final int LOG_BUFFER_CAPACITY = 1_000;

	/*
	 * Matches agent log lines that report a tunnel, e.g.:
	 *   "tcp tunnel ready: mc-yourname.gl.joinmc.link:12345 -> 127.0.0.1:25565"
	 *   "New tunnel created: [name] tcp -> your-host:12345 -> 127.0.0.1:25565"
	 * The regex is intentionally loose to tolerate old and new agent phrasings.
	 */
	private static final Pattern TUNNEL_LINE = Pattern.compile(
			"(?i)(?:tcp|udp)[^\\n]*?(?:tunnel|ready|->)[^\\n]*?" +
			"([A-Za-z0-9.-]+):(\\d+)\\s*->\\s*(?:127\\.0\\.0\\.1|localhost):(\\d+)");

	private final ConduitConfig config;
	private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
	private final AtomicReference<Process> process = new AtomicReference<>();
	private final AtomicReference<PlayitTunnel> lastTunnel = new AtomicReference<>();

	public PlayitAgentManager(ConduitConfig config) {
		this.config = config;
	}

	public PlayitTunnel currentTunnel() {
		return lastTunnel.get();
	}

	public boolean isRunning() {
		Process p = process.get();
		return p != null && p.isAlive();
	}

	/**
	 * Returns the most recent {@code max} log lines.
	 */
	public List<String> logTail(int max) {
		List<String> snapshot = List.copyOf(logBuffer);
		int from = Math.max(0, snapshot.size() - max);
		return snapshot.subList(from, snapshot.size());
	}

	// ── Binary management ────────────────────────────────────────────────────

	public Path binaryPath() {
		return config.conduitDir().resolve("bin").resolve(PlatformBinary.playitExecutableName());
	}

	public CompletableFuture<Path> ensureBinaryAsync() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Path bin = binaryPath();
				if (Files.isRegularFile(bin) && Files.size(bin) > 0) {
					return bin;
				}
				String asset = PlatformBinary.playitAssetName();
				String url = RELEASE_URL_FMT.formatted(PLAYIT_VERSION_TAG, asset);
				ConduitMod.LOGGER.info("Fetching playit agent from {}", url);
				Downloader.download(url, bin, bytes -> { /* could surface progress */ });
				makeExecutable(bin);
				return bin;
			} catch (IOException e) {
				throw new RuntimeException("Failed to download playit agent: " + e.getMessage(), e);
			}
		});
	}

	private static void makeExecutable(Path bin) {
		if (PlatformBinary.isWindows()) return;
		try {
			Set<PosixFilePermission> perms = EnumSet.of(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE,
					PosixFilePermission.GROUP_READ,
					PosixFilePermission.GROUP_EXECUTE,
					PosixFilePermission.OTHERS_READ,
					PosixFilePermission.OTHERS_EXECUTE);
			Files.setPosixFilePermissions(bin, perms);
		} catch (UnsupportedOperationException | IOException e) {
			ConduitMod.LOGGER.debug("Could not set POSIX permissions on {}: {}", bin, e.getMessage());
		}
	}

	// ── Account linking ──────────────────────────────────────────────────────

	/**
	 * Runs {@code playit claim exchange <code>} to trade a claim code for a secret key.
	 * The resulting key is persisted into the config.
	 */
	public CompletableFuture<String> linkAccountAsync(String claimCode) {
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			try {
				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString(),
						"claim", "exchange", claimCode)
						.redirectErrorStream(true);
				Process p = pb.start();

				var output = new StringBuilder();
				try (var reader = new BufferedReader(
						new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = reader.readLine()) != null) {
						output.append(line).append('\n');
						appendLog("[claim] " + line);
					}
				}
				if (!p.waitFor(20, TimeUnit.SECONDS)) {
					p.destroyForcibly();
				}

				String secret = extractHexSecret(output.toString());
				if (secret == null) {
					throw new IOException(
							"Could not parse a secret key from agent output:\n" + output);
				}
				config.values().playitSecretKey = secret;
				config.save();
				return secret;
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Link failed: " + e.getMessage(), e);
			}
		}));
	}

	private static String extractHexSecret(String s) {
		Matcher m = Pattern.compile("([0-9a-fA-F]{32,128})").matcher(s);
		return m.find() ? m.group(1) : null;
	}

	// ── Agent lifecycle ──────────────────────────────────────────────────────

	/**
	 * Start the playit agent and wait asynchronously for it to report a tunnel matching
	 * {@code localPort}.
	 */
	public CompletableFuture<PlayitTunnel> startAsync(int localPort, boolean alsoBedrockUdp) {
		if (isRunning()) {
			return CompletableFuture.completedFuture(lastTunnel.get());
		}
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			String secret = config.values().playitSecretKey;
			if (secret == null || secret.isBlank()) {
				throw new IllegalStateException(
						"No playit secret stored yet. Link your account first.");
			}
			try {
				Path dataDir = config.conduitDir().resolve("playit-data");
				Files.createDirectories(dataDir);

				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString(),
						"launch",
						"--secret", secret)
						.redirectErrorStream(true);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());
				pb.environment().put("PLAYIT_SECRET", secret);

				Process p = pb.start();
				process.set(p);

				// Drain stdout on a daemon thread, harvesting tunnel info.
				Thread reader = Thread.ofVirtual()
						.name("conduit-playit-pump")
						.start(() -> pumpOutput(p, localPort));

				// Wait up to 30 s for a tunnel line.
				long deadline = System.currentTimeMillis() + 30_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException(
								"playit agent exited early with code " + p.exitValue());
					}
					PlayitTunnel t = lastTunnel.get();
					if (t != null && t.localPort() == localPort) {
						return t;
					}
					Thread.sleep(250);
				}
				throw new IOException("Timed out waiting for playit tunnel to come up.");
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				destroyProcess();
				throw new RuntimeException(e);
			}
		}));
	}

	/** Gracefully stop the agent subprocess (blocking up to 5 s). */
	public void shutdownBlocking() {
		Process p = process.getAndSet(null);
		if (p == null) return;
		p.destroy();
		try {
			if (!p.waitFor(5, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
		}
		lastTunnel.set(null);
		ConduitMod.LOGGER.info("playit agent stopped");
	}

	// ── Internal helpers ─────────────────────────────────────────────────────

	private void pumpOutput(Process p, int expectedLocalPort) {
		try (var reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				appendLog("[playit] " + line);
				Matcher m = TUNNEL_LINE.matcher(line);
				if (m.find()) {
					String host = m.group(1);
					int pub = Integer.parseInt(m.group(2));
					int loc = Integer.parseInt(m.group(3));
					boolean udp = line.toLowerCase().contains("udp");
					String tunnelName = line.contains("name=")
							? line.substring(line.indexOf("name=") + 5).split("\\s")[0]
							: "Minecraft";
					var tunnel = new PlayitTunnel(
							"auto-" + loc,
							tunnelName,
							udp ? PlayitTunnel.Protocol.UDP : PlayitTunnel.Protocol.TCP,
							host, pub, loc);
					if (loc == expectedLocalPort) {
						lastTunnel.set(tunnel);
					}
				}
			}
		} catch (IOException ignored) {
			// Process ended — expected.
		}
	}

	private void appendLog(String line) {
		logBuffer.add(line);
		// Evict oldest entries when over capacity.
		while (logBuffer.size() > LOG_BUFFER_CAPACITY) {
			logBuffer.removeFirst();
		}
	}

	private void destroyProcess() {
		Process p = process.getAndSet(null);
		if (p != null) p.destroyForcibly();
	}
}
