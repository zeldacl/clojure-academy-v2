# Combat Core 维护手册

> 语言本体（surface DSL/IR/词汇表/静态代价分析）的完整规格见
> [NODE_LANGUAGE.md](NODE_LANGUAGE.md)——本文只讲 combat-core 如何使用这套语言、
> 模块边界、以及排障。**先读 NODE_LANGUAGE.md §0**：两套引擎并存，本文档同时描述
> 两者，注意区分。

## 系统职责

`combat-core` 是战斗侧的执行引擎：加载词汇表（原语 + `:defn` 组合），把技能/
法术编译并执行成中立的结果计划（`.-actions`/`.-events`/`.-vfx`/`.-stateWrites`/
`.-result`）——从不直接改动 Minecraft 状态。真正落地世界效果、伤害、位移由 mcmod
端口 + 已注册的 host capability 完成；AC 只负责组装与自己领域（技能学习/资源/
成就）的注入。

## 两套引擎，同一份内容目录树的两个版本

| | 旧（当前实机路径） | 新（已验证，未接入实机） |
|---|---|---|
| 入口 | `cn.li.combat.final-engine`/`final-compiler` | `cn.li.combat.run` |
| 词汇表 | `cn.li.combat.vocabulary`（`component-specs`，~57 条，`:type :any` 居多） | `cn.li.combat.dsl-vocabulary`（`nodes`，带真实 `:params`/`:returns`/`:effects`/`:capability`/`:cost`） |
| 内容资源 | `ac/src/main/resources/ac/combat/abilities/*.edn`（旧节点树 EDN） | `ac/src/main/resources/ac/skills/*.edn`（`:program` 字段内嵌新 DSL 文本，其余顶层键——`:tunables`/`:costs`/`:cooldown`/`:progression`/`:session-state`/`:mark-policies`/`:damage-policies`——跟旧文件逐字节相同） |
| 复用单元 | `combat-core/composites/*.edn`（旧宏替换式 composite） | `combat-core/lib/*.edn` + `combat-core/lib.clj`（`:defn`，显式文件名列表加载，见 NODE_LANGUAGE.md §1） |
| 伤害管线 | `combat-core/final_damage.clj` | 未迁移（不在 S6/S7 范围内） |
| 玩家法术 | 不存在 | `cn.li.combat.player`（S7，desugar/admit，见 NODE_LANGUAGE.md §6） |

旧文件在新版本转换完成后**原样保留，未被删除或修改**——`ac/skills/*.edn` 是
`ac/combat/abilities/*.edn` 的**新增同级文件**，不是替换。两者除了偶尔在测试里
互相印证语义外，运行期完全独立：旧引擎只读旧目录，`cn.li.combat.run` 只读新
目录（通过 `read-skill`/`compile-and-dispatch!` 测试 helper，见
`ac/src/test/clojure/cn/li/ac/skills/skills_test.clj`）。

## 新引擎（`cn.li.combat.run`）的模块边界

- `node-core/**`：语言本体，不依赖 Minecraft/combat-core/vfx-core/ac/mcmod。
- `mcmod/src/main/java/cn/li/mcmod/runtime/{ExecutionFrame,CompiledProgram,
  HostTable}.java`：运行时载体，分型寄存器组（doubles/longs/booleans/objects）。
  受 `verifyEffectRuntimeJavaCarriers` 约束——不得出现 `if|else|switch|for|while|
  do|.invoke(|execute`，一切逻辑在 Clojure 侧。
- `mcmod/src/main/clojure/cn/li/mcmod/runtime/effect_emit.clj`：IR → 闭包链，
  `compile-program`/`dispatch!`/`new-frame`。
- `combat-core/src/main/clojure/cn/li/combat/run.clj`：把 node-core 编译器接上
  combat 自己的词汇表（`dsl_vocabulary.clj`）和 capability 类型表
  （`capability-type`），`compile-doc!`/`compile-program`/`dispatch!` 是三个组合
  好的入口，测试和真正的组装点都只应该调这三个函数。
- `combat-core/src/main/clojure/cn/li/combat/dsl_vocabulary.clj`：新词汇表本体，
  `:effects` 字段是 `cn.li.node.cost/analyze` 与 `cn.li.combat.player` 的唯一
  数据来源。
- `combat-core/src/main/clojure/cn/li/combat/lib.clj` + `lib/*.edn`：跨技能/跨
  event 复用的 `:defn` 组合库。
- `combat-core/src/main/clojure/cn/li/combat/player.clj`：玩家法术
  desugar/admit（S7，见 NODE_LANGUAGE.md §6）。
- `mcmod/src/main/clojure/cn/li/mcmod/runtime/fixed_channel.clj`：
  `:spell-submit` 包类型，玩家 glyph 向量上行的有界二进制封装。

## 旧引擎（仍是实机路径）的模块边界

- `combat-core/src/main/clojure/cn/li/combat/final_engine.clj`：唯一仍在被真实
  游戏调用的技能图执行器；只处理编译后的节点树，产出中立 actions/events/VFX
  信号。
- `combat-core/src/main/clojure/cn/li/combat/final_compiler.clj`：唯一仍在被
  真实游戏调用的技能图编译器。
- `combat-core/src/main/clojure/cn/li/combat/final_damage.clj`：统一 DamageEvent
  收集/确定性 resolve 与 mcmod DamageBoundary 结果——伤害管线尚未迁移到新引擎，
  改动伤害相关内容目前仍只能通过这一路径。
- `combat-core/src/main/clojure/cn/li/combat/platform.clj`：向 mcmod 注册的 host
  query/action capability 表，新旧两条路径当前共用同一份 host 注册（新引擎的
  测试用假 host，真正游戏内 dispatch 走这里——但目前没有游戏内代码调用新引擎，
  所以这份共享只在"两边概念上兼容"的意义上成立，不代表已经切换）。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：唯一同时组合
  node/combat/vfx/presentation 值的中立边界。
- `ac/src/main/clojure/cn/li/ac/ability/final_catalog.clj`：AC 侧内容加载器，
  读取旧 manifest（`ac/combat/manifest.edn`/`ac/combat/composites/manifest.edn`）
  并展开旧 composite。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_runtime.clj`：AC
  composition root，注入 AC 自己领域的端口，提交旧引擎产出的 patch。

## 运行时流程（旧引擎，当前实机行为）

1. `final_catalog/initialize!` 加载 manifest，逐文档编译，失败的文档进
   `:errors`，不影响其余文档启动。
2. 客户端 CombatIntent 驱动 AC final runtime：具体化 tunable → composite 展开 →
   `final_compiler/compile-program` → `final_engine/execute!`，产出中立
   `{:actions :events :vfx-signals :feedback :query-results ...}`。
3. `combat_runtime.clj`（AC）把 `:actions` 里的 patch 提交进玩家存档；
   `:vfx-signals` 交给 ability-runtime 路由，再由 AC 的 VFX adapter 广播。
4. 任意入站伤害都进入 `final_damage.clj` 的统一 DamageEvent 收集/resolve 边界。

## 排障手册

**新引擎**（`ac/skills/*.edn` 编译/测试相关）：

- 一份 `ac/skills/*.edn` 编译报 `type-mismatch`/`unknown-node` → 对照
  `dsl_vocabulary.clj` 对应节点的 `:params` 声明，字段名/类型是否匹配；确认
  `?capability` 是否已在 `run.clj` 的 `capability-type` 里声明或能按命名空间
  规则派生。
- `finish` 之前的 `when`/`if` 分支报 "condition must be :boolean" → 条件表达式
  的静态类型必须是 `:boolean` 或 `:any`（一个具体的 `:entity-ref`/`:vec3` 之类
  不行）——常见错法是拿 `?caster/id`（固定 `:entity-ref`）当存在性判断用，应改
  拿一个 `:any` 类型的字段访问结果（比如 raycast 命中的 `(:entity-id hit)`）。
- 一个 event/phase 没写 `finish` 但测试断言 `.-result` 是 `nil` → 错的是测试，
  不是代码：没调用 `finish` 时 `.-result` 是 `{:outcome :ended :next-phase nil
  :end-ability? false}`，见 NODE_LANGUAGE.md §1。

**旧引擎**（`ac/combat/abilities/*.edn`，实机行为相关）：

- 技能施放无效果 → 先看该技能的编译 `:errors`（`final_catalog/initialize!`
  的返回值），确认文档本身编译通过。
- "unknown component" / "component field is missing" → 对照
  `vocabulary.clj`/对应 composite 文档的 `:inputs` 声明，字段名或类型不对。

## 变更风险

- `combat-core` 只产出计划/直接调用已注册的 mcmod 端口，绝不写 AC 的玩家存档
  schema——这条边界由 `verifyAcNoWorldCapabilities`/`verifyCombatSingleDamagePath`
  等门禁强制。
- 新增新引擎节点：只在 `dsl_vocabulary.clj` 加一条，`:effects` 字段必须如实
  反映它真正做什么（`cn.li.combat.player` 的准入白名单直接读这个字段，写错等于
  开了一个安全漏洞或者堵死了一个本该合法的玩家法术）。
- 新增新引擎 `:defn` 组合：加进 `combat-core/lib/*.edn`，并把文件名加进
  `lib.clj` 的显式列表——漏加等于这个组合永远编译不到，且不会有任何错误提示
  （直到有人尝试调用它，得到 "unknown fn"）。
- `combat-core` 只依赖 `node-core` 与 `mcmod`；不得依赖 `ac`/`platform`/任何
  具体 loader 命名空间，由 `verifyCombatDependencyDirection` 强制。
- `node-core` 不得依赖 `combat-core`/`vfx-core`/`mcmod`/`ac`，由
  `verifyNodeCoreDependencyDirection` 强制。

## Deferred ownership

Combat Core owns neutral settlement (`beam-settlement`) only. The instance-local
continuation queue belongs to `ability-runtime`; AC/BC/CC install one composition
runtime and supply lifecycle/result callbacks. No combat module stores a global
pending queue.
