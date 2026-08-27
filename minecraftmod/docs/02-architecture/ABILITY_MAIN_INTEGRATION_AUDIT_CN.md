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

### 明确缺陷

| 注册项 | main 行为基线 | 当前实现缺口 |
|---|---|---|
| `railgun` | Coin QTE、硬币判定/销毁、物品蓄力、反射射击、经验/成就、主射线 | EDN 只有 `:coin-thrown` 完成事件和普通 beam；QTE、蓄力 tick、反射、经验/成就未迁移 |
| `mine-ray-basic` | 基础变体，工具等级限制，fortune=0，独立冷却 | 共享图读取 `:runtime`，但注册 bindings 没注入 activation runtime |
| `mine-ray-expert` | 专家变体，取消工具等级限制，独立前置条件/冷却 | 同上 |
| `mine-ray-luck` | luck 变体，fortune=3，独立粒子/光束样式 | 同上 |
| `location-teleport` | 仅跨维度时检查经验门槛并应用跨维度倍率 | 当前 `:cross-dimension?` 使用 `:value/eq`，与 main 的 `not=` 相反 |
| `light-shield` | damage reaction 吸收伤害，CP/过载消耗，正面判断，状态和冷却 | final-damage context 没有传入；EDN 中 CP 与 overload 消耗互换 |
| `thunder-bolt` | 目标命中后 AOE/creeper/potion/经验/冷却 | AOE 分支读取不存在的 `[:input :target-type]`、`[:input :target-id]` |
| `dim-folding-theorem` | 学习状态、非反射攻击的暴击/反馈/VFX/成就 | final-damage policy 的 `:input` context 未注入 |
| `rad-intensify` | 读取 radiation mark 并按 max CP 放大伤害 | mark 没有持久化，且 final-damage context 不完整 |
| `space-fluct` | 多级暴击、反射排除、经验/成就/VFX | final-damage policy 的 context/input 未注入 |
| `vec-deviation` | 开启状态下减伤、CP 代价、音效/经验 | final-damage policy 未获得 owner 的开启状态和资源 |
| `vec-reflection` | 开启状态下反射伤害、代价、最大深度/经验 | resolver 产生 `:reflections`，AC 运行时只取数值，未提交反射动作 |
| `jet-engine` | 每 tick 移动、伤害并写入 radiation mark | `:entity-mark` 事件没有有效 reducer/提交路径 |
| `ray-barrage` | 命中后写入 radiation mark，并触发后续行为 | 同上 |

### 课程别名明确缺陷

以下 12 项的 EDN 都声明了被动效果，但当前 catalog 函数是 no-op，因此被动效果不会改变玩家状态：

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

当前实现：

```clojure
(defn apply-passive-resource-modifiers [_ability-data values]
  values)
```

## 其余 24 个真实技能

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
```

这些技能必须继续逐项核对：效果目标和数量、资源扣除、冷却启动条件、延迟任务、方块/实体副作用、VFX audience、异常/中断清理以及 owner/world 隔离。不能用“graph 已编译”替代这些检查。

## 公共链路证据

### final-damage input ABI

EDN policy 使用 `[:input :context ...]`、`[:input :tunables ...]` 和 `[:input :params ...]`，但 `final-damage` 当前只解析 `:request`、`:context`、`:param`、`:session`、`:mark` scope；AC 构造 damage event 时也没有把 activation input 放入 metadata。

结果：相关 policy 会静默读到 nil/默认值，形成“可编译但不执行”的假阳性。

### mark 持久化

AC 的 `:entity/mark` capability 只 dispatch `:entity-mark`，neutral event handler 没有对应分支；Combat Core 的纯 `domain/apply-event` 存在，但 AC 没有调用它并提交 owner/world 作用域状态。

### VFX 发送

Combat Core 返回 `:vfx-signals`，AC `finalize-result!` 只处理 state/session/domain events，没有调用 server network sender。客户端虽然注册了 `MSG-COMBAT-VFX` handler，但没有证明所有 server result 都能到达该 channel。

### 多人隔离

session 基本按 owner 隔离，但 damage reaction、mark 和 VFX audience 还缺少统一的 `[world-id owner-id entity-id]` 作用域证据。修复公共链路时必须同时增加交叉玩家不泄漏测试。

## 修复顺序与提交点

1. **Context ABI**：将 owner/world/ability/activation input/marks 作为统一 immutable metadata 传入 damage policy；提交。
2. **Mark domain**：实现持久化、过期、owner/world 隔离，并连接 mark 查询；提交。
3. **Damage result commit**：提交 reflect、absorb cost、reaction event/state patch；提交。
4. **VFX transport**：将 outbox 按 `:owner`/`:nearby` 路由到 `MSG-COMBAT-VFX`；提交。
5. **单技能确定性修复**：location teleport、light shield、thunder bolt；提交。
6. **Registration bindings**：把 Mine Ray 的 variant/presentation/runtime 显式注入；提交。
7. **Railgun capability**：增加中立 coin-QTE/entity capability，删除旧 adapter 直连路径；提交。
8. **Passive reducer**：实现三种通用课程被动效果并按 owner 状态提交；提交。
9. 重新运行 Clojure/EDN/全平台编译门禁；运行时多人、VFX 和性能测试另行执行。

