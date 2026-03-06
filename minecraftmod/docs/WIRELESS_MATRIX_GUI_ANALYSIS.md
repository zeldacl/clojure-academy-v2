# Wireless Matrix GUI 原始实现分析

## 概述

Wireless Matrix GUI 是网络矩阵的管理界面，负责创建无线网络、配置SSID和密码。

---

## 原始实现分析

### 1. 容器类 - ContainerMatrix.java

**职责**: 服务端容器，管理4个插槽的库存

```java
public class ContainerMatrix extends TechUIContainer<TileMatrix> {
    
    public ContainerMatrix(TileMatrix _tile, EntityPlayer _player) {
        super(_player, _tile);
        initInventory();
    }
    
    private void initInventory() {
        // 3个Constraint Plate插槽（三角形布局）
        this.addSlotToContainer(new SlotPlate(tile, 0, 78, 11));  // 顶部
        this.addSlotToContainer(new SlotPlate(tile, 1, 53, 60));  // 左下
        this.addSlotToContainer(new SlotPlate(tile, 2, 104, 60)); // 右下
        
        // 1个Matrix Core插槽（中心位置）
        this.addSlotToContainer(new SlotCore(tile, 3, 78, 36));
        
        mapPlayerInventory(); // 玩家背包
        
        // 快速移动规则
        SlotGroup invGroup = gRange(4, 4 + 36);
        addTransferRule(invGroup, 
            stack -> stack.getItem() == ACItems.constraint_plate, 
            gSlots(0, 1, 2));
        addTransferRule(invGroup, 
            stack -> stack.getItem() == ACItems.mat_core, 
            gSlots(3));
        addTransferRule(gRange(0, 4), invGroup);
    }
    
    // 自定义插槽类
    public static class SlotCore extends Slot {
        @Override
        public boolean isItemValid(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() == ACItems.mat_core;
        }
    }
    
    public static class SlotPlate extends Slot {
        @Override
        public boolean isItemValid(ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() == ACItems.constraint_plate;
        }
    }
}
```

**关键特性**:
- 4个插槽：3个Plate（三角形） + 1个Core（中心）
- 严格的物品过滤：Plate槽只接受`constraint_plate`，Core槽只接受`mat_core`
- 特殊布局：三角形 + 中心，而非传统的网格布局

**与Node对比**:
| 特性 | Node GUI | Matrix GUI |
|------|----------|------------|
| 插槽数量 | 2 | 4 |
| 插槽类型 | 能量物品 | Constraint Plate + Matrix Core |
| 插槽布局 | 垂直排列 | 三角形 + 中心 |

---

### 2. GUI类 - GuiMatrix.scala

**职责**: 客户端GUI，管理网络初始化和配置

```scala
object GuiMatrix2 {
  def apply(container: ContainerMatrix) = {
    val tile = container.tile
    val thePlayer = Minecraft.getMinecraft.player
    val isPlacer = tile.getPlacerName == thePlayer.getName
    
    // 库存页面
    val invPage = InventoryPage("matrix")
    
    val ret = new ContainerUI(container, invPage)
    
    // 动态构建信息面板
    {
      def rebuildInfo(data: InitData): Unit = {
        ret.infoPage.reset()
        
        // 容量直方图
        ret.infoPage.histogram(
          TechUI.histCapacity(() => data.load, tile.getCapacity)
        )
        
        ret.infoPage.seplineInfo()
          .property("owner", tile.getPlacerName)
          .property("range", "%.0f".format(tile.getRange))
          .property("bandwidth", tile.getBandwidth + " IF/T")
        
        if (data.init) {
          // 已初始化：显示SSID和密码（可编辑）
          ret.infoPage.sepline("wireless_info")
          if (isPlacer) {
            ret.infoPage
              .property("ssid", data.ssid, editCallback = newSSID => {
                send(MSG_CHANGE_SSID, tile, thePlayer, newSSID)
              })
              .sepline("change_pass")
              .property("password", data.pass, password=true, editCallback = newPass => {
                send(MSG_CHANGE_PASSWORD, tile, thePlayer, newPass)
              })
          } else {
            // 非所有者：只读
            ret.infoPage
              .property("ssid", data.ssid)
              .property("password", data.pass, password=true)
          }
        } else {
          // 未初始化：显示初始化表单
          val ssidCell = Array[TextBox](null)
          val passwordCell = Array[TextBox](null)
          
          if (isPlacer) {
            ret.infoPage
              .sepline("wireless_init")
              .property("ssid", "", _ => {}, contentCell=ssidCell, colorChange=false)
              .property("password", "", _ => {}, contentCell=passwordCell, password=true, colorChange=false)
              .blank(1)
              .button("INIT", () => {
                val (ssidBox, passBox) = (ssidCell(0), passwordCell(0))
                send(MSG_INIT, tile, ssidBox.content, passBox.content, 
                  Future.create2((_: Boolean) =>
                    send(MSG_GATHER_INFO, tile, Future.create2((inf: InitData) => rebuildInfo(inf)))
                  ))
              })
          } else {
            ret.infoPage.sepline("wireless_noinit")
          }
        }
      }
      
      // 初始化时查询网络信息
      send(MSG_GATHER_INFO, tile, Future.create2((inf: InitData) => rebuildInfo(inf)))
    }
    
    ret
  }
}
```

**关键特性**:

1. **状态驱动UI** - 两种状态：
   - `data.init == true` - 已初始化网络，显示SSID/密码（可编辑）
   - `data.init == false` - 未初始化，显示初始化表单 + INIT按钮

2. **权限控制**:
   - 所有者（`isPlacer`）：可编辑SSID和密码
   - 非所有者：只读显示

3. **动态重建**:
   - `rebuildInfo(data)` 函数根据网络状态动态构建UI
   - 使用`ret.infoPage.reset()`清空再重建

4. **网络消息**:
   - `MSG_GATHER_INFO` - 查询网络状态（SSID、密码、负载）
   - `MSG_INIT` - 初始化网络
   - `MSG_CHANGE_SSID` - 修改SSID
   - `MSG_CHANGE_PASSWORD` - 修改密码

---

### 3. 数据结构 - InitData

```scala
@NetworkS11nType
@SerializeStrategy(strategy=ExposeStrategy.ALL)
private class InitData {
  @SerializeNullable
  var ssid: String = null
  @SerializeNullable
  var pass: String = null
  var load: Int = 0
  
  def init = ssid != null  // 判断是否已初始化
}
```

**字段说明**:
- `ssid` - 网络名称（null表示未初始化）
- `pass` - 网络密码
- `load` - 当前网络负载（连接的设备数）
- `init` - 计算属性，判断网络是否已创建

---

### 4. 网络消息处理 - MatrixNetProxy

```scala
@NetworkS11nType
private object MatrixNetProxy {
  
  final val MSG_GATHER_INFO = "gather"
  final val MSG_INIT = "init"
  final val MSG_CHANGE_PASSWORD = "pass"
  final val MSG_CHANGE_SSID = "ssid"
  
  @Listener(channel=MSG_GATHER_INFO, side=Array(Side.SERVER))
  def gatherInfo(matrix: TileMatrix, future: Future[InitData]) = {
    val optNetwork = Option(WirelessHelper.getWirelessNet(matrix))
    val result = new InitData
    optNetwork match {
      case Some(net) =>
        result.ssid = net.getSSID
        result.pass = net.getPassword
        result.load = net.getLoad
      case _ =>
        // ssid和pass保持null（未初始化）
    }
    future.sendResult(result)
  }
  
  @Listener(channel=MSG_INIT, side=Array(Side.SERVER))
  def init(matrix: TileMatrix, ssid: String, pwd: String, fut: Future[Boolean]) = {
    MinecraftForge.EVENT_BUS.post(new CreateNetworkEvent(matrix, ssid, pwd))
    fut.sendResult(true)
  }
  
  @Listener(channel=MSG_CHANGE_PASSWORD, side=Array(Side.SERVER))
  def changePassword(matrix: TileMatrix, player: EntityPlayer, pwd: String) = {
    if (matrix.getPlacerName == player.getName) {
      MinecraftForge.EVENT_BUS.post(new ChangePassEvent(matrix, pwd))
    }
  }
  
  @Listener(channel=MSG_CHANGE_SSID, side=Array(Side.SERVER))
  def changeSSID(matrix: TileMatrix, player: EntityPlayer, newSSID: String) = {
    if (matrix.getPlacerName == player.getName) {
      Option(WirelessHelper.getWirelessNet(matrix)) match {
        case Some(net) =>
          net.setSSID(newSSID)
        case _ =>
      }
    }
  }
}
```

**处理器说明**:

1. **MSG_GATHER_INFO**:
   - 查询`WirelessHelper.getWirelessNet(matrix)`
   - 如果网络存在，返回SSID、密码、负载
   - 如果不存在，返回空数据（表示未初始化）

2. **MSG_INIT**:
   - 发送`CreateNetworkEvent`事件
   - 由事件系统处理网络创建

3. **MSG_CHANGE_PASSWORD**:
   - 验证权限（必须是所有者）
   - 发送`ChangePassEvent`事件

4. **MSG_CHANGE_SSID**:
   - 验证权限
   - 直接调用`net.setSSID(newSSID)`

---

## 与Node GUI的对比

| 特性 | Node GUI | Matrix GUI |
|------|----------|------------|
| **插槽数量** | 2 | 4 |
| **插槽类型** | 能量物品 | Constraint Plate + Matrix Core |
| **插槽布局** | 垂直 (42,10) + (42,80) | 三角形 + 中心 |
| **动画** | 连接状态动画（8帧/2帧） | 无动画 |
| **直方图** | 能量 + 容量 | 容量（网络负载） |
| **属性** | 范围、所有者、节点名、密码 | 范围、所有者、带宽 |
| **特殊功能** | 网络状态轮询 | 网络初始化 + SSID/密码管理 |
| **状态** | 2种（linked/unlinked） | 2种（initialized/uninitialized） |
| **编辑字段** | 节点名、密码（所有者） | SSID、密码（所有者，已初始化时） |
| **按钮** | 无 | INIT按钮（未初始化时） |

---

## 核心差异

### 1. 初始化流程

**Node GUI**:
- Node创建时即可使用
- 只需配置节点名和密码
- 连接到已存在的网络

**Matrix GUI**:
- Matrix创建后需要初始化
- 初始化时创建新网络
- 配置SSID（网络名）和密码
- 其他Node通过这个网络连接

### 2. UI状态

**Node GUI**:
```
状态：连接中 / 未连接
显示：动画效果 + 连接状态
```

**Matrix GUI**:
```
状态：已初始化 / 未初始化
已初始化：显示 SSID（可编辑） + 密码（可编辑） + 负载直方图
未初始化：显示 SSID输入框 + 密码输入框 + INIT按钮
```

### 3. 权限模型

**Node GUI**:
- 所有者可编辑：节点名、密码
- 他人只读

**Matrix GUI**:
- 所有者可编辑：SSID、密码（已初始化时）
- 所有者可初始化：填写SSID、密码、点击INIT（未初始化时）
- 他人只读（或看到"未初始化"提示）

---

## 移植策略

### 复用Node GUI的实现

1. **XML解析器** - 无需修改，通用组件
2. **DSL宏** - `defgui-from-xml`直接复用
3. **组件模式**:
   - 直方图创建 - 复用`create-histogram-widget`
   - 属性字段 - 复用`create-property-widget`
   - 网络同步 - 复用轮询模式

### 新增功能

1. **初始化表单**:
   ```clojure
   (defn create-init-form [tile player on-init]
     ;; SSID输入框
     ;; 密码输入框
     ;; INIT按钮
     )
   ```

2. **动态重建逻辑**:
   ```clojure
   (defn rebuild-info-panel! [container player init-data]
     (clear-panel!)
     (if (:initialized init-data)
       (create-network-info-panel init-data)
       (create-init-form tile player)))
   ```

3. **网络消息处理**:
   ```clojure
   (defn send-gather-info [tile callback])
   (defn send-init-network [tile ssid password callback])
   (defn send-change-ssid [tile new-ssid])
   (defn send-change-password [tile new-password])
   ```

---

## XML布局设计

### 插槽布局（三角形 + 中心）

```xml
<Widget name="slots">
  <!-- Constraint Plate 插槽 -->
  <Slot name="slot_plate_top">
    <index>0</index>
    <x>78</x>
    <y>11</y>
    <filter>constraint_plate</filter>
  </Slot>
  
  <Slot name="slot_plate_left">
    <index>1</index>
    <x>53</x>
    <y>60</y>
    <filter>constraint_plate</filter>
  </Slot>
  
  <Slot name="slot_plate_right">
    <index>2</index>
    <x>104</x>
    <y>60</y>
    <filter>constraint_plate</filter>
  </Slot>
  
  <!-- Matrix Core 插槽（中心） -->
  <Slot name="slot_core_center">
    <index>3</index>
    <x>78</x>
    <y>36</y>
    <filter>matrix_core</filter>
  </Slot>
</Widget>
```

### 信息面板布局

```xml
<Widget name="info_panel">
  <!-- 容量直方图（网络负载） -->
  <Histogram name="hist_load">
    <label>Network Load</label>
    <type>load</type>
    <color>#ff6c00</color>
    <y>10</y>
    <height>40</height>
  </Histogram>
  
  <!-- 基本属性 -->
  <PropertyList name="basic_properties">
    <Property name="owner">
      <label>Owner</label>
      <editable>false</editable>
    </Property>
    <Property name="range">
      <label>Range</label>
      <editable>false</editable>
    </Property>
    <Property name="bandwidth">
      <label>Bandwidth</label>
      <editable>false</editable>
    </Property>
  </PropertyList>
  
  <!-- 网络属性（已初始化时显示） -->
  <PropertyList name="network_properties">
    <Property name="ssid">
      <label>SSID</label>
      <editable>true</editable>
      <requiresOwner>true</requiresOwner>
    </Property>
    <Property name="password">
      <label>Password</label>
      <editable>true</editable>
      <masked>true</masked>
      <requiresOwner>true</requiresOwner>
    </Property>
  </PropertyList>
  
  <!-- 初始化表单（未初始化时显示） -->
  <Widget name="init_form">
    <TextField name="ssid_input">
      <label>Network Name</label>
      <maxLength>32</maxLength>
    </TextField>
    <TextField name="password_input">
      <label>Password</label>
      <maxLength>16</maxLength>
      <masked>true</masked>
    </TextField>
    <Button name="btn_init">
      <text>Initialize Network</text>
      <width>80</width>
      <height>20</height>
    </Button>
  </Widget>
</Widget>
```

---

## 实现清单

### ✅ 已存在（可复用）

1. `matrix_container.clj` - 容器实现（已完成）
2. `matrix_gui.clj` - 基础GUI（需重构为XML驱动）
3. `xml_parser.clj` - XML解析器（通用）
4. `dsl.clj` - DSL系统（`defgui-from-xml`宏）

### 📝 需要创建

1. `page_wireless_matrix.xml` - XML布局定义
2. `matrix_gui_xml.clj` - 新的XML驱动实现
3. 网络消息处理模块（network.clj扩展）

### 🔧 需要修改

1. `xml_parser.clj` - 添加`TextField`组件解析（如果需要）
2. `components.clj` - 添加文本输入框组件（如果缺失）

---

## 技术挑战

### 1. 动态UI重建

**问题**: GUI需要根据网络状态（已初始化/未初始化）显示不同内容

**解决方案**:
```clojure
(defn create-matrix-gui [container player]
  (let [info-panel-atom (atom nil)
        rebuild! (fn [init-data]
                   (reset! info-panel-atom
                     (if (:initialized init-data)
                       (create-network-panel init-data player)
                       (create-init-form tile player rebuild!))))]
    ;; 初始查询
    (send-gather-info tile rebuild!)
    ...))
```

### 2. 文本输入框

**问题**: 需要文本输入框组件（SSID、密码）

**解决方案**:
- 检查`components.clj`是否有`text-field`组件
- 如果没有，创建简单的文本输入框组件
- 支持：占位符、最大长度、遮罩显示

### 3. 按钮点击回调

**问题**: INIT按钮需要触发网络初始化

**解决方案**:
```clojure
(comp/button
  :text "Initialize"
  :on-click (fn []
              (let [ssid (get-text ssid-field)
                    pass (get-text pass-field)]
                (send-init-network tile ssid pass
                  (fn [success]
                    (when success
                      (send-gather-info tile rebuild!)))))))
```

---

## 总结

### 核心特点

1. **状态驱动** - 根据网络初始化状态显示不同UI
2. **权限控制** - 只有所有者可以初始化和编辑
3. **动态重建** - UI需要根据网络状态动态更新
4. **特殊布局** - 三角形 + 中心的插槽布局

### 与Node的相似度

- 容器结构：80%
- 信息面板：60%（少了动画，多了初始化表单）
- 网络消息：50%（消息类型不同）
- XML解析：100%（完全复用）

### 移植难度

**简单**（1-2小时）:
- XML布局定义
- 容器已存在
- 基础组件复用

**中等**（2-3小时）:
- 动态UI重建逻辑
- 网络消息处理
- 初始化表单实现

**总计**: 3-5小时（有Node GUI经验的情况下）

---

**文档版本**: 1.0  
**创建日期**: 2025-11-26  
**参考**: ContainerMatrix.java, GuiMatrix.scala
