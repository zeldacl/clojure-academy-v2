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

- [ ] **IWirelessMatrix** 接口
  - 包：`cn.academy.energy.api.block`
  - 方法：`getRange()`, `getCapacity()`, `getBandwidth()`
  - 用途：无线网络矩阵（中心节点）
  - 文件：`wireless/interfaces.clj`

- [ ] **IWirelessNode** 接口
  - 包：`cn.academy.energy.api.block`
  - 方法：`getEnergy()`, `setEnergy()`, `getMaxEnergy()`, `getBandwidth()`, `getRange()`, `getCapacity()`, `getPassword()`
  - 用途：无线节点
  - 文件：`wireless/interfaces.clj`

- [ ] **IWirelessGenerator** 接口
  - 包：`cn.academy.energy.api.block`
  - 方法：`getEnergy()`
  - 用途：能量生成器
  - 文件：`wireless/interfaces.clj`

- [ ] **IWirelessReceiver** 接口
  - 包：`cn.academy.energy.api.block`
  - 方法：`injectEnergy(double)`, `pullEnergy(double)`
  - 用途：能量接收器
  - 文件：`wireless/interfaces.clj`

#### 1.2 TileEntity 接口实现
- [ ] **NodeTileEntity** 实现 IWirelessNode
  - 当前状态：record 定义
  - 需要：通过 deftype 或 gen-class 实现 Java 接口
  - 文件：`block/wireless_node.clj`
  - 关键点：保持 Clojure 数据结构的优势

- [ ] **NodeTileEntity** 实现 IInventory
  - 2 槽位物品栏
  - 方法：`getSizeInventory()`, `getStackInSlot()`, `setInventorySlotContents()` 等
  - 难点：Java 接口方法较多

- [ ] **NodeTileEntity** 实现 ITickable
  - 方法：`update()` 每 tick 调用
  - 已实现逻辑：`update-node-tile!`
  - 需要：包装为 Java 接口方法

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

- [ ] **ContainerNode - 容器类**
  - 2 槽位容器
  - 槽位 0：输入（充能到节点）
  - 槽位 1：输出（从节点充能）
  - 文件：`gui/container_node.clj` 或使用现有 GUI DSL

- [ ] **GuiNode - 节点配置界面**
  - 显示能量条
  - 显示连接状态
  - 节点名称输入框
  - 密码输入框
  - 充电状态指示器（输入/输出）
  - 文件：`gui/gui_node.clj` 或使用现有 GUI DSL

### 5. 网络同步系统

- [ ] **实现真实网络包系统**
  - 当前状态：`send-sync-message` 只是 stub
  - 需要：Minecraft 网络包（Packet）系统
  - 数据：enabled, chargingIn, chargingOut, energy, name, password, placerName
  - 同步范围：20 格内玩家
  - 同步频率：每 10 tick
  - 文件：`network/sync.clj`

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

## 🎯 当前建议

根据缺失分析，**立即开始**以下任务：

1. ✅ **创建 `wireless/interfaces.clj`**
   - 定义所有 Java 接口的 Clojure 版本
   - 使用 `defprotocol` 或 `deftype`

2. ✅ **修改 `block/wireless_node.clj`**
   - 让 NodeTileEntity 实现必要接口
   - 实现 `rebuild-block-state!`

3. ✅ **创建 `item/test_battery.clj`**
   - 简单的测试能量物品
   - 用于验证充放电功能

完成以上三项后，系统即可进行基础功能测试。

## 📊 进度跟踪

- 🔴 高优先级：0/13 完成
- 🟡 中优先级：0/9 完成
- 🟢 低优先级：0/20 完成
- **总计**：0/42 完成

**最后更新**：2025-11-25
