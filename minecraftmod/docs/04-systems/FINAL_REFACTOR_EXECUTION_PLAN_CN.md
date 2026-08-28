# 最终技能图引擎重构执行计划（修订版）

本计划是对 `ac-combat-core-combat-core-vfx-core-ac-mossy-wren` 的执行版修订。目标是最终只保留一套图语言、战斗执行器、VFX 执行器和 mcmod 中转边界；旧 VM、旧 recipe、旧 composite、旧反应解释器只允许在迁移阶段存在，最终必须删除。

## 本轮复核结论（以当前代码为准）

- 已完成：39 份 ability/composite EDN 已物理迁移为 final 格式，manifest 的 `:overrides` 已改为显式 `:bindings`；catalog 直接读取 final EDN，不再执行旧 lowering。
- 已完成：静态 final vocabulary 已固定并在启动后 freeze；descriptor/schema/validate/scope 装配门覆盖全部当前节点类型，当前真实 catalog 为 39 source / 50 registration / 36 effect，50/50 registration 可编译。
- 已完成：combat `StateTxnSet`、session read/write、VFX/feedback/events outbox、owner 生命周期；VFX replication 的 update 包只携带 dirty-mask 和变化参数，并支持 baseline/replay；冷却提交按 `[ability-id cooldown-name]` 保持键空间，progression/score 事件经 AC command runtime 写回技能经验。
- 已完成：EDN 结构门禁递归检查 `:from/:tunable/:invariant`、旧 refs、旧 components、`:overrides`，任何残留均失败；没有运行时兼容 fallback。
- 已完成：descriptor 不再由装配图动态推导；`final-vocabulary` 提供 source-controlled descriptor/schema，并通过 registry freeze 防止运行期扩展。
- 本轮运行链路复核补齐：final engine 为 `:target/*`、`:owner/snapshot`、`:energy/target` 注入 owner/world/query-kind 并映射到 mcmod capability；`:domain/event` 明确写入 events outbox；`:combat/status`、`:combat/charged-area-damage` 与历史 `:target/beam` 均有显式能力映射，其中 charged-area 具备有界半径、充能比例、距离衰减和统一 DamageBoundary action。该类“descriptor 存在但 host 没有 handler”的缺口已加入测试门禁。
- 验收边界：本任务只做 headless/fake-mcmod 编译与测试；Minecraft 实机渲染、多人可见性和数值回归另开任务。
- 二次代码审计已补齐作用域 ABI：`flow/foreach`/`data/bind` 的声明字段、查询节点的紧凑 `:result`、`combat/charged-area-damage` 的 `:ratio-slot` 以及 projectile callback 的闭合作用域均进入同一套 descriptor-driven scope/composite 规则；不再用全树局部变量预填充掩盖未绑定引用。真实 EDN coverage 已通过 9 tests / 25 assertions。

## 已确认的矛盾与遗漏

1. 原计划把“旧 VM 删除”写成可直接执行；本轮已完成“最终 catalog → final compile → AC 调度切换”，生产入口不再依赖 `skill-runtime`/旧 VM，剩余 F6 只需继续由静态门禁确认旧文件无生产引用。
2. 原计划保留 `combat-core → vfx-core`，但用户要求最终依赖方向清晰。现在 Combat 只产生中立 VFX signal，AC 组合 VFX catalog，已移除该直接边；计划中的验证门禁必须更新为禁止该边。
3. 原计划把 catalog hash、输入边沿、VFX 状态同步混在一起。正确分层是：固定字节协议（mcmod）只传 catalog hello/ack、输入 edge、feedback、VFX spawn/update/trigger/destroy/clear-owner/snapshot；技能图、资源值和世界查询永不下发。
4. 原计划写了“VFX catalog/replication”，但缺少 VFX 执行引擎。现已补充 typed effect instance runtime、graph sampler、recipient baseline/replay 和 dirty-mask 增量包；旧 VFX runtime/recipe 生产路径已不再被引用。
5. “不做实机测试”与“实机验收”混在原计划中。此次验收只要求静态检查、Clojure 编译、headless fake-mcmod 测试；实机渲染、多人可见性和数值回归另开任务，不阻塞本次交付。
6. 当前 catalog 直接读取 39 个 final combat source、50 个 specialization、36 个 VFX effect；资源门禁递归确认旧 `:from/:tunable/:slot/:context/:session/patch` 均为 0，`ready=50/pending=0` 是静态 final EDN 与编译结果的共同约束，而不是 build-time lowering 的产物。
7. catalog 内容哈希必须使用跨平台稳定的 canonical 排序，不能用混合 keyword/string 键的默认 `sorted-map`；该问题已在 AC 目录中修复。
8. AC final catalog service、mcmod catalog hello/ack 编解码、固定输入边沿通道和 final runtime composition root 已接入 descriptor/schema/validate/scope 装配校验；登录时服务端发送 catalog hello，客户端回 ACK，服务端按 schema/hash 建立会话闸门，未握手成功的 CombatIntent 直接拒绝。CombatIntent 通过 mcmod binary codec 携带 fixed-channel 字节 payload，服务端再解码；非 wire payload、截断包和超长包全部拒绝，旧格式不再进入执行器。
9. AC client catalog projection 已保留每个 VFX final source graph；`spawn/update/trigger → effect instance → sample-frame!` 会产生 `draw-batch`/audio/camera/post neutral ops。`ray-beam` 有明确执行分支，其余已注册 typed VFX component 不再静默丢弃，而是输出可观测 typed draw payload，交给 presentation adapter 专化。
10. 不能把 descriptor/schema 覆盖率当作执行完整性：必须建立 `final component → capability → handler → apply result` 的可执行矩阵；只在 vocabulary 注册、但没有 host action/query 的节点不得标记 ready。
11. “composite”必须区分最终编译期展开与旧运行时解释器：`node/composite` 是 final graph 的受限展开内核，不能删除；旧 combat/vfx VM、recipe loader 和运行时 composite 路径才属于 F6 删除范围。AC catalog 已改为调用 `node/composite/expand-with-descriptors`，不再保留第二套输入替换/递归预算规则；后续只需用回归测试锁定这一唯一入口。
12. mcmod 的 `cn.li.mcbase.presentation.host-lifecycle` 不是生产 Minecraft API，而是 neutral presentation 的 mcmod/base 中转契约；本轮已在 `platform-src/test-support` 补齐 headless 实现，避免把“缺测试支撑 namespace”误判为生产编译缺口。缺失 world operation 现在按边界契约 fail-closed，缺失 client UUID 先返回专用 contract 错误；对应测试已同步，当前 mcmod 全量 headless 测试为 191 tests / 569 assertions、0 failures / 0 errors。
13. AC 全量 test source 仍包含历史测试基础设施引用（`hud-render-data`、`node-info-area-policy`），它们不影响 `:ac:checkClojure`、final catalog assembly 或 EDN coverage；执行计划必须把“生产编译门”和“历史全量测试清理”分开，不能用后者阻塞 final runtime 交付。
14. 本轮已补回纯测试支撑 `hud_render_data` 和 `node-info-area-policy`；AC 全量 test compile 仍会暴露更多明确的旧测试引用（`panel_reactive`、`console_reactive`、`skill-runtime`，以及 focused classpath 对 node-core 的缺失）。这些是旧测试迁移/Gradle test classpath 工作，不是生产源码入口；F7 以 production `checkClojure`、模块 headless 执行器测试、mcmod headless 测试和 EDN coverage 为本次交付门，AC 历史测试清理仍单列但不改变生产入口。
15. 本轮实际网络审计发现固定 `input-edge` 解码原先接受合法包后的尾随字节；现已在 `fixed-channel` 拒绝尾随数据，并加入回归断言。通用 `binary-codec` 现对字符串、集合计数、字节负值/上限和解码尾随字节做硬校验；字符串上限为 1 MiB，以保留现有大于 64 KiB 的能力 payload 语义。网络门禁必须覆盖这些 malformed packet，而不只验证 round-trip。
16. 本轮实际 VFX 审计发现客户端未知 `effect-id` 会创建 descriptor=nil 的静默空实例；现已改为抛出结构化 `:unknown-effect`。最终 VFX catalog 现在同时加载 `composites.edn` 与 `composites.edn`，并在 timeline 的 `{:at :node}` 包装内递归展开 composite；VFX graph 在展开后逐节点校验 descriptor，避免“descriptor 已注册但图仍含 composite”的假通过。
17. `:vfx/beam-bounds`、`:vfx/model-marker`、`:vfx/line` 等 VFX 运行时/结构标识不能登记为空 `:mid`，否则唯一展开器会把没有外部文档的节点替换为 nil；它们已改为 final primitive/structural descriptors。AC EDN coverage 已验证 9 tests / 25 assertions，包含无未展开 composite 的断言。
18. F3 的能力矩阵不再只是文档要求：`combat.final-engine/capability-matrix` 暴露 final component→neutral capability，combat headless 回归会逐项检查 action/query handler 闭合，并允许明确列出的 AC-owned query port（当前为 `:energy/target`）。当前 combat 测试为 31 tests / 56 assertions。
19. 二次复核发现并修正了一个真实的严格检查缺口：`flow/once` 的 callback descriptor 原先把 `body/on-first` 标成 sequential，导致 projectile 局部变量无法沿回调边界传播；最终 ABI 现在标成 closed，projectile scan 通过 descriptor 的 `:child-binds-locals` 显式传入 `:projectile`。同时修复了 `contains?` 作用于 lazy sequence 的检查器错误；节点核心回归现为 77 tests / 133 assertions。
20. 本轮再次直接审计执行器时发现 `:terrain/wave-plan` 虽然已经由 mcmod platform 暴露为 query，却被 final compiler 的 namespace 默认分支误判为 action；这会让 groundshock 等图拿不到 `:result`。现已把它加入显式 query ABI、query capability matrix，并用 fake host 回归验证不会进入 action 队列。
21. `flow/foreach` 的 descriptor 已声明 `:index-as`，但 runtime 只绑定 `:as`；现已在每次迭代同时写入值和索引局部变量，并加入执行回归。`flow/once` 也不再用单一全局布尔值：支持 `:last-key`/`:set`/`:boolean`、`storage-path`、`on-first` 与 session patch，覆盖 vec-reflection 的 projectile 去重语义。
22. Host action handler 的 apply 返回值原先被丢弃，导致 `:entity/mark` 返回的 VFX signal 无法发布；mcmod host 现在返回带 command/capability 的 `:results`，final engine 在 barrier 和最终 apply 两处把 `:vfx-signals/:feedback/:events` 合并进 outbox，并保留 `:action-results` 诊断记录。
23. VFX server/headless `spawn!` 对未知 effect-id 原先会在参数校验处产生非结构化空引用；现已在分配句柄前硬拒绝 `{:effect-id ...}` 的结构化错误。当前回归为 Combat 31 tests / 56 assertions；Node 77/133、VFX 16/45、mcmod 191/569 全部通过。
24. 多人 VFX 实例审计发现 final-client 原按 `effect-id/owner/world` 合并实例，忽略 `instance-key` 和 `event-seq`；同一玩家并发施放同类技能会互相覆盖，乱序 update 也可能回写旧参数。现按四元组定位实例、按严格递增序列丢弃重复/旧包，update 直接合并最终参数；replication 独立 `spawn!` 也在分配句柄前结构化拒绝未知 effect。回归覆盖并发 key、乱序 update 和未知 replication effect。
25. 进一步发现同一服务器 tick 内多个 VFX 操作不能共用 tick 号作为序列，否则 `spawn→update/destroy` 会被客户端误判为重复；Combat Core 现用 `tick * 1_000_000 + execution-order` 生成缺省序列，显式 `:event-seq` 仍优先，且对无 key 的瞬时节点生成 `[ability-id path activation-seq]`，避免瞬时音效/光束碰撞。
26. VFX 结构节点曾只有空字段 descriptor，编辑器无法获得真实 ABI；现已逐项声明 `beam-bounds/branch/group/let/line/model-marker/repeat` 的 inputs/outputs/children，仍由 final VFX sampler 执行，catalog 装配继续先展开 composite 再做 descriptor 校验。
27. 本次再次审计确认并已闭环网络实现：Combat 的 `vfx-publish` 按 `(owner,effect-id,instance-key,event-seq)` 保存 baseline，只把变化参数和 `:mask` 投影到 update，并丢弃乱序包；服务端 sinks 不再发送裸 signal，而是调用 mcmod `fixed-channel/encode-vfx-signal`，客户端 push handler 必须先 `decode-vfx-signal`，校验 protocol/version/type/length/ABI 后才进入 final-client。`vfx-trigger/spawn/update/destroy/clear-owner/snapshot` 均有固定 packet type。
28. 本次再次审计发现并已清除 AC 客户端 VFX 的最后一条旧旁路：`effect_controller` 不再维护 handlers、`:singleton` 聚合实例、level/hand enqueue 或按 effect-id 直接 `signal!`；所有 signal（包括相机/屏闪呈现侧读）统一经过 final-client 的 stable-key/event-seq dispatch。`COMBAT_VFX_PLATFORM_GAPS.md` 中 Batch 0-7/11 singleton 描述只作为历史审计资料，不再是当前生产架构或完成条件。
29. 本次复核又发现 `packet-types` 虽声明 `:combat-feedback`，但结果 push 仍发送通用 map；现已新增 `encode-combat-feedback/decode-combat-feedback`，服务端结果 sink 和客户端 notice handler 均改为 fixed binary packet，交易诊断字段不会越过 mcmod 边界。至此 catalog、CombatIntent、combat feedback、VFX 四类网络包都有实际固定协议调用。
30. 本次 ABI 回归又发现中立 `vfx-contract`/`combat-contract` 仍保留旧 `:signal` 操作，而最终资源和客户端使用 `:update`；现已统一允许 `spawn/update/trigger/destroy/clear-owner/snapshot`，客户端 final runtime 对 trigger/snapshot 做事件与 baseline 合并，旧 `:signal` 不再进入任何生产 contract。
31. 多人 replication 的 baseline/update 包使用 `instance-id` 而非 Combat 的 `instance-key`；contract 现允许二者任一作为实例身份，final-client 对远端句柄按 `instance-id` 路由，且 fixed-channel 有远端 snapshot round-trip 回归，避免 tracking replay 被错误拒绝。
32. 最后一次边界复核发现 destroy 包原先不强制实例身份，且远端 `instance-id` 路由没有校验 effect/owner/world；现已要求 destroy 携带 `instance-key` 或 `instance-id`，并在远端命中时校验 effect-id 及存在的 owner/world，避免 malformed 包串写实例。
33. 本次生产链路复核发现 `replay-persistent-signals!` 原先只有定义和单元测试，没有 server hook 调用；现由 AC `:on-server-tick-end!` 在非负整数 tick 的每 20 tick 调度一次，并从最终 Combat catalog 取得 VFX catalog 后走既有 fixed-channel broadcast sink。回归覆盖周期、非周期和非法 tick，避免持久/会话效果在 tracking enter 后只靠偶然事件恢复。
34. 本次 `--rerun-tasks` clean-path 审计发现 `dispatch-result-domain-events!` 的括号使 `reduce` 形成错误的单参数调用，增量构建未触发该问题；现已修正为标准 `[rf coll]` 调用。`lintClojureNative` 重跑通过（11 source roots），随后 `verifyCurrentPlatforms` 通过，确保这是生产修复而非仅缓存状态变化。
35. 本次最终代码审计又闭合三处边界：`audio-one-shot`/`audio-loop`/`camera` 已有显式 final lowering；`typed-vfx` 在 presentation adapter 中按 line/point-chain/ring/marker 规则有界落地，不再产生空计划；VFX fixed-channel 编解码统一执行 32 KiB 上限，并修复 payload 长度写入的 signed-short 溢出。VFX 回归为 16/45，mcmod 全量为 191/569，均为 0 failures / 0 errors。
36. 网络分层再次明确：`VfxPacket/VfxWireCodec` 只负责 Minecraft-free 的生命周期身份、方向和 catalog 控制面测试；真正跨 AC/loader 的完整参数、dirty-mask 和 payload 只允许经过 mcmod `fixed-channel`。两者不是第二套运行时传输协议，生产入口只有 fixed-channel sink/handler。

## 分阶段执行顺序

### F0：冻结边界与门禁

- 固定 `node-core` schema v1、StateTxnSet、IR/packet budget。
- 固定 `mcmod` 是所有 Minecraft/网络调用的唯一中转；neutral 模块禁止 `net.minecraft`。
- 增加依赖门禁：`node-core` 不得依赖 domain；`combat-core` 不得依赖 `vfx-core`；AC 才能组合二者。
- 退出条件：`node-core/checkClojure`、`mcmod/checkClojure`、依赖方向门禁通过。

### F1：固定协议与多人会话

- 完成 catalog hello/ack（protocol version + content hash + schema version）。
- 输入包固定为 `seq/control-id/edge/choice/client-tick`；服务端做单调序列去重、40 edges/20 ticks 限频。
- disconnect/death/dimension-change/gui-close 都调用同一个 `abort!`；客户端不上传技能图和资源快照。
- VFX 只发送 spawn/update/trigger/destroy/clear-owner/snapshot，使用 effect handle、anchor、dirty bit mask；trigger 是已存在实例上的事件，snapshot 是 tracking enter 的完整 baseline。
- 实现状态：catalog hello/ack 先于 CombatIntent；服务端已实现重复/乱序拒绝、40 edges/20 ticks 限频和登录/退出/停止清理，且无 MC 运行时依赖。`cn/li/mcbase/presentation/host_lifecycle` 已由 headless test-support 中转层补齐；重复包、乱序包、限频和生命周期中止均纳入当前 mcmod 191 tests / 569 assertions 的网络/中转门禁。

### F2：VFX final runtime

- `effect_schema` 为每个参数提供 type/mutability/quantization/default；效果图的几何 primitive 只允许 `line/quad/plasma-body`，而 `vfx-contract` 的 frame ABI 允许把采样结果投影为 `line/quad/billboard/particle/beam/ribbon/mesh` 等中立批次；音频/相机/post 仍走表现端口。
- `final-engine` 负责 spawn/update/tick/expire/destroy、state slots、render batch；`replication` 负责 recipient tracking 和 baseline replay。
- 将 AC `vfx-publish` 改为只消费 neutral catalog，不再调用旧 recipe/runtime。
- 当前已补齐 source graph、state-slots、生命周期、neutral draw/audio/camera/post 操作、dirty-mask、recipient baseline/replay，以及 Combat 的 `instance-key + event-seq` 与 replication 的 `instance-id + event-seq` 并发实例/乱序包语义的 headless 入口；AC server tick end 每 20 tick 触发持久/会话 baseline replay。VFX 节点类型由静态 vocabulary 注册，生产链路不依赖旧 runtime。客户端与 replication 对未知 effect 均硬拒绝，catalog 装配会递归展开并校验 timeline 包装内节点。
- 退出条件：VFX headless 全套测试通过，AC coverage 中至少一条 effect 从 signal 到 `draw-batch` op 可端到端采样，所有 packet 都能由固定协议表达；presentation adapter 只接收 render ops。

### F3：Combat final compiler/runtime

- 所有 node descriptor 完成 inputs/outputs/effects/type/range/default/doc；source/query/policy/action/vfx/feedback/flow 分类固定。
- 除 descriptor/schema 覆盖外，必须建立 `final component → capability → handler → apply result` 的可执行矩阵；只在 vocabulary 中注册、但没有 host action/query 的节点不得标记 ready。
- 编译期拒绝 query-after-mutation、未声明端口、类型不兼容、foreach 超预算、IR 超预算。
- `final-engine` 以 `StateTxnSet` 执行：先算纯图，HostCommand 全量 preflight，再 apply；只有 Host 成功才 commit owner state。
- damage/reaction 使用统一 `DamageEvent → collect → deterministic resolve → mcmod DamageBoundary`。
- 当前已补齐表达式/多作用域引用、session read/write、cost/cooldown/progression、VFX signal normalization、outbox 五通道和 owner/session 提交语义；静态 vocabulary、registry freeze 与 final compiler 已接入。
- 退出条件：combat final compiler/engine/damage 测试通过；AC 可以用 final program 执行至少一条 smoke ability，且结果包含可发布的 `:vfx-signals/:events/:feedback`。

#### F3 节点类型定义清单（最终 ABI）

节点定义唯一来源为 `ac/src/main/clojure/cn/li/ac/ability/final_vocabulary.clj`；每个 descriptor 必须包含 `:id/:revision/:layer/:category/:doc/:inputs/:outputs/:children`，primitive 额外必须有 `:impl`，mid/source 严禁 `:impl`；字段使用 `:type :any` 也必须显式列出，缺省值使用 `:default nil`，结构子节点使用 `:kind :single|:seq|:case-map`。词汇表分组如下：

- Flow：`flow/sequence`, `flow/branch`, `flow/foreach`, `flow/once`, `flow/phases`, `flow/control`, `flow/finish`, `data/bind`, `finalize`。
- Source/context：`ability/caster`, `ability/context`, `ability/budget`, `ability/tunable`, `ability/cooldown`, `ability/progression`, `session/read`, `session/write`, `owner/snapshot`, `owner/can-fly`。
- Query/target：`target/raycast`, `target/raycast-fan`, `combat/beam-strike`（内部 lowering 使用 `kernel/trace-beam`）, `target/entities`, `target/blocks`, `target/entity-snapshot`, `target/item-held`, `target/saved-location`, `target/resolve-destination`, `target/block-placement`, `target/directional-destination-query`, `terrain/wave-plan`、`target/*-destination` composites。
- Policy/resource：`cost/spend`, `cooldown/start`, `resource/add`, `resource/enforce-floor`, `score/mark`, `energy/charge`, `energy/target`, `damage/absorb`, `damage/critical`, `damage/multiply`, `damage/reduce`, `damage/reflect`。
- Combat/action：`combat/damage`, `combat/status`, `combat/impulse`, `combat/charged-area-damage`, `combat/projectile-reflection-scan`, `combat/area-damage`, `combat/beam-strike`, `terrain/apply-break-budget`, `combat/impact-strike`, `combat/teleport-group`, `entity/*`, `motion/*`, `projectile/*`, `inventory/*`, `block/*`, `world/*`。
- Feedback/VFX：`effect/vfx`, `domain/event`, `vfx/*`（arc/beam/ray/ring/particle/emitter/timeline/fade/repeat/group/branch、audio、camera、first-person、marker、scan、trajectory、vortex 等全部 descriptor 均固定在同一词汇表）。

为避免 `vfx/*` 这种省略造成漏实现，当前词汇表的 VFX primitive/structural ID 必须逐项覆盖：
`vfx/arc-field`, `vfx/arc-strike`, `vfx/audio-loop`, `vfx/audio-one-shot`, `vfx/beam`, `vfx/beam-arc-fade`, `vfx/billboard-sequence`, `vfx/block-progress`, `vfx/block-scan`, `vfx/camera`, `vfx/channel-arc`, `vfx/charge-ring`, `vfx/charge-slow`, `vfx/directional-wave`, `vfx/emitter`, `vfx/fade`, `vfx/first-person-motion`, `vfx/humanoid-marker`, `vfx/impact-burst`, `vfx/mark-sparks`, `vfx/particle-trail`, `vfx/ray-beam`, `vfx/ray-fan`, `vfx/ring`, `vfx/timeline`, `vfx/trajectory-ribbon`, `vfx/vortex-column`，以及结构节点 `vfx/beam-bounds`, `vfx/branch`, `vfx/group`, `vfx/let`, `vfx/line`, `vfx/model-marker`, `vfx/repeat`。最终 combat composite ID 为 `combat/area-damage`, `combat/beam-strike`, `terrain/apply-break-budget`, `combat/impact-strike`, `combat/teleport-group`；target composite ID 为 `target/hold-destination`, `target/penetration-destination`, `target/raycast-destination`。这些 ID 的 inputs/outputs/children/default/doc 以 `final_vocabulary.clj` 为唯一 ABI 来源，VFX source graph 必须在 catalog 装配时先展开再校验。

注册顺序是 vocabulary → validate → scope → `descriptor/freeze!`；任何运行期从图形推导 descriptor、未知字段自动放行或重新安装节点都视为失败。

### F4：AC catalog 与技能批迁移

- VFX catalog 先加载并生成 hash，再加载 combat source/registration。
- manifest 中禁止 `:overrides`；mine-ray/course 等 specialization 使用显式 `:bindings`（metadata/presentation/prerequisites/inputs）。
- 按批次迁移 39 source、50 registrations：source 节点只读显式 capability；中层 composite 只能是 EDN；技能 graph 最终产出 final IR。
- 每批必须保持覆盖计数 39/50/36，失败技能记录结构化 error 并在最终切换前清零。
- 机械迁移规则已落地到资源：`[:slot k]→[:local k]`；`:session/patch→:session/read + :session/write`；`:guard/resource→:cost/spend + flow/branch`；`:from/:tunable/:invariant` 改成 source node + local bind。VFX 中的 `:from/:to` 仅在曲线/插值语义下保留，并由 schema 明确区分。catalog 不再包含迁移函数。
- 退出条件：`final_catalog/assemble` 中所有 registration `:status :ready`，无 `:pending-final-node-migration`；资源门禁对上述旧结构返回 0 命中，而不是依赖运行时兼容解析。
- AC 启动投影必须把 registration 的 `:bindings/:metadata/:presentation` 先合并到 player-facing skill index；缺失的 category/level/controllable 只能从静态 progression definition 补齐，不能用它选择或执行 graph。固定测试必须验证 50 个 registration 均可写入 skill index。

### F5：AC 调度切换

- `ability/service/combat-runtime` 的 intent 入口已切到 `final-engine`，并已接入 neutral session provider/commit callback；pulse/event/damage 的 outbox、owner、生命周期通道已有 headless 覆盖，不能保留旧 VM 的旁路。
- AC 只负责 owner-state 投影、slot 解引用、capability port、命令提交和 packet sink；不再解析 component tree。combat-runtime 已将 owner 附加到结果并通过 outbox 发布 VFX/feedback/events。
- 将 VFX client effect controller 的硬编码 effect-id 分支迁移为 catalog descriptor/port dispatch（headless 入口已完成；实机渲染另开任务）。
- 退出条件：AC 相关静态检查和 headless tests 通过，final catalog report 的 pending 数固定为 0；资源迁移、schema 门禁和旧 combat/vfx namespace 的生产引用清理已完成。
- 网络退出条件：CombatIntent 只走 mcmod fixed-channel 的 `seq/control-id/edge/choice/client-tick`；服务端完成单调序列、限频和生命周期 abort 后才进入 final runtime，不能再从客户端 payload 接收 ability-id/目标/数值。

### F6：删除旧设计

- 删除 combat `vm.clj` 的旧解释路径、旧 `recipe.clj`、旧 combat/vfx composite loader、旧 `skill-runtime`、旧 `reactions/interception` 双解释器；保留 node-core 的 final compile-time composite expansion kernel，但不得保留运行时兼容 façade。
- 删除 vfx `vm.clj`、旧 runtime/recipe/composite 路径及无消费者组件；保留 `final-engine`、typed schema、replication、render-op adapter。
- 删除旧 manifest 字段和旧资源格式；不提供旧 EDN 自动转换或运行时 fallback。一次性迁移 runner 已删除，正式源码只保留 final EDN。
- 退出条件：`rg` 不再找到 combat/VFX 技能生产代码对旧 VM、旧 recipe、旧 composite loader 或兼容 façade 的引用；与 JEI 配方、GUI 迁移和其它非技能领域无关的 `compat`/`recipe-loader` 不属于本架构门禁；clean production build 能编译。

### F7：最终构建验收（不含实机）

- `:node-core:runNodeCoreClojureTests`
- `:mcmod:checkClojure`；随后运行 `:mcmod:compileTestClojure` 与 `:mcmod:runMcmodClojureTests`。`platform-src/test-support/.../host_lifecycle.clj` 已消除缺 namespace 的编译阻塞；当前全量 48 个命名空间为 191 tests / 569 assertions、0 failures / 0 errors。
- `:combat-core:runCombatClojureTests`
- `:vfx-core:runVfxClojureTests`（当前 16 tests / 45 assertions）
- `:ac:checkClojure` + `:ac:runAcEdnCoverageTests`（包含 39/50/36、旧结构硬拒绝、descriptor/schema/scope 装配断言）
- `verifyCurrentPlatforms` 或等价静态门禁；必要时使用 `--rerun-tasks` 验证 clean path。
- 记录测试计数、编译警告、未完成实机测试项；实机任务单独创建，不在本任务中伪造通过。

## 当前可执行检查清单

1. 先运行 `:ac:runAcEdnCoverageTests` 的结构门禁；它递归解析 EDN 并归零旧结构、`:overrides` 与非 `:final` ability，同时验证 final catalog 的 50 个 player-facing metadata registration 可写入技能索引，而不是依赖字符串子串。
2. 对每个 composite manifest 做 load → input substitution → local rename → recursive expansion → descriptor validation；禁止生产 catalog 只保存未展开的 `:graph`。
3. 对每个 final registration 运行 `compile-program`，同时检查 node descriptor parity、schema export completeness、scope/implicit dependency、IR/iteration budget。
4. 用 fake mcmod host 验证 query barrier、StateTxn 原子提交、session read/write、VFX signal、feedback/event 五条结果通道；不启动 Minecraft。
5. 用 VFX fake client 验证 spawn/update/destroy、transient expire、session/persistent replay、dirty-mask packet 和 graph→draw-batch/audio/camera 操作。
6. 通过模块测试后运行 `cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace`；该总门禁已在本轮 composite/test-support、协议 malformed 校验、VFX catalog 和作用域 ABI 修改后重跑并通过。AC 全量 test source 仍受既有 `panel_reactive`/`console_reactive`/`skill_runtime` 支撑与 focused classpath 影响；这属于另开的历史测试迁移任务，不改变已通过的 production neutral compile、combat/vfx tests、mcmod headless tests 和 AC EDN coverage。

## 可直接执行的批次与判定

按以下顺序执行；任一生产编译/静态门禁失败就停止内容迁移，先修复架构问题。测试失败必须标明是生产回归、测试支撑缺失还是历史断言漂移，不能用“未做实机测试”掩盖前两类问题。

```text
1. cmd /c gradlew.bat :node-core:checkClojure :mcmod:checkClojure :combat-core:checkClojure :vfx-core:checkClojure :ac:checkClojure --stacktrace
2. cmd /c gradlew.bat :node-core:runNodeCoreClojureTests :combat-core:runCombatClojureTests :vfx-core:runVfxClojureTests --stacktrace
3. cmd /c gradlew.bat :ac:runAcEdnCoverageTests --stacktrace
4. cmd /c gradlew.bat :mcmod:compileTestClojure --stacktrace
5. cmd /c gradlew.bat :mcmod:runMcmodClojureTests --stacktrace
6. cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace
```

第 1 步是最终生产加载/编译门；第 2、3 步分别锁定 node/composite、战斗/VFX 执行器和真实 EDN catalog；第 4、5 步验证 mcmod headless 测试 classpath 以及网络/中转契约；第 6 步验证依赖方向、旧架构残留、网络边界、VFX 边界和平台镜像。`:ac:compileTestClojure` 仍属于历史测试迁移任务；若它失败，必须单独标记为测试支撑问题，不得改写为生产架构失败。

## 最终交付不变量

- 服务器权威：客户端只发送输入边沿，不能决定 ability-id、目标、伤害、资源或 VFX recipients。
- 原子提交：Host preflight 失败时不提交 StateTxn；世界副作用不回滚，但结果必须报告 partial apply。
- 网络稳定：catalog hash 不一致拒绝执行；VFX update 使用 dirty mask，session/persistent 在 tracking enter、周期 replay 或显式 snapshot 时补发。
- 依赖稳定：`node-core ← combat-core/vfx-core ← ability-runtime ← AC/BC/CC`；Minecraft 和网络能力由 mcmod 提供，组合层只通过明确端口使用。
- 内容完整：最终 catalog 仍覆盖 39 source、50 specialization、36 effect；没有旧格式 fallback。


# 历史方案（已被 final graph 迁移实现取代）

本文保留为审计记录，不是当前执行入口。当前唯一执行路径与节点命名以
`COMBAT_CORE.md`、`ABILITY_MIGRATION_MATRIX_CN.md` 及代码中的 final catalog 为准；
其中出现的 `target/beam`、`target/beam-trace`、`terrain/propagate`、旧 VM 和旧迁移字段均不得重新引入。
