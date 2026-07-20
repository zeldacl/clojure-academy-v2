# Add a Loader or Minecraft Version

This guide describes the current target-catalog architecture.

Adding a real supported target is a product decision. If the goal is only to prove that the architecture can expand, add a synthetic fixture in tests instead of adding real loader dependencies, source trees, docs promises, or release artifacts.

## Principles

- Use `platform-targets.json` as the only target catalog.
- Add source components explicitly; do not infer behavior from a target id string.
- Do not generate loader/version combinations automatically.
- Put Minecraft API differences under `platform-src/minecraft/*`.
- Put loader lifecycle, metadata, and event bindings under `platform-src/loader/*`.
- Keep `api`, `mcmod`, and `ac` free of Minecraft/loader APIs.
- Do not add platform SPI, ServiceLoader platform bootstrap files, pass-through namespaces, or task aliases.

## Adding a Minecraft version component

1. Create a new version component under `platform-src/minecraft/version/<mc-component>/`.
2. Move only version-specific Minecraft API differences into that component.
3. Keep shared Minecraft code in `platform-src/minecraft/base`.
4. Add the component to the target catalog only for targets that are intentionally supported.
5. Update AOT manifests and target tests in the same change.
6. Run the relevant single-target compile/test command and `verifyCurrentPlatforms`.

## Adding a loader component

1. Create a new loader component under `platform-src/loader/<loader>/`.
2. Keep only framework-required entrypoints, metadata, client/datagen entrypoints, and loader event bindings there.
3. Route lifecycle to the shared platform bootstrap directly; do not add a ServiceLoader bootstrap SPI.
4. Declare dependencies and capabilities through the target catalog/build logic.
5. Add tests for capability ownership and source component selection before adding a real target.

## Adding a real target

1. Add an explicit entry in `platform-targets.json`.
2. Declare:
   - `loader`
   - `minecraftVersion`
   - `javaVersion`
   - source components
   - test components
   - capabilities
   - capability owners
   - dependencies
   - artifact metadata
3. Do not rely on parsing the target id.
4. Do not add publishing or docs commitments until compile, tests, datagen, AOT, and artifact checks pass for that target.

## Synthetic extensibility fixture

Use a synthetic fixture when validating architecture only:

1. Add fixture catalog data in tests, not a real production target.
2. Assert source component resolution, AOT inputs, capability ownership, and artifact naming.
3. Assert that no real loader dependency or source directory was created.
4. Keep the fixture out of release tasks and documentation support tables.

## Verification

For each real supported target:

1. `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=<target-id>"`
2. Datagen task for that target, when applicable.
3. Target artifact task, when applicable.
4. `cmd /c .\gradlew.bat verifyCurrentPlatforms`

Do not use old per-loader module tasks; those modules must not exist.
