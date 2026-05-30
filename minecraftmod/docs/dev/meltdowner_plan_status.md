# Meltdowner Plan Status

## Completed
- Main meltdowner service behavior tests added.
- Light Shield and Electron Missile service boundary tests added.
- Scatter Bomb, Ray Barrage, and Mine Ray Basic/Expert/Luck service tests added.
- Delayed projectile tests expanded for electron-bomb and scatter-bomb settlement behavior.
- Cross-skill projectile mark integration tests added (electron-bomb/electron-missile -> rad-intensify damage amplification).
- Phase 0 tracking artifact added: `docs/dev/meltdowner_parity_matrix.md`.
- Skill-config baseline diff artifact added: `docs/dev/meltdowner_skill_config_baseline.md`.
- Upstream AcademyCraft Scala/Java meltdowner sources were pulled and mapped into baseline diff with direct field-level citations.
- Explicit config drift signoff list added: `docs/dev/meltdowner_config_drift_signoff.md`.
- Forge datagen registry bootstrap was repaired: `content_registration.clj` no longer assumes `registered-fluids-source` is an atom/future-backed deref source, and `runData` now skips unrelated `checkClojure` gates.
- Meltdowner-family FX contract and owner/runtime isolation coverage exists across dedicated FX test suites.
- Electron-missile FX now has strict TTL cadence assertions (beam/impact decay and plan expiry).
- Scatter-bomb FX now has deterministic tick cadence assertions (state progression and cleanup).
- Ray-barrage FX now validates alpha fade by TTL decay and deterministic queue expiry.
- Meltdowner FX now validates 10-tick charge-loop sound cadence and perform-ray expiry.
- Jet-engine FX now validates trigger flash fade with TTL and deterministic trigger-state expiry.
- Light-shield FX now validates 5-tick particle cadence and deterministic cleanup.
- Mine-ray FX now validates 8-tick particle cadence and deterministic cleanup.
- Electron-bomb FX now validates 3-tick active particle cadence and beam TTL expiry.
- Core compile gate passed across `ac`, `mcmod`, forge, and fabric modules.
- `verifyArchitectureBoundaries` passed.

## Completed With Known Infra Limitations
- No Phase 0 meltdowner blockers remain. Forge datagen still emits the usual Forge/Loom asset-schema warnings during startup, but provider generation completes.

## Newly Unblocked
- `runAcUnitTests` classpath/bootstrap issue was fixed by ensuring focused `compileTestClojure` always includes `cn.li.ac.test-runner`.
- Focused meltdowner FX suite now runs through `runAcUnitTests -Dac.test.only=...` and passes.

## Remaining
- No meltdowner Phase 0 execution blockers remain.

## Current Assessment
- Phase 3 service-test coverage is substantially complete for the meltdowner skill family.
- FX timing/cadence assertions now cover the full meltdowner-family FX suite.
- Phase 5 fallback validation has a full-suite green evidence run (`runAcUnitTests`: 230 tests, 0 failures).
- Forge datagen now reaches provider completion and hash-cache write end-to-end in the normal `:forge-1.20.1:runData` path.
- The overall Phase 0 meltdowner plan is fully complete.
