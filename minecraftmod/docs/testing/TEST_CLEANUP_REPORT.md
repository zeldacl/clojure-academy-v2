# 测试与门禁清理报告（2026-06）

本轮按「无效项 → 冗余合并 → 重构补测 → 门禁校正 → 回归」执行。详细文件级清单见 [TEST_CLEANUP_INVENTORY.md](TEST_CLEANUP_INVENTORY.md)。

## 删除的测试

- 4 个 electromaster `*_config_test.clj`（shape 迁入 `skill_specs_test` 与技能行为测试）
- `fabric target` 下 2 个未接线 Clojure 测试
- `storm_wing_test` 中 3 个 `(is true)` 占位 deftest
- 35 个 `*_fx_test.clj` 中的 `init-registers-*` 占位 deftest（合并为 1 个契约测试）
- `forge adapter/network_test` 中过时的 `sync-message-payloads` deftest（`network_core_test` 已覆盖）
- 根目录 `test-out.txt`

## 合并的测试

- **FX 注册**：`fx_channel_registration_contract_test.clj`（35 个 `init!` 符号）
- **技能 config shape**：`skill_specs_test.clj`（含 `:current-charging` hold-channel）；`current_charging_test` 去重
- **config 行为**：QTE/cost → `railgun_behavior_test`；tunables → `mine_detect_test`、`thunder_bolt_test`、`thunder_clap_test`

## 新增有效覆盖

- `discovery/core_test.clj`、`discovery/scanner_test.clj`
- `registry/content_namespaces_test.clj`
- `content/ability/reducer_backed_skill_state_test.clj`（`:mag-movement`、`:directed-blastwave` reducer 写路径）
- `discovery/core.clj`：`base-family` 行为修正

## 保留但降级 / 手动的门禁

| 门禁 | 状态 |
|------|------|
| `verifyForgeClojureUnitTests` | **手动**；未纳入 `verifyLocalPrGate` / `verifyForgeTesting` |
| Fabric Clojure 单测 | **out of scope**；模块已 include，无 test runner |
| coverage ratchet shell | **未接线**；对照 `ac/coverage-baseline.txt`、`mcmod/coverage-baseline.txt` |
| `verifyForgeTesting` / GameTest | **未改默认 CI**；既有 datapack blocker 仍在 `IMPLEMENTATION_SCOPE.md` |

## 已跑验证（本轮）

| 命令 | 结果 |
|------|------|
| `quickUnitTests` | 通过（mcmod 135 + ac 935 tests） |
| `verifyLocalPrGate` | 通过 |
| `verifyForgeClojureUnitTests` | 通过（22 ns, 67 tests） |

## 未接入项及原因

- **Forge Clojure → `verifyForgeTesting`**：避免与 GameTest 同批扩大失败面；待稳定后再接。
- **Fabric `*_test.clj`**：无 test sourceSet/runner；仅维护 compile/datagen。
- **`scripts/*_coverage_ratchet.sh`**：本轮改文档，未补 CI 脚本。
- **`verifyAbilityArchitectureStrict` / `verifyForgeTesting`**：计划标为视改动可选；未纳入必跑清单（architecture 由 `verifyCleanupResidueGuards` 子集与 `architecture_guard_test` 覆盖）。

## 代码修复（非测试）

- `mcmod/test_runner.clj`：单一定义
- Forge `bridge_smoke_test`、`adapter/network_test`：对齐当前 API
