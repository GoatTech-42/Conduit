package com.goattech.conduit.util;

import com.goattech.conduit.ConduitMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.LongConsumer;

/**
 * Dependency-free HTTP(S) file downloader.
 *
 * <p>Uses the modern {@link HttpClient} API which automatically follows cross-host
 * and cross-protocol redirects (GitHub releases &rarr; object storage). Downloads are
 * atomic: the file is written to a {@code .part} sibling and moved into place only on
 * success, preventing partially-written binaries.
 */
public final class Downloader {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

	private Downloader() {}

	/**
	 * Download {@code url} into {@code dest} atomically.
	 *
	 * @param url     the remote URL to fetch
	 * @param dest    the target file path (parent directories are created automatically)
	 * @param onBytes called periodically with the cumulative byte count (for progress UI)
	 * @throws IOException on any network or I/O failure
	 */
	public static void download(String url, Path dest, LongConsumer onBytes) throws IOException {
		Files.createDirectories(dest.getParent());
		Path tmp = dest.resolveSibling(dest.getFileName() + ".part");

		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "Conduit/1.0 (+https://github.com/GoatTech-42/Conduit)")
				.header("Accept", "*/*")
				.timeout(REQUEST_TIMEOUT)
				.GET()
				.build();

		try (HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.ALWAYS)
				.connectTimeout(CONNECT_TIMEOUT)
				.build()) {

			HttpResponse<InputStream> response = client.send(request,
					HttpResponse.BodyHandlers.ofInputStream());

			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " for " + url);
			}

			long total = 0;
			byte[] buf = new byte[64 * 1024];
			try (InputStream in = response.body();
			     OutputStream out = Files.newOutputStream(tmp)) {
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					total += n;
					if (onBytes != null) onBytes.accept(total);
				}
			}

			Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			ConduitMod.LOGGER.info("Downloaded {} -> {} ({} bytes)", url, dest, total);

		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted: " + url, e);
		} finally {
			// Clean up partial file on failure.
			try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
		}
	}
}
