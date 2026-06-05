# 无线系统维护手册

> 状态标签：**现行**

无线系统负责：矩阵网络（SSID/密码/容量/距离）、无线节点入网、NodeConn（发电机/接收器挂接）、网络内能量均衡、节点连接传输、世界持久化与 GUI 查询。

**当前实现为单一函数式运行时**：不可变实体 + 纯计划（`domain.transfer` / `domain.topology`）+ 窄副作用边界（`runtime.effects` + capability）。**不存在**第二套拓扑服务或 `data.world` 命令代理层。

## 模块边界

### 对外与编排

| 命名空间 | 职责 |
|----------|------|
| `cn.li.ac.wireless.api` | **唯一**对外入口：查询、拓扑命令、拓扑事件、`network-snapshot` |
| `cn.li.ac.wireless.service.commands` | 拓扑写命令、spatial 索引命令、NBT 重建索引；`transact!` + 日志 |
| `cn.li.ac.wireless.service.queries` | 只读查询（tile → vblock → lookup） |

### 领域（纯函数）

| 命名空间 | 职责 |
|----------|------|
| `cn.li.ac.wireless.domain.topology` | world-state map 上的注册/注销网络、连接、节点入网校验 |
| `cn.li.ac.wireless.domain.transfer` | `balance-plan`、generator/receiver 传输计划 |
| `cn.li.ac.wireless.domain.model` | 容量、距离等纯校验 |

### 数据与运行时

| 命名空间 | 职责 |
|----------|------|
| `cn.li.ac.wireless.data.world-registry` | **唯一**可变提交点（`transact!`） |
| `cn.li.ac.wireless.data.entity-commit` | 将新 `WirelessNet`/`NodeConn` 写回 vectors 与 lookup |
| `cn.li.ac.wireless.data.network-lookup` | 只读：按 matrix/node/ssid 查网络、按 vblock 查连接 |
| `cn.li.ac.wireless.data.spatial-lookup` | 只读/底层：空间索引 chunk 查询 |
| `cn.li.ac.wireless.data.network-state` | `WirelessNet` 记录与 accessor |
| `cn.li.ac.wireless.data.node-conn` | `NodeConn` 记录、设备 attach/remove、tick 传输入口 |
| `cn.li.ac.wireless.data.network-runtime` | 网络 tick（间隔、validation、balance） |
| `cn.li.ac.wireless.data.network-energy-balance` | 均衡薄编排（计划 + effects） |
| `cn.li.ac.wireless.data.network-validation` | 矩阵/节点 capability 校验、dispose |
| `cn.li.ac.wireless.data.network-mutation` | SSID/密码变更（由 `commands` 调用） |
| `cn.li.ac.wireless.data.world-runtime` | 世界 tick、dispose validator |
| `cn.li.ac.wireless.data.world` | **仅**生命周期与 SavedData（`on-world-*`） |
| `cn.li.ac.wireless.data.persistence` | NBT schema v1 |
| `cn.li.ac.wireless.runtime.effects` | `.setEnergy` / generator pull / receiver inject |
| `cn.li.ac.wireless.config` | 配置 descriptors 与 getter |

### 内容与平台

| 区域 | 约定 |
|------|------|
| `ac/block/wireless_*` | 经 `wireless.api` 入网/挂接/破坏清理；tick 经 `wireless-node.logic` |
| `ac/wireless/gui` | 查询经 `wireless.api` 或 snapshot；勿直接改 world-state |
| `cn.li.ac.energy.operations` | 仅委托 `wireless.api` 查询连接状态 |
| `api` 模块 Java | `cn.li.acapi.wireless.IWireless*` — capability 契约，非 Clojure 领域模型 |

### 已删除（勿再引用）

`topology-service`、`topology-index`、`network-membership`、`query-service`、`network-command`、`world-topology`、`data.world/*-impl!`。

## 运行时流程

```text
1. 放置 Matrix → API create-network! → commands → domain.topology/register-network → transact!
2. 放置 Node → API register-node-spatial!；入网 → link-node-to-network!
3. 放置 Generator/Receiver → API link-*-to-node! → commands → node-conn add → topology link device
4. 每 tick → world-runtime → network-runtime（balance）+ node-conn tick（传输）
5. Save → persistence/world-data-to-nbt；Load → world-data-from-nbt → commands/rebuild-*-indexes!
```

要点：

- 节点切换网络时，**先**从旧网络 unlink，再入新网（同步更新 lookup）。
- 设备切换 NodeConn 时，旧连接上的设备条目**立即**移除。
- 网络均衡使用矩阵带宽 与节点 bandwidth；缓冲 `:buffer` 保存在 network state。

## 调用约定（新增代码）

```text
需要改拓扑 / 入网 / 挂接     → wireless.api（或 tests 中用 service.commands + world-registry）
需要只读查询网络/连接       → wireless.api 或 service.queries
需要改 spatial 索引（节点）  → wireless.api/register-node-spatial! 或 commands/add-spatial-vblock!
需要读 world 生命周期        → data.world（get-world-data、on-world-save 等）
需要直接读索引（内部实现）   → network-lookup / world-registry（仅限 commands/queries/persistence/runtime）
需要改网络/连接实体字段      → network-state / node-conn 纯函数 + entity-commit（勿手写 lookup patch）
需要 capability 充放电       → runtime.effects（勿在 domain/commands 里调 Java energy API）
```

## 状态一致性规则

1. 多索引更新必须在同一 `transact!` 内完成，或委托给已使用 `transact!` 的 `commands`/`node-conn`/`entity-commit`。
2. 禁止延迟删除队列；unlink 必须同步更新 `:net-lookup` / `:node-lookup`。
3. GUI 使用 `wireless.api/network-snapshot` 或 API 查询结果，不持有可变实体引用。
4. 测试构造网络时使用 `commands/create-network!` 与 `lookup/get-network-by-ssid`，**不要**使用已删除的 `create-network-impl!`。

## 扩展点

| 需求 | 修改位置 |
|------|----------|
| 新拓扑操作 | `domain.topology` 纯函数 + `service.commands` 包装 + `api` 暴露（如需） |
| 新查询 | `service.queries` + 可选 `api` 委托 |
| 新均衡策略 | `domain.transfer/balance-plan` + `runtime.effects` |
| 新配置项 | `wireless.config/descriptors` |
| 新 NBT 字段 | 提升 `persistence/schema-version`，集中读写 |
| 新方块行为 | `wireless.api`；保持 block 层薄 |

## 排障手册

| 现象 | 检查项 |
|------|--------|
| 节点不入网 | 密码、`domain.topology/validate-add-node`、矩阵 capacity/range、resolver 能否 resolve matrix/node |
| 发电机/接收器挂不上 | NodeConn capacity/range、`node-lookup` 是否指向最新 conn、旧连接是否已 unlink |
| 能量不均衡 | `network-config/update-interval-ticks`、`balance-plan`、节点是否被 validation dispose |
| 存档后索引错乱 | `persistence` 输出、`rebuild-network-indexes!` 是否包含所有 `:nodes` 的 lookup 项 |
| GUI 显示与实体不一致 | 是否绕过 API 缓存了旧 network 引用；应重新 query 或使用 snapshot |
| `identical?` 失败但数据对 | 加载后实体为新实例，应通过 lookup 按 vblock/ssid 取当前引用 |

## 变更风险

- 修改 `domain.topology` 索引形状时，须同步 `entity-commit`、`persistence` 重建与相关测试。
- 修改 NBT schema 须考虑旧存档迁移。
- 在 `node-conn` 设备 attach 路径绕过 `domain.topology/link-connection-device` 会导致 lookup 与 `:generators`/`:receivers` 不一致。

## 校验命令

```powershell
cmd /c .\gradlew.bat :ac:compileClojure
cmd /c .\gradlew.bat runAcUnitTests
cmd /c .\gradlew.bat verifyArchitectureBoundaries
cmd /c .\gradlew.bat verifyCleanupResidueGuards
```

## 相关文档

- 架构契约：[../05-wireless/WIRELESS_REFACTOR_CONTRACTS.md](../05-wireless/WIRELESS_REFACTOR_CONTRACTS.md)
- 文档索引：[../05-wireless/README_STATUS.md](../05-wireless/README_STATUS.md)
