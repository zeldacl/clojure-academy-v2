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
| **01-overview** | 项目总览、总结与迁移 | [Project_Summary_CN.md](01-overview/Project_Summary_CN.md) |
| **02-architecture** | 架构与平台 | [**Runtime_And_DSL_CN.md**](02-architecture/Runtime_And_DSL_CN.md)（启动链路与 DSL 总览）、[BlockState_Architecture.md](02-architecture/BlockState_Architecture.md)、[Platform_And_Fabric.md](02-architecture/Platform_And_Fabric.md) |
| **03-dsl** | DSL 说明 | [BLOCK_DSL_GUIDE_CN.md](03-dsl/BLOCK_DSL_GUIDE_CN.md)、[ITEM_DSL_GUIDE_CN.md](03-dsl/ITEM_DSL_GUIDE_CN.md)、[NBT_DSL_GUIDE.md](03-dsl/NBT_DSL_GUIDE.md)、[TILE_DSL_GUIDE_CN.md](03-dsl/TILE_DSL_GUIDE_CN.md) |
| **04-datagen** | 数据生成 | [DataGenerator.md](04-datagen/DataGenerator.md) |
| **05-wireless** | 无线系统与 GUI | [WIRELESS_IMPLEMENTATION_PROGRESS.md](05-wireless/WIRELESS_IMPLEMENTATION_PROGRESS.md)、[Node_GUI.md](05-wireless/Node_GUI.md)、[Matrix_GUI.md](05-wireless/Matrix_GUI.md)、[Wireless_GUI_Status.md](05-wireless/Wireless_GUI_Status.md)、[WIRELESS_SYSTEM_TODO.md](05-wireless/WIRELESS_SYSTEM_TODO.md) |
| **06-gui** | GUI 通用 | [GUI_DSL.md](06-gui/GUI_DSL.md)、[GUI_Architecture_Refactoring.md](06-gui/GUI_Architecture_Refactoring.md)、[CGUI_Migration_Report.md](06-gui/CGUI_Migration_Report.md)、[GUI_DEMO_CN.md](06-gui/GUI_DEMO_CN.md) |

## 快速查找

- **构建与运行**：`01-overview/Project_Summary_CN.md`
- **DSL 与运行时怎么接起来**：`02-architecture/Runtime_And_DSL_CN.md`
- **BlockState 与 DataProvider**：`02-architecture/BlockState_Architecture.md`、`04-datagen/DataGenerator.md`
- **平台与 Fabric（可选子工程）**：`02-architecture/Platform_And_Fabric.md`
- **Wireless Node/Matrix GUI**：`05-wireless/Node_GUI.md`、`05-wireless/Matrix_GUI.md`、`05-wireless/Wireless_GUI_Status.md`
- **GUI DSL 与架构**：`06-gui/GUI_DSL.md`、`06-gui/GUI_Architecture_Refactoring.md`
