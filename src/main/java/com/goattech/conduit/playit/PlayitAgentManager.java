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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the playit.gg agent subprocess.
 *
 * <p>The raw binary is downloaded from GitHub releases into
 * {@code <gameDir>/conduit/bin/} and executed directly &mdash; no admin privileges, no
 * service install, fully portable. Authentication is handled via the
 * {@code PLAYIT_SECRET} environment variable.
 *
 * <p>Conduit supports three modes:
 * <ol>
 *   <li><b>Guest mode (default)</b> &mdash; no playit.gg account is required. Conduit
 *       asks the agent to create a throw-away secret (stored in {@code conduit.json}) and
 *       a free anonymous tunnel. Most users never need anything more.</li>
 *   <li><b>Auto-link</b> &mdash; the user clicks "Link playit.gg Account". Conduit starts
 *       the agent in setup mode, captures the {@code playit.gg/claim/<code>} URL it prints,
 *       opens it in the user's browser, and the agent completes the exchange on its own
 *       as soon as the user clicks "Accept" on the web page.</li>
 *   <li><b>Manual claim code</b> &mdash; the user can paste a claim code from
 *       {@code playit.gg/claim} and Conduit runs {@code playit claim exchange} directly.</li>
 * </ol>
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
	private static final Pattern CLAIM_URL = Pattern.compile(
			"(?i)(https?://(?:www\\.)?playit\\.gg/(?:claim|mc)(?:/[A-Za-z0-9-]+)?)");
	private static final Pattern CLAIM_PATH_CODE = Pattern.compile(
			"(?i)playit\\.gg/(?:claim|mc)/([A-Za-z0-9-]{4,64})");
	private static final Pattern CLAIM_TOKEN = Pattern.compile(
			"(?i)(?:claim(?:\\s*code)?|code)\\D{0,12}([A-Za-z0-9-]{4,64})");
	private static final Pattern SECRET_LINE = Pattern.compile(
			"(?i)(?:secret[_\\s-]?key|agent[_\\s-]?secret)\\D{0,8}([0-9a-fA-F]{32,128})");

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

	/** @return {@code true} if a secret is stored (guest or linked). */
	public boolean hasSecret() {
		String s = config.values().playitSecretKey;
		return s != null && !s.isBlank();
	}

	/** @return {@code true} if the stored secret came from a linked playit.gg account. */
	public boolean isLinkedAccount() {
		return hasSecret() && !config.values().playitGuestMode;
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

	// ── Guest-mode setup (no account required) ──────────────────────────────

	/**
	 * Ensures a local secret exists. If the config already has one (linked or guest), this
	 * is a no-op. Otherwise Conduit asks the agent to generate an anonymous secret so the
	 * user can host immediately without ever visiting playit.gg.
	 *
	 * <p>The underlying command is {@code playit secret generate} (newer agents) or
	 * {@code playit setup --no-login} (older agents); both produce a 32&ndash;128 hex
	 * secret on stdout.
	 */
	public CompletableFuture<Void> ensureGuestSecretAsync() {
		if (hasSecret()) {
			return CompletableFuture.completedFuture(null);
		}
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			String[][] attempts = {
					{"secret", "generate"},
					{"secret", "new"},
					{"setup", "--no-login"},
			};
			List<String> diagnostics = new ArrayList<>();
			for (String[] args : attempts) {
				try {
					CommandResult r = runCommand(bin, args);
					String secret = extractSecret(r.output());
					if (secret != null) {
						config.values().playitSecretKey = secret;
						config.values().playitGuestMode = true;
						config.save();
						ConduitMod.LOGGER.info("Generated guest playit secret ({} chars)", secret.length());
						return null;
					}
					diagnostics.add("playit " + String.join(" ", args)
							+ " (exit=" + r.exitCode() + "):\n" + r.output().strip());
				} catch (IOException | InterruptedException e) {
					Thread.currentThread().interrupt();
					diagnostics.add("playit " + String.join(" ", args) + " threw: " + e.getMessage());
				}
			}
			throw new RuntimeException(
					"Could not generate an anonymous playit secret. You can still click "
							+ "\"Link playit.gg Account\" to link one manually.\n\n"
							+ String.join("\n\n", diagnostics));
		}));
	}

	// ── Account linking ──────────────────────────────────────────────────────

	/**
	 * One-click linking: start the agent in setup mode, watch for the claim URL, open it
	 * in the user's browser via {@code urlSink}, then wait until the agent reports that
	 * the account has been linked. The generated secret is persisted on success.
	 *
	 * @param urlSink receives the {@code playit.gg/claim/&lt;code&gt;} URL as soon as the
	 *                agent prints it. Typically this opens the URL in the default browser.
	 */
	public CompletableFuture<LinkResult> linkAccountInteractiveAsync(Consumer<String> urlSink) {
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			Process p = null;
			try {
				Path dataDir = config.conduitDir().resolve("playit-data");
				Files.createDirectories(dataDir);

				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString(),
						"setup")
						.redirectErrorStream(true);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

				p = pb.start();
				AtomicReference<String> captured = new AtomicReference<>();
				AtomicReference<String> foundUrl = new AtomicReference<>();
				AtomicReference<String> foundSecret = new AtomicReference<>();

				long deadline = System.currentTimeMillis() + 180_000; // 3 min user window
				try (var reader = new BufferedReader(new InputStreamReader(
						p.getInputStream(), StandardCharsets.UTF_8))) {
					String line;
					while (System.currentTimeMillis() < deadline
							&& (line = reader.readLine()) != null) {
						appendLog("[link] " + line);

						if (foundUrl.get() == null) {
							Matcher m = CLAIM_URL.matcher(line);
							if (m.find()) {
								String url = m.group(1);
								foundUrl.set(url);
								if (urlSink != null) {
									try { urlSink.accept(url); } catch (Throwable ignored) {}
								}
							}
						}

						String sec = extractSecret(line);
						if (sec != null) {
							foundSecret.set(sec);
							break;
						}
					}
				}

				String secret = foundSecret.get();
				if (secret == null) {
					// Fall back to scanning the data dir — some agent versions only persist.
					secret = readSecretFromDataDir(dataDir);
				}
				if (secret == null) {
					throw new IOException(
							"Timed out waiting for playit.gg link to complete. "
									+ "Did you click \"Accept\" on the claim page?");
				}

				config.values().playitSecretKey = secret;
				config.values().playitGuestMode = false;
				config.save();

				return new LinkResult(secret, foundUrl.get());
			} catch (IOException e) {
				throw new RuntimeException("Link failed: " + e.getMessage(), e);
			} finally {
				if (p != null && p.isAlive()) {
					p.destroy();
					try { p.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
					if (p.isAlive()) p.destroyForcibly();
				}
			}
		}));
	}

	/**
	 * Legacy manual path: trades a user-pasted claim code for a secret key via
	 * {@code playit claim exchange <code>}. Still supported for power users.
	 */
	public CompletableFuture<String> linkAccountAsync(String claimCode) {
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			try {
				CommandResult result = runCommand(bin, "claim", "exchange", claimCode);
				String output = result.output();

				String secret = extractSecret(output);
				if (secret == null) {
					throw new IOException(
							"Could not parse a secret key from agent output:\n" + output);
				}
				config.values().playitSecretKey = secret;
				config.values().playitGuestMode = false;
				config.save();
				return secret;
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("Link failed: " + e.getMessage(), e);
			}
		}));
	}

	/** Forgets the stored secret, dropping the user back into an unlinked state. */
	public void unlink() {
		shutdownBlocking();
		config.values().playitSecretKey = "";
		config.values().playitGuestMode = false;
		config.values().lastTunnelId = "";
		config.save();
		ConduitMod.LOGGER.info("Unlinked playit secret");
	}

	private static String extractSecret(String s) {
		Matcher labeled = SECRET_LINE.matcher(s);
		if (labeled.find()) {
			return labeled.group(1);
		}
		// Fallback: any 32-128 char hex blob.
		Matcher m = Pattern.compile("([0-9a-fA-F]{32,128})").matcher(s);
		return m.find() ? m.group(1) : null;
	}

	private static String readSecretFromDataDir(Path dataDir) {
		try {
			for (Path candidate : List.of(
					dataDir.resolve("playit.toml"),
					dataDir.resolve("config.toml"),
					dataDir.resolve("agent.toml"))) {
				if (!Files.isRegularFile(candidate)) continue;
				String text = Files.readString(candidate, StandardCharsets.UTF_8);
				Matcher m = Pattern.compile("(?im)^\\s*secret(?:_key)?\\s*=\\s*\"?([0-9a-fA-F]{32,128})\"?")
						.matcher(text);
				if (m.find()) return m.group(1);
			}
		} catch (IOException ignored) {}
		return null;
	}

	private CommandResult runCommand(Path bin, String... args)
			throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>();
		cmd.add(bin.toAbsolutePath().toString());
		cmd.addAll(List.of(args));

		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		Path dataDir = config.conduitDir().resolve("playit-data");
		Files.createDirectories(dataDir);
		pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

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

		if (!p.waitFor(30, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("Timed out running: " + String.join(" ", cmd));
		}

		return new CommandResult(p.exitValue(), output.toString());
	}

	/** Result of an interactive link attempt. */
	public record LinkResult(String secret, String claimUrl) {}

	private record CommandResult(int exitCode, String output) {}

	// ── Agent lifecycle ──────────────────────────────────────────────────────

	/**
	 * Start the playit agent and wait asynchronously for it to report a tunnel matching
	 * {@code localPort}. If no secret is present yet this will auto-generate a guest
	 * secret first &mdash; the user never has to sign up.
	 */
	public CompletableFuture<PlayitTunnel> startAsync(int localPort, boolean alsoBedrockUdp) {
		if (isRunning()) {
			return CompletableFuture.completedFuture(lastTunnel.get());
		}
		return ensureGuestSecretAsync()
				.thenCompose(v -> ensureBinaryAsync())
				.thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			String secret = config.values().playitSecretKey;
			if (secret == null || secret.isBlank()) {
				throw new IllegalStateException(
						"No playit secret available. This should have been auto-generated.");
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

				// Drain stdout on a virtual thread, harvesting tunnel info.
				Thread.ofVirtual()
						.name("conduit-playit-pump")
						.start(() -> pumpOutput(p, localPort));

				// Wait up to 30 s for a tunnel line.
				long deadline = System.currentTimeMillis() + 30_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException(
								"playit agent exited early with code " + p.exitValue()
										+ ". See the Console tab for details.");
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
