# main Ability → Final Combat/VFX 集成审计

> 审计基线：`main` 分支中的 Clojure `defskill`、课程构建器和 AC 运行时逻辑。
> 当前 `ac/src/main/resources/ac/combat/abilities/*.edn` 只作为待验证实现，不能反过来定义行为。

## 判定规则

- `❌`：已经发现确定的行为差异、缺失副作用或当前系统无法支持。
- `⚠️`：技能图和节点大体存在，但被公共链路阻塞，不能宣称与 main 等价。
- `✅`：只有在效果、状态、资源、冷却、事件、VFX 和 owner/world 作用域都有证据时才允许使用；本轮没有把“仅能编译”当作 `✅`。

## 本轮最终结论

结论不是“50 个 ability 都已经正确接入”：`main` 的 50 个公开注册项已经做到
逐项注册且 38 个真实技能都有 final VFX 声明，但只有课程被动的 12 个别名达到静态
闭合；38 个真实技能仍需要运行时等价证据，Railgun 的静态能力缺口已完成移植。因此
本轮不会把“EDN 可加载/可编译”包装成“效果正确”。

### 本轮逐项迁移队列（2026-08-28）

按 main 的 38 个真实技能逐项复核；“静态完成”只表示 Final 图、共享 ABI 和编译门禁已
闭合，不代表未执行的实机验收。

| 批次 | 技能 | 本轮结果 | 仍需实机/外部证据 |
|---|---|---|---|
| 1 | `electron-bomb` | 静态完成：spawn barrier 直接绑定 UUID，延迟 beam 不再查询最近同类实体 | 延迟调度、实体过期 |
| 2 | `electron-missile` | 静态完成：session 保存球 UUID，发射后精确移除，清理按 UUID+owner+world | 运动、目标命中、多人 |
| 3 | `scatter-bomb` | 静态完成：球 UUID 列表、anti-AFK release、abort 清理 | 散射碰撞、多人 |
| 4 | `mag-manip` | 静态完成：持有体 UUID barrier 与会话查询作用域 | 碰撞放置/伤害、spawn 失败回滚 |
| 5 | `light-shield` | 静态完成：护盾 UUID、接触 self-exclusion、吸收 interval/session patch | 原生受击 adapter、多人 |
| 6 | 共享 `damage/absorb` ABI | 静态完成：`interval-ticks`/`last-tick-path` 由 Combat Core 计算并提交 session | 原生事件时序 |
| 7 | penetrate-teleport | 静态完成：固定通道 wheel choice、owner session 距离 clamp、三阶段快照 | 目的地/碰撞 adapter、多人 |
| 8 | meltdowner | 静态完成：公共 beam-trace 按 reflection-policy 调用 interaction/resolve 并返回反射字段 | 反射目标/方块 adapter、多人 |

其余技能按同一顺序继续处理；不得以复制 main handler、兼容旧 callback 或第二条执行轨替代
当前 Final 图。每个批次在对应测试通过后单独提交。

本轮已先修复会影响多个技能的公共错误：资源预算的失败分支/缩放/部分扣费、按
owner+entity-type 的会话实体清理、以及 final session 元数据读取。修复后门禁均通过，
但这些修复不等同于逐技能行为测试。

随后又修正了 Final 图的控制流语义：`:flow/finish` 现在会停止当前 sequence/foreach
的后续节点，`:flow/control :skip-item` 只跳过当前 foreach 项，且无 `:else` 的
`:flow/branch` 会安全地成为 no-op。此前这些节点虽然能编译，却会在资源不足、无目标
或单项扣费失败后继续执行伤害/传送等副作用；该修复已由 Combat Core 回归测试覆盖。
同时移除了依赖已删除旧视觉状态路径的 AC 测试和 stale client hook 注册项，没有恢复
旧 VM 或双轨兼容代码。当前可执行的编译/EDN 门禁通过，但旧运行时测试中仍有若干
测试假设 `combat_runtime` 的历史返回值；它们不再作为 Final 行为正确性的证据。

## 注册规模

- 38 个真实技能实现。
- 12 个课程别名：四个能力类别分别注册 `brain-course`、`mind-course`、`brain-course-advanced`。
- 当前 catalog 结果为 39 个 combat source、50 个 registration、36 个 VFX effect。
- 50 个 registration 的当前静态结论为：12 个课程别名 `✅*`、38 个真实战斗技能均为
  `⚠️`（待运行时等价证据）。`✅*` 的星号表示课程被动 reducer 已接入，
  但完整学习/重算测试仍受现有测试 classpath 阻断。
- 当前最终静态门禁：`:ac:checkClojure`、`:ac:runAcEdnCoverageTests`（21 tests / 53 assertions）、
  `:combat-core:runCombatClojureTests`（31 tests / 83 assertions）均通过；这些门禁不执行
  main 行为等价性或实机多人测试。

## 逐注册项结论

## 50 项逐项功能核对

下表中的“效果/逻辑”来自 `main` 的 `defskill` 实现；“VFX”来自同一技能的
`*_fx.clj`，不是根据当前 EDN 的节点名称倒推。`⚠️` 表示图已经注册，但还
不能证明完整等价；`❌` 表示已经找到确定缺陷。当前没有任何战斗技能可以在
“最终伤害上下文、mark 提交、VFX 网络发送、多人作用域”都闭合前标成 `✅`。

| ability | main 效果与逻辑 | main VFX | 当前接入结论 |
|---|---|---|---|
| `arc-gen` | 射线命中实体造成缩放伤害；命中方块时点火/掉鱼；命中与未命中分别给经验并启动冷却 | beam、命中/端点音效 | ⚠️ 已修复 Final 水体 raycast、命中字段归一化和 creeper 判断；公共 damage/VFX 提交链仍未闭合 |
| `body-intensify` | 按住蓄力；CP/过载约束；释放随机药水效果、经验和冷却 | 手部弧光、循环音、端点爆发 | ⚠️ |
| `current-charging` | 对主手能量物品或可充能方块持续充能；目标丢失时清理会话 | 充能弧光、循环音 | ⚠️ |
| `mag-manip` | 捕获金属方块/物品实体，持续吸附，释放投掷；碰撞负责伤害/放置/恢复 | 持续弧光、音效、beam | ⚠️ 需继续验证实体碰撞提交 |
| `mag-movement` | 锁定金属方块/实体并牵引玩家；结束时重置摔落并按距离给经验 | 持续弧光、循环音 | ⚠️ |
| `mine-detect` | 视线扫描地雷/实体；失明和资源不足拒绝；成功施加扫描状态并计经验/冷却 | 扫描框/扫描音效 | ⚠️ |
| `railgun` | 硬币 QTE、铁物品蓄力、硬币判定/销毁、反射射击、经验/成就、主射线 | 硬币/枪体 billboard、rail beam | ⚠️ Final 图已接入 owner-scoped QTE/蓄力/消费/销毁/反射/成就；exp 细粒度与实机等价仍待验证 |
| `thunder-bolt` | 命中目标后闪电、AOE、creeper/potion 分支、经验/冷却 | 闪电冲击 | ⚠️ 目标引用已修复；仍受 damage/VFX 公共链约束 |
| `thunder-clap` | 蓄力范围伤害、闪电、资源和冷却、成就 | 环形蓄力/闪电 | ⚠️ |
| `electron-bomb` | 生成电子球并延迟调度 beam，命中后完成伤害/经验/冷却 | 瞬时电弧 | ⚠️ 延迟实体结果需继续验证 |
| `electron-missile` | 持续蓄力生成多发电子球，锁定目标并发射，资源不足/超时清理 | 粒子、beam fade、音效 | ⚠️ |
| `jet-engine` | 标记目标、持续移动与伤害，结束计经验/冷却 | 环、粒子、屏幕闪烁、billboard | ⚠️ `:entity-mark` 已接入 owner/world 表；持续移动/伤害仍需行为验证 |
| `light-shield` | 伤害反应吸收伤害；按吸收量消耗 CP/过载，处理正面判断、状态和冷却 | 护盾粒子、循环音、吸收音 | ⚠️ final-damage context、资源不足门控和 horizontal-yaw 正面判断已接入；状态/冷却仍需行为测试 |
| `meltdowner` | beam 逐段伤害并按权限破坏方块，资源/过载地板/冷却 | beam、FOV、粒子、音效 | ⚠️ `host/beam-trace` 结果提交需继续验证 |
| `mine-ray-basic` | 基础射线采矿；工具等级限制、fortune=0、独立冷却 | beam、进度条、粒子、音效 | ⚠️ variant bindings 已注入，仍需行为等价测试 |
| `mine-ray-expert` | 专家射线采矿；取消工具等级限制，独立前置/冷却 | 同 MineRay 专属变体样式 | ⚠️ |
| `mine-ray-luck` | luck=3 射线采矿，专属颜色/粒子 | 同 MineRay luck 样式 | ⚠️ |
| `rad-intensify` | 读取目标 radiation mark，使用标记创建时的 source rate 放大伤害 | 目标 mark session | ⚠️ mark-type 匹配与 source rate 快照已修复，并按 world/target 隔离；跨重启持久化与实机 VFX 仍需验证 |
| `ray-barrage` | 扇形多目标射击；命中后写 radiation mark 并触发后续行为 | ray beam、fan、音效 | ⚠️ mark 提交已接入；silbarn 触发已限定施法者 owner，扇形命中/后续行为仍需验证 |
| `scatter-bomb` | 生成/调度多枚散射弹，资源不足时清理 | 粒子、beam fade、音效 | ⚠️ |
| `dim-folding-theorem` | 学习后为非反射 magic/skill 攻击提供 level 0 暴击/反馈/成就 | 暴击尾迹/粒子 | ⚠️ policy input、damage-type 过滤、ordered critical、反馈消息和 VFX owner 已接入；经验仍需行为测试 |
| `flashing` | 四方向闪现；预览/释放资源检查，传送后保护摔落并计经验 | teleport marker、端点爆发 | ⚠️ |
| `flesh-ripping` | 锥形/射线命中伤害和状态，命中/未命中经验与冷却 | 目标框、粒子、音效 | ⚠️ |
| `location-teleport` | 读取保存地点；跨维度才检查经验门槛并应用倍率 | 传送音效 | ⚠️ `cross-dimension?` 逻辑已修复，仍需验证保存地点/网络边界 |
| `mark-teleport` | 持续保持目标地点标记，释放时扣资源并传送 | marker、ring fade | ⚠️ |
| `penetrate-teleport` | 穿透目标寻找可传送地点；成功传送并计经验/冷却 | marker、音效 | ⚠️ |
| `shift-teleport` | 预览方块放置点，释放放置/掉落物并伤害线路目标 | 目标框、轨迹音 | ⚠️ 方块/物品副作用需最终提交验证 |
| `space-fluct` | 多级暴击，排除反射伤害并给经验/成就 | 暴击尾迹/粒子 | ⚠️ policy input、damage-type 过滤、level 顺序合并、反馈消息和 VFX owner 已接入；概率/排除反射仍需行为测试 |
| `threatening-teleport` | 持有物品进入威胁态；被攻击时传送并伤害，未命中计 miss | 目标框、传送轨迹 | ⚠️ |
| `blood-retrograde` | 扇形/射线反向伤害，资源、命中经验和会话清理 | 蓄力、冲击、音效 | ⚠️ |
| `directed-blastwave` | 蓄力后范围伤害、击退、破坏路径方块 | 蓄力弧、冲击波、音效 | ⚠️ |
| `directed-shock` | 蓄力拳击；命中伤害、位移/击退、命中/未命中经验和冷却 | 第一人称手部动画、音效 | ⚠️ |
| `groundshock` | 传播破坏/替换方块并 AOE 伤害；能量、掉落率、范围随经验变化 | 第一人称动作、地面冲击波 | ⚠️ 方块/实体批处理需验证 |
| `plasma-cannon` | 蓄力飞行弹；命中爆炸、范围伤害和地形破坏，过载/冷却 | 能量球、龙卷柱、端点爆发、音效 | ⚠️ |
| `storm-wing` | 飞行移动、软方块破坏、范围击退、摔落保护和资源扣除 | 飞行粒子、循环音、龙卷柱 | ⚠️ |
| `vec-accel` | 方向加速、碰撞检查、速度/摔落重置、经验和冷却 | 轨迹带、冲量音 | ⚠️ |
| `vec-deviation` | 扫描并偏转/销毁投射物；开启时减伤并按伤害消耗 CP | 环形 fade、反射音效 | ⚠️ 开启状态、ignore-threshold 与减伤 policy 已接入；main 的“CP 不足仍按剩余 CP 扣除但继续减伤”已由 Final damage 测试覆盖，反射扫描仍需实机验证 |
| `vec-reflection` | 扫描并重定向投射物；受到伤害时按倍率反射并扣资源 | 环形粒子、fade/音效 | ⚠️ 反射结果已在攻击预检查一次性提交，按 world/source/target/seed/depth 幂等；参数快照与扣费仍需行为测试 |

课程别名的 12 个注册项（四类别 × `brain-course`、`brain-course-advanced`、
`mind-course`）没有战斗 VFX：它们分别是 `+1000 max CP`、`+1500 max CP +100
max overload`、`CP recovery ×1.2`。本轮已在
`combat-catalog/apply-passive-resource-modifiers` 中实现 owner-local 纯 reducer，
因此这 12 项的资源效果接入为 `✅*`；`*` 表示仍需在完整测试 classpath 恢复后执行
学习/重算回归测试。

为避免“按类别合并”掩盖漏注册，12 个别名逐项列出如下；它们都复用同一个
Combat Core 被动 reducer，不产生 VFX，也不共享其他玩家的资源状态：

| registration | main 被动效果 | 当前接入 |
|---|---|---|
| `electromaster/brain-course` | `max-cp +1000` | ✅* |
| `meltdowner/brain-course` | `max-cp +1000` | ✅* |
| `teleporter/brain-course` | `max-cp +1000` | ✅* |
| `vecmanip/brain-course` | `max-cp +1000` | ✅* |
| `electromaster/brain-course-advanced` | `max-cp +1500`, `max-overload +100` | ✅* |
| `meltdowner/brain-course-advanced` | `max-cp +1500`, `max-overload +100` | ✅* |
| `teleporter/brain-course-advanced` | `max-cp +1500`, `max-overload +100` | ✅* |
| `vecmanip/brain-course-advanced` | `max-cp +1500`, `max-overload +100` | ✅* |
| `electromaster/mind-course` | `cp-recovery-speed ×1.2` | ✅* |
| `meltdowner/mind-course` | `cp-recovery-speed ×1.2` | ✅* |
| `teleporter/mind-course` | `cp-recovery-speed ×1.2` | ✅* |
| `vecmanip/mind-course` | `cp-recovery-speed ×1.2` | ✅* |

### 已确认问题（含本轮已修复项）

| 注册项 | main 行为基线 | 当前实现缺口 |
|---|---|---|
| `railgun` | Coin QTE、硬币判定/销毁、物品蓄力、反射射击、经验/成就、主射线 | item→EDN trigger、owner-scoped QTE/charge session、通用 beam reflection、实体消费/销毁及成就事件均已移植；细粒度经验和实机时序待验证 |
| `mine-ray-basic` | 基础变体，工具等级限制，fortune=0，独立冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-expert` | 专家变体，取消工具等级限制，独立前置条件/冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-luck` | luck 变体，fortune=3，独立粒子/光束样式 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `location-teleport` | 仅跨维度时检查经验门槛并应用跨维度倍率 | ✅ 已修复 `not=` 逻辑；仍需保存地点/跨维度提交测试 |
| `light-shield` | damage reaction 吸收伤害，CP/过载消耗，正面判断，状态和冷却 | ✅ 已修复 CP/过载映射并接入 final-damage context；资源/夹角需实机验证 |
| `thunder-bolt` | 目标命中后 AOE/creeper/potion/经验/冷却 | ✅ 已修复目标引用并接入统一 VFX；AOE 分支需实机验证 |
| `dim-folding-theorem` | 学习状态、非反射攻击的暴击/反馈/VFX/成就 | input/context、反馈消息和 VFX owner 已接入；主线等级与经验细节需验证 |
| `rad-intensify` | 读取 radiation mark，并使用标记创建时的 source rate 放大伤害 | mark 表、策略输入、mark-type 匹配和倍率快照已接入；跨重启持久化未实现 |
| `space-fluct` | 多级暴击、反射排除、经验/成就/VFX | input/context、反馈消息和 VFX owner 已接入；多级概率与排除反射需验证 |
| `vec-deviation` | 开启状态下减伤、CP 代价、音效/经验 | owner session/context、ignore-threshold 与按剩余 CP 尽量扣费已接入；仍需实机验证反射扫描与减伤时序 |
| `vec-reflection` | 开启状态下反射伤害、代价、最大深度/经验；低于 minimum 时仍减少原伤害但不取消原生攻击 | 反射已一次性提交，按 world/source/target/seed/depth 幂等；minimum、残余伤害和环境边界已对齐，参数快照/扣费与递归上限需行为测试 |
| `jet-engine` | 每 tick 移动、伤害并写入 radiation mark | mark reducer 已接入；持续移动/伤害需验证 |
| `ray-barrage` | 命中后写入 radiation mark，并触发后续行为 | mark reducer 已接入；silbarn 查询按 caster owner 隔离，扇形命中与后续行为需验证 |

Railgun 还有一个入口级修复：`main` 的 `ItemCoin` 使用不依赖当前是否处于技能
激活态，因此 `:item/use` 的 final trigger 不能带 `:ability-mode? true` 过滤。
该过滤已移除，并由 `main-item-trigger-is-unconditional-test` 固定 `ac:coin` 和
`academy:coin` 在 true/false 两种激活状态都能解析到 `:coin-thrown`。这只证明
事件能进入 Combat Core；事件图本身仍未实现 QTE 状态机。

### 课程别名（本轮已修复 reducer）

以下 12 项的 EDN 声明了被动效果；本轮已把它们改为读取 owner-local
`ability-data` 的纯 reducer，不再是 no-op：

```text
electromaster/brain-course
meltdowner/brain-course
teleporter/brain-course
vecmanip/brain-course
electromaster/mind-course
meltdowner/mind-course
teleporter/mind-course
vecmanip/mind-course
electromaster/brain-course-advanced
meltdowner/brain-course-advanced
teleporter/brain-course-advanced
vecmanip/brain-course-advanced
```

当前核心实现：

```clojure
(defn apply-passive-resource-modifiers [ability-data values]
  (let [learned (set (or (:learned-skills ability-data) #{}))
        has-suffix? (fn [suffix]
                      (some #(and (keyword? %) (= suffix (name %))) learned))]
    (cond-> values
      (has-suffix? "brain-course")
      (update :max-cp (fnil + 0.0) 1000.0)
      (has-suffix? "brain-course-advanced")
      (-> (update :max-cp (fnil + 0.0) 1500.0)
          (update :max-overload (fnil + 0.0) 100.0))
      (has-suffix? "mind-course")
      (update :cp-recovery-speed (fnil * 0.0) 1.2))))
```

## 尚未完成行为等价证据的真实技能

以下技能的 graph 结构、主要 action/VFX 节点已找到，但仍不能标为完全通过，因为所有 graph VFX 都受公共发送链路影响，且 damage/mark/owner context 的公共问题会按技能是否使用这些能力继续传递：

```text
arc-gen
blood-retrograde
body-intensify
current-charging
directed-blastwave
directed-shock
electron-bomb
electron-missile
flashing
flesh-ripping
groundshock
mag-manip
mag-movement
mark-teleport
meltdowner
mine-detect
penetrate-teleport
plasma-cannon
scatter-bomb
shift-teleport
storm-wing
threatening-teleport
thunder-clap
vec-accel
mine-ray-basic
mine-ray-expert
mine-ray-luck
location-teleport
thunder-bolt
```

这些技能必须继续逐项核对：效果目标和数量、资源扣除、冷却启动条件、延迟任务、方块/实体副作用、VFX audience、异常/中断清理以及 owner/world 隔离。不能用“graph 已编译”替代这些检查。

## 后续逐项修复清单（不并行改 50 项）

本清单作为唯一执行队列。每完成一项，必须在该项的 Final graph、对应 VFX
声明、资源/冷却/经验、异常清理和 owner/world 边界上留下证据，并在该节点单独
提交；未完成项保持 `⚠️`，不能因为注册或编译成功而提前改成 `✅`。

1. `arc-gen`
2. `body-intensify`
3. `current-charging`
4. `mag-manip`
5. `mag-movement`
6. `mine-detect`
7. `thunder-bolt`
8. `thunder-clap`
9. `electron-bomb`
10. `electron-missile`
11. `jet-engine`
12. `light-shield`
13. `meltdowner`
14. `mine-ray-basic`
15. `mine-ray-expert`
16. `mine-ray-luck`
17. `rad-intensify`
18. `ray-barrage`
19. `scatter-bomb`
20. `dim-folding-theorem`
21. `space-fluct`
22. `vec-deviation`
23. `vec-reflection`
24. `flashing`
25. `flesh-ripping`
26. `location-teleport`
27. `mark-teleport`
28. `penetrate-teleport`
29. `shift-teleport`
30. `threatening-teleport`
31. `blood-retrograde`
32. `directed-blastwave`
33. `directed-shock`
34. `groundshock`
35. `plasma-cannon`
36. `storm-wing`
37. `vec-accel`
38. `railgun`（已完成静态能力移植，待运行时等价证据）

### arc-gen checkpoint（db8de4f4e）

- `target/raycast` 使用现有 `policy` ABI 的 `:collidable-or-water` 策略；Combat
  Core 在中性边界选择最近实体/碰撞方块/水体，不把 Minecraft 类型泄漏到 EDN。
- 中性命中结果统一提供 `:position`、`:block-position`、`:water?`、`:entity-id` 和
  `:entity-type`，因此 Arc Gen 不再读取不存在的旧 `:creeper?` 字段。
- AC 的 `world/block-impact` 处理会在 miss 终点重新读取水体，保持 main 的“未命中也
  走方块分支”语义；读取和写入始终由事件携带的 `world-id` 约束。
- 已通过 `:combat-core:runCombatClojureTests`（25/65）及
  `:ac:runAcEdnCoverageTests`（14/33）。这只是该项的静态/编译 checkpoint，不能把
  仍未闭合的 skill damage scaling、实际客户端 VFX 发送和实机结果标成 `✅`。

### electron-bomb checkpoint（检查与修复）

- `main` 基准是无资源消耗；施放时立即增加固定经验并启动按熟练度插值的冷却；生成带生命周期的 MdBall，在接近生命周期结束时从球的实时位置向施法者当时的准星终点射出单体魔法伤害射线；无论命中与否都发送射线表现。
- Final EDN 已覆盖上述时序、伤害/冷却曲线、生命周期分支、魔法伤害、延迟调度和附近玩家 VFX 广播。
- 本轮修正：生成 MdBall 后通过 Final `target/entities` 查询取得本次生成球的实体 ID，并把 ID 放入中立 `origin-selector`；`combat-core/deferred` 增加 `:entity-id` 精确过滤。同一玩家同时存在多个 MdBall 时，延迟射线不会误选其它技能的球，也不会跨玩家选中实体。
- 未引入旧技能函数、兼容分支或 AC 直连；仍由 AC 组合根提供 Final host/query/action wiring。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）与 `:combat-core:runCombatClojureTests`（25/65）。实机下仍需验证生成失败边界及适配器返回 ID 与实体生命周期的一致性，因此总表保持 `⚠️`。
### body-intensify checkpoint（逐项迁移）

- Final 图覆盖 main 的起始 overload、逐 tick CP/overload floor、最小/最大/容忍蓄力、
  随机状态效果、hunger、VFX、超时/资源不足/abort 清理。
- 本轮把 cooldown tunable 暴露为配置端点，在 release 图中先计算
  `mastery + progression-exp-use`，再以 `math/lerp` 求冷却并启动；因此顺序与 main 的
  “成功释放增加经验后计算 cooldown”一致，不再依赖提交后的旧 callback 或静态旧 exp。
- 仍需实机验证随机效果 adapter、状态持续时间和多人 owner VFX；静态门禁通过，故总表
  继续保持 `⚠️`，但该项的 progression→cooldown 共享阻塞已解除。

### current-charging checkpoint（检查与修复）

- Final 图已覆盖 main 的 item/block 双模式、起始 overload 扣除、逐 tick CP、overload
  floor、能量目标解析、有效/无效经验、充能 VFX 更新，以及 release/abort 清理。
- 已修复 item 模式中“主手物品消失”只销毁 VFX 但不结束 session 的缺口：现在返回
  `:item-missing` 并设置 `:finish-session? true`，不会继续占用该玩家的 channel。
- 该项已通过 `:ac:runAcEdnCoverageTests`（14/33）。实际能量方块/物品 adapter 的
  运行时结果仍需实机任务验证，因此总表继续保持 `⚠️`。

### electron-missile checkpoint（检查与修复）

- `main` 基准：蓄力每 10 tick 生成最多 5 个 MdBall；每 8 tick 选择施法者附近最近的 living 非自身实体，支付攻击 CP/过载后造成魔法伤害、重置无敌时间、增加命中经验并销毁对应球；每 tick 发送 owner+nearby 充能更新，释放/超时/abort 清理球、结束 VFX 并启动固定冷却。
- Final 图已覆盖蓄力计时、球数量上限、过载地板、tick/攻击费用、超时/释放/abort 清理、目标排序、伤害/经验/冷却和 Final VFX；本轮将目标过滤补为 `excluded-entity-ids=[owner-id]`，与 main 的 `missile-filter-self` 一致，避免施法者被选为目标。
- Final session 现在保存每个生成球的 UUID：`entity/spawn :barrier? true` 的中性结果绑定到 `:ball-ids`，发射后按 `collection/remove` 更新列表，释放/超时/abort 使用 `entity-ids + owner + world` 精确清理；不再按 owner/type 模糊抓取同一玩家的其它实体。
- 已通过 `:ac:runAcEdnCoverageTests` 与 `:combat-core:runCombatClojureTests`；总表仍保持 `⚠️`，仅表示实体 adapter、延迟射线和多人实机结果尚未测试。
### jet-engine checkpoint（检查与修复）

- `main` 的标记阶段使用仅方块 raycast（实体不参与目标点），释放后以 8 tick 线性速度推进并在 15 tick 生命周期内逐段做实体命中/伤害；释放资源不足则结束标记，触发阶段结束清理全部 owner-scoped VFX。
- Final 图已覆盖 marking/triggering phase、CP 门控、release CP+overload、速度/分段命中、radiation mark、经验/冷却和 owner/nearby VFX。
- 本轮修正 start/pulse 的 `target/raycast`：改为 `include-entities? false, include-blocks? true`，避免实体挡在准星前时改变 main 定义的方块目标点；triggering 分段仍保持实体-only raycast。
- 仍需实机验证玩家速度/碰撞、segment 命中及 radiation mark 与 `rad-intensify` 的跨技能组合；当前总表保持 `⚠️`。
### light-shield checkpoint（检查与修复）

- `main` 的核心行为是 toggle 护盾：前方水平 yaw 接触伤害；受到合资格攻击时按吸收上限减伤，并按原实现的参数顺序扣防御资源；按 tick/接触/受击分别加经验；结束时移除护盾实体、施加 slowness 并按持有 tick 计算冷却。
- Final 图已覆盖 toggle session、overload floor、tick/接触费用、前方 cone、吸收 policy、状态/冷却和 owner/nearby VFX。
- 本轮修正 `damage/absorb` 的费用映射：`main` 的防御路径把 `absorb-cp` 传入 overload、把 `absorb-overload` 传入 CP（配置注释也明确记录该历史参数顺序）；Final policy 现按该顺序提交资源费用。
- Final `damage/absorb` 现在消费 `interval-ticks/last-tick-path`：同一护盾会话在间隔内不会重复吸收，成功吸收后以中性 `session-patch` 提交 last-absorb tick；该状态由 AC 组合根提交，不在技能内保留旧 damage handler。激活 cost-fail 时序仍需以 Final 统一资源策略在实机复核。
- 本轮 EDN 静态门禁待运行后记录；总表保持 `⚠️`，不能把可编译视作受击行为等价。
### meltdowner checkpoint（检查与修复）

- `main` 的 charge window 为 20/40/100 tick；release 计算 time-rate，执行 beam（实体伤害、方块破坏、反射射击），按 time-rate 增加经验并启动 time-rate×base×cooldown 冷却，最后清理充能 VFX。
- Final 图已覆盖充能状态、overload floor、tick 费用、beam trace、实体/方块副作用、反射字段消费、VFX 和经验事件。
- 本轮修正 release 冷却：原图只有 `cooldown/start :main`，未传入 `main-cooldown`，按 Final 引擎会写入 0 tick；现在先读取 `ability/cooldown :main`，再以 `:cooldown {:ref [:local :main-cooldown]}` 启动。
- 本轮补齐公共 beam-trace：Combat Core 对每个命中实体按 EDN `reflection-policy` 调用中性
  `interaction/resolve`，把 `reflection-accepted?/target/start/end/damage` 作为同一 trace 结果返回；
  Meltdowner 的反射分支因此与实体伤害、VFX 一样走 Final graph，不恢复旧 beam helper 或技能回调。
- 仍需实机验证反射目标的真实 raycast、方块破坏 adapter 和多人同时 beam；静态门禁只证明
  ABI/编译闭合，故总表保持 `⚠️`。
### ray-barrage checkpoint（检查与修复）

- main 的分支基准是：准星首个实体为尚未触发的 Silbarn 时，触发其行为并在当前瞄准方向
  对锥体内目标逐个造成 scattered magic damage；其它命中（包括已触发 Silbarn）走 plain
  单体射线。两条路径都发送预射线/音效、写 radiation mark、增加一次使用经验并启动
  熟练度冷却；资源不足不产生任何副作用。
- 当前 Final 图已使用中性 `target/raycast` + `target/entities` + `target/entities`
  cone 查询，Silbarn 行为通过 `entity/trigger-behavior`，伤害/mark/VFX/经验/冷却均在
  同一 graph 内组合；锥体查询排除施法者和已触发的 Silbarn，owner/world 由 Final host
  作用域传入，未保留 main 的 `ray_barrage_perform!` 或旧 damage helper。
- 发现并修复的确定性缺口：平台实体 adapter 已提供中性 `:behavior-hit?`，但
  Combat Core 的 `project-entity` 没有投影该字段，导致 Final 无法识别已触发 Silbarn，
  所有 Silbarn 都会误走爆炸分支。现在字段被保留并可由 EDN projection 显式读取。
- 已通过 `:combat-core:runCombatClojureTests`（25/65）及
  `:ac:runAcEdnCoverageTests`（14/33）。由于没有实机，raycast 命中点、Silbarn 行为
  adapter、锥体几何边界和多人同时施放的实际结果仍保持 `⚠️`，不能将静态通过视为运行时
  完成。
### flesh-ripping checkpoint（逐项复核）

- main 在 hold 期间持续 raycast 并更新目标框；release 使用最后一次保存的 trace，命中时
  扣 CP/overload、造成 magic damage、按概率给施法者 nausea、记经验并启动 cooldown；
  miss 只清理 marker，不扣 release 费用。
- 当前 Final 图原本在 release 重新 raycast，这会把 release 时的目标替换成不同实体。现在
  start/pulse 将中性 `target/raycast` 的 `:attacked?/:target-id/:position` 写入 owner session，
  release 只读取该快照；资源不足与 miss 分支仍由同一 graph 负责 VFX destroy 和 session finish。
- main 的 `:creative?` 费用策略也已迁移：命中 release 的 `cost/spend` 现在按
  `caster/creative?` 缩放，creative 玩家不扣 CP/overload；miss 仍不进入费用节点。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）。目标移动、nausea 随机率、真实伤害 adapter
  与多人同时瞄准仍需实机验证，不能把静态加载视为完成。
### scatter-bomb checkpoint（逐项复核）

- main 的基准是 hold-channel：启动时扣 overload 并记录实际余量作为 floor；server tick
  扣 CP，在 20..80 tick 每 10 tick 生成一枚 MdBall（最多 7），释放时每枚球独立散射，
  熟练度超过阈值后才允许按 `floor(balls * exp)` 枚自动瞄准；tick 200 anti-AFK 先造成
  generic 自伤，再结算并发射已有球体。无 cooldown，按球发放经验。
- 当前 Final 图已覆盖启动/蓄力费用、floor、生成节奏、owner/type/world 过滤、散射
  beam、nearby VFX 和按球 score。此前释放条件漏掉 `auto-aim-exp-threshold`，现在已在
  Final branch 中补齐；server pulse 已由统一 runtime 每 tick 注入 `charge/ticks`。
- Final session 现在保存每个生成球的 UUID，并在 release/abort 通过 `entity-ids + owner + world` 精确查询；spawn 结果绑定与列表更新都在同一 Final graph 内完成。anti-AFK 已通过 Final `flow/finish :next-phase :release` 转入同一释放 graph：先执行 generic 自伤，再由 server pulse runtime 派发 release，不复制 volley，也不建立第二条路径。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）；没有实机时不能将实体轨迹、方块碰撞、
  delayed beam 和多人同时蓄力标为完成。
### mark-teleport checkpoint（逐项复核）

- `main` 的目标距离随持有时间增长，并受最大距离、当前 CP/每米成本限制；最小有效
  距离为 3，松键使用持有期间最后一个目标，成功时传送、脱离载具、清除摔落状态、
  计经验并启动冷却。
- Final 的 `target/hold-destination` 已保留上述距离/资源/最小距离策略；本轮将
  release 从重新 raycast 改为读取 owner session 的最后 `:destination`，因此不会因
  松键时准星变化而偏离主线语义。公共 `entity/teleport` 端口现在实际执行
  `:dismount?`/`:reset-fall-damage?`。
- 仍需实机确认 teleport adapter 的跨维度实体边界与 marker 的客户端到达率；静态门禁
  已通过，未将该项标记为最终行为 `✅`。

### location-teleport checkpoint（逐项复核）

- `main` 的保存/删除/查询仍由 AC 专属 named-position RPC 负责；实际传送统一进入
  Final `target/saved-location` + `combat/teleport-group`，没有恢复旧技能执行器。
- Final 已按跨维度条件检查经验门槛，并使用距离平方根、跨维度倍率、过载和冷却曲线；
  `teleport-group` 负责 owner 与半径 5 内实体的相对偏移迁移。
- RPC 预览只用于 UI 快照，不执行传送；它与 Final 图共享同一 AC tunable registry，
  但算术镜像仍是可维护性风险，后续总审计需决定是否抽取通用纯成本函数。

### shift-teleport checkpoint（逐项复核）

- `main` 在持有期间持续保存手持物、落点 trace 和路径实体；释放优先使用最后一次
  预览快照，只有没有快照时才重新解析。当前 Final release 已改为读取 owner session
  的 `:hand-item`、`:trace`、`:targets`，避免松键重采样改变放置点或伤害列表。
- 放置/掉落、路径伤害、经验、冷却和 marker 清理仍由同一 Final graph 串联；
  `target/block-placement` 的权限事实由中性 host 提供，EDN 只决定是否执行。
- main 的 release cost 带 creative 免扣策略；当前 Final 已在唯一 `cost/spend :release`
  节点按 `caster/creative?` 缩放，creative 放置不会错误消耗 CP/overload。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。仍需实机确认方块权限
  失败时的 drop fallback、路径实体排序和多人 VFX audience。

### flashing checkpoint（逐项复核）

- `main` 使用同一按键切换 flashing：首次按下建立持续 session，重复按下结束
  session 并启动停用冷却；W/A/S/D 事件只在该 owner 的 session 存在时生效。
- 当前 Final 仍由 `flow/phases` 描述持续时间、过载地板、摔落保护和四方向事件；
  本轮在 AC 组合根增加通用 toggle 边沿解析：对同一 owner 的 active `:toggle`
  session，下一次 `:start` 自动路由为该技能的 `:abort` phase，避免覆盖旧 session。
- 对照 main 又发现两处确定性差异：creative 玩家不应支付 activation overload，且
  `overload-floor` 必须记录 activation 扣费后的余量。Final start 现对 CP/overload
  都按 `caster/creative?` 缩放，并以 `max(0, start-overload-cost)` 写入 floor；
  不引入技能专属旧回调。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。仍需实机确认方向目标、
  CP/过载原子扣费以及停用冷却与 marker 清理的网络时序。

### threatening-teleport checkpoint（逐项复核）

- `main` 在持有期间不断更新目标框，但释放优先使用最后一次保存的 trace；释放时再
  校验当前手持物，按命中/未命中概率结算物品、伤害、经验和冷却。
- 当前 Final release 已改为读取 owner session 的 `:trace`，同时保留释放时
  `target/item-held` 检查和 needle 倍率；命中/未命中、settle、damage、score、VFX
  仍由同一 graph 决定，没有恢复旧 handler。
- main 的 up-stage creative 免扣也已迁移：命中 release 的 `cost/spend` 按
  `caster/creative?` 缩放，creative 玩家不会错误消耗 CP/overload。
- 仍需实机确认实体顶部 drop 坐标、物品扣除失败和多人 marker 销毁边界。

### penetrate-teleport checkpoint（逐项复核）

- `main` 允许客户端通过专用 distance channel 调整期望距离，服务端在 down/tick/up
  都以该期望值、最大距离和当前 CP 重新计算穿透落点；up 阶段还会缓存同一解析结果，
  避免费用检查与实际传送使用不同目标。
- 当前 Final 已迁移穿透扫描、CP 上限、最小距离、marker、释放费用/经验/冷却，并由
  server pulse 驱动预览。本轮补齐距离调节：客户端滚轮通过固定输入 choice 发送 `wheel:<delta>`，服务端
  只接受有限浮点范围并转换为 `:slot-wheel` 事件；Final 事件图按 owner session 的
  `:desired-distance` 做 clamp 写回，start/pulse/release 统一读取同一快照。
- `dispatch-intent!` 额外要求 `:slot-wheel` 必须命中该 owner 的活动会话，避免伪造或跨玩家修改；
  没有恢复旧 RPC/channel 或技能 callback。剩余 `⚠️` 仅是目的地 adapter、碰撞边界和多人实机验证。

### directed-blastwave checkpoint（逐项复核）

- 对照 `main` 的 release 路径确认资源只应扣除一次；当前 Final 原先在显式 CP/过载
  扣费成功后又执行了一次 `:input :budgets :release`，属于确定的双重扣费。
- 已删除重复 `:cost/spend` 节点，保留单一事务扣费、AOE damage/knockback、方块破坏、
  hit/miss progression、cooldown 和统一 VFX 链；没有恢复旧 handler 或新增兼容分支。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。资源适配器实际扣费、
  方块权限和多人同时施放仍需实机任务验证，故总表继续保持 `⚠️`。

### blood-retrograde checkpoint（逐项复核）

- 对照 `main`：蓄力期间的 charge-slow 属于 owner-only presentation，自动释放、手动
  release、无目标和资源不足都必须结束该 session 效果；不能依赖旧 handler 的隐式清理。
- 当前 Final 已在 pulse 自动释放和 release 的统一入口显式 destroy
  `:blood-retrograde-charge`，abort 路径也保持显式 destroy；伤害、fan raycast、费用、
  命中 progression/cooldown 仍由同一 graph 执行，没有恢复旧逻辑。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。charge-slow 在各版本
  Presentation renderer 的本地走速应用仍需实机确认，总表继续保持 `⚠️`。

### directed-shock checkpoint（逐项复核）

- 对照 `main` 的命中 release 路径：伤害、精通阈值后的实体位移/击退、音效、命中经验和
  cooldown 后才结束会话；命中与 miss 都要销毁第一人称手部动画。
- 当前 Final 命中分支此前遗漏了 hand-session destroy，已补齐；自动 punch、miss、资源
  不足和 abort 路径保持同一清理契约，没有引回旧 handler。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。实体击退 adapter 的
  实机结果与多人并发仍待运行时任务验证，总表继续保持 `⚠️`。

### plasma-cannon checkpoint（逐项复核）

- 对照 `main`：蓄力开始时龙卷柱的地面基准来自向下 block raycast；无命中时必须使用
  射线终点，不能把 nil 位置传入后续 VFX。飞行、路径阻挡、到达/超时爆炸和资源/冷却
  仍由 Final session pulse/release 图负责。
- 当前 Final start 增加 `target/resolve-destination`，以 `target-hit?` 在精确命中点和
  向下射线终点之间选择 `vortex-base`；没有复制 main 的 helper，也没有增加旧回调通道。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。飞行碰撞、爆炸地形权限和
  多人 VFX audience 仍需实机任务验证，总表继续保持 `⚠️`。

### vec-accel checkpoint（逐项复核）

- 对照 `main`：蓄力每 tick 需要同时刷新玩家 eye/look、地面检测、初速度和轨迹预览；
  Final pulse 的轨迹原点引用了 `:local :eye`，但原先 caster bind 遗漏该字段。
- 已在当前 Final pulse 绑定 `:eye :eye`；速度、可执行条件、费用、实体 motion、摔落
  重置、经验/cooldown 和 owner-only 轨迹仍通过现有中性节点执行，无旧回调双轨。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。运动 adapter、碰撞和
  多人 owner 作用域仍需实机任务验证，总表继续保持 `⚠️`。

### storm-wing checkpoint（逐项复核）

- 对照 `main`：充能阶段只推进 charge/悬浮和预览；进入 flying 后才按 tick 扣除
  CP/overload、累计 flight progression，并在资源不足时恢复 `can-fly?`、销毁四个
  vortex、粒子和循环音效。
- 当前 Final 原先把 `cost/spend :flight` 放在 phase 分支之后，导致充能阶段也扣费；
  已保存 pulse 进入时的 `phase-before`，并以它作为 `:scale` 条件（进入时 phase 0
  为 0，进入时 phase 1 为 1），避免充能完成的转换 tick 因 session 写入 phase=1 而
  多扣一次。不引入旧 handler 或第二条扣费路径；资源不足清理和 owner/world 作用域保持不变。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。飞行 motion adapter、
  软方块权限、范围击退与多人同时飞行仍需实机验证，总表保持 `⚠️`。

### groundshock / vec-reflection / vec-deviation checkpoint（逐项复核）

- `groundshock` 的 Final 图已覆盖 main 的地面传播、方块替换/破坏、AOE 伤害、随机
  向上速度、精通经验、资源/冷却和 hand-session/冲击波 VFX；传播结果先在中性
  `terrain/propagate` 形成 bounded plan，再由 block/entity actions 提交，避免跨玩家
  共享可变集合。本轮未发现确定性缺口，保留 `⚠️` 仅因 adapter/runtime 未实测。
- `vec-reflection` 与 `vec-deviation` 均通过 owner-scoped `flow/once` 去重 projectile，
  使用统一 reflection scan、damage policy、overload floor 和终止 VFX；未恢复 main 的
  旧 projectile callback。`vec-deviation` 的所有终止分支均为无状态 session，当前无需
  代码修改；多人隔离仍由 owner/world query 边界负责，待实机确认。

### railgun checkpoint（逐项移植）

- 对照 `main`：Railgun 的两条主路径已按当前 Final ABI 移植：owner-scoped 硬币查询按
  `motion-progress` 降序选择候选；事件阶段同时检查 active/perform 阈值，命中后销毁硬币并
  请求 `next-phase :release`；没有命中时只结束本次事件，不会跨玩家取硬币。
- 铁物品 fallback 使用 owner session 的 `hold-ticks`，服务器 pulse 达到 `item-charge-ticks`
  后请求 release；release 再重新读取主手并消费铁锭/铁块。资源消耗使用现有 `cost.down`/
  `cost.tick` tunable，不再引入未配置的 `cost.fire` 或旧回调。
- 主射线继续使用通用 `combat/beam-strike`；该 composite 现按中性
  `reflection-policy` 路由反射目标与伤害，Railgun 不包含 skill-specific host callback。命中
  creeper 通过 `:achievement/trigger` domain event 进入 AC 组合根。
- 仍保留 `⚠️`：main 的反射命中经验细分、按键按下时已有硬币的即时路径，以及真实实体运动
  时序/VFX 客户端表现需实机验证；静态门禁不把这些未测项伪装成 `✅`。
### rad-intensify checkpoint（逐项复核）

- main 的 Rad Intensify 本身是被动技能：它不在按键图中执行副作用，而是在目标拥有
  radiation mark 且收到正向战斗伤害时，把创建该 mark 时的倍率乘到伤害上；倍率经验来自
  `max-cp / init-cp(level5)`，并限制在 `[0,1]`，不是普通 skill-exp。
- 当前 Final 实现的 `mark-policies` + `damage-policies` 正好落在这条边界：`entity/mark`
  由 AC 组合根校验 `requires-ability`、快照 source rate 并按
  `[world-id,target-id,mark-type]` 保存；`final-damage` 只在 `:mark-type :radiation` 且
  `base > 0` 时读取 `[:input :context :mark :rate]` 做乘法。没有把 main 的被动函数或
  damage handler 复制进来，也没有第二套触发路径。
- 已核对 tunable：`damage-rate=[1.4,1.8]`、`mastery-denominator=8000` 与 main 的
  level-5 初始 CP 默认值一致；mark duration=60，VFX 使用 target-position、nearby
  audience 和 mark-specific instance key。source rate 在 mark 更新时替换，符合 main
  后写覆盖前写的语义。
- 本项不需要 EDN/代码修改；剩余 `⚠️` 仅表示 mark 表跨重启不持久化、VFX 到达率和
  原生伤害 adapter 尚未实机验证。已通过现有 AC/Combat Core 静态编译门禁，不能将这些
  未测运行时项标为完成。
### mine-ray-basic/expert/luck checkpoint（检查与修复）

- 三个 public registration 共用一个 `mine-ray` Final source；manifest 只注入变体参数：Basic 为
  10 格、工具等级受限、fortune=0，Expert 为 20 格且不限制工具等级，Luck 在 Expert 基础
  上使用 fortune=3。三者的 hardness 快照、同目标逐 tick 扣减、换目标重置、方块掉落、
  方块经验、overload floor、冷却和 beam/progress/particle/audio VFX 均在同一 Final graph
  内完成，没有旧回调双轨。
- 对照 main 后发现确定性缺口：main 在每次目标获取时还检查该技能的
  `skill-destroy-allowed?`；此前 Final 只检查世界权限/工具等级，关闭技能破坏开关时仍
  可能推进进度并在 break 分支发放经验。现在 AC 组合根通过中性 capability
  `:ability/destroy-blocks?` 提供每个 owner/ability 的策略，Final graph 在“同目标继续”和
  “新目标获取”两个分支都消费该 capability；关闭时立即清空目标状态，不产生进度、破坏或
  经验。
- `block/break` 仍由 Combat Core 的中性平台 action 执行，携带 expected-block-id、fortune
  和 tool-tier policy；权限、世界隔离和内部 break gate 留在 adapter。这样不会把 AC 的技能
  配置泄漏到 Combat Core，也不会因多个玩家共享方块目标而互相修改 session。
- Final `block/break` 现在使用 barrier 返回的 `:status`，只有 `:applied` 才提交 block
  progression；失败时清空目标状态，不会发放经验。共享边界仍需保留为 `⚠️`：Final tick
  的原子 cost-fail 顺序，以及多人同时改变同一方块的实际 adapter 结果无法在当前无实机
  条件下证明与 main 完全一致；
  不得以旧 `mine_rays_base` 回调恢复双轨。
- 本轮 `:ac:runAcEdnCoverageTests`（14/33）及 `:combat-core:runCombatClojureTests`
  （25/65）通过；这是加载/编译 checkpoint，不等价于实机行为验收。
### mag-manip checkpoint（检查与修复）

- Final pulse 现在先以 `owner-id + world-id + entity-type` 查询持有实体，写入
  `body-id` 并计算有效距离，再通过 `cost/spend :scale` 决定是否扣 CP/过载；这与
  main 的“仅在持有实体接近玩家时收费”一致，也让首次 pulse 资源不足时能够设置
  `place-when-collide?`，避免实体遗留。
- homing、throw fallback、too-far/entity-missing/abort 的 VFX 和实体清理路径保持在
  Final 图内，没有恢复旧 `MagManipContext` 回调。
- 持有实体现在由 spawn barrier 返回 UUID 并写入 session，pulse/release/abort 只使用该 UUID
  加 owner/world 作用域；不再因为同一玩家存在其它同类实体而选错目标。投掷实体的碰撞
  伤害/放置仍依赖平台实体行为，spawn 失败后的跨 action 回滚仍需共享事务或实机 adapter
  验证，故总表继续保持 `⚠️`。

### mag-movement checkpoint（检查结论）

- 目标解析、normal/weak metal 过滤、entity 目标存活检查、持续移动/overload floor、
  释放时 reset-fall-damage、距离经验和 nearby VFX 均已有 Final 节点。
- 发现确定性缺口：`start` 图没有执行 `costs.activate` 的 `ability/budget + cost/spend`，
  只用资源快照计算了 floor；因此激活不会扣除 main 要求的 down overload，也没有资源
  不足的权威结束分支。
- 已在 Final `start` 的唯一入口加入 `cost/spend :activate`，以 `caster/creative?` 做
  scale（creative=0），不足资源立即以 `:insufficient-resource` 结束；之后才进入目标
  解析分支。没有恢复旧回调或硬编码补扣。
- `:ac:runAcEdnCoverageTests` 通过（18 tests / 44 assertions）。目标实体/方块运动、
  权限和多人并发仍需实机验证，总表继续保持 `⚠️`。

### mine-detect checkpoint（逐项迁移）

- Final 图已覆盖资源不足拒绝、blindness、普通/advanced 扫描模式、owner-only
  `block-scan-transient`、经验和冷却节点。
- 已将 VFX `max-range` 从硬编码 `28.0` 改为与扫描 `targeting-range` 相同的 Final
  tunable；否则高经验的 30 格扫描会被 bounds 截断。
- 本轮补齐 post-cast cooldown：Final 图读取配置端点和 `exp-cast`，以
  `mastery + exp-cast` 做 `math/lerp` 后 `math/floor`，再启动 cooldown，匹配 main 的
  `addSkillExp` 后 `Float#toInt` 语义；未保留技能 callback。
- 已通过 `:ac:runAcEdnCoverageTests`（20 tests / 52 assertions）。扫描 adapter、盲目状态
  和多人 VFX 仍需实机验证，故总表保持 `⚠️`。

### thunder-bolt checkpoint（检查与修复）

- Final 图覆盖 main 的 living-only 射线、direct target 与 AOE 排除关系、无命中时的
  full-range 端点、闪电/VFX、direct/重复 AOE slowness、creeper 充能、有效/无效经验
  和资源/冷却顺序。
- 已修复 direct target 分支读取不存在的旧 `:creeper?` 字段，统一改为检查中性命中结果
  的 `:entity-type`（兼容两个平台描述 id 形式）。
- 已修复 AOE 过滤遗漏施法者的问题：Final `target/entities` 现在同时排除
  `caster/id` 与 direct target，匹配 main 的 `#{player-id target-uuid}` 作用域。
- 已通过 `:ac:runAcEdnCoverageTests`（21 tests / 53 assertions）。技能伤害全局缩放及
  实机雷击/VFX 时序仍需验证，不在该技能内保留旧实现或硬编码绕过。

### thunder-clap checkpoint（检查与修复）

- Final 图覆盖蓄力 start/pulse/release/abort、最大蓄力自动释放、最小蓄力判断、
  CP/overload 费用、charged-area-damage、距离 falloff、overcharge、闪电、环形
  VFX、成就和 session 清理。
- 发现并修复 charged-area-damage 的伤害表达式错误引用 `[:item :position]`：Final
  引擎没有 `:item` 作用域，该表达式会得到 nil；现在只传基础伤害×overcharge，实体
  距离 falloff 由中性 `combat/charged-area-damage` host 统一计算。
- 已通过 `:ac:runAcEdnCoverageTests`（21 tests / 53 assertions）。该技能的主线 cooldown
  本来就在经验提交前计算，当前 Final 顺序保持这一语义；公共 damage scaling 与实机
  蓄力/闪电时序仍需验证，当前总表保持 `⚠️`。

课程别名不进入战斗图修复队列，但仍保留在最终总验收中：

```text
electromaster/brain-course
meltdowner/brain-course
teleporter/brain-course
vecmanip/brain-course
electromaster/brain-course-advanced
meltdowner/brain-course-advanced
teleporter/brain-course-advanced
vecmanip/brain-course-advanced
electromaster/mind-course
meltdowner/mind-course
teleporter/mind-course
vecmanip/mind-course
```

每个战斗技能的完成门槛是：

- 以 `main` 对应 namespace 的效果/分支/副作用为行为基线，只移植到当前 Final
  graph，不复制旧函数、旧 VM 或兼容分支；
- graph 中所有 query/action/policy 都能落到 Combat Core、VFX Core 或 AC 组合根的
  明确端口，不能出现“只声明、无 handler”的节点；
- 成功、失败、取消、超时四类路径分别核对资源、冷却、经验、实体清理和 VFX
  destroy/update；
- 所有写入和广播带 owner/world 作用域，不能共享其它玩家的 session、mark、实体或
  VFX instance；
- 完成后只运行该技能的静态/编译门禁和针对性回归，再进入下一项；最后才执行一次
  50 项 catalog、VFX、能力端口、多人作用域静态总验收。

## 本轮新增公共链路修复

- `final-damage` 的 `mark-type` 现在读取当前策略的 `:input :context`；此前错误读取顶层 metadata，会让 radiation policy 静默不匹配。
- radiation mark 在写入时保存 source rate，伤害读取该快照；这与 `main` 的 `mark-target!`（后续命中替换 source/rate）一致，而不是按受击者当前 CP 重新计算。
- 资源型吸收反应在 CP/过载不足时会被移除，避免先减少伤害再提交失败；非阻塞的 VecDeviation/VecReflection 仍保留 main 的“尽量扣费、继续处理”语义。
- `:cost/spend` 已补齐 final ABI 的 `:scale`、`:partial?` 和 `:on-insufficient`：前两者分别用于创意模式/按剩余资源扣费，后者现在真正执行显式失败清理分支，不再“只编译不分支”。
- `:entity/discard` 已支持 UUID 以及 owner+entity-type 两种中立请求；后者先限定同一 world，再同时匹配 owner/type，避免清理其他玩家的会话实体。
- Final hold-session 的 `pulse` 已统一由 server tick 驱动：客户端 slot tick 只作为输入/界面通知，`combat-runtime/tick!` 每个服务器 tick 按 owner 快照逐个派发一次 `:pulse`，并注入经过的 `:hold-ticks`。这样 ScatterBomb、BloodRetrograde、DirectedBlastwave 等蓄力技能不依赖客户端 tick，且仍只经过 `dispatch-intent! → Final execute!` 这一条路径。
- `:start` 图若返回 `:finish-session? true`（例如激活资源不足）不再登记 owner session，避免后续 server tick 向已经失败的技能继续 pulse。
- final session 读取同时保留会话元数据和 `:state`，外部事件图可安全读取 `:ability-id` 等元数据而不会丢失 owner-local 状态。
- 暴击 feedback 已转成 `:player/feedback`，由 AC 的玩家反馈 adapter 发送；damage-reaction VFX 使用策略输入中的 owner，而非一律使用攻击者。
- 暴击策略现在尊重 `:damage-types`，并按 level 0→1→2 的顺序合并概率；同一等级只产生一次 VFX/side-event，匹配 main 的 `roll-crit-level`。
- LightShield 未携带 front flag 时由 AC 组合层按目标朝向和攻击者位置计算 horizontal-yaw cone，不再默认所有攻击都是正面。
- VecReflection 现在始终提交一次反射并从原生伤害中扣除反射量；只有达到 main 的 `min-reflected-damage` 才在 attack-precheck 阶段取消原生攻击，环境伤害不会生成无效反射目标。
- Final `flow/finish` 现在真正终止当前 sequence/foreach 的后续节点；`flow/control` 的
  `:skip-item` 只跳过当前 foreach 项；无 `:else` 的 branch 安全地 no-op。这避免了
  资源失败分支继续落入伤害、传送或其它副作用。
- `entity/teleport` 端口现在兑现 EDN 中的 `:dismount?` 与
  `:reset-fall-damage?`：传送前尝试解除玩家载具，传送成功后清除摔落伤害状态；
  非玩家实体在没有玩家运动适配器时仍可正常传送。这样标记、穿透、转移和危险传送
  的公共传送语义不再只是“传参但被忽略”。
- `mark-teleport` 的 release 阶段改为读取该 owner 会话最后一次 server-pulse 写入的
  `:destination` 快照，不在松键瞬间重新 raycast。目标、距离和 CP 计算因此与 main 的
  “按住期间持续更新、释放使用最后目标”一致，也避免网络时序造成跨玩家/跨 tick 漂移。

## 公共链路证据

### final-damage input ABI

EDN policy 使用 `[:input :context ...]`、`[:input :tunables ...]` 和 `[:input :params ...]`。当前 `final-damage` 已支持 `:input` scope，并由 AC 按 policy ability-id 注入 immutable 输入快照。

结果：公共 `:input` ABI 已支持并按 policy ability-id 选择快照；仍有少数技能所需的主线参数（例如 Railgun QTE）未建模，不能把所有 policy 视为等价。

### mark 持久化

AC 的 `:entity/mark` capability 现在落入按 `[world-id,target-id,mark-type]` 隔离的有界标记表，支持到期与 owner/target 清理；同一 world/target/type 的新标记会替换旧标记，符合 main 的 target entity 单标记语义；damage policy 会读取有效 mark。该表尚未做持久化存档，跨重启恢复仍属于实机/持久化任务。

### VFX 发送

Combat Core 返回 `:vfx-signals`；AC `finalize-result!` 现已将 graph 与 damage-reaction VFX 统一编码后发送到 `MSG-COMBAT-VFX`，并按 owner/nearby audience 路由。未运行实机，故仍需验证实际到达率和跨世界过滤。

### 多人隔离

session、damage reaction、mark 和 VFX audience 现已携带 owner/world 边界；mark 使用 `[world-id,target-id,mark-type]`，反射/结果使用 `[world-id,source,target,seed]` 幂等键。交叉玩家不泄漏仍需实机测试。

本轮又修复了同一玩家同时使用多个 MdBall 技能时的交叉清理：`electron-missile`、
`electron-bomb`、`scatter-bomb` 现在分别在 `entity/spawn` 写入
`ac_electron_missile`、`ac_electron_bomb`、`ac_scatter_bomb` 标签；Combat Core 的
`target/entities` 与延迟 beam 的 `origin-selector` 使用 `required-tags` 精确筛选，
Minecraft adapter 在生成实体后同一中立边界内写入标签。这样清理/发射不会误取同一
owner 的另一技能实体；提交为 `c70a49f0c`。这属于公共实体 ABI，不是 AC 技能专属
兼容分支。

### AC 越界静态扫描

在 AC 主源码中，技能相关的 `apply-direct-damage!`、VFX nearby 推送和实体副作用调用只出现在 `combat_runtime.clj` 的能力实现/组合边界；技能内容与 server hook 没有再直接调用这些平台函数。`server_hooks` 的伤害入口只转发到 `process-damage-request!` / `apply-attack-precheck!`，物品入口只产生 neutral `:edn-trigger`。因此当前发现的越界不是“绕过三核心模块”，而是 Railgun 的特殊硬币/QTE 语义尚未在中间能力中建模。

### 50 项统一静态验收（本轮）

- 以 `main` 的 `defskill` 集合抽取到 38 个真实 skill id；当前 Final catalog 对应 38 个真实 registration，另有 12 个课程别名，共 50 个 registration。catalog 编译结果为 39 个 combat source（Mine Ray 三变体共用一个 source）、36 个 VFX effect；50 项均为 `:engine :final`，`:ac:runAcEdnCoverageTests` 的 21 tests / 53 assertions 全部通过。
- `verifyCoreNoSkillKnowledge`、`verifyEdnNodeCoverage`、`verifyEffectRuntimeJavaCarriers`、`verifyNoGeneratedClojureTypes`、`verifyNeutralClojureNoMinecraftApis` 全部通过；未发现 ability 内容直接调用 AC/Minecraft adapter。Railgun 的 QTE/charge 状态机已移植到单一 Final 图；其组合边界是“消费硬币后生成实体，再触发 Final external event”，提交为 `b4ee9d989`。事件与 release 的 next-phase 继续经过 owner-scoped runtime；反射则由通用 beam composite 承载。剩余仅是上面列出的运行时等价验证项。
- 函数式风格静态结论：Combat Core 的技能执行是不可变 graph + 纯表达式求值；VFX/Core 与 Presentation 的 `atom/volatile!` 仅用于有界 runtime registry、帧/实例生命周期和复制序号，不承载技能业务状态。技能 session、mark、伤害上下文均通过 owner/world keyed immutable patch/transaction 传递。这样满足“函数式组合、命令式边界适配”的分层，但最终 CPU/GC/内存仍需实机 profiling，不能由静态检查推断性能达标。
- 多人边界静态确认：session 按 owner、mark 按 `[world,target,type]`、damage/VFX 幂等键带 world/source/target/seed；target/entity 查询要求 owner/world 过滤。跨玩家不互相影响仍需实机并发场景验证。

## 修复顺序与提交点

1. **Context ABI**：已完成并提交（`46f3dc0aa`）。
2. **Mark domain**：已完成并提交（`7b8510bd0`）。
3. **Damage result commit**：已完成并提交（`46f3dc0aa`、`2478da3c2`、`d9cff8772`、`19195dbcf`）；参数/费用仍需行为测试。
4. **VFX transport**：已完成并提交（`46f3dc0aa`、`2478da3c2`）。
5. **Neutral entity isolation**：`entity/spawn` 的 `add-tags`、实体查询/延迟
   selector 的 `required-tags` 已完成并提交（`c70a49f0c`）。
6. **单技能确定性修复**：location teleport、light shield、thunder bolt、mark-teleport、
   flesh-ripping、directed-blastwave、directed-shock、blood-retrograde、plasma-cannon、
   vec-accel、storm-wing、mag-movement；均已按技能单独提交（最近提交分别为
   `26dee6fa3`、`a2618d29d`、`fba68d32a`）。
7. **Registration bindings**：把 Mine Ray 的 variant/presentation/runtime 显式注入；提交。
8. **Railgun capability**：已完成 Final 图移植；QTE/蓄力/实体隔离/反射/成就均有中性节点或通用 composite，剩余反射经验细分和实机时序验证。
9. **Passive reducer**：实现三种通用课程被动效果并按 owner 状态提交；提交。
10. 重新运行 Clojure/EDN/全平台编译门禁；运行时多人、VFX 和性能测试另行执行。

## 本轮验证结果

- `:ac:checkClojure`：通过（包含本轮 catalog、runtime 改动）。
- `:ac:runAcEdnCoverageTests`：通过 21 tests / 53 assertions；该门禁只验证
  EDN 解析、注册和有限图执行，不代表与 `main` 行为等价。
- `:combat-core:runCombatClojureTests`：通过 31 tests / 83 assertions（包含本轮
  phase-transition、damage-threshold、teleport safety 回归）。
- `:ac:compileTestClojure`：通过。旧测试中依赖已删除旧 VM/旧 hook 契约的文件已移除，
  没有恢复兼容实现。
- `:ac:runAcClojureTests`：可编译并进入 143 个 namespace、580 tests；仍有历史测试
  假设旧 `combat_runtime` 返回值的失败，以及与本任务无关的 Wind Gen fixture 失败，
  因此不把该整套旧回归作为 Final 行为正确性证据。
- 实机运行、多玩家交叉污染、VFX 网络到达率、CPU/GC/内存尚未测试，必须作为
  后续独立任务完成。
