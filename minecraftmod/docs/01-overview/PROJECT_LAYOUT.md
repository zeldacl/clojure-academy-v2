# 工程布局与命名空间（人类可读）

与 Cursor 规则 [`.cursor/rules/project-structure.mdc`](../../.cursor/rules/project-structure.mdc) 描述一致；**以本文与 `settings.gradle` 为准** 修改布局时，请同步更新该规则文件，避免分叉。

## 顶层 Gradle 模块

| 模块 | 职责 |
|------|------|
| **`api`** | 对外 Java API（如互操作用的接口包），无 Clojure 游戏逻辑 |
| **`mcmod`** | 平台无关：协议、DSL、`protocol.metadata`、事件/GUI/NBT 等元数据；**禁止** `net.minecraft.*` 与 Loader API |
| **`ac`** | 游戏内容与域逻辑；**禁止**直接引用 Forge/Fabric/Minecraft API；通过 `mcmod` 与约定边界交互 |
| **`forge-1.20.1`** | Forge 入口、注册、桥接 Java、实现 `mcmod` 协议；允许通过受控运行时桥接使用 `ac` 能力 |
| **`fabric-1.20.1`** | 可选 Fabric 适配；默认可能未在 `settings.gradle` 中 `include` |

## 依赖红线（以“静态耦合”约束为主）

- **禁止** `ac` 对 `forge-1.20.1` 建立静态依赖（命名空间/类依赖）。
- **禁止**在 `mcmod` 与 `ac` 中引入平台 API（`net.minecraft.*` / Forge/Fabric）。
- **允许** `forge-1.20.1` 对 `ac` 进行受控运行时桥接（动态入口），用于装配与平台绑定。
- 运行时桥接必须：
  1. 有明确入口函数；
  2. 在文档中可追踪；
  3. 不把跨层实现细节固化为稳定 API。

## 源码路径与 Clojure 命名空间

- **`mcmod`**：`mcmod/src/main/clojure/cn/li/mcmod/...` → 命名空间前缀 **`cn.li.mcmod.*`**
- **`ac`**：`ac/src/main/clojure/cn/li/ac/...` → **`cn.li.ac.*`**
- **`forge-1.20.1`**：`forge-1.20.1/src/main/clojure/cn/li/forge1201/...` → **`cn.li.forge1201.*`**

资源与注册用 id 仍以根目录 **`gradle.properties`** 的 `mod_id`（如 `my_mod`）、**`assets/my_mod/`**、`data/my_mod/` 为准。

## 数据终端（`ac/terminal`）

函数式拆分，服务端与客户端命名空间分离：

| 路径 | 命名空间前缀 | 说明 |
|------|----------------|------|
| `ac/.../terminal/model.clj` 等 | `cn.li.ac.terminal.*` | 会话状态、`catalog`、网络、`messages` |
| `ac/.../terminal/client/` | `cn.li.ac.terminal.client.*` | Shell、runtime、app launcher、`client.actions` 侧载入口 |

维护说明见 [TERMINAL_SYSTEM_MAINTENANCE.md](../04-systems/TERMINAL_SYSTEM_MAINTENANCE.md)。勿再引入 `registry` 包装层、`client/bridge` 或 manifest/SPI 式应用注册。

## 无线能源（`ac/wireless`）

单一函数式运行时，对外经 `cn.li.ac.wireless.api`：

| 路径 | 命名空间 | 说明 |
|------|----------|------|
| `wireless/api.clj` | `cn.li.ac.wireless.api` | 查询与拓扑命令的唯一对外入口 |
| `wireless/service/commands.clj`、`queries.clj` | `service.*` | 写/读编排（模块内） |
| `wireless/domain/` | `domain.topology`、`domain.transfer` | 纯 world-state 与能量计划 |
| `wireless/data/world.clj` | `data.world` | **仅**生命周期与 SavedData |
| `wireless/data/world_registry.clj` | `data.world-registry` | `transact!` 可变提交 |
| `wireless/runtime/effects.clj` | `runtime.effects` | capability 能量 IO |
| `ac/block/wireless_*` | `cn.li.ac.block.wireless-*` | 方块 tick/事件；经 `wireless.api` 改拓扑 |

契约与排障：[WIRELESS_REFACTOR_CONTRACTS.md](../05-wireless/WIRELESS_REFACTOR_CONTRACTS.md)、[WIRELESS_SYSTEM_MAINTENANCE.md](../04-systems/WIRELESS_SYSTEM_MAINTENANCE.md)。已删除 `topology-service`、`query-service`、`topology-index` 等并行层。

## 新增内容应落在何处

1. 在 **`mcmod`** 扩展 DSL / 元数据 / 协议（若涉及新抽象）。
2. 在 **`ac`** 实现方块、物品、业务逻辑，使用 `defblock` / `defitem` 等写入 `mcmod` registry。
3. 仅在需要 Loader 专用胶水时改 **`forge-1.20.1`**（或启用后的 Fabric 模块），并保持适配层薄。
4. 新终端应用：改 `terminal/catalog.clj` + `terminal/client/apps/*` + `client/apps.clj` 的 `launchers`。
