# GUI DSL 使用指南

## 概述

本项目实现了一个完整的 **Clojure GUI DSL**（领域特定语言），用于声明式定义 Minecraft Mod 的 GUI 界面。

## 核心模块

### 1. `my-mod.gui.dsl` - DSL 核心

提供声明式 GUI 定义宏和运行时管理。

#### 主要功能

```clojure
;; 定义 GUI
(dsl/defgui my-gui
  :title "My GUI"
  :width 176
  :height 166
  :slots [{:index 0 :x 80 :y 35}]
  :buttons [{:id 0 :x 120 :y 30 :text "OK" :on-click #(println "OK")}]
  :labels [{:x 8 :y 6 :text "Title"}])

;; 获取 GUI
(dsl/get-gui "my-gui")

;; 列出所有 GUI
(dsl/list-guis)

;; 创建 GUI 实例
(dsl/create-gui-instance my-gui player world pos)
```

#### 组件规范

**Slot（槽位）**：
```clojure
{:index 0          ; 槽位索引（必需）
 :x 80             ; X 坐标
 :y 35             ; Y 坐标
 :filter fn        ; 过滤函数（判断是否接受物品）
 :on-change fn}    ; 变化回调 (fn [old new])
```

**Button（按钮）**：
```clojure
{:id 0             ; 按钮 ID（必需）
 :x 120            ; X 坐标
 :y 30             ; Y 坐标
 :width 60         ; 宽度（默认 60）
 :height 20        ; 高度（默认 20）
 :text "Button"    ; 按钮文本
 :on-click fn}     ; 点击回调 (fn [])
```

**Label（标签）**：
```clojure
{:x 8              ; X 坐标
 :y 6              ; Y 坐标
 :text "Label"     ; 文本内容
 :color 0x404040}  ; 颜色（默认深灰）
```

### 2. `my-mod.gui.renderer` - 渲染抽象

提供跨版本的渲染抽象层。

#### Multimethod 接口

```clojure
;; 创建渲染上下文
(renderer/create-render-context graphics gui-instance)

;; 渲染 GUI 各部分
(renderer/render-gui-background render-ctx gui-spec left-pos top-pos)
(renderer/render-gui-slots render-ctx gui-instance left-pos top-pos)
(renderer/render-gui-buttons render-ctx gui-instance left-pos top-pos mouse-x mouse-y)
(renderer/render-gui-labels render-ctx gui-spec left-pos top-pos)
(renderer/render-gui-tooltips render-ctx gui-instance mouse-x mouse-y)

;; 高级渲染（一次性调用）
(renderer/render-gui graphics gui-instance left-pos top-pos mouse-x mouse-y)
```

#### 点击检测

```clojure
;; 检测按钮点击
(renderer/find-clicked-button gui-spec left-pos top-pos mouse-x mouse-y)

;; 检测槽位点击
(renderer/find-clicked-slot gui-spec left-pos top-pos mouse-x mouse-y)
```

### 3. `my-mod.gui.container` - 容器管理

管理服务端 GUI 状态和槽位数据。

#### 容器操作

```clojure
;; 创建容器
(container/create-container container-id gui-instance player)

;; 注册容器
(container/register-container! container-id container)

;; 获取容器
(container/get-container container-id)

;; 槽位操作
(container/get-slot-item container slot-index)
(container/set-slot-item! container slot-index item-stack)
(container/clear-slot! container slot-index)

;; 按钮操作
(container/handle-button-click! container button-id)
```

#### 平台特定实现

```clojure
;; 版本适配器需要实现
(defmethod container/create-platform-container :forge-1.16.5 [...]
  ;; Forge 1.16.5 实现
)

(defmethod container/open-gui-container :fabric-1.20.1 [...]
  ;; Fabric 1.20.1 实现
)
```

### 4. `my-mod.gui.network` - 网络通信

处理客户端-服务端 GUI 通信。

#### 数据包创建

```clojure
;; 按钮点击包
(network/button-click-packet container-id button-id)

;; 槽位变化包
(network/slot-change-packet container-id slot-index item-stack)

;; 打开 GUI 包
(network/open-gui-packet gui-id world-pos)
```

#### 发送数据包

```clojure
;; 发送到服务端
(network/send-to-server packet)

;; 发送到客户端
(network/send-to-client packet player)
```

#### 注册处理器

```clojure
;; 初始化默认处理器
(network/init-default-handlers!)

;; 自定义处理器
(network/register-handler! :custom-packet
  (fn [data player]
    (println "Custom packet:" data)))
```

### 5. `my-mod.gui.demo` - 示例 GUI

提供多个实际可用的 GUI 示例。

#### Demo GUI（基础示例）

```clojure
demo-gui
- 1 个槽位（放置物品）
- 1 个"Destroy"按钮（清空槽位）
- 2 个标签
```

#### Crafting GUI（合成台）

```clojure
crafting-gui
- 9 个输入槽位（3x3 网格）
- 1 个输出槽位
- "Craft"按钮（执行合成）
- "Clear"按钮（清空所有输入）
```

#### Furnace GUI（熔炉）

```clojure
furnace-gui
- 输入槽位（可熔炼物品）
- 燃料槽位
- 输出槽位（只能取出，不能放入）
- "Start"按钮（开始熔炼）
```

#### Storage GUI（存储箱）

```clojure
storage-gui
- 54 个槽位（6x9 网格）
- "Sort"按钮（整理物品）
- "Clear"按钮（清空所有槽位）
```

## 完整使用示例

### 1. 定义 GUI

```clojure
(ns my-mod.custom-gui
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.util.log :as log]))

(defonce my-slots (atom {}))

(dsl/defgui enchanting-table
  :title "Enchanting Table"
  :width 176
  :height 166
  :slots [{:index 0 
           :x 15 
           :y 47
           :filter (fn [item] 
                     ;; Only accept enchantable items
                     (.isEnchantable item))
           :on-change (dsl/slot-change-handler my-slots 0)}
          {:index 1 
           :x 35 
           :y 47
           :filter (fn [item]
                     ;; Only accept lapis lazuli
                     (= (.getItem item) Items/LAPIS_LAZULI))
           :on-change (dsl/slot-change-handler my-slots 1)}]
  :buttons (vec
            (for [i (range 3)]
              {:id i
               :x 60
               :y (+ 14 (* i 19))
               :width 108
               :height 17
               :text (str "Enchantment " (inc i))
               :on-click #(do
                            (log/info "Applied enchantment" (inc i))
                            (swap! my-slots dissoc 0 1))}))
  :labels [{:x 8 :y 6 :text "Enchant"}])
```

### 2. 在游戏中打开 GUI

```clojure
(ns my-mod.forge1165.gui.impl
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.gui.container :as container]
            [my-mod.custom-gui :as custom]))

(defmethod container/open-gui-container :forge-1.16.5
  [player gui-spec world pos]
  ;; 创建 GUI 实例
  (let [gui-instance (dsl/create-gui-instance gui-spec player world pos)
        container (container/create-container (java.util.UUID/randomUUID)
                                               gui-instance
                                               player)]
    ;; 注册容器
    (container/register-container! (:id container) container)
    
    ;; 使用 Forge API 打开 GUI
    ;; TODO: 调用 NetworkHooks.openGui
    ))

;; 在事件处理中打开
(defn handle-right-click-block [event]
  (let [player (.getPlayer event)
        world (.getWorld event)
        pos (.getPos event)]
    (container/open-gui-container player 
                                   custom/enchanting-table
                                   world 
                                   pos)))
```

### 3. 处理按钮点击（客户端）

```clojure
;; 在渲染类中
(defn on-mouse-clicked [mouse-x mouse-y button]
  (when (= button 0) ; 左键
    (when-let [btn-id (renderer/find-clicked-button 
                        (:spec gui-instance) 
                        left-pos 
                        top-pos 
                        mouse-x 
                        mouse-y)]
      ;; 发送数据包到服务端
      (network/send-to-server
        (network/button-click-packet container-id btn-id)))))
```

### 4. 处理按钮点击（服务端）

```clojure
;; 网络处理器会自动调用
(network/register-handler! :button-click
  (fn [data player]
    (let [{:keys [container-id button-id]} data
          container (container/get-container container-id)]
      (when (container/validate-container container player)
        ;; 执行按钮的 on-click 回调
        (dsl/handle-button-click (:gui-instance container) button-id)))))
```

## 高级特性

### 1. 动态按钮状态

```clojure
;; 创建可禁用的按钮
(dsl/defgui advanced-gui
  :buttons [{:id 0 
             :text "Process"
             :on-click (fn []
                         ;; 处理逻辑
                         )}])

;; 禁用/启用按钮
(dsl/set-button-enabled! gui-instance 0 false)
(dsl/button-enabled? gui-instance 0) ; => false
```

### 2. 复杂的槽位过滤

```clojure
(defn ore-only-filter [item-stack]
  (let [item (.getItem item-stack)
        registry-name (str (.getRegistryName item))]
    (or (.contains registry-name "ore")
        (.contains registry-name "ingot"))))

(dsl/defgui smelter-gui
  :slots [{:index 0
           :filter ore-only-filter
           :on-change (fn [old new]
                        (log/info "Ore changed:" old "->" new))}])
```

### 3. 处理逻辑

```clojure
;; 使用 DSL 提供的 processing-handler
(defonce processor-slots (atom {}))

(defn combine-items [item1 item2]
  ;; 合成逻辑
  (log/info "Combining" item1 "and" item2)
  ;; 返回结果物品
  item1)

(dsl/defgui processor-gui
  :slots [{:index 0 :x 30 :y 30}  ; Input 1
          {:index 1 :x 50 :y 30}  ; Input 2
          {:index 2 :x 110 :y 30}] ; Output
  :buttons [{:id 0
             :text "Process"
             :on-click (dsl/processing-handler 
                         processor-slots 
                         [0 1]        ; Input slots
                         2            ; Output slot
                         combine-items)}]) ; Process function
```

## 架构优势

### ✅ 纯 Clojure 实现
- 所有 GUI 定义和逻辑都是 Clojure
- 无需编写 Java 代码（除了平台桥接）

### ✅ 声明式语法
- GUI 定义简洁直观
- 易于理解和维护

### ✅ 跨版本支持
- 通过 multimethod 分发版本特定实现
- 核心逻辑完全共享

### ✅ 类型安全
- 使用 defrecord 定义数据结构
- 编译时验证

### ✅ 热重载友好
- 使用 defonce 保护状态
- 支持 REPL 开发

## 版本适配器实现示例

版本特定的代码只需要实现渲染和容器创建：

```clojure
;; Forge 1.16.5 渲染实现
(defmethod renderer/render-gui-background :forge-1.16.5
  [render-ctx gui-spec left-pos top-pos]
  ;; 使用 Forge 1.16.5 的 MatrixStack 渲染背景
  )

;; Fabric 1.20.1 容器实现
(defmethod container/create-platform-container :fabric-1.20.1
  [container-id gui-spec player world pos]
  ;; 使用 Fabric 的 ScreenHandler API
  )
```

## 总结

通过这套 GUI DSL 系统，我们实现了：

1. **完全 Clojure 化**：GUI 定义和逻辑 100% Clojure
2. **声明式编程**：使用宏简化 GUI 定义
3. **模块化设计**：DSL、渲染、容器、网络各司其职
4. **跨平台抽象**：通过 multimethod 支持多版本
5. **实用示例**：提供多个完整可用的 GUI 模板

这是一个生产级的 GUI 框架，可以用于实际 Mod 开发！
