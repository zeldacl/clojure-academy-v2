# GUI Layering Playbook (Forge/Fabric + mc1201 shared)

本文档描述本仓库 GUI 重构后的最终分层与入口约定。

## 目标

- 让 `mc-1.20.1` 承担 **Minecraft API 相关且 loader-agnostic** 的 GUI 核心。
- 让 `forge-1.20.1` / `fabric-1.20.1` 只保留 loader 生命周期、注册、网络与平台桥接壳。
- 用统一 adapter 入口 `cn.li.mcmod.gui.adapter` 连接平台与业务实现。

## 分层职责

### 1) Shared (`mc-1.20.1/src/main/{clojure,java}/cn/li/mc1201/gui`)

主要包含：

- `cgui_runtime.clj` / `cgui_runtime_impl.clj`：共享 CGUI runtime facade + 实现
- `container_adapter.clj`：共享容器适配
- `menu_bridge_common.clj`：共享菜单行为
- `provider_common.clj`：共享 provider helper
- `registry_common.clj`：共享注册包装逻辑
- `screen_registry.clj`：共享 screen 注册循环
- `CMenuBridge.java`：共享菜单桥 Java 基类
- `CGuiContainerScreen.java`：共享 container screen Java 基类

约束：

- 不得引入 `net.minecraftforge.*` / `net.fabricmc.*`
- client-only 代码通过调用路径控制（仅在 client 初始化/屏幕打开路径触发）

### 2) Forge (`forge-1.20.1/src/main/clojure/cn/li/forge1201/gui`)

保留职责：

- `init.clj`：Forge 生命周期阶段入口
- `registry_impl.clj`：`DeferredRegister` / `IForgeMenuType` / `NetworkHooks`
- `network.clj`：Forge 网络通道注册与桥接
- `menu_bridge.clj` / `provider_bridge.clj` / `screen_impl.clj`：平台壳装配共享核心
- `bridge.clj`：Facade（对外桥接入口）

### 3) Fabric (`fabric-1.20.1/src/main/clojure/cn/li/fabric1201/gui`)

保留职责：

- `init.clj`：Fabric 生命周期阶段入口
- `registry_impl.clj`：`ScreenHandlerRegistry` + opening data + 客户端重建容器
- `network.clj`：Fabric networking receiver
- `menu_bridge.clj` / `provider_bridge.clj` / `screen_impl.clj`：平台壳装配共享核心
- `bridge.clj`：Facade（对外桥接入口）

备注：

- Fabric 已对齐共享 CGUI 宿主路径（`CGuiContainerScreen + mc1201 cgui runtime`）
- Fabric `menu_bridge` 已补齐 tabbed GUI 的同步与槽位交互约束

## 统一入口

- 平台 GUI 代码统一依赖：`cn.li.mcmod.gui.adapter`
- 避免直接在平台层混用旧 `ac.*` GUI facade 命名空间

## 初始化顺序原则（不要强行统一）

- Forge 与 Fabric 的初始化顺序允许不同：
  - Forge：`DeferredRegister` / Common Setup / Client Setup
  - Fabric：`registry_impl/init!` + client/server network receiver 分离
- 共享化目标是共享核心逻辑，不是抹平 loader 生命周期差异

## 验证建议

至少执行：

- `gradlew.bat :mcmod:compileJava :mcmod:compileClojure`
- `gradlew.bat :forge-1.20.1:compileJava :forge-1.20.1:compileClojure`
- `gradlew.bat :fabric-1.20.1:compileJava :fabric-1.20.1:compileClojure`
- `gradlew.bat verifyArchitectureBoundaries verifyCurrentPlatforms`

并做代表性 GUI 烟测：

- 普通容器 GUI（Forge/Fabric）
- tabbed CGUI 容器（Forge/Fabric）
- 标签切换后的槽位交互行为一致性
