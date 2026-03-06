# Wireless Node GUI 原始实现分析

## 概述

本文档分析 AcademyCraft 的 Wireless Node GUI 原始实现（Java + Scala），为移植到 Clojure 框架提供参考。

## 原始架构

### 1. 核心组件

#### 1.1 容器类 - ContainerNode.java

**职责**: 服务端容器，管理库存和数据同步

```java
public class ContainerNode extends TechUIContainer<TileNode> {
    public ContainerNode(TileNode _node, EntityPlayer player) {
        super(player, _node);
        initInventory();
    }
    
    private void initInventory() {
        // 两个插槽：输入和输出
        this.addSlotToContainer(new SlotIFItem(tile, 0, 42, 10));   // 输入（充电）
        this.addSlotToContainer(new SlotIFItem(tile, 1, 42, 80));   // 输出（放电）
        
        mapPlayerInventory(); // 添加玩家背包插槽
        
        // 快速移动规则
        SlotGroup gBatteries = gSlots(0, 1);
        SlotGroup gInv = gRange(2, 2+36);
        
        addTransferRule(gBatteries, gInv);
        addTransferRule(gInv, IFItemManager.instance::isSupported, gBatteries);
    }
}
```

**关键特性**:
- 继承自 `TechUIContainer<TileNode>`
- 2个特殊插槽：只接受能量物品（IFItem）
- 快速移动规则：能量物品可以在背包和电池插槽间移动

**Clojure 对应实现**: `wireless/gui/node_container.clj`
- ✅ 已实现基本容器结构
- ✅ 已实现插槽验证逻辑（`can-place-item?`）
- ✅ 已实现数据同步（`sync-to-client!`）

---

#### 1.2 GUI 类 - GuiNode.scala

**职责**: 客户端GUI，渲染界面和处理交互

**架构分析**:

```scala
object GuiNode {
  def apply(container: ContainerNode) = {
    val tile = container.tile
    val thePlayer = Minecraft.getMinecraft.player
    
    // 1. 状态管理
    var state = STATE_UNLINKED  // 连接状态：已连接/未连接
    
    // 2. 库存页面（Inventory Page）
    val invPage = InventoryPage("node")
    
    // 3. 动画区域（Animation Area）
    {
      val animArea = new Widget().pos(42, 35.5f).size(186, 75).scale(0.5f)
      var stateContext = StateContext(getState, 0)
      
      animArea.listens[FrameEvent](() => {
        stateContext.updateAndDraw(width, height) // 每帧更新动画
      })
      
      invPage.window :+ animArea
    }
    
    // 4. 无线页面（Wireless Page）
    val wirelessPage = WirelessPage.nodePage(tile)
    
    // 5. 创建容器UI（多页面）
    val ret = new ContainerUI(container, invPage, wirelessPage)
    
    // 6. 信息页面（Info Page）
    {
      var load = 1
      send(MSG_INIT, tile, Future.create2((cap: Int) => load = cap))
      
      ret.infoPage
        .histogram(
          TechUI.histEnergy(() => tile.getEnergy, tile.getMaxEnergy),
          TechUI.histCapacity(() => load, tile.getCapacity))
        .seplineInfo()
        .property("range", tile.getRange)
        .property("owner", tile.getPlacerName)
        .property("node_name", tile.getNodeName, newName => send(MSG_RENAME, ...))
        .property("password", tile.getPassword, newPass => send(MSG_CHANGE_PASS, ...))
    }
    
    // 7. 网络状态轮询
    {
      var time = GameTimer.getTime - 2
      ret.main.listens[FrameEvent](() => {
        val dt = GameTimer.getTime - time
        if (dt > 2) {
          send(MSG_QUERY_LINK, tile, Future.create2((res: Boolean) => 
            state = if (res) STATE_LINKED else STATE_UNLINKED))
          time = GameTimer.getTime
        }
      })
    }
    
    ret
  }
}
```

**关键特性**:

1. **多页面系统** - 3个页面：
   - `invPage` - 库存页面（显示插槽）
   - `wirelessPage` - 无线连接页面
   - `infoPage` - 信息/直方图页面

2. **动画系统**:
   - 状态驱动：`STATE_LINKED` / `STATE_UNLINKED`
   - 帧动画：10帧总帧数，根据状态循环播放
   - 呼吸效果：`breatheAlpha` 透明度动画

3. **网络同步**:
   - `MSG_INIT` - 初始化时获取负载
   - `MSG_RENAME` - 重命名节点
   - `MSG_CHANGE_PASS` - 修改密码
   - `MSG_QUERY_LINK` - 查询连接状态（每2秒轮询）

4. **信息显示**:
   - 直方图：能量/容量
   - 属性：范围、所有者、节点名、密码

**Clojure 对应实现**: `wireless/gui/node_gui.clj`
- ✅ 已实现基本GUI结构
- ⚠️ 未实现动画系统
- ⚠️ 未实现无线页面集成
- ⚠️ 未使用XML布局

---

### 2. XML 布局系统

#### 2.1 XML 布局文件 - page_inv.xml

**分析**:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Root>
  <Widget name="main">
    <!-- 背景层 -->
    <Component class="cn.lambdalib2.cgui.component.Transform">
      <width>176.0</width>
      <height>187.0</height>
      ...
    </Component>
    <Component class="cn.lambdalib2.cgui.component.DrawTexture">
      <texture>academy:textures/guis/parent/parent_background.png</texture>
      ...
    </Component>
    
    <!-- 库存UI层 -->
    <Widget name="ui_inv">
      <Component class="cn.lambdalib2.cgui.component.DrawTexture">
        <texture>academy:textures/guis/ui/ui_inventory.png</texture>
      </Component>
    </Widget>
    
    <!-- 方块特定UI层 -->
    <Widget name="ui_block">
      <Component class="cn.lambdalib2.cgui.component.DrawTexture">
        <texture>academy:textures/guis/ui/ui_phasegen.png</texture>
      </Component>
    </Widget>
  </Widget>
</Root>
```

**结构**:
- `Root/Widget` - 根组件
- `Component` - 组件属性（Transform, DrawTexture）
- `Widget` - 嵌套子组件（ui_inv, ui_block）

**渲染层次**:
1. 底层：`parent_background.png` - 通用背景
2. 中层：`ui_inventory.png` - 玩家背包UI
3. 顶层：`ui_block.png` - 方块特定UI（插槽位置等）

**动态替换**:
```scala
// 在代码中动态替换 ui_block 纹理
ret.window.getWidget("ui_block").component[DrawTexture]
  .setTex(Resources.getTexture("guis/ui/ui_" + name))
```

**Clojure 目标**: 支持类似的XML结构

---

#### 2.2 TechUI 框架

**核心类 - TechUI.scala**:

```scala
object TechUI {
  // 页面定义
  case class Page(id: String, window: Widget)
  
  // 创建多页面UI
  def apply(pages: Page*) = new TechUIWidget(pages: _*)
  
  // ContainerUI - 容器GUI基类
  class ContainerUI(container: Container, pages: Page*) extends CGuiScreenContainer(container) {
    xSize += 31
    ySize += 20
    
    // 信息区域
    class InfoArea extends Widget {
      def histogram(elems: HistElement*) = {...}
      def property(name: String, value: => String, setter: String => Any) = {...}
      def button(name: String, callback: () => Any) = {...}
    }
    
    val main = TechUI(pages: _*)
    val infoPage = new InfoArea()
    
    gui.addWidget(main)
    
    // 根据当前页面决定是否显示库存
    override def isSlotActive = shouldDisplayInventory(main.currentPage)
  }
}
```

**特性**:
- **页面切换**: 左侧按钮切换不同页面
- **信息面板**: 右侧固定信息显示
- **直方图**: 能量/容量可视化
- **属性编辑**: 可编辑字段（节点名、密码）
- **混合渲染**: `BlendQuad` 半透明背景

---

### 3. 无线页面系统

#### 3.1 WirelessPage.nodePage()

**职责**: 显示可连接的矩阵列表

```scala
def nodePage(node: TileNode): Page = {
  val ret = WirelessPage()
  val world = node.getWorld
  
  def rebuild(): Unit = {
    send(MSG_FIND_NETWORKS, node, Future.create2((result: NodeResult) => {
      val linked = Option(result.linked).map(data => new LinkedTarget {
        def name = data.ssid
        def disconnect() = send(MSG_NODE_DISCONNECT, node, ...)
      })
      
      val avail = result.avail.map(data => new AvailTarget {
        def name = data.ssid
        def encrypted = data.encrypted
        def connect(pass: String) = send(MSG_NODE_CONNECT, node, data.tile(world).get, pass, ...)
      })
      
      rebuildPage(ret.window, linked, avail)
    }))
  }
  
  rebuild()
  ret.window.child("icon_logo").component[DrawTexture].setTex(toMatrixIcon)
  ret
}
```

**关键点**:
- `MSG_FIND_NETWORKS` - 查询附近可用矩阵
- `rebuildPage` - 动态构建列表
- `LinkedTarget` / `AvailTarget` - 抽象已连接/可连接目标

---

### 4. 动画系统

#### 4.1 StateContext

```scala
case class StateContext(state: State, var frame: Int) {
  var lastChange: Double = GameTimer.getTime
  
  def updateAndDraw(w: Double, h: Double) = {
    val time = GameTimer.getTime
    val dt: Long = ((time - lastChange) * 1000).toLong
    
    if (dt >= state.frameTime) {
      lastChange = time
      frame = (frame + 1) % state.frames  // 循环播放
    }
    
    val texFrame = state.begin + frame
    
    RenderUtils.loadTexture(animTexture)
    GL11.glColor4d(1, 1, 1, TechUI.breatheAlpha)
    HudUtils.rawRect(0, 0,
      0, texFrame.toDouble / ALL_FRAMES,
      w, h,
      1, 1.0 / ALL_FRAMES)
  }
}

case class State(begin: Int, frames: Int, frameTime: Long)

val states = Array(
  State(0, 8, 800),   // STATE_LINKED: 8帧，800ms/帧
  State(8, 2, 3000)   // STATE_UNLINKED: 2帧，3000ms/帧
)
```

**动画机制**:
- **帧动画**: 纹理垂直切片，根据时间切换UV
- **状态驱动**: 不同状态播放不同帧范围
- **呼吸效果**: `breatheAlpha` 全局透明度动画

---

## 移植计划

### 阶段 1: 分析文档（当前阶段）✅

**输出**: `WIRELESS_NODE_GUI_ANALYSIS.md`

---

### 阶段 2: 创建XML布局文件

**目标**: 创建 `page_wireless_node.xml`

**内容**:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Root>
  <Widget name="main">
    <Component class="Transform">
      <width>176.0</width>
      <height>187.0</height>
    </Component>
    
    <!-- 背景纹理 -->
    <Component class="DrawTexture">
      <texture>my_mod:textures/gui/node_background.png</texture>
    </Component>
    
    <!-- 插槽区域 -->
    <Widget name="slots_area">
      <Slot index="0" x="42" y="10" filter="energy_item"/>
      <Slot index="1" x="42" y="80" filter="energy_item"/>
    </Widget>
    
    <!-- 动画区域 -->
    <Widget name="anim_area">
      <Component class="Transform">
        <x>42.0</x>
        <y>35.5</y>
        <width>186.0</width>
        <height>75.0</height>
        <scale>0.5</scale>
      </Component>
      <Animation texture="my_mod:textures/gui/effect_node.png" frames="10"/>
    </Widget>
    
    <!-- 信息面板区域 -->
    <Widget name="info_panel">
      <Histogram type="energy"/>
      <Histogram type="capacity"/>
      <Property name="range"/>
      <Property name="owner"/>
      <Property name="node_name" editable="true"/>
      <Property name="password" editable="true" masked="true"/>
    </Widget>
  </Widget>
</Root>
```

**文件位置**: `core/src/main/resources/assets/my_mod/gui/layouts/page_wireless_node.xml`

---

### 阶段 3: 扩展 GUI DSL 支持 XML

**目标**: 在 `gui/dsl.clj` 中添加 XML 解析功能

**新增功能**:

```clojure
;; XML 解析器
(ns my-mod.gui.xml-parser
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]))

(defn parse-xml-layout [xml-path]
  "解析 XML 布局文件
  
  Args:
  - xml-path: XML 文件路径（ResourceLocation格式）
  
  Returns: GuiSpec 规范"
  (let [xml-str (slurp (io/resource xml-path))
        parsed (xml/parse-str xml-str)
        root (first (:content parsed))]
    (xml->gui-spec root)))

(defn xml->gui-spec [widget-node]
  "将 XML Widget 转换为 GuiSpec"
  (let [transform (find-component widget-node "Transform")
        slots (parse-slots widget-node)
        animations (parse-animations widget-node)
        properties (parse-properties widget-node)]
    {:width (get-float transform :width 176.0)
     :height (get-float transform :height 187.0)
     :slots slots
     :animations animations
     :properties properties}))
```

**集成到 DSL**:

```clojure
(defmacro defgui-from-xml
  "从 XML 文件定义 GUI
  
  Example:
  (defgui-from-xml node-gui
    :xml-path \"my_mod:gui/layouts/page_wireless_node.xml\"
    :on-init (fn [gui] ...)
    :on-update (fn [gui dt] ...))"
  [gui-name & options]
  (let [options-map (apply hash-map options)
        xml-path (:xml-path options-map)]
    `(def ~gui-name
       (let [base-spec# (xml-parser/parse-xml-layout ~xml-path)
             merged-spec# (merge base-spec# ~options-map)]
         (register-gui! (create-gui-spec (name '~gui-name) merged-spec#))))))
```

---

### 阶段 4: 实现 Node GUI 逻辑

**目标**: 在 `wireless/gui/node_gui.clj` 中使用 XML + DSL

**新实现**:

```clojure
(ns my-mod.wireless.gui.node-gui
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.gui.xml-parser :as xml]
            [my-mod.gui.components :as comp]
            [my-mod.wireless.gui.node-container :as container]))

;; 从 XML 加载基础布局
(dsl/defgui-from-xml node-gui-base
  :xml-path "my_mod:gui/layouts/page_wireless_node.xml")

;; 添加运行时逻辑
(defn create-node-gui [container]
  "创建 Node GUI 实例
  
  流程：XML → DSL → 填充逻辑"
  (let [tile (:tile-entity container)
        base-layout @node-gui-base
        
        ;; 1. 加载XML布局
        root-widget (comp/widget :size [176 187])
        
        ;; 2. 添加背景
        _ (comp/add-child! root-widget
            (comp/texture "my_mod:textures/gui/node_background.png" 0 0 176 187))
        
        ;; 3. 添加动画区域
        anim-widget (create-animation-area container)
        _ (comp/add-child! root-widget anim-widget)
        
        ;; 4. 添加信息面板
        info-panel (create-info-panel container)
        _ (comp/add-child! root-widget info-panel)
        
        ;; 5. 添加无线页面
        wireless-panel (create-wireless-panel container)
        _ (comp/add-child! root-widget wireless-panel)]
    
    root-widget))

(defn create-animation-area [container]
  "创建动画区域（基于XML规范）"
  (let [anim-widget (comp/widget :pos [42 35.5] :size [93 37.5]) ; 186*75*0.5
        state (atom :unlinked) ; :linked 或 :unlinked
        frame (atom 0)
        last-update (atom (System/currentTimeMillis))]
    
    ;; 每帧更新
    (comp/on-frame anim-widget
      (fn []
        (let [now (System/currentTimeMillis)
              dt (- now @last-update)
              [begin frames frame-time] (case @state
                                          :linked   [0 8 800]
                                          :unlinked [8 2 3000])]
          (when (>= dt frame-time)
            (swap! frame #(mod (inc %) frames))
            (reset! last-update now))
          
          ;; 渲染当前帧
          (comp/render-texture-region 
            "my_mod:textures/gui/effect_node.png"
            0 0 186 75
            0 (/ (+ begin @frame) 10.0) ; UV坐标
            1 0.1))))
    
    ;; 定期查询连接状态
    (comp/on-interval anim-widget 2000
      (fn []
        (network/query-link-status tile
          (fn [is-linked]
            (reset! state (if is-linked :linked :unlinked))))))
    
    anim-widget))

(defn create-info-panel [container]
  "创建信息面板（直方图+属性）"
  (let [panel (comp/widget :pos [183 5] :size [100 180])]
    
    ;; 能量直方图
    (comp/add-child! panel
      (comp/histogram 
        :label "Energy"
        :value #(deref (:energy container))
        :max #(deref (:max-energy container))
        :color 0xff25c4ff))
    
    ;; 容量直方图
    (comp/add-child! panel
      (comp/histogram
        :label "Capacity"
        :value #(deref (:capacity container))
        :max #(deref (:max-capacity container))
        :color 0xffff6c00))
    
    ;; 属性列表
    (comp/add-child! panel
      (comp/property-list
        [{:name "Range" :value #(str (deref (:range container)) " blocks")}
         {:name "Owner" :value #(deref (:owner container))}
         {:name "Name" :value #(deref (:node-name container))
          :editable true
          :on-change #(network/send-rename (:tile-entity container) %)}
         {:name "Password" :value #(deref (:password container))
          :editable true
          :masked true
          :on-change #(network/send-change-pass (:tile-entity container) %)}]))
    
    panel))

(defn create-wireless-panel [container]
  "创建无线连接面板"
  (let [panel (comp/widget :pos [0 0] :size [176 187])
        tile (:tile-entity container)]
    
    ;; 查询可用网络
    (network/query-available-networks tile
      (fn [networks]
        (doseq [net networks]
          (comp/add-child! panel
            (comp/button
              :text (:ssid net)
              :on-click #(network/connect-to-network tile net))))))
    
    panel))
```

---

### 阶段 5: 网络同步层

**目标**: 实现客户端-服务端通信

**文件**: `wireless/gui/network.clj`

```clojure
(ns my-mod.wireless.gui.network
  "Wireless GUI 网络同步")

(def MSG_RENAME "wireless_gui_rename")
(def MSG_CHANGE_PASS "wireless_gui_change_pass")
(def MSG_QUERY_LINK "wireless_gui_query_link")
(def MSG_FIND_NETWORKS "wireless_gui_find_networks")

(defn send-rename [tile new-name]
  "发送重命名请求"
  (network/send-to-server MSG_RENAME {:tile-pos (get-pos tile) :name new-name}))

(defn send-change-pass [tile new-pass]
  "发送修改密码请求"
  (network/send-to-server MSG_CHANGE_PASS {:tile-pos (get-pos tile) :pass new-pass}))

(defn query-link-status [tile callback]
  "查询连接状态"
  (network/send-with-callback MSG_QUERY_LINK {:tile-pos (get-pos tile)} callback))

(defn query-available-networks [tile callback]
  "查询可用网络列表"
  (network/send-with-callback MSG_FIND_NETWORKS {:tile-pos (get-pos tile)} callback))

;; 服务端处理器
(defn handle-rename [world pos player-name new-name]
  (when-let [tile (get-tile world pos)]
    (when (= (:placer-name tile) player-name)
      (swap! (:node-name tile) (constantly new-name))
      (mark-dirty tile))))

(defn handle-change-pass [world pos player-name new-pass]
  (when-let [tile (get-tile world pos)]
    (when (= (:placer-name tile) player-name)
      (swap! (:password tile) (constantly new-pass))
      (mark-dirty tile))))

(defn handle-query-link [world pos]
  (when-let [tile (get-tile world pos)]
    (my-mod.wireless.helper/is-node-linked? tile)))

(defn handle-find-networks [world pos]
  (when-let [tile (get-tile world pos)]
    (my-mod.wireless.helper/get-networks-in-range tile)))
```

---

## 关键技术点

### 1. XML → DSL 转换流程

```
page_wireless_node.xml
    ↓ (解析)
XML AST
    ↓ (转换)
GuiSpec {:width 176 :height 187 :slots [...] :animations [...]}
    ↓ (填充逻辑)
Runtime GUI Instance (带回调、状态、网络同步)
```

### 2. 组件层次

```
Root Widget (176x187)
  ├─ Background Texture
  ├─ Slots Area
  │   ├─ Slot 0 (42, 10) - Input
  │   └─ Slot 1 (42, 80) - Output
  ├─ Animation Area (42, 35.5, 93x37.5)
  │   └─ State-driven frame animation
  ├─ Info Panel (183, 5, 100x180)
  │   ├─ Energy Histogram
  │   ├─ Capacity Histogram
  │   └─ Property List
  │       ├─ Range (read-only)
  │       ├─ Owner (read-only)
  │       ├─ Name (editable)
  │       └─ Password (editable, masked)
  └─ Wireless Panel (multi-page)
      ├─ Connected Network (if linked)
      └─ Available Networks List
```

### 3. 数据流

```
Server (TileEntity)
    ↓ (sync-to-client!)
Container (atoms: energy, max-energy, is-online, ...)
    ↓ (deref in render)
GUI Widgets (display current values)
    ↓ (user input)
Network Messages (MSG_RENAME, MSG_CHANGE_PASS, ...)
    ↓ (server handler)
Server (TileEntity) - update state
```

### 4. 动画系统

**状态机**:
```
:unlinked ←→ :linked
   ↓            ↓
Frames 8-9   Frames 0-7
3000ms/frame  800ms/frame
```

**渲染**:
- 纹理：垂直切片（10帧）
- UV 映射：`(0, frame/10, 1, 1/10)`
- Alpha：`breatheAlpha` 全局脉动

---

## 优势分析

### 原始实现优势

1. **XML 驱动** - 布局与逻辑分离，易于调整UI
2. **组件化** - Widget/Component 模式，可复用
3. **多页面** - 页面切换系统，信息组织清晰
4. **动画系统** - 状态驱动动画，视觉反馈丰富
5. **直方图** - 数据可视化，直观显示能量/容量

### Clojure 移植优势

1. **保留 XML** - 继续使用 XML 定义布局
2. **DSL 增强** - Clojure 宏提供更灵活的DSL
3. **函数式** - 纯函数组件，易于测试
4. **Atom 同步** - 自动响应式数据绑定
5. **Protocol 调度** - 更清晰的多态

---

## 待移植功能清单

### 高优先级

- [x] 基础容器（`node_container.clj`）✅
- [ ] XML 布局文件创建
- [ ] XML 解析器实现
- [ ] 动画系统移植
- [ ] 信息面板（直方图+属性）

### 中优先级

- [ ] 无线页面集成
- [ ] 网络同步层
- [ ] 多页面切换系统
- [ ] 呼吸效果动画

### 低优先级

- [ ] 密码遮罩输入
- [ ] 快速移动规则优化
- [ ] 本地化支持
- [ ] 音效反馈

---

## 参考资源

### 原始代码路径

- `ContainerNode.java` - 容器类
- `GuiNode.scala` - GUI 类
- `TechUI.scala` - UI 框架
- `WirelessPage.scala` - 无线页面
- `page_inv.xml` - XML 布局示例

### Clojure 实现路径

- `core/src/main/clojure/my_mod/wireless/gui/node_container.clj` - 容器
- `core/src/main/clojure/my_mod/wireless/gui/node_gui.clj` - GUI
- `core/src/main/clojure/my_mod/gui/dsl.clj` - DSL系统
- `core/src/main/clojure/my_mod/gui/components.clj` - 组件库

---

**文档版本**: 1.0  
**创建日期**: 2025-11-26  
**分析范围**: ContainerNode.java, GuiNode.scala, TechUI.scala, page_inv.xml
