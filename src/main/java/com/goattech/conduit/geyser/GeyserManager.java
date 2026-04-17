package com.goattech.conduit.geyser;

import com.goattech.conduit.ConduitMod;
import com.goattech.conduit.config.ConduitConfig;
import com.goattech.conduit.util.Downloader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads Geyser + Floodgate (standalone builds) and runs them in a child JVM next
 * to the integrated server.
 *
 * <p>We use the <strong>standalone</strong> Geyser jar rather than the Fabric mod
 * variant. This avoids version-coupling with Conduit and prevents Geyser from injecting
 * itself into the client-side JVM. The standalone jar talks to the Java server over a
 * normal TCP socket, which is both the most robust and the fastest path to cross-play on
 * brand-new Minecraft versions.
 */
public final class GeyserManager {

	private static final String GEYSER_STANDALONE_URL =
			"https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/standalone";
	private static final String FLOODGATE_STANDALONE_URL =
			"https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/standalone";

	/** Maximum number of log lines retained in memory. */
	private static final int LOG_BUFFER_CAPACITY = 1_000;

	private final ConduitConfig config;
	private final CopyOnWriteArrayList<String> logBuffer = new CopyOnWriteArrayList<>();
	private final AtomicReference<Process> process = new AtomicReference<>();
	private volatile int bedrockUdpPort = 19132;

	public GeyserManager(ConduitConfig config) {
		this.config = config;
	}

	public boolean isRunning() {
		Process p = process.get();
		return p != null && p.isAlive();
	}

	public int bedrockUdpPort() {
		return bedrockUdpPort;
	}

	/**
	 * Returns the most recent {@code max} log lines.
	 */
	public List<String> logTail(int max) {
		List<String> snapshot = List.copyOf(logBuffer);
		int from = Math.max(0, snapshot.size() - max);
		return snapshot.subList(from, snapshot.size());
	}

	// ── Paths ────────────────────────────────────────────────────────────────

	private Path geyserDir()    { return config.conduitDir().resolve("geyser"); }
	private Path geyserJar()    { return geyserDir().resolve("Geyser-Standalone.jar"); }
	private Path floodgateJar() { return geyserDir().resolve("floodgate-standalone.jar"); }
	private Path configYml()    { return geyserDir().resolve("config.yml"); }

	// ── JAR download ─────────────────────────────────────────────────────────

	public CompletableFuture<Void> ensureJarsAsync() {
		return CompletableFuture.runAsync(() -> {
			try {
				Files.createDirectories(geyserDir());
				if (!Files.isRegularFile(geyserJar())) {
					ConduitMod.LOGGER.info("Downloading Geyser standalone");
					Downloader.download(GEYSER_STANDALONE_URL, geyserJar(), bytes -> {});
				}
				if (!Files.isRegularFile(floodgateJar())) {
					ConduitMod.LOGGER.info("Downloading Floodgate standalone");
					Downloader.download(FLOODGATE_STANDALONE_URL, floodgateJar(), bytes -> {});
				}
			} catch (IOException e) {
				throw new RuntimeException(
						"Failed to download Geyser/Floodgate: " + e.getMessage(), e);
			}
		});
	}

	// ── Startup ──────────────────────────────────────────────────────────────

	/**
	 * Start Geyser, forwarding Bedrock traffic on {@code bedrockPort} (UDP) to the Java
	 * integrated server on {@code javaPort} (TCP).
	 */
	public CompletableFuture<Integer> startAsync(int javaPort, int bedrockPort) {
		return ensureJarsAsync().thenCompose(v -> CompletableFuture.supplyAsync(() -> {
			try {
				writeConfig(javaPort, bedrockPort);
				bedrockUdpPort = bedrockPort;

				String java = resolveJavaCmd();
				ProcessBuilder pb = new ProcessBuilder(
						java,
						"-Xms256M", "-Xmx512M",
						"-jar", geyserJar().toAbsolutePath().toString())
						.directory(geyserDir().toFile())
						.redirectErrorStream(true);

				Process p = pb.start();
				process.set(p);

				Thread.ofVirtual()
						.name("conduit-geyser-pump")
						.start(() -> pumpOutput(p));

				// Wait up to 20 s for Geyser to confirm it is listening.
				long deadline = System.currentTimeMillis() + 20_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException(
								"Geyser exited early with code " + p.exitValue());
					}
					for (String line : logBuffer) {
						if (line.contains("Started Geyser")
								|| line.contains("listening on")
								|| (line.contains("Done") && line.contains("Bedrock"))) {
							return bedrockPort;
						}
					}
					Thread.sleep(250);
				}

				ConduitMod.LOGGER.warn(
						"Geyser did not confirm startup but process is alive; assuming OK.");
				return bedrockPort;

			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				destroyProcess();
				throw new RuntimeException(e);
			}
		}));
	}

	// ── Shutdown ─────────────────────────────────────────────────────────────

	/** Gracefully stop the Geyser subprocess (blocking up to 5 s). */
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
		ConduitMod.LOGGER.info("Geyser stopped");
	}

	// ── Internal helpers ─────────────────────────────────────────────────────

	private void writeConfig(int javaPort, int bedrockPort) throws IOException {
		String yml = """
				# Auto-generated by Conduit. Feel free to edit; Conduit only touches fields it owns.
				bedrock:
				  address: 0.0.0.0
				  port: %d
				  clone-remote-port: false
				  motd1: "Conduit"
				  motd2: "Cross-play bridge"
				remote:
				  address: 127.0.0.1
				  port: %d
				  auth-type: floodgate
				floodgate-key-file: key.pem
				passthrough-motd: false
				passthrough-player-counts: true
				max-players: 100
				debug-mode: false
				""".formatted(bedrockPort, javaPort);
		Files.writeString(configYml(), yml, StandardCharsets.UTF_8);
	}

	private static String resolveJavaCmd() {
		String home = System.getProperty("java.home");
		if (home != null) {
			boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
			Path exe = Path.of(home, "bin", win ? "java.exe" : "java");
			if (Files.isRegularFile(exe)) {
				return exe.toAbsolutePath().toString();
			}
		}
		return "java";
	}

	private void pumpOutput(Process p) {
		try (var reader = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				appendLog("[geyser] " + line);
			}
		} catch (IOException ignored) {
			// Process ended.
		}
	}

	private void appendLog(String line) {
		logBuffer.add(line);
		while (logBuffer.size() > LOG_BUFFER_CAPACITY) {
			logBuffer.removeFirst();
		}
	}

	private void destroyProcess() {
		Process p = process.getAndSet(null);
		if (p != null) p.destroyForcibly();
	}
}
