# Agent and Tooling Rules

This repository uses a single `:platform` Gradle project. Loader/version behavior is selected only by `platform-catalog.json` plus `scripts/target-gradle.ps1 <target-id>`.

## Current layout

- `api`: Java API and external contracts.
- `mcmod`: loader-neutral runtime framework.
- `ac`: content layer.
- `platform-src/minecraft/base`: version-agnostic Minecraft glue (`cn.li.mcbase.*`).
- `platform-src/minecraft/mc-1.20.1`: Minecraft 1.20.1 adaptation (`cn.li.mc1201.*`) plus version seam (`cn.li.mcver.*`).
- `platform-src/minecraft/mc-1.21.1`: Minecraft 1.21.1 adaptation (`cn.li.mc1211.*`) plus version seam (`cn.li.mcver.*`).
- `platform-src/loader/forge-1.20.1`: Forge loader entrypoints, metadata, events, and loader bindings.
- `platform-src/loader/fabric-1.20.1`: Fabric loader entrypoints, metadata, events, and loader bindings.
- `platform-src/loader/neoforge-1.21.1`: NeoForge loader entrypoints, metadata, events, and loader bindings.
- `platform`: the single Gradle platform project.

Use only the current target catalog architecture. Do not add root platform modules, platform SPI, task aliases, pass-through namespaces, or dual-track implementations.

Version-seam rules: [MC_VERSION_SEAM.md](MC_VERSION_SEAM.md). Loader hook capability matrix: [loader-hook-support.properties](loader-hook-support.properties).

## Common commands

- Architecture gate: `cmd /c .\gradlew.bat verifyCurrentPlatforms`
- Install clj-kondo binary: `cmd /c .\gradlew.bat downloadCljKondo`
- Clojure lint gate (must run **per target** so that target's source roots are scanned): `.\scripts\target-gradle.ps1 <target-id> lintClojureNative`
- Neutral-layer API gate: `cmd /c .\gradlew.bat verifyNeutralClojureNoMinecraftApis` (blocks `net.minecraft.*` / Forge / Fabric / NeoForge refs in `ac`/`mcmod`; clj-kondo hook also errors on `:import` there). Note: `:import` is **not** a Clojure reflection warning — Reflection Guard only catches untyped interop.
- LVT strip (packaging): always on (`stripAotLvt` / `stripPlatformOutputLvt` / `stripShadowJarLvt`) for Loom 1.13 tiny-remapper. AOT also sets `-Dclojure.compile.elide-meta=[:doc]` (keeps `:file`/`:line`).
- Forge compile: `.\scripts\target-gradle.ps1 forge-1.20.1 :platform:compileClojure`
- Fabric compile: `.\scripts\target-gradle.ps1 fabric-1.20.1 :platform:compileClojure`
- NeoForge compile: `.\scripts\target-gradle.ps1 neoforge-1.21.1 :platform:compileClojure`
- Forge / Fabric / NeoForge lint:
  - `.\scripts\target-gradle.ps1 forge-1.20.1 lintClojureNative`
  - `.\scripts\target-gradle.ps1 fabric-1.20.1 lintClojureNative`
  - `.\scripts\target-gradle.ps1 neoforge-1.21.1 lintClojureNative`

Use `scripts/target-gradle.ps1`, `scripts/target-gradle.cmd`, or `scripts/target-gradle.sh`; these are the only platform build entry points and select the target runtime from the catalog. Loom / clojurephant / shadow plugin versions come from the target's `buildProfile` in `platform-catalog.json` via `settings.gradle` `pluginManagement`.

## Architecture rules

- Never infer loader or Minecraft version by parsing the target id string; read the catalog model.
- Do not auto-generate a loader/version cartesian product. Every supported target must be explicitly declared.
- Minecraft components must not enumerate Forge/Fabric/NeoForge. Loader lifecycle belongs only to loader components.
- `ac` and `mcmod` must not depend on Minecraft, Forge, Fabric, NeoForge, or other loader APIs.
- Java entrypoints, client/datagen entrypoints, and loader metadata are allowed only because external frameworks require them. Internal pass-through namespaces are not allowed.
- Datagen output belongs under `build/targets/<target-id>/platform/generated/datagen/<target-id>/`; do not write generated output back to source directories.
- Do not add a real new-loader target, dependency, source tree, documentation promise, or release artifact unless the project explicitly decides to support it. Use synthetic catalog/sourceSet/capability fixtures to validate extensibility.

## Required gate

`verifyCurrentPlatforms` aggregates:

- `verifyBuildProfiles`
- `verifyNoLegacyArchitecture`
- `verifyNoThinForwarders`
- `verifyNoDuplicateCapabilities`
- `verifyNoTargetHardcoding`
- `verifyRepositoryHygiene`
- `verifyVersionSeamParity`
- `verifyNeutralClojureNoMinecraftApis` (and related catalog/entrypoint checks as configured)
