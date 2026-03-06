# BlockState 架构：属性定义与 DataProvider

## 概述

BlockState 采用**定义与实现分离**：属性与结构在 core 中定义，运行时与 DataProvider 在平台层使用这些定义，新增属性只需改 Clojure，无需改 Java。

---

## 问题与目标

- **原问题**：属性曾硬编码在 Java（如 NodeDynamicBlock），新增属性需改 Java，平台与业务耦合。
- **目标**：属性与 BlockState 结构在 core 中声明；平台仅做“注入/生成”，不包含游戏特定知识。

---

## 当前方案

### 1. 业务逻辑（Core）

**属性定义**：`core/.../my_mod/block/wireless_node.clj`

```clojure
(def block-state-properties
  {:energy   {:name "energy"   :type :integer :min 0 :max 4 :default 0}
   :connected {:name "connected" :type :boolean :default false}})
```

在 block DSL 中声明：`(bdsl/defblock ... :block-state-properties block-state-properties ...)`。

**BlockState 结构定义**：`core/.../my_mod/block/blockstate_definition.clj`

- `BlockStatePart`、`BlockStateDefinition` 等数据结构。
- `SIMPLE_BLOCKS`、`NODE_BLOCKS`（从 wireless-node 等推导）。
- 查询接口：`get-block-state-definition`、`get-all-definitions`、`is-multipart-block?`、`get-model-texture-config` 等。

### 2. 属性生成与注册（Core）

**位置**：`core/.../my_mod/block/blockstate_properties.clj`

- 根据 block 的 `block-state-properties` 生成 Minecraft Property 对象（IntegerProperty、BooleanProperty 等）。
- 存入 registry，供平台查询：`get-property`、`get-all-properties` 等。
- 运行时与 DataProvider 共用同一套属性对象。

### 3. 平台集成

**运行时（Forge/Fabric）**：在 `mod.clj` 等注册逻辑中，对需要动态属性的 block（如 node）：

1. 通过 `bsp/get-all-properties(block-id)` 获取 Property 对象。
2. 创建 NodeDynamicBlock（或等价类）并注入这些属性（如 `setBlockIdAndProperties`）。
3. 不硬编码属性名或取值范围。

**Data 生成**：`forge-1.20.1/.../datagen/blockstate_provider.clj`

- 依赖 core 的 `blockstate_definition`，调用 `get-block-state-definition`、`get-all-definitions` 等生成 blockstate JSON。
- 不依赖 core 内 datagen 包；定义层与平台 DataProvider 分离。

---

## 定义层与实现层关系

```
core/block/
  blockstate_definition.clj   ← 所有 block 的 BlockState 结构、parts、model 对应
  blockstate_properties.clj   ← 属性对象生成与 registry
  wireless_node.clj           ← block-state-properties 数据
        ↑
        │ 查询
        │
forge-1.20.1/.../datagen/
  blockstate_provider.clj     ← 生成 blockstates/*.json
  setup.clj                   ← 注册 Provider
```

---

## 添加新属性（仅 Clojure）

示例：为 node 增加 `powered` 属性。

1. 在 `core/block/wireless_node.clj` 的 `block-state-properties` 中增加：
   ```clojure
   :powered {:name "powered" :type :boolean :default false}
   ```
2. 若需对应 model 变体，在 `blockstate_definition.clj` 中为 node 的 parts 增加条件与 model。
3. 无需修改 Java；平台通过 registry 自动发现并注入属性，DataProvider 从定义层读取并生成 JSON。

---

## 优势

- 单一真实来源：属性与结构均在 core 的 Clojure 中。
- 平台无游戏名硬编码：通过 metadata/registry 发现属性与定义。
- 新属性、新 block 仅改 Clojure 定义即可；Forge/Fabric 行为一致。
- 易于扩展新平台：仅需接入属性注入与 DataProvider，复用同一套定义。

---

## 参考文件

- Core 定义：`my-mod.block.blockstate-definition`、`my-mod.block.blockstate-properties`、`my-mod.block.wireless-node`
- Forge 运行时：`my-mod.forge1201.mod`（register-blocks / register-all-blocks）
- Forge DataProvider：`my-mod.forge1201.datagen.blockstate-provider`
