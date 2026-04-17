package com.goattech.conduit.util;

/**
 * Resolves the right per-platform playit release asset for the current JVM.
 * <p>We intentionally never fall back to the .msi / .deb packages — Conduit is zero-admin and
 * ships the raw binary into ./conduit/.
 */
public final class PlatformBinary {

	private PlatformBinary() {}

	public static String playitAssetName() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String arch = System.getProperty("os.arch", "").toLowerCase();

		if (os.contains("win")) {
			if (arch.contains("64")) return "playit-windows-x86_64.exe";
			return "playit-windows-x86.exe";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			// No official mac binary is published in release assets at the moment; the user
			// has to install via brew / the PKG. We surface this cleanly in the UI rather
			// than silently downloading a broken file.
			throw new UnsupportedOperationException(
					"macOS isn't directly supported by Conduit yet — install `playit` via brew "
					+ "and set $PATH so Conduit can find it.");
		}
		// Assume Linux / BSD-ish.
		if (arch.contains("aarch64") || arch.contains("arm64")) return "playit-linux-aarch64";
		if (arch.contains("arm"))  return "playit-linux-armv7";
		if (arch.contains("64"))    return "playit-linux-amd64";
		return "playit-linux-i686";
	}

	public static String playitExecutableName() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) return "playit.exe";
		return "playit";
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
}
