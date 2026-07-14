# Top-level Mutable State Governance

This document governs Clojure top-level mutable vars such as `defonce` atoms,
`atom-registry` instances, and lazy singleton holders. The goal is not to ban
all top-level vars; the goal is to make runtime ownership explicit so integrated
server, LAN, and multi-player sessions do not share state accidentally.

## Classification

- **S0 pure** — constants, protocols, records, functions, and immutable data.
  These are outside the audit unless a mutable holder is introduced.
- **S1 static registry / SPI / init guard / cache** — may remain top-level when
  it is process-static and not player/world/session data. It must have a clear
  owner, idempotent registration or duplicate policy, and a reset hook for tests
  when mutation is expected.
- **S2 runtime state** — must be moved behind an explicit owner such as side,
  server session, world, player, container, screen, effect instance, or request.
- **S3 compatibility alias / re-export** — remove when references reach zero.
  This project does not require compatibility with old internal APIs for this
  refactor.

## Owner key rule

New or migrated runtime state must declare the narrowest available owner. Use a
composite owner rather than a namespace singleton when any of these dimensions
can differ at runtime:

- `:logical-side`
- `:server-session-id` / `:client-session-id`（canonical；能力/GUI 上层用 `mcmod.runtime.owner`，勿直读 `:session-id`）
- `:world-id`
- `:player-uuid`
- `:container-id`
- `:screen-id`
- `:effect-instance-id`
- `:request-id`

If a value is intentionally process-static, document why it is not affected by
multi-player or integrated-server side sharing.

## Audit command

Run the report-only audit with:

```powershell
cmd /c .\gradlew.bat auditTopLevelMutableState
```

To fail the build when multiplayer owner governance drifts, run:

```powershell
cmd /c .\gradlew.bat verifyMultiplayerOwnerGuards
```

The task writes `build/reports/top-level-state/audit.md`. It scans main Clojure
sources for top-level `def`/`defonce` forms that contain `atom`,
`atom-registry`, or `delay`, then cross-references
`docs/dev/top-level-mutable-state-whitelist.tsv`.

`auditTopLevelMutableState` remains the human-readable report, while
`verifyMultiplayerOwnerGuards` turns it into a failing guard by requiring zero
unclassified findings and zero stale whitelist entries. The guard is wired into
`verifyCleanupResidueGuards`.

## Whitelist format

`docs/dev/top-level-mutable-state-whitelist.tsv` is tab-separated:

```text
relative-path<TAB>symbol<TAB>classification<TAB>owner<TAB>notes
```

Rules:

1. Each mutable top-level var is listed by exact relative path and symbol.
2. Classification must be one of the S1/S2/S3 families above.
3. Owner must say who owns cleanup or why it is static.
4. Notes should state the migration phase or retained duplicate/freeze policy.
5. Do not whitelist by namespace prefix, glob, or vague symbol names.

**Removed (2026-06 scripted logic refactor):** runtime `*tile-logic-registry-state*`, `*container-registry-state*`, `*capability-registry-state*`, and entity `*hook-registry-state*` / `*hook-metadata-state*` are deleted — no whitelist entries should remain for them. Bundle maps `{tile-id → TileLogicBundle}` are registration-phase `let` locals, not top-level state.

## `mcmod.platform` SPI globals (S1)

Platform adapter namespaces under `mcmod/src/main/clojure/cn/li/mcmod/platform/`
hold **process-static** loader installs only:

- **Private runtime vars** — `^:private ^:dynamic *runtime*`, `*world-ops*`,
  `*be-ops*`, or factory fns. Installed only via public `install-*!` in the
  owning namespace (called from `mc1201` / Forge / Fabric bootstrap).
- **Public operation wrappers** — `...*` suffix functions used by `ac` and
  `mcmod` content. They return nil/false/empty when unavailable; factories
  still fail-fast when required at init.
- **Static registries** — capability type registry, UI widget factories,
  command/integration/energy hook runtimes. Duplicate registration with
  different values fails; tests may call documented `reset-*!` helpers.

**Content rule:** `ac/src/main` must not reference `cn.li.mcmod.platform.*/\*…\*`
implementation vars or `resolve` platform impl symbols. Use wrappers and
`available?` / `call-with-runtime` in tests.

Loader registry: Forge uses `forge1201.registry.state`; Fabric uses
`fabric1201.registry.fabric-dispatch`. The removed `mcmod.platform.registry`
multimethod path must not be reintroduced.

**GUI block-state broadcast (required platform multimethod):** Server→client tile
GUI sync dispatches via `cn.li.mcmod.gui.sync-api/broadcast-gui-state!*`
on `platform-dispatch/current-platform-version` (same pattern as
`mcmod.network.client/send-request`). Each loader registers `defmethod` in
`forge1201.gui.block-sync-broadcast` / `fabric1201.gui.block-sync-broadcast` and
calls `assert-gui-broadcast-dispatch!` from GUI network bootstrap. Do not use
optional `available?` guards or `install-platform-broadcast!` for this path.

## Current migration focus

P0 starts with player state, dispatcher route/context state, sync scheduler, and
GUI container owner keys. P1 follows with world/wireless identity,
`vec_reflection`, and energy runtime state. Client FX/UI/RPC singleton cleanup is
P2, while static registry freeze/dedupe and recipe reload policy are P3.

## Exactly-once primitives (P0)

`cn.li.mcmod.runtime.install` provides the two sanctioned exactly-once entry
points — `framework-once!` (Framework-lifecycle-scoped; flag resets on
`with-fresh-framework` reinjection) and `process-once!` (genuine JVM-process
one-shot side effects only) — plus `install-root!` for SPI holders that no
longer need `^:dynamic` + `Object` lock + `alter-var-root`. New init-guard or
SPI-injection code must go through one of these; do not add another
`defonce-guard`-shaped macro, `^:dynamic` boolean guard, or ad hoc lock.

`docs/dev/dynamic-var-binding-audit.md` cross-references every current
`^:dynamic` top-level def against real `(binding [...])` usage — a keep/kill
list consumed by later migration phases that remove `^:dynamic` where it is
pure decoration (no var is ever actually bound).
