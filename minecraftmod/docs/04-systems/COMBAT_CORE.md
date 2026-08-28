# Combat Core 维护手册

> 语言本体（描述符/表达式/作用域/composite 展开）的完整规格见 [NODE_LANGUAGE.md](NODE_LANGUAGE.md)——本文只讲 combat-core 如何使用这套语言、模块边界、以及排障。

## 系统职责

`combat-core` 加载 `node-core` 语言之上的**战斗词汇表**（原语 + 组合层 composite），执行全部技能。它是纯数据驱动、平台中立的执行引擎：技能是编译期校验过的 EDN 节点树，由树遍历解释器执行，只产出中立的结果计划（`:actions`/`:events`/`:vfx-signals`/`:query-results`）——从不直接改动 Minecraft 状态；真正落地世界效果、伤害、位移是通过 `mcmod` 端口 + 已注册的 host capability 完成的，AC 只负责组装与自己领域（技能学习/资源/成就）的注入。

## 模块边界

- `node-core/**`：语言本体，不依赖 Minecraft，也不依赖 combat-core/vfx-core/ac。
- `combat-core/src/main/clojure/cn/li/combat/final_engine.clj`：唯一 final graph 执行器；只处理编译后的节点树，产出中立 actions/events/VFX 信号。
- `combat-core/src/main/clojure/cn/li/combat/final_compiler.clj`：唯一 final graph 编译器；展开后的 composite 必须通过 descriptor、作用域和引用检查。
- `combat-core/src/main/clojure/cn/li/combat/recipe.clj`：仅保留为非技能内容的文档读取工具，不是技能执行入口。
- `combat-core/src/main/clojure/cn/li/combat/interception.clj`：伤害拦截决策边界（含反应管线），platform 事实采集（raycast/entity-motion）与 `:entity/damage` capability 调用都在这里直接完成，不经过 AC 转发。
- `combat-core/src/main/clojure/cn/li/combat/skill_runtime.clj`：技能激活编排——tunable 具体化、VFX 信号规范化、结果组装。
- `combat-core/src/main/clojure/cn/li/combat/platform.clj`：向 mcmod 注册的 host query/action capability 表。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：唯一同时组合 node/combat/vfx/presentation 值的中立边界；AC、BC、CC 都通过它组装 catalog、result 和 frame。
- `ac/src/main/clojure/cn/li/ac/ability/final_catalog.clj`：AC 侧内容加载器，读取 AC manifest 并将 combat/vfx/node 值交给 ability-runtime 组合。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_runtime.clj`：AC composition root，注入 AC 自己领域的端口（resource/progression/achievement/saved-location 等），提交 combat-core 产出的 `:owner-patch`/`:session-patch`。

## 词汇表分层（详见 NODE_LANGUAGE.md §1）

```
:layer :primitive   Clojure 函数，可调 mcmod        components.clj 的 register-primitive!
:layer :composite          纯 EDN composite                ac/src/main/resources/ac/combat/composites/*.edn
:layer :ability       纯 EDN 技能文档                  ac/src/main/resources/ac/combat/abilities/*.edn
```

一个组件是否该是原语，判定标准：**一个原语只做一件事且必须触碰宿主**；一旦它内部组合了"查询 → 循环 → 施加"这类多步骤，就必须是 composite。当前词汇表里哪些属于哪一层、审计依据，见迁移计划 R2 章节（`docs/04-systems/NODE_LANGUAGE.md` §12 有旧→新的概念映射）。

## 运行时流程

1. `combat_catalog/initialize!` 加载四份 manifest（`combat/manifest.edn`、`combat/composites.edn`、`vfx/manifest.edn`、`vfx/composites.edn`），逐文档编译，失败的文档进 `:errors`、不影响其余文档启动。
2. 客户端 CombatIntent 驱动 AC final runtime：具体化 tunable → composite 展开 → `final_compiler/compile-program` → `final_engine/execute!`，产出 `{:commands :events :vfx :status}`。
3. `combat_runtime.clj`（AC）把 `:actions` 里的 `:owner-patch`/`:session-patch` 提交进玩家存档；`:vfx-signals` 作为中立 Intent 交给 ability-runtime 路由，再由 AC 的 VFX adapter 广播。
4. 任意入站伤害（技能命中或 vanilla 击中）都先经过 `combat-core/interception.clj` 的 `intercept!`——这是唯一的伤害决策边界，platform 事实（world-id/目标位置/攻击者朝向）与反应管线（`reactions.clj`，逐步并入同一 VM，见 NODE_LANGUAGE.md §10）都在这一步完成，结果只返回给调用方提交，不在中途落地。

## 扩展点

- 新增底层原语：在 `components.clj` 用 `register-primitive!` 登记完整 v3 描述符（`:inputs`/`:outputs`/`:effects`/`:impl`），先确认它确实"只做一件事且必须触碰宿主"——否则应该是新增组合层 composite 而不是新增原语。
- 新增组合层语义：在 `ac/src/main/resources/ac/combat/composites/*.edn` 加一个 `:layer :composite` composite 文档，登记进 `composites.edn`。**禁止**给它写任何 Clojure 实现。
- 新增技能：在 `ac/src/main/resources/ac/combat/abilities/*.edn` 加文档，登记进 `manifest.edn`。技能文档顶层可以用 source 节点（`:ability/caster`/`:ability/tunable`/…，见 NODE_LANGUAGE.md §5）读取环境；组合层/底层节点内部不可以。

## 排障手册

- 技能施放无效果 → 先看该技能的编译 `:errors`（`combat_catalog/initialize!` 的返回值），确认文档本身编译通过。
- "unknown component" / "component field is missing" → 对照 `components.clj`/对应 composite 文档的 `:inputs` 声明，字段名或类型不对。
- "read of a local not bound on every reachable path" / 作用域相关错误 → 检查 `:bind` 是否在读取点之前的兄弟节点完成，是否跨了 `:flow/branch`/`:txn/atomic` 的分支边界（分支间绑定不逃逸，见 NODE_LANGUAGE.md §4）。
- source 节点相关编译错误 → 确认该节点只出现在技能文档顶层，且技能文档确实声明了对应的 `:tunables`/`:costs`/`:progression`/`:cooldown`/`:invariants` 条目。

## 变更风险

- `combat-core` 只产出计划/直接调用已注册的 mcmod 端口，绝不写 AC 的玩家存档 schema——这条边界由 `verifyAcNoWorldCapabilities`/`verifyCombatSingleDamagePath` 等门禁强制；新增 `:mutate` 原语时确认它落地的是 mcmod 端口而不是绕道 AC。
- `:layer :composite` 组件不得有 `:impl`，不得出现在任何 `defmethod`/handler 表里——`verifyNodeLayerDiscipline` 强制。
- 组合层/底层节点不得读取 `{:from …}`/`{:tunable …}`/`{:ref [:context …]}` 等环境形式——`verifyNoImplicitDependency` 强制。

## 兼容性约束

- `combat-core` 只依赖 `node-core` 与 `mcmod`；VFX 信号是中立数据 ABI，不依赖 `vfx-core`。不得依赖 `ac`/`platform`/任何具体 loader 命名空间，由 `verifyCombatDependencyDirection` 强制。
- `node-core` 不得依赖 `combat-core`/`vfx-core`/`mcmod`/`ac`，由 `verifyNodeCoreDependencyDirection` 强制。




## Deferred ownership

Combat Core owns neutral settlement (`beam-settlement`) only. The instance-local continuation queue belongs to `ability-runtime`; AC/BC/CC install one composition runtime and supply lifecycle/result callbacks. A graph `flow/after` is a graph-local scheduling construct in the AC composition root and is not a second beam/deferred implementation. No combat module stores a global pending queue.
