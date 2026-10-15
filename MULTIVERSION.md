# TASK: Refactor Fabric Mod into Multi-Version Project

I have an existing Fabric mod designed for Minecraft 1.21.11. I need you to convert this repository into a multi-project Gradle workspace that generates distinct compiled JARs for the following target Minecraft versions:
- 1.21.10
- 1.21.11
- 26.1
- 26.2
- 26.3

---

## CRITICAL REQUIREMENTS & CONSTRAINTS
1. ABSOLUTE LOGIC PRESERVATION: Do NOT modify, optimize, remove, or refactor any existing features, algorithms, event handlers, packet/NMS manipulations, UI elements, Mixin targets, or configuration logic unless explicitly required to compile against breaking changes in target mappings.
2. INDIVIDUAL JARS: Every target version sub-project must output its own distinct compiled JAR file under `<version-folder>/build/libs/` named using the pattern: `<mod-id>-<mod-version>+<minecraft-version>.jar`.
3. CLEAN COMMON SOURCE: Extract all version-agnostic code, assets, Mixins, and configuration definitions into a shared `:common` sub-module.

---

## PROJECT ARCHITECTURE REQUIREMENTS

### 1. Root Workspace Structure
Restructure the repository layout into:
├── build.gradle
├── settings.gradle
├── gradle.properties
├── common/
│   ├── build.gradle
│   └── src/main/ (Shared source code, assets, mixins, fabric.mod.json template)
├── 1.21.10/
│   ├── build.gradle
│   └── gradle.properties (Version-specific Minecraft & Yarn properties)
├── 1.21.11/
│   ├── build.gradle
│   └── gradle.properties
├── 26.1/
│   ├── build.gradle
│   └── gradle.properties
├── 26.2/
│   ├── build.gradle
│   └── gradle.properties
└── 26.3/
    ├── build.gradle
    └── gradle.properties

### 2. Multi-Project Gradle Configuration
- Configure `settings.gradle` to include `:common` and all target version sub-folders (`:1.21.10`, `:1.21.11`, `:26.1`, `:26.2`, `:26.3`).
- Set up parent/sub-project Gradle tasks so running `./gradlew build` in the root directory compiles and builds all target JARs sequentially.
- Pass common configuration properties (Mod ID, Mod Version, Fabric Loader Version) down from the root build script or shared `gradle.properties`.

### 3. Sub-Project Mapping & Source Linking
- Configure each version sub-project `build.gradle` to depend on `project(':common')` for both main source and compile tasks.
- Match each version folder with its appropriate official Yarn mappings and Fabric Loom setup for that specific Minecraft version.
- Enable `fabric.mod.json` dynamic property expansion during `processResources` to accurately reflect each sub-project's target Minecraft version range (e.g., `">=1.21.10 <1.22"` or specific build numbers).

### 4. Handling Version-Specific Breaking Changes
- If a target version introduces breaking method signatures, renamed classes, or changed Yarn mappings (e.g., between 1.21.x and 26.x):
  - Abstract the affected API behind a shared interface in `:common`.
  - Provide version-specific implementations within the respective sub-project folder (`<version>/src/main/java/...`).
  - Do NOT modify the core business logic or features of the mod when creating these wrappers.

---

## EXECUTION STEPS FOR THE AGENT
1. Analyze the existing source code, assets, Mixins, and dependencies in the repository.
2. Create the `:common` directory and move shared code/resources into `common/src/main/`.
3. Create sub-directories for `1.21.10`, `1.21.11`, `26.1`, `26.2`, and `26.3`.
4. Generate/update `settings.gradle`, root `build.gradle`, and version-specific `build.gradle` / `gradle.properties` files.
5. Fix any mapping/compilation errors across all version projects while strictly maintaining functional parity.
6. Run `./gradlew build` to verify all 5 distinct JARs compile without errors.

Please begin by analyzing the current project structure and generating the updated `settings.gradle` and build scripts.