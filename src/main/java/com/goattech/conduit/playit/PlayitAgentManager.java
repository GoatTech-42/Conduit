package com.goattech.conduit.playit;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.config.ConduitConfig;
import com.goattech.conduit.util.ConsoleLog;
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
 * service install, fully portable. Authentication is handled via the agent's own
 * config directory ({@code PLAYIT_CONFIG_HOME}).
 *
 * <p>Conduit supports three modes:
 * <ol>
 *   <li><b>Guest mode (default)</b> &mdash; no playit.gg account is required. Conduit
 *       runs the agent headlessly; the agent self-provisions a secret on its very first
 *       run. The secret path is discovered via {@code playit secret-path} and persisted
 *       in {@code conduit.json} for subsequent launches.</li>
 *   <li><b>Auto-link</b> &mdash; the user clicks "Link playit.gg Account". Conduit runs
 *       the agent interactively, captures the {@code playit.gg/claim/<code>} URL it
 *       prints, and opens it in the user's browser. The agent completes the exchange on
 *       its own as soon as the user clicks "Accept" on the web page.</li>
 *   <li><b>Manual claim code</b> &mdash; the user can paste a claim code from
 *       {@code playit.gg/claim} and Conduit runs {@code playit claim exchange} directly.</li>
 * </ol>
 *
 * <h3>playit v0.17.x CLI reference</h3>
 * The v0.17 agent has the following subcommands:
 * <ul>
 *   <li>{@code playit} or {@code playit start} &mdash; run the agent (auto-provisions on
 *       first use; generates a secret + tunnel automatically).</li>
 *   <li>{@code playit reset} &mdash; clear local state.</li>
 *   <li>{@code playit secret-path} &mdash; print the path to the stored secret file.</li>
 * </ul>
 * <p><strong>There is no {@code secret generate} or {@code secret new} subcommand.</strong>
 * Guest provisioning is done by simply launching the agent and letting it self-configure.
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

	/*
	 * Broader tunnel fallback: some agent versions print something like
	 *   "address = xxx.gl.joinmc.link:12345" or "tunnel address: host:port"
	 */
	private static final Pattern TUNNEL_ADDRESS_LINE = Pattern.compile(
			"(?i)(?:address|tunnel|assigned)[^\\n]*?([A-Za-z0-9.-]+\\.(?:ply\\.gg|joinmc\\.link|at\\.playit\\.gg)):(\\d+)");

	private static final Pattern CLAIM_URL = Pattern.compile(
			"(?i)(https?://(?:www\\.)?playit\\.gg/(?:claim|mc)(?:/[A-Za-z0-9-]+)?)");
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
	 * is a no-op. Otherwise, Conduit provisions a guest secret by:
	 *
	 * <ol>
	 *   <li>First, trying {@code playit secret-path} to discover if the agent already has
	 *       a local secret on disk. If so, we read and cache it.</li>
	 *   <li>If no secret exists yet, we launch the agent briefly with {@code playit start}
	 *       (or bare {@code playit}) in the background. The agent auto-provisions a guest
	 *       secret on first run. We wait for it to write the secret, read it back via
	 *       {@code secret-path}, then terminate the bootstrap run.</li>
	 * </ol>
	 *
	 * <p><strong>This replaces the previous (broken) calls to {@code secret generate} and
	 * {@code secret new}, which do not exist in playit v0.17.x.</strong>
	 */
	public CompletableFuture<Void> ensureGuestSecretAsync() {
		if (hasSecret()) {
			return CompletableFuture.completedFuture(null);
		}
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			Path dataDir = prepareDataDir();

			// ── Attempt 1: check if a secret already exists on disk ──
			String existingSecret = readSecretViaSecretPath(bin, dataDir);
			if (existingSecret != null) {
				config.values().playitSecretKey = existingSecret;
				config.values().playitGuestMode = true;
				config.save();
				ConduitMod.LOGGER.info("Found existing playit secret ({} chars)", existingSecret.length());
				return null;
			}

			// ── Also check the data dir TOML files directly ──
			existingSecret = readSecretFromDataDir(dataDir);
			if (existingSecret != null) {
				config.values().playitSecretKey = existingSecret;
				config.values().playitGuestMode = true;
				config.save();
				ConduitMod.LOGGER.info("Found existing playit secret from TOML ({} chars)", existingSecret.length());
				return null;
			}

			// ── Attempt 2: launch the agent briefly to auto-provision ──
			ConduitMod.LOGGER.info("No playit secret found; launching agent for guest provisioning...");
			Process bootstrap = null;
			try {
				ProcessBuilder pb = new ProcessBuilder(bin.toAbsolutePath().toString())
						.redirectErrorStream(true);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

				bootstrap = pb.start();

				// Read output, looking for the agent to report it's ready (tunnel line,
				// "ready", "listening", claim URL). Give it up to 30 s.
				long deadline = System.currentTimeMillis() + 30_000;
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(bootstrap.getInputStream(), StandardCharsets.UTF_8));
				Thread pumpThread = Thread.ofVirtual().name("conduit-playit-bootstrap").start(() -> {
					try {
						String line;
						while ((line = reader.readLine()) != null) {
							appendLog("[bootstrap] " + line);
						}
					} catch (IOException ignored) {}
				});

				// Wait for a secret to appear on disk.
				while (System.currentTimeMillis() < deadline) {
					Thread.sleep(1_000);
					String secret = readSecretViaSecretPath(bin, dataDir);
					if (secret == null) {
						secret = readSecretFromDataDir(dataDir);
					}
					if (secret != null) {
						config.values().playitSecretKey = secret;
						config.values().playitGuestMode = true;
						config.save();
						ConduitMod.LOGGER.info("Guest provisioning complete ({} chars)", secret.length());
						return null;
					}
					if (!bootstrap.isAlive()) {
						break;
					}
				}

				// Final attempt after the agent may have written and exited.
				String secret = readSecretViaSecretPath(bin, dataDir);
				if (secret == null) {
					secret = readSecretFromDataDir(dataDir);
				}
				if (secret != null) {
					config.values().playitSecretKey = secret;
					config.values().playitGuestMode = true;
					config.save();
					return null;
				}

				throw new RuntimeException(
						"Could not auto-provision a playit secret. The playit agent may "
								+ "require an interactive setup on first use.\n\n"
								+ "You can still click \"Link playit.gg Account\" to set up "
								+ "your tunnel manually, or run 'playit' in a terminal to "
								+ "complete the initial setup.");

			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(
						"Failed during guest provisioning: " + e.getMessage()
								+ "\n\nYou can still click \"Link playit.gg Account\" to "
								+ "link one manually.", e);
			} finally {
				if (bootstrap != null && bootstrap.isAlive()) {
					bootstrap.destroy();
					try { bootstrap.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
					if (bootstrap.isAlive()) bootstrap.destroyForcibly();
				}
			}
		}));
	}

	/**
	 * Run {@code playit secret-path} and read the file it points to.
	 * Returns the hex secret if found, or {@code null}.
	 */
	private String readSecretViaSecretPath(Path bin, Path dataDir) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					bin.toAbsolutePath().toString(), "secret-path")
					.redirectErrorStream(true);
			pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

			Process p = pb.start();
			String output;
			try (var reader = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				output = reader.lines().reduce("", (a, b) -> a + "\n" + b).strip();
			}
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				return null;
			}
			if (p.exitValue() != 0 || output.isBlank()) {
				return null;
			}

			appendLog("[secret-path] " + output);

			// The output should be a file path; try to read it.
			Path secretFile = Path.of(output.strip());
			if (Files.isRegularFile(secretFile)) {
				String content = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
				String secret = extractSecret(content);
				if (secret != null) return secret;
				// The file might just contain the raw hex.
				if (content.matches("[0-9a-fA-F]{32,128}")) return content;
			}
		} catch (IOException | InterruptedException e) {
			ConduitMod.LOGGER.debug("secret-path probe failed: {}", e.getMessage());
		}
		return null;
	}

	private Path prepareDataDir() {
		Path dataDir = config.conduitDir().resolve("playit-data");
		try {
			Files.createDirectories(dataDir);
		} catch (IOException e) {
			throw new RuntimeException("Cannot create playit data directory", e);
		}
		return dataDir;
	}

	// ── Account linking ──────────────────────────────────────────────────────

	/**
	 * One-click linking: start the agent interactively, watch for the claim URL, open it
	 * in the user's browser via {@code urlSink}, then wait until the agent reports that
	 * the account has been linked. The generated secret is persisted on success.
	 *
	 * @param urlSink receives the {@code playit.gg/claim/<code>} URL as soon as the
	 *                agent prints it. Typically this opens the URL in the default browser.
	 */
	public CompletableFuture<LinkResult> linkAccountInteractiveAsync(Consumer<String> urlSink) {
		return ensureBinaryAsync().thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			Process p = null;
			try {
				Path dataDir = prepareDataDir();

				// Start the agent normally; it will print a claim URL if not yet linked.
				ProcessBuilder pb = new ProcessBuilder(
						bin.toAbsolutePath().toString())
						.redirectErrorStream(true);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

				p = pb.start();
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

						// Check if a tunnel came up (means the agent is linked and running)
						if (TUNNEL_LINE.matcher(line).find()
								|| TUNNEL_ADDRESS_LINE.matcher(line).find()) {
							// Agent is running with tunnels; it's linked. Read the secret.
							String diskSecret = readSecretViaSecretPath(bin, dataDir);
							if (diskSecret == null) diskSecret = readSecretFromDataDir(dataDir);
							if (diskSecret != null) {
								foundSecret.set(diskSecret);
								break;
							}
						}
					}
				}

				String secret = foundSecret.get();
				if (secret == null) {
					// Fall back to scanning the data dir.
					secret = readSecretViaSecretPath(bin, dataDir);
				}
				if (secret == null) {
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
		Path dataDir = prepareDataDir();
		pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());

		Process p = pb.start();

		var output = new StringBuilder();
		try (var reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
				appendLog("[cmd] " + line);
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
	 *
	 * <p>The agent is launched as {@code playit} (bare, no subcommand) or
	 * {@code playit start}. The {@code PLAYIT_SECRET} env var is set if a secret is
	 * known, and {@code PLAYIT_CONFIG_HOME} always points to our data directory.
	 */
	public CompletableFuture<PlayitTunnel> startAsync(int localPort, boolean alsoBedrockUdp) {
		if (isRunning()) {
			return CompletableFuture.completedFuture(lastTunnel.get());
		}
		return ensureGuestSecretAsync()
				.thenCompose(v -> ensureBinaryAsync())
				.thenCompose(bin -> CompletableFuture.supplyAsync(() -> {
			try {
				Path dataDir = prepareDataDir();
				String secret = config.values().playitSecretKey;

				List<String> cmd = new ArrayList<>();
				cmd.add(bin.toAbsolutePath().toString());
				// Use bare invocation (equivalent to 'start') — the most portable option.

				ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
				pb.environment().put("PLAYIT_CONFIG_HOME", dataDir.toString());
				if (secret != null && !secret.isBlank()) {
					pb.environment().put("PLAYIT_SECRET", secret);
				}

				Process p = pb.start();
				process.set(p);

				// Drain stdout on a virtual thread, harvesting tunnel info.
				Thread.ofVirtual()
						.name("conduit-playit-pump")
						.start(() -> pumpOutput(p, localPort));

				// Wait up to 60 s for a tunnel line (first run may need provisioning time).
				long deadline = System.currentTimeMillis() + 60_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException(
								"playit agent exited early with code " + p.exitValue()
										+ ". See the Console tab of the admin panel for the "
										+ "agent's output.");
					}
					PlayitTunnel t = lastTunnel.get();
					if (t != null && t.localPort() == localPort) {
						return t;
					}
					Thread.sleep(250);
				}
				throw new IOException(
						"Timed out waiting for the playit tunnel to come up after 60s. "
								+ "Your network may be blocking outbound traffic to playit's "
								+ "relays (common on corporate networks). Open the Console tab "
								+ "for details, or try 'Reset playit agent' from the Network tab.");
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

				// Try primary tunnel pattern.
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
					continue;
				}

				// Try fallback address pattern for new agent output formats.
				Matcher am = TUNNEL_ADDRESS_LINE.matcher(line);
				if (am.find()) {
					String host = am.group(1);
					int pub = Integer.parseInt(am.group(2));
					// Assume it maps to our expected local port.
					var tunnel = new PlayitTunnel(
							"auto-" + expectedLocalPort,
							"Minecraft",
							PlayitTunnel.Protocol.TCP,
							host, pub, expectedLocalPort);
					lastTunnel.set(tunnel);
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
		// Mirror into the global Conduit console so the admin-panel Console tab
		// can show a unified, interleaved view of every subsystem.
		ConsoleLog.INSTANCE.append("playit", stripOwnPrefix(line));
	}

	private static String stripOwnPrefix(String s) {
		// pumpOutput / bootstrap / link already prefix their lines; keep them
		// out of the unified console so we don't double-tag.
		if (s == null) return "";
		if (s.startsWith("[playit] "))    return s.substring(9);
		if (s.startsWith("[bootstrap] ")) return s.substring(12);
		if (s.startsWith("[link] "))      return s.substring(7);
		if (s.startsWith("[cmd] "))       return s.substring(6);
		if (s.startsWith("[secret-path] ")) return s.substring(14);
		return s;
	}

	// ── Additional CLI commands exposed to the UI ────────────────────────────

	/**
	 * Runs {@code playit reset} to wipe the agent's local state (secret,
	 * tunnels, etc.). Conduit's own stored secret is cleared as well.
	 *
	 * @return the agent's stdout/stderr output, for display in the console.
	 */
	public CompletableFuture<String> resetAgentAsync() {
		return ensureBinaryAsync().thenApply(bin -> {
			try {
				shutdownBlocking();
				CommandResult r = runCommand(bin, "reset");
				config.values().playitSecretKey = "";
				config.values().playitGuestMode = false;
				config.values().lastTunnelId = "";
				config.save();
				ConduitMod.LOGGER.info("playit agent reset (exit={})", r.exitCode());
				return r.output();
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("reset failed: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Runs {@code playit secret-path} and returns its raw output. Useful when
	 * the user wants to know where the local secret file lives, or to copy it
	 * somewhere safe.
	 */
	public CompletableFuture<String> secretPathAsync() {
		return ensureBinaryAsync().thenApply(bin -> {
			try {
				CommandResult r = runCommand(bin, "secret-path");
				return r.output().strip();
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException("secret-path failed: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Writes a raw command to the <em>running</em> agent's stdin. Useful for
	 * the few interactive prompts the agent may show (e.g. "Press enter to
	 * continue"). Returns {@code false} if the agent isn't running.
	 */
	public boolean writeStdin(String input) {
		Process p = process.get();
		if (p == null || !p.isAlive()) return false;
		try {
			var out = p.getOutputStream();
			out.write((input + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
			out.flush();
			return true;
		} catch (IOException e) {
			ConduitMod.LOGGER.debug("playit stdin write failed: {}", e.toString());
			return false;
		}
	}

	private void destroyProcess() {
		Process p = process.getAndSet(null);
		if (p != null) p.destroyForcibly();
	}
}
