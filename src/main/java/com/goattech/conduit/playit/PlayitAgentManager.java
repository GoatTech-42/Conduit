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
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the playit.gg agent subprocess.
 *
 * <p>We download the <strong>raw binary</strong> from GitHub releases into
 * {@code <gameDir>/conduit/bin/} and execute it directly — no admin, no service install,
 * fully portable. The agent binary uses the {@code PLAYIT_SECRET} env var (as shown in the
 * Docker image docs) or a {@code --secret <key>} CLI flag to authenticate non-interactively.
 */
public final class PlayitAgentManager {

	public static final String PLAYIT_VERSION_TAG = "v0.17.1";
	public static final String RELEASE_URL_FMT =
			"https://github.com/playit-cloud/playit-agent/releases/download/%s/%s";

	// Matches log lines like:
	// "tcp tunnel ready: mc-yourname.gl.joinmc.link:12345 -> 127.0.0.1:25565"
	// or the current agent's:
	// "New tunnel created: [tunnel name] tcp -> your-host:12345 -> 127.0.0.1:25565"
	// We keep the regex loose + forgiving — we tolerate both old and new agent phrasings.
	private static final Pattern TUNNEL_LINE = Pattern.compile(
			"(?i)(?:tcp|udp)[^\\n]*?(?:tunnel|ready|->)[^\\n]*?" +
			"([A-Za-z0-9.-]+):(\\d+)\\s*->\\s*(?:127\\.0\\.0\\.1|localhost):(\\d+)");

	private final ConduitConfig config;
	private final ConcurrentLinkedDeque<String> logBuffer = new ConcurrentLinkedDeque<>();
	private final AtomicReference<Process> process = new AtomicReference<>(null);
	private final AtomicReference<PlayitTunnel> lastTunnel = new AtomicReference<>(null);

	public PlayitAgentManager(ConduitConfig config) {
		this.config = config;
	}

	public PlayitTunnel currentTunnel() { return lastTunnel.get(); }
	public Iterable<String> logTail(int max) {
		int skip = Math.max(0, logBuffer.size() - max);
		int i = 0;
		var out = new java.util.ArrayList<String>();
		for (String s : logBuffer) {
			if (i++ < skip) continue;
			out.add(s);
		}
		return out;
	}

	public boolean isRunning() {
		Process p = process.get();
		return p != null && p.isAlive();
	}

	// ------------------------------------------------------------------
	// Binary download / self-install

	public Path binaryPath() {
		Path dir = config.conduitDir().resolve("bin");
		return dir.resolve(PlatformBinary.playitExecutableName());
	}

	public CompletableFuture<Path> ensureBinaryAsync() {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Path bin = binaryPath();
				if (Files.isRegularFile(bin) && Files.size(bin) > 0) return bin;
				String asset = PlatformBinary.playitAssetName();
				String url = String.format(RELEASE_URL_FMT, PLAYIT_VERSION_TAG, asset);
				ConduitMod.LOGGER.info("[Conduit] Fetching playit agent from {}", url);
				Downloader.download(url, bin, b -> { /* could surface progress */ });
				if (!PlatformBinary.isWindows()) {
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
					} catch (UnsupportedOperationException ignored) {
						// non-posix filesystem – should be fine
					}
				}
				return bin;
			} catch (IOException e) {
				throw new RuntimeException("Failed to download playit agent: " + e.getMessage(), e);
			}
		});
	}

	// ------------------------------------------------------------------
	// Claim / account link

	/**
	 * Runs {@code playit claim exchange <code>}. When the user has pasted a claim code from
	 * {@code https://playit.gg/claim}, the agent will print the resulting
	 * {@code PLAYIT_SECRET} to stdout — we capture it and stash it in the config.
	 *
	 * <p>If the CLI argument shape ever changes upstream, we still fall back to running the
	 * agent in interactive-claim mode and scraping stdout for a 64-char hex key.
	 */
	public CompletableFuture<String> linkAccountAsync(String claimCode) {
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			try {
				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString(),
						"claim", "exchange", claimCode)
						.redirectErrorStream(true);
				Process p = pb.start();
				StringBuilder out = new StringBuilder();
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						out.append(line).append('\n');
						appendLog("[claim] " + line);
					}
				}
				p.waitFor(20, TimeUnit.SECONDS);
				String secret = extractHexSecret(out.toString());
				if (secret == null) {
					throw new IOException("Could not parse a secret key from agent output:\n" + out);
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

	// ------------------------------------------------------------------
	// Run the agent

	/**
	 * Start the playit agent and wait (asynchronously) for it to report back a tunnel
	 * matching {@code localPort}.
	 */
	public CompletableFuture<PlayitTunnel> startAsync(int localPort, boolean alsoBedrockUdp) {
		if (isRunning()) {
			return CompletableFuture.completedFuture(lastTunnel.get());
		}
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			String secret = config.values().playitSecretKey;
			if (secret == null || secret.isBlank()) {
				throw new IllegalStateException(
						"No playit secret stored yet — link your account first.");
			}
			try {
				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString(),
						"launch",
						"--secret", secret)
						.redirectErrorStream(true);
				// playit writes its own config into ~/.playit by default; we redirect it into
				// ./conduit/playit-data/ so Conduit stays self-contained.
				Path dataDir = config.conduitDir().resolve("playit-data");
				Files.createDirectories(dataDir);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());
				pb.environment().put("PLAYIT_SECRET", secret);
				Process p = pb.start();
				process.set(p);

				// Drain stdout on a background thread, harvest tunnel info.
				Thread reader = new Thread(() -> pumpOutput(p, localPort), "Conduit-playit-pump");
				reader.setDaemon(true);
				reader.start();

				// Wait up to 30s for a tunnel line.
				long deadline = System.currentTimeMillis() + 30_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException("playit agent exited early with code " + p.exitValue());
					}
					PlayitTunnel t = lastTunnel.get();
					if (t != null && t.localPort() == localPort) return t;
					Thread.sleep(250);
				}
				throw new IOException("Timed out waiting for playit tunnel to come up.");
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				Process p = process.getAndSet(null);
				if (p != null) p.destroyForcibly();
				throw new RuntimeException(e);
			}
		}));
	}

	private void pumpOutput(Process p, int expectedLocalPort) {
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				appendLog("[playit] " + line);
				Matcher m = TUNNEL_LINE.matcher(line);
				if (m.find()) {
					String host = m.group(1);
					int pub = Integer.parseInt(m.group(2));
					int loc = Integer.parseInt(m.group(3));
					boolean udp = line.toLowerCase().contains("udp");
					PlayitTunnel t = new PlayitTunnel(
							"auto-" + loc,
							line.contains("name=")
									? line.substring(line.indexOf("name=") + 5).split("\\s")[0]
									: "Minecraft",
							udp ? PlayitTunnel.Protocol.UDP : PlayitTunnel.Protocol.TCP,
							host, pub, loc);
					if (loc == expectedLocalPort) {
						lastTunnel.set(t);
					}
				}
			}
		} catch (IOException ignored) {
			// process ended
		}
	}

	private void appendLog(String line) {
		logBuffer.add(line);
		while (logBuffer.size() > 1000) logBuffer.pollFirst();
	}

	// ------------------------------------------------------------------
	// Shutdown

	public void shutdownBlocking() {
		Process p = process.getAndSet(null);
		if (p == null) return;
		p.destroy();
		try {
			if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
		}
		lastTunnel.set(null);
		ConduitMod.LOGGER.info("[Conduit] playit agent stopped");
	}
}
