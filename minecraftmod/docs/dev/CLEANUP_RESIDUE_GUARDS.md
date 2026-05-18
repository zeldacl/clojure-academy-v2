# Cleanup Residue Guards

This document records cleanup decisions that are enforced by Gradle verification
tasks. The goal is to remove stale compatibility residue while preserving the
explicit architecture seams that the multi-loader design requires.

## Guarded SSOT rules

- Player UUID strings in `ac` must go through
  `cn.li.ac.ability.util.uuid/player-uuid`.
  - Guard: `verifyAcPlayerUuidSsoT`
  - Only `ac/ability/util/uuid.clj` may call `entity/player-get-uuid` directly.
- Gameplay config provider contract lives in
  `cn.li.mc1201.config.gameplay-bridge`.
  - Guard: `verifyGameplayConfigContractSsoT`
  - Forge/Fabric provider maps must delegate construction to
    `shared-gameplay/make-provider-map`.
  - Removed self-cycling keys must not return:
    `analysis-enabled`, `gen-ores`, `gen-phase-liquid`, `heads-or-tails`.
- Transient build/log captures must stay out of the Git index.
  - Guard: `verifyTransientBuildArtifactsIgnored`
  - Root `build-*.txt`, `compile-*.txt`, root `*.log`, and historical
    `build/` capture files are treated as local evidence, not source or docs.
  - `.gitignore` must retain the matching ignore patterns so future verification
    output is not accidentally committed.
- Active loader main entrypoints use explicit names.
  - Guard: `verifyLoaderEntrypointNames`
  - Forge Java must call `cn.li.forge1201.mod/start-forge-mod!`.
  - Fabric Java must call `cn.li.fabric1201.mod/start-fabric-mod!`.
  - The old generic `mod-init` main-entry name must not return in active
    Forge/Fabric entrypoint code.

## Intentionally retained aggregate APIs

These namespaces are not cleanup targets even though they aggregate split
implementations. They are public/stable entrypoints, not compatibility leftovers:

- `cn.li.mcmod.block.dsl` — user-facing block DSL surface.
- `cn.li.ac.ability.service.player-state` — complete player-state service API.
- `cn.li.ac.ability.server.service.learning` — learning-service aggregate API.
- `cn.li.mc1201.runtime.entity-query-core` — shared runtime entity query entrypoint.

Future refactors may migrate individual call sites to narrower modules, but these
entrypoints should only be removed after references reach zero and a replacement
API is documented.

## Required architecture seams

Do not delete files just because their name contains `bridge`, `adapter`, or
`shim`. The following categories are expected architecture boundaries:

- `mcmod/platform/*` protocols and platform dispatch seams.
- `mc-1.20.1/runtime/*_spi.clj` and shared runtime core modules.
- Forge/Fabric event registration glue and loader lifecycle entrypoints.
- Fabric startup reflection/class-noinit wrappers.
- Forge missing-mapping/world-save compatibility handlers.

Cleanup should target duplicated business logic, dead fallback aliases, and
unreferenced old APIs—not the explicit boundaries between modules/loaders.