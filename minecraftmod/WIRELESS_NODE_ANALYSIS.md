# Wireless Node 方块功能分析文档

## 概述
Wireless Node（无线节点）是一个能量传输系统的方块，支持无线能量网络连接和物品充电功能。

## 核心类分析

### 1. BlockNode.java - 方块定义

#### 方块类型（NodeType 枚举）
三种节点类型，每种有不同的能量参数：

| 类型 | 最大能量 | 带宽 | 范围 | 容量 |
|------|---------|------|------|------|
| BASIC | 15,000 | 150 | 9 | 5 |
| STANDARD | 50,000 | 300 | 12 | 10 |
| ADVANCED | 200,000 | 900 | 19 | 20 |

#### 方块状态属性
- `CONNECTED` (PropertyBool) - 是否连接到无线网络
- `ENERGY` (PropertyInteger, 0-4) - 能量百分比显示（5个等级）

#### 方块属性
- 材质：ROCK（石头）
- 硬度：2.5
- 采集工具：镐子
- 采集等级：1
- 创造模式标签：AcademyCraft

#### 交互功能
- **右键点击**：打开 GUI 界面（GuiNode）
- **放置时**：记录放置者（玩家名称）
- **破坏时**：掉落物品栏内容（继承自 ACBlockContainer）

#### 渲染
- `getActualState`：根据 TileEntity 状态动态更新方块状态
  - 读取节点启用状态 → CONNECTED 属性
  - 计算能量百分比（0-4级）→ ENERGY 属性

### 2. ACBlockContainer.java - 基类容器方块

#### 功能
- 自动处理 GUI 打开逻辑
- 右键点击时（非潜行）打开 GUI
- 破坏时自动掉落物品栏内容
- 使用 MODEL 渲染类型

### 3. TileNode.java - TileEntity 实现

#### 实现的接口
- `IWirelessNode` - 无线节点接口
- `IInventory` - 物品栏（2个槽位）
- `ITickable` - 每 tick 更新

#### 数据字段

##### 能量系统
- `energy` (double) - 当前能量值
- `getMaxEnergy()` - 最大能量（根据节点类型）
- `getBandwidth()` - 能量传输带宽
- `getRange()` - 无线传输范围

##### 网络连接
- `enabled` (boolean) - 是否连接到无线网络（客户端渲染标志）
- `password` (String) - 网络密码
- `placerName` (String) - 放置者名称

##### 充电状态
- `chargingIn` (boolean) - 正在充电（从物品充能到节点）
- `chargingOut` (boolean) - 正在放电（从节点充能到物品）

##### 物品栏
- 槽位 0：输入槽（从物品充能到节点）
- 槽位 1：输出槽（从节点充能到物品）

#### 核心逻辑

##### update() - 每 tick 执行
1. **状态同步**（每 10 tick）：
   - 检查是否连接到 WirelessNet
   - 向附近玩家同步状态（20格范围）
   - 更新方块状态

2. **充电处理**：
   - `updateChargeIn()` - 从槽位0的物品吸取能量到节点
   - `updateChargeOut()` - 从节点输出能量到槽位1的物品

##### updateChargeIn() - 输入充电
```
如果槽位0有支持的物品：
  计算需求 = min(带宽, 最大能量 - 当前能量)
  从物品拉取能量
  更新节点能量
  设置 chargingIn 标志
```

##### updateChargeOut() - 输出充电
```
如果槽位1有支持的物品：
  计算输出 = min(带宽, 当前能量)
  向物品充能
  更新节点能量
  设置 chargingOut 标志
```

##### rebuildBlockState() - 更新方块状态
- 根据能量百分比更新 ENERGY 属性（0-4）
- 根据网络连接状态更新 CONNECTED 属性

#### 网络同步
- 使用 NetworkMessage 系统
- MSG_SYNC 消息包含：enabled, chargingIn, chargingOut, energy, name, password, placerName
- 客户端接收后更新本地状态

#### NBT 数据持久化
保存/加载：
- energy - 能量值
- nodeName - 节点名称
- password - 密码
- placer - 放置者名称

## 依赖的外部系统

### 能量系统
- `IFItemManager` - 物品能量管理器
  - `isSupported(ItemStack)` - 检查物品是否支持能量
  - `pull(ItemStack, amount, simulate)` - 从物品拉取能量
  - `charge(ItemStack, amount)` - 向物品充能

### 无线网络系统
- `WirelessHelper.getWirelessNet(node)` - 获取节点所在的无线网络
- `WirelessNet` - 无线网络对象

### GUI 系统
- `ContainerNode` - 容器类
- `GuiNode` - GUI 界面

## 实现要点总结

### 方块特性
1. 三种类型（meta 0-2）
2. 动态方块状态（连接状态 + 能量等级）
3. GUI 交互
4. 2槽位物品栏

### TileEntity 特性
1. 能量存储和传输
2. 物品充放电（双向）
3. 无线网络连接
4. 密码保护
5. 放置者追踪
6. 客户端/服务器同步

### 更新逻辑
1. 每10 tick 同步状态
2. 每 tick 处理充放电
3. 带宽限制能量传输速率
4. 能量百分比影响渲染

### 数据持久化
- 能量值
- 节点名称
- 密码
- 放置者

## 技术难点

1. **能量系统集成**：需要实现 IFItemManager 接口或创建 stub
2. **无线网络系统**：需要实现 WirelessHelper 和 WirelessNet 或创建 stub
3. **网络同步**：Clojure 中需要实现类似的同步机制
4. **动态方块状态**：根据 TileEntity 数据更新方块显示
5. **物品栏管理**：2槽位的充放电逻辑

## Clojure 实现策略

### 方块定义
使用 Block DSL 定义三种节点类型，每种作为独立的 defblock

### TileEntity
使用 defrecord 定义 NodeTileEntity，包含：
- 能量数据
- 物品栏（atom 或 vector）
- 充电状态
- 网络连接信息

### 更新循环
使用 Clojure 的调度机制（或 Java interop）实现 tick 更新

### Stub 系统
为未实现的外部依赖创建 stub：
- IFItemManager - 模拟物品能量操作
- WirelessHelper - 模拟网络连接检查
- NetworkMessage - 模拟网络同步

### GUI
使用现有 GUI DSL 或创建简单的容器界面
