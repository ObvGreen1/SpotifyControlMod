# Contributing

Thanks for wanting to improve Spotify Control. This is a client-side Fabric mod for
Minecraft 1.21.11, built with Java 21.

## Before you start

- Open an issue first for anything larger than a bug fix or a small cleanup, so the design can be
  agreed on before code is written.
- Never commit credentials. The mod uses OAuth PKCE precisely so that no client secret exists;
  do not reintroduce one, and do not commit `config/spotifycontrol.json` (it holds tokens).

## Development setup

1. Install **JDK 21** (the build pins a Java 21 toolchain, so a newer JDK running Gradle is fine as
   long as a 21 toolchain is resolvable).
2. Clone and build:

   ```bash
   git clone https://github.com/ObvGreen/SpotifyControlMod.git
   cd SpotifyControlMod
   ./gradlew build          # Windows: .\gradlew.bat build
   ```

3. Run a dev client:

   ```bash
   ./gradlew runClient
   ```

   The Fabric Loom `run/` directory is a real `.minecraft` instance; its `config/` folder will hold
   your Spotify tokens and they stay on your machine.

4. If your machine needs an HTTP proxy for Maven/Gradle downloads, put it in your **user-level**
   `~/.gradle/gradle.properties`, not in the project's `gradle.properties`:

   ```properties
   systemProp.http.proxyHost=127.0.0.1
   systemProp.http.proxyPort=10808
   systemProp.https.proxyHost=127.0.0.1
   systemProp.https.proxyPort=10808
   systemProp.http.nonProxyHosts=localhost|127.0.0.1
   ```

## Code style

`.editorconfig` is authoritative: UTF-8, LF, 4-space indent, 120-column soft limit, final newline.
Match the surrounding code:

- Threading rule: **no network, disk, or DBus/PowerShell work on the render or tick thread.** Use
  the existing daemon executors in `SpotifyService`, and hop back to the client thread with
  `MinecraftClient.execute(...)` before touching GL or `DynamicTexture`.
- Immutable snapshots (`TrackInfo`, `LyricsSnapshot`) are replaced wholesale; do not mutate shared
  state in place.
- Every failure path degrades to a logged no-op and an `Offline`/`Not Playing` HUD state. The mod
  must never crash the client because Spotify is missing, closed, or logged out.
- Keep user-visible strings in `src/main/resources/assets/spotifycontrol/lang/en_us.json` and
  reference them as translation keys; only English is shipped today.

## Submitting changes

1. Branch off `main`: `git checkout -b fix/hud-anchor-offset`.
2. Keep commits focused; a short imperative subject line is enough.
3. Verify locally before pushing:
   - `./gradlew build` succeeds.
   - `./gradlew runClient` starts and the HUD renders.
   - Both the Web API path and the native fallback path were exercised, or state which one you
     could not test (no Windows machine, no Spotify Premium account, ...).
4. Update `README.md` and `CHANGELOG.md` when behaviour or configuration changes.
5. Open a pull request describing what changed, why, and how you verified it.

## Reporting bugs

Include: mod version, Minecraft version, Fabric Loader and Fabric API versions, OS, whether you were
on the Web API path or the native fallback, and the relevant log excerpt from
`run/logs/latest.log` (or `.minecraft/logs/latest.log`). Redact tokens and your Client ID.
