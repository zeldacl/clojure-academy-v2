# Demo Mod 使用说明

> 状态标签：**历史**（演示文档，非主线规范）

**说明**：默认构建为 **Forge 1.20.1**。Fabric 代码在 `fabric-1.20.1/`，但根 `settings.gradle` 默认不 include 该子工程。下文若出现已删除的 `cn.li.mcmod.gui.core` / `cn.li.core` 等命名空间，请以当前仓库中的 **`cn.li.ac.core`**、`cn.li.mcmod.gui.*`、`cn.li.forge1201.*` 为准。

## 功能概述

这个 Demo 演示了一个完整的 Clojure Mod，包含：

1. **一个自定义方块** (`demo_block`)
2. **一个自定义物品** (`demo_item`)
3. **交互式 GUI**：
   - 右键点击 `demo_block` 打开 GUI
   - GUI 包含一个物品槽
   - 一个"销毁"按钮可以清空槽位中的物品

## 当前实现状态

### ✅ 已完成的 Clojure 代码

#### mcmod / ac 层（版本无关逻辑）
- GUI 槽位、校验与打开流程由 **`cn.li.mcmod.gui.*`**（DSL、adapter、metadata）与 **`cn.li.ac.gui.*`**、**`cn.li.ac.core/init`** 协同完成；具体符号名以仓库为准，不再使用已废弃的 `cn.li.mcmod.gui.core`、`cn.li.core` 命名空间。

#### Forge 1.20.1 适配层
- `cn.li.forge1201.events`：handle-right-click 等
- `cn.li.forge1201.gui.impl`：on-button-clicked、on-slot-changed、create-menu-title、get-slot-count

#### Fabric 1.20.1 适配层（可选）
- 子工程存在时需单独启用 Gradle include；API 与 Forge 侧类似（ScreenHandler 等）。

### ⚠️ 需要 Java 层配合的部分

由于 Forge/Fabric 的 GUI 系统深度依赖 Java API（Menu/Screen/Networking），以下功能需要 Java 代码配合：

1. **Menu/Container 类**：管理槽位、实现 quickMoveStack/quickMove、调用 Clojure 的 on-button-clicked
2. **Screen 类**：渲染 GUI 背景和按钮、发送网络包到服务端
3. **MenuType / ScreenHandlerType 注册**：在客户端注册 Screen
4. **网络通信**：Packet 类、SimpleChannel 等

## Clojure 代码工作流程

1. 玩家右键点击方块 → 平台事件 → Clojure handle-right-click（检查 demo_block）
2. 事件经 `cn.li.mcmod.events.dispatcher` 等到内容处理器 → GUI 校验与 `gui-adapter`/平台打开 GUI
3. 平台 gui.impl/open-gui 返回 gui-id 与 pos → [Java] 打开 GUI
4. 槽位变化 / 按钮点击 → Clojure on-slot-changed、on-destroy-button-clicked 等

## 当前可测试的功能

运行游戏后使用 `/give @p my_mod:demo_item`、`/give @p my_mod:demo_block`，放置 demo_block 并右键点击，控制台会输出相应日志。

## 完整实现 GUI 的选项

- **选项 1**：纯 Clojure（gen-class/proxy）— 学习用
- **选项 2**：Java + Clojure 混合（本框架当前方式）— Java 处理 Menu/Screen/Networking，Clojure 处理业务逻辑
- **选项 3**：使用 GUI DSL（defgui）声明式定义 — 见 `06-gui/GUI_DSL.md`
