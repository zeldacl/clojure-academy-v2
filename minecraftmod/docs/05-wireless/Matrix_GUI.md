# Wireless Matrix GUI

> 状态标签：**现行**（维护入口）

本文档合并自：原始实现分析、移植实现报告、迁移总结。资源路径以 **`ac/src/main/resources/assets/my_mod/guis/`**、`my_mod:guis/` 为准；与 Node GUI 共用 `cn.li.mcmod.gui` 下 XML 解析器与 DSL。

---

## 一、原始实现分析（参考）

- **ContainerMatrix**：4 插槽——3×Constraint Plate（三角形）+ 1×Matrix Core（中心）；快速移动规则按物品类型分配到对应槽位。
- **GuiMatrix**：库存页 + 信息页；根据网络是否已初始化动态切换：已初始化显示 SSID/密码（可编辑）、容量直方图等；未初始化则显示初始化表单（SSID、密码、INIT 按钮）或非所有者提示。
- 网络消息：MSG_GATHER_INFO、MSG_INIT、MSG_CHANGE_SSID、MSG_CHANGE_PASSWORD；权限校验（所有者可编辑）。

---

## 二、Clojure 移植架构

- **复用**：`mcmod` 中 `cn.li.mcmod.gui.xml-parser`、`cn.li.mcmod.gui.dsl` 的 `defgui-from-xml`；直方图/属性/按钮创建模式与 Node GUI 一致。
- **Matrix 特有**：实现位于 **`ac`**（如 `cn.li.ac.block.wireless-matrix.*` 下的 gui / 网络处理）；历史文件名 `matrix_gui_xml.clj` 等若已合并，以仓库内实际路径为准。
- **布局**：XML 定义 4 插槽、容量直方图、属性与网络信息/初始化表单；路径以当前项目 `my_mod` 资源为准，不写 academy 目录。

---

## 三、迁移总结

- 已完成：分析、XML 布局、GUI 逻辑（含动态重建）、网络处理与权限。
- 与 Node GUI 的差异：Matrix 为事件驱动拉取（GATHER_INFO 等），Node 为定时轮询连接状态。
- 文档内引用统一为本文档（Matrix_GUI.md）。
