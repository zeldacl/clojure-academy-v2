# Cleanup Residue Guards

This document records cleanup decisions that are enforced by Gradle verification
tasks. The goal is to remove stale compatibility residue while preserving the
explicit architecture seams that the multi-loader design requires.

`verifyCleanupResidueGuards` keeps each guard as an independently named task for
actionable failures, but the root build now attaches shared source inputs and a
per-guard pass marker under `build/verification/cleanup-residue-guards/`. When
source files and guard implementation stay unchanged, hot runs can skip the 25+
pure scan guards instead of rereading the same trees.

## Guarded SSOT rules

- Player UUID strings in `ac` must go through
  `cn.li.ac.ability.util.uuid/player-uuid`.
  - Guard: `verifyAcPlayerUuidSsoT`
  - Only `ac/ability/util/uuid.clj` may call `entity/player-get-uuid` directly.
- Gameplay config provider contract lives in
  `cn.li.ac.config.gameplay`.
  - Guard: `verifyGameplayConfigContractSsoT`
  - Forge/Fabric provider maps must delegate construction to
    `gameplay/make-provider-map`.
  - Removed self-cycling keys must not return:
    `analysis-enabled`, `gen-ores`, `gen-phase-liquid`, `heads-or-tails`.
- `mcmod` content SPI must remain content-agnostic.
  - Guard: `verifyMcmodNoAcSpecificBootstrap`
  - AC-owned content bootstrap providers live under `ac`, not `mcmod`.
- AC command action semantics must be content-owned.
  - Guards: `verifyMcmodCommandNoAcAbilityActions`,
    `verifyAcCommandActionManifestExists`
  - `mcmod.command.actions` only provides the generic action registry and base
    platform actions. Ability actions such as learning, leveling, cooldown,
    CP, category, and preset mutations live in `ac.command.actions`.
- `mcmod` runtime policy hooks must not read AC ability state shape or carry AC
  gameplay defaults.
  - Guard: `verifyMcmodHooksNoBusinessStateReads`
  - Runtime activation and reflection radius are provided by AC-installed hooks;
    shared/platform layers must not read `:resource-data/:activated` directly or
    define AC fallback values.
- Shared saved-location storage is a policy-free named world-position store.
  - Guard: `verifyMcmodNoSavedLocationBusinessSurface`
  - AC LocationTeleport owns max count, name length, cross-dimension thresholds,
    UI/open channels, and error semantics. `mcmod`/`mc1201` may preserve the
    storage protocol and NBT key, but must not enforce AC feature policy.
- `mcmod` client/UI bridge surfaces must be generic keyed seams.
  - Guards: `verifyMcmodClientBridgeScreenKeysNeutral`,
    `verifyMcmodRuntimeClientHooksNeutral`
  - `mcmod.client.platform-bridge`, `mcmod.platform.ui`,
    `mcmod.hooks.core`, and shared `mc1201` hosted-screen/item seams must not
    expose AC screen/effect names such as skill tree, preset editor, location
    teleport, terminal GUI, Railgun, Body Intensify, or Current Charging. AC owns
    those keys and registers them into generic screen/widget/effect/state hooks.
- Runtime item handling uses shared event helpers, not a thin compatibility
  adapter namespace.
  - Guard: `verifyRuntimeEventNoThinItemAdapter`
  - `cn.li.mc1201.runtime.event.item-use` owns item-use event semantics.
  - `cn.li.mc1201.runtime.adapter.entity-damage` owns the shared entity-damage
    adapter factory; the old top-level `entity-damage-adapter` namespace must
    not return.
  - `cn.li.mc1201.runtime.adapter.world-effects` owns the shared world-effects
    adapter factory; the old top-level `world-effects-adapter` namespace must
    not return.
- GUI network envelope/codec is shared by `cn.li.mc1201.gui.network.packet`,
  while Forge/Fabric files remain transport-only.
  - Guard: `verifyGuiNetworkTransportBoundaries`
  - Forge GUI network must call packet-base encode/decode helpers directly rather
    than reintroducing local `serialize`/`deserialize` wrappers.
  - Fabric GUI network must use platform-neutral `cn.li.mcmod.config` for mod-id
    and `packet-base/request-map`, `response-map`, and `push-map` for envelopes.
- Migrated GUI helper namespaces live under domain subpackages such as
  `cn.li.mc1201.gui.cgui.*`, `cn.li.mc1201.gui.init.*`,
  `cn.li.mc1201.gui.menu.*`, `cn.li.mc1201.gui.network.*`,
  `cn.li.mc1201.gui.provider.*`, `cn.li.mc1201.gui.registry.*`,
  `cn.li.mc1201.gui.screen.*`, and `cn.li.mc1201.gui.slots.*`; their old
  top-level files must stay deleted.
- Scripted entity hook registration must flow through `cn.li.mc1201.entity.hooks`
  and the `scripted-hook-specs` table in `hook_registry_core.clj`; the old
  per-kind wrappers (`effect_hooks`, `ray_hooks`, `marker_hooks`) must stay
  deleted.
- Block runtime event side-effect helpers live in `cn.li.mcmod.block.events`;
  `cn.li.mcmod.block.dsl` remains the declaration/preset/query aggregate and
  must not define `handle-*` runtime event functions.
- Schema-backed AC block GUIs (`developer`, `imag_fusor`, `phase_gen`,
  `solar_gen`, `wireless_node`, `wireless_matrix`) use
  `cn.li.ac.block.gui.sync` for schema sync/get/apply/close helpers and
  `cn.li.mcmod.gui.spec` for standard GUI spec/slot operation
  grouping instead of direct per-file `schema-runtime/build-*` lifecycle calls
  or repeated `gui-dsl/create-gui-spec` templates.
- Manual-sync complex AC block GUIs such as Metal Former, Ability Interferer,
  Wind Generator, and Energy Converter may keep custom atom update logic, but
  their standard GUI spec construction still goes through
  `cn.li.mcmod.gui.spec` instead of duplicating the nested
  `:registration`/`:lifecycle`/`:sync`/`:operations` map shape.
- Client ability beam/ray visual effects use
  `cn.li.ac.ability.client.effects.beam-ops` for RGB/alpha style composition,
  fading beam/ray op construction, and VecAccel glow-line ribbon helpers. Local
  `beam-ops`/`ray-ops`/`mag-movement-beam-ops` render builders and direct
  `beam-render` usage should not return in the migrated FX files.
- Forge/Fabric datagen setup files use shared
  `cn.li.mc1201.datagen.provider-registration` for provider iteration/logging
  and platform-local `datagen.provider-factory` adapters for Loader API wiring.
  Thin blockstate provider wrappers are deleted; blockstate provider creation
  calls the shared `mc1201.datagen.blockstate-provider-shell` directly from the
  platform factory adapter.
  - Guard: `verifyGuiNamespaceLayout`
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