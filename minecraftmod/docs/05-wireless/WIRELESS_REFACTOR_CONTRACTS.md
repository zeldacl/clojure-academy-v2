# Wireless Refactor Contracts

> 状态标签：**现行**（重构约束与架构契约）

本文档描述**当前**无线能源运行时的唯一架构。旧的并行 facade（`topology-service`、`topology-index`、`network-membership`、`query-service`、`network-command`、`world-topology`、`data.world` 上的 `*-impl!` 别名等）已删除，**不得**再引入兼容层或第二套命令路径。

## 1. 架构总览

```text
外部调用方（block / GUI / terminal / energy.operations）
        │
        ▼
cn.li.ac.wireless.api          ← 对外唯一入口（查询、拓扑命令、拓扑事件、snapshot）
        │
        ├── service.queries    ← 只读：tile → vblock → network-lookup / spatial-lookup
        │
        └── service.commands   ← 写入：domain 纯函数 + world-registry/transact! + entity-commit
                │
                ├── domain.topology   ← 纯 world-state 索引/入网变换（无副作用）
                ├── domain.transfer   ← 纯能量均衡/NodeConn 传输计划
                └── domain.model      ← 纯容量/距离等校验辅助

运行时 tick / 持久化 / 生命周期
        │
        ├── data.world-registry     ← 唯一可变提交点（transact!）
        ├── data.entity-commit        ← 替换 :networks/:connections 与 lookup 中的实体引用
        ├── data.network-state        ← WirelessNet 不可变记录 + commit 辅助
        ├── data.node-conn            ← NodeConn 不可变记录；设备 attach 经 topology + commit
        ├── data.network-runtime      ← 网络 tick → validation → energy-balance
        ├── data.world-runtime        ← 世界 tick、validator、统计
        ├── data.world                ← **仅** 生命周期与 SavedData（不代理命令/lookup）
        ├── data.persistence          ← NBT schema v1
        └── runtime.effects           ← 唯一 capability 能量 IO 边界（.setEnergy / inject / pull）
```

## 2. Public API Contract

### 2.1 入口

- 游戏逻辑、GUI、方块、终端、其它 mod 内容应使用 **`cn.li.ac.wireless.api`**。
- 模块内部实现使用 **`service.commands`**（写）与 **`service.queries`**（读）；**不要**在 block/GUI 中直接调用 `data.world-registry` 或 `network-lookup`，除非你在实现 `commands`/`queries` 本身。

### 2.2 API 职责（节选）

| API | 说明 |
|-----|------|
| `create-network!` / `destroy-network!` | 矩阵网络创建/销毁；含拓扑事件 |
| `link-node-to-network!` / `unlink-node-from-network!` | 节点入网/退网 |
| `link-generator-to-node!` / `unlink-generator-from-node!` | 发电机挂接 |
| `link-receiver-to-node!` / `unlink-receiver-from-node!` | 接收器挂接 |
| `get-wireless-net-by-*` / `get-node-conn-by-*` | 查询（委托 `queries`） |
| `register-node-spatial!` / `unregister-node-spatial!` | 节点放置/破坏时的空间索引维护 |
| `network-snapshot` 等 | 对 `network-state/snapshot` 的稳定只读视图，供 presenter 使用 |

### 2.3 已删除（禁止恢复）

- `wireless.service.topology-service`
- `wireless.data.topology-index`（逻辑已并入 `domain.topology` + `commands`）
- `wireless.data.network-membership`
- `wireless.service.query-service`（逻辑已并入 `service.queries`）
- `wireless.service.network-command`
- `wireless.data.world-topology`
- `data.world` 上的 `create-network-impl!`、`link-node-to-network!` 等命令别名
- `WirelessQueryApi` 等未接线的 Java stub 桥

## 3. World State Contract

- 每个世界一份 `WiWorldData`，由 `world-registry` 管理，状态 map 包含：
  - `:net-lookup`、`:node-lookup`、`:spatial-index`、`:networks`、`:connections`
- **所有**多索引变更必须在 `world-registry/transact!` 内完成，或通过已封装 `transact!` 的 `commands` / `node-conn` / `entity-commit` 路径。
- `WirelessNet` / `NodeConn` 为不可变 record；字段变更产生新实例，经 `entity-commit` 写回 world state。
- 节点/设备/网络 **unlink 立即生效**；禁止 `to-remove-*` 延迟队列。

## 4. 纯函数与副作用边界

| 层 | 允许副作用 | 不允许 |
|----|------------|--------|
| `domain.*` | 无 | `transact!`、capability、日志 |
| `service.commands` | `transact!`、日志、编排 | 直接 `.setEnergy` |
| `runtime.effects` | capability 能量 IO | 修改 world-state 索引 |
| `network-energy-balance` | 调用 effects + commands（清理失效节点） | 手写索引 patch |

网络均衡流程：`network-runtime/tick` → `network-energy-balance/balance-energy!` → `domain.transfer/balance-plan` → `runtime.effects/apply-node-energy-plan!`。

NodeConn 传输：tick 内 `domain.transfer` 计划 + `runtime.effects` 执行 generator/receiver IO。

## 5. Capability Boundary

- Tile 解析：`cn.li.ac.wireless.core.capability-resolver`。
- 在调用 `IWirelessMatrix` / `IWirelessNode` / `IWirelessGenerator` / `IWirelessReceiver` 方法前必须先 resolve capability。
- VBlock 与 NBT：`wireless.core.vblock`、`wireless.data.vblock-codec`；位置纯逻辑可复用 `cn.li.ac.foundation.vblock`。

## 6. Persistence Contract

- 权威编解码：`cn.li.ac.wireless.data.persistence`，**schema version `1`**。
- Save：序列化当前 `:networks` / `:connections`（跳过 disposed）。
- Load：`world-data-from-nbt` 创建新 `WiWorldData`，对每个实体调用 `commands/rebuild-network-indexes!` 与 `commands/rebuild-connection-indexes!`（内部使用 `domain.topology`）。
- `data.world` 只负责生命周期钩子（`on-world-load` / `on-world-save` / `on-world-tick`），不持有第二套持久化路径。

## 7. Config Contract

- 无线配置仅在 `cn.li.ac.wireless.config`（含 descriptors 与 typed getter）。
- 聚合入口：`cn.li.ac.config.registry`；勿在 block/data 包下新增无线 config namespace。

## 8. Energy 模块边界

- `cn.li.ac.energy.operations` 中 `get-wireless-network` / `is-node-connected?` **委托** `wireless.api`（合法跨模块边界）。
- 不得在 `energy` 内复制无线拓扑或 world-state 逻辑。

## 9. 测试与门禁

在 `minecraftmod` 根目录：

```powershell
cmd /c .\gradlew.bat :ac:compileClojure
cmd /c .\gradlew.bat runAcUnitTests
cmd /c .\gradlew.bat verifyArchitectureBoundaries
cmd /c .\gradlew.bat verifyCleanupResidueGuards
```

无线相关测试 namespace 示例：`cn.li.ac.wireless.*`、`cn.li.ac.contract.energy-wireless-contract-test`。

定向过滤：

```powershell
cmd /c .\gradlew.bat runAcUnitTests "-Dac.test.only=cn.li.ac.wireless.data.network-test,cn.li.ac.wireless.data.world-test,cn.li.ac.wireless.service.commands-test,cn.li.ac.contract.energy-wireless-contract-test"
```

## 10. 相关文档

- 维护手册：[../04-systems/WIRELESS_SYSTEM_MAINTENANCE.md](../04-systems/WIRELESS_SYSTEM_MAINTENANCE.md)
- 命名空间迁移图：[../dev/AC_REFACTOR_NAMESPACE_MIGRATION_MAP.md](../dev/AC_REFACTOR_NAMESPACE_MIGRATION_MAP.md)
- AC 分层约定：[../02-architecture/AC_MODULE_LAYERING.md](../02-architecture/AC_MODULE_LAYERING.md)
