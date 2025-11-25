# Block DSL 使用指南

## 概述

Block DSL 提供了一种声明式的方式来定义 Minecraft 方块，无需编写繁琐的 Java 代码。

## 核心概念

### 1. Block DSL 核心 (`my-mod.block.dsl`)

提供声明式方块定义宏和运行时管理。

#### 基础语法

```clojure
(bdsl/defblock block-name
  :material :stone          ; 材质
  :hardness 1.5             ; 硬度
  :resistance 6.0           ; 爆炸抗性
  :light-level 0            ; 光照等级 (0-15)
  :requires-tool true       ; 是否需要工具
  :harvest-tool :pickaxe    ; 采集工具
  :harvest-level 0          ; 采集等级
  :sounds :stone            ; 音效
  :on-right-click fn        ; 右键点击处理器
  :on-break fn              ; 破坏处理器
  :on-place fn)             ; 放置处理器
```

### 2. 材质类型

```clojure
:stone   ; 石头
:wood    ; 木头
:metal   ; 金属
:glass   ; 玻璃
:dirt    ; 泥土
:sand    ; 沙子
:grass   ; 草
:leaves  ; 树叶
:water   ; 水
:lava    ; 岩浆
:air     ; 空气
```

### 3. 工具类型

```clojure
:pickaxe  ; 镐
:axe      ; 斧
:shovel   ; 铲
:hoe      ; 锄
:sword    ; 剑
```

### 4. 音效类型

```clojure
:stone   ; 石头音效
:wood    ; 木头音效
:metal   ; 金属音效
:glass   ; 玻璃音效
:grass   ; 草地音效
:sand    ; 沙子音效
:gravel  ; 沙砾音效
```

## 完整示例

### 示例 1：基础方块

```clojure
(ns my-mod.custom-blocks
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.util.log :as log]))

;; 简单的石头方块
(bdsl/defblock custom-stone
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone)
```

### 示例 2：带交互的方块

```clojure
;; 可点击的方块
(bdsl/defblock clickable-block
  :material :metal
  :hardness 3.0
  :resistance 10.0
  :light-level 5
  :on-right-click (fn [event-data]
                    (log/info "Block clicked!")
                    (let [{:keys [player world pos]} event-data]
                      ;; 打开 GUI 或执行其他逻辑
                      )))
```

### 示例 3：发光方块

```clojure
;; 发光方块
(bdsl/defblock lamp
  :material :glass
  :hardness 1.0
  :resistance 1.0
  :light-level 15        ; 最大亮度
  :sounds :glass)
```

### 示例 4：矿石

```clojure
;; 铜矿石
(bdsl/defblock copper-ore
  :material :stone
  :hardness 3.0
  :resistance 3.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1       ; 石镐可挖
  :sounds :stone
  :on-break (fn [event-data]
              (log/info "Copper ore mined!")
              ;; 可以添加掉落物逻辑
              ))
```

### 示例 5：滑溜方块

```clojure
;; 冰块（滑溜）
(bdsl/defblock ice
  :material :glass
  :hardness 0.5
  :resistance 0.5
  :friction 0.98         ; 高摩擦力 = 滑溜
  :slip-factor 0.98
  :sounds :glass)
```

## 预设系统

DSL 提供了预设函数来快速创建常见类型的方块。

### 矿石预设

```clojure
(def my-ore-preset (bdsl/ore-preset 2))
;; => {:material :stone
;;     :hardness 3.0
;;     :resistance 3.0
;;     :requires-tool true
;;     :harvest-tool :pickaxe
;;     :harvest-level 2
;;     :sounds :stone}
```

### 木头预设

```clojure
(def my-wood-preset (bdsl/wood-preset))
;; => {:material :wood
;;     :hardness 2.0
;;     :resistance 3.0
;;     :requires-tool false
;;     :harvest-tool :axe
;;     :harvest-level 0
;;     :sounds :wood}
```

### 金属预设

```clojure
(def my-metal-preset (bdsl/metal-preset 3))
;; => {:material :metal
;;     :hardness 5.0
;;     :resistance 6.0
;;     :requires-tool true
;;     :harvest-tool :pickaxe
;;     :harvest-level 3
;;     :sounds :metal}
```

### 玻璃预设

```clojure
(def my-glass-preset (bdsl/glass-preset))
;; => {:material :glass
;;     :hardness 0.3
;;     :resistance 0.3
;;     :requires-tool false
;;     :sounds :glass}
```

### 发光方块预设

```clojure
(def my-light-preset (bdsl/light-block-preset 12))
;; => {:material :glass
;;     :hardness 1.0
;;     :resistance 1.0
;;     :light-level 12
;;     :sounds :glass}
```

## 预设合并

可以组合多个预设和自定义选项：

```clojure
(def magical-ore
  (bdsl/merge-presets
    (bdsl/ore-preset 2)           ; 基础矿石属性
    {:light-level 8               ; 添加发光
     :on-break (fn [event-data]   ; 添加破坏处理器
                 (log/info "Magical ore broken!"))}))

;; 使用合并后的预设
(bdsl/defblock magical-diamond-ore
  :material (:material magical-ore)
  :hardness (:hardness magical-ore)
  :light-level (:light-level magical-ore)
  :on-break (:on-break magical-ore))
```

## 交互处理器

### 右键点击

```clojure
(bdsl/defblock interactive-block
  :material :metal
  :hardness 3.0
  :on-right-click (fn [event-data]
                    (let [{:keys [player world pos]} event-data]
                      (log/info "Player" player "clicked at" pos)
                      ;; 打开 GUI
                      (container/open-gui-container player my-gui world pos))))
```

### 方块破坏

```clojure
(bdsl/defblock explosive-block
  :material :sand
  :hardness 0.5
  :on-break (fn [event-data]
              (let [{:keys [world pos]} event-data]
                (log/info "Creating explosion at" pos)
                ;; 创建爆炸
                )))
```

### 方块放置

```clojure
(bdsl/defblock tracked-block
  :material :stone
  :hardness 2.0
  :on-place (fn [event-data]
              (let [{:keys [player pos]} event-data]
                (log/info "Player" player "placed block at" pos)
                ;; 记录放置位置
                )))
```

## 完整功能示例

### 示例：带 GUI 的工作台

```clojure
(ns my-mod.workbench
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.gui.demo :as gui-demo]
            [my-mod.gui.container :as container]))

(bdsl/defblock crafting-station
  :material :wood
  :hardness 2.5
  :resistance 2.5
  :requires-tool false
  :harvest-tool :axe
  :sounds :wood
  :on-right-click (fn [event-data]
                    (let [{:keys [player world pos]} event-data]
                      (container/open-gui-container 
                        player 
                        gui-demo/crafting-gui 
                        world 
                        pos))))
```

### 示例：自定义熔炉

```clojure
(bdsl/defblock custom-furnace
  :material :stone
  :hardness 3.5
  :resistance 3.5
  :light-level 13          ; 工作时发光
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone
  :on-right-click (fn [event-data]
                    (let [{:keys [player world pos]} event-data]
                      (container/open-gui-container 
                        player 
                        gui-demo/furnace-gui 
                        world 
                        pos))))
```

### 示例：传送方块

```clojure
(bdsl/defblock teleporter
  :material :metal
  :hardness 3.0
  :resistance 10.0
  :light-level 5
  :requires-tool true
  :on-right-click (fn [event-data]
                    (let [{:keys [player world]} event-data]
                      (log/info "Teleporting player...")
                      ;; 执行传送逻辑
                      )))
```

## 获取方块信息

```clojure
;; 获取已注册的方块
(bdsl/get-block "demo-block")
;; => #BlockSpec{:id "demo-block" :material :stone ...}

;; 列出所有方块
(bdsl/list-blocks)
;; => ("demo-block" "copper-ore" "glowing-stone" ...)

;; 获取方块属性
(bdsl/get-block-properties my-block-spec)
;; => {:material :stone :hardness 1.5 :resistance 6.0 ...}
```

## 在版本适配器中使用

### Forge 实现

```clojure
(ns my-mod.forge1165.mod
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.block.demo :as demo]))

;; 初始化方块
(defn mod-init []
  (demo/init-demo-blocks!)
  
  ;; 注册方块
  (let [block-spec (bdsl/get-block "demo-block")
        props (bdsl/get-block-properties block-spec)]
    (.register blocks-register "demo_block"
      (reify Supplier
        (get [_]
          (Block. (.. (AbstractBlock$Properties/of Material/STONE)
                      (strength (:hardness props) (:resistance props)))))))))
```

### Fabric 实现

```clojure
(ns my-mod.fabric1201.mod
  (:require [my-mod.block.dsl :as bdsl]))

;; 使用 DSL 定义的方块
(defn register-blocks []
  (let [block-spec (bdsl/get-block "demo-block")]
    (Registry/register 
      BuiltInRegistries/BLOCK
      (ResourceLocation. "my_mod" (:id block-spec))
      (create-fabric-block block-spec))))
```

## API 参考

### 宏

- `(defblock name & options)` - 定义方块

### 函数

- `(create-block-spec id options)` - 创建方块规范
- `(register-block! spec)` - 注册方块
- `(get-block id)` - 获取方块规范
- `(list-blocks)` - 列出所有方块
- `(get-block-properties spec)` - 获取方块属性
- `(handle-right-click spec data)` - 处理右键点击
- `(handle-break spec data)` - 处理方块破坏
- `(handle-place spec data)` - 处理方块放置

### 预设函数

- `(ore-preset harvest-level)` - 矿石预设
- `(wood-preset)` - 木头预设
- `(metal-preset harvest-level)` - 金属预设
- `(glass-preset)` - 玻璃预设
- `(light-block-preset light-level)` - 发光方块预设
- `(merge-presets & presets)` - 合并预设

## 优势

### ✅ 声明式语法
- 清晰直观的方块定义
- 易于阅读和维护

### ✅ 代码复用
- 预设系统减少重复代码
- 组合式设计

### ✅ 类型安全
- 编译时验证
- 明确的错误提示

### ✅ 跨版本支持
- 版本无关的定义
- 平台特定实现分离

### ✅ 功能完整
- 支持所有方块属性
- 交互处理器
- GUI 集成

## 代码对比

### 传统 Java 方式

```java
// 每个方块需要 50+ 行代码
public class DemoBlock extends Block {
    public DemoBlock() {
        super(Properties.of(Material.STONE)
            .strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops());
    }
    
    @Override
    public InteractionResult use(BlockState state, Level level, 
                                  BlockPos pos, Player player, ...) {
        // 处理逻辑
        return InteractionResult.SUCCESS;
    }
}

// 注册代码
public static final RegistryObject<Block> DEMO_BLOCK = 
    BLOCKS.register("demo_block", DemoBlock::new);
```

### DSL 方式

```clojure
;; 只需 10 行代码
(bdsl/defblock demo-block
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :requires-tool true
  :on-right-click (fn [data]
                    ;; 处理逻辑
                    ))
```

**代码减少 80%**

## 总结

Block DSL 提供了：

1. **简洁的语法** - 10 行代替 50+ 行 Java
2. **预设系统** - 快速创建常见方块类型
3. **交互处理** - 内置右键、破坏、放置处理器
4. **跨平台** - 统一定义，多版本支持
5. **类型安全** - 编译时验证，运行时检查

使用 Block DSL，你可以专注于方块的功能，而不是繁琐的样板代码！
