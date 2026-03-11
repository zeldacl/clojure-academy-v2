# 平台实现与 Fabric 支持

本文档合并自平台实现指南、Fabric 支持文档与 Fabric 1.20.1 实现报告。**当前支持的平台为 Forge 1.20.1 与 Fabric 1.20.1**。

---

## 设计原则

- 平台代码**零游戏名硬编码**；所有内容通过元数据与 registry 动态发现。
- 各平台采用相同架构模式：Java 入口 → Clojure mod.clj（注册、事件、GUI 桥接）→ core 逻辑。

---

## 平台结构

```
forge-1.20.1/ 或 fabric-1.20.1/
├── src/main/
│   ├── java/
│   │   └── my_mod/ 或 com/example/...   # 入口与 DataGenerator 等
│   └── clojure/my_mod/forge1201 或 fabric1201/
│       ├── mod.clj
│       ├── bridge.clj (容器/菜单桥接)
│       ├── events.clj
│       ├── registry_impl.clj (MenuType / ScreenHandlerType)
│       └── init.clj
└── src/main/resources/
    └── META-INF/mods.toml 或 fabric.mod.json
```

- **Forge 入口**：`my_mod/MyMod1201.java`，包名 `my_mod.*`。
- **Fabric 入口**：`com.example.my_mod1201.MyModFabric`、DataGenerator 为 `com.example.fabric1201.datagen.DataGeneratorSetup`。

---

## mod.clj 要点

- 动态方块/物品注册：从 `registry_metadata` 获取 ID 与规格，无硬编码名称。
- BlockItem 为需要物品的方块统一创建并注册。
- 不依赖 `block-demo`、`item-demo` 等游戏模块；仅依赖 core 的 registry/events 元数据。

---

## 平台对象协议边界（非兼容残留）

- `my-mod.platform.item/IItemStack` 是当前在用的跨平台抽象，不是历史兼容层。
- Forge/Fabric 都在各自 `platform_impl.clj` 中对原生 `ItemStack` 做了 `extend-type` 实现。
- core/content 代码通过 `my-mod.platform.item` 调用统一方法（如 `item-is-empty?`、`item-save-to-nbt`、`item-set-damage!`、`create-item-from-nbt`）。

结论：`IItemStack` 需保留；清理标准应为“无实现且无调用”。

---

## 事件与 GUI

- **事件**：通过 `events/metadata` 识别方块与处理器（如 `:on-right-click`），调用 core 的 on-block-right-click 等。
- **GUI**：容器/菜单由 bridge 与 slot-manager 配合；屏幕创建委托给 core 的 screen_factory；MenuType / ScreenHandlerType 由元数据驱动注册。

---

## Fabric 与 Forge 差异摘要

| 概念         | Forge 1.20.1           | Fabric 1.20.1                 |
|--------------|------------------------|-------------------------------|
| 容器         | AbstractContainerMenu  | ScreenHandler                 |
| 类型注册     | MenuType               | ScreenHandlerType             |
| 打开 GUI     | NetworkHooks.openScreen| openHandledScreen             |
| 提供者       | MenuProvider           | NamedScreenHandlerFactory     |
| 网络         | SimpleChannel          | Fabric Networking API         |
| 快速移动     | quickMoveStack         | quickMove                     |

Fabric 1.20.1 已实现 bridge、registry_impl、screen_impl、network、slots、init 等，与 Forge 功能对齐，使用相同 core 逻辑。

---

## 参考

- 项目总结：`01-overview/Project_Summary_CN.md`
- GUI 架构：`06-gui/GUI_Architecture_Refactoring.md`
- DataGenerator：`04-datagen/DataGenerator.md`
