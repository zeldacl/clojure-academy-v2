# GUI 架构与重构

> 状态标签：**现行**（架构文档）

本文档合并自 GUI 架构重构报告、实现总结、平台 GUI 实现说明与槽位管理重构。**当前默认目标为 Forge 1.20.1**；Fabric 适配代码在 `platform-src/loader/fabric/` 中，维护级别为 minimal maintenance，至少保证 compile 与边界门禁。

---

## 重构概览

1. **屏幕工厂**：具体 GUI screen/container 组装集中在 **`ac`** 的业务 `gui.clj` 中，平台层只注册并调用 manifest/metadata 暴露的入口。
2. **槽位与快速移动**：通用规则与 schema 在 **`mcmod`**（如 `cn.li.mcmod.gui.slot-schema`）；各 GUI 在 **`ac`** 的 `gui.clj` 中组合 slot layout、quick-move 与 validator，平台 menu proxy 只执行桥接。
3. **Container 分发与 GUI 元数据**：`cn.li.mcmod.gui.registry` 为 GUI spec/metadata 单一注册表；平台 MenuType/ScreenHandlerType 归 `cn.li.mc1201.runtime.spi.gui-registry` 与 Forge/Fabric adapter 管理，避免 `mcmod` 和平台层双缓存。
4. **注册去游戏化**：GUI 声明、manifest 与 metadata 驱动 shared dispatcher；Forge/Fabric 只保留 loader API 差异和注册胶水。
5. **能量过滤**：与 **`cn.li.ac.energy.operations`** 对齐，供槽位 `filter` / validator 使用。
6. **TechUI 组装统一**：简单块 GUI 的 XML page、wireless tab、tab sync、InfoArea 与 screen container 包装统一通过 **`cn.li.ac.gui.tech-ui-common`**，业务 GUI 只注入 widget 绑定和 InfoArea 内容。
7. **Terminal/simple screen**：数据终端属于 client simple screen，不进入 block menu 生命周期。AC 在 client init 通过 `cn.li.ac.terminal.client.actions/install-ui-hooks!` 委托 `client.shell/install-ui-hooks!`，向 `mcmod.platform.ui` 注册 `:ac/terminal-gui` 工厂；打开时用 `mcmod.client.platform-bridge/open-screen!` `:ac/terminal`。Forge 复用通用 `cgui_screen_bridge` 与 `mc1201.gui.cgui.runtime`，**无**专用 `terminal_screen_bridge` 或 `mcmod.platform.terminal-ui`。详见 [TERMINAL_SYSTEM_MAINTENANCE.md](../04-systems/TERMINAL_SYSTEM_MAINTENANCE.md)。

---

## 核心文件（按层）

### mcmod（协议与 DSL）

- **`cn.li.mcmod.gui.registry`**：GUI spec 注册、metadata 查询与 screen factory 表。
- **`cn.li.mcmod.gui.spec`**：`create-block-gui-spec` / `register-block-gui!` 等纯 map 规格构造。
- **`cn.li.mcmod.gui.handler`**：`IGuiHandler` 与 `register-gui-handler` multimethod。
- **`cn.li.mcmod.gui.adapter.platform-registry`**：AC 注入的平台容器回调（tick/sync/slot/quick-move）。
- **`cn.li.mcmod.gui.container-state` / `tabbed-gui`**：显式 owner 的容器索引与 tab 状态。
- **`cn.li.mcmod.gui.cgui-core` / `components` / `events` / `xml-parser`**：widget、事件与 XML runtime；不负责 Minecraft 渲染或 loader 注册。
- **`cn.li.mcmod.gui.slot-schema` / `slot-registry`**：槽位 schema、slot layout 与快速移动配置；不直接访问 Minecraft/Forge/Fabric API。

### ac（内容与 Wireless）

- **`cn.li.ac.block.wireless-node.gui`** / **`cn.li.ac.block.wireless-matrix.gui`** 等：块 GUI 定义（`cn.li.mcmod.gui.spec/register-block-gui!`）、容器与 quick-move。
- **`cn.li.ac.gui.platform-adapter`**：仅在 `content_loader` 中调用 `install-into-mcmod!`，向 `platform-registry` 注册 AC 容器回调；不再 re-export mcmod registry API。
- **`cn.li.ac.gui.tech-ui-common`**：TechUI 标准 screen builder；简单块 GUI 不直接读取 XML、不直接调用 tab sync 或 `cgui-screen/create-cgui-screen-container`。
- **`cn.li.ac.wireless.gui.tab`**：TechUI 的 wireless tab 统一入口，调用方必须传 `:role :node/:generator/:receiver`，不再保留 `:mode` fallback 或角色 wrapper。
- **`cn.li.ac.block.gui.sync`**：AC block GUI sync helper；schema-backed GUI 通过它构造 container sync 生命周期。
- **`cn.li.ac.core`**：`init` 中注册 slot validators、注入 GUI 平台回调；client init 通过 `requiring-resolve` 安装 Terminal UI hook（`terminal.client.actions`）并加载 client renderer。
- **`cn.li.ac.terminal.*`**（服务端）与 **`cn.li.ac.terminal.client.*`**（客户端）：数据终端 catalog、会话状态、网络 handler、shell 与 app launcher；见 [TERMINAL_SYSTEM_MAINTENANCE.md](../04-systems/TERMINAL_SYSTEM_MAINTENANCE.md)。

### forge target（适配）

- **`cn.li.forge1201.gui.*`**：菜单桥接、网络、注册。

---

## 平台层（Forge 1.20.1）

- **Bridge**：容器包装；quickMove 调用 `gui/execute-quick-move-forge` → Clojure 容器逻辑。Forge/Fabric provider 通过 `mc1201.gui.provider.dispatcher/create-menu-from-provider!` 共享菜单创建流程，loader 差异由各 loader component 以 opts 传入共享 `mc1201.gui.menu.proxy/menu-proxy-opts`。
- **Registry**：MenuType 注册位于平台 adapter 自有缓存后，通过 `cn.li.mc1201.runtime.spi.gui-registry` 暴露给 shared GUI 打开与 screen 注册路径。
- **Screen**：客户端注册；创建屏幕时调用 `ac` 侧 screen-factory。
- **网络**：Forge 通道与包；业务分发在 `mcmod`/`ac` 协议与 handler 中完成。
- **Terminal host**：与 block menu 无关。Widget 由 `shell/create-terminal-gui` 经 `platform.ui` 工厂 `:ac/terminal-gui` 提供；屏幕 host 走通用 CGui screen bridge，**禁止**恢复 `forge1201/client/terminal_screen_bridge` 或 `mcmod.platform.terminal-ui`。

## Java Shim 与 CGui Runtime

- **`CMenuBridge`**：Java shim，只承接 Minecraft `AbstractContainerMenu` 必须覆盖的方法，并将 removed/broadcast/click/quick-move 回调交给 shared Clojure proxy。
- **`CGuiContainerScreen`**：Java shim，只负责 Minecraft screen 基类对接；实际 widget frame/render/input/dispose 走 `mc1201.gui.cgui.runtime`、`renderer`、`input`、`traversal` 与 `assets`。
- 修改 shim 时必须同步跑 Forge/Fabric compile 或对应 baseline；不要把业务规则塞回 Java shim。

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
