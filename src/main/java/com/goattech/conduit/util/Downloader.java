package com.goattech.conduit.util;

import com.goattech.conduit.ConduitMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongConsumer;

/**
 * Small, dependency-free HTTP(S) downloader. We follow redirects manually (GitHub releases
 * issue 302s to object storage) because Java's built-in redirect follower refuses to follow
 * cross-protocol / cross-host redirects by default.
 */
public final class Downloader {

	private static final int MAX_REDIRECTS = 6;
	private static final int CONNECT_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 60_000;

	private Downloader() {}

	/**
	 * Download {@code url} into {@code dest} atomically. Calls {@code onBytes} periodically
	 * with the number of bytes pulled so far (for UI progress).
	 */
	public static void download(String url, Path dest, LongConsumer onBytes) throws IOException {
		Files.createDirectories(dest.getParent());
		Path tmp = dest.resolveSibling(dest.getFileName() + ".part");

		String target = url;
		HttpURLConnection conn = null;
		int redirects = 0;
		while (true) {
			URL u = URI.create(target).toURL();
			conn = (HttpURLConnection) u.openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("User-Agent", "Conduit/1.0 (+https://github.com/GoatTech-42/Conduit)");
			conn.setRequestProperty("Accept", "*/*");
			int code = conn.getResponseCode();
			if (code >= 300 && code < 400) {
				String loc = conn.getHeaderField("Location");
				conn.disconnect();
				if (loc == null || ++redirects > MAX_REDIRECTS) {
					throw new IOException("Too many redirects or missing Location for " + target);
				}
				// handle relative redirects
				if (loc.startsWith("/")) {
					URL base = URI.create(target).toURL();
					target = base.getProtocol() + "://" + base.getHost()
							+ (base.getPort() > 0 ? ":" + base.getPort() : "") + loc;
				} else {
					target = loc;
				}
				continue;
			}
			if (code != HttpURLConnection.HTTP_OK) {
				String msg = "HTTP " + code + " for " + target;
				conn.disconnect();
				throw new IOException(msg);
			}
			break;
		}

		long total = 0;
		byte[] buf = new byte[64 * 1024];
		try (InputStream in = conn.getInputStream();
		     OutputStream out = Files.newOutputStream(tmp)) {
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				total += n;
				if (onBytes != null) onBytes.accept(total);
			}
		} finally {
			conn.disconnect();
		}

		Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
		ConduitMod.LOGGER.info("[Conduit] Downloaded {} -> {} ({} bytes)", url, dest, total);
	}
}
