# Conduit  `v1.0.0`

**One-click server hosting for your single-player Minecraft worlds** — powered by [playit.gg](https://playit.gg), with optional [Geyser](https://geysermc.org) / Floodgate cross-play for Bedrock friends.

*A [GoatTech Industries](https://github.com/GoatTech-42) mod.*

> No port-forwarding. No sign-up. No server install. No admin rights.
> Open your world, click one button, hand your friend an address.

---

## Highlights

- **🟢 "Host This World" button** injected into the in-game **pause menu**. One click to go from singleplayer to a public tunnel.
- **🟢 Zero-signup, zero-admin**. Conduit downloads the `playit` agent binary on demand into `<.minecraft>/conduit/bin/`. A **playit.gg account is _not_ required** — a free anonymous tunnel is auto-provisioned on first use.
- **🟢 Optional one-click account linking**. If you _do_ want to manage tunnels on playit.gg, Conduit captures the claim URL from the agent and opens it in your browser — no copy-pasting claim codes.
- **🟢 Bedrock cross-play**. Toggle one checkbox and Conduit downloads and configures standalone Geyser + Floodgate alongside your server.
- **🟢 Auto server-list entry**. Your tunneled address appears in the Multiplayer screen automatically, and is cleaned up when you stop hosting.
- **🟢 Manage running servers**. A "Manage Conduit Servers" button appears on the Multiplayer screen while a tunnel is live — copy IPs, open the admin panel, or shut everything down from there.
- **🟢 Smart render-distance locking**. The render / simulation distance sliders in **Video Settings** are disabled while hosting so the server and every client stay in sync; change them from the admin panel.
- **🟢 Dedicated-server safe**. The jar does nothing on dedicated servers — the common entrypoint logs its presence and returns; the client code is never loaded.

## The Admin Panel

The in-game admin panel has **five tabs**, all reachable from pause menu → Host This World or from the Manage Conduit Servers button:

| Tab | What it does |
|---|---|
| **Players** | Whitelist on/off, add-by-name input, per-player **Kick / Ban / Op / Deop**. |
| **World**   | Cycle **difficulty** & default **game mode**, toggle **PvP / flight / force-gamemode / spawn NPCs / animals / monsters / advancements / command blocks**, save world now. |
| **Settings** | Render & simulation-distance sliders, **spawn-protection**, **idle timeout**, **MOTD** editor, `/say` broadcast. |
| **Console** | **New in v1.0.0** &mdash; live, interleaved log from the playit agent + Geyser + Conduit itself, **with an input box** that runs anything you type as a server command (with `/cmd` syntax and `↑/↓` history). Prefix a line with `playit ` to forward it to the agent's stdin instead. |
| **Network** | Public Java / Bedrock addresses with copy-to-clipboard, account status, **Link / Unlink playit.gg**, **Show secret path**, **Reset playit agent**. |

### Interactive Console

The Console tab is a proper, scrollable terminal rather than a static log tail:

- **Live merged log** from every subsystem, colour-coded by source.
- **Input field** at the bottom — `↑/↓` to recall recent commands, **Enter** to send, **PgUp / PgDn / mousewheel** to scroll through history.
- Anything you type runs on the **integrated server** exactly as if an operator typed it (`/op`, `/gamemode`, `/tp`, custom datapack commands — everything works).
- Prefix a line with `playit ` to forward the rest of it to the running agent's stdin.
- **Clear**, **Jump to end**, and **Copy logs → clipboard** buttons underneath.

### playit.gg Controls (verified against agent v0.17.1)

The Network tab exposes every safe agent subcommand:

| UI action | Underlying call |
|---|---|
| Link playit.gg Account | Runs the agent interactively, watches for the `playit.gg/claim/<code>` URL and opens it in your browser. Secret is auto-persisted when the web page is accepted. |
| Unlink | Clears the stored secret & tunnel id from Conduit's config and stops the agent. |
| Show secret path | `playit secret-path` — useful for backing up the local secret file. |
| Reset playit agent | `playit reset` — wipes local state; Conduit then re-provisions on the next host. |
| Guest provisioning | Done automatically on first host when no secret exists — the agent is launched, the secret it self-generates is read from disk and cached in `conduit.json`. |

> Note: the old `secret generate` / `secret new` subcommands were removed by playit in v0.17; Conduit now uses the supported flow.

## Requirements

| Component     | Version                                 |
|:--------------|:----------------------------------------|
| Minecraft     | **26.1.2** (Tiny Takeover)              |
| Fabric Loader | **0.18.6** or newer                     |
| Fabric API    | **0.145.4+26.1.2** or newer             |
| Java          | **25** or newer                         |

The project builds against Mojang's official 26.1 mappings (no Yarn, no `remapJar`) using **Gradle 9.4.1** and **Fabric Loom 1.16**.

## Quick Start

### Building

A zero-admin Python build script handles everything, including bootstrapping a JDK if needed:

```bash
python3 scripts/build.py
```

On first run the script will:

1. Look for a JDK 25+ on `$PATH` / `$JAVA_HOME`.
2. If none is found, download [Eclipse Temurin](https://adoptium.net/) JDK 25 into `.conduit-build/jdk/` (no elevated privileges required, no system-wide install).
3. Invoke the Gradle wrapper to produce `build/libs/conduit-1.0.0.jar`.

Other useful invocations:

```bash
python3 scripts/build.py clean            # gradle clean
python3 scripts/build.py --jdk-only        # bootstrap the JDK without building
python3 scripts/build.py --force-bootstrap # force a fresh JDK download
```

If you prefer to drive Gradle directly:

```bash
python3 scripts/build.py --jdk-only
export JAVA_HOME="$(pwd)/.conduit-build/jdk/$(ls .conduit-build/jdk)"
./gradlew build
```

### Installing

Drop the built `conduit-1.0.0.jar` into your `.minecraft/mods/` folder alongside **Fabric API** (`fabric-api-0.145.4+26.1.2.jar`).

### Running in a dev environment

```bash
./gradlew runClient   # launches a 26.1.2 dev client with Conduit installed
./gradlew runServer   # launches a dedicated server (Conduit no-ops there)
```

## Runtime Layout

Conduit keeps everything self-contained under a single directory in your game folder:

```
<.minecraft>/conduit/
├── conduit.json                  # local config (playit secret, tunnel id, defaults)
├── bin/
│   └── playit[.exe]              # downloaded agent binary (v0.17.1)
├── playit-data/                  # PLAYIT_CONFIG_HOME (managed by the agent)
└── geyser/                       # only exists if you ever enable cross-play
    ├── Geyser-Standalone.jar
    ├── floodgate-standalone.jar
    ├── config.yml                # regenerated each start; safe to edit
    └── key.pem                   # generated by Floodgate on first run
```

Nothing is written outside this folder.

## Project Structure

```
src/
├── main/java/com/goattech/conduit/
│   ├── ConduitMod.java                # common (both-sides) entrypoint
│   ├── config/ConduitConfig.java       # on-disk JSON config
│   ├── util/ConsoleLog.java            # shared in-memory log buffer
│   ├── util/Downloader.java            # HttpClient-based file downloader
│   ├── util/PlatformBinary.java        # per-OS playit asset name resolver
│   ├── playit/PlayitAgentManager.java  # agent subprocess + CLI commands
│   ├── playit/PlayitTunnel.java        # tunnel record
│   └── geyser/GeyserManager.java       # Geyser + Floodgate subprocess
└── client/java/com/goattech/conduit/
    ├── client/
    │   ├── ConduitClient.java          # client init, screen hooks
    │   ├── ConduitController.java      # start/stop hosting orchestration
    │   ├── ConduitSessionHolder.java   # hosting session state
    │   └── screen/                     # HostWorld, AdminPanel, ManageServers
    └── server/
        ├── ServerBridge.java           # integrated-server adapter + console exec
        └── ServerEntryManager.java     # Multiplayer server list injection
```

## Platform Support

| Platform      | Status                                                                 |
|:--------------|:-----------------------------------------------------------------------|
| **Linux**     | Full support — `playit-linux-amd64` / `aarch64` / `armv7` auto-downloaded. |
| **Windows**   | Full support — `playit-windows-x86_64.exe` auto-downloaded.            |
| **macOS**     | Partial — install `playit` via Homebrew (`brew install playit`) first. |
| **Dedicated** | The jar is safe to include; it no-ops on the server side.              |

## Troubleshooting

**"Timed out waiting for playit tunnel to come up."**
Open the **Console** tab of the admin panel — every line of agent output is there, colour-coded. Most commonly: your machine is blocking outbound UDP to playit's relays (corporate network), or you disabled IPv4 somewhere. Re-link your account or try `Reset playit agent` from the Network tab.

**"No Temurin JDK 25 build for your platform."**
Install JDK 25 manually from [Adoptium](https://adoptium.net/) and either set `$JAVA_HOME` or drop it on `$PATH`, then rerun `python3 scripts/build.py`.

**Geyser doesn't confirm startup.**
If the agent + Java server are fine, toggle off cross-play, start hosting, then re-toggle it — standalone Geyser takes ~5-15 s on slower machines. Logs are in the Console tab under the `geyser` tag.

## License

[MIT](LICENSE) — do what you like with it.

---

*Made by [GoatTech Industries](https://github.com/GoatTech-42). Issues & PRs welcome.*
