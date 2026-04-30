# Testing Implementation Scope (Current Iteration)

## In Scope
- Platform-neutral unit testing in `ac`, `mcmod`, and optional `api`.
- Forge `GameTest` integration and verification pipeline.
- Testability refactors that preserve behavior.

## Out of Scope (This Iteration)
- Enabling `fabric-1.20.1` in `settings.gradle`.
- Adding Fabric run tasks or Fabric GameTest execution.

## Fabric Extension Hooks (Reserved)
- Keep shared behavior contracts platform-neutral (inputs, expected outputs, invariants).
- Keep platform binding logic isolated in platform adapters.
- Mirror Forge test names when Fabric implementation is added later.

## Testability Refactor Rule
Refactors are allowed when existing structure blocks reliable tests, but each refactor must satisfy all checks below:
1. Business behavior stays unchanged for existing scenarios.
2. New/updated tests prove the same outcomes before and after refactor.
3. No architecture boundary break: `ac` and `mcmod` remain free of `net.minecraft.*`.

## Verification Entry Points
- `gradlew unitTestCompile`
- `gradlew verifyForgeBaseline`
- `gradlew runForgeGameTests`
- `gradlew verifyForgeTesting`

## Current Runtime Blocker
- Forge GameTest task is wired and executable, but runtime world bootstrap currently fails before tests run because datapack registry contains an invalid configured feature reference:
	- `my_mod:worldgen/configured_feature/phase_liquid_pool.json`
	- Error: unknown feature key `my_mod:phase_liquid_pool`
- `--safeMode` is already enabled for the GameTest run config, but this still loads the mod's built-in datapack (`pack main`), so the registry error must be fixed at data/feature registration level.
- This is an existing content/data issue, not a test harness issue. Unit compile and Forge compile verification remain green.
