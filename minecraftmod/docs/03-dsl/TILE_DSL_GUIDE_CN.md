# Tile DSL（`deftile` / `deftile-kind`）使用指南

本项目的 BlockEntity（旧称 tile entity）注册采用 **元数据驱动**：内容逻辑在 `core` 声明，Forge/Fabric 适配层只“遍历元数据并注册”，避免在平台层硬编码内容列表。

相关代码位置：
- `core/src/main/clojure/my_mod/block/tile_dsl.clj`：Tile DSL（`deftile`、`deftile-kind`）
- `core/src/main/clojure/my_mod/registry/metadata.clj`：平台侧查询入口（tile-id / block->tile 映射）

---

## 1. `deftile-kind`：复用一套生命周期逻辑

当多个 tile 共享同一套 tick/NBT 逻辑时，优先用 `deftile-kind` 注册默认逻辑，然后在 `deftile` 中只填写 `:tile-kind` 即可，减少样板代码。

```clojure
(ns cn.li.block.my-machine
  (:require [cn.li.block.tile-dsl :as tdsl]))

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

Forge/Fabric 适配层会通过 `cn.li.registry.metadata` 查询：
- `get-all-tile-ids`
- `get-tile-registry-name`
- `get-tile-block-ids`
- `get-tile-spec`
- `get-block-tile-id`（block-id -> tile-id）

因此新增 tile 内容通常只需要在 core 中新增 `deftile`（以及可选的 `deftile-kind`），平台侧注册循环无需修改。

