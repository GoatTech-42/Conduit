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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads Geyser + Floodgate (standalone builds) and runs them in a child JVM next to the
 * integrated server.
 *
 * <p>We intentionally use the <strong>standalone</strong> Geyser jar rather than the Fabric
 * mod variant. Why: the Fabric variants of Geyser/Floodgate will almost certainly not be
 * updated to MC 26.1 at the same pace as Conduit, and they'd also inject themselves into the
 * client-side JVM (Geyser-fabric is a server-side mod but still). The standalone jar talks
 * to the Java server over a normal TCP socket the same way any other proxy would, which is
 * both the most robust and the fastest path to cross-play on brand-new Minecraft versions.
 *
 * <p>Floodgate standalone doesn't really exist as a first-class thing — Geyser standalone
 * bundles Floodgate auth, and we just have to drop a {@code floodgate-standalone.jar} next to
 * it which provides the key.pem. That file is generated on first run.
 */
public final class GeyserManager {

	private static final String GEYSER_STANDALONE_URL =
			"https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/standalone";
	private static final String FLOODGATE_STANDALONE_URL =
			"https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/standalone";

	private final ConduitConfig config;
	private final ConcurrentLinkedDeque<String> logBuffer = new ConcurrentLinkedDeque<>();
	private final AtomicReference<Process> process = new AtomicReference<>(null);
	private volatile int bedrockUdpPort = 19132;

	public GeyserManager(ConduitConfig config) {
		this.config = config;
	}

	public boolean isRunning() {
		Process p = process.get();
		return p != null && p.isAlive();
	}

	public int bedrockUdpPort() { return bedrockUdpPort; }

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

	private Path geyserDir() { return config.conduitDir().resolve("geyser"); }
	private Path geyserJar() { return geyserDir().resolve("Geyser-Standalone.jar"); }
	private Path floodgateJar() { return geyserDir().resolve("floodgate-standalone.jar"); }
	private Path configYml() { return geyserDir().resolve("config.yml"); }

	public CompletableFuture<Void> ensureJarsAsync() {
		return CompletableFuture.runAsync(() -> {
			try {
				Files.createDirectories(geyserDir());
				if (!Files.isRegularFile(geyserJar())) {
					ConduitMod.LOGGER.info("[Conduit] Downloading Geyser standalone");
					Downloader.download(GEYSER_STANDALONE_URL, geyserJar(), b -> {});
				}
				if (!Files.isRegularFile(floodgateJar())) {
					ConduitMod.LOGGER.info("[Conduit] Downloading Floodgate standalone");
					Downloader.download(FLOODGATE_STANDALONE_URL, floodgateJar(), b -> {});
				}
			} catch (IOException e) {
				throw new RuntimeException("Failed to download Geyser/Floodgate: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * Start Geyser, forwarding Bedrock traffic on {@code bedrockPort} (UDP) to the Java
	 * integrated server on {@code javaPort} (TCP).
	 */
	public CompletableFuture<Integer> startAsync(int javaPort, int bedrockPort) {
		return ensureJarsAsync().thenCompose(v -> CompletableFuture.supplyAsync(() -> {
			try {
				writeConfig(javaPort, bedrockPort);
				this.bedrockUdpPort = bedrockPort;
				String java = resolveJavaCmd();
				ProcessBuilder pb = new ProcessBuilder(
						java,
						"-Xms256M", "-Xmx512M",
						"-jar", geyserJar().toAbsolutePath().toString())
						.directory(geyserDir().toFile())
						.redirectErrorStream(true);
				Process p = pb.start();
				process.set(p);

				Thread reader = new Thread(() -> pumpOutput(p), "Conduit-geyser-pump");
				reader.setDaemon(true);
				reader.start();

				// Wait up to 20s for Geyser to bind the UDP port.
				long deadline = System.currentTimeMillis() + 20_000;
				while (System.currentTimeMillis() < deadline) {
					if (!p.isAlive()) {
						throw new IOException("Geyser exited early with code " + p.exitValue());
					}
					for (String line : logBuffer) {
						if (line.contains("Started Geyser") || line.contains("listening on")
								|| line.contains("Done") && line.contains("Bedrock")) {
							return bedrockPort;
						}
					}
					Thread.sleep(250);
				}
				ConduitMod.LOGGER.warn("[Conduit] Geyser didn't confirm startup but process is alive; assuming OK.");
				return bedrockPort;
			} catch (IOException | InterruptedException e) {
				Thread.currentThread().interrupt();
				Process p = process.getAndSet(null);
				if (p != null) p.destroyForcibly();
				throw new RuntimeException(e);
			}
		}));
	}

	private void writeConfig(int javaPort, int bedrockPort) throws IOException {
		String yml = """
				# Auto-generated by Conduit. Feel free to edit — we only touch fields we own.
				bedrock:
				  address: 0.0.0.0
				  port: %d
				  clone-remote-port: false
				  motd1: "Conduit"
				  motd2: "Single-player takeover"
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
			Path exe = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
			if (Files.isRegularFile(exe)) return exe.toAbsolutePath().toString();
		}
		return "java";
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private void pumpOutput(Process p) {
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				logBuffer.add("[geyser] " + line);
				while (logBuffer.size() > 1000) logBuffer.pollFirst();
			}
		} catch (IOException ignored) {
		}
	}

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
		ConduitMod.LOGGER.info("[Conduit] Geyser stopped");
	}
}
