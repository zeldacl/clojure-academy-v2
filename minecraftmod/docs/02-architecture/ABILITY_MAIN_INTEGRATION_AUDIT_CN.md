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
闭合；37 个真实技能仍需要运行时等价证据，`railgun` 还有已确认的行为缺失。因此
本轮不会把“EDN 可加载/可编译”包装成“效果正确”。

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
- 50 个 registration 的当前静态结论为：12 个课程别名 `✅*`、37 个真实战斗技能
  `⚠️`、`railgun` 1 个 `❌`。`✅*` 的星号表示课程被动 reducer 已接入，
  但完整学习/重算测试仍受现有测试 classpath 阻断。
- `:ac:checkClojure` 与 `:ac:runAcEdnCoverageTests` 已通过，但这两个门禁不执行 main 行为等价性测试。

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
| `railgun` | 硬币 QTE、铁物品蓄力、硬币判定/销毁、反射射击、经验/成就、主射线 | 硬币/枪体 billboard、rail beam | ❌ item→EDN trigger 路由已补；仍缺 QTE 阈值/蓄力 tick/判定销毁/反射/经验成就完整链 |
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
| `ray-barrage` | 扇形多目标射击；命中后写 radiation mark 并触发后续行为 | ray beam、fan、音效 | ⚠️ mark 提交已接入；扇形命中/后续行为仍需验证 |
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
| `vec-deviation` | 扫描并偏转/销毁投射物；开启时减伤并按伤害消耗 CP | 环形 fade、反射音效 | ⚠️ 开启状态与减伤 policy 已接入；main 的“CP 不足仍按剩余 CP 扣除但继续减伤”需行为测试 |
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
| `railgun` | Coin QTE、硬币判定/销毁、物品蓄力、反射射击、经验/成就、主射线 | item→EDN trigger 和硬币实体生成已在 AC 组合根注册；EDN 仍只有 `:coin-thrown` 完成事件和普通 beam，QTE、蓄力 tick、判定销毁、反射、经验/成就未迁移 |
| `mine-ray-basic` | 基础变体，工具等级限制，fortune=0，独立冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-expert` | 专家变体，取消工具等级限制，独立前置条件/冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-luck` | luck 变体，fortune=3，独立粒子/光束样式 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `location-teleport` | 仅跨维度时检查经验门槛并应用跨维度倍率 | ✅ 已修复 `not=` 逻辑；仍需保存地点/跨维度提交测试 |
| `light-shield` | damage reaction 吸收伤害，CP/过载消耗，正面判断，状态和冷却 | ✅ 已修复 CP/过载映射并接入 final-damage context；资源/夹角需实机验证 |
| `thunder-bolt` | 目标命中后 AOE/creeper/potion/经验/冷却 | ✅ 已修复目标引用并接入统一 VFX；AOE 分支需实机验证 |
| `dim-folding-theorem` | 学习状态、非反射攻击的暴击/反馈/VFX/成就 | input/context、反馈消息和 VFX owner 已接入；主线等级与经验细节需验证 |
| `rad-intensify` | 读取 radiation mark，并使用标记创建时的 source rate 放大伤害 | mark 表、策略输入、mark-type 匹配和倍率快照已接入；跨重启持久化未实现 |
| `space-fluct` | 多级暴击、反射排除、经验/成就/VFX | input/context、反馈消息和 VFX owner 已接入；多级概率与排除反射需验证 |
| `vec-deviation` | 开启状态下减伤、CP 代价、音效/经验 | owner session/context 已接入；main 的“按剩余 CP 尽量扣费但继续减伤”需验证 |
| `vec-reflection` | 开启状态下反射伤害、代价、最大深度/经验；低于 minimum 时仍减少原伤害但不取消原生攻击 | 反射已一次性提交，按 world/source/target/seed/depth 幂等；minimum、残余伤害和环境边界已对齐，参数快照/扣费与递归上限需行为测试 |
| `jet-engine` | 每 tick 移动、伤害并写入 radiation mark | mark reducer 已接入；持续移动/伤害需验证 |
| `ray-barrage` | 命中后写入 radiation mark，并触发后续行为 | mark reducer 已接入；扇形命中与后续行为需验证 |

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
38. `railgun`（唯一已确认缺少 Final 能力建模的项，最后单独处理）

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
### body-intensify checkpoint（检查结论）

- Final 图已覆盖 main 的起始 overload 扣除、逐 tick CP 扣除、overload floor、最小/最大/
  容忍蓄力时长、随机状态效果、hunger、成功经验、超时/资源不足/abort 清理，以及
  owner-only 充能和 nearby 释放 VFX。
- 当前不能宣称完整等价：`score/mark` 的经验事件在图执行后才由 AC reducer 提交，而
  `cooldown/start` 在同一图内使用激活时 materialized tunable；因此“经验增加后再计算
  冷却”的 main 语义需要先补共享 progression→cooldown ABI。禁止在 Body Intensify
  内写技能专属常量或保留旧回调双轨。
- 该项暂不修改 EDN，保持队列中的 `⚠️`，待共享 ABI 修复后重新核验并单独提交。

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
- 当前仍有迁移边界：Final session 只保存球数量，释放/超时按 owner+entity-type 查询清理，尚未像 main 一样保存每个生成球的 UUID；因此同一玩家并行使用其它 MdBall 技能时，实机会发生跨技能清理/计数干扰风险。该问题不能通过旧回调兼容解决，后续需扩展当前 Final 实体生命周期/会话标识 ABI 后再处理。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）；由于上述生命周期标识和 activation cost 时序尚未在 Final 中闭合，总表继续保持 `⚠️`。
### mag-manip checkpoint（检查与修复）

- Final pulse 现在先以 `owner-id + world-id + entity-type` 查询持有实体，写入
  `body-id` 并计算有效距离，再通过 `cost/spend :scale` 决定是否扣 CP/过载；这与
  main 的“仅在持有实体接近玩家时收费”一致，也让首次 pulse 资源不足时能够设置
  `place-when-collide?`，避免实体遗留。
- homing、throw fallback、too-far/entity-missing/abort 的 VFX 和实体清理路径保持在
  Final 图内，没有恢复旧 `MagManipContext` 回调。
- 仍有两个未闭合风险：批处理 action 无法在同一图内分支处理 `entity/spawn` 失败后的
  回滚；投掷实体的碰撞伤害/放置依赖平台实体行为。两者需要共享事务或实机 adapter
  验证，故总表继续保持 `⚠️`。

### mag-movement checkpoint（检查结论）

- 目标解析、normal/weak metal 过滤、entity 目标存活检查、持续移动/overload floor、
  释放时 reset-fall-damage、距离经验和 nearby VFX 均已有 Final 节点。
- 发现确定性缺口：`start` 图没有执行 `costs.activate` 的 `ability/budget + cost/spend`，
  只用资源快照计算了 floor；因此激活不会扣除 main 要求的 down overload，也没有资源
  不足的权威结束分支。
- 该修复必须重排完整 start 图（先扣费，再进入三种目标分支），不能在技能内添加旧
  回调兼容或硬编码补扣；当前保持 `⚠️`，待 Final start 资源模板统一后单独提交。

### mine-detect checkpoint（检查与修复）

- Final 图已覆盖资源不足拒绝、blindness、普通/advanced 扫描模式、owner-only
  `block-scan-transient`、经验和冷却节点。
- 已将 VFX `max-range` 从硬编码 `28.0` 改为与扫描 `targeting-range` 相同的 Final
  tunable；否则高经验的 30 格扫描会被 bounds 截断。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）。main 的 post-exp cooldown 仍受共享
  progression→cooldown ABI 影响，故总表保持 `⚠️`。

### thunder-bolt checkpoint（检查与修复）

- Final 图覆盖 main 的 living-only 射线、direct target 与 AOE 排除关系、无命中时的
  full-range 端点、闪电/VFX、direct/重复 AOE slowness、creeper 充能、有效/无效经验
  和资源/冷却顺序。
- 已修复 direct target 分支读取不存在的旧 `:creeper?` 字段，统一改为检查中性命中结果
  的 `:entity-type`（兼容两个平台描述 id 形式）。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）。技能伤害全局缩放及 post-exp cooldown
  仍属于共享 ABI 问题，不在该技能内保留旧实现或硬编码绕过。

### thunder-clap checkpoint（检查与修复）

- Final 图覆盖蓄力 start/pulse/release/abort、最大蓄力自动释放、最小蓄力判断、
  CP/overload 费用、charged-area-damage、距离 falloff、overcharge、闪电、环形
  VFX、成就和 session 清理。
- 发现并修复 charged-area-damage 的伤害表达式错误引用 `[:item :position]`：Final
  引擎没有 `:item` 作用域，该表达式会得到 nil；现在只传基础伤害×overcharge，实体
  距离 falloff 由中性 `combat/charged-area-damage` host 统一计算。
- 已通过 `:ac:runAcEdnCoverageTests`（14/33）。main 的 post-exp cooldown 与公共
  damage scaling 仍需共享 ABI 修复，当前总表保持 `⚠️`。

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
- final session 读取同时保留会话元数据和 `:state`，外部事件图可安全读取 `:ability-id` 等元数据而不会丢失 owner-local 状态。
- 暴击 feedback 已转成 `:player/feedback`，由 AC 的玩家反馈 adapter 发送；damage-reaction VFX 使用策略输入中的 owner，而非一律使用攻击者。
- 暴击策略现在尊重 `:damage-types`，并按 level 0→1→2 的顺序合并概率；同一等级只产生一次 VFX/side-event，匹配 main 的 `roll-crit-level`。
- LightShield 未携带 front flag 时由 AC 组合层按目标朝向和攻击者位置计算 horizontal-yaw cone，不再默认所有攻击都是正面。
- VecReflection 现在始终提交一次反射并从原生伤害中扣除反射量；只有达到 main 的 `min-reflected-damage` 才在 attack-precheck 阶段取消原生攻击，环境伤害不会生成无效反射目标。
- Final `flow/finish` 现在真正终止当前 sequence/foreach 的后续节点；`flow/control` 的
  `:skip-item` 只跳过当前 foreach 项；无 `:else` 的 branch 安全地 no-op。这避免了
  资源失败分支继续落入伤害、传送或其它副作用。

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

### AC 越界静态扫描

在 AC 主源码中，技能相关的 `apply-direct-damage!`、VFX nearby 推送和实体副作用调用只出现在 `combat_runtime.clj` 的能力实现/组合边界；技能内容与 server hook 没有再直接调用这些平台函数。`server_hooks` 的伤害入口只转发到 `process-damage-request!` / `apply-attack-precheck!`，物品入口只产生 neutral `:edn-trigger`。因此当前发现的越界不是“绕过三核心模块”，而是 Railgun 的特殊硬币/QTE 语义尚未在中间能力中建模。

## 修复顺序与提交点

1. **Context ABI**：已完成并提交（`46f3dc0aa`）。
2. **Mark domain**：已完成并提交（`7b8510bd0`）。
3. **Damage result commit**：已完成并提交（`46f3dc0aa`、`2478da3c2`、`d9cff8772`、`19195dbcf`）；参数/费用仍需行为测试。
4. **VFX transport**：已完成并提交（`46f3dc0aa`、`2478da3c2`）。
5. **单技能确定性修复**：location teleport、light shield、thunder bolt；提交。
6. **Registration bindings**：把 Mine Ray 的 variant/presentation/runtime 显式注入；提交。
7. **Railgun capability**：尚未完成；main 的 coin-QTE、硬币判定/销毁、蓄力 tick、反射射击和经验/成就仍需单独建模，不能以当前 `:coin-thrown` 完成事件代替。
8. **Passive reducer**：实现三种通用课程被动效果并按 owner 状态提交；提交。
9. 重新运行 Clojure/EDN/全平台编译门禁；运行时多人、VFX 和性能测试另行执行。

## 本轮验证结果

- `:ac:checkClojure`：通过（包含本轮 catalog、runtime 改动）。
- `:ac:runAcEdnCoverageTests`：通过 14 tests / 33 assertions；该门禁只验证
  EDN 解析、注册和有限图执行，不代表与 `main` 行为等价。
- `:combat-core:runCombatClojureTests`：通过 25 tests / 65 assertions（包含本轮
  finish/control 回归）。
- `:ac:compileTestClojure`：通过。旧测试中依赖已删除旧 VM/旧 hook 契约的文件已移除，
  没有恢复兼容实现。
- `:ac:runAcClojureTests`：可编译并进入 143 个 namespace、580 tests；仍有历史测试
  假设旧 `combat_runtime` 返回值的失败，以及与本任务无关的 Wind Gen fixture 失败，
  因此不把该整套旧回归作为 Final 行为正确性证据。
- 实机运行、多玩家交叉污染、VFX 网络到达率、CPU/GC/内存尚未测试，必须作为
  后续独立任务完成。
