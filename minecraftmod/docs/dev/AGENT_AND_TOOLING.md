# Agent and Tooling Rules

This repository uses a single `:platform` Gradle project. Loader/version behavior is selected only by `platform-catalog.json` plus `-PplatformTarget=<target-id>`.

## Current layout

- `api`: Java API and external contracts.
- `mcmod`: loader-neutral runtime framework.
- `ac`: content layer.
- `platform-src/common`: shared platform glue.
- `platform-src/minecraft/mc-1.20.1`: Minecraft API shared across supported Minecraft versions.
- `platform-src/minecraft/mc-1.20.1`: Minecraft 1.20.1-specific API adaptation.
- `platform-src/loader/forge`: Forge loader entrypoints, metadata, events, and loader bindings.
- `platform-src/loader/fabric`: Fabric loader entrypoints, metadata, events, and loader bindings.
- `platform-target`: the single Gradle platform project.

Use only the current target catalog architecture. Do not add root platform modules, platform SPI, task aliases, pass-through namespaces, or dual-track implementations.

## Common commands

- Architecture gate: `cmd /c .\gradlew.bat verifyCurrentPlatforms`
- Forge compile: `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1"`
- Fabric compile: `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1"`
- Forge client: `cmd /c .\gradlew.bat :platform:runClient "-PplatformTarget=forge-1.20.1"`
- Fabric client: `cmd /c .\gradlew.bat :platform:runClient "-PplatformTarget=fabric-1.20.1"`

On Windows, keep the `-PplatformTarget=...` argument quoted.

## Architecture rules

- Never infer loader or Minecraft version by parsing the target id string; read the catalog model.
- Do not auto-generate a loader/version cartesian product. Every supported target must be explicitly declared.
- Minecraft components must not enumerate Forge/Fabric. Loader lifecycle belongs only to loader components.
- `ac` and `mcmod` must not depend on Minecraft, Forge, Fabric, or other loader APIs.
- Java entrypoints, client/datagen entrypoints, and loader metadata are allowed only because external frameworks require them. Internal pass-through namespaces are not allowed.
- Datagen output belongs under `platform-target/build/generated/datagen/<target-id>/`; do not write generated output back to source directories.
- Do not add a real new-loader target, dependency, source tree, documentation promise, or release artifact unless the project explicitly decides to support it. Use synthetic catalog/sourceSet/capability fixtures to validate extensibility.

## Required gate

`verifyCurrentPlatforms` aggregates:

- `verifyNoLegacyArchitecture`
- `verifyNoThinForwarders`
- `verifyNoDuplicateCapabilities`
- `verifyNoUnusedNamespaces`
- `verifyNoTargetHardcoding`
- `verifyRepositoryHygiene`
