# Combat Core 维护手册

> 语言本体（surface DSL/IR/词汇表/静态代价分析）的完整规格见
> [NODE_LANGUAGE.md](NODE_LANGUAGE.md)——本文只讲 combat-core 如何使用这套语言、
> 模块边界、以及排障。**先读 NODE_LANGUAGE.md §0**：新引擎是技能/战斗 dispatch
> 唯一的实机路径；旧的图执行引擎（`final_engine.clj`/`final_compiler.clj`）**已
> 删除**——`combat-catalog.clj` 早先经由它们编译内容元数据，但那步编译从没有
> 真正被执行过（`combat-api/execute!` 零调用点），移除后不影响任何真实功能。
> 旧的**内容元数据加载侧**（`ac/ability/final_catalog.clj`/
> `final_catalog_service.clj`、`ac/combat/abilities/*.edn`〔39 个〕、
> `combat-core/composites/*.edn`〔17 个〕）**也已删除**：`combat-catalog.clj`
> 自己的元数据来源已重写为直接读 `ac/skills-v4/*.edn`（`skills-catalog.clj`），
> 见下方"内容元数据加载侧"一节。`kernels.clj` 不属于"旧引擎"，是永久保留的
> 基础设施，见 NODE_LANGUAGE.md §0 详细说明。`final_damage.clj` 自己也已删除
> ——聚合运算逐字节 port 进 `combat/damage.clj`（同一份算法，只是分派层从
> `final_damage.clj` 的 O(n) 线性扫描换成 `damage.clj` 的 mark-type+priority
> 索引查找，见下方"伤害管线"一行），`combat-api/resolve-damage` 现在指向它。

## 系统职责

`combat-core` 是战斗侧的执行引擎：加载词汇表（原语 + `:defn` 组合），把技能/
法术编译并执行成中立的结果计划（`.-actions`/`.-events`/`.-vfx`/`.-stateWrites`/
`.-result`）——从不直接改动 Minecraft 状态。真正落地世界效果、伤害、位移由 mcmod
端口 + 已注册的 host capability 完成；AC 只负责组装与自己领域（技能学习/资源/
成就）的注入。

## 唯一的执行引擎；内容元数据加载侧现在也读同一套内容

| | 唯一的 dispatch 路径 |
|---|---|
| 入口 | `cn.li.combat.run`（`combat_runtime.clj` 的 `dispatch-intent-v2!`，真实
  玩家操作、`process-damage-request!`/`apply-attack-precheck!`〔原生近战伤害/
  反射边界〕全部走这里） |
| 词汇表 | `cn.li.combat.dsl-vocabulary`（`nodes`，带真实 `:params`/`:returns`/`:effects`/`:capability`/`:cost`） |
| 内容资源 | `ac/src/main/resources/ac/skills-v4/*.edn`（每个文件都是可校验的 `:ac/skill-v4` map，入口位于 `:entries`，参数/状态/元数据同图编辑器共享） |
| 复用单元 | `combat-core/lib/*.edn` + `combat-core/lib.clj`（`:defn`，显式文件名列表加载，见 NODE_LANGUAGE.md §1） |
| 伤害管线 | `combat-core/damage.clj`（`combat-api/resolve-damage`/`materialize-vfx`）——独立于 dispatch 引擎，永久共享 |
| 玩家法术 | `cn.li.combat.player`（S7，desugar/admit，见 NODE_LANGUAGE.md §6） |

`combat-source`（`combat_runtime.clj` 内部函数，`final-capabilities-v2`/
`damage-policy-inputs`/`mark-rate-for` 等共享读取点）读 `final-runtime-v2*`
里的新 catalog（`cn.li.ac.ability.skills-catalog/assemble` 的输出）——不读任何
旧 catalog，dispatch/伤害拦截这两条真实路径完全不依赖旧内容目录。

`combat-catalog.clj` 的技能元数据表（skill tree UI、trigger 索引、
passive-effects、activation-context 的 bindings/presentation）现在直接读
`cn.li.ac.ability.skills-catalog/assemble`（`ac/skills-v4/*.edn` +
`the ac/skills-v4 directory`），与 dispatch/伤害拦截读的是**同一套内容**——不再有
第二条内容加载路径。旧 manifest/composite 资源和对应读取器已删除；生产内容只从
`ac/skills-v4/*.edn` 与 `ac/vfx-v4/*.edn` 进入 V4 catalog。V4 文档由
`skills-catalog-v3`/`fx-catalog-v3` 解析、校验并编译，运行时只消费编译后的 IR。
`node-core` 的 schema/export API 仅作为编辑器基础设施保留，不读取
任何旧生产资源。
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

## 内容元数据加载侧（独立于执行引擎，但现在共享同一套内容）的模块边界

- `ac/src/main/clojure/cn/li/ac/ability/skills_catalog.clj`：`assemble` 读
  `the ac/skills-v4 directory`，逐 `:sources`/`:registrations` 装配，`:program`
  编译成 IR（`combat-api/compile-skill-doc!`）供新引擎 dispatch 用——纯数据
  组装，不做结构校验。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_catalog.clj`：在
  `skills_catalog/assemble` 的输出上投影出 skill tree UI（`skill-specs`）、
  item-trigger 索引（`resolve-trigger`）、passive-effects
  （`apply-passive-resource-modifiers`）——真实生产启动（`core/init.clj`）和
  测试套件广泛依赖它。`:category-id`/`:level`/`:controllable?` 优先取
  `skill-config/skill-definitions-by-id`（该表的文档字符串自称是这三个字段的
  single source of truth），EDN 内容里的同名字段只是未配置技能的兜底。
- `combat-core/src/main/clojure/cn/li/combat/damage.clj`：统一 DamageEvent 收集/
  确定性 resolve 与 mcmod DamageBoundary 结果——**独立于 dispatch 引擎，永久
  共享**，不属于内容加载或执行引擎中的任何一侧。取代已删除的 `final_damage.
  clj`：聚合算法（`multiply`/`reduce`/`absorb`/`critical`/`reflect` 的合并、
  资源代价结算、`max-reflection-depth`）逐字节 port 过来，唯一实质变化是
  `collect` 的 O(n) 全表线性扫描换成 `build-index`/`candidates-for` 的
  mark-type+priority 索引查找（O(k)，k = 该事件 mark-type 相关的 policy
  数）。`combat/api.clj` 的 `resolve-damage` 在内部对每次调用的 policies 列表
  现建现查一次索引（不跨事件缓存——沿用 `combat_runtime.clj` 自己
  `final-damage-policies-v2` 一贯的"伤害不是逐帧热路径，不为它引入需要跟
  `final-runtime-v2*` 保持同步的第二份可变状态"判断，如果之后 profiling
  证明这里确实是瓶颈，跨事件缓存索引是一个独立、更晚的优化）。
- `combat-core/src/main/clojure/cn/li/combat/platform.clj`：向 mcmod 注册的 host
  query/action capability 表，新引擎通过这份注册表在真实游戏里 dispatch。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：`merge-draw-
  lists`/`merge-vfx-into-frame` 仍是 `ac/gui/reactive/register.clj` 的真实调用
  点；`compose-catalog`/`catalog-fingerprint-input`（旧 `final_catalog.clj` 曾经
  唯一的调用方，跨核心捆绑 combat+vfx+node-environment 做内容指纹）随
  `final_catalog.clj` 一起变成零调用点，未删除但已是死代码——若你需要跨进程内容
  身份校验，`combat-catalog.clj` 自己已有一份更简单的等价物（对
  `:sources`/`:registrations` 直接 `cn.li.node.digest/content-hash`），不要
  重新接上这两个旧函数。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_runtime.clj`：AC
  composition root，`dispatch-intent-v2!`/`process-damage-request!`/
  `apply-attack-precheck!` 都在这里。

## 运行时流程（当前实机行为）

1. `ac.ability.skills-catalog/assemble` 读 `the ac/skills-v4 directory`，逐文档编译
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
5. 任意入站伤害都进入 `damage.clj` 的统一 DamageEvent 收集/resolve 边界，
   与哪个引擎负责 dispatch 无关（见上表）。

旧引擎自己曾经的运行流程（`final_catalog/initialize!` → 具体化 tunable →
composite 展开 → `final_compiler/compile-program` → `final_engine/execute!`）
**已经不存在**：`final_engine.clj`/`final_compiler.clj`/`final_catalog.clj`/
`final_catalog_service.clj` 全部已删除，`combat-catalog.clj` 不再有第二条内容
加载路径可比较——它现在读的就是步骤 1 里 `skills-catalog/assemble` 装配的同一份
`ac/skills-v4/*.edn` 内容，只是取其中 dispatch 不需要的字段（`:name-key`/
`:actions`/`:external-triggers`/`:passive-effects` 等）。

## 排障手册

- 一份 `ac/skills-v4/*.edn` 编译报 `type-mismatch`/`unknown-node` → 对照
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
- `combat-catalog/initialize!`（或任何依赖它的测试 fixture）报编译错误 → 检查
  `ac/skills-v4/*.edn` 里某个文件的问题；旧目录 `ac/combat/abilities/*.edn` 已删除，
  `combat-catalog.clj` 现在只读 `ac/skills-v4/*.edn`，跟 dispatch 引擎读的是同一份
  内容，不会再有"两个目录、两份真相"的问题。

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

