package com.goattech.conduit.util;

/**
 * Resolves the correct per-platform playit release asset for the current JVM.
 *
 * <p>Conduit is zero-admin and always downloads the <em>raw binary</em> into
 * {@code ./conduit/bin/} &mdash; never the {@code .msi} or {@code .deb} package.
 */
public final class PlatformBinary {

	private PlatformBinary() {}

	private static final String OS   = System.getProperty("os.name", "").toLowerCase();
	private static final String ARCH = System.getProperty("os.arch", "").toLowerCase();

	/** Returns {@code true} when running on a Windows JVM. */
	public static boolean isWindows() {
		return OS.contains("win");
	}

	/** Returns {@code true} when running on a macOS / Darwin JVM. */
	public static boolean isMac() {
		return OS.contains("mac") || OS.contains("darwin");
	}

	/**
	 * The file name of the playit binary as it appears in the GitHub release assets.
	 *
	 * @throws UnsupportedOperationException on macOS (no official standalone binary)
	 */
	public static String playitAssetName() {
		if (isWindows()) {
			return is64Bit() ? "playit-windows-x86_64.exe" : "playit-windows-x86.exe";
		}
		if (isMac()) {
			throw new UnsupportedOperationException(
					"macOS is not directly supported by Conduit yet. "
					+ "Install playit via Homebrew (`brew install playit`) "
					+ "and make sure it is on your $PATH."
			);
		}
		// Linux / BSD-ish
		if (isArm64())  return "playit-linux-aarch64";
		if (isArm32())  return "playit-linux-armv7";
		if (is64Bit())  return "playit-linux-amd64";
		return "playit-linux-i686";
	}

	/** The local executable name (platform-appropriate). */
	public static String playitExecutableName() {
		return isWindows() ? "playit.exe" : "playit";
	}

	// ── Architecture helpers ─────────────────────────────────────────────────

	private static boolean is64Bit() {
		return ARCH.contains("amd64") || ARCH.contains("x86_64") || ARCH.equals("x64");
	}

	private static boolean isArm64() {
		return ARCH.contains("aarch64") || ARCH.contains("arm64");
	}

	private static boolean isArm32() {
		return ARCH.contains("arm") && !isArm64();
	}
}
