# Combat Core / VFX Core 平台缺口工单

## 这是什么

这是一份**未完成工作清单**，不是系统架构文档（架构说明见 [COMBAT_CORE.md](COMBAT_CORE.md)、[VFX_CORE.md](VFX_CORE.md)）。记录的是 2026-08-17 一轮 combat-core/vfx-core/presentation-core 执行会话中发现、但因为需要新写 touch 真实 Minecraft API 的平台代码、或需要本仓库里找不到答案的游戏设计判断，因而**明确推迟未做**的条目。每一条都已经定位到具体文件和函数，接手时不需要重新排查"这一层到底有没有 wired"——已经排查过了，结论写在下面。

**为什么专门写一份工单，不直接在代码里留 TODO**：这些条目的完成度评估在执行过程中被反复推翻——每次以为"这一层应该已经接好了"，深入一层就发现还差一截（host-port 从未安装、`now-tick` 恒为 nil、`validate-host-api` 恒失败、11 个 world-effect 执行器整体缺失）。把最终核实过的结论集中写下来，比散在各处的 TODO 更不容易被下一次"看起来已经完成了"的误判绕过。

## 已经做完、不在本工单范围内的部分

以下问题**已在 2026-08-17 修复并提交**，不要重复排查：

| 提交 | 内容 |
|---|---|
| `dec882c40` | `combat_runtime.clj` 的 `now-tick` 恒为 nil 导致每次 combat intent NPE；`tick!` 的 deadline-queue 跳 tick 永久滞留；`compile-all!` 的 content-hash 对自定义 node 不稳定 |
| `d72b1695f` | `:query` op 新增 `:query-returned-nil` 诊断 feedback |
| `f49297f2e` | vfx-core `tick!` 的 `swap!` 内副作用导致 CAS 重试重复记录故障 |
| `b97a2ad0c` | vfx-core `:bounds`/`:visible?` 从 ArityException 双签名分派收敛为单签名 |
| `db831d963` | mcmod `validate-host-api` 恒拒绝任何合法 host-api（`:schema-version` 被塞进自己的 `ifn?` 完整性检查）；VFX ABI 拆分必需/可选操作 |
| `0532dbf7a` | presentation-core 删除死的 `VFX/CAMERA/FIRST_PERSON/POST_PROCESS` HostKind |
| `b72f0555c` | presentation-core `input/dispatch!` 改为返回真实 EventResult，删除冗余重注册 |
| （见下方 C 节） | `execute-thunder-clap!`/`execute-blood-retrograde!`/`execute-plasma-cannon!`/`execute-meltdowner!` 四个 world-effect 执行器 |

## 分类清单

### A. 传送类：approval-token 平台能力整个不存在（6 个技能）

**受影响技能**：`mark-teleport`、`penetrate-teleport`、`shift-teleport`、`threatening-teleport`、`flashing`、`location-teleport`。

**现状**：`combat_content.clj` 里这 6 个技能的 `:release` 阶段都会产出 `:teleport-approved-target`（前 5 个）或 `:teleport-approved`（`location-teleport`）world-effect。`combat_runtime.clj:1142-1181` 已经写好了这两个 world-effect 的处理分支，调用 `cn.li.mcmod.platform.teleportation/teleport-approved-target!` / `teleport-approved-location!`。

**缺口**：这两个平台操作**在全仓库任何 loader 里都未安装**。`platform-src/minecraft/base/src/main/clojure/cn/li/mcbase/runtime/teleportation_core.clj` 的 `create-teleportation` 工厂函数只导出 `:teleport-player!`、`:teleport-with-entities!`、`:reset-fall-damage!`、`:get-player-position`、`:get-player-dimension` 五个 key，没有 `:teleport-approved-target!`/`:teleport-approved-location!`。调用即抛 `"Required teleportation operation is not installed"`（被 `execute-world-effects!` 的 try/catch 兜住，降级为 `:status :failed`，服务端不崩，但玩家资源已经先扣了，技能悄无声息地失效）。

**query 侧同样缺失**：`combat_runtime.clj` 的 `default-query-port` 里 `:teleport-target`（5 种 `:mode`：`:mark`/`:penetrate`/`:shift`/`:threatening`/`:flashing`）和 `:saved-location` 都是 `(when-let [host-query (contract/host-port :query)] ...)`，host-port 从未安装，恒返回 nil。

**要做的事**：
1. 设计一个"approval-token"机制：query 阶段算出候选目的地后签发一个 opaque token（可以是一个本地 map 存储 `token -> {:world-id .. :x .. :y .. :z ..}`，短 TTL），返回给 combat-core 的 `:destination` ref；world-effect 阶段凭 token 兑现，做碰撞/维度合法性校验后真正传送。这个设计不需要凭空发明——`teleport_approved_location!`/`teleport_approved_target!` 的函数签名（`owner ability-id approval-token mode`）已经暗示了这个模式，只是从未实现。
2. 在 `mcbase/runtime/teleportation_core.clj` 新增 `teleport-approved-target!`/`teleport-approved-location!`，可以复用已经验证过的 `teleport-player!`（内部用 `TeleportAccess/teleportPreservingRotation`，已处理乘客/维度切换）。
3. 同步 3 个 MC 版本的转发文件（`platform-src/minecraft/mc-1.20.1/.../teleportation_core.clj`、`mc-1.21.1`、`mc-26.2`——目前都是对 `mcbase` 同名函数的 `def` 别名，加新 key 后同步加别名即可，机械操作）。
4. `combat_runtime.clj` 的 `default-query-port` 补齐 `:teleport-target`（5 种 mode 的候选目标搜索逻辑）与 `:saved-location`（读已保存位置）。

**风险**：touch 真实 Minecraft 实体/维度 API，需要进游戏验证碰撞检测、跨维度传送边界情况；不是纯逻辑改动。

### B. mag-manip / mag-movement：目标检测逻辑未定义（2 个技能）

**现状**：world-effect 执行器**已经真实存在且工作**——`platform-src/minecraft/base/.../adapter/world_effects.clj` 的 `execute-mag-manip!`/`execute-mag-movement!`（约第 67-118 行）已实现，会被安装到全部 6 个 loader。缺的只是 query 侧：`default-query-port` 的 `:mag-manip`/`:mag-movement` 都是 `(when-let [host-query ...])`，恒返回 nil。

**缺口不是平台代码，是设计判断**：query 需要回答"什么算金属方块/物品实体"以及"grab/throw 怎么找目标"，这两个问题在全仓库找不到任何可复用的既有逻辑：
- 没有 `metal-block?` 或类似的方块/物品分类谓词（搜过 `ac/src/main/clojure` 全目录）。
- `ac/ability/client/fx_templates/arc_beam/impl/mag_manip.clj` 看起来像是相关实现，但读过之后确认它**只是纯客户端手部动画/音效状态机**（loop sound、hand transform），完全不含服务端目标检测。
- `ac/ability/skill_config/electromaster.clj:83` 只有一条名为 `targeting.weak-metal-exp-threshold` 的配置量，backing 逻辑不存在。

**已知的执行器契约**（据此反推 query 必须产出的字段）：
- `execute-mag-manip!` 从 `plan[:query-result]` 读 `:entity-uuid`、`:position {:x :y :z}`、`:throw-target {:x :y :z}`。
- `execute-mag-movement!` 从 `plan[:query-result]` 读 `:target-x`/`:target-y`/`:target-z`。

**顺带发现的独立 bug（不阻塞，但要修）**：`execute-mag-manip!`（`world_effects.clj:101-118`）里有个死代码：`(when (and world-id entity-uuid ...))` 后面紧跟的 `let` 缺了闭合括号把 body 塞进 `when`，导致校验条件被算出来又被丢弃，紧跟着的 `let` 无条件执行。只要 query 保证只在数据完整时才让 combat-core 的 `:require` 放行，这个 bug 目前不会被触发（`combat_content.clj` 里 `:release`/`:pulse` 阶段查完都跟了 `{:op :require :predicate :held}`/`{:op :require :predicate :target}`），但仍然是需要一并清理的债务。

**要做的事**：需要先有人（游戏设计/内容作者）回答"什么算金属"，才能实现 query。不是纯粹的代码填空。

### C. world-effect 执行器缺失

**已完成（thunder-clap/blood-retrograde/plasma-cannon/meltdowner，2026-08-17 追加会话）**：`platform-src/minecraft/base/.../adapter/world_effects.clj` 的 `create-world-effects` 新增了 `execute-thunder-clap!`、`execute-blood-retrograde!`、`execute-plasma-cannon!`、`execute-meltdowner!` 四个执行器。全部复用已安装、已验证的 `cn.li.mcmod.platform.entity-damage/apply-direct-damage!`，没有引入新的 Minecraft 实体/物理原语。已在 `forge-1.20.1` 与 `neoforge-1.21.1` 两个平台目标上跑 `:platform:compileClojure` 编译通过（`fabric-26.2` 因为预先存在、与本次改动无关的 Fabric Loom 插件版本不匹配未能编译，环境问题，不是代码问题）；**仍未进游戏验证实际战斗表现**。

实现细节与已知简化：
- **owner 排除是自己写的，不是白拿来的**：`entity-damage/apply-aoe-damage!` 这个已安装的平台原语**没有 owner 排除参数**，直接用会连施法者自己一起打。新增了 `aoe-victims!`/`apply-aoe-damage-excluding-owner!` 两个本地 helper，逻辑镜像 `ac.ability.util.attack/aoe-victims`（owner 排除 + 球形距离过滤），但不能直接 `require` 那个 AC 层命名空间（platform 依赖 AC 是方向反转），所以是重新写的等价实现，不是简单复用。
- **meltdowner 是保守实现**：只对 raycast 命中的单一目标造成伤害。`beam-radius`/`block-energy`（沿光束熔化方块）和 `:reflection` 字段（Vector-Reflection 被动联动）**故意没有实现**——这块和 B 节的 mag-manip 一样需要设计判断，不是可以照抄平台原语的低风险填空，代码里留了注释指回本文档。
- **意外发现一个更大范围的既有问题，没有在本次改动范围内修**：`platform-src/minecraft/base/.../DamageSourceAccess.java` 的 `resolveKeyword` 只认识 `:magic`/`:lightning`/`:explosion`/`:generic`/`:skill`/`:vec-reflection` 六个 damage-type 关键字，`combat_content.clj` 里广泛使用的 `:electric`/`:vector`/`:teleporter` 都会落进 `default -> "generic"`。这不是我这次改动引入的——thunder-bolt 等已经"能用"的技能同样受影响（它们的 `:damage`/`:damage-targets` world-effect 最终也调用同一个 `resolveKeyword`）。伤害本身仍然会打（不会报错、不会变成 0），只是没有拿到对应伤害类型的护甲穿透/抗性/免疫特殊处理。新的 4 个执行器延续了这个既有行为（用 `:electric`/`:vector` 标签，和 content 里的现有约定保持一致），没有引入新的不一致，但值得单独排查是否要给 `resolveKeyword` 补齐这几个关键字。

### C-2. 其余 7 个技能：world-effect 执行器仍然缺失

**受影响技能**：`jet-engine`、`light-shield`、`storm-wing`、`electron-missile`、`scatter-bomb`、`groundshock`、`mine-ray-basic`/`mine-ray-expert`/`mine-ray-luck`（`groundshock` 的 query 走 `:block-scan` 的 fallback，`mine-ray` 三个也走 `:block-scan`，所以实际是 7 个技能点、8 个 skill id）。这 7 个的 **query 侧也没有真实实现**（都是 `when-let` 恒返回 nil），所以修复它们需要 query+执行器两层都补，比上面已完成的 4 个工作量更大。

**要做的事**：给 `execute-groundshock!`、`execute-electron-missile!`、`execute-scatter-bomb!`、`execute-mine-ray!`、`execute-jet-engine!`、`execute-light-shield!`、`execute-storm-wing!` 逐个写 Minecraft 实体/方块/物理交互代码，参考本次新增的 `execute-thunder-clap!`/`execute-blood-retrograde!`/`execute-plasma-cannon!`（AOE 伤害类）或已有的 `execute-mag-manip!`/`execute-mag-movement!`（速度/位移类）实现风格。每个技能的参数校验（`combat_runtime.clj` 里对应 `:type effect` 分支的 `valid?` 判断）已经写好，能看出每个执行器该接收什么形状的 `plan`。同时还需要在 `default-query-port` 里补上对应的 query 实现（`:groundshock`/`:block-scan`、`:electron-missile`、`:scatter-bomb`、`:jet-engine`、`:light-shield`、`:storm-wing`）。

**风险**：7 个技能，工作量大，每个都 touch 真实 MC API，需要逐个进游戏验证战斗表现（编译通过不代表游戏里数值/手感正确）。建议分批做，一个技能一个提交，方便单独回滚。

### D. 3 个 world-effect 类型：连处理分支都没有

**受影响技能**：`current-charging`（`:charge-energy`）、`mine-detect`（`:mine-detect`）、`directed-shock`（`:knockback`——**这个技能的 query 本身工作正常**，伤害会正常命中，只是击退效果被静默吞掉，容易被误判为"技能没问题"）。

**现状**：`combat_runtime.clj` 里 `(case (:type effect) ...)` 的 world-effect 处理分支**完全没有** `:charge-energy`/`:mine-detect`/`:knockback` 三个 case，全部落进最后的兜底分支 `{:status :unhandled :reason :missing-world-effect-host-port :effect effect}`。

**这三个不是平台代码缺口，是设计判断**：
- `:charge-energy`（`current-charging` 技能用）：应该接入 AC 已有的 `ac.energy.*` 机器能量系统，还是独立实现一套？`ac.energy.*` 目前服务的是方块机器间能量传输，"玩家技能给某个目标充能"是否复用同一套抽象需要设计决定。
- `:mine-detect`（`mine-detect` 技能用）：方块扫描结果要以什么格式反馈给客户端（高亮方块？小地图标记？纯文字提示？）——这是 UI/UX 设计问题，不是纯逻辑填空。
- `:knockback`（`directed-shock` 技能用）：应该调用哪个已有的击退原语？`entity-motion`/`player-motion` 里可能已有速度设置的函数可以复用，需要先确认。

**要做的事**：先由懂游戏设计的人拍板上述三个问题，再实现 `combat_runtime.clj` 里对应的 case 分支（`:knockback` 大概率可以复用 `execute-mag-manip!` 同款的速度设置原语，是三者里最快能做的）。

### E. vfx-core 通用化剩余部分（P1.1-P1.3，~6000 行内容迁移）

不是缺陷修复，是架构迁移——[VFX_CORE.md](VFX_CORE.md) 里已经记录了根因：vfx-core 按「一次施法 = 一个 instance」设计，AC 内容按「一个 effect-id = 一个 aggregate 实例，owner 维度塞在实例内部 map 里」写，`ac/client/effect_controller.clj:239-271` 的 `dispatch-signal!` 直接绕开 vfx-core 自己的 `instance-key`/`event-seq`/tombstone 分派机制。

**要做的事**（详见执行计划文档，未随此工单复制全文）：
1. 把 `:transient`/`:persistent`/`:singleton` 三种生命周期形态提升为 vfx-core 的一等概念。
2. 拆掉 `effect_controller/dispatch-signal!` 的旁路——需要同步把 `ac/ability/client/fx_templates/arc_beam.clj` + `arc_beam/impl/*`（约 6000 行）里 owner-keyed 的内部 map 拆成真正的 per-instance 状态。
3. 删除 `register-channel!`/`dispatch-channel!` 第二条投递路径。

**为什么推迟**：这是对**当前正在工作的客户端特效表现**的迁移，视觉效果对不对只能进游戏肉眼验证，本环境没有可运行的游戏客户端。工作量（~6000 行）和风险都明显大于本工单其他条目。

### F. presentation-core 剩余部分（P2.1/P2.2/P2.6）

**现状**：`presentation-core` 有一套完整但**从未接入真实渲染路径**的保留模式基础设施（`core/tree.clj` keyed reconcile、`core/dirty.clj` 六标志、`core/frame.clj` 的 frame-graph）——已核实这些只被 presentation-core 自己的测试调用，`ac/src`、`platform-src`、`mcmod/src` 里零调用者。真实渲染路径是 `runtime/extract!` → `render-mount` → `presentation-compiler/render.clj`，每帧从 `TemplateNode` 全量重算布局，只用一个粗粒度的 `:invalidated?` 布尔做失效判断，不读 dirty 六标志。

**要做的事**：
1. 把 `extract!` 改为真正走 `reconcile → dirty → layout cache → paint`，只在 `:measure`/`:layout` 脏时才重算布局。
2. `presentation-compiler/render.clj` 改为消费已经 laid-out 的保留节点，而不是自己每帧调 `core.layout` 重算。
3. `presentation_hud.clj` 的 `:composite-list` 扁平化（把布局职责挪回 AC 代码里做）改为在 `ac/src/main/resources/assets/academy/presentation/combat_hud.ui.edn` 里用真实模板节点表达。

**为什么推迟**：这三项全部会改变**战斗 HUD（游戏里最高频可见的界面）的实际渲染输出**，正确性只能通过进游戏截图比对验证，本环境无法验证。

## 建议的下手顺序

1. **先做全量审计，不要分批摸索**：对全部 37 个技能逐条核实 query 侧 + world-effect 侧 + 所需的目标检测/物理原语是否真实存在于平台代码里。本轮工单里的每一条分类，都是"以为已经 wired，深入一层才发现没有"这个模式重复了三次以后才逐渐收敛出的准确清单——不要重复这个摸索过程，直接从上面 A、B、C-2、D 四类清单开始。
2. ~~C 类里的 thunder-clap/blood-retrograde/plasma-cannon/meltdowner 4 个技能最快能做~~ **已完成**，见上方 C 节（仍需进游戏验证）。
3. **C-2 类的 7 个技能**（jet-engine 等）是现在优先级最高的剩余项——不需要设计判断，只是工作量大，可以参照 C 节新增的 4 个执行器风格继续做。
4. **D 类的 `:knockback`**（directed-shock）大概率可以复用已有的实体速度设置原语，是 D 类三者里最快能做的。
5. **A 类（传送）、B 类（mag 系列）需要先做设计判断**，不建议在没有设计输入的情况下直接开始写代码。
6. **E、F 类是大迁移，建议单独排期**，不要和 A/B/C-2/D 的小修小补混在一个提交序列里。

## 验证方式

```bash
# 每次改动后先跑对应模块的单测
./gradlew :combat-core:runCombatClojureTests
./gradlew :vfx-core:runVfxClojureTests
./gradlew :ac:compileClojure      # 快速确认没有编译期引用错误

# 架构门禁（含 verifyCombatSkillCoverage 等）
./gradlew verifyCurrentPlatforms
```

**端到端**（必须进游戏，本环境无法执行）：每完成一个技能的修复，单独进游戏验证该技能实际生效——技能"看起来接好了"和"真的能用"之间的落差正是这份工单存在的原因。

## 关联文档

- 系统架构：[COMBAT_CORE.md](COMBAT_CORE.md)、[VFX_CORE.md](VFX_CORE.md)
- 系统索引：[SYSTEMS_MAINTENANCE_INDEX.md](SYSTEMS_MAINTENANCE_INDEX.md)
- 完整执行计划与背景（含已撤回的错误诊断记录）：`C:\Users\lxy\.claude\plans\ui-presentation-vfx-core-combat-core-ps-fancy-panda.md`（本机 Claude Code 计划文件，不在仓库版本控制内）
