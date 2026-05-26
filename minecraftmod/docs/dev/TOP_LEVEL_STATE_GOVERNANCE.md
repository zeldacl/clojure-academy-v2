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
- `:server-session-id`
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

The task writes `build/reports/top-level-state/audit.md`. It scans main Clojure
sources for top-level `def`/`defonce` forms that contain `atom`,
`atom-registry`, or `delay`, then cross-references
`docs/dev/top-level-mutable-state-whitelist.tsv`.

This task is intentionally report-only during the migration. Once P0/P1 runtime
state has moved behind explicit owners, convert unclassified or forbidden S2
entries into a failing guard and wire it into `verifyCleanupResidueGuards`.

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

## Current migration focus

P0 starts with player state, dispatcher route/context state, sync scheduler, and
GUI container owner keys. P1 follows with world/wireless identity,
`vec_reflection`, and energy runtime state. Client FX/UI/RPC singleton cleanup is
P2, while static registry freeze/dedupe and recipe reload policy are P3.
