# 无线能量系统 - 待办事项列表

基于 Java 代码分析和 Clojure 实现现状，整理出的待办任务清单。

## 🔴 高优先级任务（核心功能必需）

### 1. Java 接口适配层

#### 1.1 创建 Java 接口定义（或 Stub）
- [ ] **ImagEnergyItem** 接口
  - 包：`cn.academy.energy.api.item`
  - 方法：`getMaxEnergy()`, `getBandwidth()`
  - 实现方式：Clojure deftype 或 Java 接口定义
  - 文件：`energy/imag_energy_item.clj` 或 Java 源码

- [x] **IWirelessMatrix** 接口 ✅
  - 包：`cn.li.wireless.interfaces`
  - 方法：`get-matrix-range`, `get-matrix-capacity`, `get-matrix-bandwidth`
  - 用途：无线网络矩阵（中心节点）
  - 文件：`wireless/interfaces.clj`
  - 状态：已完成 defprotocol 定义

- [x] **IWirelessNode** 接口 ✅
  - 包：`cn.li.wireless.interfaces`
  - 方法：`get-energy`, `set-energy`, `get-max-energy`, `get-bandwidth`, `get-range`, `get-capacity`, `get-password`, `get-node-name`
  - 用途：无线节点
  - 文件：`wireless/interfaces.clj`
  - 状态：已完成 defprotocol 定义

- [x] **IWirelessGenerator** 接口 ✅
  - 包：`cn.li.wireless.interfaces`
  - 方法：`get-provided-energy`, `get-generator-bandwidth`
  - 用途：能量生成器
  - 文件：`wireless/interfaces.clj`
  - 状态：已完成 defprotocol 定义

- [x] **IWirelessReceiver** 接口 ✅
  - 包：`cn.li.wireless.interfaces`
  - 方法：`inject-energy`, `pull-energy`, `get-required-energy`, `get-receiver-bandwidth`
  - 用途：能量接收器
  - 文件：`wireless/interfaces.clj`
  - 状态：已完成 defprotocol 定义

#### 1.2 TileEntity 接口实现
- [x] **NodeTileEntity** 实现 IWirelessNode ✅
  - 当前状态：defrecord 定义，已实现接口方法
  - 文件：`block/wireless_node.clj`
  - 状态：完成，使用 Clojure record + protocol 实现
  - 包含：能量管理、物品栏（2槽位）、网络连接

- [x] **TileMatrix** 实现 IWirelessMatrix ✅
  - 当前状态：defrecord 定义，已实现接口方法
  - 文件：`block/wireless_matrix.clj`
  - 状态：完成，使用 Clojure record + protocol 实现
  - 包含：4槽位物品栏（3个constraint_plate + 1个mat_core）

- [x] **NodeTileEntity** 完成物品栏逻辑 ✅
  - 2 槽位物品栏
  - 方法：基于 customState `:inventory` 字段读写
  - 状态：完成，集成在 NodeTileEntity record 中

- [x] **NodeTileEntity** 实现 ITickable ✅
  - 方法：`update-node-tile!` 每 tick 调用
  - 状态：完成，包含充电逻辑、网络同步
  - 文件：`block/wireless_node.clj`

### 2. 方块状态管理

- [ ] **实现 rebuild-block-state!**
  - 根据能量百分比更新 ENERGY 属性（0-4）
  - 根据网络连接状态更新 CONNECTED 属性
  - 文件：`block/wireless_node.clj`
  - 依赖：Minecraft BlockState API

- [ ] **实现 get-actual-state**
  - 动态方块状态获取
  - 从 TileEntity 读取状态
  - 用于客户端渲染
  - 文件：`block/wireless_node.clj`

### 3. 测试用能量物品

- [ ] **创建测试能量物品**
  - 实现 ImagEnergyItem 接口
  - 用于测试充放电功能
  - 属性：maxEnergy=10000, bandwidth=100
  - 文件：`item/test_battery.clj`

## 🟡 中优先级任务（完整功能）

### 4. GUI 系统

- [x] **ContainerNode - 容器类** ✅
  - 2 槽位容器
  - 槽位 0：输入（充能到节点）
  - 槽位 1：输出（从节点充能）
  - 文件：`wireless/gui/node_container.clj`
  - 状态：完成

- [x] **GuiNode - 节点配置界面** ✅
  - 显示能量条、容量直方图
  - 显示连接状态（动画）
  - 节点名称输入框
  - 密码输入框
  - 网络状态轮询
  - 文件：`wireless/gui/node_gui.clj` (CGui版本)
  - 文件：`wireless/gui/node_gui_xml.clj` (XML驱动版本) ✅ 完成
  - 状态：双版本实现，XML版本已完成并文档化

- [x] **ContainerMatrix - 容器类** ✅
  - 4 槽位容器
  - 槽位 0-2：constraint_plate（三角形布局）
  - 槽位 3：mat_core（中心位置）
  - 文件：`wireless/gui/matrix_container.clj`
  - 状态：完成

- [x] **GuiMatrix - Matrix配置界面** ✅
  - 网络初始化UI（SSID + 密码）
  - 网络信息显示（可编辑）
  - 容量直方图（网络负载）
  - 动态UI切换（已初始化/未初始化）
  - 所有者权限控制
  - 文件：`wireless/gui/matrix_gui.clj` (CGui版本)
  - 文件：`wireless/gui/matrix_gui_xml.clj` (XML驱动版本) ✅ 完成
  - XML布局：`resources/assets/academy/gui/page_wireless_matrix.xml` ✅
  - 状态：双版本实现，XML版本已完成并文档化

### 5. 网络同步系统

- [x] **GUI网络消息系统** ✅
  - Node GUI 消息：
    - MSG_GET_STATUS - 查询节点状态
    - MSG_CHANGE_NAME - 修改节点名称
    - MSG_CHANGE_PASSWORD - 修改密码
  - Matrix GUI 消息：
    - MSG_GATHER_INFO - 查询网络信息
    - MSG_INIT - 初始化网络
    - MSG_CHANGE_SSID - 修改SSID
    - MSG_CHANGE_PASSWORD - 修改密码
  - 文件：`wireless/gui/node_gui_xml.clj`, `wireless/gui/matrix_gui_xml.clj`
  - 网络处理：`wireless/gui/matrix_network_handler.clj` ✅
  - 状态：完成，包含权限验证和回调机制

- [ ] **TileEntity 同步系统**
  - 数据：enabled, chargingIn, chargingOut, energy, name, password, placerName
  - 同步范围：20 格内玩家
  - 同步频率：每 10 tick
  - 文件：`network/sync.clj` 或 `block/wireless_node.clj`
  - 需要：实现真实的 Minecraft Packet 系统

- [ ] **客户端状态接收处理**
  - 接收服务器同步数据
  - 更新客户端 TileEntity 状态
  - 触发方块状态更新
  - 文件：`network/sync.clj`

### 6. 世界数据持久化

- [ ] **WorldSavedData 集成**
  - 注册 WiWorldData 为 WorldSavedData
  - 自动保存/加载
  - 当前状态：NBT 序列化已实现
  - 需要：与 Minecraft 保存系统集成
  - 文件：`wireless/world_data.clj`

- [ ] **世界加载/卸载事件**
  - WorldLoadEvent：创建 WiWorldData
  - WorldUnloadEvent：清理 WiWorldData
  - 文件：`wireless/events.clj`

### 7. 辅助工具实现

- [ ] **方块范围搜索工具**
  - `WorldUtils.getBlocksWithin` 的 Clojure 版本
  - 用于 `get-nodes-in-range`
  - 参数：world, x, y, z, range, maxResults
  - 文件：`util/world_utils.clj`

- [ ] **方块选择器**
  - `IBlockSelector` 接口实现
  - 用于过滤搜索结果
  - 条件：TileEntity 类型、范围、容量
  - 文件：`util/block_selector.clj`

## 🟢 低优先级任务（增强功能）

### 8. 事件系统（可选）

如果需要事件驱动架构，实现以下事件：

- [ ] **CreateNetworkEvent**
  - 创建无线网络
  - 验证：SSID 唯一性
  - 文件：`wireless/events.clj`

- [ ] **DestroyNetworkEvent**
  - 销毁网络
  - 断开所有节点
  - 文件：`wireless/events.clj`

- [ ] **ChangePassEvent**
  - 修改网络密码
  - 文件：`wireless/events.clj`

- [ ] **LinkNodeEvent**
  - 节点连接到网络
  - 验证：密码、容量、范围
  - 文件：`wireless/events.clj`

- [ ] **UnlinkNodeEvent**
  - 节点断开
  - 文件：`wireless/events.clj`

- [ ] **LinkUserEvent**
  - 生成器/接收器连接到节点
  - 文件：`wireless/events.clj`

- [ ] **UnlinkUserEvent**
  - 生成器/接收器断开
  - 文件：`wireless/events.clj`

- [ ] **WirelessSystem 事件监听器**
  - 监听所有无线系统事件
  - 协调操作
  - 验证合法性
  - 文件：`wireless/events.clj`

### 9. 测试和调试

- [ ] **单元测试**
  - VBlocks 系统测试
  - WiWorldData 查找表测试
  - WirelessNet 能量平衡测试
  - NodeConn 能量传输测试
  - 文件：`test/wireless/` 目录

- [ ] **集成测试**
  - 完整能量流动测试
  - 多节点网络测试
  - 持久化和加载测试
  - 文件：`test/integration/` 目录

- [ ] **调试工具**
  - 网络可视化命令
  - 能量流动日志
  - 状态查询命令
  - 文件：`debug/wireless_debug.clj`

### 10. 性能优化

- [ ] **查找表优化**
  - 使用更高效的数据结构
  - 索引优化
  - 文件：`wireless/world_data.clj`

- [ ] **能量平衡算法优化**
  - 减少不必要的计算
  - 批量更新
  - 文件：`wireless/network.clj`

- [ ] **区块加载检查优化**
  - 缓存区块加载状态
  - 减少频繁检查
  - 文件：`wireless/virtual_blocks.clj`

### 11. 文档完善

- [ ] **API 文档**
  - 所有公开函数的详细文档
  - 使用示例
  - 文件：各个 .clj 文件中的 docstring

- [ ] **架构图**
  - 能量流动图
  - 类图/记录图
  - 时序图
  - 文件：`WIRELESS_SYSTEM_ARCHITECTURE.md`

- [ ] **配置指南**
  - 如何创建网络
  - 如何连接节点
  - 故障排除
  - 文件：`WIRELESS_SYSTEM_GUIDE.md`

## 📋 实现计划

### 阶段一：基础适配（1-2周）
1. 创建所有 Java 接口定义
2. NodeTileEntity 实现必要接口
3. 方块状态管理
4. 测试能量物品

**目标**：系统可以运行，节点可以存储能量

### 阶段二：功能完善（2-3周）
1. GUI 系统
2. 网络同步
3. WorldSavedData 集成
4. 辅助工具

**目标**：完整功能可用，有 GUI 界面

### 阶段三：增强优化（1-2周）
1. 事件系统（可选）
2. 测试覆盖
3. 性能优化
4. 文档完善

**目标**：生产就绪，性能良好

## 🎯 当前优先级建议

### 立即需要（阻塞测试）

1. **方块状态管理** 🔴
   - 实现 `rebuild-block-state!`
   - 实现 `get-actual-state`
   - 文件：`block/wireless_node.clj`
   - 原因：客户端渲染需要

2. **测试能量物品** 🔴
   - 创建 `item/test_battery.clj`
   - 实现 ImagEnergyItem 接口
   - 用于验证充放电功能
   - 原因：测试Node充电系统

3. **TileEntity 同步** 🟡
   - 实现 Minecraft Packet 系统
   - 同步节点状态到客户端
   - 文件：`network/sync.clj`
   - 原因：GUI显示需要实时数据

### 短期目标（本周）

4. **WorldSavedData 集成** 🟡
   - 注册 WiWorldData 为 WorldSavedData
   - 实现自动保存/加载
   - 文件：`wireless/world_data.clj`

5. **方块范围搜索** 🟡
   - 实现 `WorldUtils.getBlocksWithin`
   - 用于节点发现
   - 文件：`util/world_utils.clj`

### 已完成的基础任务 ✅

1. ✅ **创建 `wireless/interfaces.clj`**
   - 所有接口定义完成
   - 使用 defprotocol

2. ✅ **修改 `block/wireless_node.clj`**
   - NodeTileEntity 实现完成
   - 包含充电逻辑

3. ✅ **GUI 系统完整实现**
   - Node GUI（XML + CGui双版本）
   - Matrix GUI（XML + CGui双版本）
   - 网络消息处理
   - 权限控制

完成"立即需要"的3项任务后，系统即可进行完整功能测试。

## 📊 进度跟踪

- 🔴 高优先级：8/13 完成 (61.5%)
  - ✅ IWirelessMatrix 接口
  - ✅ IWirelessNode 接口
  - ✅ IWirelessGenerator 接口
  - ✅ IWirelessReceiver 接口
  - ✅ NodeTileEntity 接口实现
  - ✅ TileMatrix 接口实现
  - ✅ 物品栏状态实现
  - ✅ ITickable 实现
  - ⏳ ImagEnergyItem 接口
  - ⏳ 方块状态管理
  - ⏳ 测试能量物品
  - ⏳ rebuild-block-state!
  - ⏳ get-actual-state

- 🟡 中优先级：6/11 完成 (54.5%)
  - ✅ ContainerNode 容器
  - ✅ GuiNode 界面（双版本）
  - ✅ ContainerMatrix 容器
  - ✅ GuiMatrix 界面（双版本）
  - ✅ GUI网络消息系统
  - ✅ Matrix网络消息处理器
  - ⏳ TileEntity同步系统
  - ⏳ 客户端状态接收
  - ⏳ WorldSavedData 集成
  - ⏳ 世界加载/卸载事件
  - ⏳ 方块范围搜索工具

- 🟢 低优先级：0/20 完成 (0%)
  - ⏳ 事件系统
  - ⏳ 测试和调试
  - ⏳ 性能优化
  - ⏳ 文档完善

- **总计**：14/44 完成 (31.8%)

**最后更新**：2025-11-26

## 📝 新增完成项

### 2025-11-26 更新：
1. ✅ **Wireless Matrix GUI 完整移植**
   - XML布局：`page_wireless_matrix.xml` (230行)
   - GUI实现：`matrix_gui_xml.clj` (420行)
   - 网络处理：`matrix_network_handler.clj` (180行)
   - 文档：
     - `WIRELESS_MATRIX_GUI_ANALYSIS.md` (600+行)
     - `WIRELESS_MATRIX_GUI_IMPLEMENTATION.md` (850+行)
     - `WIRELESS_MATRIX_GUI_MIGRATION_SUMMARY.md`
   - 特性：
     - 动态UI切换（3种状态）
     - 网络初始化表单
     - SSID/密码管理
     - 权限控制（客户端+服务端）
     - 代码复用率：55.7%

2. ✅ **Wireless Node GUI 完整移植**
   - XML布局：已完成
   - GUI实现：`node_gui_xml.clj`
   - 特性：
     - 动画系统（连接状态）
     - 直方图（能量+容量）
     - 属性编辑（节点名、密码）
     - 网络状态轮询

3. ✅ **核心接口定义完成**
   - IWirelessMatrix
   - IWirelessNode
   - IWirelessGenerator
   - IWirelessReceiver
   - IWirelessTile
   - IWirelessUser
