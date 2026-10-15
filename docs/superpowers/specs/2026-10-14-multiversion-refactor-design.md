# Spotify Control — Multi-Version Refactor (Design)

Date: 2026-10-14
Status: awaiting approval

## Intent and why

The mod currently targets exactly one Minecraft version (1.21.11, Yarn). We want a
Gradle multi-project workspace that also produces distinct JARs for 1.21.10, 26.1,
26.2 and 26.3, without changing what the mod does.

## What the reconnaissance found (evidence, not assumption)

Verified against the Fabric meta API, the Mojang version manifest, the Maven
metadata for Yarn/intermediary/Fabric API, and the actual 26.3 client jar:

| Target   | Yarn mappings      | Mojang mappings | Java | Fabric API | Mod Menu | Cloth Config (fabric) |
|----------|--------------------|-----------------|------|------------|----------|-----------------------|
| 1.21.10  | `1.21.10+build.3`  | available       | 21   | yes        | 16.0.1   | `20.0.149`            |
| 1.21.11  | `1.21.11+build.6`  | available       | 21   | yes        | 17.0.1   | `21.11.153`           |
| 26.1     | **none**           | **none**        | 25   | yes        | 18.0.2   | `26.1.154`            |
| 26.2     | **none**           | **none**        | 25   | yes        | 20.0.3   | `26.2.155`            |
| 26.3     | **none**           | **none**        | 25   | yes        | 21.0.0   | `26.3.159`            |

Reason: **Minecraft 26.x ships deobfuscated.** The 26.3 `client.jar` contains real
names (`net.minecraft.client.Minecraft`, `net.minecraft.client.KeyMapping`,
`net.minecraft.client.Camera`, …). There is nothing to remap, so Yarn has published
no 26.x builds and Mojang publishes no 26.x mapping files. The upstream
`fabric-example-mod` template for 26.3 confirms the new world: the `mappings`
dependency is gone, dependencies use plain `implementation`, the plugin is
`net.fabricmc.fabric-loom`, and it targets Java 25.

Consequence: the spec's "match each folder with its official Yarn mappings" is
impossible for 26.1–26.3. The 26.x sources must use **Mojang official names**.

## Decision made

**Unified Mojang official names across all five targets** (chosen by the human).
1.21.10/1.21.11 use `loom.officialMojangMappings()`; 26.x use no mappings at all
(already named). One source namespace, one codebase.

Yarn → Mojang renames that affect the 12 Minecraft-facing files include, but are
not limited to:

- `net.minecraft.client.MinecraftClient` → `net.minecraft.client.Minecraft`
- `net.minecraft.client.gui.DrawContext` → `net.minecraft.client.gui.GuiGraphics`
- `net.minecraft.client.option.KeyBinding` → `net.minecraft.client.KeyMapping`
- `net.minecraft.client.util.InputUtil` → `com.mojang.blaze3d.platform.InputConstants`
- `net.minecraft.util.Identifier` → `net.minecraft.resources.ResourceLocation`
- `net.minecraft.text.Text` → `net.minecraft.network.chat.Component`
- `net.minecraft.util.Formatting` → `net.minecraft.ChatFormatting`
- `net.minecraft.client.font.TextRenderer` → `net.minecraft.client.gui.Font`
- `…gui.screen.Screen` → `…gui.screens.Screen`, `ButtonWidget` → `Button`, etc.
- `client.texture.NativeImage` → `client.renderer.texture.NativeImage`

## Goals

- Five targets: `1.21.10`, `1.21.11`, `26.1`, `26.2`, `26.3`.
- Each emits `<version>/build/libs/spotifycontrol-<mod-version>+<minecraft-version>.jar`.
- A root `./gradlew build` builds the common module and all five JARs.
- Identical features, logic, config and behaviour on every target.
- A shared `:common` module holds version-agnostic source, assets and the
  `fabric.mod.json` template.

## Non-goals

- No new features, no optimisation, no behaviour changes.
- No other mod loader (NeoForge/Forge) and no cross-loader abstraction.
- No publishing/release automation beyond keeping `build`.

## Anti-goals (would make this a failure even if it compiles)

- Silently dropping or stubbing a feature (HUD element, command, keybind, ad-skip,
  lyrics, native fallback) to get a target to compile.
- Different user-visible behaviour between targets.
- Overwriting or losing the existing 1.21.11 behaviour.

## Constraints

- **Toolchains:** 1.21.x needs Java 21; 26.x needs Java 25. Both JDKs are present
  locally (`ms-21.0.11`, `ms-25.0.4.1`). Gradle is 9.5.
- **One Loom version per build.** Gradle resolves a plugin to a single version
  across all subprojects, so the same Loom must serve both the Yarn-era 1.21.x
  setup and the deobfuscated 26.x setup. The exact version is a spike item.
- **Network + time.** Five full Minecraft dev environments must be provisioned
  (each MC jar is ~40 MB plus libraries). This is a large first build.
- **Platform:** Windows, PowerShell.

## Architecture

```
settings.gradle              include ':common' and the five version projects
gradle.properties            shared: mod id/version/name, group, loader, loom, Java versions
build.gradle                 root: shared config pushed into subprojects
docs/...                     this document
common/
  build.gradle
  src/main/java              version-agnostic Java (all source under unified naming)
  src/main/resources         assets, lang, fabric.mod.json (templated)
1.21.10/  build.gradle, gradle.properties
1.21.11/  build.gradle, gradle.properties
26.1/     build.gradle, gradle.properties
26.2/     build.gradle, gradle.properties
26.3/     build.gradle, gradle.properties
```

### How `:common` is shared (important)

Java bytecode links against the *exact* signatures of the Minecraft classes it
calls, and those signatures differ between 1.21.11 and 26.3. A single prebuilt
`:common` JAR therefore cannot be linked by all five targets. The version-agnostic
code is shared as **source**, and each version project compiles it against its own
game jar. Concretely, each version project's `build.gradle` adds `common`'s source
and resource directories to its own source sets and references `project(':common')`.

This is the standard multi-version pattern; it keeps one copy of the code while
letting every target compile against the API it actually has. `:common` does not
run Loom on its own.

### Per-family build configuration

- **1.21.10 / 1.21.11:** plugin id `net.fabricmc.fabric-loom-remap`;
  `officialMojangMappings()`; `modImplementation` for Fabric API / Mod Menu / Cloth
  Config; `release = 21`; Loom remapping on.
- **26.1 / 26.2 / 26.3:** plugin id `net.fabricmc.fabric-loom`; no `mappings` line;
  plain `implementation`; `release = 25`; deobfuscated (no remap).
- Both plugin ids are the same Loom artifact (`net.fabricmc:fabric-loom:1.18.4`), so
  they coexist in one build with a single version.

### Spike results (settled, verified by a throwaway two-project build)

- **Gradle 9.7.1** is required (Loom 1.18.4 declares plugin api-version 9.7.0; the
  old wrapper 9.5.0 fails). The official Fabric template also pins 9.7.1.
- **The Gradle JVM must be Java 25** — Loom 1.18 uses Java 25's FFM API and refuses
  to load on a Java 21 JVM ("Dependency requires at least JVM runtime version 25").
  Compilation still targets Java 21 for 1.21.x via the toolchain.
  This is pinned in `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=25`), so a
  plain `./gradlew <task>` selects a Java 25 daemon automatically no matter what the
  shell's default `JAVA_HOME` is; Gradle finds the locally installed JDK 25 (e.g. `~/.jdks`).
- **Loom 1.18.4** with plugin ids `net.fabricmc.fabric-loom-remap` (1.21.x) and
  `net.fabricmc.fabric-loom` (26.x) builds both families in one build.
- `loom.officialMojangMappings()` **works** for 1.21.11; a 26.3 project with **no
  mappings line** configures and compiles.
- `net.minecraft.client.Minecraft.getInstance()` resolves unchanged under both, so
  the unified-official-names decision holds.
- JARs land at `<version>/build/libs/<archivesName>-<version>+<mc>.jar` as required.


### Third-party mods in the deobfuscated 26.x world (verified)

Downloading and scanning the published artifacts settles how 26.x dependencies are
consumed:

- `modmenu-21.0.0.jar` references `net/minecraft/client/Minecraft` (47×),
  `GuiGraphics` (55×), `KeyMapping` (7×); **zero** intermediary (`class_`) and zero
  Yarn (`MinecraftClient`).
- `cloth-config-fabric-26.3.159.jar` is the same: official names, zero intermediary.

So 26.x Fabric API, Mod Menu and Cloth Config are published against official names
and pulled with plain `implementation`; Loom does no remapping. Cloth Config can
still be Jar-in-Jar'd (loader feature, independent of mappings) — JiJ is kept and
re-verified in the spike.

### Settled calls (builder decisions)

- **Keep the split source sets.** `common` keeps `main` + `client` (via
  `splitEnvironmentSourceSets()`), and both entrypoints stay in `fabric.mod.json`.
  The near-empty `main` loader entrypoint is preserved.
- **Cloth Config and Mod Menu ship for all five targets** (table above); the config
  screen stays as-is per version, with only mapping/API renames applied.
- **Consume 26.x third-party mods with `implementation`** (verified above).


### `fabric.mod.json` expansion

`processResources` expands per project: `${version}` (mod version),
`${minecraft_version}` and `${minecraft_range}` (e.g. `>=1.21.10 <1.21.12`,
`>=26.1 <26.4`), `${loader_version}`, and the Mod Menu / Cloth Config suggestion
ranges.

### JAR naming

`base.archivesName = spotifycontrol`; project `version = "${mod_version}+${minecraft_version}"`
so Loom emits `spotifycontrol-1.0.1+1.21.11.jar`, `…+26.3.jar`, etc., under each
version folder's `build/libs`.

## Handling version-specific breakage

Genuine API differences (rendering pipeline, screen/widget API, keybind API,
Fabric API surface, Mod Menu / Cloth Config) are resolved inside the relevant
version project or with minimal `common` shims — logic and features are preserved.
Anything that cannot be expressed in one shared source file gets a small
version-specific implementation behind a shared interface in `:common`.

## Risks / open items (to settle with spikes, not guesses)

1. Exact API deltas between 1.21.11 and 26.1 for the 12 Minecraft-facing files.
2. Whether `splitEnvironmentSourceSets()` behaves identically in the 26.x setup.
3. First-build size/time (five MC dev environments, ~40 MB each plus libraries).
4. Whether Jar-in-Jar of Cloth Config still works under the 26.x no-remap setup.

Resolved by the spike: Loom/plugin/Gradle/JVM toolchain (see above). Resolved during
design review: Cloth Config / Mod Menu availability (all five targets) and the 26.x
third-party artifact model (official names, plain `implementation`).

## Acceptance and CI (settled calls — human opted to proceed)

- **Definition of done:** all five JARs build via a single root `./gradlew build`,
  plus a best-effort launch/smoke test on whichever targets can run in this
  environment (1.21.x certainly; 26.x if the runtime is available). Behaviour
  preservation is enforced by making the rename mechanical and reviewable rather
  than re-deriving logic.
- **CI in scope:** `.github/workflows/build.yml` is updated to provision JDK 21
  and JDK 25, run the root build, and upload the five per-version JARs.

## What is left to the builder

- Final Loom version(s) after the spike.
- The mechanical Yarn→Mojang rename of the 12 files.
- Exact source-set wiring for sharing `common`.
- Per-version resolution of compile errors, preserving behaviour.
