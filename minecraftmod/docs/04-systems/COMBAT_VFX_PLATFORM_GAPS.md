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
| `32eb35ead` | `:groundshock` query + `execute-groundshock!`（传播算法移植，见下方 C-2 节） |
| `79a93388f` | `:shift-teleport` 独立 query/effect-type + `execute-shift-teleport!`（A 类最后一个技能，见上方 A 节） |
| `c2e374266` | `:charge-target`/`:charge-energy`（current-charging 方块充能路径）+ `:mine-detect`（无条件自我致盲，去掉误加的 block-scan gate），D 类全部完成，见上方 D 节 |
| `b284185a2` | 删除死代码 channel/topic 总线（vfx-core `register-channel!`/`dispatch-channel!`/`freeze-channels!` 及封装），E 类 P1.3 完成，见下方 E 节 |
| `c38f125bb` | `presentation-compiler/render.clj` 按 `(template, width, height)` 缓存 layout 结果，绑定值变化不再触发重新布局，F 类对症修复，见下方 F 节 |
| `61b87f4f1` | 删除确认死代码 `core/tree.clj`/`core/dirty.clj`/`frame.clj` 的 `frame-graph`/`host.clj` 的 tree 封装，F 类死代码清理完成，见下方 F 节 |
| `07229a522` | P1.1/P1.2 迁移 Batch 0：`:lifecycle` 分派基础设施（vfx-core + `effect_controller.clj`），零行为变化，见下方 E 节 |
| `e7c7113e6` | P1.1/P1.2 迁移 Batch 1：`vec_deviation`/`mag_movement`/`light_shield` 3/26 个效果迁移到真实 per-instance 状态，新增 vfx-core `:destroy` 钩子，见下方 E 节 |
| `a019151cd` | 修复 effect_controller.clj `:transient` 实例从不自然结束的 bug（影响已迁移的 Batch 0/1），见下方 E 节 |
| `6148ef128` | P1.1/P1.2 迁移 Batch 2：`mark_teleport`/`penetrate_teleport`/`flashing`/`groundshock`/`electron_bomb`/`flesh_ripping`/`vec_accel` 7/26 个效果，发现其中 6 个的触发事件与 combat_content.clj 实际 wiring 存在系统性错位，见下方 E 节 |
| `592e904ea` | P1.1/P1.2 迁移 Batch 3：`blood_retrograde`/`ray_barrage`/`directed_blastwave`/`threatening_teleport`/`shift_teleport` 5/26 个效果，触发事件错位问题在这批同样复现（`ray_barrage` 甚至是这次迁移开始前就已经死的），见下方 E 节 |
| `d80a8fb00` | P1.1/P1.2 迁移 Batch 4：`directed_shock`/`mag_manip` 2/26 个效果（hand-only），新增跨 vfx-core/effect_controller.clj 的 `instance-for-owner` API，见下方 E 节 |
| `80ac1bc42` | P1.1/P1.2 迁移 Batch 5：`meltdowner`/`mine_detect`/`jet_engine`/`railgun_shot`/`thunder_clap` 5/26 个效果（计划标注的最高复杂度批次），`railgun_shot`/`thunder_clap` 是目前 26 个里仅有的两个真正存活的复杂样本，新增 `update-state-for-owner!` API，见下方 E 节 |

## 分类清单

### A. 传送类（2026-08-17/18 追加会话，6/6 全部完成）

原受影响技能：`mark-teleport`、`penetrate-teleport`、`shift-teleport`、`threatening-teleport`、`flashing`、`location-teleport`。

**执行前的重要发现**：读技能翻译文本后发现 `shift-teleport`/`threatening-teleport` **根本不是玩家传送**，尽管它们最初和另外 3 个共用同一个 `:teleport-approved-target` world-effect。`threatening-teleport`（"将小质量的物品移动到自己附近的某个位置。有时候，只需要一小块体内的碎片就可以对生物造成巨大伤害"）实质是"瞬间对附近敌人造成伤害"，不是真的移动物体。`shift-teleport` 的翻译文本（"将方块运用坐标移动的方式高速发射出去...因为其运动方式...所以并不会因为其速度与质量而影响破坏力"）第一次读的时候被理解成"块体弹道+路径伤害"物理机制——**读到 `a8c000766` 删除的旧实现后确认这个理解是错的**：真实机制是纯几何（"raycast 一个落点，能放置就把手持方块放到命中面，不能放就在目标点丢一件手持物品，然后对**视线到落点这条线段**相交的所有实体造成伤害"），不涉及任何物理模拟，翻译文本是"这次攻击不受方块质量/速度影响"的文学化说法，不是字面的抛射体描述。

**已完成（mark-teleport、penetrate-teleport、flashing、location-teleport、threatening-teleport、shift-teleport）**：

- **`mark-teleport`/`penetrate-teleport`/`flashing`**：复用了已经存在、已经过测试、平台无关的目的地求解代码——`ac/content/ability/teleporter/{mark_teleport_dest,penetrate_dest,flashing_dest}.clj`，三个文件都明确标注是"upstream `MTContext`/`PTContext`/`MainContext` 端口"，不是重新发明的逻辑。`flashing` 只做了前方闪现：`combat_content.clj` 的 query 节点没有传 `:direction` 字段（原版支持 WASD 相对方向闪现），固定用 `:forward`，是一个真实的简化，代码里有注释标注。`penetrate-teleport` 的行进算法在 `:available? false`（还在墙里）时正确返回 nil（视为"无目的地"），不会把玩家传送进方块里。
- **`location-teleport`**：复用了已经完整、已经在跑的已保存位置基础设施（`location_teleport.clj` 的公共函数 `query-location-teleport`、6 个 loader 都装了的 NBT 存储 `named-position-store`），没有重新实现。**故意没有**调用 `location_teleport.clj` 自己的 `perform-location-teleport!`——那个函数有自己的一套 CP 距离公式和冷却逻辑（服务于 RPC/UI 流程），在 world-effect 里调用会和 combat-core 自己声明的 `:cost` 重复扣费。全仓库没有任何"主/默认保存点"的约定（UI 一直是玩家从列表里选名字），给"没有输入名字的快捷键激活"选了一个确定性的默认值——按名字字母序取最靠前的——这是推断的 UX 选择，不是确认过的设计，已在代码注释标注。
- **`threatening-teleport`（保守实现）**：改为直接造成伤害（`entity-damage/apply-direct-damage!`），不走传送/approval-token 路径。给 `combat_content.clj` 的 world-effect 步骤加了 `:damage (scale 3.0 6.0)`（跟随全文件已有的 `thunder-clap` 等同款模式）。跳过了 `needle-damage-multiplier`（持"针"类物品的伤害加成）与"碎片"掉落概率机制。
- **`shift-teleport`（`79a93388f`）**：给了它自己独立的 `:shift-teleport` query-type/effect-type，不再借用 `:teleport-target`/`:teleport-approved-target`（这两个的形状本来就装不下它的落点/放置/线段伤害数据，之前 `:mode :shift` 只是落进 `:teleport-approved-target` 的默认分支后因为不在 `ability-id` 白名单里干净地失败）。
  - query（`combat_runtime.clj`，纯几何，不碰 Player 对象）：raycast 落点（命中方块用 face-offset 求放置坐标，未命中用光线终点），再对"视线原点→落点"线段做 segment-vs-AABB 扫描（`world-effects/find-entities-in-aabb`，已是中立/uuid 接口）找出所有相交实体。
  - world-effect `execute-shift-teleport!`（`platform-src`）：用 `query-core/get-player-by-uuid` 解析出真实 `Player` 对象后调用 `mcmod.platform.entity` 的手持物品函数（能不能放置、放置、消耗、丢弃、创造模式复制丢弃），放置或丢弃成功后对扫描到的每个实体造成一次性伤害。**没有**移植旧版的护甲加成/暴击伤害管线（那属于已删除的 Context 伤害系统）和经验获取，直接用 `entity-damage/apply-direct-damage!` 打固定值，和本轮其它技能的简化方式一致。
  - 顺带修了一个从未被注意到的遗漏：`ac/ability/skill_config/teleporter.clj` 里 shift-teleport 的 schema 声明了 `cost.up.overload [40.0 30.0]`，但 `combat_content.clj` 的 `:cost` 从来只有 `:cp`，没有 `:overload`——已补上 `(scale 40.0 30.0)`。

**approval-token 桥接机制**：`mcmod/platform/teleportation.clj` 新增 `mint-approval-token!`/`redeem-approval-token!`（内存 atom，短 TTL）。不是安全边界——query 和 world-effect 在同一次 intent dispatch 内同步执行，中间不经过真实时间——只是为了让 `teleport-approved-target!` 的既有函数签名（`owner ability-id approval-token mode`）在真正的玩家传送 mode 上保持统一。`combat_runtime.clj` 的 `:teleport-approved-target` case 按 `mode` 分派：`:threatening` 直接扣血；`:mark`/`:penetrate`/`:flashing` 铸造 token 走真实传送。`shift-teleport` 有了自己的独立 op 之后，`:teleport-approved-target` 里原来处理它"干净失败"的注释和分支已经删掉——不再需要，因为它已经不会再被送进这个 op。

**方法论备注（写给下一个接手的人）**：这一节连续三次证明"这个技能需要设计判断/权威实现"是可以被 git 历史推翻的假阳性（mag-manip 的金属判定、groundshock 的传播算法、shift-teleport 的整个机制）。`a8c000766`（combat-core 迁移提交）删除的旧 defskill 链几乎覆盖了全部 37 个技能，其中很多在删除前是完整、调好参、带测试的实现。**下次遇到"没有可复用逻辑/权威参考实现"这类结论时，先跑一遍 `git log --diff-filter=D -- "*<skill-name>*"` 再相信它**，比先假定"需要设计输入"再去论证要快得多，也更准。

**验证状态**：`:ac:checkClojure`、`:platform:compileClojure`（`forge-1.20.1`/`neoforge-1.21.1`）通过，`verifyCombatSkillCoverage` 报告 38 个技能一致，`combat-core`/`vfx-core` 测试套件不受影响。**均未进游戏验证**——`mark-teleport`/`penetrate-teleport`/`flashing` 的落点几何计算、`location-teleport` 的默认位置选择，这些都需要进游戏确认才能信任。

### B. mag-manip / mag-movement（2026-08-18 追加会话，已完成，`decc4ec30`）

**上一版工单的前提是错的**：不是"没有金属判定逻辑"，是 combat-core 迁移把它删掉了、但没删干净。`git log -- ac/src/main/clojure/cn/li/ac/content/ability/electromaster/mag_manip.clj` 显示 `a8c000766`（"switch production to Combat Core, delete legacy defskill chain"）删除了一份完整可用的旧 defskill 实现——它就是"什么算金属"的答案：`ability-config/is-metal-block?`（`ac/src/main/clojure/cn/li/ac/ability/config.clj:432`，配置驱动的 normal + weak 金属方块 allow-list，`targeting.metal.normal-metal-blocks`/`weak-metal-blocks`），这些函数**在迁移后依然完整存在于当前 `config.clj`**，只是没人在 combat-core 侧调用它们。之前"搜过 ac/src/main/clojure 全目录找不到"的结论是搜索范围/关键词不对。

**mag-movement：直接可做，无新平台面**。`execute-mag-movement!`（platform-src 早已实现且可用）只需要 query 产出 `:target-x`/`:target-y`/`:target-z`。实现：raycast 沿视线方向找 `is-metal-block?` 命中的方块（`raycast/raycast-blocks-matching` + `ability-config/get-normal-metal-blocks`/`get-weak-metal-blocks`），返回命中方块中心点。

**mag-manip：比预想复杂得多**。旧版 `execute-mag-manip!` 读 `:entity-uuid`/`:position`/`:throw-target`，对应旧 defskill 的真实机制：抓取时生成一个**真实物理实体**（`ScriptedBlockBodyEntity`，三个 MC 版本都完整保留，`entity-motion` adapter 也仍在），悬浮跟随视角，松手后靠自己的碰撞造成伤害/放置方块。`mcmod.platform.entity` 里所有的生成/手持物品函数（`player-spawn-tracked-entity-by-id!`、`player-get-main-hand-item-id` 等）都要求调用方已经拿到一个 `Player` 对象，而 combat-core 的 query-port 函数（`combat_runtime.clj`，AC 层）手里只有 uuid 字符串，没有办法解析出 `Player`。

**2026-08-18 更新，修正当时的误判**：当时把这个写成"需要新增 uuid→Player 平台操作，工作量接近整个 A 类"——**这是错的**。`query-core/get-player-by-uuid` 这个解析函数早就存在，而且本轮从 storm-wing 开始就一直在用（`platform-src/.../adapter/world_effects.clj` 里 `execute-storm-wing!`/`execute-mag-movement!`/`execute-light-shield!`/`execute-electron-missile!`/`execute-scatter-bomb!` 全部靠它拿到真 `Player` 对象）。真正的限制窄得多：**这个解析函数只存在于 platform-src，AC 层的 query-port 永远拿不到**——所以像"生成/操作手持物品这种需要 `Player` 对象的逻辑，必须整个放进 world-effect 执行器（platform-src），不能放进 query（AC 层）"。这正是后来实现 shift-teleport（同样需要操作手持物品）时用的办法，且完全不需要新平台设施，见上方 A 节。mag-manip 要走物理实体版本，需要的不是"新平台操作"，是把抓取/悬浮/抛掷的整个流程从 query 挪到 world-effect 里重写——工作量比当时估的小，但仍然比保守版大得多，本轮未重新评估这笔投入是否值得，`execute-mag-manip!` 仍是保守直接伤害版：

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

### C-2. 其余 7 个技能：query + world-effect 执行器（2026-08-17/18 追加会话，6/7 已完成）

原受影响技能：`jet-engine`、`light-shield`、`storm-wing`、`electron-missile`、`scatter-bomb`、`groundshock`、`mine-ray-basic`/`mine-ray-expert`/`mine-ray-luck`（8 个 skill id，7 个技能点，因为 `groundshock` 与 `mine-ray` 三个共用 `:block-scan` query）。这批的 query 侧也没有真实实现，修复需要 query+执行器两层都补，比 C 节工作量更大。

**已完成（mine-ray-basic/expert/luck、storm-wing、light-shield、electron-missile、scatter-bomb、groundshock，共 6 个技能点 / 8 个 skill id）**：

- **`mine-ray-basic`/`mine-ray-expert`/`mine-ray-luck`**：`:block-scan` query 用 `cn.li.mcmod.platform.block-manipulation/find-blocks-in-line`（沿视线找方块，已是完整安装的平台原语，`groundshock` 的方块破坏权限检查也依赖同一个 port）扫描视线上第一个非空气方块。`execute-mine-ray!` 实现渐进式挖掘——`break-speed` 是每 tick 累加进目标方块 `hardness` 的进度量，不是"瞬间达标"阈值（mine-ray-basic 的 0.2-0.4 远低于常见方块硬度），用 owner-keyed 本地 atom 跟踪进度，换目标方块会重置进度。
- **`storm-wing`**：query 是纯 no-op（`{}`）——世界效果的 `valid?` 只检查 `query-result` 是 map，不读取内容。`execute-storm-wing!` 复用已验证过的移动类原语（`player-motion`），朝视线方向推进（低于 `speed-threshold` 时用 `speed-scale` 加速），并按 `is-on-ground-for-player?` 在贴地/空中悬浮速度间切换。
- **`light-shield`/`electron-missile`/`scatter-bomb`（保守实现，已与用户确认范围）**：这三个的核心机制（伤害吸收、追踪弹幕、散射弹丸）都没有现成原语可复用，只实现了各自 `valid?` 已经校验过的"对附近/锁定目标造成伤害"核心部分：
  - `light-shield` 只实现 `touch-damage`（对 `touch-radius` 内、`front-cone-degrees` 锥形范围内的敌人造成伤害）；`absorb-damage`（被动伤害吸收）**没有实现**——需要 hook 伤害管线，这个架构在全仓库任何地方都不存在。
  - `electron-missile` 用 owner-keyed 本地 atom 按 `fire-interval` 节流，每次触发对 `seek-range` 内最近目标造成一次瞬间伤害，**没有**实际的追踪弹球实体/视觉。
  - `scatter-bomb` 对 `auto-aim-radius` 内最近的至多 `ball-count` 个目标瞬间造成伤害，**没有**实际的弹丸散射视觉/物理；`anti-afk-tick`/`anti-afk-damage`（防挂机机制）也没有实现，触发时机需要设计输入。
- **`groundshock`**（`32eb35ead`）：**上一版工单"没有可复用传播算法/权威参考实现"的结论是错的**，和 B 节 mag-manip 同一个模式——`a8c000766`（combat-core 迁移提交）删除了一份完整、带测试的 groundshock defskill 实现（DDA line-plotter 传播 + 能量预算式方块转换/破坏 + 沿视线方向的实体伤害/击飞，是原版 AcademyCraft 算法的移植），连它完整调校过的配置 schema 都原封不动留在 `ac/ability/skill_config/vecmanip.clj` 里没删。`combat_content.clj` 现有的 `:groundshock` world-effect 步骤（`init-energy` [60,120]、`max-iterations` [10,25]、`entity-search-radius` 2.0、`launch-scale` [0.8,1.3] 等）逐字段对得上这份 schema 的默认值——说明当初写 DSL content 的人已经忠实移植过参数，只是从没写执行器去读它们。
  - query：起点（脚下一格）+ 水平朝向（长度归一化，为 0 时返回 nil——旧代码的"退化时回落到 +Z"由 `targeting.horizontal-look-fallback` 配置项控制，默认值是 `false`，即默认行为其实是"不回落，技能直接不触发"，不是"总是回落"；第一版实现搞反了这个默认值，写执行器之前已核实修正）。
  - `execute-groundshock!`：完整移植传播循环、plotter/spread 数学（**逐 bug 保留**了原版 `Vec3d.rotateYaw(90)` 传的是 90 弧度而不是 90 度转弧度——这是有记录的上游怪癖，不是笔误）、shock-box 实体重叠判定、按方块类型区分的能量消耗。跳过了旧版"100% 经验时半径内方块全破坏"的 mastery-ring 加成——`combat_content.clj` 的 world-effect 步骤从没把这个机制的调参旋钮暴露进 DSL content，移植也就没有东西可读。
  - 顺带修了一个无关 bug：`:groundshock` world-effect case 的 `valid?` 校验一直把 `drop-rate` 当 2 元素 vector 检查，但 combat-core 的 `:world-effect` op 会对整个 node（含嵌套 map 值）递归跑 `resolve-data`，所以 content 里 `:breaking {:drop-rate (scale 0.3 1.0)}` 送到这里时早就被解析成标量 double 了，vector 形状永远不可能出现。因为 `execute-groundshock!` 之前从未安装，这条 `valid?` 也从没被真正跑过。已改为校验标量范围，并给 world-effect 步骤加了 `:energy-cost {:stone :grass-block :farmland :default-block}`（取自恢复出来的 schema 默认值），让每种方块的能量消耗调参留在 DSL content 里，不写死在 platform-src。

**仍然推迟（jet-engine）**：

- **`jet-engine`**：调查后发现它和上面几个不同类——它的 query/require/world-effect 全部只在 session **release 时触发一次**（不是每 tick），且效果本该是"延迟 `trigger-time-ticks` 后才发作、`trigger-lifetime-ticks` 后过期"的定时雷区。**全平台层没有任何延迟调度基础设施**——`ac.ability.service.delayed_projectiles` 是 AC 层的延迟效果系统，但 platform 代码不能依赖 AC（方向反转）。写"瞬间伤害版本"会丢失延迟这个定义性特征，等于换了一个技能而不是简化——已与用户确认，不写这个版本，等平台层有通用延迟调度设施后再做。

**要做的事（仅剩 jet-engine）**：需要先有平台层通用延迟调度设施，才能继续实现对应的 query + `execute-jet-engine!`。`combat_runtime.clj` 里它的 `valid?` 已经写好，能看出执行器该接收什么形状的 `plan`。

**验证状态**：以上全部改动已跑 `:ac:checkClojure` 通过，`:platform:compileClojure` 在 `forge-1.20.1`/`neoforge-1.21.1` 两个目标上编译通过，`combat-core`/`vfx-core` 测试套件不受影响。**均未进游戏验证**——编译通过只说明代码类型正确、能加载，不代表游戏里数值平衡或手感符合预期，尤其是 light-shield/electron-missile/scatter-bomb 三个保守实现版本的行为已经偏离技能原本设计。

### D. 3 个 world-effect 类型：连处理分支都没有（2026-08-17/18 追加会话，3/3 全部完成）

原受影响技能：`current-charging`（`:charge-energy`）、`mine-detect`（`:mine-detect`）、`directed-shock`（`:knockback`）。三者共同点：`combat_runtime.clj` 里 `(case (:type effect) ...)` 完全没有对应 case，全部落进兜底分支 `{:status :unhandled :reason :missing-world-effect-host-port}`。

**已完成（`:knockback`）**：`directed-shock` 的 query 本来就工作正常（`:raycast`），伤害会正常命中，只是击退效果之前被静默吞掉——这种"技能好像有效但缺一部分"的情况最容易被误判为"没问题"。已加 `combat_runtime.clj` 的 `:knockback` case 分支 + `mcbase/adapter/world_effects.clj` 的 `execute-knockback!` + `mcmod/platform/world_effects.clj` 的中立契约函数（`:knockback` 不在原本 16 个 `execute-*` 声明里，是新增的）。

**方向/公式是推断的，不是参照实现验证过的**：技能翻译文本（"将挥拳时产生的反作用力夺取并叠加到正前方的冲击力上"）确认这是"沿施法者朝向方向把目标推开"，不是拉扯——尽管 content 里 `knockback-scale` 是负数（`-0.7`）。把 `knockback-scale` 解读为"对目标现有速度的阻尼/反转系数"（而不是对 `impulse` 本身的乘数——那样负数会导致推力变成拉力，跟翻译文本矛盾），沿用同文件里 `vec-accel`/`mag-movement`/`storm-wing` 已经在用的"现有速度 + 新增量"公式形状。**如果进游戏测试发现效果是"拉过来"而不是"推开"，先查这个公式。**

**已完成（`:mine-detect`/`:charge-energy`，`c2e374266`）——第四次证明"需要设计判断"是假阳性**：`a8c000766` 同样删除了 `current_charging.clj`（406 行 + 438 行测试）和 `mine_detect.clj`（93 行 + 78 行测试）两份完整实现。

- **`mine-detect`**：上一版工单问"方块扫描结果要以什么格式反馈给客户端"——**这个问题本身不成立**。读旧实现发现真实机制根本不扫描方块：无条件对自己上失明 debuff，然后发一个带 `:range`/`:advanced?` 参数的 FX 事件，方块高亮显示大概率是纯客户端渲染逻辑，不在 combat-core 权限范围内。`combat_content.clj` 原来的 `:mine-detect` 条目加了一个 `:block-scan` query + `:require`，这个 gate 和真实机制对不上——按原机制，技能应该无条件生效，不该因为准星没对着方块就打不出来。已删掉这个 query/require，world-effect 直接无条件上 debuff。`cn.li.ac.ability.effects.potion`（uuid 接口，不需要 Player 对象）本来就装好了，`:mine-detect` 的 world-effect case 完全写在 `combat_runtime.clj` 里，**没有改动任何 platform-src 代码**。
- **`current-charging`**：机制比 mine-detect 复杂得多（持续引导，充能手持物品**或**瞄准的能量方块/机器），但依赖链完整存活——`cn.li.ac.energy.operations`（`is-node-supported?`/`charge-node`/`is-receiver-supported?`/`charge-receiver`）从没被删；`:runtime-interop` 适配器的 `:get-block-entity-at` 操作（uuid/world-id 接口，不需要真实 level 对象）也还装着，而且**现在还有一个活跃调用者**——`ac/item/developer_portable_energy.clj` 用一模一样的 `fw`/`platform` call-adapter 手法访问能量，证明这条路子不是孤儿代码，是仍在用的正规写法。只实现了**方块充能路径**：`:charge-target` query 用普通 raycast 找方块（不是旧版的"实体优先"raycast——一个离得更近的实体会挡住光束但不会被这个技能充能）；`:charge-energy` world-effect 解析出方块实体后调 `energy/charge-node`/`energy/charge-receiver`。**跳过**：手持物品充能分支（需要解析出真实 `Player` 对象才能读取手持物品，这个限制和 shift-teleport/mag-manip 是同一条——要做的话得整个挪到 platform-src 的 world-effect 执行器里，这次没做）、multiblock controller 解析（旧版的 `resolve-energy-target-tile`/`target-structure-bounds`——现在瞄准 multiblock 机器的非控制器格子不会充上能）、overload-floor 强制、有效/无效经验区分追踪。
  - **值得注意**：`:charge-energy` 的 world-effect case 是本轮**唯一一个没有委托给 platform-src `execute-*!` 的**——`ac.energy.operations` 是 AC 层代码，platform 代码不能依赖 AC，所以这个变更只能直接写在 `combat_runtime.clj` 里。
  - 顺带修了两个从未被跑到的 bug：`:target-ref` 从来对不上 world-effect case 读的 `:query-result`（应该是 `:query-ref`）；`:cost {:cp 2}`/`:amount 1.0` 是占位数字，从没对上 `skill_config/electromaster.clj` 里恢复出的 schema（`cost.tick.cp` [3,7]、`cost.down.overload` [65,48]、`effect.charge-amount` [15,35]）。改成了 `:cost-phase :start` 一次性收取 overload、`:patch` 每次 phase 调用扣一次按 exp 缩放的 CP，在 combat-core 单一 `:cost` 字段的形状里尽量还原 schema 的 down/tick 两段式。

**方法论备注**：这是本轮第四次"需要设计判断"被 git 历史推翻（mag-manip、groundshock、shift-teleport、这次的 current-charging/mine-detect）。到这个次数，"需要设计判断"基本可以当作"没查过删除历史"的同义词了。

### E. vfx-core 通用化剩余部分（P1.1-P1.3；P1.3 已完成，P1.1/P1.2 进行中，17/26 效果已迁移）

不是缺陷修复，是架构迁移——[VFX_CORE.md](VFX_CORE.md) 里已经记录了根因：vfx-core 按「一次施法 = 一个 instance」设计，AC 内容按「一个 effect-id = 一个 aggregate 实例，owner 维度塞在实例内部 map 里」写，`ac/client/effect_controller.clj` 的 `dispatch-signal!` 直接绕开 vfx-core 自己的 `instance-key`/`event-seq`/tombstone 分派机制。

**2026-08-18 更新，执行前重新核实**：读 `dispatch-signal!` 现在的代码才发现，这个"旁路"**不是一处被忽视的技术债**——函数上有一段详细注释，解释了为什么当前 content 的状态结构（owner-keyed map 塞在一个共享聚合实例里）下，真的走 vfx-core 自己的按 key 分派反而会让不同玩家各自独立的 `event-seq` 计数器互相冲突。这是一个当前确实生效、有理有据的权衡，不是一个可以直接删掉的错误。真要修（P1.1+P1.2），意味着要把 `ac/ability/client/fx_templates/arc_beam.clj` + 全部 28 个 `arc_beam/impl/*` 文件（6105 行）里的 owner-keyed 内部 map 重写成真正的 per-instance 状态——工作量和风险都远超本工单其它任何一项，而且视觉效果只能进游戏肉眼验证，本环境没有可运行的游戏客户端。**已和用户确认，本轮不做这部分**，见下方"仍然推迟"。

**已完成（`b284185a2`）——P1.3，删除死代码 channel 总线**：`register-channel!`/`dispatch-channel!`/`freeze-channels!`（vfx-core `runtime.clj`）连同 `effect_controller.clj`/`fx_spec.clj` 的封装一起删掉了。核实过：全仓库没有任何技能内容真正给 `:channels` 塞过一个带 `:topic` 的条目——`arc_beam.clj` 的 `build-spec` 虽然总会构造一个 `:channels` 键，但 28 个 impl 文件没有一个覆盖过它，恒为空。combat 信号本来就直接走 `dispatch-signal!`，这条 channel/topic 总线就是当初想替代 `dispatch-signal!` 旁路、但从没被任何内容真正用起来的第二条路径。纯删除，`arc_beam.clj` 不用改（`fx_spec.clj` 的 `register!` 现在只是容忍并忽略传入的 `:channels`，不再处理）。

**验证时顺带发现一个无关的既有问题**：`cn.li.ac.ability.client.fx-registry` 被 39 个测试文件 `:require`，但整个仓库找不到这个命名空间的主源文件——`:ac:runAcClojureTestsFast` 一上来就在第一个测试命名空间加载时报 `FileNotFoundException`。核实过这个失败在本次改动前后完全一样，不是这次改动引入的，和这里删除的 channel 总线也没有关系（`fx-registry` 是另一层更上层的、从未见过主实现的注册表抽象）。没有深入排查——不在这次任务范围内，但因为同属客户端 FX 区域，留给下一个接手 E 类剩余部分的人。

**2026-08-18 追加会话：与用户确认后启动 P1.1+P1.2 全部 26 个效果的迁移**，分批执行、每批独立验证+提交（详细设计见 `C:\Users\lxy\.claude\plans\ui-presentation-vfx-core-combat-core-ps-fancy-panda.md`，本仓库外的计划文档）。核心机制：`register-effect!`（vfx-core `runtime.clj` 与 `effect_controller.clj` 两处）新增 `:lifecycle`（`:transient`/`:persistent`/`:singleton`，缺省 `:singleton` 保持旧行为不变），`dispatch-signal!` 按声明的 lifecycle 分派——`:singleton` 效果继续走旧的聚合实例旁路，`:transient` 效果改走 vfx-core 真正的 `core/dispatch-signal!`（`instance-key`/`event-seq`/tombstone 全部生效）。这个设计让 26 个效果可以逐个独立切换，不需要一次性 big-bang。

**已完成（`07229a522`）——Batch 0，基础设施**：vfx-core `register-effect!` 加 `:lifecycle` 校验；`effect_controller.clj` 的 `register-effect!`/`dispatch-signal!` 按上述设计改写；审计过全部 `register-effect!`/`fx-spec/register!` 调用点，确认没有遗漏非战斗类效果。零行为变化（这批不改任何 impl 文件的渲染逻辑），`:vfx-core:runVfxClojureTests`/`:ac:checkClojure` 通过。

**已完成（`e7c7113e6`）——Batch 1，3/26 个效果（`vec_deviation`/`mag_movement`/`light_shield`）**：每个效果的 `initial-state` 从旧的 `{owner-key -> state}` 聚合 map 拍平成单个 vfx-core 实例自己的一份状态（owner 隔离改由实例身份本身提供，不再靠手搓 owner-key map）；`tick-state-fn` 返回 `nil` 让实例自然结束（vfx-core `tick!` 的既有约定），取代旧的 `effect-clear-owner!` 手动摘除 owner-key 的做法。**新增 vfx-core 引擎能力**：descriptor 可选的 `:destroy` 钩子（`run-destroy-hook!`，`vfx-core/runtime.clj`），在实例被销毁的全部路径（显式 `:destroy` 信号、`clear-owner!`、`clear-world!`、`tick!` 里 `:update` 自然返回 `nil` 结束）统一触发一次；`arc_beam.clj` 新增 `effect-destroy!`/`dispatch-destroy!`，镜像既有 `effect-clear-owner!`/`dispatch-clear-owner!` 的 multimethod-dispatch 写法（含 `:default` 空实现），`build-spec` 现在无条件挂 `:destroy-fn`。这顺带修好了一个真实的既有生产缺陷：`effect_controller.clj` 自己的 `clear-owner!` 从没有任何调用方，真正生效的断线清理路径（`combat_vfx_adapter.clj` → vfx-core 自己的 `clear-owner!`）对聚合实例（`:owner ::aggregate`）在 owner-index 里天生找不到对应条目——`mag_movement`/`light_shield` 的循环音效过去在玩家断线后永远不会停；迁移到 `:transient` 之后实例按真实 owner 索引，这条路径现在能正确找到并销毁实例、触发 `:destroy-fn` 停音效。

调查中发现 `light_shield` 的既有缺口比预想更深：不只是事件名对不上（`combat_content.clj` 实际发 `:active` 不是 `:tick`），`:active` 事件的 `:params` 从来只带 `{:radius 3.0}`，从没带过 `:pos`——原本带 `:pos` 的路径是已删除的死 channel/topic 总线（`light_shield_fx.clj` 的 `:channels`），从没在存活的 `:vfx` 信号路径上补过等价物。这是 `C-2.3`"仅 touch-damage 部分的保守实现"里已知的既有缺口（护盾的视觉/音效从没真正接完）——本次迁移只做了状态形状的拍平，原样保留了 `:start`/`:tick` 两个从未真正触发过的死分支及其行为，没有尝试"顺手"把它们接活，因为要接活需要一个这条管线现在根本没有的实时位置数据源，是一个真正的设计任务，不是生命周期拍平能顺带解决的。

**已完成（前置修复 `a019151cd`）——effect_controller.clj 的 `:transient` 实例从不自然结束的 bug**：开始 Batch 2 之前重新推导 vfx-core `tick!`/`tick-instance` 的确切语义时发现，`effect_controller.clj` 的 descriptor `:update` 函数把 `state` 一路 `->` 穿过 `apply-events`/`apply-tick`，这两者只会在某个 track 的 `tick-state-fn` 返回 `nil` 时 `dissoc` 掉 `{:level .. :hand ..}` 里对应的那个 KEY，`:update` 函数本身返回的整个 map 永远不是 `nil`。而 vfx-core `tick!` 只认"整个 `:update` 返回 `nil`"这一种"实例自然结束"的信号（见 `runtime.clj` 的 `tick-instance` 文档字符串）——也就是说 Batch 0/1 写的"`tick-state-fn` 返回 `nil` 会让实例自然结束"这句话在 `effect_controller.clj` 这一层其实**从未生效过**：`:level`/`:hand` 内部 key 被清空之后，实例本身仍然天天被 `tick!`/`sample-frame!` 空转，直到玩家断线才会被 `clear-owner!` 真正清掉——每一次 `:transient` 效果的施法都会永久占用一个 vfx-core 实例槽位，只受 `create-runtime` 的 `:max-instances`（2048）兜底，达到上限后全体 `:transient` 效果会静默生成失败。修复方式：`register-effect!` 把 `lifecycle` 传进 `descriptor` 的构造参数，`:update` 在 `(= :transient lifecycle)` 且 `:level`/`:hand` 都已经是 `nil` 时才真正返回整个 `nil`；`:singleton` 效果这个条件永远不成立，行为不变。这个 bug 影响 Batch 0/1 已提交的三个效果，但修复点完全在 `effect_controller.clj` 内部，不需要改任何一个 impl 文件。

**验证方式的新发现**：`ac/build.gradle` 的 `runAcClojureTestsFast` 支持 `-Dac.test.only=<namespace,...>`，且 `cn.li.test-support.auto-test-runner` 的 `select-namespaces` 在 `require` 之前就完成了过滤——意味着只要新测试文件自己不 `:require` 那个缺失的 `cn.li.ac.ability.client.fx-registry`，就可以用这个参数单独跑，绕开挡住全部 242 个测试命名空间的那个已知 pre-existing 问题。为这个修复写的回归测试（`ac/src/test/clojure/cn/li/ac/client/effect_controller_test.clj`）就是这样单独跑通的（2 tests，3 assertions，0 failures）——这是本次迁移里第一次能对 `effect_controller.clj` 这一层的行为做到运行时验证，而不仅仅是 `:ac:checkClojure` 的静态检查 + 推理；但每个效果自己的 `*_fx_test.clj` 大多直接或间接 `:require` 了 `fx-registry`（试过 `mag_movement_fx_test.clj`，一样触发同样的 `FileNotFoundException`），所以这个技巧只能验证 `effect_controller.clj`/`arc_beam.clj` 这类基础设施代码，不能用来验证具体某个效果 impl 文件的行为。

**已完成（`6148ef128`）——Batch 2，7/26 个效果（`mark_teleport`/`penetrate_teleport`/`flashing`/`groundshock`/`electron_bomb`/`flesh_ripping`/`vec_accel`）**：状态形状拍平方式与 Batch 1 相同。`groundshock`（本批唯一双轨道效果）的 `:level` 轨道被显式改成无状态（它一直只是纯副作用的音效+粒子发射器，从没有任何 `effect-build-plan` 注册过读它），这样"两个轨道都空了才算自然结束"的检查才能在 `:hand` 轨道（真正带状态）清空后触发。

**重大发现（对着 `combat_content.clj` 逐个核对 7 个效果的真实触发事件，跟 Batch 1 揪出 light_shield 的手法一样，这次是执行前系统性做了一遍）**：7 个效果里 6 个的 case 分支在生产环境里大部分或全部是死的——不是这次迁移引入的，是 `combat_content.clj` 对这些技能的 `:vfx` 步骤实际只发送一个终点事件（`:release`、`:perform`，或 `:spawn`）、带着极少的 `:params`，从来不发这些文件当初设计时假设的 `:start`/`:update` 连续追踪序列，很多时候连那一个真正会触发的事件都缺目标/位置数据。具体：`mark_teleport`/`penetrate_teleport`/`flashing` 三个（A 类传送技能共享的触发形状）只发一次 `:release`，`:params` 只有一个 `:max-range`/`:blink-distance` 数字，标记人形渲染整条链路是死的；`vec_accel`/`flesh_ripping` 只发一次 `:perform`，参数同样不含渲染需要的字段（`vec_accel` 的弹道线渲染死，`flesh_ripping` 的准星标记死，虽然音效/粒子的触发条件本身也读不到数据所以同样不响）；`electron_bomb` 的 `:spawn` 事件是活的（放一个施法音效），但它的 `:beam`（结算射线）走的是另一条更老的传输——`delayed_projectiles.clj` 的 `push-channel-to-player!`，发的是真实的 `MSG-CTX-CHANNEL` 网络包，跟 E 类 P1.3 删掉的 vfx-core 内部 channel/topic 总线是两回事——但翻遍整个仓库找不到任何客户端代码接收这个消息 id，所以同样到不了客户端；`groundshock` 是唯一有一部分真正工作的（`:hand` 轨道的第一人称后坐力/相机震动，靠 `update`-on-missing-key 的自愈写法，`:perform` 单独一个事件就能正确工作，不需要这次迁移改），`:level` 轨道的方块碎裂粒子理论上会响但 `:affected-blocks` 参数从来没被真正传过，所以视觉上也是空的。全部按 light_shield 的先例处理：只拍平状态形状，case 分支原样保留（包括死分支），在文件顶部写清楚哪些事件/字段今天真的会到、哪些不会，不在这次迁移里顺手"修好"——这类修复需要改 `combat_content.clj` 的 `:vfx` 步骤定义（把目标/位置数据接进 `:params`），是一个真正的设计任务，超出"把 owner-map 拍平成 per-instance 状态"这一件事的范围。

**已完成（`592e904ea`）——Batch 3，5/26 个效果（`blood_retrograde`/`ray_barrage`/`directed_blastwave`/`threatening_teleport`/`shift_teleport`）**：执行前先对着 `combat_content.clj` 核实了全部 5 个效果的真实触发事件（Batch 2 的发现提示这会复现，果然复现了）——`threatening_teleport`/`shift_teleport` 只发一次 `:release`（A 类传送技能共享的触发形状，跟 `mark_teleport`/`penetrate_teleport`/`flashing` 一样），两个文件的 case 分支都是按 `:start`/`:update`/`:perform`/`:end` 写的，没有 `:release` 分支，全部落到默认空分支，标记框和传送尾迹粒子在生产环境里从没渲染过。`blood_retrograde`/`directed_blastwave` 只发一次 `:perform`，但 `:params` 里没有各自 `:perform` 分支需要读的字段——`blood_retrograde` 的音效/溅血/喷溅队列因此是空操作，但 `:active?` 还是会被设成 `true` 且从不复位（`:end` 不发），导致 `build-plan` 的步行速度覆盖从第一次施法后**永久生效**（原样保留，写进了代码注释，没有改）；`directed_blastwave` 的音效仍然会无条件播放，但因为 `:pos` 恒为 nil，声音的坐标钉死在世界原点，冲击波纹和蓄力光环视觉都不渲染。`ray_barrage` 是这批里唯一一个"这次迁移之前就已经死了"的样本：它的 case 分支键是 `:preray`/`:barrage`，但 `combat_content.clj` 只发 `:perform`，两个分支都对不上——这个错位比 P1.1/P1.2 这次会话本身还要早。全部按既有先例处理：只拍平状态形状，case 分支原样保留，每个文件顶部写清楚哪些事件/字段今天真的会到。`directed_blastwave` 的 build-plan 保留了 mag_movement 式的"只画自己这份实例"过滤，但这次审查时改正了一处翻译问题：过滤只应该套在蓄力光环上（需要本地 `hand-center-pos`），冲击波纹本身是世界坐标、所有玩家都该看到，不应该被这个过滤一起挡掉。

**已完成（`d80a8fb00`）——Batch 4，2/26 个效果（`directed_shock`/`mag_manip`，hand-only，收尾了原计划里"两个 hand-only 效果"这一类）**：这两个是唯一只有 `:hand` 轨道、没有 `:level` 轨道的效果，触发了计划里预留的一块新基础设施——`:hand` 的 `transform-fn` 是零参函数（`sample-hand!` 调用时不传任何上下文），不像 `:level` 的 `build-plan-fn` 能从 `hand-center-pos` 里拿到本地玩家的 `:player-uuid`，所以拍平之后没有天然的办法知道"该渲染哪个实例"。新增两层 API：vfx-core `runtime.clj` 的 `instance-for-owner`（`effect-id`+`owner` → instance id，扫 `owner-index`，镜像 `instance-for-effect` 的写法，带测试）；`effect_controller.clj` 的同名包装（返回该实例的 `:level`/`:hand` state）。两个 impl 文件都用 `cn.li.mcmod.client.platform-bridge/local-player-uuid` 解析"本地玩家是谁"再传给这个查找（`plasma_cannon.clj` 已经在用一模一样的手法判断"这是不是我自己的实例"，不是新发明）。`arc_beam.clj` 的 `build-spec` 顺带给 `:hand` 轨道也接上了 `:destroy-fn`（`:level` 轨道 Batch 1 就有，`:hand` 一直没有，因为在 `mag_manip` 的循环音效之前没有 `:hand` 轨道效果真的需要在销毁时释放资源）。执行前核实了两个效果的真实触发事件：`mag_manip` 只发一次 `:throw`（`:params {:throw-range 20.0}`）——`:hold-start`/`:hold-loop`/`:end` 都不发，循环音效（`start-hold-loop!`/`stop-hold-loop!`）从未真正播放过，跟 B 类"保守实现"的既有记录一致；`directed_shock` 只发一次 `:perform`（`:params {:charge-min-ticks 6}`），但这是 Batch 2-4 里第一个"唯一会到的那个事件真的对上一个真实 case 分支"的样本——挥拳动画确实会播，只有前摇（`:start` 对应的 `:stage :prepare`）是死的；挥拳音效则是另一个原因死的：它原本挂在 `:channels` 的 `:immediate-fn` 上，而 `:channels` 本身在 E 类 P1.3 就已经整体删成死代码。`mag_manip_fx.clj` 顺手清掉了一个无关的既有死代码：一个 `current-state` 函数，从没被任何地方调用过——碰巧在改的同一行旁边，不是单独开的一趟。**更正（Batch 5 复查时发现）**：上一条原本还说这个函数引用的 `fx-snapshot` 是未定义的、调用了会抛异常——这个说法是错的，`fx-snapshot` 是 `arc-beam/def-arc-beam-fx` 宏在调用方命名空间里生成的，`mag_manip_fx.clj` 在 `current-state` 定义之前已经调用过这个宏，这个 var 从来就不是未绑定的。函数本身确实是死代码（零调用方）这个结论不受影响，删除依然正确，只是当初给的理由不对。

**已完成（`80ac1bc42`）——Batch 5，5/26 个效果（`meltdowner`/`mine_detect`/`jet_engine`/`railgun_shot`/`thunder_clap`，计划里标注的最高复杂度批次）**：执行前照例先核对了全部 5 个效果的真实触发事件。三个（`meltdowner`/`mine_detect`/`jet_engine`）跟前几批一样彻底死透——真实事件是 `:release`，跟这三个文件任何一个 case 分支都对不上。`meltdowner` 尤其值得记一笔：原计划以为它需要一整套"施法结束后继续淡出"的状态机（因为拖尾光线故意要在 `:end` 之后活一段时间），执行前核实完发现这个担心整个不成立——`:perform`/`:reflect`（唯一会创建光线的两个分支）也从来不触发，根本没有光线可淡出。三个都按老办法处理：只拍平状态形状，case 分支原样保留，写清楚哪些事件今天真的会到。

这批另外两个是这一整轮 26 个效果里目前唯一两个"真正活着"的复杂样本：

- **`railgun_shot`**：`combat_content.clj` 的 `:railgun` 发的是 `:event :release :params-ref :hit`——跟这次迁移里其它所有效果都不一样，它不发字面量 `:params` map，而是把 `:raycast` query 自己的结果原样转发。这个结果真的带着 `:eye-x/:eye-y/:eye-z`（`combat_runtime.clj` 的 query-port 加的）和 `:hit-x/:hit-y/:hit-z`（raycast 结果本身自带，`attack.clj`/多个传送 dest 文件都拿这几个字段当权威数据用），正好是这个文件默认分支（`:release` 落进去的那个分支）拼装光束端点需要的全部数据。光束、电弧、命中光环今天真的会渲染。死的只有 `:charge-*` 系列分支——当前 `:railgun` 就是一个不带蓄力阶段的单序列即时技能，压根没有分开发送这几个事件的机制。`:beams` 拍平成一个 vector，`:charging` 拍平成一个可选的单值；没有加 `:destroy-fn`（没有循环音效，没有外部资源）——而且光束自带的 50 tick 寿命现在真能让实例借着 Batch 2 那个自然结束的修复正常退出了，不再是"打一枪永久占一个空实例槽位"。
- **`thunder_clap`**：`:perform` 真的对上了一个 case 分支（这批里唯一一个），而它做的事情相当意外——`:perform` 把 `:active?` 设成 `true`，但 `:target`/`:caster-pos` 都是 nil（准星标记和落雷特效因此确实不会出现），可是**下一个 tick** 的 `tick-surround` 调用会从 `nil` 的 `:surround` 重新生成一批电弧——因为 `(every? :dead [])` 对空集合永远返回 `true`（vacuous truth）。生产环境里今天真实发生的事：施法者身边会永远绕着噼啪作响的电弧特效（`:active?` 从不复位，`:end` 从不触发），走路速度会一路降到地板值并停在那里——没有准星标记，没有落雷特效，没有淡出尾巴。这不是这次迁移引入的行为，是照原样搬过来的既有效果，在 `tick-surround` 上写了很长一段注释专门讲清楚，免得被后人当成这次迁移手滑引入的 bug。

新增 `effect_controller.clj` API：`update-state-for-owner!`（`instance-for-owner` 的写版本）——`mine_detect` 的矿石重扫描结果需要在 `build-plan-fn` 内部写回**自己这个实例**的状态（`query-fn` 只在采样时才拿得到，`tick-state-fn` 永远拿不到），用 `update-state!` 会打到 `core/instance-for-effect` 挑出来的"随便一个 :mine-detect 实例"，一旦多个玩家同时用就会写错人。这顺带把计划里点名的"`mine_detect` 只渲染第一个 active owner"的潜藏 bug 从结构上解决了——虽然今天完全看不出来，因为压根没有事件能让它变 active。`jet_engine_fx.clj` 的 `flash-alpha`（2D 屏幕闪光 HUD 钩子，`reactive_hud.clj` 的真实活跃调用方，在 sample 回调之外调用、`*sample-state*` 没绑定）同样从旧的 owner-map 扫描换成了 `instance-for-owner`，因为它本来就已经拿到了唯一需要的 `player-uuid`。

**待办（Batch 6-7，9/26 个效果）**：`plasma_cannon`/`current_charging`（Batch 6，计划里标注的最高复杂度，开始前同样要先核实真实触发事件）；收尾审计（Batch 7）。`rad_intensify_mark`/`teleporter_crit` 的触发机制完全依赖已删除的死 channel 总线，在能进游戏验证或设计出新触发机制之前，迁移它们没有意义——已从 Batch 1 移出，未排期。

### F. presentation-core 剩余部分（P2.1/P2.2 有意重新定位并完成；死代码已删；P2.6 未动）

**现状**：`presentation-core` 有一套完整但**从未接入真实渲染路径**的保留模式基础设施（`core/tree.clj` keyed reconcile、`core/dirty.clj` 六标志、`core/frame.clj` 的 frame-graph）——已核实这些只被 presentation-core 自己的测试调用，`ac/src`、`platform-src`、`mcmod/src` 里零调用者。真实渲染路径是 `runtime/extract!` → `render-mount` → `presentation-compiler/render.clj`，每帧从 `TemplateNode` 全量重算布局，只用一个粗粒度的 `:invalidated?` 布尔做失效判断，不读 dirty 六标志。

**2026-08-18 更新，执行前重新核实：P2.1/P2.2 原方案目标选错了对象**。`core/tree.clj` 那套保留模式是围绕一种 `spec`/`RNode` 节点表示设计的（`tree/node`/`tree/reconcile`），而真实渲染路径用的是完全不同的数据模型——`presentation-compiler` 编译出的 `CompiledTemplate`/`TemplateNode`。两者除了都最终喂给共用的 `core/layout.clj` 之外没有交集。"把 `extract!` 接入 `reconcile → dirty → layout cache`"字面上做不到，因为要接的这套机制根本不认识 `TemplateNode`。

**已完成（`c38f125bb`）——按实际性能缺口重新定位的修复**：真正查到的问题比原方案窄得多——`layout/layout` 的输出**只是 `(template, width, height)` 的纯函数，完全不读绑定值**，但 `render-template` 每次调用都无条件对整棵 `TemplateNode` 树重新跑一遍。冷却/CP 每 tick 变化只改绑定值，不该触发重新布局，但 `runtime.clj` 的事务调度器不分青红皂白，任何变化都统一标记整帧失效，等于每次数值一变就重新布局整棵树。已在 `presentation-compiler/render.clj` 里按 `CompiledTemplate` 对象身份（确认它没有覆写 `equals`/`hashCode`，普通 map 当 key 天然是身份比较）+ `width`/`height` 缓存布局结果——`ac/gui/reactive/register.clj` 的 `resolve-template` 本来就把每个模板 id 解析成一个稳定复用的 `CompiledTemplate` 实例（`template-cache*`），缓存不需要淘汰策略。改动只涉及 `render.clj` 一个文件，没有碰 `core/tree.clj`/`core/dirty.clj`/`runtime.clj`。

**已完成（`61b87f4f1`）——删除确认死代码**：`core/tree.clj`、`core/dirty.clj` 整个文件，`frame.clj` 的 `frame-graph`/`validate-graph!`/`order`，`runtime.clj` 的 `mount-tree!`/`retained-tree`/`reconcile-tree!`/`layout-tree!`/`dirty-flags`/`take-dirty!` 及 `create-runtime` 的 `:dirty` 状态槽位全部删掉。`layout.clj` 顺带去掉了一个从未被用过的 `tree` require（它其实只消费 `{:type :props :children}` 这种普通 map，不依赖 `tree.clj` 的 `RNode`）。**删除过程中发现第一轮跨模块 grep 漏了一处**：`host.clj` 里的 `mount-tree-host!`/`reconcile-tree!`/`layout-tree!` 三个薄封装函数直接调用刚删掉的 `runtime.clj` 函数，名字跟 runtime.clj 里的不一样所以没被第一次搜索命中，编译时才报错发现——重新核实了 `host.clj` 的 4 个真实外部调用者（`presentation_hud.clj`/`presentation_container.clj`/`register.clj`/`presentation_terminal.clj`）全部只用 `mount-host!`（不带 tree 的那个），确认这三个封装同样是安全删除，一并清理。测试套件从 11 个/36 断言降到 7 个/22 断言（删掉的 4 个测试全部在测这次删除的代码），其余测试全绿。

**未动（P2.6）**：`presentation_hud.clj` 的 `:composite-list` 扁平化（把布局职责挪回 AC 代码里做）改为在 `ac/src/main/resources/assets/academy/presentation/combat_hud.ui.edn` 里用真实模板节点表达——这项会改变**战斗 HUD（游戏里最高频可见的界面）的实际渲染输出**，正确性只能通过进游戏截图比对验证，本环境无法验证，本轮未动。

## 建议的下手顺序

1. **先做全量审计，不要分批摸索**：对全部 37 个技能逐条核实 query 侧 + world-effect 侧 + 所需的目标检测/物理原语是否真实存在于平台代码里。本轮工单里的每一条分类，都是"以为已经 wired，深入一层才发现没有"这个模式反复出现以后才逐渐收敛出的准确清单——不要重复这个摸索过程。
2. ~~C 类 4 个技能~~ / ~~C-2 类 6 个技能点（含 groundshock）~~ / ~~D 类全部 3 个 world-effect 类型~~ / ~~A 类全部 6 个技能（含 shift-teleport）~~ / ~~B 类 2 个技能（mag-manip/mag-movement）~~ **均已完成**，见上方 C、C-2、D、A、B 节（light-shield/electron-missile/scatter-bomb/mag-manip/current-charging 是保守简化版本，`:knockback` 的推力方向/公式、mag-manip/groundshock/shift-teleport/current-charging 的部分数值是推断/移植自旧配置的，approval-token 桥接机制是本轮新增的平台基础设施，均仍需进游戏验证）。
3. **"需要设计判断"这个分类已经连续四次被证明是假阳性**（mag-manip 的金属判定、groundshock 的传播算法、shift-teleport 的整个机制、current-charging/mine-detect 的机制，全都在 git 历史里找到了 `a8c000766`——combat-core 迁移那次提交——删掉的完整实现）。**下次遇到类似结论，先 `git log --diff-filter=D -- "*<skill-name>*"` 查一遍再采信**，不要重复"假设需要设计输入→论证→被推翻"这个循环。全部 37 个技能到这里已经过了一遍——本工单列出过的技能里，唯二没有在 git 历史查到可恢复实现、货真价实需要设计输入的只剩：
   - **需要新基础设施**：C-2 类的 `jet-engine`（平台层通用延迟调度设施）。
   - **可恢复但要重写现有实现（不是设计阻塞，是工作量问题）**：mag-manip 若要从当前的保守直接伤害版还原成真实物理实体悬浮/抛掷，current-charging 若要补上手持物品充能分支，都不需要新平台设施（`query-core/get-player-by-uuid` 已经在到处用），但需要把对应逻辑从 query 挪到 world-effect（platform-src）重写，本轮未评估是否值得。
4. **E 类里安全、体量小的部分（死代码 channel 总线）已经删了；F 类里对症、体量小的部分（layout 结果缓存 + 确认死代码 `core/tree.clj`/`core/dirty.clj`/`frame-graph`/`host.clj` 的 tree 封装）都已经处理完**，见上方 E、F 节。两节剩下的都是同一档：E 剩 P1.1 生命周期形态 + P1.2 拆 `dispatch-signal!` 真正的旁路（要重写 `arc_beam.clj` 全家 6105 行），F 剩 P2.6（`presentation_hud.clj` 的 `:composite-list` 扁平化改真实模板节点，会改变战斗 HUD 实际渲染输出）——都是大改动、高风险、只能进游戏验证，建议单独排期，不要和小修小补混在一个提交序列里。

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
