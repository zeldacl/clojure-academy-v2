# 运行时架构与 DSL 总览

本文描述重构后的 **模块边界、启动顺序、DSL 数据如何流到 Forge**。阅读 `03-dsl/*` 各指南前，建议先浏览本节。

---

## 1. 模块分工

| 模块 | 职责 | 禁止事项 |
|------|------|----------|
| **`api`** | 对外 Java 接口（如无线/能量的 `acapi` 包），供 Java 与可选互操作 | 不含游戏逻辑 |
| **`mcmod`** | 协议与 DSL：`defblock`/`defitem`/`deftile`、`registry.metadata`、GUI/NBT/事件元数据、`platform.*` 抽象 | **不得**引用 `net.minecraft.*` 或 Loader API |
| **`ac`** | 内容与域逻辑：无线、能量、`content/*` 入口、方块/物品具体实现、网络与 GUI 业务 | **不得**引用 Forge/Fabric/Minecraft 类；通过 `mcmod` 协议与 `acapi` 边界交互 |
| **`forge-1.20.1`** | `@Mod`、注册表、`DeferredRegister`、事件桥、Java 桥（BlockEntity、Menu 等） | **不得**依赖 `cn.li.ac.*` 命名空间（通过 `mcmod.content` + `lifecycle` 间接拉起 `ac`） |

**依赖红线**：`ac` ↔ `forge-1.20.1` 无直接依赖；二者只共同依赖 `mcmod`（及 `api`）。

---

## 2. Block / Item / Tile DSL 在 mcmod 中的位置

### 2.1 数据结构与注册表

- **`cn.li.mcmod.block.dsl`**
  - `defblock`、`defmultiblock` / `defcontroller-multiblock`：宏展开为 `register-block!`，写入 **`block-registry`**（atom）。
  - `create-block-spec` 将选项归并为 **`BlockSpec`**，含嵌套记录：`PhysicalProperties`、`RenderingProperties`、`TileEntityConfig`、`BlockStateConfig`、`EventHandlers`、`MultiBlockConfig`。
  - 支持 **扁平写法**（`:material`、`:on-right-click` 与顶层同级）与 **嵌套写法**（`:physical {...}`、`:events {...}`），二者在 `create-block-spec` 中合并。
- **`cn.li.mcmod.item.dsl`**
  - `defitem` → **`item-registry`**（atom），`ItemSpec` 记录。
- **`cn.li.mcmod.block.tile-dsl`**
  - `deftile` / `deftile-kind` → **tile-registry**，描述哪些 block-id 绑定哪个 tile-id、生命周期钩子。
- **`cn.li.mcmod.registry.metadata`**
  - **唯一面向平台的聚合查询层**：从上述 registry 读取，提供 `get-all-block-ids`、`get-block-spec`、`has-block-entity?`、`get-all-tile-ids`、GUI/item 等查询，**避免 Forge 直接扫 `ac`**。

### 2.2 事件与方块 DSL 的关系

- 方块 spec 的 **`:events`**（或扁平的 `:on-right-click` 等）在 `create-block-spec` 中写入 `EventHandlers`。
- **`cn.li.mcmod.events.metadata`** 在 **`init-event-metadata!`** 时执行 **`sync-handlers-from-dsl!`**：遍历 `bdsl/list-blocks`，把 DSL 里的事件函数登记到 **`block-event-handlers`**。
- Forge 侧 **`cn.li.forge1201.events`** 通过 **`cn.li.mcmod.events.dispatcher`** 按 block-id 分发，不硬编码方块名。

---

## 3. 内容入口（ac）：谁在 `require` 方块定义

- **`cn.li.ac.registry.content-namespaces`** 维护 **`block-namespaces`**、**`item-namespaces`**（如 `cn.li.ac.content.blocks.wireless`、`cn.li.ac.content.items.all`）。
- **`load-all!`** 对上述命名空间做 **`require`**，触发各文件中的 **`defblock`** / **`defitem`** 及子模块的 **`require`**（例如 wireless 再拉取 `wireless-node.block`、`wireless-matrix.gui` 等）。
- **`cn.li.ac.registry.hooks`**：`register-network-handler!`、`register-client-renderer!`；在 **`cn.li.ac.core/init`** 末尾 **`call-all-network-handlers!`**、客户端再跑注册的 renderer。

典型无线入口 **`cn.li.ac.content.blocks.wireless`**：`(msg-reg/register-all!)` 在加载期注册消息 ID。

---

## 4. 启动顺序（Forge）

1. Java **`@Mod`** 构造 / 早期入口调用 Clojure **`cn.li.forge1201.init/init-from-java`**。
2. **`init-from-java`**：`platform.dispatch/*platform-version*` → `:forge-1.20.1`，**`cn.li.mcmod.content/ensure-content-init-registered!`**（内部 `requiring-resolve` **`cn.li.ac.core/init`**，使 **`lifecycle/register-content-init!`** 已执行），再 **`lifecycle/run-content-init!`**。
3. **`cn.li.ac.core/init`**（内容初始化）大致顺序：
   - 注入 mod-id、GUI 平台实现、slot validators、resource 解析等；
   - **`wd/init-world-data!`**、能量/无线 Java API bridge；
   - **`content-ns/load-all!`** → 所有 **`defblock`/`defitem`** 进入 registry；
   - 按 GUI id 注册 screen factory（依赖上一步 DSL 已加载）；
   - **`event-metadata/init-event-metadata!`** → 从 block DSL 同步事件表；
   - **`hooks/call-all-network-handlers!`** 等。
4. Forge **注册阶段**（如 **`cn.li.forge1201.mod/register-all-blocks!`**）：只对 **`registry.metadata`** 暴露的 id 循环，按 metadata 选择 `DynamicStateBlock`、`ScriptedBlock`、多方块等 Java 工厂；**`register-block-entities!`** 按 **tile-id** 聚合 BlockEntityType；**`register-all-items!`** 同理。

---

## 5. 复杂方块实现模式（ac 侧）

以无线节点为例（**`cn.li.ac.block.wireless-node.block`**）：

- 仍用 **`bdsl/defblock`** / **`tdsl/deftile`** 声明；额外使用 **`cn.li.mcmod.block.state-schema`** 从 schema 生成 **NBT 读写、网络 handler、blockstate 属性**。
- 状态进 **`ScriptedBlockEntity`** 的 Clojure map；能力通过 **`platform.capability`** 与 **`acapi`** 接口暴露。
- 网络：消息 ID 在域内注册，**`hooks/register-network-handler!`** 在适当时机注册服务端逻辑。

---

## 6. 与 DataGenerator 的关系

- **定义**在 **`mcmod`**（`blockstate_definition`、`blockstate_properties`）。
- **生成**在 **`forge-1.20.1`** 的 **`cn.li.forge1201.datagen.*`**，由 **`DataGeneratorSetup`** 把 `GatherDataEvent` 交给 Clojure。

---

## 7. Fabric

`fabric-1.20.1` 目录可保留对等适配；**根 `settings.gradle` 默认不 include**。启用后应复用同一套 **`mcmod` registry + `ac` 内容**，仅替换平台入口与桥接实现。

---

## 参考代码入口

| 主题 | 命名空间 / 路径 |
|------|-----------------|
| Block 宏与 BlockSpec | `cn.li.mcmod.block.dsl` |
| 平台查询 | `cn.li.mcmod.registry.metadata` |
| Forge 注册 | `cn.li.forge1201.mod` |
| 内容加载 | `cn.li.ac.registry.content-namespaces`, `cn.li.ac.core` |
| 生命周期 | `cn.li.mcmod.lifecycle`, `cn.li.mcmod.content` |
| 事件同步 | `cn.li.mcmod.events.metadata`, `cn.li.mcmod.events.dispatcher` |
| Forge 初始化 | `cn.li.forge1201.init` |
