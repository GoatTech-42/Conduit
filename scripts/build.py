#!/usr/bin/env python3
"""
Conduit zero-admin build script.

This script:
  1. Detects the OS / architecture.
  2. If a usable JDK 25+ is not on PATH / JAVA_HOME, downloads Eclipse Temurin
     JDK 25 into .conduit-build/jdk/ (no admin / sudo / MSI required).
  3. Invokes the bundled Gradle wrapper using that JDK to build the mod jar.
  4. Prints the path of the resulting jar.

Usage:
  python3 scripts/build.py              # build
  python3 scripts/build.py clean        # gradle clean
  python3 scripts/build.py --jdk-only   # just bootstrap JDK, don't build
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# ── Constants ──────────────────────────────────────────────────────────────────

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".conduit-build"
REQUIRED_MAJOR = 25

ADOPTIUM_API = (
    "https://api.adoptium.net/v3/assets/latest/{major}/hotspot?vendor=eclipse"
)


# ── Logging helpers ────────────────────────────────────────────────────────────

def info(msg: str) -> None:
    print(f"\033[1;34m[conduit-build]\033[0m {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"\033[1;33m[conduit-build]\033[0m {msg}", flush=True)


def success(msg: str) -> None:
    print(f"\033[1;32m[conduit-build]\033[0m {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"\033[1;31m[conduit-build]\033[0m {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


# ── JDK detection ──────────────────────────────────────────────────────────────

def java_major(java_exe: str) -> int | None:
    """Parse the major version from ``java -version`` output."""
    try:
        result = subprocess.run(
            [java_exe, "-version"],
            capture_output=True, text=True, timeout=10,
        )
        text = (result.stderr or "") + (result.stdout or "")
        for line in text.splitlines():
            if "version" in line:
                parts = line.split('"')
                if len(parts) >= 2:
                    ver = parts[1]
                    head = ver.split(".")[0]
                    # Legacy "1.x" scheme (JDK 8 and below).
                    if head == "1" and len(ver.split(".")) > 1:
                        head = ver.split(".")[1]
                    try:
                        return int(head)
                    except ValueError:
                        return None
    except Exception:
        return None
    return None


def find_usable_jdk() -> str | None:
    """Look for an existing JDK >= REQUIRED_MAJOR on JAVA_HOME or PATH."""
    candidates: list[str] = []

    jh = os.environ.get("JAVA_HOME")
    if jh:
        exe = "java.exe" if os.name == "nt" else "java"
        cand = Path(jh) / "bin" / exe
        if cand.exists():
            candidates.append(str(cand))

    path_java = shutil.which("java")
    if path_java:
        candidates.append(path_java)

    for c in candidates:
        m = java_major(c)
        info(f"Considering {c} (Java {m})")
        if m is not None and m >= REQUIRED_MAJOR:
            return c
    return None


# ── Adoptium JDK bootstrap ────────────────────────────────────────────────────

def _detect_arch_os() -> tuple[str, str]:
    os_name = platform.system().lower()
    mach = platform.machine().lower()

    o = {"windows": "windows", "darwin": "mac"}.get(os_name, "linux")

    arch_map = {
        "x86_64": "x64", "amd64": "x64",
        "arm64": "aarch64", "aarch64": "aarch64",
        "i686": "x86", "i386": "x86", "x86": "x86",
    }
    a = arch_map.get(mach, "arm" if mach.startswith("arm") else mach)
    return o, a


def _sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _http_get(url: str, dest: Path) -> None:
    """Download a URL to a file with a progress indicator."""
    req = urllib.request.Request(url, headers={
        "User-Agent": "conduit-build/1.0",
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=120) as resp, dest.open("wb") as out:
        total = int(resp.getheader("Content-Length", 0))
        downloaded = 0
        while True:
            chunk = resp.read(128 * 1024)
            if not chunk:
                break
            out.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                pct = downloaded * 100 // total
                mb = downloaded / (1024 * 1024)
                total_mb = total / (1024 * 1024)
                print(
                    f"\r  {mb:.1f} / {total_mb:.1f} MB ({pct}%)",
                    end="", flush=True,
                )
        if total > 0:
            print(flush=True)  # newline


def _http_get_json(url: str) -> object:
    req = urllib.request.Request(url, headers={
        "User-Agent": "conduit-build/1.0",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def bootstrap_jdk() -> str:
    """Download Temurin JDK into .conduit-build/jdk/ and return the java path."""
    CACHE.mkdir(exist_ok=True)
    jdk_root = CACHE / "jdk"
    exe_name = "java.exe" if os.name == "nt" else "java"

    # Reuse an existing local install.
    if jdk_root.exists():
        for release_file in jdk_root.rglob("release"):
            cand = release_file.parent / "bin" / exe_name
            if cand.exists():
                m = java_major(str(cand))
                if m and m >= REQUIRED_MAJOR:
                    info(f"Reusing JDK at {cand}")
                    return str(cand)

    os_name, arch = _detect_arch_os()
    info(f"Bootstrapping JDK {REQUIRED_MAJOR} for {os_name}-{arch} ...")

    try:
        listing = _http_get_json(ADOPTIUM_API.format(major=REQUIRED_MAJOR))
    except urllib.error.HTTPError as e:
        die(f"Adoptium API error: {e}")
        return ""  # unreachable

    pkg = None
    for entry in listing:
        if not isinstance(entry, dict):
            continue
        b = entry.get("binary", {})
        if b.get("image_type") != "jdk":
            continue
        if b.get("os") != os_name or b.get("architecture") != arch:
            continue
        pkg = b.get("package")
        if pkg:
            break

    if not pkg:
        die(
            f"No Temurin JDK {REQUIRED_MAJOR} build for {os_name}-{arch}. "
            "Install JDK 25 manually and set JAVA_HOME."
        )
        return ""

    link = pkg["link"]
    filename = pkg["name"]
    expected_sha = pkg.get("checksum")
    archive = CACHE / filename

    if not archive.exists() or (expected_sha and _sha256_of(archive) != expected_sha):
        info(f"Downloading {link}")
        _http_get(link, archive)
        if expected_sha:
            got = _sha256_of(archive)
            if got != expected_sha:
                archive.unlink(missing_ok=True)
                die(f"Checksum mismatch on {filename}: got {got}, expected {expected_sha}")

    info(f"Extracting {filename} ...")
    jdk_root.mkdir(parents=True, exist_ok=True)

    # Clear old partial extractions.
    for child in jdk_root.iterdir():
        if child.is_dir():
            shutil.rmtree(child, ignore_errors=True)

    if filename.endswith(".zip"):
        with zipfile.ZipFile(archive) as zf:
            zf.extractall(jdk_root)
    elif filename.endswith((".tar.gz", ".tgz")):
        with tarfile.open(archive, "r:gz") as tf:
            # Use data_filter for safety on Python >= 3.12.
            if hasattr(tarfile, "data_filter"):
                tf.extractall(jdk_root, filter="data")
            else:
                tf.extractall(jdk_root)
    else:
        die(f"Unknown archive format: {filename}")

    # Locate the extracted java binary.
    for cand in jdk_root.rglob(exe_name):
        if cand.parent.name == "bin":
            m = java_major(str(cand))
            if m and m >= REQUIRED_MAJOR:
                success(f"JDK ready at {cand}")
                return str(cand)

    die("Extracted JDK but could not find a usable java binary inside it.")
    return ""


# ── Gradle invocation ──────────────────────────────────────────────────────────

def run_gradle(java_exe: str, tasks: list[str]) -> int:
    env = os.environ.copy()
    java_home = str(Path(java_exe).resolve().parent.parent)
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java_exe).parent) + os.pathsep + env.get("PATH", "")

    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        die(f"Missing Gradle wrapper at {gradlew}")

    if os.name != "nt":
        try:
            gradlew.chmod(0o755)
        except OSError:
            pass

    cmd = [str(gradlew), *tasks, "--no-daemon", "--stacktrace"]
    info("Running: " + " ".join(cmd))
    info(f"JAVA_HOME = {java_home}")
    return subprocess.call(cmd, cwd=str(ROOT), env=env)


def print_output_jars() -> None:
    out = ROOT / "build" / "libs"
    if not out.exists():
        warn("No build/libs directory found.")
        return
    jars = sorted(out.glob("*.jar"))
    if not jars:
        warn("No jars produced.")
        return
    success("Build output:")
    for j in jars:
        size_kb = j.stat().st_size / 1024
        print(f"  -> {j}  ({size_kb:,.1f} KB)")


# ── Main ───────────────────────────────────────────────────────────────────────

def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Conduit zero-admin build script",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n"
               "  python3 scripts/build.py           # build the jar\n"
               "  python3 scripts/build.py clean      # gradle clean\n"
               "  python3 scripts/build.py --jdk-only # bootstrap JDK only\n",
    )
    parser.add_argument(
        "task", nargs="?", default="build",
        help="Gradle task to run (default: build).",
    )
    parser.add_argument(
        "--jdk-only", action="store_true",
        help="Only bootstrap the JDK; do not build.",
    )
    parser.add_argument(
        "--force-bootstrap", action="store_true",
        help="Ignore any JDK on PATH and always download a fresh one.",
    )
    args = parser.parse_args(argv)

    info(f"Platform: {platform.system()} {platform.machine()}")
    info(f"Python:   {sys.version.split()[0]}")

    if args.force_bootstrap:
        java_exe = bootstrap_jdk()
    else:
        java_exe = find_usable_jdk() or bootstrap_jdk()

    success(f"Using java: {java_exe}")

    if args.jdk_only:
        return 0

    rc = run_gradle(java_exe, [args.task])
    if rc != 0:
        die(f"Gradle exited with code {rc}", rc)
    print_output_jars()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
