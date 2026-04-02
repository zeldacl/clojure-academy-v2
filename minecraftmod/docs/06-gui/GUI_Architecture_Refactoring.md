# GUI 架构与重构

本文档合并自 GUI 架构重构报告、实现总结、平台 GUI 实现说明与槽位管理重构。**当前默认目标为 Forge 1.20.1**；Fabric 适配代码在 `fabric-1.20.1/` 中，但根工程默认不构建该子模块（见 `settings.gradle`）。

---

## 重构概览

1. **屏幕工厂**：Node/Matrix 等屏幕创建集中在 **`ac`**（如 `cn.li.ac.wireless.gui.screen-factory`、`cn.li.ac.wireless.shared.screen-factory`），Forge 仅注册并调用。
2. **槽位与快速移动**：通用规则与 schema 在 **`mcmod`**（如 `cn.li.mcmod.gui.slot-schema`）；各 GUI 在 **`ac`** 的 `gui.clj` 中组合 `build-quick-move-config` 与 `quick-move-stack`，Forge `menu_bridge` 委托 `cn.li.mcmod.gui.adapter` 上的 quick-move 多方法。
3. **Container 分发与 GUI 元数据**：通过 `cn.li.mcmod.gui.metadata` 与容器 dispatcher（`ac` 侧 wireless/gui）配合，避免在平台层硬编码具体方块类型。
4. **注册去游戏化**：MenuType 等由元数据与 DSL 驱动（`cn.li.forge1201` 注册路径）。
5. **能量过滤**：与 **`cn.li.ac.energy.operations`** 对齐，供槽位 `filter` / validator 使用。

---

## 核心文件（按层）

### mcmod（协议与 DSL）

- **`cn.li.mcmod.gui.dsl`**：`defgui`、`defgui-with-lazy-fns`、GUI registry。
- **`cn.li.mcmod.gui.metadata`**：GUI 元数据查询。
- **`cn.li.mcmod.gui.slot-schema`**：快速移动规则编译与配置。
- **`cn.li.mcmod.gui.adapter`**：平台 multimethod 入口（如 `execute-quick-move-forge`）。

### ac（内容与 Wireless）

- **`cn.li.ac.block.wireless-node.gui`** / **`cn.li.ac.block.wireless-matrix.gui`** 等：具体 `defgui`、容器与 quick-move。
- **`cn.li.ac.wireless.gui.screen-factory`**：屏幕创建（与历史文档中的独立 `screen_factory.clj` 路径对应，现位于 `ac` 下上述命名空间）。
- **`cn.li.ac.core`**：`init` 中注册 slot validators、注入 GUI 平台回调等。

### forge-1.20.1（适配）

- **`cn.li.forge1201.gui.*`**：菜单桥接、网络、注册。

---

## 平台层（Forge 1.20.1）

- **Bridge**：容器包装；quickMove 调用 `gui/execute-quick-move-forge` → Clojure 容器逻辑。
- **Registry**：MenuType 注册，元数据驱动打开 GUI。
- **Screen**：客户端注册；创建屏幕时调用 `ac` 侧 screen-factory。
- **网络**：Forge 通道与包；业务分发在 `mcmod`/`ac` 协议与 handler 中完成。

---

## 平台 API 差异摘要（Fabric 参考）

若将来重新启用 Fabric 子工程，典型差异如下：

| 概念     | Forge 1.20.1              | Fabric 1.20.1           |
|----------|---------------------------|-------------------------|
| 容器     | AbstractContainerMenu     | ScreenHandler           |
| 类型注册 | MenuType                  | ScreenHandlerType       |
| 打开 GUI | NetworkHooks.openScreen   | openHandledScreen 等    |

---

## 槽位管理要点

- 快速移动策略由 **`cn.li.mcmod.gui.slot-schema`** + 各 **`ac/.../gui.clj`** 中的配置共同给出；平台执行“移动 + 标记脏”。
- 能量物品等过滤逻辑使用 **`cn.li.ac.energy.operations`**，与 GUI 槽位校验、slot validator 一致。
