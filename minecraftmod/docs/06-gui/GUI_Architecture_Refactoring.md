# GUI 架构与重构

本文档合并自 GUI 架构重构报告、实现总结、平台 GUI 实现说明与槽位管理重构。当前支持的平台为 **Forge 1.20.1** 与 **Fabric 1.20.1**（无 Forge 1.16.5）。

---

## 重构概览

1. **屏幕工厂**：将 `create-node-screen` / `create-matrix-screen` 从平台层抽到 core 的 `screen_factory.clj`，平台仅负责注册与调用工厂。
2. **槽位管理**：在 core 的 `slot_manager.clj` 中集中定义槽位布局与快速移动策略，Forge/Fabric 的 quickMove 仅委托给 slot-manager，消除重复游戏逻辑。
3. **Container 分发器 + GUI 元数据**：通过 GUI 元数据与 IContainerOperations 协议分发，平台使用通用容器/桥接类，不再硬编码 Node/Matrix 类型判断。
4. **平台中性命名**：容器、MenuType 等使用通用命名（如 ForgeMenuBridge），由元数据驱动具体 GUI 类型。
5. **注册去游戏化**：方块/物品/GUI 注册通过元数据与 DSL 驱动，平台层无游戏名硬编码。
6. **网络包去游戏化**：包处理按协议分发，不按 GUI 类型写死分支。

---

## 核心文件（Core）

- **screen_factory.clj**：create-node-screen、create-matrix-screen（平台无关）；从 container/handler 取 Clojure 容器后委托 node-gui / matrix-gui。
- **slot_manager.clj**：node/matrix 的 tile/player 槽位范围、get-quick-move-strategy、execute-quick-move-forge / execute-quick-move-fabric。
- **gui_metadata.clj**（若存在）：GUI 配置单一来源，供平台注册与分发使用。

---

## 平台层（Forge 1.20.1 / Fabric 1.20.1）

- **Bridge**：容器/菜单包装器，quickMove 调用 slot-manager；detectAndSendChanges 等平台 API。
- **Registry**：MenuType / ScreenHandlerType 注册，通过元数据打开 Node/Matrix GUI。
- **Screen 实现**：仅做平台注册（ScreenManager / HandledScreens），屏幕创建调用 screen_factory。
- **网络**：通道与包编解码为平台实现；业务逻辑在 core 的 network handler 中按协议分发。

---

## 平台 API 差异摘要

| 概念       | Forge 1.20.1              | Fabric 1.20.1                |
|------------|----------------------------|-------------------------------|
| 容器       | AbstractContainerMenu      | ScreenHandler                 |
| 类型注册   | MenuType                   | ScreenHandlerType             |
| 打开 GUI   | NetworkHooks.openScreen    | ServerPlayerEntity.openHandledScreen |
| 提供者     | MenuProvider               | NamedScreenHandlerFactory     |

---

## 槽位管理要点

- 槽位范围与快速移动策略由 **slot_manager** 统一给出，平台仅执行“移动+标记脏”。
- 能量物品等过滤逻辑使用 **cn.li.energy.operations**，与 GUI 槽位校验一致。
- 详见原 SLOT_MANAGER_REFACTORING 文档中的常量与 get-quick-move-strategy 说明。
