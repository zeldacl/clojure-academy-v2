# Meltdowner Parity and Acceptance Matrix

This file is the executable tracking artifact for Phase 0.

## Legend
- Status: `done`, `partial`, `blocked`.
- Evidence: implementation and test locations that prove current behavior.
- Gap type: `logic`, `interaction`, `fx`, `data`, `infra`.

## Matrix

| ID | Skill/Subsystem | Gap Type | Requirement | Status | Evidence | Notes |
|---|---|---|---|---|---|---|
| MD-001 | meltdowner main | logic | End context for insufficient charge and all terminal branches (`up`, `abort`, `cost-fail`). | done | `ac/src/main/clojure/cn/li/ac/content/ability/meltdowner/meltdowner.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_test.clj` | Prevent leaked runtime contexts. |
| MD-002 | meltdowner main | logic | Reflection shot must support both absolute and delta look-vector payloads. | done | `ac/src/main/clojure/cn/li/ac/content/ability/meltdowner/meltdowner.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_test.clj` | Added normalized look vector handling. |
| MD-003 | electron-bomb | logic | Delayed settlement order and cleanup correctness. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/electron_bomb_test.clj`, `ac/src/test/clojure/cn/li/ac/ability/server/service/delayed_projectiles_test.clj` | Includes post-settlement cleanup assertions. |
| MD-004 | scatter-bomb | logic | Hold lifecycle, anti-AFK branch, delayed release ordering. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/scatter_bomb_test.clj`, `ac/src/test/clojure/cn/li/ac/ability/server/service/delayed_projectiles_test.clj` | Covers down/tick/up path and ordering. |
| MD-005 | electron-missile | logic | Max-hold termination, state cleanup, hit path behavior. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/electron_missile_test.clj` | Added max-hold terminal coverage. |
| MD-006 | light-shield | interaction | Front-cone absorb and abort side effects. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/light_shield_test.clj` | Includes short slowness-on-abort assertion. |
| MD-007 | ray-barrage | logic | Beam-hit reward and miss no-reward boundaries. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/ray_barrage_test.clj` | Validates exp behavior at hit/no-hit boundaries. |
| MD-008 | mine-ray basic/expert/luck | logic | Wrapper config delegation and luck break/drop behavior. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/mine_ray_basic_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/mine_ray_expert_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/mine_ray_luck_test.clj` | Confirms wrapper routing and luck-specific branch. |
| MD-009 | cross-skill (projectiles + rad-intensify) | logic | Electron bomb/missile hit must install mark that amplifies later runtime damage. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/projectile_mark_integration_test.clj` | Verifies integration via `process-damage!`. |
| MD-010 | FX contracts | fx | Topic registration and payload routing for meltdowner family. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_fx_owner_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/*_fx_test.clj` | Contract-level channels and payload routing are covered across the family. |
| MD-011 | FX concurrency | fx | Multi-owner isolation and runtime isolation. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_fx_owner_test.clj` | Covers owner-key state separation across all meltdowner-family FX modules and level-runtime isolation checks. |
| MD-015 | FX timing cadence | fx | Queue lifetime/tick cadence should decay deterministically and clear plan output at expiry. | done | `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/electron_missile_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/scatter_bomb_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/ray_barrage_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/meltdowner_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/jet_engine_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/light_shield_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/mine_ray_fx_test.clj`, `ac/src/test/clojure/cn/li/ac/content/ability/meltdowner/electron_bomb_fx_test.clj` | Strict cadence assertions now cover the full meltdowner-family FX suite. |
| MD-012 | config/data parity | data | Skill-config defaults and explicit deviations tracked against upstream baselines. | done | `ac/src/main/clojure/cn/li/ac/ability/skill_config/meltdowner.clj`, `docs/dev/meltdowner_skill_config_baseline.md`, `docs/dev/meltdowner_config_drift_signoff.md` | Direct upstream Scala/Java citations and explicit drift signoff list are now both present. |
| MD-013 | translation/datagen | data | Stable source-of-truth and reproducible datagen outputs. | done | `mc-1.20.1/src/main/clojure/cn/li/mc1201/datagen/lang_data.clj`, `mc-1.20.1/src/main/clojure/cn/li/mc1201/datagen/provider_manifest.clj`, `forge-1.20.1/src/main/clojure/cn/li/forge1201/registry/content_registration.clj`, `forge-1.20.1/build.gradle`, `ac/build.gradle`, `forge-1.20.1/run-data/logs/latest.log` | Forge `runData` now completes the real datagen provider pass after fixing the registry snapshot lookup and skipping unrelated `checkClojure` gates for `runData` invocations. |
| MD-014 | test execution pipeline | infra | Run full targeted AC tests via `runAcUnitTests`. | done | `ac/build.gradle`, `build/tmp/verify-test-runner-full-final.log` | Infra bootstrap/classpath blocker is fixed and a full AC run reports 230 tests, 0 failures, BUILD SUCCESSFUL. |

## Validation Snapshot
- `unitTestCompile`: passed after latest test additions.
- `verifyArchitectureBoundaries`: previously passed in this implementation slice.
- Full targeted runtime test execution: task runner works end-to-end and latest full evidence is green.
- Forge `runData`: provider run now completes end-to-end (`All providers took`, `HashCache` write finished) after the Forge/AC `checkClojure` skip-on-datagen wiring.

## Remaining Work to Close the Whole Plan
1. No open Phase 0 blockers remain.
