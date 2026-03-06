# GUI DSL 使用与实现

本文档合并自 GUI DSL 使用指南与实现总结。当前平台为 Forge 1.20.1、Fabric 1.20.1；能量相关使用 `my-mod.energy.operations`。

---

## 概述

本项目使用 **Clojure GUI DSL** 声明式定义 Minecraft Mod GUI，包含 DSL 核心、渲染抽象、容器管理与网络抽象。

---

## 核心模块

### 1. `my-mod.gui.dsl` — DSL 核心

- **defgui**：声明式 GUI 定义（title、width、height、slots、buttons、labels）。
- **GuiSpec / SlotSpec / ButtonSpec / LabelSpec**：数据结构。
- **GuiInstance**：运行时实例；gui-registry 管理注册。
- **辅助**：slot-change-handler、clear-slot-handler、processing-handler 等。
- **defgui-from-xml**：从 XML 布局加载并转换为 DSL（与 wireless Node/Matrix GUI 共用）。

**Slot**：`{:index :x :y :filter :on-change}`；**Button**：`{:id :x :y :width :height :text :on-click}`；**Label**：`{:x :y :text :color}`。

### 2. `my-mod.gui.renderer` — 渲染抽象

- **IRenderContext** 与 multimethod**：render-gui-background、render-gui-slots、render-gui-buttons、render-gui-labels、render-gui-tooltips；find-clicked-button、find-clicked-slot。
- 平台实现通过实现对应 multimethod 完成。

### 3. `my-mod.gui.container` — 容器管理

- 容器创建/注册/获取；槽位 get/set/clear；handle-button-click。
- create-platform-container、open-gui-container 等 multimethod。

### 4. `my-mod.gui.network` — 网络抽象

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

插槽过滤与能量物品支持可依赖 `my-mod.energy.operations`（如 `is-energy-item-supported?`）。

---

## 与 Wireless GUI 的关系

- Node/Matrix GUI 使用同一套 DSL 与 **xml_parser**（XML → GuiSpec）；布局路径为 `assets/my_mod/guis/`、`my_mod:guis/rework/` 等。
- 详见 `05-wireless/Node_GUI.md`、`05-wireless/Matrix_GUI.md`。
