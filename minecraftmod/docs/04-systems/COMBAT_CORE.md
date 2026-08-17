# Combat Core 维护手册

## 系统职责

`:combat-core` 是纯数据、平台中立的技能程序引擎：技能是编译期校验过的数据结构（`:sequence`/`:repeat`/`:branch`/`:query`/`:damage`/`:vfx`/`:world-effect`/`:domain-event`/`:patch`/`:phase`/`:session-patch` 等 op），由 `runtime.clj` 的执行器解释执行。它只产出中立的结果计划（伤害请求、VFX 信号、world-effect 描述、StatePatch）——从不认识 Minecraft、渲染或任何具体 VFX 运行时；执行这些计划是 AC 与 host 适配器的职责。

## 模块边界

- `combat-core/src/main/clojure/cn/li/combat/registry.clj`：node/ability/provider 的冻结注册表，`register-node!`/`register-ability!` 在 `freeze!` 之后拒绝新注册。
- `combat-core/src/main/clojure/cn/li/combat/dsl.clj`：技能作者用的数据优先 DSL（`defability`/`sequence`/`repeat`/...），展开为不可变 map，不执行游戏逻辑。
- `combat-core/src/main/clojure/cn/li/combat/compiler.clj`：确定性编译器，`built-in-ops` 校验合法 op 集合，`content-hash` 提供内容寻址。
- `combat-core/src/main/clojure/cn/li/combat/damage.clj`：纯变换伤害管线，`damage-request` 构造/校验请求，永不产生 Minecraft 副作用。
- `combat-core/src/main/clojure/cn/li/combat/runtime.clj`：`create-engine` 组装执行器；`:query-port`/`:damage-pipeline`/`:domain-event-handler` 是外部注入的中立接口，engine 本身不知道它们的实现细节。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_content.clj`：AC 侧技能内容目录，`:execution :combat-core` 是每个 skill-spec 的执行路由标记。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_runtime.clj`：AC 侧 composition root，安装 `:query-port`（`:raycast` 等 query 类型的实现）、`:damage-pipeline`、`:domain-event-handler`，并把 engine 的计划桥接到真实的伤害/VFX/world-effect 执行。
- `ac/src/main/clojure/cn/li/ac/client/combat_vfx_adapter.clj` + `ac/src/main/clojure/cn/li/ac/client/effect_controller.clj`：客户端把 combat-core 发出的 `:vfx` 信号路由到 vfx-core 的实例（详见 [VFX_CORE.md](VFX_CORE.md)）。

## 运行时流程

1. `combat_content.clj` 声明技能的 skill-spec（含 `:execution :combat-core`），交给 `compiler.clj` 编译成内容寻址的 catalog。
2. 服务端 `combat_runtime.clj` 用编译后的 catalog 调 `runtime/create-engine`，注入真实 `:query-port`（当前只装了 `:raycast`，见下方"已知限制"）。
3. 客户端发出的 CombatIntent 驱动 engine 逐 op 执行：`:query` 向 `:query-port` 要数据，`:damage` 经 `damage.clj` 产出纯变换后的伤害请求，`:vfx` 产出携带 `[:combat owner activation-key effect-id]` 形态 `instance-key` 的信号，`:world-effect`/`:domain-event` 产出中立描述。
4. `combat_runtime.clj` 把这些计划翻译成真实副作用：伤害请求交给 `entity-damage` 平台适配器，VFX 信号推给 `MSG-COMBAT-RESULT` 网络消息，world-effect 描述交给 world-effects 平台适配器。
5. `assert-complete-composition!`（`combat_content.clj`）在 content 加载时校验 `:abilities` 声明集合与 `skill-specs` 集合完全一致（从 id 集合派生比较，不再硬编码技能数量）。

## 扩展点

- 新增 op：在 `compiler.clj`'s `built-in-ops` 登记，在 `runtime.clj` 加执行分支，在 `dsl.clj` 加对应构造函数。
- 新增技能：在 `combat_content.clj` 加一条 skill-spec，`:execution :combat-core` 是必需字段——遗漏会被 `context_state.clj` 的 fail-closed 守卫拒绝执行。
- 新增 query 类型：在 `combat_runtime.clj` 的 `:query-port` 实现里加分支——**加之前先读下面"已知限制"**，当前只有 `:raycast` 真正装了实现。

## 已知限制（重要，排障先看这里）

> 2026-08-17 更新：本节曾经的表述（"只装了 `:raycast`"、"mine-ray 端到端验证过"）已被逐条核实推翻。完整、按技能分类的当前缺口清单见 **[COMBAT_VFX_PLATFORM_GAPS.md](COMBAT_VFX_PLATFORM_GAPS.md)**，本节只保留排障判断依据。

**`:query-port` 与 world-effect 执行器都可能缺失，且缺失方式不同、不能只查一处。** `default-query-port`（`combat_runtime.clj`）里 9 种 query 有真实本地实现（`:raycast`/`:attack`/`:ray-barrage`/`:directed-blastwave`/`:groundshock`/`:thunder-clap`/`:blood-retrograde`/`:vec-accel`/`:vec-deviation`），其余约 13 种恒返回 `nil`。world-effect 侧（`mcbase/adapter/world_effects.clj` 的 `create-world-effects`）只真正安装了 4 个执行器（`execute-vec-accel!`/`execute-mag-movement!`/`execute-mag-manip!`/`execute-vec-deviation!`），其余约 11 个调用即抛异常（被 try/catch 兜成 `:status :failed`）。**两层要分别检查**——一个技能的 query 工作正常不代表它的 world-effect 也工作（例如 `directed-shock` 的 `:raycast` 查询正常、伤害正常命中，但 `:knockback` world-effect 完全没有处理分支）。

排障判断依据：如果一个已迁移到 combat-core 的技能施放后完全没有效果（伤害、位移、特效都没有），无论哪种情况都**不会抛异常或记可见错误**——query 返回 nil 被 `:require` 拒绝成普通"没瞄准目标"（这个已经在 [COMBAT_VFX_PLATFORM_GAPS.md](COMBAT_VFX_PLATFORM_GAPS.md) 相关的执行会话里补了 `:query-returned-nil` 诊断 feedback，见 commit `d72b1695f`），world-effect 缺失则被 `execute-world-effects!` 的 try/catch 降级为 `:status :failed`。先检查它的 op 序列里的 `:query-type`，再检查 `:world-effect` 的 `:effect-type`，分别对照 `default-query-port` 和 `create-world-effects` 是否真的覆盖了这两个值。

只有 `:raycast`/`:attack`/`:ray-barrage`/`:directed-blastwave` 类查询 + `:damage`（走独立的伤害管线，不经过 `world-effects/execute-*!`）组合出的技能（如 railgun、thunder-bolt、electron-bomb、flesh-ripping、directed-shock 的伤害部分）是当前可信的端到端正常路径。

## 排障手册

- 技能施放无效果 → 先看上面的"已知限制"。
- VFX 没有出现，客户端日志报 `unknown VFX effect` → `combat_content.clj` 发出的 `:effect-id` 与客户端注册的 effect-id 不一致，参见 [VFX_CORE.md](VFX_CORE.md) 的排障手册。
- `assert-complete-composition!` 抛 "Combat Core composition incomplete" → `:abilities` 声明与 `skill-specs` 的 id 集合不一致，检查两边是否漏加/多加了某个技能。
- `verifyCombatSkillCoverage`/`verifyCombatContentHash`/`verifyAbilityVfxRegistryCoverage` 见 [../dev/AGENT_AND_TOOLING.md](../dev/AGENT_AND_TOOLING.md) 的 Required gate。

## 变更风险

- `runtime.clj` 只产出计划，绝不能引入任何直接副作用（网络发送、渲染调用、平台 API）——这是 `verifyCombatNoPlatformNamespaces`/`verifyCombatDependencyDirection` 强制的边界。
- `:query-port`/`:damage-pipeline`/`:domain-event-handler` 是唯一允许的外部注入点；不要在 engine 内部新增隐式依赖或全局状态读取。
- `registry.clj` 冻结后拒绝新注册——测试之间必须调用相应的 reset，否则会跨测试污染。

## 兼容性约束

- combat-core 只依赖 `:mcmod`（见 [PROJECT_LAYOUT.md](../01-overview/PROJECT_LAYOUT.md)），不得引用 `cn.li.(ac|platform|mcbase|mc1201|mc1211|mc262|forge|fabric|neoforge|vfx|presentation).*`，由 `verifyCombatDependencyDirection`/`verifyCombatNoPlatformNamespaces` 强制。
- combat-core 的 Clojure 源码是全部实现——`verifyCombatClojureOwnership` 禁止 `combat-core/src/main/java` 出现战斗实现逻辑。
