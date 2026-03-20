# 项目总结：架构、重构与迁移

本文档合并自项目完成总结、平台中立化重构总结与 Java→Clojure 迁移报告。**当前支持的平台为 Forge 1.20.1 与 Fabric 1.20.1**（无 Forge 1.16.5）。

---

## 一、项目概览

### 构建与模块

- **Gradle 多项目**：`settings.gradle` 包含 `core`、`forge-1.20.1`、`fabric-1.20.1`。
- **Core**：平台无关逻辑（registry、blocks/items、gui、events 元数据、blockstate 定义、energy 等）。
- **平台层**：仅做 API 适配与注册，不包含游戏名硬编码；通过元数据与 DSL 发现内容。

### 核心设计原则

- **分层解耦**：Minecraft/Forge 或 Fabric API → Java 入口（@Mod / ModInitializer）→ Clojure 适配层（defmethod / multimethod）→ Clojure 核心逻辑。
- **零硬编码**：平台代码不写死方块、物品、GUI ID；元数据驱动注册与事件。
- **单一真实来源**：游戏内容在 core 的 DSL 与元数据中定义；添加新内容无需改平台代码。

### 迁移成果（Java → Clojure）

- 绝大部分逻辑在 Clojure 中；Java 仅保留极简桥接（@Mod、委托到 Clojure 的 mod-init/mod-setup/事件处理）。
- Forge 1.20.1：`MyMod1201.java` 委托给 `cn.li.forge1201.mod`；Fabric 1.20.1 同理，入口为 ModInitializer。

---

## 二、平台中立化重构摘要

- **GUI**：screen_factory、slot_manager、GUI 元数据、IContainerOperations、动态 MenuType 注册；平台仅调用工厂与 slot-manager。
- **注册**：registry_metadata、events 元数据；方块/物品/事件由元数据与 DSL 驱动，平台循环注册，无 demo-block 等硬编码。
- **事件**：events/metadata.clj、从 DSL 的 `:on-right-click` 等同步处理器；平台零游戏模块依赖。
- **能量**：物品/节点/接收器充放电统一走 `cn.li.energy.operations`（原 energy/stub 已重命名）。

---

## 三、构建与运行

- **构建**：`.\gradlew clean build` 或 `buildAll`（若配置）。
- **运行客户端**：`.\gradlew :forge-1.20.1:runClient` 或 `:fabric-1.20.1:runClient`。
- **DataGenerator**：`.\gradlew :forge-1.20.1:runData` / `:fabric-1.20.1:runData`；详见 `04-datagen/DataGenerator.md`。

---

## 四、文档索引

- 架构与 BlockState：`02-architecture/BlockState_Architecture.md`
- 平台与 Fabric：`02-architecture/Platform_And_Fabric.md`
- DataGenerator：`04-datagen/DataGenerator.md`
- Wireless / GUI：`05-wireless/`、`06-gui/`
- DSL：`03-dsl/`
