# Presentation Runtime Next 断代重构计划（指针，已归档）

**正文已迁移**：[docs/06-gui/PRESENTATION_V3.md](../06-gui/PRESENTATION_V3.md)

本文档描述的是 Presentation Runtime **v2**（`host_v2.clj`/`paint_v2.clj`/
`runtime_v2.clj`、7 个生产 artifact、"Java 只允许定义数据格式和契约"的铁律）。
这套架构已在 Presentation Runtime v3 重构中被完整替换：

- v2 的三个 `*_v2.clj` 文件已删除，`presentation-core` 现在是
  `artifact.clj`/`nodetable.clj`/`runtime.clj`（Clojure 控制平面）+
  `core/engine/*.java`（Java 布局/绘制/命中/记忆化引擎）。
- "Java 只允许定义数据格式和契约"这条铁律**已被推翻**：引擎档
  （`presentation-core/src/main/java/cn/li/presentation/core/engine/`）
  现在允许可变数组和真实循环，由 `verifyPresentationEngineIsolation` 门禁
  单独约束（禁 MC/Loader 依赖、禁并发、禁静态可变状态），而不是禁止逻辑本身。
- 生产 artifact 从 7 个增加到 15 个，`.ui.edn` 源 schema 从 1 升到 2，编译产物
  schema 从 4 升到 5（`:pui4` → `:pui5`）。

**不要再引用本文档描述的实现细节**——它们已经和代码不符。需要当前架构信息时，
去读 [PRESENTATION_V3.md](../06-gui/PRESENTATION_V3.md) 和
[GUI_Architecture_Refactoring.md](../06-gui/GUI_Architecture_Refactoring.md)。
本文档保留仅作历史记录（v2 重构的实施约束与审计基线），不再维护。
