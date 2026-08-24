# 最终技能图引擎重构执行计划（修订版）

本计划是对 `ac-combat-core-combat-core-vfx-core-ac-mossy-wren` 的执行版修订。目标是最终只保留一套图语言、战斗执行器、VFX 执行器和 mcmod 中转边界；旧 VM、旧 recipe、旧 composite、旧反应解释器只允许在迁移阶段存在，最终必须删除。

## 已确认的矛盾与遗漏

1. 原计划把“旧 VM 删除”写成可直接执行，但当前 AC 仍由 `combat-catalog`、`combat-runtime`、`skill-runtime`、`vfx-publish` 的旧路径驱动；在所有技能没有落到 `final-compiler` 前删除会造成运行时无可执行技能。正确顺序是“最终 catalog → 分批 final compile → AC 调度切换 → 删除旧路径”。
2. 原计划保留 `combat-core → vfx-core`，但用户要求最终依赖方向清晰。现在 Combat 只产生中立 VFX signal，AC 组合 VFX catalog，已移除该直接边；计划中的验证门禁必须更新为禁止该边。
3. 原计划把 catalog hash、输入边沿、VFX 状态同步混在一起。正确分层是：固定字节协议（mcmod）只传 catalog hello/ack、输入 edge、feedback、VFX spawn/update/destroy；技能图、资源值和世界查询永不下发。
4. 原计划写了“VFX catalog/replication”，但缺少 VFX 执行引擎。现已补充 typed effect instance runtime；仍需把旧 `vfx/runtime.clj` 的平台表现接线迁移到该 runtime。
5. “不做实机测试”与“实机验收”混在原计划中。此次验收只要求静态检查、Clojure 编译、headless fake-mcmod 测试；实机渲染、多人可见性和数值回归另开任务，不阻塞本次交付。
6. 当前 final catalog 可以读取并保留 39 个 combat source、50 个 specialization、36 个 VFX effect，但部分旧 graph 仍标记 `:pending-final-node-migration`；这不是兼容层，必须在最终切换前全部变为 final IR。
7. catalog 内容哈希必须使用跨平台稳定的 canonical 排序，不能用混合 keyword/string 键的默认 `sorted-map`；该问题已在 AC 目录中修复。

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
- VFX 只发送 spawn/update/destroy/clear/snapshot，使用 effect handle、anchor、dirty bit mask。
- 退出条件：headless session 测试覆盖重复包、乱序包、限频和生命周期中止；无 MC 运行时依赖。

### F2：VFX final runtime

- `effect_schema` 为每个参数提供 type/mutability/quantization/default；只允许 `line/quad/plasma-body` 几何 primitive，音频/相机/post 走表现端口。
- `final-engine` 负责 spawn/update/tick/expire/destroy、state slots、render batch；`replication` 负责 recipient tracking 和 baseline replay。
- 将 AC `vfx-publish` 改为只消费 neutral catalog，不再调用旧 recipe/runtime。
- 退出条件：VFX headless 全套测试通过，所有 packet 都能由固定协议表达；presentation adapter 只接收 render ops。

### F3：Combat final compiler/runtime

- 所有 node descriptor 完成 inputs/outputs/effects/type/range/default/doc；source/query/policy/action/vfx/feedback/flow 分类固定。
- 编译期拒绝 query-after-mutation、未声明端口、类型不兼容、foreach 超预算、IR 超预算。
- `final-engine` 以 `StateTxnSet` 执行：先算纯图，HostCommand 全量 preflight，再 apply；只有 Host 成功才 commit owner state。
- damage/reaction 使用统一 `DamageEvent → collect → deterministic resolve → mcmod DamageBoundary`。
- 退出条件：combat final compiler/engine/damage 测试通过；AC 可以用 final program 执行至少一条 smoke ability。

### F4：AC catalog 与技能批迁移

- VFX catalog 先加载并生成 hash，再加载 combat source/registration。
- manifest 中禁止 `:overrides`；mine-ray/course 等 specialization 使用显式 `:bindings`（metadata/presentation/prerequisites/inputs）。
- 按批次迁移 39 source、50 registrations：source 节点只读显式 capability；中层 composite 只能是 EDN；技能 graph 最终产出 final IR。
- 每批必须保持覆盖计数 39/50/36，失败技能记录结构化 error 并在最终切换前清零。
- 退出条件：`final_catalog/assemble` 中所有 registration `:status :ready`，无 `:pending-final-node-migration`。

### F5：AC 调度切换

- 将 `ability/service/combat-runtime` 的 dispatch/pulse/event/damage 入口切换到 `final-engine`。
- AC 只负责 owner-state 投影、slot 解引用、capability port、命令提交和 packet sink；不再解析 component tree。
- 将 VFX client effect controller 的硬编码 effect-id 分支迁移为 catalog descriptor/port dispatch。
- 退出条件：AC 相关静态检查和 headless tests 通过，旧 runtime 入口无生产引用。

### F6：删除旧设计

- 删除 combat `vm.clj` 的旧解释路径、旧 `recipe.clj`、旧 composite loader、旧 `skill-runtime`、旧 `reactions/interception` 双解释器；保留的文件名若必要也必须是 final implementation，而非兼容 façade。
- 删除 vfx `vm.clj`、旧 runtime/recipe/composite 路径及无消费者组件；保留 `final-engine`、typed schema、replication、render-op adapter。
- 删除旧 manifest 字段和旧资源格式；不提供旧 EDN 自动转换或运行时 fallback。
- 退出条件：`rg` 不再找到生产代码对旧 VM/recipe/compat 的引用；clean build 能编译。

### F7：最终构建验收（不含实机）

- `:node-core:runNodeCoreClojureTests`
- `:mcmod:checkClojure` + input session tests
- `:combat-core:runCombatClojureTests`
- `:vfx-core:runVfxClojureTests`
- `:ac:checkClojure` + catalog/coverage tests
- `verifyCurrentPlatforms` 或等价静态门禁；必要时使用 `--rerun-tasks` 验证 clean path。
- 记录测试计数、编译警告、未完成实机测试项；实机任务单独创建，不在本任务中伪造通过。

## 最终交付不变量

- 服务器权威：客户端只发送输入边沿，不能决定 ability-id、目标、伤害、资源或 VFX recipients。
- 原子提交：Host preflight 失败时不提交 StateTxn；世界副作用不回滚，但结果必须报告 partial apply。
- 网络稳定：catalog hash 不一致拒绝执行；VFX update 使用 dirty mask，session/persistent 只在 tracking enter 或 snapshot 时补发。
- 依赖稳定：`node-core ← combat-core/vfx-core ← AC`，Minecraft 和网络只在 mcmod。
- 内容完整：最终 catalog 仍覆盖 39 source、50 specialization、36 effect；没有旧格式 fallback。

