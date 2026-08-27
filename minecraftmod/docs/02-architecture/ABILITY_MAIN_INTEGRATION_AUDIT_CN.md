# main Ability → Final Combat/VFX 集成审计

> 审计基线：`main` 分支中的 Clojure `defskill`、课程构建器和 AC 运行时逻辑。
> 当前 `ac/src/main/resources/ac/combat/abilities/*.edn` 只作为待验证实现，不能反过来定义行为。

## 判定规则

- `❌`：已经发现确定的行为差异、缺失副作用或当前系统无法支持。
- `⚠️`：技能图和节点大体存在，但被公共链路阻塞，不能宣称与 main 等价。
- `✅`：只有在效果、状态、资源、冷却、事件、VFX 和 owner/world 作用域都有证据时才允许使用；本轮没有把“仅能编译”当作 `✅`。

## 注册规模

- 38 个真实技能实现。
- 12 个课程别名：四个能力类别分别注册 `brain-course`、`mind-course`、`brain-course-advanced`。
- 当前 catalog 结果为 39 个 combat source、50 个 registration、36 个 VFX effect。
- `:ac:checkClojure` 与 `:ac:runAcEdnCoverageTests` 已通过，但这两个门禁不执行 main 行为等价性测试。

## 逐注册项结论

## 50 项逐项功能核对

下表中的“效果/逻辑”来自 `main` 的 `defskill` 实现；“VFX”来自同一技能的
`*_fx.clj`，不是根据当前 EDN 的节点名称倒推。`⚠️` 表示图已经注册，但还
不能证明完整等价；`❌` 表示已经找到确定缺陷。当前没有任何战斗技能可以在
“最终伤害上下文、mark 提交、VFX 网络发送、多人作用域”都闭合前标成 `✅`。

| ability | main 效果与逻辑 | main VFX | 当前接入结论 |
|---|---|---|---|
| `arc-gen` | 射线命中实体造成缩放伤害；命中方块时点火/掉鱼；命中与未命中分别给经验并启动冷却 | beam、命中/端点音效 | ⚠️ 公共 damage/VFX 提交链未闭合 |
| `body-intensify` | 按住蓄力；CP/过载约束；释放随机药水效果、经验和冷却 | 手部弧光、循环音、端点爆发 | ⚠️ |
| `current-charging` | 对主手能量物品或可充能方块持续充能；目标丢失时清理会话 | 充能弧光、循环音 | ⚠️ |
| `mag-manip` | 捕获金属方块/物品实体，持续吸附，释放投掷；碰撞负责伤害/放置/恢复 | 持续弧光、音效、beam | ⚠️ 需继续验证实体碰撞提交 |
| `mag-movement` | 锁定金属方块/实体并牵引玩家；结束时重置摔落并按距离给经验 | 持续弧光、循环音 | ⚠️ |
| `mine-detect` | 视线扫描地雷/实体；失明和资源不足拒绝；成功施加扫描状态并计经验/冷却 | 扫描框/扫描音效 | ⚠️ |
| `railgun` | 硬币 QTE、铁物品蓄力、硬币判定/销毁、反射射击、经验/成就、主射线 | 硬币/枪体 billboard、rail beam | ❌ EDN 缺少 QTE 阈值、蓄力 tick、判定销毁、反射和成就完整链 |
| `thunder-bolt` | 命中目标后闪电、AOE、creeper/potion 分支、经验/冷却 | 闪电冲击 | ⚠️ 目标引用已修复；仍受 damage/VFX 公共链约束 |
| `thunder-clap` | 蓄力范围伤害、闪电、资源和冷却、成就 | 环形蓄力/闪电 | ⚠️ |
| `electron-bomb` | 生成电子球并延迟调度 beam，命中后完成伤害/经验/冷却 | 瞬时电弧 | ⚠️ 延迟实体结果需继续验证 |
| `electron-missile` | 持续蓄力生成多发电子球，锁定目标并发射，资源不足/超时清理 | 粒子、beam fade、音效 | ⚠️ |
| `jet-engine` | 标记目标、持续移动与伤害，结束计经验/冷却 | 环、粒子、屏幕闪烁、billboard | ⚠️ `:entity-mark` 已接入 owner/world 表；持续移动/伤害仍需行为验证 |
| `light-shield` | 伤害反应吸收伤害；按吸收量消耗 CP/过载，处理正面判断、状态和冷却 | 护盾粒子、循环音、吸收音 | ⚠️ final-damage context 已接入；资源提交/正面夹角仍需实机验证 |
| `meltdowner` | beam 逐段伤害并按权限破坏方块，资源/过载地板/冷却 | beam、FOV、粒子、音效 | ⚠️ `host/beam-trace` 结果提交需继续验证 |
| `mine-ray-basic` | 基础射线采矿；工具等级限制、fortune=0、独立冷却 | beam、进度条、粒子、音效 | ⚠️ variant bindings 已注入，仍需行为等价测试 |
| `mine-ray-expert` | 专家射线采矿；取消工具等级限制，独立前置/冷却 | 同 MineRay 专属变体样式 | ⚠️ |
| `mine-ray-luck` | luck=3 射线采矿，专属颜色/粒子 | 同 MineRay luck 样式 | ⚠️ |
| `rad-intensify` | 读取目标 radiation mark，按最大 CP 放大伤害 | 目标 mark session | ⚠️ mark 已按 world/target 持久化；倍率与 mark VFX 仍需实机验证 |
| `ray-barrage` | 扇形多目标射击；命中后写 radiation mark 并触发后续行为 | ray beam、fan、音效 | ⚠️ mark 提交已接入；扇形命中/后续行为仍需验证 |
| `scatter-bomb` | 生成/调度多枚散射弹，资源不足时清理 | 粒子、beam fade、音效 | ⚠️ |
| `dim-folding-theorem` | 学习后为非反射攻击提供暴击/反馈/成就 | 暴击尾迹/粒子 | ⚠️ policy input 已接入；暴击等级/经验仍需验证 |
| `flashing` | 四方向闪现；预览/释放资源检查，传送后保护摔落并计经验 | teleport marker、端点爆发 | ⚠️ |
| `flesh-ripping` | 锥形/射线命中伤害和状态，命中/未命中经验与冷却 | 目标框、粒子、音效 | ⚠️ |
| `location-teleport` | 读取保存地点；跨维度才检查经验门槛并应用倍率 | 传送音效 | ⚠️ `cross-dimension?` 逻辑已修复，仍需验证保存地点/网络边界 |
| `mark-teleport` | 持续保持目标地点标记，释放时扣资源并传送 | marker、ring fade | ⚠️ |
| `penetrate-teleport` | 穿透目标寻找可传送地点；成功传送并计经验/冷却 | marker、音效 | ⚠️ |
| `shift-teleport` | 预览方块放置点，释放放置/掉落物并伤害线路目标 | 目标框、轨迹音 | ⚠️ 方块/物品副作用需最终提交验证 |
| `space-fluct` | 多级暴击，排除反射伤害并给经验/成就 | 暴击尾迹/粒子 | ⚠️ policy input 已接入；多级概率/排除反射仍需验证 |
| `threatening-teleport` | 持有物品进入威胁态；被攻击时传送并伤害，未命中计 miss | 目标框、传送轨迹 | ⚠️ |
| `blood-retrograde` | 扇形/射线反向伤害，资源、命中经验和会话清理 | 蓄力、冲击、音效 | ⚠️ |
| `directed-blastwave` | 蓄力后范围伤害、击退、破坏路径方块 | 蓄力弧、冲击波、音效 | ⚠️ |
| `directed-shock` | 蓄力拳击；命中伤害、位移/击退、命中/未命中经验和冷却 | 第一人称手部动画、音效 | ⚠️ |
| `groundshock` | 传播破坏/替换方块并 AOE 伤害；能量、掉落率、范围随经验变化 | 第一人称动作、地面冲击波 | ⚠️ 方块/实体批处理需验证 |
| `plasma-cannon` | 蓄力飞行弹；命中爆炸、范围伤害和地形破坏，过载/冷却 | 能量球、龙卷柱、端点爆发、音效 | ⚠️ |
| `storm-wing` | 飞行移动、软方块破坏、范围击退、摔落保护和资源扣除 | 飞行粒子、循环音、龙卷柱 | ⚠️ |
| `vec-accel` | 方向加速、碰撞检查、速度/摔落重置、经验和冷却 | 轨迹带、冲量音 | ⚠️ |
| `vec-deviation` | 扫描并偏转/销毁投射物；开启时减伤并按伤害消耗 CP | 环形 fade、反射音效 | ⚠️ 开启状态与减伤 policy 已接入；按伤害扣 CP/投射物路径仍需验证 |
| `vec-reflection` | 扫描并重定向投射物；受到伤害时按倍率反射并扣资源 | 环形粒子、fade/音效 | ⚠️ 反射结果已在攻击预检查一次性提交；参数快照与扣费仍需实机验证 |

课程别名的 12 个注册项（四类别 × `brain-course`、`brain-course-advanced`、
`mind-course`）没有战斗 VFX：它们分别是 `+1000 max CP`、`+1500 max CP +100
max overload`、`CP recovery ×1.2`。本轮已在
`combat-catalog/apply-passive-resource-modifiers` 中实现 owner-local 纯 reducer，
因此这 12 项的资源效果接入为 `✅*`；`*` 表示仍需在完整测试 classpath 恢复后执行
学习/重算回归测试。

### 已确认问题（含本轮已修复项）

| 注册项 | main 行为基线 | 当前实现缺口 |
|---|---|---|
| `railgun` | Coin QTE、硬币判定/销毁、物品蓄力、反射射击、经验/成就、主射线 | EDN 只有 `:coin-thrown` 完成事件和普通 beam；QTE、蓄力 tick、反射、经验/成就未迁移 |
| `mine-ray-basic` | 基础变体，工具等级限制，fortune=0，独立冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-expert` | 专家变体，取消工具等级限制，独立前置条件/冷却 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `mine-ray-luck` | luck 变体，fortune=3，独立粒子/光束样式 | ✅ 已注入 registration bindings；仍需行为等价测试 |
| `location-teleport` | 仅跨维度时检查经验门槛并应用跨维度倍率 | ✅ 已修复 `not=` 逻辑；仍需保存地点/跨维度提交测试 |
| `light-shield` | damage reaction 吸收伤害，CP/过载消耗，正面判断，状态和冷却 | ✅ 已修复 CP/过载映射并接入 final-damage context；资源/夹角需实机验证 |
| `thunder-bolt` | 目标命中后 AOE/creeper/potion/经验/冷却 | ✅ 已修复目标引用并接入统一 VFX；AOE 分支需实机验证 |
| `dim-folding-theorem` | 学习状态、非反射攻击的暴击/反馈/VFX/成就 | input/context 已接入；主线等级与经验细节需验证 |
| `rad-intensify` | 读取 radiation mark 并按 max CP 放大伤害 | mark 表与 policy input 已接入；跨重启持久化未实现 |
| `space-fluct` | 多级暴击、反射排除、经验/成就/VFX | input/context 已接入；多级概率与排除反射需验证 |
| `vec-deviation` | 开启状态下减伤、CP 代价、音效/经验 | owner session/context 已接入；按伤害扣 CP 需验证 |
| `vec-reflection` | 开启状态下反射伤害、代价、最大深度/经验 | 反射已一次性提交；参数快照/扣费与递归上限需验证 |
| `jet-engine` | 每 tick 移动、伤害并写入 radiation mark | mark reducer 已接入；持续移动/伤害需验证 |
| `ray-barrage` | 命中后写入 radiation mark，并触发后续行为 | mark reducer 已接入；扇形命中与后续行为需验证 |

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

## 其余 29 个真实技能

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

## 公共链路证据

### final-damage input ABI

EDN policy 使用 `[:input :context ...]`、`[:input :tunables ...]` 和 `[:input :params ...]`，但 `final-damage` 当前只解析 `:request`、`:context`、`:param`、`:session`、`:mark` scope；AC 构造 damage event 时也没有把 activation input 放入 metadata。

结果：公共 `:input` ABI 已支持并按 policy ability-id 选择快照；仍有少数技能所需的主线参数（例如 Railgun QTE）未建模，不能把所有 policy 视为等价。

### mark 持久化

AC 的 `:entity/mark` capability 现在落入按 `[world-id,target-id,mark-type]` 隔离的有界标记表，支持到期与 owner/target 清理；damage policy 会读取有效 mark。该表尚未做持久化存档，跨重启恢复仍属于实机/持久化任务。

### VFX 发送

Combat Core 返回 `:vfx-signals`；AC `finalize-result!` 现已将 graph 与 damage-reaction VFX 统一编码后发送到 `MSG-COMBAT-VFX`，并按 owner/nearby audience 路由。未运行实机，故仍需验证实际到达率和跨世界过滤。

### 多人隔离

session、damage reaction、mark 和 VFX audience 现已携带 owner/world 边界；mark 使用 `[world-id,target-id,mark-type]`，反射/结果使用 `[world-id,source,target,seed]` 幂等键。交叉玩家不泄漏仍需实机测试。

## 修复顺序与提交点

1. **Context ABI**：已完成并提交（`46f3dc0aa`）。
2. **Mark domain**：已完成并提交（`7b8510bd0`）。
3. **Damage result commit**：已完成并提交（`46f3dc0aa`、`2478da3c2`）；参数/费用仍需实机确认。
4. **VFX transport**：已完成并提交（`46f3dc0aa`、`2478da3c2`）。
5. **单技能确定性修复**：location teleport、light shield、thunder bolt；提交。
6. **Registration bindings**：把 Mine Ray 的 variant/presentation/runtime 显式注入；提交。
7. **Railgun capability**：尚未完成；main 的 coin-QTE、硬币判定/销毁、蓄力 tick、反射射击和经验/成就仍需单独建模，不能以当前 `:coin-thrown` 完成事件代替。
8. **Passive reducer**：实现三种通用课程被动效果并按 owner 状态提交；提交。
9. 重新运行 Clojure/EDN/全平台编译门禁；运行时多人、VFX 和性能测试另行执行。

## 本轮验证结果

- `:ac:checkClojure`：通过（包含本轮 catalog、runtime 改动）。
- `:ac:runAcEdnCoverageTests`：此前通过 11 tests / 27 assertions；该门禁只验证
  EDN 解析、注册和有限图执行，不代表与 `main` 行为等价。
- `:ac:runAcClojureTests`：未能进入测试执行，`compileTestClojure` 被仓库现有
  classpath 问题阻断（缺少 `cn/li/combat/skill_runtime`、
  `cn/li/combat/structural_primitives` 和 AC developer reactive 命名空间）。
  这不是本轮源码编译错误；完整回归需先修复测试 classpath。
- 实机运行、多玩家交叉污染、VFX 网络到达率、CPU/GC/内存尚未测试，必须作为
  后续独立任务完成。
