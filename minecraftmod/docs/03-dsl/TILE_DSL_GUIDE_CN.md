# Tile DSL（`deftile` / `deftile-kind`）使用指南

本项目的 BlockEntity（旧称 tile entity）注册采用 **元数据驱动**：Tile DSL 与元数据在 **`mcmod`** 声明，具体方块内容在 **`ac`**；Forge 适配层遍历元数据并注册，避免在平台层硬编码内容列表。（Fabric 子工程当前已纳入根构建，按 minimal maintenance 维护。）

与 **`defblock`**、Forge **`register-block-entities!`** 的衔接见 **`docs/02-architecture/Runtime_And_DSL_CN.md`**。

相关代码位置：
- `mcmod/src/main/clojure/cn/li/mcmod/block/tile_dsl.clj`：Tile DSL（`deftile`、`deftile-kind`）
- `mcmod/src/main/clojure/cn/li/mcmod/protocol/metadata.clj`：平台侧查询入口（tile-id / block->tile 映射）

---

## 1. `deftile-kind`：复用一套生命周期逻辑

当多个 tile 共享同一套 tick/NBT 逻辑时，优先用 `deftile-kind` 注册默认逻辑，然后在 `deftile` 中只填写 `:tile-kind` 即可，减少样板代码。

```clojure
(ns cn.li.ac.block.my-machine
  (:require [cn.li.mcmod.block.tile-dsl :as tdsl]))

(defn my-tick [level pos state be] ...)
(defn my-read [tag] ...)
(defn my-write [be tag] ...)

(tdsl/deftile-kind :my-machine
  :tick-fn my-tick
  :read-nbt-fn my-read
  :write-nbt-fn my-write)
```

---

## 2. `deftile`：声明 tile 元数据（BlockEntityType）

`deftile` 的核心信息：
- `:id`：tile-id（建议 kebab-case，作为元数据主键）
- `:registry-name`：注册名（snake_case）。**对已有世界/存档要保持稳定**。
- `:blocks`：绑定到哪些 block-id（可 1 个或多个）
- `:tile-kind`：复用 `deftile-kind` 注册的默认逻辑（可选）
- `:tick-fn` / `:read-nbt-fn` / `:write-nbt-fn`：直接写在 tile 上（可选，优先级高于 kind 默认）

```clojure
(tdsl/deftile my-machine-tile
  :id "my-machine"
  :impl :scripted
  :registry-name "my_machine"     ;; 稳定注册名（重要）
  :blocks ["my-machine"]          ;; 绑定 1 个 block
  :tile-kind :my-machine)         ;; 复用 kind 默认逻辑
```

---

## 3. 一个 tile 绑定多个 blocks（减少 BlockEntityType 数量）

对于新内容（尚未发布/不担心旧世界兼容）可以让一个 tile 绑定多个 blocks：

```clojure
(tdsl/deftile my-tiers-tile
  :id "my-tiers"
  :impl :scripted
  :registry-name "my_tiers"
  :blocks ["my-tier-1" "my-tier-2" "my-tier-3"]
  :tile-kind :my-machine)
```

注意：
- 这会让多个 blocks 共享同一个 BlockEntityType（平台侧会按 tile-id 注册）。
- 如果这些 blocks 在旧版本里曾经各自拥有不同的 BlockEntityType 注册名，**合并会改变 BE type id**，可能导致旧世界无法读取对应方块实体。对已发布内容请谨慎迁移。

---

## 4. 平台侧如何消费（你不需要改平台代码）

Forge/Fabric 适配层会通过 `cn.li.mcmod.protocol.metadata` 查询：
- `get-all-tile-ids`
- `get-tile-registry-name`
- `get-tile-block-ids`
- `get-tile-spec`
- `get-block-tile-id`（block-id -> tile-id）

因此新增 tile 内容通常只需要在内容层新增 `deftile`（以及可选的 `deftile-kind`），平台侧注册循环无需修改。

---

## 5. 容器与 capability（写入 tile spec）

运行期不再使用 `tile-logic-registry` / `container-registry` / `capability-registry`。容器与 capability **只在声明期**写入 `tile-registry`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `:container` | map | 标准键：`:get-size` `:get-item` `:set-item!` `:remove-item` `:remove-item-no-update` `:still-valid` `:slots-for-face` `:can-place` `:can-take`（兼容旧键 `:still-valid?` 等，编译期归一） |
| `:capability-keys` | set of keyword | 如 `#{:wireless-receiver :fluid-handler}`；handler 工厂由 `platform.capability/declare-capability!` 注册，编译进 `ITileCapabilityLogic` |

`ac` 侧推荐通过 `cn.li.ac.block.machine.registration/init-machine!`：

```clojure
(machine-reg/init-machine!
  {:tiles [{:id "my-machine" :registry-name "my_machine" :blocks ["my-machine"]
            :tick-fn my-tick :read-nbt-fn my-read :write-nbt-fn my-write}]
   :containers {"my-machine" {:get-size (fn [be] 9) :get-item ...}}
   :capabilities [{:key :wireless-receiver :interface IWirelessReceiver :factory my-factory}]
   :blocks [...]})
```

`:containers` / `:capabilities` 语法保留，内部合并进 tile spec 后 `register-tile!`，**不再**调用已删除的 `register-container!` / `register-capability!`。

---

## 6. 编译与安装（`mc-1.20.1`）

注册期平台调用共享流水线（Forge/Fabric 相同）：

1. `cn.li.mc1201.block.logic-pipeline/compile-all-bundles` — 遍历 `tile-dsl`，合并 `tile-kind` 默认，产出 `TileLogicBundle`。
2. 每个 `IScriptedBlock` 实例创建后立即 `install-bundle-to-block!`。
3. Forge 在 common setup 调用 `assert-all-blocks-have-bundle!`（未安装则启动失败）。

热路径：`AbstractScriptedBlockEntity` 通过 `getBlockState().getBlock()` 读取 bundle，无 `RT.var`。

详见 [SCRIPTED_LOGIC_DISPATCH.md](../04-systems/SCRIPTED_LOGIC_DISPATCH.md)。

