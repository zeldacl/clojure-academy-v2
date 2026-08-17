# VFX Core 维护手册

## 系统职责

`:vfx-core` 是特效实例的生命周期运行时：per-instance `spawn!`/`signal!`/`destroy!`，`instance-key` 幂等去重、`event-seq` 排序、tombstone 防止已销毁实例复活、seed 保证跨客户端确定性表现、`bounds` 驱动距离/LOD 剔除。它独占"实例/种子/tombstone/生成销毁"这组职责；模板/绑定/输入/布局/挂载/失效是 presentation-core 的职责，两者仍然互不依赖，各自只依赖 `:mcmod`（详见 [PROJECT_LAYOUT.md](../01-overview/PROJECT_LAYOUT.md)）。

## 模块边界

- `vfx-core/src/main/clojure/cn/li/vfx/runtime.clj`：唯一的实例运行时实现——注册表冻结、per-instance 状态机、`tick!`/`sample-frame!` 采样管线、channel 传输（见下）。
- `vfx-core/src/main/clojure/cn/li/vfx/random.clj`：确定性随机数工具，供 seed 驱动的表现使用。
- `ac/src/main/clojure/cn/li/ac/client/effect_controller.clj`：AC 侧安装点——`register-effect!` 登记 descriptor，`dispatch-signal!` 是 combat-core VFX 信号进入 vfx-core 的入口，`sample-frame!`/`sample-hand!` 把采样结果喂给 Presentation 帧合并（见 `register.clj` 的 `merge-vfx-passes`）。
- `ac/src/main/clojure/cn/li/ac/ability/client/fx_spec.clj` + `arc_beam.clj` + `arc_beam/impl/*.clj`：具体特效内容的声明式门面与实现，`def-arc-beam-fx`/直接 `fx-spec/register!` 是两种登记方式（详见 [COMBAT_CORE.md](COMBAT_CORE.md) 的 `verifyAbilityVfxRegistryCoverage` 说明）。

## 运行时流程（两条并存的信号投递路径）

1. **combat-core 直投路径（主路径，Step 6 起）**：combat-core 的 `:vfx` op 产出携带 `[:combat owner activation-key effect-id]` 形态 `instance-key` 的信号 → `MSG-COMBAT-RESULT` 网络消息 → `combat_vfx_adapter/dispatch-result!` → `effect_controller/dispatch-signal!`。该函数**绕开了 vfx-core 自身基于 `instance-key` 的幂等分派**，直接经 `core/instance-for-effect` 查到 aggregate 实例后调 `core/signal!`——原因见下方"实例模型"一节。
2. **channel 传输路径（仍然存活，未被 Step 6 移除）**：`fx_spec.clj` 在特效登记时调 `vfx/register-channel!` 声明 `:fx-topic`/`:topic`；`dispatch-channel!` 是这条路径的投递入口。这条路径与路径 1 并存，尚未合并成单一投递机制——**这是已知的架构遗留，不是本次重构的完成状态**。
3. 无论走哪条路径，`tick!`/`sample-frame!` 都是同一套采样管线：`tick!` 推进每个实例的状态机（`update`/`priority`），`sample-frame!` 对可见实例（`bounds` 剔除后）插值取样，产出按 stage 分桶的 batch，交给 `register.clj` 的 `merge-vfx-passes` 并入同一个 Presentation `FramePacket`。

## 实例模型（P0-3 修复，Step 6）

`effect_controller.clj` 曾经每个 effect-id 只维护一个 `::aggregate` 单例，owner 维度塞在单例内部的 map 里；vfx-core 自己的模型是 per-instance（`instance-key` = 一个 owner 的一次激活）。两者曾经同时生效，导致同一 combat 信号会在 aggregate 单例和 vfx-core 自己新建的实例上各跑一次 `tick`。

修复方式**不是**让 aggregate 消失，而是让 `dispatch-signal!` 对 `:spawn`/`:signal` 绕开 vfx-core 的 `instance-key` 幂等分派，直接把信号灌给已存在的 aggregate 实例——理由是 `MSG-COMBAT-RESULT` 是可靠有序推送，不是需要 vfx-core 自带去重的有损/可重放传输。这是一个务实的桥接，不是把 combat-core 迁移到真正的 per-instance 模型；如果未来要做后者，需要先确认 `event-seq` 的作用域（当前是 per-client-session，不是全局单调，直接复用会在多玩家场景下把另一玩家更低的 event-seq 误判为过期而丢弃，这是评估过并放弃的方案）。

## 扩展点

- 新增特效：`arc_beam/impl/` 下加一个 impl 文件，`def-arc-beam-fx :your-effect-id` 登记；或直接 `fx-spec/register!` 一个自定义 descriptor（需要 `:level`/`:hand` 分支）。
- 新增 op → VFX 信号映射：在 `combat_content.clj` 的技能 skill-spec 里加 `{:op :vfx :effect-id :your-effect-id ...}`，**effect-id 必须与客户端注册的完全一致**——不一致会被 `verifyAbilityVfxRegistryCoverage` 挡住（[COMBAT_CORE.md](COMBAT_CORE.md)）。

## 排障手册

- 客户端日志 `unknown VFX effect` → combat-core 发出的 `:effect-id` 未注册；检查 `combat_content.clj` 与对应 `arc_beam/impl/*.clj`/直接注册文件的 id 是否一致。
- 同一技能连续释放，特效行为像是 tick 了两次 → 怀疑又出现了 aggregate/per-instance 双跑，参照上面"实例模型"一节排查 `dispatch-signal!` 是否被绕过。
- 特效在远处不渲染 → 先看 descriptor 的 `:bounds` 是不是返回了 `nil`（等价于永不可见，不是"总是可见"）。
- VFX 信号送不到客户端 → 先确认信号走的是哪条路径（combat-core 直投 vs channel），两条路径的失败模式不同，见上方"运行时流程"。

## 变更风险

- vfx-core 不得直接依赖 presentation-core（`verifyVfxDependencyDirection`）；两者的唯一交汇点是 AC 侧的 `register.clj`/`effect_controller.clj`，把 vfx-core 采样出的 batch 转成 `RenderCommand$Batch` 并入 Presentation 帧。
- `verifyVfxDirectHostBoundary` 要求 vfx-core 自己的 Host 安装（`ac.client.vfx-host`，负责 tick!/fov-offset/hand transform）与 Presentation 的 bridge 分开安装，不能把 vfx-core 的 host API key 重新导出到 presentation bridge 里。
- `verifyVfxSingleTickPath`：每帧只允许一条 tick 路径驱动 vfx-core 的实例状态机，不要在多个地方各调一次 `tick!`。

## 兼容性约束

- vfx-core 只依赖 `:mcmod`，不引用 presentation-core/combat-core/AC 的具体类型。
- `verifyVfxAotBoundary`：vfx-core 当前不是全量 AOT（`fullAot=false`），改变这个假设前先确认门禁期望。
