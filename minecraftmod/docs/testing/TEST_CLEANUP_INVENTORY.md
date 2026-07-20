# 测试清理清单（2026-06）

按模块与处置类型标注本轮清理结论。计数为 `*_test.clj` 文件数（Clojure 自动发现）。

## 汇总

| 模块 | 清理前 | 清理后 | 净变化 |
|------|--------|--------|--------|
| `ac` | 231 | 232 | +1（契约/发现/reducer 补测，删 4 config + 2 占位 deftest，+2 新契约文件） |
| `mcmod` | 36 | 36 | 0（修 runner） |
| `forge target` | 22 | 22 | 0（删 1 过时 deftest，修 2 文件） |
| `fabric target` | 2 | 0 | -2（未接线，删除） |

## `ac` — 删除

| 路径 | 原因 |
|------|------|
| `content/ability/electromaster/thunder_clap_config_test.clj` | 与 `skill_specs_test` / `thunder_clap_test` 重复 shape |
| `content/ability/electromaster/railgun_config_test.clj` | 迁入 `railgun_behavior_test` |
| `content/ability/electromaster/mine_detect_config_test.clj` | 迁入 `mine_detect_test` |
| `content/ability/electromaster/thunder_bolt_config_test.clj` | 迁入 `thunder_bolt_test` |
| `storm_wing_test.clj` 末尾 3 个 `(is true)` deftest | 假绿占位 |

## `ac` — 合并（FX / config）

| 处置 | 范围 |
|------|------|
| **合并** | 35 个 `*_fx_test.clj` 中仅做 `init!` / `fn?` / 静态注册的 `init-registers-*` deftest → `fx_channel_registration_contract_test.clj` |
| **保留** | 各 `*_fx_test.clj` 中 owner routing、state clear、cadence、plan building 等行为测试 |
| **合并** | electromaster config shape → `skill_specs_test.clj` + 各技能 `*_test.clj` 行为断言 |

## `ac` — 补强

| 新增/修改 | 说明 |
|-----------|------|
| `discovery/core_test.clj` | `normalize-provider`、`provider-sort-key`、`base-family`、`fx-namespace?` |
| `discovery/scanner_test.clj` | provider 分组与 priority 排序（mock scanner） |
| `registry/content_namespaces_test.clj` | load plan phase 契约 |
| `content/ability/reducer_backed_skill_state_test.clj` | reducer 写路径样例（`:mag-movement`、`:directed-blastwave`） |
| `discovery/core.clj` | `base-family` 索引修正（`(nth parts 5)`） |

## `ac` — 保留

- 其余 ~190+ 平台无关单测：ability 域、wireless、terminal、energy、gui、integration 等（无批量删除）。
- `architecture_guard_test`：少量契约断言；test 源码旧 token 扫描不强 fail（按计划）。

## `mcmod` — 修复

| 路径 | 处置 |
|------|------|
| `test_runner.clj` | 删除重复 `ns` / `-main` |
| 其余 35 个 `*_test.clj` | **保留** |

## `forge target`

| 处置 | 文件/任务 |
|------|-----------|
| **保留** | 22 个 Clojure 测试 namespace |
| **修复** | `config/bridge_smoke_test.clj`（私有 `*registered-configs*`） |
| **修复** | `adapter/network_test.clj`（对齐 4 参 except-local API；删过时 sync-payloads 重复） |
| **接入门禁（手动）** | `verifyForgeClojureUnitTests` → `:platform:runForgeClojureTests` |
| **未接入** | `verifyForgeTesting`（避免与 GameTest 同批扩大失败面） |

## `fabric target`

| 处置 | 说明 |
|------|------|
| **删除** | `src/test/clojure` 下 2 个未接线测试 |
| **保留** | 模块 include；compile/datagen 烟雾在 `verifyFabricBaseline` / `verifyPlatformSmoke` |
| **未接入** | Clojure test runner（见 `IMPLEMENTATION_SCOPE.md`） |

## 根目录 / 构建产物

| 项 | 处置 |
|----|------|
| `test-out.txt` | 删除；`.gitignore` 增加 `/test-out.txt` |

## 门禁（Gradle / 文档）

| 项 | 处置 |
|----|------|
| `verifyCurrentPlatforms` vs smoke | 文档区分 `verifyCurrentPlatformsWithSmoke` |
| coverage ratchet 脚本 | 文档标明未接线，改用手动基线 |
| `verifyCleanupResidueGuards` | 不再依赖 `auditAbilityReducerGenericCommands` |
| `verifyForgeTesting` | 去掉重复 `verifyArchitectureBoundaries`（由 baseline 覆盖） |
