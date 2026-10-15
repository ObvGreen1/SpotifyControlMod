# Multi-Version Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the single-target (1.21.11, Yarn) Spotify Control Fabric mod into a Gradle multi-project that builds five distinct JARs — 1.21.10, 1.21.11, 26.1, 26.2, 26.3 — with identical behaviour and a shared `:common` source module.

**Architecture:** One source tree in official Mojang names. Each version project compiles the shared source against its own game jar (1.21.x via `officialMojangMappings()`, 26.x deobfuscated with no mappings). `:common` holds the shared source and resources; version projects consume it as source sets and add only version-specific shims.

**Tech Stack:** Gradle 9.7.1, Fabric Loom 1.18.4, Java 21 (1.21.x) and Java 25 (26.x) toolchains, Fabric API, Mod Menu, Cloth Config (JiJ). The Gradle JVM must be Java 25.

**Spec:** `docs/superpowers/specs/2026-10-14-multiversion-refactor-design.md`

## Global Constraints

- Targets: `1.21.10`, `1.21.11`, `26.1`, `26.2`, `26.3`. JAR path `<version>/build/libs/` named `spotifycontrol-<mod-version>+<minecraft-version>.jar`.
- Mod id `spotifycontrol`, mod version `1.0.1`, group `me.obvgreen`, loader `0.19.5`.
- Gradle wrapper `9.7.1`; Loom `1.18.4`; run Gradle with a Java 25 JVM (pinned via `gradle/gradle-daemon-jvm.properties`, so a plain `./gradlew` works).
- 1.21.x: plugin id `net.fabricmc.fabric-loom-remap`, Java 21 target, `officialMojangMappings()`, `modImplementation`, remap on.
- 26.x: plugin id `net.fabricmc.fabric-loom`, Java 25 target, no mappings, plain `implementation`, no remap.
- ABSOLUTE LOGIC PRESERVATION: renames are mechanical; no feature, handler, config or UI is changed, removed or optimised.
- Keep the `main` + `client` split source sets and both entrypoints.
- Do not commit secrets; do not touch `.minecraft/config/spotifycontrol.json`.

## Review Focus

- **HUD rendering under official names**: `DrawContext`→`GuiGraphics` and the 1.21.9+ `RenderPipelines` API changed; a wrong render call must not silently drop an element.
- **Keybind registration**: `KeyBinding`→`KeyMapping` + `KeyBindingHelper`; the tick polling loop must keep firing on every target.
- **Command tree**: the full `/spotify` Brigadier tree (play, adskip, volume, seek, …) must keep every branch on every target.
- **Config screen**: the 651-line Cloth Config screen must keep every field on 26.x where the API is a major-version bump.
- **`fabric.mod.json` ranges**: a wrong `minecraft` range makes the loader refuse the mod or offer it on the wrong game.

---

### Task 1: Spike — prove the toolchain serves both families

**Files:**
- Create: `spike/` (throwaway, deleted after): `spike/settings.gradle`, `spike/1.21.11/`, `spike/26.3/` minimal Loom projects.
- Test: build output.

**Interfaces:**
- Produces: the chosen Loom plugin id + version, and confirmation that `officialMojangMappings()` (1.21.11) and no-mappings (26.3) both configure, compile and `remapJar` under one build.

- [ ] **Step 1:** Create a throwaway Gradle build with one 1.21.11 project (`officialMojangMappings()`, Java 21) and one 26.3 project (no mappings, Java 25), each a `ClientModInitializer` that references `net.minecraft.client.Minecraft`.
- [ ] **Step 2:** Run the spike build. Record which Loom version works for both (try stable `1.18.x`; if it cannot serve 1.21.11, fall back to a per-family version and note the constraint).
- [ ] **Step 3:** Confirm the 26.3 class behind `Minecraft` matches the names in the downloaded 26.3 client jar; confirm 1.21.11 official names via the Mojang mappings file.
- [ ] **Step 4:** Delete `spike/` and record the chosen Loom id/version in the design doc.

### Task 2: Root workspace

**Files:**
- Modify: `settings.gradle`, `gradle.properties`, root `build.gradle`.
- Create: `common/build.gradle`, `1.21.10/`, `1.21.11/`, `26.1/`, `26.2/`, `26.3/` directories with `gradle.properties`.

**Interfaces:**
- Produces: project `:common` and version projects reachable as `:1.21.10`, `:1.21.11`, `:26.1`, `:26.2`, `:26.3`; a root `allprojects`/`subprojects` config and a `buildAll` aggregate dependsOn each version project's `build`.

- [ ] **Step 1:** `settings.gradle` includes `:common` plus the five version projects; keep the plugin-management repos.
- [ ] **Step 2:** root `gradle.properties` holds shared props (`mod_id`, `mod_version`, `maven_group`, `archives_base_name`, `loader_version`, `loom_version`, `java_21`, `java_25`).
- [ ] **Step 3:** root `build.gradle` applies shared config to subprojects (encoding, JVM args) and defines `buildAll`.
- [ ] **Step 4:** each `<version>/gradle.properties` sets `minecraft_version`, `mappings`, `java_version`, `fabric_api_version`, `modmenu_version`, `cloth_config_version`, `minecraft_range`.
- [ ] **Step 5:** Verify `./gradlew projects` lists all six projects.

### Task 3: Move shared source + resources into `common`

**Files:**
- Move: every file under `src/main/java`, `src/client/java`, `src/main/resources` into `common/src/main/...` (same relative paths).
- Create: `common/src/main/resources/fabric.mod.json` template.

**Interfaces:**
- Consumes: Task 2 project layout.
- Produces: `common/src/main/java`, `common/src/client/java`, `common/src/main/resources` as the shared source roots.

- [ ] **Step 1:** Move the trees with `git mv` to preserve history.
- [ ] **Step 2:** Keep the `fabric.mod.json` entrypoints and asset paths unchanged.
- [ ] **Step 3:** Commit the move alone (no content edits) so later rename diffs stay reviewable.

### Task 4: Wire each version project to the shared source

**Files:**
- Create: `<version>/build.gradle` for all five.
- Modify: `common/build.gradle`.

**Interfaces:**
- Consumes: `common/src/{main,client}/…` (Task 3).
- Produces: version projects compiling shared source; `processResources` expanding `fabric.mod.json`.

- [ ] **Step 1:** version `build.gradle`: apply the family plugin (`net.fabricmc.fabric-loom-remap` for 1.21.x, `net.fabricmc.fabric-loom` for 26.x, both `1.18.4`); `splitEnvironmentSourceSets()`; add `common/src/main/java` to `main`, `common/src/client/java` to `client`, and `common/src/main/resources` to `main.resources`; add own `src` dirs for shims.
- [ ] **Step 2:** dependencies per family (`modImplementation`+`officialMojangMappings()` for 1.21.x; `implementation`+no mappings for 26.x), with Fabric API, Mod Menu (`exclude fabric-api`), Cloth Config (`modApi`+`include`).
- [ ] **Step 3:** `processResources` expands `${version}`, `${minecraft_version}`, `${minecraft_range}`, `${loader_version}`, `${modmenu_range}`, `${cloth_range}`.
- [ ] **Step 4:** `base.archivesName = spotifycontrol`; `version = "${mod_version}+${minecraft_version}"`; Java toolchain from `java_version`.
- [ ] **Step 5:** `./gradlew :1.21.11:properties` resolves and the project configures.

### Task 5: Convert the Minecraft-facing files to official names (baseline 1.21.11)

**Files:**
- Modify (rename only): `common/src/client/java/.../SpotifyControlClient.java`, `command/SpotifyCommand.java`, `config/ModMenuIntegration.java`, `gui/AlbumArtCache.java`, `gui/SpotifyGuiScreen.java`, `gui/SpotifyHudOverlay.java`, `gui/SpotifyLyricsScreen.java`, `gui/hud/{ArtHudElement,CardHudElement,HudPainter,HudRenderContext}.java`, `keybinds/SpotifyKeybinds.java`.

**Interfaces:**
- Consumes: the 1.21.11 Mojang mappings file (`client_mappings` from the version manifest).
- Produces: the shared source in official names, compiling on 1.21.11.

- [ ] **Step 1:** Build a Yarn→official rename table from the 1.21.11 mappings (imports, methods, fields, accessors like `.getInstance()`, `.textRenderer`, `drawText`, `fill`).
- [ ] **Step 2:** Apply the renames mechanically, file by file. No logic changes; only symbol names and import paths.
- [ ] **Step 3:** `./gradlew :1.21.11:build` until green. Fix only genuine signature deltas, keeping behaviour.
- [ ] **Step 4:** Verify the produced jar name/path: `1.21.11/build/libs/spotifycontrol-1.0.1+1.21.11.jar`.

### Task 6: Target 1.21.10

**Files:** `1.21.10/gradle.properties`; shims under `1.21.10/src/…` only if required.

- [ ] **Step 1:** `./gradlew :1.21.10:build`; resolve any 1.21.10-vs-1.21.11 API delta with a shim in `1.21.10/src`, not by changing shared logic.
- [ ] **Step 2:** Confirm `1.21.10/build/libs/spotifycontrol-1.0.1+1.21.10.jar`.

### Task 7: Target 26.3 (deobfuscated)

**Files:** `26.3/` config + shims; possibly new interfaces in `common`.

- [ ] **Step 1:** `./gradlew :26.3:build`; read errors against the 26.3 client jar class listing.
- [ ] **Step 2:** For each breaking API (render pipeline, screen/widget, Fabric API surface, Mod Menu/Cloth), add the smallest version-specific implementation behind a shared interface in `common`; keep feature behaviour identical.
- [ ] **Step 3:** Confirm `26.3/build/libs/spotifycontrol-1.0.1+26.3.jar`.

### Task 8: Target 26.1 and 26.2

- [ ] **Step 1:** `./gradlew :26.1:build` and `:26.2:build`; reuse 26.3's shims where the API matches, add per-version shims where it does not.
- [ ] **Step 2:** Confirm both jars exist.

### Task 9: Full build + naming

- [ ] **Step 1:** `./gradlew clean build` from the root.
- [ ] **Step 2:** Assert five files: `{1.21.10,1.21.11,26.1,26.2,26.3}/build/libs/spotifycontrol-1.0.1+<version>.jar`.
- [ ] **Step 3:** Inspect each jar's `fabric.mod.json` for the correct expanded version + minecraft range.

### Task 10: CI

**Files:** Modify `.github/workflows/build.yml`.

- [ ] **Step 1:** Provision JDK 21 and 25; run the root build.
- [ ] **Step 2:** Upload the five per-version jars as artifacts.

### Task 11: Smoke test

- [ ] **Step 1:** Launch the 1.21.11 jar in a dev client; verify HUD, `/spotify`, keybinds, config screen.
- [ ] **Step 2:** Attempt the same for 26.3 if the runtime is available; otherwise record it as unverified.

## Self-Review

- Spec coverage: structure (T2–T4), shared source (T3), mappings/JAR naming (T4, T9), version-specific handling (T6–T8), yarn→mojmap (T5), build-all (T9), CI (T10), smoke (T11). Ok.
- Type consistency: project names, `archivesName`, and the `fabric.mod.json` placeholders are used identically across tasks.
- Test strategy: this mod has no unit tests; each task's check is a compile/run command with a named expected artifact.
