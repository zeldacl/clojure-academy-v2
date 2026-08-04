# Add a Loader or Minecraft Version

This guide describes the current target-catalog architecture.

Adding a real supported target is a product decision. If the goal is only to prove that the architecture can expand, add a synthetic fixture in tests instead of adding real loader dependencies, source trees, docs promises, or release artifacts.

## Principles

- Use `platform-catalog.json` as the only target catalog.
- Add source components explicitly; do not infer behavior from a target id string.
- Do not generate loader/version combinations automatically.
- Put Minecraft API differences under `platform-src/minecraft/*`.
- Put loader lifecycle, metadata, and event bindings under `platform-src/loader/*`.
- Keep `api`, `mcmod`, and `ac` free of Minecraft/loader APIs.
- Do not add platform SPI, ServiceLoader platform bootstrap files, pass-through namespaces, or task aliases.

## Adding a Minecraft version component

1. Create a new version component under `platform-src/minecraft/mc-<version>/` (e.g. `mc-1.21.1`).
2. Move only version-specific Minecraft API differences into that component (`cn.li.mc*`).
3. Keep version-agnostic shared Minecraft glue in `platform-src/minecraft/base/` (`cn.li.mcbase`); do not put version forks there.
4. Implement `cn.li.mcver.*` under the new component so it matches the existing seam set (contracts shaped like 1.21.1; older versions downgrade). See `docs/dev/MC_VERSION_SEAM.md` and run `verifyVersionSeamParity`.
5. Add the component to the target catalog only for targets that are intentionally supported.
6. Add the component source and target tests; AOT namespaces are derived automatically from its Clojure sources.
7. Run the relevant single-target compile/test command and `verifyCurrentPlatforms`.

## Adding a loader component

1. Create a new loader component under `platform-src/loader/<loader>-<mc-version>/` (versioned directory, matching catalog ids such as `forge-1.20.1` / `neoforge-1.21.1`).
2. Keep only framework-required entrypoints, metadata, client/datagen entrypoints, and loader event bindings there.
3. Route lifecycle to the shared platform bootstrap directly; do not add a ServiceLoader bootstrap SPI.
4. Declare dependencies and capabilities through the target catalog/build logic.
5. Add tests for capability ownership and source component selection before adding a real target.

### NeoForge note

NeoForge is a first-class peer loader for supported targets (today: `neoforge-1.21.1`).

- Put NeoForge sources under `platform-src/loader/neoforge-<mc-version>/`.
- Use `META-INF/neoforge.mods.toml` (not Forge `mods.toml`).
- Pair with the matching `minecraft-<version>` + `minecraft-base` source components in `platform-catalog.json`.
- Do not treat NeoForge as a Forge subdirectory or as a synthetic-only fixture when adding a real supported target.

## Adding a real target

1. Add an explicit entry in `platform-catalog.json`.
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

1. `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure `scripts/target-gradle.ps1 <target-id>``
2. Datagen task for that target, when applicable.
3. Target artifact task, when applicable.
4. `cmd /c .\gradlew.bat verifyCurrentPlatforms`

Do not use old per-loader module tasks; those modules must not exist.
