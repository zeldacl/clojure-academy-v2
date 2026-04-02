# 平台实现与 Fabric 说明

本文档合并自平台实现指南与 Fabric 相关说明。**日常开发与默认构建以 Forge 1.20.1 为主**；`fabric-1.20.1` 子目录保留适配代码，但根 `settings.gradle` 中 **`include 'fabric-1.20.1'` 默认注释**，根工程不会编译 Fabric 模块，除非手动恢复。

---

## 设计原则

- 平台代码**不写死游戏内资源 ID**；内容通过 `mcmod` 元数据与 registry 发现。
- **Forge**：Java 入口 → `cn.li.forge1201.*` → `mcmod` / `ac`。
- **Fabric**（若启用子工程）：结构类似，入口在 `fabric-1.20.1` 的 Java `ModInitializer` 与 `cn.li.fabric1201.*` / 历史 `my_mod.fabric1201.*` 混排处需以仓库实际代码为准。

---

## 平台结构（Forge 1.20.1，当前启用）

```
forge-1.20.1/
├── src/main/java/cn/li/forge1201/
│   ├── MyMod1201.java              # @Mod 入口
│   └── datagen/DataGeneratorSetup.java
└── src/main/clojure/cn/li/forge1201/
    ├── mod.clj
    ├── gui/                        # 菜单桥接、注册等
    └── datagen/                    # DataGenerator Clojure 实现
```

- **资源**：`forge-1.20.1/src/main/resources/META-INF/mods.toml` 等；游戏资源也可在 `ac/src/main/resources/assets/<mod_id>/` 维护。

### Fabric 子工程（可选，默认未加入根构建）

仓库中存在 `fabric-1.20.1/`，内含 `fabric.mod.json`、Java 入口与 Clojure 适配。**启用前**请在根 `settings.gradle` 取消 `include 'fabric-1.20.1'` 的注释，并解决与当前 `mcmod`/`ac` 分支的同步问题。

---

## mod.clj 要点（Forge）

- 动态方块/物品注册：从 `cn.li.mcmod.registry.metadata` 等获取 ID 与规格。
- BlockItem 等为需要物品的方块统一创建并注册。
- 不依赖演示用硬编码模块；以元数据为准。

---

## 平台对象协议边界

- **`cn.li.mcmod.platform.item`**（历史文档曾写作 `cn.li.platform.item`）是跨平台 `ItemStack` 抽象。
- Forge 在 `cn.li.forge1201.platform_impl`（或等价命名空间）中对原生类型 `extend-type`。
- `mcmod` 与 `ac` 通过上述协议访问物品（如 `item-is-empty?`、`item-save-to-nbt` 等），避免在内容层直接依赖 Minecraft 类。

---

## 事件与 GUI

- **事件**：`cn.li.mcmod.events.metadata` 等识别方块与处理器（如 `:on-right-click`）。
- **GUI**：DSL 与元数据在 `mcmod`；具体 Wireless 等屏幕工厂与容器逻辑多在 **`ac`**（如 `cn.li.ac.wireless.gui.*`、`cn.li.ac.block.wireless-node.gui`）。Forge 负责 MenuType 注册与桥接类。

---

## Fabric 与 Forge 差异摘要（参考）

当 Fabric 子工程重新纳入构建时，典型差异如下（与 Minecraft 1.20.1 文档一致）：

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
