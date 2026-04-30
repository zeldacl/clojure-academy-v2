# 配置系统实现总结

**日期**: 2026-04-27
**状态**: ✅ 完成

---

## 实现概述

完成了AcademyCraft的配置持久化系统，使用Forge 1.20.1的ForgeConfigSpec实现配置文件的持久化存储和动态加载。

---

## 实现的文件

### 1. Java配置定义
**文件**: `forge-1.20.1/src/main/java/cn/li/forge1201/config/GameplayConfig.java`

- 使用ForgeConfigSpec定义所有配置项
- 配置文件: `academycraft-gameplay.toml`
- 配置类型: COMMON（服务端和客户端共享）

**配置分组**:
```java
// Generic settings
ANALYSIS_ENABLED, ATTACK_PLAYER, DESTROY_BLOCKS, 
GEN_ORES, GEN_PHASE_LIQUID, HEADS_OR_TAILS

// Ability settings
NORMAL_METAL_BLOCKS, WEAK_METAL_BLOCKS, METAL_ENTITIES

// CP/Overload data
CP_RECOVER_COOLDOWN, CP_RECOVER_SPEED,
OVERLOAD_RECOVER_COOLDOWN, OVERLOAD_RECOVER_SPEED,
INIT_CP, ADD_CP, INIT_OVERLOAD, ADD_OVERLOAD

// Global calculation
DAMAGE_SCALE
```

### 2. Clojure配置桥接
**文件**: `forge-1.20.1/src/main/clojure/cn/li/forge1201/config/gameplay_bridge.clj`

提供Clojure友好的配置访问函数:
```clojure
(defn attack-player? [] (.get GameplayConfig/ATTACK_PLAYER))
(defn get-normal-metal-blocks [] (vec (.get GameplayConfig/NORMAL_METAL_BLOCKS)))
(defn is-metal-block? [block-id] ...)
(defn get-init-cp [level] ...)
```

### 3. 配置初始化
**文件**: `forge-1.20.1/src/main/clojure/cn/li/forge1201/config/gameplay_init.clj`

在平台初始化时绑定配置桥接:
```clojure
(defn bind-gameplay-config!
  "Bind the gameplay config bridge to ac config namespace."
  []
  (let [config-bridge {:analysis-enabled? bridge/analysis-enabled?
                       :attack-player? bridge/attack-player?
                       ...}]
    (alter-var-root config-var (constantly config-bridge))))
```

### 4. 游戏逻辑配置访问
**文件**: `ac/src/main/clojure/cn/li/ac/config/gameplay.clj`

使用动态变量实现配置注入:
```clojure
(def ^:dynamic *config-bridge*
  "Dynamic var bound to config bridge implementation by platform layer.
  If nil, uses default values."
  nil)

(defn attack-player-enabled? []
  (if (use-bridge?)
    ((:attack-player? *config-bridge*))
    (:attack-player default-generic-config)))
```

### 5. 平台集成
**文件**: `forge-1.20.1/src/main/clojure/cn/li/forge1201/mod.clj`

在FMLCommonSetupEvent中初始化配置:
```clojure
(defn on-common-setup [_event]
  ...
  ;; Bind gameplay config bridge
  (gameplay-init/bind-gameplay-config!)
  ...)
```

---

## 配置项详细说明

### 通用配置 (Generic)

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| analysis | boolean | false | 分析系统（已弃用，保留兼容性） |
| attackPlayer | boolean | true | 允许技能伤害其他玩家（PvP） |
| destroyBlocks | boolean | true | 允许技能破坏方块 |
| genOres | boolean | true | 在世界中生成矿石 |
| genPhaseLiquid | boolean | true | 在世界中生成相位液体池 |
| headsOrTails | boolean | true | 显示硬币正反面 |

### 能力配置 (Ability)

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| normalMetalBlocks | List<String> | 14个方块ID | 普通金属方块列表（磁力操控） |
| weakMetalBlocks | List<String> | 10个方块ID | 弱金属方块列表（磁力操控） |
| metalEntities | List<String> | 6个实体ID | 金属实体列表（磁力操控） |

**普通金属方块**:
- minecraft:rail, iron_bars, iron_block, iron_door, iron_trapdoor
- minecraft:anvil, chipped_anvil, damaged_anvil, cauldron
- minecraft:chain, lantern, soul_lantern
- minecraft:heavy_weighted_pressure_plate, light_weighted_pressure_plate

**弱金属方块**:
- minecraft:dispenser, hopper
- minecraft:iron_ore, deepslate_iron_ore, raw_iron_block
- minecraft:gold_ore, deepslate_gold_ore, raw_gold_block
- minecraft:copper_ore, deepslate_copper_ore, raw_copper_block

**金属实体**:
- minecraft:minecart, chest_minecart, furnace_minecart
- minecraft:hopper_minecart, tnt_minecart
- my_mod:entity_mag_hook

### CP/过载配置 (CP/Overload)

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| cpRecoverCooldown | int | 15 | CP恢复冷却时间（tick） |
| cpRecoverSpeed | double | 1.0 | CP恢复速度（每tick） |
| overloadRecoverCooldown | int | 32 | 过载恢复冷却时间（tick） |
| overloadRecoverSpeed | double | 1.0 | 过载恢复速度（每tick） |
| initCp | List<Integer> | [1800, 1800, 2800, 4000, 5800, 8000] | 各等级初始CP |
| addCp | List<Integer> | [0, 900, 1000, 1500, 1700, 12000] | 各等级额外CP |
| initOverload | List<Integer> | [100, 100, 150, 240, 350, 500] | 各等级初始过载 |
| addOverload | List<Integer> | [0, 40, 70, 80, 100, 500] | 各等级额外过载 |

### 全局计算 (Global)

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| damageScale | double | 1.0 | 全局伤害倍率 |

---

## 架构设计

### 依赖注入模式

```
┌─────────────────────────────────────────────────────────────┐
│ ac/config/gameplay.clj (游戏逻辑层)                          │
│ - 定义 *config-bridge* 动态变量                              │
│ - 提供配置访问函数                                           │
│ - 使用默认值作为fallback                                     │
└─────────────────────────────────────────────────────────────┘
                              ▲
                              │ 注入
                              │
┌─────────────────────────────────────────────────────────────┐
│ forge-1.20.1/config/gameplay_init.clj (平台初始化层)         │
│ - 在FMLCommonSetupEvent时绑定配置桥接                        │
│ - 使用alter-var-root注入配置函数映射                         │
└─────────────────────────────────────────────────────────────┘
                              ▲
                              │ 读取
                              │
┌─────────────────────────────────────────────────────────────┐
│ forge-1.20.1/config/gameplay_bridge.clj (Clojure桥接层)     │
│ - 提供Clojure友好的配置访问函数                              │
│ - 封装Java配置访问逻辑                                       │
└─────────────────────────────────────────────────────────────┘
                              ▲
                              │ 调用
                              │
┌─────────────────────────────────────────────────────────────┐
│ forge-1.20.1/config/GameplayConfig.java (Java配置层)        │
│ - 使用ForgeConfigSpec定义配置                                │
│ - 持久化到academycraft-gameplay.toml                         │
└─────────────────────────────────────────────────────────────┘
```

### 优势

1. **平台无关**: `ac`模块不依赖Forge，可以在不同平台上使用
2. **测试友好**: 可以使用默认值进行单元测试，无需Minecraft环境
3. **类型安全**: Java层提供类型检查，Clojure层提供便捷访问
4. **热重载**: ForgeConfigSpec支持配置文件热重载
5. **用户友好**: TOML格式配置文件易于编辑

---

## 使用示例

### 在技能中使用配置

```clojure
(ns cn.li.ac.content.ability.electromaster.arc-gen
  (:require [cn.li.ac.config.gameplay :as config]))

(defn can-attack-player? []
  (config/attack-player-enabled?))

(defn get-damage-multiplier []
  (config/get-damage-scale))
```

### 在方块逻辑中使用配置

```clojure
(ns cn.li.ac.content.ability.electromaster.mag-manip
  (:require [cn.li.ac.config.gameplay :as config]))

(defn is-manipulable-block? [block-id]
  (config/is-metal-block? block-id))

(defn get-normal-blocks []
  (config/get-normal-metal-blocks))
```

### 在CP系统中使用配置

```clojure
(ns cn.li.ac.content.ability.cp-system
  (:require [cn.li.ac.config.gameplay :as config]))

(defn get-max-cp [level]
  (+ (config/get-init-cp level)
     (config/get-add-cp level)))

(defn get-recover-speed []
  (config/get-cp-recover-speed))
```

---

## 配置文件示例

生成的`academycraft-gameplay.toml`文件示例:

```toml
[generic]
    #Enable analysis system (deprecated, kept for compatibility)
    analysis = false
    #Allow abilities to damage other players (PvP)
    attackPlayer = true
    #Allow abilities to destroy blocks
    destroyBlocks = true
    #Generate AcademyCraft ores in world
    genOres = true
    #Generate phase liquid pools in world
    genPhaseLiquid = true
    #Show heads or tails display for coin flip
    headsOrTails = true

[ability]
    #List of normal metal blocks for Mag Manip
    normalMetalBlocks = ["minecraft:rail", "minecraft:iron_bars", ...]
    #List of weak metal blocks for Mag Manip
    weakMetalBlocks = ["minecraft:dispenser", "minecraft:hopper", ...]
    #List of metal entities for Mag Manip
    metalEntities = ["minecraft:minecart", "my_mod:entity_mag_hook", ...]

[cpOverload]
    #Cooldown ticks before CP starts recovering
    cpRecoverCooldown = 15
    #CP recovery speed per tick
    cpRecoverSpeed = 1.0
    #Cooldown ticks before overload starts recovering
    overloadRecoverCooldown = 32
    #Overload recovery speed per tick
    overloadRecoverSpeed = 1.0
    #Initial CP values for each level (0-5)
    initCp = [1800, 1800, 2800, 4000, 5800, 8000]
    #Additional CP gained per level (0-5)
    addCp = [0, 900, 1000, 1500, 1700, 12000]
    #Initial overload values for each level (0-5)
    initOverload = [100, 100, 150, 240, 350, 500]
    #Additional overload gained per level (0-5)
    addOverload = [0, 40, 70, 80, 100, 500]

[global]
    #Global damage scale multiplier for all abilities
    damageScale = 1.0
```

---

## 待完善功能

### 低优先级

1. **配置GUI**: 游戏内配置界面（可选）
2. **网络同步**: 多人游戏配置同步（可选）
3. **配置验证**: 更严格的配置值验证
4. **配置迁移**: 旧版本配置文件迁移

---

## 总结

✅ **完成的功能**:
- ForgeConfigSpec配置定义
- 配置文件持久化（TOML格式）
- Clojure配置桥接
- 动态变量注入
- 默认值fallback
- 平台初始化集成

✅ **技术优势**:
- 平台无关设计
- 测试友好
- 类型安全
- 用户友好

✅ **配置覆盖**:
- 通用配置（6项）
- 能力配置（3个列表）
- CP/过载配置（8项）
- 全局计算（1项）

配置系统现已完全可用，玩家可以通过编辑`academycraft-gameplay.toml`文件自定义游戏体验。
