#!/usr/bin/env python3
"""
Conduit zero-admin build script.

What this does:
  1. Detects the OS/arch.
  2. If a usable JDK 25+ is not on PATH, downloads Adoptium Temurin JDK 25 into .conduit-build/jdk
     (user-local, NO admin / sudo / MSI required).
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

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / ".conduit-build"
CACHE.mkdir(exist_ok=True)

REQUIRED_MAJOR = 25


# ---------------------------------------------------------------------------
# Helpers

def info(msg: str) -> None:
    print(f"\033[1;34m[conduit-build]\033[0m {msg}", flush=True)


def warn(msg: str) -> None:
    print(f"\033[1;33m[conduit-build]\033[0m {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"\033[1;31m[conduit-build]\033[0m {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def which(name: str) -> str | None:
    return shutil.which(name)


def java_major(java_exe: str) -> int | None:
    try:
        out = subprocess.run([java_exe, "-version"], capture_output=True, text=True, timeout=10)
        text = (out.stderr or "") + (out.stdout or "")
        # Examples:
        #   openjdk version "25.0.1" 2025-10-21
        #   java version "1.8.0_291"
        for line in text.splitlines():
            if "version" in line:
                parts = line.split('"')
                if len(parts) >= 2:
                    ver = parts[1]
                    head = ver.split(".")[0]
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
    """Look for an existing java runtime that's >= REQUIRED_MAJOR."""
    candidates: list[str] = []

    # Use JAVA_HOME first if set
    jh = os.environ.get("JAVA_HOME")
    if jh:
        exe = "java.exe" if os.name == "nt" else "java"
        cand = Path(jh) / "bin" / exe
        if cand.exists():
            candidates.append(str(cand))

    # PATH
    if which("java"):
        candidates.append(which("java"))  # type: ignore[arg-type]

    for c in candidates:
        m = java_major(c)
        info(f"Considering {c} (Java {m})")
        if m is not None and m >= REQUIRED_MAJOR:
            return c
    return None


# ---------------------------------------------------------------------------
# Adoptium JDK bootstrap (no admin)

ADOPTIUM_API = ("https://api.adoptium.net/v3/assets/latest/"
                "{major}/hotspot?vendor=eclipse")


def _detect_arch_os() -> tuple[str, str]:
    os_name = platform.system().lower()
    mach = platform.machine().lower()
    if os_name == "windows":
        o = "windows"
    elif os_name == "darwin":
        o = "mac"
    else:
        o = "linux"

    if mach in ("x86_64", "amd64"):
        a = "x64"
    elif mach in ("arm64", "aarch64"):
        a = "aarch64"
    elif mach.startswith("arm"):
        a = "arm"
    elif mach in ("i686", "i386", "x86"):
        a = "x86"
    else:
        a = mach
    return o, a


def _sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _http_get(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, headers={
        "User-Agent": "conduit-build/1.0",
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=120) as r, dest.open("wb") as out:
        shutil.copyfileobj(r, out, length=128 * 1024)


def _http_get_json(url: str) -> object:
    req = urllib.request.Request(url, headers={
        "User-Agent": "conduit-build/1.0",
        "Accept": "application/json",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def bootstrap_jdk() -> str:
    """Download Temurin JDK {REQUIRED_MAJOR} into .conduit-build/jdk and return its java path."""
    jdk_root = CACHE / "jdk"
    exe_name = "java.exe" if os.name == "nt" else "java"
    # Look for an existing install first.
    if jdk_root.exists():
        for home in jdk_root.rglob("release"):
            bin_dir = home.parent / "bin"
            cand = bin_dir / exe_name
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
        return ""

    pkg = None
    for entry in listing:
        if not isinstance(entry, dict):
            continue
        b = entry.get("binary", {})
        if b.get("image_type") != "jdk":
            continue
        if b.get("os") != os_name:
            continue
        if b.get("architecture") != arch:
            continue
        pkg = b.get("package")
        if pkg:
            break
    if not pkg:
        die(f"No Temurin JDK {REQUIRED_MAJOR} build available for {os_name}-{arch}. "
            f"Install JDK 25 manually and set JAVA_HOME.")
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
                die(f"Checksum mismatch on {filename}: got {got} expected {expected_sha}")

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
            tf.extractall(jdk_root)
    else:
        die(f"Unknown archive format: {filename}")

    # Locate the extracted java binary.
    for cand in jdk_root.rglob(exe_name):
        if cand.parent.name == "bin":
            m = java_major(str(cand))
            if m and m >= REQUIRED_MAJOR:
                info(f"JDK ready at {cand}")
                return str(cand)

    die("Extracted JDK but couldn't find a usable java binary inside it.")
    return ""


# ---------------------------------------------------------------------------
# Gradle invocation

def run_gradle(java_exe: str, args: list[str]) -> int:
    env = os.environ.copy()
    java_home = str(Path(java_exe).resolve().parent.parent)
    env["JAVA_HOME"] = java_home
    # Make sure the wrapper picks up our JDK even if PATH points elsewhere.
    env["PATH"] = str(Path(java_exe).parent) + os.pathsep + env.get("PATH", "")

    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not gradlew.exists():
        die(f"Missing Gradle wrapper at {gradlew}")

    if os.name != "nt":
        try:
            gradlew.chmod(0o755)
        except OSError:
            pass

    cmd = [str(gradlew), *args, "--no-daemon", "--stacktrace"]
    info("Running: " + " ".join(cmd))
    info(f"JAVA_HOME = {java_home}")
    return subprocess.call(cmd, cwd=str(ROOT), env=env)


def print_output_jars() -> None:
    out = ROOT / "build" / "libs"
    if not out.exists():
        warn(f"No build/libs directory yet at {out}")
        return
    jars = sorted(out.glob("*.jar"))
    if not jars:
        warn("No jars produced.")
        return
    info("Build output:")
    for j in jars:
        print(f"  -> {j}  ({j.stat().st_size:,} bytes)")


# ---------------------------------------------------------------------------
# Main

def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(description="Conduit zero-admin build script")
    p.add_argument("task", nargs="?", default="build",
                   help="Gradle task to run (default: build). Use 'clean' or anything else Gradle accepts.")
    p.add_argument("--jdk-only", action="store_true",
                   help="Only bootstrap the JDK, don't build.")
    p.add_argument("--force-bootstrap", action="store_true",
                   help="Ignore any JDK on PATH and always download our own.")
    args = p.parse_args(argv)

    if args.force_bootstrap:
        java_exe = bootstrap_jdk()
    else:
        java_exe = find_usable_jdk() or bootstrap_jdk()

    info(f"Using java: {java_exe}")

    if args.jdk_only:
        return 0

    rc = run_gradle(java_exe, [args.task])
    if rc != 0:
        die(f"Gradle exited with code {rc}", rc)
    print_output_jars()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
