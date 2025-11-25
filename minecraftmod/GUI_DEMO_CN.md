# Demo Mod 使用说明

## 功能概述

这个 Demo 演示了一个完整的 Clojure Mod，包含：

1. **一个自定义方块** (`demo_block`)
2. **一个自定义物品** (`demo_item`)
3. **交互式 GUI**：
   - 右键点击 `demo_block` 打开 GUI
   - GUI 包含一个物品槽
   - 一个"销毁"按钮可以清空槽位中的物品

## 当前实现状态

### ✅ 已完成的 Clojure 代码

#### Core 层（版本无关）
- `my-mod.gui.core`：GUI 核心逻辑
  - `gui-slots` atom：存储 GUI 槽位状态
  - `register-gui-slot`：注册槽位和物品
  - `clear-slot`：清空槽位（销毁物品）
  - `on-destroy-button-clicked`：按钮点击处理
  - `validate-gui-open`：验证 GUI 是否可以打开

- `my-mod.core`：主逻辑
  - `on-block-right-click`：右键点击方块时调用 `gui-api/open-gui`

#### Forge 1.16.5 适配层
- `my-mod.forge1165.events`：
  - `handle-right-click`：检测是否点击了 `demo_block`，触发 GUI 打开

- `my-mod.forge1165.gui.impl`：
  - `on-button-clicked`：处理按钮点击，调用 `gui-core/on-destroy-button-clicked`
  - `on-slot-changed`：监听槽位变化
  - `create-menu-title`：生成 GUI 标题
  - `get-slot-count`：返回槽位数量

#### Forge 1.20.1 适配层
- 与 1.16.5 相同的实现，针对 1.20.1 API 调整

### ⚠️ 需要 Java 层配合的部分

由于 Forge 的 GUI 系统深度依赖 Java API（Menu/Screen/Networking），以下功能需要 Java 代码配合：

1. **Menu/Container 类**：
   - 管理槽位（`ItemStackHandler` + `Slot`）
   - 实现 `quickMoveStack`（Shift+点击）
   - 调用 Clojure 的 `on-button-clicked`

2. **Screen 类**：
   - 渲染 GUI 背景和按钮
   - 发送网络包到服务端

3. **MenuType 注册**：
   - 使用 `DeferredRegister` 注册 `ContainerType`
   - 在客户端注册 Screen

4. **网络通信**：
   - 定义 Packet 类处理按钮点击
   - 使用 `SimpleChannel` 注册

## Clojure 代码工作流程

```
1. 玩家右键点击方块
   ↓
2. Java: MyMod1165.onRightClickBlock
   ↓
3. Clojure: my-mod.forge1165.events/handle-right-click
   - 检查是否为 demo_block
   ↓
4. Clojure: my-mod.core/on-block-right-click
   - 调用 gui-core/validate-gui-open
   - 调用 gui-api/open-gui (multimethod)
   ↓
5. Clojure: my-mod.forge1165.gui.impl/open-gui
   - 返回 {:gui-id 1 :pos [...]}
   ↓
6. [需要 Java] NetworkHooks.openGui (实际打开 GUI)
   ↓
7. 玩家在 GUI 中放入物品
   ↓
8. Clojure: my-mod.forge1165.gui.impl/on-slot-changed
   - 更新 gui-slots atom
   ↓
9. 玩家点击"销毁"按钮
   ↓
10. [需要 Java] Packet 发送到服务端
    ↓
11. Clojure: my-mod.forge1165.gui.impl/on-button-clicked
    ↓
12. Clojure: my-mod.gui.core/on-destroy-button-clicked
    - 清空 gui-slots atom
    ↓
13. [需要 Java] Menu.destroyItem 清空 ItemStackHandler
```

## 当前可测试的功能

运行游戏后：

```
/give @p my_mod:demo_item
/give @p my_mod:demo_block
```

放置 `demo_block`，右键点击它，控制台会输出：

```
[my_mod] 1.16.5 Right-click event at (x, y, z) block: Block{my_mod:demo_block}
[my_mod] Demo block detected! Triggering GUI open logic...
[my_mod] Right-click on block at (x, y, z) by player
[my_mod] Validating GUI open for player at [x y z]
[my_mod] Opening demo GUI for player at block position
[my_mod] Opening GUI 1 for player at [x y z] (1.16.5)
```

## 完整实现 GUI 的选项

### 选项 1：纯 Clojure（推荐用于学习）
使用 Clojure 的 `gen-class` 或 `proxy` 生成 Java 类：

```clojure
(ns my-mod.forge1165.gui.menu
  (:gen-class
    :name com.example.DemoMenu
    :extends net.minecraft.inventory.container.Container
    :constructors {[int PlayerInventory BlockPos] [ContainerType]}
    :init init
    :state state))
```

**优点**：完全使用 Clojure
**缺点**：复杂，需要深入理解 Java 互操作

### 选项 2：Java + Clojure 混合（本框架当前方式）
- Java 类处理 Forge API（Menu, Screen, Networking）
- Clojure 处理业务逻辑（按钮处理、数据管理）

**优点**：类型安全，易调试
**缺点**：需要编写 Java 代码

### 选项 3：使用 Clojure 库包装 Forge API
创建一个 Clojure DSL 来简化 GUI 定义：

```clojure
(defgui demo-gui
  :slots [{:index 0 :x 80 :y 35}]
  :buttons [{:id 0 :x 120 :y 30 :text "Destroy"
             :on-click #(clear-slot 0)}])
```

**优点**：声明式，易维护
**缺点**：需要大量前期工作

## 下一步

如果你想完整实现 GUI（包含实际的界面和按钮），我可以：

1. **补充 Java GUI 类**：提供 `DemoMenu.java` 和 `DemoScreen.java`，它们会调用 Clojure 函数
2. **纯 Clojure 方案**：使用 `gen-class` 实现完整的 Clojure GUI
3. **创建 GUI DSL**：设计一个 Clojure 宏来简化 GUI 定义

请告诉我你希望采用哪种方式！
