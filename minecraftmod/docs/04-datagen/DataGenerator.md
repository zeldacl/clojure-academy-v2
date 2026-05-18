# DataGenerator 实现指南

## 概述

本项目使用 **Clojure 实现** 的 DataGenerator，在 **Forge/Fabric 1.20.1** 上生成 JSON 资源（blockstate、模型、语言等），避免手写大量资源文件与 id 不一致。

**设计原则**：Java 仅负责 Loader 事件/入口；provider 列表、provider 注册循环与可共享的生成逻辑在 Clojure 中完成。

---

## 架构

### 定义层（mcmod，平台无关）

- **`mcmod/src/main/clojure/cn/li/mcmod/block/blockstate_definition.clj`**
  - BlockState 结构、属性与 model 对应关系。
  - 提供 `get-block-state-definition`、`get-all-definitions`、`get-model-texture-config` 等。
  - `mcmod` 内**不**维护独立 `datagen` 包；生成由 Forge 子工程调用上述函数完成。

### 共享 Minecraft datagen 层（mc-1.20.1）

- **`mc-1.20.1/src/main/clojure/cn/li/mc1201/datagen/`**
  - `provider_manifest.clj`：Forge/Fabric provider 顺序与逻辑 provider 集合。
  - `provider_registration.clj`：共享 provider 注册循环与日志。
  - `blockstate_provider_shell.clj`：共享 blockstate provider 创建壳。
  - `*_provider.clj` / `*_core.clj`：语言、物品模型、配方、成就等共享 provider 实现与数据整理。

### 平台实现层（Forge/Fabric 1.20.1）

- **`forge-1.20.1/src/main/clojure/cn/li/forge1201/datagen/`**
  - `provider_factory.clj`：Forge Provider Factory/API 适配，包括共享 blockstate provider shell。
  - `setup.clj`、`event_handler.clj`：在 `GatherDataEvent` 中调用共享 provider 注册循环。
- **`fabric-1.20.1/src/main/clojure/cn/li/fabric1201/datagen/`**
  - `provider_factory.clj`：Fabric Pack Factory/API 适配，包括共享 blockstate provider shell。
  - `setup.clj`：在 Fabric DataGeneratorEntrypoint 中调用共享 provider 注册循环。
- **`forge-1.20.1/src/main/java/cn/li/forge1201/datagen/DataGeneratorSetup.java`**
  - `@Mod.EventBusSubscriber`，将 `GatherDataEvent` 委托给 Clojure：`cn.li.forge1201.datagen.event-handler` 的 `static-gather-data`。

---

## 使用方法

### 编译项目

```bash
cd minecraftmod
cmd /c .\gradlew.bat :forge-1.20.1:classes
```

### 执行 DataGenerator（Forge）

```bash
cmd /c .\gradlew.bat :forge-1.20.1:runData
```

### 执行 DataGenerator（Fabric）

```bash
cmd /c .\gradlew.bat :fabric-1.20.1:runDatagen
```

### 对比 Forge/Fabric datagen 输出

```bash
cmd /c .\gradlew.bat :forge-1.20.1:runData :fabric-1.20.1:runDatagen verifyForgeFabricDatagenParity
```

### 输出位置

生成文件通常写入 **`ac`** 或 **Forge** 模块的 `src/main/resources/assets/<mod_id>/`，例如（`mod_id` 见 `gradle.properties`）：

- `blockstates/*.json`
- `models/block/*.json`
- `models/item/*.json`

以各 Provider 实现与 Gradle 任务配置为准。

---

## 配置 mod-id

- 主配置在根目录 **`gradle.properties`**：`mod_id=my_mod`（示例）。
- 生成逻辑应通过统一常量或资源路径工具读取，避免在多个文件手写不一致的 id。

修改后重新运行 `runData`。

---

## 添加新方块/物品

1. **方块**：在 `mcmod` 的 blockstate 定义与 `ac` 的 block DSL 中注册；共享 blockstate provider shell 从定义层读取并生成 JSON。
2. **物品**：在 `item_model_provider`（或等价数据源）中添加条目，运行 `runData`。

无需在 `mcmod` 中维护单独的 “datagen” 业务包；扩展定义层与 Forge Provider 即可。

---

## 纯 Clojure 逻辑 + Java 桥接

- **Java**：`DataGeneratorSetup` 实现 `@SubscribeEvent`，通过 Clojure `RT` 调用 `static-gather-data`。
- **Clojure**：平台 `setup.clj` 只负责 Loader API 入参拆解；provider 顺序/日志由 `mc1201.datagen.provider-registration` 统一执行，具体 Factory/API 差异由平台 `provider_factory.clj` 适配。

---

## Fabric 与 Forge 差异（参考）

| 特性     | Forge 1.20.1            | Fabric 1.20.1                     |
|----------|-------------------------|-----------------------------------|
| 触发方式 | GatherDataEvent         | DataGeneratorEntrypoint           |
| Gradle   | `:forge-1.20.1:runData` | `:fabric-1.20.1:runDatagen`       |

---

## 故障排除

- **Clojure 未加载**：先 `cmd /c .\gradlew.bat :forge-1.20.1:compileJava :forge-1.20.1:compileClojure`，再 `runData`。
- **JSON 未生成**：查看 `runData` 完整日志；确认输出目录可写且 Provider 已注册。
- **mod-id 错误**：核对 `gradle.properties` 与代码中资源路径一致。

---

## 项目结构摘要（当前）

```
mcmod/
  src/main/clojure/cn/li/mcmod/block/
    blockstate_definition.clj
    blockstate_properties.clj

mc-1.20.1/
  src/main/clojure/cn/li/mc1201/datagen/
    provider_manifest.clj
    provider_registration.clj
    blockstate_provider_shell.clj

forge-1.20.1/
  src/main/clojure/cn/li/forge1201/datagen/
    provider_factory.clj
    event_handler.clj
    setup.clj
  src/main/java/cn/li/forge1201/datagen/
    DataGeneratorSetup.java

fabric-1.20.1/
  src/main/clojure/cn/li/fabric1201/datagen/
    provider_factory.clj
    setup.clj

ac/
  src/main/resources/assets/<mod_id>/   # 常见：生成结果或手工资源
```
