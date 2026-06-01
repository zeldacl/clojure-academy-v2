# GUI DSL（现行）

> **历史说明**：旧 `cn.li.mcmod.gui.dsl` / `defgui` / XML→DSL 转换路径已删除。现行架构见 [GUI_Architecture_Refactoring.md](GUI_Architecture_Refactoring.md)。

**架构**：块 GUI 在 **`ac`** 各 `gui.clj` 中通过 **`cn.li.mcmod.gui.spec/register-block-gui!`** 注册；**`cn.li.ac.core/content-loader`** 在 `content-ns/load-all!` 之后向 **`cn.li.mcmod.gui.registry`** 注册 screen factory。模块边界见 **`docs/02-architecture/Runtime_And_DSL_CN.md`**。

## mcmod 入口

| 命名空间 | 职责 |
|----------|------|
| `cn.li.mcmod.gui.spec` | 纯 map GUI 规格、`register-block-gui!` |
| `cn.li.mcmod.gui.registry` | 注册表、metadata 查询、screen factory |
| `cn.li.mcmod.gui.handler` | 平台 `register-gui-handler` multimethod |
| `cn.li.mcmod.gui.components` / `events` | CGui 组件与事件 |
| `cn.li.mcmod.gui.xml-parser` | `read-xml` / `get-widget` runtime |

## ac 内容

Wireless / TechUI 等业务 GUI 定义在 `ac` 对应 `gui.clj`；平台仅通过 `cn.li.ac.gui.platform-adapter/install-into-mcmod!` 注入回调，不直接依赖 `cn.li.ac.*` 实现细节。
