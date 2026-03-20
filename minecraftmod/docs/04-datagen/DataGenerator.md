# DataGenerator 实现指南

## 概述

本项目使用 **Clojure 实现** 的 DataGenerator，在 Forge 1.20.1 与 Fabric 1.20.1 上自动生成 JSON 资源文件，避免 mod-id 与 blockstate 硬编码。

**设计原则**：Java 仅负责框架约束（注解、接口）；所有业务逻辑在 Clojure 中实现。

---

## 架构

### 定义层（Core，平台无关）

- **`core/src/main/clojure/my_mod/block/blockstate_definition.clj`**
  - 定义所有 block 的 BlockState 结构、属性和 model 对应关系。
  - 提供 `get-block-state-definition`、`get-all-definitions`、`get-model-texture-config` 等查询接口。
  - 平台 DataProvider 调用这些接口生成 JSON，core 中**没有** datagen 包。

### 平台实现层（Platform-Specific）

DataProvider 实现位于各平台模块内：

#### Forge 1.20.1

- **`forge-1.20.1/src/main/clojure/my_mod/forge1201/datagen/`**
  - `blockstate-provider.clj`：读取 blockstate_definition，生成 blockstate JSON。
  - `item-model-provider.clj`：生成物品模型 JSON。
  - `setup.clj`：在 GatherDataEvent 中注册上述 Provider。
- **`forge-1.20.1/src/main/java/my_mod/datagen/DataGeneratorSetup.java`**
  - `@Mod.EventBusSubscriber`，将 `GatherDataEvent` 委托给 Clojure：`cn.li.forge1201.datagen.event-handler` 的 `static-gather-data`。

#### Fabric 1.20.1

- **`fabric-1.20.1/src/main/clojure/my_mod/fabric1201/datagen/setup.clj`**
  - `register-data-generators!`：向 Fabric DataGenerator 注册 Provider（blockstate、item-model 等）。
- **`fabric-1.20.1/src/main/java/com/example/fabric1201/datagen/DataGeneratorSetup.java`**
  - 实现 `DataGeneratorEntrypoint`，Fabric 通过 `fabric.mod.json` 的 `fabric-datagen` 入口点调用。
- **`fabric-1.20.1/src/main/resources/fabric.mod.json`**
  - 配置 `"fabric-datagen"` 入口点为上述 Java 类。

---

## 使用方法

### 编译项目

```bash
cd minecraftmod
.\gradlew.bat clean build
```

### 执行 DataGenerator

**Forge 1.20.1**:
```bash
.\gradlew.bat :forge-1.20.1:runData
```

**Fabric 1.20.1**:
```bash
.\gradlew.bat :fabric-1.20.1:runData
```

### 输出位置

生成文件位于对应平台的 `src/main/resources/assets/my_mod/` 下，例如：

- `blockstates/*.json`
- `models/block/*.json`
- `models/item/*.json`

---

## 配置 mod-id

所有生成文件使用 `modid/MOD-ID`（或等价配置）。修改方式：

1. **直接修改**：编辑 `core/src/main/clojure/my_mod/...` 中的 mod-id 常量（若存在集中配置）。
2. **环境变量**（若支持）：
   ```bash
   $env:MOD_ID="custom_mod"
   .\gradlew.bat :forge-1.20.1:runData
   ```

修改后重新运行 `runData`，JSON 会使用新 mod-id。

---

## 添加新方块/物品

1. **方块**：在 core 的 blockstate 定义与 block DSL 中注册；Forge blockstate-provider 从 `blockstate_definition` 读取并生成 JSON。
2. **物品**：在平台 `item-model-provider` 的配置（或等价数据源）中添加条目，运行 `runData`。

无需在 core 中维护“core/datagen”包；仅扩展定义层与平台侧 Provider 配置即可。

---

## 纯 Clojure 逻辑 + Java 桥接

- **Java**：仅实现 `@Mod.EventBusSubscriber`（Forge）或 `DataGeneratorEntrypoint`（Fabric），通过 `RT.var("namespace", "function-name").invoke(event)` 调用 Clojure。
- **Clojure**：实现 `static-gather-data`（Forge）或 `register-data-generators!`（Fabric），注册 Provider；各 Provider 的生成逻辑均在 Clojure 中。

---

## Fabric 与 Forge 差异

| 特性       | Forge 1.20.1              | Fabric 1.20.1           |
|------------|---------------------------|--------------------------|
| 触发方式   | GatherDataEvent           | DataGeneratorEntrypoint  |
| 入口配置   | @Mod.EventBusSubscriber   | fabric.mod.json         |
| 任务       | `:forge-1.20.1:runData`   | `:fabric-1.20.1:runData` |

---

## 故障排除

- **Clojure 未加载**：先执行 `.\gradlew.bat clean build`，再运行 `runData`。
- **JSON 未生成**：检查对应平台 `src/main/resources` 存在且可写；查看 `runData` 完整输出。
- **mod-id 错误**：确认 mod-id 配置或环境变量一致，必要时清理后重新 `runData`。

---

## 项目结构摘要

```
core/
  block/blockstate_definition.clj   ← 定义层（无 datagen 包）

forge-1.20.1/
  clojure/.../datagen/
    blockstate-provider.clj
    item-model-provider.clj
    setup.clj
  java/my_mod/datagen/
    DataGeneratorSetup.java

fabric-1.20.1/
  clojure/.../datagen/
    setup.clj
  java/com/example/fabric1201/datagen/
    DataGeneratorSetup.java
  resources/fabric.mod.json         ← fabric-datagen 入口
```
