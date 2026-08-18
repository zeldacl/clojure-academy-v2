# VFX Core 维护手册

## 系统职责

`:vfx-core` 是特效实例的生命周期运行时：per-instance `spawn!`/`signal!`/`destroy!`，`instance-key` 幂等去重、`event-seq` 排序、tombstone 防止已销毁实例复活、seed 保证跨客户端确定性表现、`bounds` 驱动距离/LOD 剔除。它独占"实例/种子/tombstone/生成销毁"这组职责；模板/绑定/输入/布局/挂载/失效是 presentation-core 的职责，两者仍然互不依赖，各自只依赖 `:mcmod`（详见 [PROJECT_LAYOUT.md](../01-overview/PROJECT_LAYOUT.md)）。

## 模块边界

- `vfx-core/src/main/clojure/cn/li/vfx/runtime.clj`：唯一的实例运行时实现——注册表冻结、`:lifecycle`（`:transient`/`:persistent`/`:singleton`，见下"实例模型"）、per-instance 状态机、`tick!`/`sample-frame!` 采样管线、`:destroy` 钩子（`run-destroy-hook!`，实例被销毁的全部路径统一触发一次）。
- `vfx-core/src/main/clojure/cn/li/vfx/random.clj`：确定性随机数工具，供 seed 驱动的表现使用。
- `ac/src/main/clojure/cn/li/ac/client/effect_controller.clj`：AC 侧安装点——`register-effect!` 登记 descriptor，`dispatch-signal!` 是 combat-core VFX 信号进入 vfx-core 的入口，`sample-frame!`/`sample-hand!` 把采样结果喂给 Presentation 帧合并（见 `register.clj` 的 `merge-vfx-passes`）。
- `ac/src/main/clojure/cn/li/ac/ability/client/fx_spec.clj` + `arc_beam.clj` + `arc_beam/impl/*.clj`：具体特效内容的声明式门面与实现，`def-arc-beam-fx`/直接 `fx-spec/register!` 是两种登记方式（详见 [COMBAT_CORE.md](COMBAT_CORE.md) 的 `verifyAbilityVfxRegistryCoverage` 说明）。

## 运行时流程

combat-core 的 `:vfx` op 产出携带 `[:combat owner activation-key effect-id]` 形态 `instance-key` 的信号 → `MSG-COMBAT-RESULT` 网络消息 → `combat_vfx_adapter/dispatch-result!` → `effect_controller/dispatch-signal!`。这个函数按信号目标效果**声明的 `:lifecycle`** 分派（见下方"实例模型"）——`:transient` 效果直接走 vfx-core 自己基于 `instance-key`/`event-seq`/tombstone 的幂等分派（`core/dispatch-signal!`）；`:singleton` 效果仍走 `core/instance-for-effect` 查到 aggregate 实例后调 `core/signal!`，绕开 vfx-core 的幂等分派。

**channel 传输路径已删除**（2026-08-17，`b284185a2`，见 [COMBAT_VFX_PLATFORM_GAPS.md](COMBAT_VFX_PLATFORM_GAPS.md) E 节 P1.3）：`register-channel!`/`dispatch-channel!`/`freeze-channels!` 连同 `effect_controller.clj`/`fx_spec.clj` 的封装一起删掉了——核实过全仓库没有任何技能内容真正给 `:channels` 塞过一个带 `:topic` 的条目。`fx_spec.clj`/`arc_beam.clj` 的 `build-spec` 仍然容忍并忽略调用方传入的 `:channels` 键（不少 impl 文件还留着这个键，纯粹是历史遗留的死配置，不影响功能），但不再有任何投递机制读它。

无论哪种 lifecycle，`tick!`/`sample-frame!` 都是同一套采样管线：`tick!` 推进每个实例的状态机（`update`/`priority`），`sample-frame!` 对可见实例（`bounds` 剔除后）插值取样，产出按 stage 分桶的 batch，交给 `register.clj` 的 `merge-vfx-passes` 并入同一个 Presentation `FramePacket`。

## 实例模型（P1.1/P1.2 迁移，2026-08-18）

三种 `:lifecycle`（`register-effect!` 的 `:or {lifecycle :singleton}`，`vfx-core/runtime.clj` 的 `lifecycles` 集合）：

- **`:transient`**——一次性战斗特效，一个 (owner, 一次施法) 对应一个真实的 vfx-core 实例，走 `instance-key`/`event-seq`/tombstone 的正规幂等分派。**26 个 `arc_beam/impl/*.clj` 效果里 24 个已迁移到这个形态**（`vec_deviation`/`mag_movement`/`light_shield`/`mark_teleport`/`penetrate_teleport`/`flashing`/`groundshock`/`electron_bomb`/`flesh_ripping`/`vec_accel`/`blood_retrograde`/`ray_barrage`/`directed_blastwave`/`threatening_teleport`/`shift_teleport`/`directed_shock`/`mag_manip`/`meltdowner`/`mine_detect`/`jet_engine`/`railgun_shot`/`thunder_clap`/`plasma_cannon`/`current_charging`）。剩下 2 个（`rad_intensify_mark`/`teleporter_crit`）故意留在 `:singleton`——它们的触发机制完全依赖已删除的死 channel 总线，在设计出新触发机制之前迁移它们没有意义。
- **`:persistent`**——按 world-id + 位置/BE 身份索引，机器/方块挂载特效用；目前还没有效果声明这个值，是为将来准备的。
- **`:singleton`**（默认值）——每个 effect-id 一个 aggregate 实例，owner 维度塞在实例内部自己的 map 里，`dispatch-signal!` 绕开 vfx-core 的幂等分派直接灌给这一个实例。除了上面两个故意保留的效果，**还有 9 个效果结构性地仍是这个形态，从未被 P1.1/P1.2 这轮迁移覆盖过**（Batch 7 审计时发现，不在原计划的 26 个之内）：
  - `scatter_bomb`/`mine_ray`/`electron_missile`/`storm_wing`/`vec_reflection`——直接调 `fx-spec/register!`，不经过 `arc_beam.clj`；这 5 个在 P1.1/P1.2 启动前就已经明确排除在外，另开批次处理。
  - `body_intensify`/`location_teleport`——`build-spec` 的 `:runtime :none`，没有 `:level`/`:hand` 状态机，`:lifecycle` 对它们没有实际意义（没有 owner-map 可拍平）；触发完全靠已死的 `:channels` `:immediate-fn`，今天不产生任何客户端表现。
  - `arc_gen`/`thunder_bolt-strike`——没有自己的 `arc_beam/impl/*.clj`，走 `arc_beam.clj` **自己的通用默认渲染器**（`build-arc-plan`/`tick-arc-state!`/`ensure-arc-store` 等，`:sound-id`/`:arc-life`/`:arc-pattern` 之类的 opts 驱动）。这个默认渲染器本身还是 owner-map 形状，P1.1/P1.2 全程没有改动过它——要迁移这两个效果，需要先迁移 `arc_beam.clj` 自己的默认实现，这是比"改一个 impl 文件"更大的改动，没有排期。

`event-seq` 的作用域问题（曾经是 aggregate 桥接存在的理由之一：`event-seq` 是 per-client-session，不是全局单调，直接复用会在多玩家场景下把另一玩家更低的 event-seq 误判为过期丢弃）**对已迁移的 24 个 `:transient` 效果不成立**——每个实例有自己独立的 `instance-key`，`event-seq`/tombstone 比较范围就是这一个 `instance-key` 自己的历史，不会跨玩家碰撞。这个顾虑只对仍然共享一个 aggregate 实例的 `:singleton` 效果有意义。

## 扩展点

- 新增特效：`arc_beam/impl/` 下加一个 impl 文件，`def-arc-beam-fx :your-effect-id` 登记，`build-spec` 传 `:lifecycle :transient`（除非有正当理由要用 aggregate 单例）；或直接 `fx-spec/register!` 一个自定义 descriptor（需要 `:level`/`:hand` 分支）。
- 新增 op → VFX 信号映射：在 `combat_content.clj` 的技能 skill-spec 里加 `{:op :vfx :effect-id :your-effect-id ...}`，**effect-id 必须与客户端注册的完全一致**——不一致会被 `verifyAbilityVfxRegistryCoverage` 挡住（[COMBAT_CORE.md](COMBAT_CORE.md)）。**在写 impl 文件的 case 分支之前，先去 `combat_content.clj` 核实这个技能实际会发哪些 `:event`、`:params` 里真的有什么字段**——P1.1/P1.2 这轮迁移反复发现 impl 文件的 case 分支是按"设计时以为会发的事件"写的，跟 `combat_content.clj` 实际发送的往往对不上（很多技能的 `:vfx` 步骤只在一个阶段发一次、`:params` 是字面量而非真实位置/目标数据），不核实就无法判断一段渲染代码是否真的会执行。
- `:hand` 轨道的 `transform-fn`/`:level` 轨道之外，如果一个回调需要在采样上下文之外（比如 HUD 渲染帧、不经过 `sample-plan!`/`sample-hand!` 的地方）读某个特定 owner 的实例状态，用 `effect_controller.clj` 的 `instance-for-owner`（读）/`update-state-for-owner!`（写）——`core/instance-for-effect` 只会返回"随便一个匹配 effect-id 的实例"，一旦同一 effect-id 有多个并发的 `:transient` 实例（比如两个玩家同时用同一个技能）就会读/写错人。

## 排障手册

- 客户端日志 `unknown VFX effect` → combat-core 发出的 `:effect-id` 未注册；检查 `combat_content.clj` 与对应 `arc_beam/impl/*.clj`/直接注册文件的 id 是否一致。
- 一个 `:transient` 效果的实例好像永远不消失（`@(:instances runtime)` 只增不减）→ 检查这个效果的 `tick-state-fn`（对 `:hand`-only 效果同理）是不是真的会在该结束的时候返回 `nil`——`effect_controller.clj` 的 descriptor `:update` 只在 `:level`/`:hand` 都变 `nil` 时才会让整个实例返回 `nil`（vfx-core `tick!` 只认这个信号），一个从来不返回 `nil` 的 track 会让整个实例卡住到玩家断线。
- 同一技能连续释放，特效行为像是 tick 了两次 → 确认这个效果的 `:lifecycle` 是不是 `:singleton`；`:singleton` 效果仍然可能撞上 aggregate/per-instance 双跑的旧问题，`:transient` 效果不会。
- 特效在远处不渲染 → 先看 descriptor 的 `:bounds` 是不是返回了 `nil`（等价于永不可见，不是"总是可见"）。
- 一个新写的 impl 文件的渲染逻辑看起来正确但实际没有任何视觉效果 → 先核实 `combat_content.clj` 是否真的发送了这段代码期望的 `:event`/`:params`，不要假设 case 分支和真实信号对得上（见上方"扩展点"）。

## 变更风险

- vfx-core 不得直接依赖 presentation-core（`verifyVfxDependencyDirection`）；两者的唯一交汇点是 AC 侧的 `register.clj`/`effect_controller.clj`，把 vfx-core 采样出的 batch 转成 `RenderCommand$Batch` 并入 Presentation 帧。
- `verifyVfxDirectHostBoundary` 要求 vfx-core 自己的 Host 安装（`ac.client.vfx-host`，负责 tick!/fov-offset/hand transform）与 Presentation 的 bridge 分开安装，不能把 vfx-core 的 host API key 重新导出到 presentation bridge 里。
- `verifyVfxSingleTickPath`：每帧只允许一条 tick 路径驱动 vfx-core 的实例状态机，不要在多个地方各调一次 `tick!`。

## 兼容性约束

- vfx-core 只依赖 `:mcmod`，不引用 presentation-core/combat-core/AC 的具体类型。
- `verifyVfxAotBoundary`：vfx-core 当前不是全量 AOT（`fullAot=false`），改变这个假设前先确认门禁期望。
