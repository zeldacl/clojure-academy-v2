# Testing Implementation Scope (Current Iteration)

## In Scope
- Platform-neutral unit testing in `ac`, `mcmod`, and optional `api`.
- Forge `GameTest` integration and verification pipeline.
- Testability refactors that preserve behavior.

## Architecture Red Lines For Tests
- `ac` owns gameplay/domain rules and gameplay contract assertions.
- `mcmod` only tests platform-neutral foundation contracts (DSL/metadata/parsing/network shapes), no gameplay semantics.
- `forge-1.20.1` keeps `GameTest` as a thin runtime adapter check (registration, datapack loading, minimal world-level execution), no duplicated gameplay logic.

## Out of Scope (This Iteration)
- Fabric Clojure unit test runner / `fabric-1.20.1` `*_test.clj` 自动发现执行（模块已 include，仅 compile/datagen 烟雾维护）。
- Fabric GameTest execution.

## Fabric Extension Hooks (Reserved)
- Keep shared behavior contracts platform-neutral (inputs, expected outputs, invariants).
- Keep platform binding logic isolated in platform adapters.
- Mirror Forge test names when Fabric implementation is added later.

## Testability Refactor Rule
Refactors are allowed when existing structure blocks reliable tests, but each refactor must satisfy all checks below:
1. Business behavior stays unchanged for existing scenarios.
2. New/updated tests prove the same outcomes before and after refactor.
3. No architecture boundary break: `ac` and `mcmod` remain free of `net.minecraft.*`.

## Cleanup Notes (2026-06)

- Inventory: [TEST_CLEANUP_INVENTORY.md](TEST_CLEANUP_INVENTORY.md)
- Report: [TEST_CLEANUP_REPORT.md](TEST_CLEANUP_REPORT.md)

## Verification Entry Points
- `gradlew unitTestCompile`
- `gradlew quickUnitTests`（`ac` + `mcmod`）
- `gradlew verifyLocalPrGate`（平台矩阵 + `quickUnitTests`）
- `gradlew verifyForgeBaseline`
- `gradlew verifyForgeClojureUnitTests`（Forge/shared Clojure 单测，手动）
- `gradlew runForgeGameTests`
- `gradlew validateForgeGameTestLog`
- `gradlew verifyForgeTesting`

## Recommended Execution Order
1. `gradlew unitTestCompile`
2. `gradlew verifyForgeBaseline`
3. `gradlew verifyForgeTesting`
4. Split run when debugging:
   - `gradlew runForgeGameTests`
   - `gradlew validateForgeGameTestLog`

## Failure Triage
- **`ac` unit test failure**
  - Treat as gameplay/domain regression first.
  - Fix `ac` contracts before looking at Forge `GameTest` logs.
- **`mcmod` unit test failure**
  - Treat as platform-neutral foundation regression.
  - Keep fixes gameplay-agnostic; do not move gameplay rules into `mcmod`.
- **Compile failure (`compileClojure` / `checkClojure`)**
  - First narrow with namespace flags:
    - `-PcompileNsOnly=ns.a,ns.b`
    - `-PcheckNsOnly=ns.a,ns.b`
    - `-PcheckNsFile=<path>`
  - Then use:
    - `:forge-1.20.1:bisectCompileClojure`
    - `:forge-1.20.1:bisectCheckClojure`
- **GameTest startup failure (before any tests execute)**
  - Treat as runtime bootstrap/data issue, not gameplay contract issue.
  - Prioritize datapack/registry consistency checks.
- **GameTest log validation failure**
  - Use `validateForgeGameTestLog` output to classify startup/runtime fatal errors vs thin adapter assertions.
  - Fix environment/bootstrap errors first, then test-level failures.

## Current Runtime Blocker
- Forge GameTest task is wired and executable, but runtime world bootstrap currently fails before tests run because datapack registry contains an invalid configured feature reference:
	- `my_mod:worldgen/configured_feature/phase_liquid_pool.json`
	- Error: unknown feature key `my_mod:phase_liquid_pool`
- `--safeMode` is already enabled for the GameTest run config, but this still loads the mod's built-in datapack (`pack main`), so the registry error must be fixed at data/feature registration level.
- This is an existing content/data issue, not a test harness issue. Unit compile and Forge compile verification remain green.
