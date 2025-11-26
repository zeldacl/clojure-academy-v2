# GUI架构重构报告

**日期**: 2025-11-26  
**重构内容**: 
1. 分离屏幕创建游戏逻辑与平台特定代码
2. 分离槽位管理游戏逻辑与平台特定代码

## 目录

1. [第一次重构: 屏幕工厂](#第一次重构-屏幕工厂)
2. [第二次重构: 槽位管理器](#第二次重构-槽位管理器)
3. [重构总结](#重构总结)

---

## 第一次重构: 屏幕工厂

### 问题分析

在重构前，`create-node-screen`和`create-matrix-screen`函数包含核心游戏逻辑，但被放置在平台特定的`screen_impl.clj`中：

```clojure
;; Forge 1.16.5/screen_impl.clj
(defn create-node-screen [container player-inventory title]
  (try
    (let [clj-container (.getClojureContainer container)
          cgui-screen (node-gui/create-screen clj-container container)]
      cgui-screen)
    (catch Exception e ...)))

;; Fabric 1.20.1/screen_impl.clj  
(defn create-node-screen [handler player-inventory title]
  (try
    (let [clj-container (.getClojureContainer handler)
          cgui-screen (node-gui/create-screen clj-container handler)]
      cgui-screen)
    (catch Exception e ...)))
```

**问题**：
1. ❌ **代码重复**: 两个平台完全重复的游戏逻辑（~100行）
2. ❌ **关注点混淆**: 游戏逻辑与平台集成混在一起
3. ❌ **可维护性差**: 修改需要同步更新所有平台
4. ❌ **可测试性差**: 游戏逻辑依赖平台特定类型

### 唯一的平台差异

实际上两个平台的差异**仅限于**：
- 参数名称: `container` (Forge) vs `handler` (Fabric)
- 日志信息: "Creating Node screen" vs "Creating Node screen (Fabric)"

核心游戏逻辑完全相同：
1. 调用`.getClojureContainer()`提取Clojure容器
2. 调用`node-gui/create-screen`创建CGui屏幕
3. 错误处理和日志

## 重构方案

### 架构改进

引入**平台无关的屏幕工厂**作为抽象层：

```
Before:
  Platform Code (screen_impl.clj) 
    ├── Game Logic (create-*-screen)     ❌ 重复
    └── Registration (register-screens!) ✅ 平台特定

After:
  Core Code (screen_factory.clj)
    └── Game Logic (create-*-screen)     ✅ 共享
  
  Platform Code (screen_impl.clj)
    └── Registration (register-screens!) ✅ 平台特定
```

### 实现细节

#### 1. 新文件: screen_factory.clj (核心系统)

**位置**: `core/src/main/clojure/my_mod/wireless/gui/screen_factory.clj`

```clojure
(ns my-mod.wireless.gui.screen-factory
  "Platform-agnostic screen factory for Wireless GUI system"
  (:require [my-mod.wireless.gui.node-gui :as node-gui]
            [my-mod.wireless.gui.matrix-gui :as matrix-gui]
            [my-mod.util.log :as log]))

(defn create-node-screen
  "Create Node GUI screen (platform-agnostic)
  
  Args:
  - container-or-handler: Platform-specific wrapper (Container/ScreenHandler)
                          Must have .getClojureContainer() method
  - player-inventory: Player inventory (required by platform APIs)
  - title: Text component (required by platform APIs)
  
  Returns: CGuiScreenContainer instance or nil on error"
  [container-or-handler player-inventory title]
  (log/info "Creating Node screen (platform-agnostic factory)")
  
  (try
    (let [clj-container (.getClojureContainer container-or-handler)
          cgui-screen (node-gui/create-screen clj-container container-or-handler)]
      (log/info "Node screen created successfully")
      cgui-screen)
    (catch Exception e
      (log/error "Failed to create Node screen:" (.getMessage e))
      (.printStackTrace e)
      nil)))

(defn create-matrix-screen
  "Create Matrix GUI screen (platform-agnostic)"
  [container-or-handler player-inventory title]
  ;; Similar implementation...
  )
```

**设计要点**：
- ✅ **平台无关**: 参数命名通用（`container-or-handler`）
- ✅ **鸭子类型**: 依赖`.getClojureContainer()`接口，不依赖具体类型
- ✅ **统一错误处理**: 所有平台共享错误处理逻辑
- ✅ **清晰文档**: 说明参数要求和返回值

#### 2. 重构: screen_impl.clj (平台特定)

**Forge 1.16.5 改动**:

```clojure
(ns my-mod.forge1165.gui.screen-impl
  "Forge 1.16.5 Client-side Screen Implementation
  
  This namespace handles Forge-specific screen registration mechanics.
  Core screen creation logic is in my-mod.wireless.gui.screen-factory."
  (:require [my-mod.wireless.gui.screen-factory :as screen-factory]  ; 改动
            [my-mod.util.log :as log])
  ;; 移除 node-gui 和 matrix-gui 引用
  ...)

;; 移除 create-node-screen 和 create-matrix-screen 定义

(defn register-screens!
  "Register screen factories with Minecraft
  
  Delegates to platform-agnostic screen-factory for actual screen creation.
  This function only handles Forge-specific registration mechanics."
  []
  (log/info "Registering Wireless GUI screens for Forge 1.16.5")
  
  (try
    (let [screen-manager net.minecraft.client.gui.ScreenManager]
      (.registerFactory screen-manager
                       @NODE_MENU_TYPE
                       (reify net.minecraft.client.gui.IScreenFactory
                         (create [_ container player-inventory title]
                           (screen-factory/create-node-screen     ; 改动
                             container player-inventory title))))
      ;; Matrix screen similar...
      )))
```

**Fabric 1.20.1 改动**: 类似的重构

**改动总结**：
- ❌ 删除 `create-node-screen` 和 `create-matrix-screen` 定义（~50行）
- ✅ 添加 `screen-factory` 命名空间引用
- ✅ 调用 `screen-factory/create-node-screen`
- ✅ 保留所有平台特定注册逻辑

## 重构效果

### 代码指标

| 指标 | 重构前 | 重构后 | 改进 |
|-----|--------|--------|------|
| **screen_factory.clj** | 不存在 | 103行 | +103行 (新增) |
| **Forge screen_impl.clj** | 108行 | 60行 | -48行 (-44%) |
| **Fabric screen_impl.clj** | 130行 | 85行 | -45行 (-35%) |
| **总代码行数** | 238行 | 248行 | +10行 (+4%) |
| **重复代码** | ~50行×2 | 0行 | -100行 (-100%) |

**净效果**: 用10行额外代码消除了100行重复代码

### 质量改进

#### 1. DRY原则 (Don't Repeat Yourself)

✅ **Before**: 游戏逻辑在2个平台中重复  
✅ **After**: 游戏逻辑在1个地方实现，2个平台共享

#### 2. 关注点分离 (Separation of Concerns)

✅ **Game Logic** (screen_factory.clj):
- 提取Clojure容器
- 创建CGui屏幕
- 错误处理

✅ **Platform Integration** (screen_impl.clj):
- ScreenManager/ScreenRegistry注册
- IScreenFactory/Factory实现
- 平台初始化生命周期

#### 3. 可测试性

**Before**:
```clojure
;; 需要mock Forge的Container类型
(test-create-node-screen 
  (mock Container :getClojureContainer ...))
```

**After**:
```clojure
;; 可以用任何实现.getClojureContainer()的对象
(test-create-node-screen
  (reify Object
    (getClojureContainer [_] test-container)))
```

#### 4. 可维护性

**场景**: 需要添加新的错误处理逻辑

**Before**: 修改2个文件（Forge + Fabric）  
**After**: 修改1个文件（screen_factory）

### 设计模式应用

#### 1. 桥接模式 (Bridge Pattern)

```
Abstraction (screen_factory.clj)
  ├── create-node-screen()
  └── create-matrix-screen()
      │
      ▼
Implementation (screen_impl.clj)
  ├── Forge: ScreenManager.registerFactory
  └── Fabric: ScreenRegistry.register
```

#### 2. 工厂模式 (Factory Pattern)

```clojure
;; Factory Method
screen-factory/create-node-screen

;; Concrete Factories
Forge:  (reify IScreenFactory ...)
Fabric: (reify ScreenRegistry$Factory ...)
```

#### 3. 策略模式 (Strategy Pattern)

```clojure
;; Strategy Interface
(.getClojureContainer container-or-handler)

;; Concrete Strategies
Forge:  WirelessContainer.getClojureContainer()
Fabric: WirelessScreenHandler.getClojureContainer()
```

## 文件清单

### 修改的文件

1. ✅ **新增**: `core/src/main/clojure/my_mod/wireless/gui/screen_factory.clj` (103行)
2. ✅ **重构**: `forge-1.16.5/src/main/clojure/my_mod/forge1165/gui/screen_impl.clj` (108→60行)
3. ✅ **重构**: `fabric-1.20.1/src/main/clojure/my_mod/fabric1201/gui/screen_impl.clj` (130→85行)
4. ✅ **更新**: `minecraftmod/GUI_IMPLEMENTATION_COMPLETE.md`
5. ✅ **更新**: `WIRELESS_IMPLEMENTATION_PROGRESS.md`

### 未修改的文件

- `node_gui.clj` / `matrix_gui.clj`: GUI定义保持不变
- `bridge.clj`: Container/ScreenHandler实现保持不变
- `registry_impl.clj`: 注册逻辑保持不变
- `network.clj` / `slots.clj` / `init.clj`: 其他系统保持不变

## 后续改进建议

### 1. 添加单元测试

```clojure
(ns my-mod.wireless.gui.screen-factory-test
  (:require [clojure.test :refer :all]
            [my-mod.wireless.gui.screen-factory :as factory]))

(deftest test-create-node-screen-success
  (let [mock-container (reify Object
                         (getClojureContainer [_] 
                           {:type :node :energy 1000}))
        result (factory/create-node-screen mock-container nil nil)]
    (is (some? result))))

(deftest test-create-node-screen-error
  (let [bad-container (reify Object)  ; 没有getClojureContainer
        result (factory/create-node-screen bad-container nil nil)]
    (is (nil? result))))  ; 应该返回nil而不是抛异常
```

### 2. 考虑协议抽象

如果未来需要更多平台特定行为：

```clojure
(defprotocol IContainerWrapper
  (get-clojure-container [this])
  (get-platform-name [this]))

;; 平台实现
(extend-protocol IContainerWrapper
  net.minecraft.inventory.container.Container
  (get-clojure-container [this] (.getClojureContainer this))
  (get-platform-name [_] "Forge")
  
  net.minecraft.screen.ScreenHandler
  (get-clojure-container [this] (.getClojureContainer this))
  (get-platform-name [_] "Fabric"))
```

### 3. 日志级别优化

考虑将成功日志降级为DEBUG，减少噪音：

```clojure
(log/debug "Node screen created successfully")  ; 而不是 info
```

## 总结

### 成就

✅ **消除重复**: 100行重复代码归并到单一实现  
✅ **清晰架构**: 游戏逻辑与平台集成明确分离  
✅ **提高可维护性**: 单点修改，全平台生效  
✅ **增强可测试性**: 核心逻辑无平台依赖  
✅ **保持兼容性**: 现有API和行为完全不变

### 经验教训

1. **识别抽象点**: `.getClojureContainer()`是平台差异的关键抽象
2. **渐进式重构**: 先创建新抽象，再迁移平台代码，最后清理
3. **文档驱动**: 通过文档说明设计意图和使用方式

### 下一步

此重构为未来扩展奠定了基础：
- 添加新平台（如Forge 1.18.2）只需实现`screen_impl.clj`
- 修改屏幕创建逻辑只需修改`screen_factory.clj`
- 所有平台自动继承改进

---

**重构完成**: 2025-11-26  
**状态**: ✅ 屏幕工厂重构完成

---

## 第二次重构: 槽位管理器

### 问题分析

#### 原始问题

在第一次重构后，发现 `quickMoveStack` (Forge) 和 `quickMove` (Fabric) 方法中仍然包含游戏逻辑：

```clojure
;; Forge 1.16.5/bridge.clj
(defn -quickMoveStack [this player slot-index]
  (cond
    ;; Node container: 2 slots (0-1 tile, 2-38 player)
    (instance? NodeContainer clj-container)
    (if (< slot-index 2)
      ;; From tile to player
      (if (.moveItemStackTo this stack 2 38 true) ...)
      ;; From player to tile
      (if (.moveItemStackTo this stack 0 2 false) ...))
    
    ;; Matrix container: 4 slots (0-3 tile, 4-40 player)
    (instance? MatrixContainer clj-container)
    (if (< slot-index 4)
      (if (.moveItemStackTo this stack 4 40 true) ...)
      (if (.moveItemStackTo this stack 0 4 false) ...))
    ...))

;; Fabric 1.20.1/bridge.clj - 完全相同的逻辑
(defn -quickMove [this player slot-index]
  (cond
    (instance? NodeContainer clj-container)
    (if (< slot-index 2)
      (if (.insertItem this stack 2 38 true) ...)
      (if (.insertItem this stack 0 2 false) ...))
    ...))
```

**问题**:
1. ❌ **硬编码槽位范围**: Node (0-1 tile, 2-38 player), Matrix (0-3 tile, 4-40 player)
2. ❌ **重复的游戏逻辑**: 两个平台完全相同的判断逻辑（~30行）
3. ❌ **关注点混淆**: 槽位布局信息与平台API调用混在一起
4. ❌ **可维护性差**: 修改槽位布局需要同步更新所有平台

#### 唯一的平台差异

实际上两个平台的差异**仅限于API调用**：
- Forge: `.moveItemStackTo(stack, start, end, reverse)` + `.setChanged(slot)`
- Fabric: `.insertItem(stack, start, end, reverse)` + `.markDirty(slot)`

核心游戏逻辑完全相同：
1. 判断槽位索引属于tile还是player
2. 确定目标槽位范围
3. 确定插入方向（reverse）

### 重构方案

#### 架构改进

引入**平台无关的槽位管理器**作为抽象层：

```
Before:
  Forge Bridge (bridge.clj)
    ├── Slot Layout Logic         ❌ 硬编码
    ├── Quick-Move Strategy       ❌ 重复
    └── .moveItemStackTo API      ✅ 平台特定
  
  Fabric Bridge (bridge.clj)
    ├── Slot Layout Logic         ❌ 硬编码
    ├── Quick-Move Strategy       ❌ 重复
    └── .insertItem API           ✅ 平台特定

After:
  Core Code (slot_manager.clj)
    ├── Slot Layout Constants     ✅ 集中定义
    ├── Slot Range Queries        ✅ 共享
    ├── Quick-Move Strategy       ✅ 共享
    └── Platform Helpers          ✅ 桥接
  
  Forge Bridge (bridge.clj)
    └── Call slot-manager helper  ✅ 平台特定
  
  Fabric Bridge (bridge.clj)
    └── Call slot-manager helper  ✅ 平台特定
```

### 实现细节

#### 1. 新文件: slot_manager.clj (核心系统)

**位置**: `core/src/main/clojure/my_mod/wireless/gui/slot_manager.clj`

```clojure
(ns my-mod.wireless.gui.slot-manager
  "Platform-agnostic slot layout and quick-move logic"
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]))

;; Slot Layout Constants
(def node-tile-slots {:start 0 :end 2 :count 2})
(def node-player-slots {:start 2 :end 38 :count 36})
(def matrix-tile-slots {:start 0 :end 4 :count 4})
(def matrix-player-slots {:start 4 :end 40 :count 36})

;; Slot Range Queries
(defn get-tile-slot-range [container] ...)
(defn get-player-slot-range [container] ...)
(defn is-tile-slot? [container slot-index] ...)
(defn is-player-slot? [container slot-index] ...)

;; Quick-Move Strategy
(defn get-quick-move-strategy [container slot-index]
  "Returns: {:target-start int :target-end int :reverse boolean}"
  ...)

;; Platform Bridge Helpers
(defn execute-quick-move-forge [container-wrapper clj-container slot-index slot stack]
  "Uses .moveItemStackTo + .setChanged"
  ...)

(defn execute-quick-move-fabric [handler-wrapper clj-container slot-index slot stack]
  "Uses .insertItem + .markDirty"
  ...)
```

**设计要点**:
- ✅ **集中定义**: 所有槽位布局常量在一处
- ✅ **查询API**: is-tile-slot?, is-player-slot?, get-*-slot-range
- ✅ **策略模式**: get-quick-move-strategy返回移动策略
- ✅ **平台桥接**: execute-quick-move-* 封装平台API差异

#### 2. 重构: Forge bridge.clj

**改动前** (40行游戏逻辑):
```clojure
(defn -quickMoveStack [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          (cond
            (instance? NodeContainer clj-container)
            (if (< slot-index 2)
              (if (.moveItemStackTo this stack 2 38 true)
                (do (.setChanged slot) EMPTY)
                stack)
              (if (.moveItemStackTo this stack 0 2 false)
                (do (.setChanged slot) EMPTY)
                stack))
            
            (instance? MatrixContainer clj-container)
            (if (< slot-index 4)
              (if (.moveItemStackTo this stack 4 40 true)
                (do (.setChanged slot) EMPTY)
                stack)
              (if (.moveItemStackTo this stack 0 4 false)
                (do (.setChanged slot) EMPTY)
                stack))
            
            :else stack))
        EMPTY))
    (catch Exception e ...)))
```

**改动后** (10行委托):
```clojure
(ns my-mod.forge1165.gui.bridge
  (:require [my-mod.wireless.gui.slot-manager :as slot-manager]  ; 新增
            ...))

(defn -quickMoveStack [this player slot-index]
  (try
    (let [slot (.getSlot this slot-index)]
      (if (and slot (.hasItem slot))
        (let [stack (.getItem slot)
              clj-container (-getClojureContainer this)]
          ;; 委托给slot-manager
          (slot-manager/execute-quick-move-forge 
            this clj-container slot-index slot stack))
        EMPTY))
    (catch Exception e ...)))
```

**改动总结**:
- ❌ 删除 40行硬编码槽位逻辑
- ✅ 添加 slot-manager 引用
- ✅ 调用 execute-quick-move-forge
- ✅ 保持异常处理

#### 3. 重构: Fabric bridge.clj

**改动类似**, Fabric 的 `quickMove` 从 40行减少到 10行。

### 重构效果

#### 代码指标

| 指标 | 重构前 | 重构后 | 改进 |
|-----|--------|--------|------|
| **slot_manager.clj** | 不存在 | 180行 | +180行 (新增) |
| **Forge bridge.clj** | 250行 | 220行 | -30行 (-12%) |
| **Fabric bridge.clj** | 323行 | 293行 | -30行 (-9%) |
| **重复代码** | ~30行×2 | 0行 | -60行 (-100%) |

**净效果**: 用180行新代码消除了60行重复代码，并提供了可复用的槽位管理API

#### 质量改进

1. **DRY原则**
   - ✅ **Before**: 槽位范围在2个平台中硬编码
   - ✅ **After**: 槽位范围在1个地方定义，2个平台共享

2. **关注点分离**
   - ✅ **Slot Layout** (slot_manager.clj): 槽位常量、范围查询
   - ✅ **Quick-Move Strategy** (slot_manager.clj): 移动方向和目标
   - ✅ **Platform API** (bridge.clj): .moveItemStackTo/.insertItem调用

3. **可扩展性**
   - 添加新容器类型只需在 slot_manager.clj 中添加常量
   - 新平台只需实现 execute-quick-move-* helper
   - 槽位布局修改只需更新常量定义

4. **可测试性**
   ```clojure
   ;; 可以独立测试槽位查询
   (is (= {:start 0 :end 2} (get-tile-slot-range node-container)))
   (is (true? (is-tile-slot? node-container 0)))
   (is (false? (is-tile-slot? node-container 2)))
   
   ;; 可以独立测试移动策略
   (is (= {:target-start 2 :target-end 38 :reverse true}
          (get-quick-move-strategy node-container 0)))
   ```

### 文件清单

#### 修改的文件

1. ✅ **新增**: `core/my_mod/wireless/gui/slot_manager.clj` (180行)
   - 槽位布局常量 (node/matrix × tile/player)
   - 槽位范围查询函数 (get-*-slot-range, is-*-slot?)
   - 快速移动策略函数 (get-quick-move-strategy)
   - 平台桥接helper (execute-quick-move-forge/fabric)

2. ✅ **重构**: `forge-1.16.5/gui/bridge.clj` (250→220行)
   - 添加 slot-manager 引用
   - quickMoveStack委托给 execute-quick-move-forge
   - 移除硬编码槽位逻辑

3. ✅ **重构**: `fabric-1.20.1/gui/bridge.clj` (323→293行)
   - 添加 slot-manager 引用
   - quickMove委托给 execute-quick-move-fabric
   - 移除硬编码槽位逻辑

### 设计模式应用

1. **策略模式 (Strategy Pattern)**
   ```
   Context: Container (Node/Matrix)
   Strategy: get-quick-move-strategy
   Concrete Strategies: 
     - Tile→Player (reverse=true)
     - Player→Tile (reverse=false)
   ```

2. **桥接模式 (Bridge Pattern)**
   ```
   Abstraction: slot-manager (platform-agnostic logic)
   Implementation: execute-quick-move-forge/fabric
   ```

3. **适配器模式 (Adapter Pattern)**
   ```
   Target Interface: execute-quick-move-*
   Adaptee: .moveItemStackTo (Forge) / .insertItem (Fabric)
   ```

---

## 重构总结

### 两次重构对比

| 维度 | 第一次重构 (屏幕工厂) | 第二次重构 (槽位管理器) |
|------|---------------------|----------------------|
| **目标代码** | create-*-screen函数 | quickMoveStack/quickMove方法 |
| **重复行数** | ~100行×2平台 | ~30行×2平台 |
| **新增文件** | screen_factory.clj (103行) | slot_manager.clj (180行) |
| **消除重复** | 100行 | 60行 |
| **净增代码** | +10行 | +120行 |

### 累计效果

#### 代码指标

| 文件 | 原始 | 第一次重构后 | 第二次重构后 | 总改进 |
|-----|------|-------------|-------------|--------|
| **核心系统** | - | +103行 | +283行 | +283行 |
| **Forge bridge** | 250行 | 250行 | 220行 | -30行 |
| **Forge screen_impl** | 108行 | 60行 | 60行 | -48行 |
| **Fabric bridge** | 323行 | 323行 | 293行 | -30行 |
| **Fabric screen_impl** | 130行 | 85行 | 85行 | -45行 |
| **总重复消除** | - | -100行 | -160行 | **-160行** |

#### 架构改进

✅ **第一次重构成果**:
- 屏幕创建逻辑平台无关化
- 引入 screen_factory.clj 作为抽象层
- 桥接模式: 抽象(factory) vs 实现(platform)

✅ **第二次重构成果**:
- 槽位管理逻辑平台无关化
- 引入 slot_manager.clj 作为抽象层
- 策略模式: 快速移动策略可配置
- 适配器模式: 统一平台API差异

✅ **综合架构**:
```
Core (Platform-Agnostic)
├── screen_factory.clj    # 屏幕创建游戏逻辑
├── slot_manager.clj      # 槽位管理游戏逻辑
├── node_gui.clj          # CGui界面定义
├── matrix_gui.clj        # CGui界面定义
├── node_container.clj    # 容器逻辑
└── matrix_container.clj  # 容器逻辑

Platform (Forge/Fabric)
├── bridge.clj            # Java包装器 (仅平台API调用)
├── registry_impl.clj     # 注册逻辑 (平台特定)
├── screen_impl.clj       # 屏幕注册 (委托给factory)
├── network.clj           # 网络包 (平台API)
├── slots.clj             # Slot实现 (平台API)
└── init.clj              # 初始化流程
```

### 最终收益

1. **DRY原则**: 消除160行跨平台重复代码
2. **关注点分离**: 游戏逻辑 vs 平台集成清晰划分
3. **可维护性**: 
   - 屏幕创建修改: 1个文件 (screen_factory)
   - 槽位布局修改: 1个文件 (slot_manager)
4. **可测试性**: 核心逻辑无平台依赖
5. **可扩展性**: 添加新平台只需实现桥接层

### 经验教训

1. **识别抽象点**: 
   - `.getClojureContainer()` → 屏幕工厂
   - 槽位范围/移动策略 → 槽位管理器

2. **渐进式重构**: 
   - 第一次: 发现屏幕创建重复 → 创建factory
   - 第二次: 发现槽位逻辑重复 → 创建manager

3. **设计模式**: 
   - 桥接模式分离抽象和实现
   - 策略模式封装算法变化
   - 适配器模式统一接口差异

4. **文档驱动**: 每次重构都更新文档，保持知识同步

---

**重构完成**: 2025-11-26  
**状态**: ✅ 所有重构完成，文档已同步
