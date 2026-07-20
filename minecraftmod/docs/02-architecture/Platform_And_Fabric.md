# 平台实现与 Fabric 说明

本文档合并自平台实现指南与 Fabric 相关说明。**日常开发与默认构建以 Forge 1.20.1 为主**；`fabric target` 已纳入根 `settings.gradle`，并以 **minimal maintenance** 级别参与 compile 基线验证。

> **架构已完成 DRY 共享安装器迁移（Batches A-F）。**  
> 公共安装逻辑集中于 `mc-1.20.1`；Forge 与 Fabric 各自仅保留平台私有部分。

---

## 设计原则

- 平台代码**不写死游戏内资源 ID**；内容通过 `mcmod` 元数据与 registry 发现。
- **Forge**：Java 入口 → `cn.li.forge1201.*` → `mcmod` / `ac`。
- **Fabric**：Java `ModInitializer` → `cn.li.fabric1201.*`，无历史 `my_mod.*` 混排。
- **共享逻辑**：通过 `cn.li.mc1201.installer` 提供的统一安装器，两套平台均委托同一函数集完成协议安装与 var-root 配置。

---

## 平台结构（当前状态）
```
platform-src/minecraft/version/mc-1201/src/main/clojure/cn/li/mc1201/
├── installer.clj                   # 薄门面，转发至 bootstrap/installer_core.clj
├── bootstrap/installer_core.clj    # 全量共享安装逻辑
├── platform_adapter.clj            # PlatformAdapter 协议（Forge/Fabric 各自实现）
├── reflect_util.clj                # 反射工具（class-noinit 等）
├── block/, registry/, runtime/, gui/  # 共享业务工具
└── ...

platform-src/loader/forge/src/main/clojure/cn/li/forge1201/
├── platform/platform/init.clj    # SPI 触发门面（主初始化链调用）
├── platform/platform/init.clj      # Forge PlatformAdapter 实现 + 私有协议 extend
├── integration/imc_dispatch.clj    # Forge IMC 事件分发桥接
└── ...（全为 Forge 私有：entity, gui, events, runtime 等）

platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/
├── platform/platform/init.clj    # SPI 触发门面（主初始化链调用）
├── platform/platform/init.clj      # Fabric PlatformAdapter 实现 + 私有协议 extend
└── ...（全为 Fabric 私有：block, gui, client, datagen 等）
```

### 安装器调用序列

```
Forge:
	install-foundation!
	install-entity-protocols-only!
	install-item-protocols-only!
	install-block-state-protocol-only!
	install-resource-factory-only!
	install-world-fns-only!
	install-be-fns-only!

Fabric:
	install-platform-core!(adapter)
	install-be-fns-only!(fns-map)    # 由 install-be-ops! 包装调用
```

`platform/platform/init.clj` 调用 `installer/install-be-fns-only!` 并传入平台私有 BE 类的 lambda；`extend` 调用保留在各平台本地（类型私有，无法共享）。`platform/platform/init.clj` 仅负责通过 SPI 触发平台安装。

- **资源**：`platform-src/loader/forge/src/main/resources/META-INF/mods.toml` 等；游戏资源也可在 `ac/src/main/resources/assets/<mod_id>/` 维护。

### Fabric 子工程（当前已纳入根构建）

仓库中 `platform-src/loader/fabric/` 内含 `fabric.mod.json`、Java 入口与 Clojure 适配。**已移除历史占位符 stub（`platform/nbt.clj` 等 5 个文件）**，Fabric 平台安装现全量委托共享安装器。当前策略：

- 至少保持 compile 级可用（`verifyFabricBaseline`）。
- 与 Forge 不承诺完全功能对齐；能力差异按当前实现与测试矩阵维护。

---

## mod.clj 要点（Forge）

- 动态方块/物品注册：从 `cn.li.mcmod.protocol.metadata` 等获取 ID 与规格。
- BlockItem 等为需要物品的方块统一创建并注册。
- 不依赖演示用硬编码模块；以元数据为准。

---

## 平台对象协议边界

- **`cn.li.mcmod.platform.item`**（历史文档曾写作 `cn.li.platform.item`）是跨平台 `ItemStack` 抽象。
- 当前平台安装入口统一为 `cn.li.<loader>.platform.bootstrap-entry`，真实协议扩展位于 `cn.li.<loader>.platform.spi-bootstrap`。
- `mcmod` 与 `ac` 通过上述协议访问物品（如 `item-is-empty?`、`item-save-to-nbt` 等），避免在内容层直接依赖 Minecraft 类。

---

## 事件与 GUI

- **事件**：`cn.li.mcmod.block.query` 识别方块并解析处理器（如 `:on-right-click`），`cn.li.mcmod.events.dispatcher` 统一分发。
- **GUI**：DSL 与元数据在 `mcmod`；具体 Wireless 等屏幕工厂与容器逻辑多在 **`ac`**（如 `cn.li.ac.wireless.gui.*`、`cn.li.ac.block.wireless-node.gui`）。Forge 负责 MenuType 注册与桥接类。

---

## Fabric 与 Forge 差异摘要（参考）

当前仓库中 Fabric 已纳入根构建；典型平台差异如下（与 Minecraft 1.20.1 API 形态一致）：

| 概念     | Forge 1.20.1            | Fabric 1.20.1            |
|----------|-------------------------|--------------------------|
| 容器     | AbstractContainerMenu   | ScreenHandler            |
| 类型注册 | MenuType                | ScreenHandlerType        |
| 打开 GUI | NetworkHooks.openScreen | openHandledScreen 等     |

---

## 参考

- 项目总结：`01-overview/Project_Summary_CN.md`
- GUI 架构：`06-gui/GUI_Architecture_Refactoring.md`
- DataGenerator：`04-datagen/DataGenerator.md`
