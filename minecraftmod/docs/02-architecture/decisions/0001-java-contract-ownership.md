# ADR 0001: Java contract ownership for energy and platform bootstrap

## Status

Accepted as documentation-only guidance for the current refactor. No Java source movement is performed by this ADR.

## Context

During the AC structural refactor, two cross-module Java contract duplications were identified:

- `api/src/main/java/cn/li/acapi/energy/IEnergyCapable.java`
- `mcmod/src/main/java/cn/li/mcmod/energy/IEnergyCapable.java`
- `api/src/main/java/cn/li/acapi/platform/spi/platform target bootstrap.java`
- `mcmod/src/main/java/cn/li/mcmod/platform/spi/platform target bootstrap.java`

The active runtime wiring currently depends on the `mcmod` contracts in several places, while `api` is intended to be the stable public contract surface. Moving or deleting these interfaces without a migration path would risk binary/source churn across Forge/Fabric glue, Clojure `deftype` implementations, and any external consumers.

## Decision

1. Keep both contract locations for now.
2. Treat `api` as the intended long-term published contract surface.
3. Treat `mcmod` as the active runtime contract surface until all current call sites are inventoried and a compatibility bridge is available.
4. Do not change Java package ownership opportunistically inside unrelated AC Clojure refactors.
5. Any future consolidation must be done as its own migration with explicit compatibility tests.

## Migration rule for future cleanup

A future Java contract consolidation may proceed only after all of the following are true:

- All `IEnergyCapable` implementors and callers are inventoried across `ac`, `mcmod`, `mc-1.20.1`, Forge, and Fabric modules.
- A chosen canonical package is documented.
- Existing public-ish packages get deprecated bridge interfaces/classes for at least one transition window.
- Compile checks pass for every module that consumes the interface.
- `verifyArchitectureBoundaries` still passes.

Recommended final direction:

- `api` owns stable externally visible interfaces/SPI.
- `mcmod` owns Clojure/runtime adapters and may depend on `api`, not duplicate external-facing contract names indefinitely.
- Platform modules depend on the stable API or the smallest platform-specific adapter, not AC implementation details.

## Consequences

Positive:

- Avoids high-risk cross-module Java churn during AC internal refactoring.
- Makes the current duplication explicit instead of accidental.
- Provides a clear gate for future cleanup.

Trade-offs:

- Duplicate Java contract names remain temporarily.
- New developers must check this ADR before moving `IEnergyCapable` or `platform target bootstrap`.

## Current validation expectation

For AC-only structural refactors, run:

```powershell
cmd.exe /c "gradlew.bat :ac:compileClojure"
cmd.exe /c "gradlew.bat runAcUnitTests"
cmd.exe /c "gradlew.bat verifyArchitectureBoundaries"
```

For future Java contract consolidation, also run module Java/Clojure compile tasks for affected modules and both platform baselines.
