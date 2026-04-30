# 文档索引

本目录按主题分类存放项目文档。内容已与当前 Gradle 多模块布局对齐（见下文「当前构建」）。

## 当前构建（以仓库为准）

- **根 `settings.gradle` 包含**：`api`、`mcmod`、`ac`、`forge-1.20.1`。
- **`fabric-1.20.1`**：目录中保留 Fabric 适配代码，但 **`include 'fabric-1.20.1'` 在根 `settings.gradle` 中默认注释**，不参与根工程构建；需 Fabric 时请取消注释并单独验证。
- **命名空间**：Clojure 以 `cn.li.mcmod.*`（协议与 DSL）、`cn.li.ac.*`（内容与无线等业务）、`cn.li.forge1201.*`（Forge 适配）为主；资源与 mod id 仍以 `gradle.properties` 的 `mod_id`（如 `my_mod`）、`assets/my_mod/` 为准。
- **能量逻辑**：`cn.li.ac.energy.operations`（非 `cn.li.energy.operations`）。

## 目录结构

| 目录 | 用途 | 入口文档 |
|------|------|----------|
| **01-overview** | 总览、布局、构建速查 | [Project_Summary_CN.md](01-overview/Project_Summary_CN.md)、[PROJECT_LAYOUT.md](01-overview/PROJECT_LAYOUT.md)、[GETTING_STARTED.md](01-overview/GETTING_STARTED.md) |
| **02-architecture** | 架构与平台 | [**Runtime_And_DSL_CN.md**](02-architecture/Runtime_And_DSL_CN.md)、[BlockState_Architecture.md](02-architecture/BlockState_Architecture.md)、[Platform_And_Fabric.md](02-architecture/Platform_And_Fabric.md)、[CLIENT_SERVER_SEPARATION.md](02-architecture/CLIENT_SERVER_SEPARATION.md) |
| **03-dsl** | DSL 说明 | [BLOCK_DSL_GUIDE_CN.md](03-dsl/BLOCK_DSL_GUIDE_CN.md)、[ITEM_DSL_GUIDE_CN.md](03-dsl/ITEM_DSL_GUIDE_CN.md)、[NBT_DSL_GUIDE.md](03-dsl/NBT_DSL_GUIDE.md)、[TILE_DSL_GUIDE_CN.md](03-dsl/TILE_DSL_GUIDE_CN.md) |
| **04-datagen** | 数据生成 | [DataGenerator.md](04-datagen/DataGenerator.md) |
| **04-systems** | 系统维护手册（架构/边界/排障） | [SYSTEMS_MAINTENANCE_INDEX.md](04-systems/SYSTEMS_MAINTENANCE_INDEX.md) |
| **05-wireless** | 无线系统与 GUI（含现行/历史标签） | [README_STATUS.md](05-wireless/README_STATUS.md)、[Node_GUI.md](05-wireless/Node_GUI.md)、[Matrix_GUI.md](05-wireless/Matrix_GUI.md)、[Wireless_GUI_Status.md](05-wireless/Wireless_GUI_Status.md) |
| **06-gui** | GUI 通用（含现行/历史标签） | [README_STATUS.md](06-gui/README_STATUS.md)、[GUI_DSL.md](06-gui/GUI_DSL.md)、[GUI_Architecture_Refactoring.md](06-gui/GUI_Architecture_Refactoring.md) |
| **07-ability** | 能力系统（迁移追踪） | [README_STATUS.md](07-ability/README_STATUS.md)、[ABILITY_FEATURE_MATRIX.md](07-ability/ABILITY_FEATURE_MATRIX.md) |
| **testing** | 测试范围说明 | [IMPLEMENTATION_SCOPE.md](testing/IMPLEMENTATION_SCOPE.md) |
| **dev** | Agent / 工具链与构建验证手册 | [AGENT_AND_TOOLING.md](dev/AGENT_AND_TOOLING.md)、[BUILD_AND_VERIFY_PLAYBOOK.md](dev/BUILD_AND_VERIFY_PLAYBOOK.md) |
| **98-archive** | 历史迁移报告、旧版架构/构建说明（**非现行权威**） | [98-archive/README.md](98-archive/README.md) |

## 快速查找

- **构建与运行（命令表）**：[GETTING_STARTED.md](01-overview/GETTING_STARTED.md)；总览仍见 [Project_Summary_CN.md](01-overview/Project_Summary_CN.md)
- **构建验证与排障总入口**：[dev/BUILD_AND_VERIFY_PLAYBOOK.md](dev/BUILD_AND_VERIFY_PLAYBOOK.md)
- **工程路径与命名空间**：[PROJECT_LAYOUT.md](01-overview/PROJECT_LAYOUT.md)
- **系统维护索引（全量核心系统）**：[04-systems/SYSTEMS_MAINTENANCE_INDEX.md](04-systems/SYSTEMS_MAINTENANCE_INDEX.md)
- **DSL 与运行时怎么接起来**：[Runtime_And_DSL_CN.md](02-architecture/Runtime_And_DSL_CN.md)
- **客户端/服务端代码分离**：[CLIENT_SERVER_SEPARATION.md](02-architecture/CLIENT_SERVER_SEPARATION.md)
- **BlockState 与 DataProvider**：[BlockState_Architecture.md](02-architecture/BlockState_Architecture.md)、[DataGenerator.md](04-datagen/DataGenerator.md)
- **平台与 Fabric（可选子工程）**：[Platform_And_Fabric.md](02-architecture/Platform_And_Fabric.md)
- **Wireless Node/Matrix GUI**：[Node_GUI.md](05-wireless/Node_GUI.md)、[Matrix_GUI.md](05-wireless/Matrix_GUI.md)、[Wireless_GUI_Status.md](05-wireless/Wireless_GUI_Status.md)
- **GUI DSL 与架构**：[GUI_DSL.md](06-gui/GUI_DSL.md)、[GUI_Architecture_Refactoring.md](06-gui/GUI_Architecture_Refactoring.md)
- **现行/历史标签索引**：[05-wireless/README_STATUS.md](05-wireless/README_STATUS.md)、[06-gui/README_STATUS.md](06-gui/README_STATUS.md)、[07-ability/README_STATUS.md](07-ability/README_STATUS.md)
- **历史文档**：[98-archive/README.md](98-archive/README.md)
