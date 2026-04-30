# GUI DSL 使用与实现

> 状态标签：**现行**（规范文档）

本文档合并自 GUI DSL 使用指南与实现总结。**默认构建为 Forge 1.20.1**；Fabric 子工程存在但未纳入根 `settings.gradle`。能量相关使用 **`cn.li.ac.energy.operations`**。

**架构**：`defgui` 元数据在 **`cn.li.mcmod.gui.dsl`**；具体 Wireless 等 GUI 在 **`ac`**；**`cn.li.ac.core/init`** 在加载 **`content-ns/load-all!`** 之后注册 screen factory。模块边界与启动顺序见 **`docs/02-architecture/Runtime_And_DSL_CN.md`**。

---

## 概述

本项目使用 **Clojure GUI DSL** 声明式定义 Minecraft Mod GUI，包含 DSL 核心、渲染抽象、容器管理与网络抽象。

---

## 核心模块

### 1. `cn.li.mcmod.gui.dsl` — DSL 核心

- **defgui**：声明式 GUI 定义（title、width、height、slots、buttons、labels）。
- **GuiSpec / SlotSpec / ButtonSpec / LabelSpec**：数据结构。
- **GuiInstance**：运行时实例；gui-registry 管理注册。
- **辅助**：slot-change-handler、clear-slot-handler、processing-handler 等。
- **defgui-from-xml**：从 XML 布局加载并转换为 DSL（与 wireless Node/Matrix GUI 共用）。

#### 1.1 Wireless（平台可注册 GUI）的扩展字段

当 `defgui` 提供 `:gui-id`（int）时，该 GUI 会进入“平台可见”注册集合，Forge/Fabric 适配层会通过元数据自动完成：
- **MenuType/ScreenHandlerType 注册**（根据 `:registry-name`）
- **Screen 注册**（根据 `:screen-factory-fn-kw`）
- **容器 tick/sync 分发**（根据 `:container-predicate` / `:tick-fn` / `:sync-*`）

常用字段：
- `:gui-id`：稳定的整数 GUI id（用于打开 GUI / 网络 payload 中的 `:gui-id`）
- `:registry-name`：注册名（snake_case）
- `:display-name`：显示名（用于 debug/标题等）
- `:gui-type`：类型关键字（如 `:node` / `:matrix` / `:solar`）
- `:screen-factory-fn-kw`：平台侧 screen-factory 关键字（如 `:create-node-screen`）
- `:slot-layout`：用于 quick-move/slot manager 的布局与范围
- `:container-fn`：`(fn [tile player] -> container)`（服务端容器创建）
- `:screen-fn`：`(fn [container minecraft-container player] -> screen)`（客户端 screen 创建）
- `:payload-sync-apply-fn`：`(fn [payload] ...)`（客户端应用网络 payload，可选）

实际项目中，Wireless 等 GUI 的 `defgui` / `defgui-with-lazy-fns` 声明在 **内容层**，例如 `ac/src/main/clojure/cn/li/ac/block/wireless_node/gui.clj`、`wireless_matrix/gui.clj` 等；通用 DSL 在 `mcmod/src/main/clojure/cn/li/mcmod/gui/dsl.clj`。新增 GUI 通常以 DSL + 元数据为主，Forge 侧只做注册与桥接。

**Slot**：`{:index :x :y :filter :on-change}`；**Button**：`{:id :x :y :width :height :text :on-click}`；**Label**：`{:x :y :text :color}`。

### 2. `cn.li.mcmod.gui.renderer` — 渲染抽象

- **IRenderContext** 与 multimethod**：render-gui-background、render-gui-slots、render-gui-buttons、render-gui-labels、render-gui-tooltips；find-clicked-button、find-clicked-slot。
- 平台实现通过实现对应 multimethod 完成。

### 3. `cn.li.mcmod.gui.container` — 容器管理

- 容器创建/注册/获取；槽位 get/set/clear；handle-button-click。
- create-platform-container、open-gui-container 等 multimethod。

### 4. `cn.li.mcmod.gui.network` — 网络抽象

- Packet 与 button-click / slot-change / open-gui 等包；send-to-server / send-to-client；register-packet-handlers。

---

## 使用示例

```clojure
(dsl/defgui my-gui
  :title "My GUI"
  :width 176
  :height 166
  :slots [{:index 0 :x 80 :y 35 :filter energy/can-place-item?}]
  :buttons [{:id 0 :x 120 :y 30 :text "OK" :on-click #(println "OK")}]
  :labels [{:x 8 :y 6 :text "Title"}])

(dsl/get-gui "my-gui")
(dsl/create-gui-instance my-gui player world pos)
```

插槽过滤与能量物品支持可依赖 `cn.li.ac.energy.operations`（如 `is-energy-item-supported?`，以实际导出为准）。

---

## 与 Wireless GUI 的关系

- Node/Matrix GUI 使用同一套 DSL 与 **xml_parser**（XML → GuiSpec）；布局路径为 `assets/my_mod/guis/`、`my_mod:guis/rework/` 等。
- 详见 `05-wireless/Node_GUI.md`、`05-wireless/Matrix_GUI.md`。

**更新（当前架构）**：
- Wireless GUI 的“元数据源”以 `cn.li.mcmod.gui.dsl` 的 registry 与 `cn.li.mcmod.gui.metadata` 为主；具体各 GUI 定义在 `ac` 对应 `gui.clj` 中。
