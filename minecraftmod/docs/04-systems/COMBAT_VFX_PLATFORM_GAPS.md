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
| `9b8077266` | `execute-thunder-clap!`/`execute-blood-retrograde!`/`execute-plasma-cannon!`/`execute-meltdowner!` 四个 world-effect 执行器（见下方 C 节） |
| `b55116f6f` | `:block-scan` query + `execute-mine-ray!`（mine-ray-basic/expert/luck 三个 skill id）、`:storm-wing` query + `execute-storm-wing!`（见下方 C-2 节） |
| `626be7705` | `:light-shield`/`:electron-missile`/`:scatter-bomb` query + 三个保守简化执行器（见下方 C-2 节） |
| `9d7c1fa72` | `:knockback` world-effect case 分支 + `execute-knockback!`（`directed-shock`，见下方 D 节） |
| `21d7b1187` | `mark-teleport`/`penetrate-teleport`/`flashing`/`location-teleport`/`threatening-teleport` query + world-effect，approval-token 桥接机制（见下方 A 节） |
| `decc4ec30` | `:mag-manip`/`:mag-movement` query + `:mag-manip` 执行器重写（见下方 B 节） |

## 分类清单

### A. 传送类（2026-08-17/18 追加会话，5/6 已完成）

原受影响技能：`mark-teleport`、`penetrate-teleport`、`shift-teleport`、`threatening-teleport`、`flashing`、`location-teleport`。

**执行前的重要发现**：读技能翻译文本后发现 `shift-teleport`/`threatening-teleport` **根本不是玩家传送**，尽管它们和另外 3 个共用同一个 `:teleport-approved-target` world-effect。`shift-teleport`（"将方块运用坐标移动的方式高速发射出去...因为其运动方式...所以并不会因为其速度与质量而影响破坏力"）是一个**块体弹道+路径伤害**机制，没有任何弹道/传播算法可复用，和 groundshock 同一复杂度级别。`threatening-teleport`（"将小质量的物品移动到自己附近的某个位置。有时候，只需要一小块体内的碎片就可以对生物造成巨大伤害"）实质是"瞬间对附近敌人造成伤害"，不是真的移动物体。

**已完成（mark-teleport、penetrate-teleport、flashing、location-teleport、threatening-teleport）**：

- **`mark-teleport`/`penetrate-teleport`/`flashing`**：复用了已经存在、已经过测试、平台无关的目的地求解代码——`ac/content/ability/teleporter/{mark_teleport_dest,penetrate_dest,flashing_dest}.clj`，三个文件都明确标注是"upstream `MTContext`/`PTContext`/`MainContext` 端口"，不是重新发明的逻辑。`flashing` 只做了前方闪现：`combat_content.clj` 的 query 节点没有传 `:direction` 字段（原版支持 WASD 相对方向闪现），固定用 `:forward`，是一个真实的简化，代码里有注释标注。`penetrate-teleport` 的行进算法在 `:available? false`（还在墙里）时正确返回 nil（视为"无目的地"），不会把玩家传送进方块里。
- **`location-teleport`**：复用了已经完整、已经在跑的已保存位置基础设施（`location_teleport.clj` 的公共函数 `query-location-teleport`、6 个 loader 都装了的 NBT 存储 `named-position-store`），没有重新实现。**故意没有**调用 `location_teleport.clj` 自己的 `perform-location-teleport!`——那个函数有自己的一套 CP 距离公式和冷却逻辑（服务于 RPC/UI 流程），在 world-effect 里调用会和 combat-core 自己声明的 `:cost` 重复扣费。全仓库没有任何"主/默认保存点"的约定（UI 一直是玩家从列表里选名字），给"没有输入名字的快捷键激活"选了一个确定性的默认值——按名字字母序取最靠前的——这是推断的 UX 选择，不是确认过的设计，已在代码注释标注。
- **`threatening-teleport`（保守实现）**：改为直接造成伤害（`entity-damage/apply-direct-damage!`），不走传送/approval-token 路径。给 `combat_content.clj` 的 world-effect 步骤加了 `:damage (scale 3.0 6.0)`（跟随全文件已有的 `thunder-clap` 等同款模式）。跳过了 `needle-damage-multiplier`（持"针"类物品的伤害加成）与"碎片"掉落概率机制。

**approval-token 桥接机制**：`mcmod/platform/teleportation.clj` 新增 `mint-approval-token!`/`redeem-approval-token!`（内存 atom，短 TTL）。不是安全边界——query 和 world-effect 在同一次 intent dispatch 内同步执行，中间不经过真实时间——只是为了让 `teleport-approved-target!` 的既有函数签名（`owner ability-id approval-token mode`）在全部玩家传送 mode 上保持统一。`combat_runtime.clj` 的 `:teleport-approved-target` case 改为按 `mode` 分派：`:threatening` 直接扣血；`:mark`/`:penetrate`/`:flashing` 铸造 token 走真实传送；`:shift-teleport` 落进同一分支但因为不在 `ability-id` 白名单里而干净地失败（不会崩，只是 `:status :failed`）。

**仍然推迟（`shift-teleport`）**：核心机制是块体弹道 + 路径伤害，没有可复用的传播/弹道算法，也没有权威参考实现——和 C-2 节的 `groundshock` 同一档，需要设计输入，已与用户确认单独推迟。

**验证状态**：`:ac:checkClojure`、`:platform:compileClojure`（`forge-1.20.1`/`neoforge-1.21.1`）通过，`verifyCombatSkillCoverage` 报告 38 个技能一致，`combat-core`/`vfx-core` 测试套件不受影响。**均未进游戏验证**——`mark-teleport`/`penetrate-teleport`/`flashing` 的落点几何计算、`location-teleport` 的默认位置选择，这些都需要进游戏确认才能信任。

### B. mag-manip / mag-movement（2026-08-18 追加会话，已完成，`decc4ec30`）

**上一版工单的前提是错的**：不是"没有金属判定逻辑"，是 combat-core 迁移把它删掉了、但没删干净。`git log -- ac/src/main/clojure/cn/li/ac/content/ability/electromaster/mag_manip.clj` 显示 `a8c000766`（"switch production to Combat Core, delete legacy defskill chain"）删除了一份完整可用的旧 defskill 实现——它就是"什么算金属"的答案：`ability-config/is-metal-block?`（`ac/src/main/clojure/cn/li/ac/ability/config.clj:432`，配置驱动的 normal + weak 金属方块 allow-list，`targeting.metal.normal-metal-blocks`/`weak-metal-blocks`），这些函数**在迁移后依然完整存在于当前 `config.clj`**，只是没人在 combat-core 侧调用它们。之前"搜过 ac/src/main/clojure 全目录找不到"的结论是搜索范围/关键词不对。

**mag-movement：直接可做，无新平台面**。`execute-mag-movement!`（platform-src 早已实现且可用）只需要 query 产出 `:target-x`/`:target-y`/`:target-z`。实现：raycast 沿视线方向找 `is-metal-block?` 命中的方块（`raycast/raycast-blocks-matching` + `ability-config/get-normal-metal-blocks`/`get-weak-metal-blocks`），返回命中方块中心点。

**mag-manip：比预想复杂得多**。旧版 `execute-mag-manip!` 读 `:entity-uuid`/`:position`/`:throw-target`，对应旧 defskill 的真实机制：抓取时生成一个**真实物理实体**（`ScriptedBlockBodyEntity`，三个 MC 版本都完整保留，`entity-motion` adapter 也仍在），悬浮跟随视角，松手后靠自己的碰撞造成伤害/放置方块。要接上这套机制需要一个新的 uuid→Player 平台操作——`mcmod.platform.entity` 里所有的生成/手持物品函数（`player-spawn-tracked-entity-by-id!`、`player-get-main-hand-item-id` 等）都要求调用方已经拿到一个 `Player` 对象，而 combat-core 的 query-port 函数手里只有 uuid 字符串；`raycast`/`block-manipulation` 之所以能在 query 侧直接用，是因为它们的平台适配器自己内部做了 `query-core/get-player-by-uuid` 解析，`entity.clj` 没有这一层。工作量接近整个 A 类传送，已和用户确认改用保守简化版：

**已完成的保守实现**：
- `:start`（抓取）query：raycast 找视线前方 `grab-range` 内的金属方块，用 `block-manipulation/can-break-block?`/`break-block!` 击碎，把 `{:block-id :world-id}` 存进一个 owner-keyed 原子 `mag-manip-held*`（`combat_runtime.clj`，AC 层）。这是 mine-ray/electron-missile 已经用过的"外部原子桥接跨 tick 状态"技巧，放在 query 侧是因为抓取阶段不需要任何实体生成 API。
- `:release`（丢出）query：查 `mag-manip-held*`，没有则 `:require` 失败；raycast-combined 找视线前方 `throw-range` 内的实体，无论有没有命中都清空原子条目（脱手即松手，未命中不退还）。
- `execute-mag-manip!`（platform-src）整体重写：读 `{:block-id :target-uuid}`，命中实体则 `entity-damage/apply-direct-damage!` 直接造成伤害；不生成实体、不悬浮跟随、不真实抛掷物理、不落地放置方块。原先记录的死代码括号 bug随整个函数体被替换而一并消失。
- `combat_content.clj` 的 world-effect 步骤同步更新：去掉 `:physics :tracked-block-body`/`:collision-authoritative?` 等不再准确的字段，加 `:damage (scale 16.0 40.0)`（推断值，未参照任何权威实现调校，和本轮其余 scale 猜测同一档不确定性）。

**已知遗留**：combat-core 的 `:abort` phase 不会触发任何 query（`combat_content.clj` 没给 mag-manip 定义 `:abort` 步骤），所以如果玩家抓取后中途取消（不是正常松手释放），`mag-manip-held*` 里对应 owner 的条目不会被清理，会一直留到该玩家下一次成功走完抓取→丢出为止才被覆盖。是一个很小的、有界的内存占用（每个曾经中途取消过的玩家一条 `{:block-id :world-id}`），接受为已知简化，不在本轮修。

**验证状态**：`:ac:checkClojure`、`:platform:compileClojure`（`forge-1.20.1`/`neoforge-1.21.1`）通过，`combat-core` 测试套件 25 测试/85 断言全绿。**均未进游戏验证**——尤其是 `:damage` 数值、raycast 命中判定手感，都需要进游戏确认。

### C. world-effect 执行器缺失

**已完成（thunder-clap/blood-retrograde/plasma-cannon/meltdowner，2026-08-17 追加会话）**：`platform-src/minecraft/base/.../adapter/world_effects.clj` 的 `create-world-effects` 新增了 `execute-thunder-clap!`、`execute-blood-retrograde!`、`execute-plasma-cannon!`、`execute-meltdowner!` 四个执行器。全部复用已安装、已验证的 `cn.li.mcmod.platform.entity-damage/apply-direct-damage!`，没有引入新的 Minecraft 实体/物理原语。已在 `forge-1.20.1` 与 `neoforge-1.21.1` 两个平台目标上跑 `:platform:compileClojure` 编译通过（`fabric-26.2` 因为预先存在、与本次改动无关的 Fabric Loom 插件版本不匹配未能编译，环境问题，不是代码问题）；**仍未进游戏验证实际战斗表现**。

实现细节与已知简化：
- **owner 排除是自己写的，不是白拿来的**：`entity-damage/apply-aoe-damage!` 这个已安装的平台原语**没有 owner 排除参数**，直接用会连施法者自己一起打。新增了 `aoe-victims!`/`apply-aoe-damage-excluding-owner!` 两个本地 helper，逻辑镜像 `ac.ability.util.attack/aoe-victims`（owner 排除 + 球形距离过滤），但不能直接 `require` 那个 AC 层命名空间（platform 依赖 AC 是方向反转），所以是重新写的等价实现，不是简单复用。
- **meltdowner 是保守实现**：只对 raycast 命中的单一目标造成伤害。`beam-radius`/`block-energy`（沿光束熔化方块）和 `:reflection` 字段（Vector-Reflection 被动联动）**故意没有实现**——这块和 B 节的 mag-manip 一样需要设计判断，不是可以照抄平台原语的低风险填空，代码里留了注释指回本文档。
- **意外发现一个更大范围的既有问题，没有在本次改动范围内修**：`platform-src/minecraft/base/.../DamageSourceAccess.java` 的 `resolveKeyword` 只认识 `:magic`/`:lightning`/`:explosion`/`:generic`/`:skill`/`:vec-reflection` 六个 damage-type 关键字，`combat_content.clj` 里广泛使用的 `:electric`/`:vector`/`:teleporter` 都会落进 `default -> "generic"`。这不是我这次改动引入的——thunder-bolt 等已经"能用"的技能同样受影响（它们的 `:damage`/`:damage-targets` world-effect 最终也调用同一个 `resolveKeyword`）。伤害本身仍然会打（不会报错、不会变成 0），只是没有拿到对应伤害类型的护甲穿透/抗性/免疫特殊处理。新的 4 个执行器延续了这个既有行为（用 `:electric`/`:vector` 标签，和 content 里的现有约定保持一致），没有引入新的不一致，但值得单独排查是否要给 `resolveKeyword` 补齐这几个关键字。

### C-2. 其余 7 个技能：query + world-effect 执行器（2026-08-17 追加会话，5/7 已完成）

原受影响技能：`jet-engine`、`light-shield`、`storm-wing`、`electron-missile`、`scatter-bomb`、`groundshock`、`mine-ray-basic`/`mine-ray-expert`/`mine-ray-luck`（8 个 skill id，7 个技能点，因为 `groundshock` 与 `mine-ray` 三个共用 `:block-scan` query）。这批的 query 侧也没有真实实现，修复需要 query+执行器两层都补，比 C 节工作量更大。

**已完成（mine-ray-basic/expert/luck、storm-wing、light-shield、electron-missile、scatter-bomb，共 5 个技能点 / 7 个 skill id）**：

- **`mine-ray-basic`/`mine-ray-expert`/`mine-ray-luck`**：`:block-scan` query 用 `cn.li.mcmod.platform.block-manipulation/find-blocks-in-line`（沿视线找方块，已是完整安装的平台原语，`groundshock` 的方块破坏权限检查也依赖同一个 port）扫描视线上第一个非空气方块。`execute-mine-ray!` 实现渐进式挖掘——`break-speed` 是每 tick 累加进目标方块 `hardness` 的进度量，不是"瞬间达标"阈值（mine-ray-basic 的 0.2-0.4 远低于常见方块硬度），用 owner-keyed 本地 atom 跟踪进度，换目标方块会重置进度。
- **`storm-wing`**：query 是纯 no-op（`{}`）——世界效果的 `valid?` 只检查 `query-result` 是 map，不读取内容。`execute-storm-wing!` 复用已验证过的移动类原语（`player-motion`），朝视线方向推进（低于 `speed-threshold` 时用 `speed-scale` 加速），并按 `is-on-ground-for-player?` 在贴地/空中悬浮速度间切换。
- **`light-shield`/`electron-missile`/`scatter-bomb`（保守实现，已与用户确认范围）**：这三个的核心机制（伤害吸收、追踪弹幕、散射弹丸）都没有现成原语可复用，只实现了各自 `valid?` 已经校验过的"对附近/锁定目标造成伤害"核心部分：
  - `light-shield` 只实现 `touch-damage`（对 `touch-radius` 内、`front-cone-degrees` 锥形范围内的敌人造成伤害）；`absorb-damage`（被动伤害吸收）**没有实现**——需要 hook 伤害管线，这个架构在全仓库任何地方都不存在。
  - `electron-missile` 用 owner-keyed 本地 atom 按 `fire-interval` 节流，每次触发对 `seek-range` 内最近目标造成一次瞬间伤害，**没有**实际的追踪弹球实体/视觉。
  - `scatter-bomb` 对 `auto-aim-radius` 内最近的至多 `ball-count` 个目标瞬间造成伤害，**没有**实际的弹丸散射视觉/物理；`anti-afk-tick`/`anti-afk-damage`（防挂机机制）也没有实现，触发时机需要设计输入。

**仍然推迟（jet-engine、groundshock）**：

- **`jet-engine`**：调查后发现它和上面 5 个不同类——它的 query/require/world-effect 全部只在 session **release 时触发一次**（不是每 tick），且效果本该是"延迟 `trigger-time-ticks` 后才发作、`trigger-lifetime-ticks` 后过期"的定时雷区。**全平台层没有任何延迟调度基础设施**——`ac.ability.service.delayed_projectiles` 是 AC 层的延迟效果系统，但 platform 代码不能依赖 AC（方向反转）。写"瞬间伤害版本"会丢失延迟这个定义性特征，等于换了一个技能而不是简化——已与用户确认，不写这个版本，等平台层有通用延迟调度设施后再做。
- **`groundshock`**：核心机制是"从冲击点向外传播 N 次迭代的地面冲击波"（`max-iterations`/`init-energy`/`launch-scale` 等参数）。传播的具体算法、范围、形态属于技能手感设计决策，没有可复用原语，也没有权威参考实现可核实——已与用户确认单独推迟。

**要做的事（仅剩 jet-engine、groundshock）**：需要先有平台层通用延迟调度设施（jet-engine）和明确的传播算法设计输入（groundshock），才能继续实现对应的 query + `execute-jet-engine!`/`execute-groundshock!`。`combat_runtime.clj` 里两者的 `valid?` 已经写好，能看出各自执行器该接收什么形状的 `plan`。

**验证状态**：以上全部改动已跑 `:ac:checkClojure` 通过，`:platform:compileClojure` 在 `forge-1.20.1`/`neoforge-1.21.1` 两个目标上编译通过，`combat-core`/`vfx-core` 测试套件不受影响。**均未进游戏验证**——编译通过只说明代码类型正确、能加载，不代表游戏里数值平衡或手感符合预期，尤其是 light-shield/electron-missile/scatter-bomb 三个保守实现版本的行为已经偏离技能原本设计。

### D. 3 个 world-effect 类型：连处理分支都没有（1/3 已完成）

原受影响技能：`current-charging`（`:charge-energy`）、`mine-detect`（`:mine-detect`）、`directed-shock`（`:knockback`）。三者共同点：`combat_runtime.clj` 里 `(case (:type effect) ...)` 完全没有对应 case，全部落进兜底分支 `{:status :unhandled :reason :missing-world-effect-host-port}`。

**已完成（`:knockback`，2026-08-17 追加会话）**：`directed-shock` 的 query 本来就工作正常（`:raycast`），伤害会正常命中，只是击退效果之前被静默吞掉——这种"技能好像有效但缺一部分"的情况最容易被误判为"没问题"。已加 `combat_runtime.clj` 的 `:knockback` case 分支 + `mcbase/adapter/world_effects.clj` 的 `execute-knockback!` + `mcmod/platform/world_effects.clj` 的中立契约函数（`:knockback` 不在原本 16 个 `execute-*` 声明里，是新增的）。

**方向/公式是推断的，不是参照实现验证过的**：技能翻译文本（"将挥拳时产生的反作用力夺取并叠加到正前方的冲击力上"）确认这是"沿施法者朝向方向把目标推开"，不是拉扯——尽管 content 里 `knockback-scale` 是负数（`-0.7`）。把 `knockback-scale` 解读为"对目标现有速度的阻尼/反转系数"（而不是对 `impulse` 本身的乘数——那样负数会导致推力变成拉力，跟翻译文本矛盾），沿用同文件里 `vec-accel`/`mag-movement`/`storm-wing` 已经在用的"现有速度 + 新增量"公式形状。**如果进游戏测试发现效果是"拉过来"而不是"推开"，先查这个公式。**

**仍然缺失（`:charge-energy`、`:mine-detect`）——不是平台代码缺口，是设计判断**：
- `:charge-energy`（`current-charging` 技能用）：应该接入 AC 已有的 `ac.energy.*` 机器能量系统，还是独立实现一套？`ac.energy.*` 目前服务的是方块机器间能量传输，"玩家技能给某个目标充能"是否复用同一套抽象需要设计决定。
- `:mine-detect`（`mine-detect` 技能用）：方块扫描结果要以什么格式反馈给客户端（高亮方块？小地图标记？纯文字提示？）——这是 UI/UX 设计问题，不是纯逻辑填空。

**要做的事**：先由懂游戏设计的人拍板上述两个问题，再实现 `combat_runtime.clj` 里对应的 case 分支。

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

1. **先做全量审计，不要分批摸索**：对全部 37 个技能逐条核实 query 侧 + world-effect 侧 + 所需的目标检测/物理原语是否真实存在于平台代码里。本轮工单里的每一条分类，都是"以为已经 wired，深入一层才发现没有"这个模式反复出现以后才逐渐收敛出的准确清单——不要重复这个摸索过程。
2. ~~C 类 4 个技能~~ / ~~C-2 类 5 个技能点~~ / ~~D 类 `:knockback`~~ / ~~A 类 5 个技能（mark/penetrate/flashing/location-teleport/threatening-teleport）~~ / ~~B 类 2 个技能（mag-manip/mag-movement）~~ **均已完成**，见上方 C、C-2、D、A、B 节（light-shield/electron-missile/scatter-bomb/mag-manip 是保守简化版本，`:knockback` 的推力方向/公式、mag-manip 的伤害数值是推断的，approval-token 桥接机制是本轮新增的平台基础设施，均仍需进游戏验证）。
3. **剩下能不靠设计判断直接做的都做完了**。剩余项分两类：
   - **需要新基础设施**：C-2 类的 `jet-engine`（平台层通用延迟调度设施）；mag-manip 若要还原成真实物理实体悬浮/抛掷（而非当前的保守直接伤害版），需要新增 uuid→Player 平台操作，工作量接近整个 A 类，已确认不在本轮做。
   - **需要设计/游戏手感输入**：C-2 类的 `groundshock`（传播算法）、D 类的 `:charge-energy`/`:mine-detect`、A 类的 `shift-teleport`（方块投射物物理，1 个技能）。
4. **E、F 类是大迁移，建议单独排期**，不要和小修小补混在一个提交序列里。

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
