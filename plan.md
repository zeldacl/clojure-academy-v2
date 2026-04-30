# Ability系统迁移执行手册（可直接交付Agent）

以"功能完整对齐旧版1.12 ability系统、架构按新工程规范重构"为唯一目标。执行策略是先建立可验证的协议和数据骨架，再逐步接入规则、网络、客户端表现，最后以功能矩阵逐项验收，确保无遗漏。

---

## 决策基线（不可更改）

- **功能目标**：完整能力系统对齐（不做裁剪）。
- **平台目标**：本轮仅 Forge 1.20.1，Fabric 预留扩展点。
- **重构策略**：领域重构优先，不保留旧接口兼容层。
- **架构边界**：`ac` = 功能规则；`mcmod` = 协议与通用机制；`forge` = Minecraft 绑定。

---

## 关键参考文件

| 文件 | 用途 |
|------|------|
| `minecraftmod/docs/98-archive/ARCHITECTURE.md` | 历史架构说明（归档；现行见 `minecraftmod/docs/02-architecture/Runtime_And_DSL_CN.md`） |
| `minecraftmod/docs/02-architecture/CLIENT_SERVER_SEPARATION.md` | 客户端隔离规则 |
| `mcmod/.../platform/capability.clj` | 协议与能力挂接模式 |
| `mcmod/.../events/metadata.clj` | 事件元数据注册 |
| `mcmod/.../events/dispatcher.clj` | 事件分发入口 |
| `mcmod/.../network/server.clj` | 服务端消息注册 |
| `mcmod/.../network/client.clj` | 客户端请求/回调 |
| `forge-1.20.1/.../forge1201/events.clj` | Forge 事件桥接入口 |
| `docs/05-wireless/WIRELESS_REFACTOR_CONTRACTS.md` | 消息目录与同步契约参考 |

---

## 门禁命令（每个 Phase 结束强制通过后再进入下一个）

```bash
rtk ./gradlew :mcmod:classes
rtk ./gradlew :ac:compileClojure
rtk ./gradlew :forge-1.20.1:classes
rtk rg "net\.minecraft\.client" ac/src/        # 结果必须为空
rtk rg "cn\.li\.ac" forge-1.20.1/src/          # 结果必须为空
rtk ./gradlew :forge-1.20.1:compileClojure
```

---

## Phase 0 — 启动与约束冻结（阻塞全部）

**输入**：旧版 ability 源码（java + scala），现仓库架构文档与分层规范。

**动作**：
1. 冻结迁移约束：`ac` 仅领域逻辑，`mcmod` 仅协议/元数据/通用机制，`forge` 仅平台绑定；不做旧 API 兼容层。
2. 确认旧版 ability 源码目录：
   - `https://github.com/LambdaInnovation/AcademyCraft/src/main/java/cn/academy/ability`
   - `https://github.com/LambdaInnovation/AcademyCraft/src/main/scala/cn/academy/ability`

**产物**：迁移约束清单（以注释写入首个实现文件或 docs 文档）。

**DoD**：约束覆盖依赖方向、客户端隔离、事件与网络归属；后续实现不得违背。

---

## Phase 1 — 功能矩阵与追踪表（阻塞 Phase 2+）

**输入**：旧版 ability 全量代码。

**动作**：建立"能力功能矩阵"与"新旧映射表"，覆盖以下子系统：

| 子系统 | 覆盖要点 |
|--------|----------|
| 分类/技能注册 | 依赖树、可控性标记、图标、本地化键、启用开关 |
| 学习条件 | 等级门槛、前置技能经验、开发器类型门槛 |
| 经验与升级 | 经验累积、升级阈值公式、等级上限(5) |
| CP/Overload 资源 | 初始值、恢复速度、消耗、计算事件 |
| 冷却 | 主冷却+子冷却键空间、叠加取最大值 |
| Context 生命周期 | CONSTRUCTED → ALIVE → TERMINATED 状态机、握手消息缓冲与刷出、keepalive/超时 |
| 消息路由 | sendToServer/sendToClient/sendToLocal/sendToExceptLocal/sendToSelf |
| Preset/按键运行时 | 4 预设 × 4 键位、keydown/keytick/keyup/abort 四态 |
| HUD | CP 条、Overload 条、激活态、过载恢复态、键位提示 |
| 技能树/开发器 GUI | 可学条件可视化、经验进度、升级动作、预设编辑 |
| 事件族 | 学习、经验变化、等级变化、分类变化、过载、启停、预设切换、计算事件 |
| 配置项 | 全局与分类/技能级，伤害/消耗/经验倍率，世界规则开关 |

**产物**：逐项追踪表（每行包含：旧行为、目标行为、目标模块、测试点）。

**DoD**：矩阵中每个条目都可映射到"待实现步骤 + 验收用例"；不允许"待确认"空白项。

---

## Phase 2 — mcmod 协议层扩展（阻塞 Phase 3/4）

**输入**：Phase 1 映射表。

**动作**：

1. 新增/扩展 ability 相关通用协议与基础设施：
   - 玩家能力访问协议（分类、技能学习态、技能经验、等级）
   - 资源池协议（CP/Overload 读写、可用性判定）
   - 冷却协议（主/子冷却查询与设置）
   - Context 路由协议（activate/terminate/send/keepalive）
   - player 维度事件元数据注册与分发入口（学习、经验、等级、过载、启停、预设切换）
   - ability message catalog（统一命名、集中注册）

2. 明确序列化边界：`ac` 输出纯数据 map；平台侧做 NBT 和网络包转换。

**参考模式**：沿用 `WIRELESS_REFACTOR_CONTRACTS.md` 定义的消息目录与 sync payload 契约。

**产物**：可被 forge 调用的协议层 API 与文档注释。

**DoD**：`mcmod` 不包含 Minecraft 类引用；协议命名稳定；消息目录无散落字符串拼接。

---

## Phase 3 — ac 领域建模与规则实现（依赖 Phase 2；可与 Phase 4 并行）

**输入**：Phase 2 协议。

**动作**：

1. 建立 ability 领域模块骨架：
   ```
   ac/src/main/clojure/cn/li/ac/ability/
   ├── category.clj       ; 分类注册与元数据
   ├── skill.clj          ; 技能定义与属性
   ├── controllable.clj   ; 可控性标记与序列化键
   ├── context.clj        ; Context 状态机与消息路由
   ├── model/
   │   ├── ability_data.clj   ; 类别、技能位图、经验、等级进度
   │   ├── resource_data.clj  ; CP/Overload、恢复计时、干扰源
   │   ├── cooldown_data.clj  ; 控制键+子键冷却空间
   │   └── preset_data.clj    ; 4×4 预设槽位
   ├── service/
   │   ├── learning.clj   ; 学习条件判定与经验升级
   │   ├── resource.clj   ; CP 消耗/恢复、Overload 机制
   │   ├── cooldown.clj   ; 冷却管理
   │   └── context_mgr.clj ; Context 生命周期管理
   ├── event.clj          ; 领域事件定义
   └── config.clj         ; 配置树绑定
   ```

2. 实现四类玩家状态聚合：
   - **AbilityData**：类别 ID、技能学习位图、技能经验数组、等级进度。
   - **ResourceData**：curCP/maxCP、curOverload/maxOverload、恢复计时、干扰源 map。
   - **CooldownData**：`(controlID << 2 | subID)` 键空间，tick 倒计时。
   - **PresetData**：4 预设 × 4 键槽，稀疏编码。

3. 实现规则引擎：
   - 学习条件判定并触发学习事件。
   - 技能经验累积（单技能上限 1.0）与升级阈值计算。
   - CP 消费校验与消耗、恢复冷却。
   - Overload 叠加、过载锁定（`canUseAbility = false`）、恢复解锁。
   - 冷却"取最大值"叠加策略。

4. 实现 Context 状态机：
   - `CONSTRUCTED → ALIVE → TERMINATED`（单向，无非法回退）。
   - 握手前消息缓冲，建立后原子刷出。
   - 服务端权威提交；客户端仅展示与受控预测。

**产物**：`ac` 领域服务 API 与可单测的纯逻辑函数。

**DoD**：`ac` 无 `net.minecraft.*` 引用；核心规则具备单元测试；状态机无非法回退。

---

## Phase 4 — forge-1.20.1 适配与持久化（依赖 Phase 2；可与 Phase 3 并行）

**输入**：Phase 2 协议 + Phase 3 初版服务。

**动作**：

1. 接入 Forge 玩家生命周期事件并桥接到 `mcmod` 分发入口：
   - `PlayerEvent.PlayerLoggedInEvent`
   - `PlayerEvent.PlayerRespawnEvent` / `PlayerEvent.Clone`
   - `LivingDeathEvent`（玩家）
   - `TickEvent.PlayerTickEvent`
   - `PlayerEvent.PlayerLoggedOutEvent`

2. 绑定 capability/附件与持久化：
   - 登录时从 NBT 恢复 ability 状态。
   - 保存时将纯数据 map 写回 NBT。
   - 脏数据同步触发策略（dirty flag + 周期 flush）。

3. 实现网络桥接：
   - 客户端请求 → 服务端校验 → 广播/回包。
   - Context keepalive（0.5 s 周期）与超时回收（1.5 s 阈值）。
   - 近邻广播范围（25 m 查询）。

4. 客户端隔离：GUI/HUD/按键/渲染仅在 forge client 子层，通过 `side/resolve-client-fn` 安全加载。

**产物**：Forge 平台可运行的能力适配层。

**DoD**：专用服务端启动无客户端类加载错误；forge 通过 `mcmod` 协议访问能力，无反向耦合（`rtk rg "cn\.li\.ac" forge-1.20.1/src/` 为空）。

---

## Phase 5 — 能力 DSL 与技能内容迁移（依赖 Phase 3+4）

**输入**：可用的领域层 + 平台桥接。

**动作**：

1. 实现技能 DSL，支持：
   - 前置依赖声明。
   - 开发器类型门槛（PORTABLE/NORMAL/ADVANCED）。
   - 启用开关、图标路径、本地化键。
   - 默认 CP 消耗/经验倍率参数。

2. 按旧版分类迁移全量技能内容，允许内部结构重构，但行为必须等价。

3. 补齐可配置参数挂接（伤害倍率、消耗倍率、经验倍率、破坏方块规则）。

**产物**：可加载的全量能力分类与技能定义。

**DoD**：功能矩阵中的每个技能条目都映射到新 DSL 定义；禁用开关可生效。

---

## Phase 6 — 客户端运行时与界面（依赖 Phase 5）

**输入**：完整技能定义与服务端逻辑。

**动作**：

1. 实现按键运行时与 Preset 切换：
   - `keydown` → 创建 Context，发起握手。
   - `keytick` → 持续发送心跳与技能 tick 消息。
   - `keyup` → 终止 Context。
   - `abort` → 强制终止（如死亡、分类切换）。
   - Preset 切换时卸载旧委托，加载新委托。

2. 实现 HUD：
   - CP 条（蓝色，cur/max）。
   - Overload 条（橙色，cur/max，过载恢复态特殊显示）。
   - 激活态指示器。
   - 4 键位提示（图标 + 文字）。

3. 实现技能树与开发器 GUI：
   - 技能节点可学条件可视化（通过/未通过指示）。
   - 技能经验进度条（0–1.0 per skill）。
   - 升级动作按钮。
   - 预设编辑：4×4 网格，可拖拽分配技能到键位，切换激活预设。

4. 所有 UI 操作走"发请求 → 服务端校验 → 回包 → 同步"闭环，不直接修改权威状态。

**产物**：可交互客户端能力体验。

**DoD**：UI 与服务端状态最终一致；条件不足/资源不足/冷却中的异常路径反馈正确。

---

## Phase 7 — 事件、平衡与扩展点（依赖 Phase 5+6）

**输入**：能力系统主链路可运行版本。

**动作**：

1. 补齐事件族（全部在服务端 post，除非注明双端）：
   - `AbilityActivateEvent` / `AbilityDeactivateEvent`
   - `SkillLearnEvent`（双端）
   - `SkillExpAddedEvent` / `SkillExpChangedEvent`
   - `LevelChangeEvent`
   - `CategoryChangeEvent`（双端）
   - `OverloadEvent`
   - `PresetUpdateEvent`（双端）/ `PresetSwitchEvent`（双端）
   - 计算事件：`CalcEvent.SkillAttack` / `.MaxCP` / `.MaxOverload` / `.CPRecoverSpeed` / `.OverloadRecoverSpeed`

2. 补齐配置树：
   - 全局：`init_cp[5]`, `init_overload[5]`, `cp_recover_speed`, `overload_recover_speed`, `prog_incr_rate`。
   - 分类：启用开关、图标、颜色风格。
   - 技能：`damage_scale`, `cp_consume_speed`, `overload_consume_speed`, `exp_incr_speed`, `enabled`, `destroy_blocks`。

3. 为后续 mod 扩展保留可调节计算钩子（计算事件在使用前 post，外部可修改字段后返回）。

**产物**：可调参与可扩展的稳定版本。

**DoD**：配置修改可生效；事件触发时机与旧版行为一致；计算事件覆盖伤害、上限、恢复速度。

---

## Phase 8 — 验收、回归与收尾（阻塞交付）

**输入**：Phase 0–7 实现结果。

**动作**：

1. 执行自动化测试：
   - **单测**：学习条件判定、升级阈值公式、资源恢复速率、冷却键计算、状态机转移。
   - **集成测试**：登录加载、重连状态同步、死亡/克隆数据保真、Context 超时终止。

2. 人工回归全链路：
   - 新建角色 → 获得开发器 → 解锁分类 → 学习技能 → 连续施法（CP消耗与恢复）。
   - 触发过载 → 等待恢复 → 再次施法。
   - 切换 Preset → 验证键位绑定变化。
   - 打开技能树 GUI → 升级 → 验证 CP 上限变化。
   - 服务端关闭重启 → 验证状态持久化。

3. 对照 Phase 1 功能矩阵逐项打勾，输出差异报告（目标：0 功能缺失）。

4. 门禁命令全部通过（见文档顶部）。

**产物**：迁移完成报告（覆盖率、已知风险、后续建议）。

**DoD**：功能矩阵全通过；构建与运行通过；关键路径无阻断级缺陷。

---

## 并行执行策略

```
Phase 0 ──► Phase 1 ──► Phase 2 ──┬──► Phase 3 ──┐
                                   └──► Phase 4 ──┴──► Phase 5 ──► Phase 6 ──► Phase 7 ──► Phase 8
                                   
Phase 3 与 Phase 4 可并行。
Phase 5 可在 Phase 3 核心稳定后提前迁移静态技能元数据（不等 Phase 4 全部完成）。
Phase 6 必须等待 Phase 5 主体完成。
Phase 8 需全量冻结后执行。
每个 Phase 结束先过门禁再进入下一个，避免误差放大。
```

---

## 交付标准（Definition of Done）

| 维度 | 标准 |
|------|------|
| 行为层 | 旧版能力系统功能清单 100% 对齐，无功能遗漏 |
| 架构层 | 严格满足 `platform → mcmod → ac` 单向依赖与客户端隔离规则 |
| 质量层 | 关键路径有自动化测试；构建与运行验证通过 |
| 文档层 | 功能矩阵、差异报告、维护说明齐备 |
