# Wireless Node GUI

> 状态标签：**现行**（维护入口）

本文档合并自：原始实现分析、移植实现报告、迁移总结。当前项目资源路径以 `ac/src/main/resources/assets/my_mod/guis/`、`my_mod:guis/rework/` 为准；能量相关逻辑见 **`cn.li.ac.energy.operations`**。

---

## 一、原始实现分析（参考）

### 核心组件

- **ContainerNode**：服务端容器，2 个能量物品插槽（输入/输出），快速移动规则由 IFItemManager 判定；Clojure 对应 `wireless/gui/node_container.clj`。
- **GuiNode**：客户端 GUI，多页面（库存 / 无线 / 信息），动画区域（连接状态）、直方图（能量/容量）、属性（范围、所有者、节点名、密码），网络轮询（MSG_QUERY_LINK 等）。
- 能量物品支持：由 **energy/operations**（原 energy/stub）提供 `is-energy-item-supported?` 等，与插槽验证一致。

### 设计要点

- 插槽仅接受能量物品；快速移动规则：背包 ↔ 电池槽。
- 动画：连接/未连接两态，多帧；直方图与属性从 tile 与网络消息同步。

---

## 二、Clojure 移植架构

- **定义层**：XML 布局（若使用）→ `gui/xml_parser.clj`（XML → AST → DSL Spec）→ `gui/dsl.clj`（`defgui-from-xml`）。
- **运行时**：`wireless/gui/node_gui_xml.clj` 加载布局、填充直方图/属性/动画，与 CGui 渲染层对接。
- **容器**：`wireless/gui/node_container.clj`，插槽校验与 data 同步（如 `sync-to-client!`）。

当前布局与纹理路径以项目为准，例如 `my_mod:guis/rework/page_wireless.xml`、`my_mod:textures/guis/...`；不再使用 `gui/layouts/` 的旧路径表述，除非兼容层显式支持。

---

## 三、迁移总结

- 已完成：容器结构、插槽验证、数据同步、XML 解析与 DSL、Node GUI 逻辑（布局加载、动画、直方图、属性、轮询）。
- 能量相关：统一使用 `cn.li.ac.energy.operations`（物品支持、充放电等）。
- 文档内相互引用已统一为本文档（Node_GUI.md）。
