# Combat Core 维护手册

> 语言本体（surface DSL/IR/词汇表/静态代价分析）的完整规格见
> [NODE_LANGUAGE.md](NODE_LANGUAGE.md)——本文只讲 combat-core 如何使用这套语言、
> 模块边界、以及排障。**先读 NODE_LANGUAGE.md §0**：新引擎是技能/战斗 dispatch
> 唯一的实机路径；旧引擎（`final_engine.clj`/`final_compiler.clj`/`kernels.clj`）
> 仍在代码里，但不再有专属测试直接调用它——它没删除的原因是 `combat-catalog.
> clj` 仍把它当内容元数据编译器用，跟"哪个引擎执行技能"无关，见 NODE_LANGUAGE.md
> §0 详细说明。

## 系统职责

`combat-core` 是战斗侧的执行引擎：加载词汇表（原语 + `:defn` 组合），把技能/
法术编译并执行成中立的结果计划（`.-actions`/`.-events`/`.-vfx`/`.-stateWrites`/
`.-result`）——从不直接改动 Minecraft 状态。真正落地世界效果、伤害、位移由 mcmod
端口 + 已注册的 host capability 完成；AC 只负责组装与自己领域（技能学习/资源/
成就）的注入。

## 两套引擎共存的真实原因：内容元数据编译，不是 dispatch 安全网

| | 旧（不再被任何 dispatch 调用，只被 `combat-catalog.clj` 当元数据编译器用） | 新（唯一的 dispatch 路径） |
|---|---|---|
| 入口 | `cn.li.combat.final-engine`/`final-compiler`，经 `cn.li.ac.ability.
  final-catalog-service` 被 `combat-catalog.clj` 调用——不再有 `dispatch-intent!`
  这个函数，也不再有任何测试直接调它 | `cn.li.combat.run`（`combat_runtime.clj` 的
  `dispatch-intent-v2!`，真实玩家操作、`process-damage-request!`/
  `apply-attack-precheck!`〔原生近战伤害/反射边界〕全部走这里） |
| 词汇表 | `cn.li.combat.vocabulary`（`component-specs`，~57 条，`:type :any` 居多） | `cn.li.combat.dsl-vocabulary`（`nodes`，带真实 `:params`/`:returns`/`:effects`/`:capability`/`:cost`） |
| 内容资源 | `ac/src/main/resources/ac/combat/abilities/*.edn`（旧节点树 EDN，仍被
  `ac/ability/final_catalog.clj` 的 `load-combat` 加载——供 `combat-catalog.clj`
  的技能元数据表使用，例如 trigger 索引、mark-policies） | `ac/src/main/resources/ac/skills/*.edn`（`:program` 字段内嵌新 DSL 文本，其余顶层键——`:tunables`/`:costs`/`:cooldown`/`:progression`/`:session-state`/`:mark-policies`/`:damage-policies`——跟旧文件逐字节相同） |
| 复用单元 | `combat-core/composites/*.edn`（旧宏替换式 composite） | `combat-core/lib/*.edn` + `combat-core/lib.clj`（`:defn`，显式文件名列表加载，见 NODE_LANGUAGE.md §1） |
| 伤害管线 | `combat-core/final_damage.clj`——**新旧两条 dispatch 路径共用同一份**，
  永久共享，不属于任何一侧 | 同左 |
| 玩家法术 | 不存在 | `cn.li.combat.player`（S7，desugar/admit，见 NODE_LANGUAGE.md §6） |

`combat-source`（`combat_runtime.clj` 内部函数，`final-capabilities-v2`/
`damage-policy-inputs`/`mark-rate-for` 等共享读取点）现在直接读 `final-runtime-
v2*` 里的新 catalog（`cn.li.ac.ability.skills-catalog/assemble` 的输出），**不
再需要旧 catalog 提供元数据**——这是本文档早前版本描述的"故意读旧 catalog 共享
字段"设计已经被替换掉的地方，不要再假设这条读取路径存在。真正需要旧 catalog
的只剩 `combat-catalog.clj` 自己的技能元数据表（trigger 索引等），跟 dispatch/
伤害拦截完全无关。

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

## 旧引擎（不是 dispatch 路径，是 combat-catalog.clj 的元数据编译器）的模块边界

- `combat-core/src/main/clojure/cn/li/combat/final_engine.clj`：技能图执行器；
  只处理编译后的节点树，产出中立 actions/events/VFX 信号。已经不存在
  `dispatch-intent!`/`cn.li.ability.engine`（两者均已删除）这样的调用点了——
  唯一还在用它的是 `cn.li.ac.ability.final-catalog-service`，经由
  `combat-api/create-engine`/`execute!`/`compile-program` 把它当元数据编译器，
  供 `combat-catalog.clj` 使用。
- `combat-core/src/main/clojure/cn/li/combat/final_compiler.clj`：对应的技能图
  编译器，状态同上。
- `combat-core/src/main/clojure/cn/li/combat/final_damage.clj`：统一 DamageEvent
  收集/确定性 resolve 与 mcmod DamageBoundary 结果——**新旧两条 dispatch 路径
  永久共用同一份**，不属于"旧引擎"范畴，只是刚好也叫 final_*。
- `combat-core/src/main/clojure/cn/li/combat/platform.clj`：向 mcmod 注册的 host
  query/action capability 表，新引擎通过这份注册表在真实游戏里 dispatch。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：唯一同时组合
  node/combat/vfx/presentation 值的中立边界。
- `ac/src/main/clojure/cn/li/ac/ability/final_catalog.clj`：AC 侧内容元数据
  加载器，读取旧 manifest（`ac/combat/manifest.edn`/`ac/combat/composites/
  manifest.edn`）并展开旧 composite——`cn.li.ac.ability.service.combat-catalog`
  依赖它，后者被真实生产启动（`core/init.clj`）和测试套件广泛使用，所以这整条
  链路都不是死代码。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_runtime.clj`：AC
  composition root，`dispatch-intent-v2!`/`process-damage-request!`/
  `apply-attack-precheck!` 都在这里；不再引用 `cn.li.ability.engine`（已删除）。

## 运行时流程（当前实机行为）

1. `ac.ability.skills-catalog/assemble` 读 `ac/skills/manifest.edn`，逐文档编译
   `:program`（经 `combat-api/compile-skill-doc!` → `cn.li.combat.run`），失败的
   文档进 `:errors`，不影响其余文档启动。
2. 客户端 CombatIntent 包（`network.clj`）、物品触发（`server_hooks.clj`）、传送
   RPC（`location_teleport_rpc.clj`）三个真实入口，以及 `combat_runtime.clj` 自己
   的 `dispatch-trigger!`/`dispatch-event!`/`pulse-active-sessions!`，全部调用
   `combat_runtime.clj` 的 `dispatch-intent-v2!`。
3. `dispatch-intent-v2!` 的懒加载守卫只初始化新引擎自己的 catalog
   （`initialize-final-runtime-v2!`）——`combat-source`/`final-capabilities-v2`
   现在直接读这份新 catalog，不再需要旧 catalog 提供任何元数据。随后走
   `final-capabilities-v2`/`final-input-v2` 具体化 tunable/capability，交给
   `cn.li.combat.run` 的 `dispatch!` 执行，产出中立
   `{:actions :events :vfx-signals :feedback :query-results ...}`。
4. `combat_runtime.clj`（AC）把 `:actions` 里的 patch 提交进玩家存档；
   `:vfx-signals` 交给 ability-runtime 路由，再由 AC 的 VFX adapter 广播——这一步
   与切换前完全相同，两个引擎产出的中立结果计划形状一致，下游消费代码未改动。
5. 任意入站伤害都进入 `final_damage.clj` 的统一 DamageEvent 收集/resolve 边界，
   与哪个引擎负责 dispatch 无关（见上表）。

旧引擎自己曾经的运行流程（`final_catalog/initialize!` → 具体化 tunable →
composite 展开 → `final_compiler/compile-program` → `final_engine/execute!`）
不再被任何测试或 dispatch 路径驱动——`combat-catalog.clj` 只调用它的编译步骤
产出元数据，从不调用 `final_engine/execute!`。这条完整的旧执行流程代码还在，
但已经没有任何调用点会真的走完它。

## 排障手册

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
- `combat-catalog/initialize!`（或任何依赖它的测试 fixture）报编译错误，但看起来
  跟你正在改的新内容无关 → 检查是不是 `ac/combat/abilities/*.edn`（旧目录）里
  某个文件的问题；`combat-catalog.clj` 编译的是旧目录，不是 `ac/skills/*.edn`。

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
