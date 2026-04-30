# Wireless Node 方块功能分析文档

> 状态标签：**历史**（旧实现分析，非现行规范）

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
- `ITickable` - 每 tick 更新

#### 物品栏实现
- customState `:inventory` - 2个槽位（输入/输出）

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

#### IFItemManager - 物品能量管理器
实现 `EnergyItemManager` 接口，管理 `ImagEnergyItem` 物品的能量：

**核心方法**：
- `isSupported(ItemStack)` - 检查物品是否为 ImagEnergyItem
- `getEnergy(ItemStack)` - 从 NBT 读取能量值
- `getMaxEnergy(ItemStack)` - 获取物品最大能量
- `setEnergy(ItemStack, amount)` - 设置能量并更新耐久度显示
- `charge(ItemStack, amount, ignoreBandwidth)` - 充能到物品
  - 遵守带宽限制（除非 ignoreBandwidth=true）
  - 返回未充入的能量
- `pull(ItemStack, amount, ignoreBandwidth)` - 从物品拉取能量
  - 遵守带宽限制
  - 返回实际拉取的能量

**耐久度显示**：
- 使用 `setItemDamage` 显示能量百分比
- 公式：`damage = (1 - energy/maxEnergy) * maxDamage`

#### IFNodeManager - 节点能量管理器
实现 `IEnergyBlockManager`，为 `IWirelessNode` 提供能量操作：

- `isSupported(TileEntity)` - 检查是否为 IWirelessNode
- `getEnergy(TileEntity)` - 获取节点能量
- `setEnergy(TileEntity, energy)` - 设置节点能量
- `charge(TileEntity, amount, ignoreBandwidth)` - 充能到节点
  - 限制：min(amount, maxEnergy-current, bandwidth)
  - 返回未充入能量
- `pull(TileEntity, amount, ignoreBandwidth)` - 从节点拉取能量
  - 限制：min(amount, current, bandwidth)
  - 返回实际拉取能量

#### IFReceiverManager - 接收器能量管理器
实现 `IEnergyBlockManager`，为 `IWirelessReceiver` 提供能量操作：

- `isSupported(TileEntity)` - 检查是否为 IWirelessReceiver
- `getEnergy(TileEntity)` - 返回 0（接收器不存储能量）
- `setEnergy(TileEntity, energy)` - 不支持
- `charge(TileEntity, amount)` - 调用 `injectEnergy(amount)`
- `pull(TileEntity, amount)` - 调用 `pullEnergy(amount)`

### 无线网络系统

#### WirelessHelper - 无线系统辅助类
静态工具类，提供无线系统的常用操作：

**网络查询**：
- `getWirelessNet(IWirelessMatrix)` - 获取矩阵的网络
- `getWirelessNet(IWirelessNode)` - 获取节点的网络
- `isNodeLinked(IWirelessNode)` - 检查节点是否连接到网络
- `isMatrixActive(IWirelessMatrix)` - 检查矩阵是否激活
- `getNetInRange(World, x, y, z, range, max)` - 范围搜索网络

**节点连接查询**：
- `getNodeConn(IWirelessNode)` - 获取节点的连接对象
- `getNodeConn(IWirelessUser)` - 获取用户的连接对象
- `isReceiverLinked(IWirelessReceiver)` - 检查接收器是否连接
- `isGeneratorLinked(IWirelessGenerator)` - 检查生成器是否连接
- `getNodesInRange(World, pos)` - 获取范围内可连接的节点
  - 搜索范围：20格
  - 最大结果：100个
  - 过滤条件：节点范围够、容量未满

所有操作通过 `WiWorldData` 实现。

#### WirelessNet - 无线能量网络
管理一个 SSID 网络下的所有节点和能量平衡：

**数据字段**：
- `matrix` (VWMatrix) - 网络矩阵（中心节点）
- `ssid` (String) - 网络名称
- `password` (String) - 网络密码
- `nodes` (List<VWNode>) - 连接的节点列表
- `buffer` (double) - 能量缓冲区（最大2000）
- `disposed` (boolean) - 是否已销毁

**核心方法**：
- `addNode(VWNode, password)` - 添加节点
  - 验证密码
  - 检查容量（load < capacity）
  - 检查范围（distance ≤ matrix.range）
  - 自动从旧网络移除
- `removeNode(VWNode)` - 移除节点
- `dispose()` - 销毁网络
- `validate()` - 验证网络有效性（矩阵存在）
- `isInRange(x, y, z)` - 检查坐标是否在范围内

**能量平衡逻辑（tick）**：
1. 验证矩阵存在
2. 随机打乱节点顺序（公平性）
3. 计算总能量和最大能量：`sum`, `maxSum`
4. 计算平均百分比：`percent = sum / maxSum`
5. 遍历节点，传输能量：
   - 如果节点能量 > 平均值：拉取能量到缓冲区
   - 如果节点能量 < 平均值：从缓冲区推送能量
   - 遵守矩阵带宽限制
6. 清理断开的节点

**更新周期**：每 40 tick 更新一次

#### NodeConn - 节点连接管理
管理一个节点与其连接的生成器/接收器：

**数据字段**：
- `node` (VNNode) - 中心节点
- `receivers` (List<VNReceiver>) - 接收器列表
- `generators` (List<VNGenerator>) - 生成器列表
- `disposed` (boolean) - 是否已销毁

**核心方法**：
- `addReceiver(VNReceiver)` - 添加接收器
  - 检查容量（load < capacity）
  - 检查范围（distance ≤ node.range）
  - 自动从旧连接移除
- `removeReceiver(VNReceiver)` - 移除接收器
- `addGenerator(VNGenerator)` - 添加生成器
  - 检查容量和范围
- `removeGenerator(VNGenerator)` - 移除生成器
- `validate()` - 验证连接有效性
- `getLoad()` - 获取负载（receivers + generators）
- `getCapacity()` - 获取容量（节点的 capacity）

**能量传输逻辑（tick）**：
1. 验证节点存在
2. 从生成器收集能量：
   - 随机打乱生成器列表
   - 遍历生成器，调用 `getEnergy()`
   - 将能量充入节点
   - 遵守节点带宽限制
3. 向接收器分发能量：
   - 随机打乱接收器列表
   - 遍历接收器，调用 `injectEnergy()`
   - 从节点拉取能量
   - 遵守节点带宽限制
4. 清理断开的生成器/接收器

#### WiWorldData - 世界数据管理
每个世界的无线系统数据容器（WorldSavedData）：

**数据字段**：
- `world` (World) - 所属世界
- `netLookup` (Map) - 查找表
  - Matrix → WirelessNet
  - Node → WirelessNet
  - SSID → WirelessNet
- `nodeLookup` (Map) - 节点连接查找表
  - Node → NodeConn
  - Generator → NodeConn
  - Receiver → NodeConn
- `networks` (List<WirelessNet>) - 所有网络
- `connections` (List<NodeConn>) - 所有连接

**核心方法**：
- `get(World)` - 获取世界数据（自动创建）
- `getNonCreate(World)` - 获取世界数据（不创建）
- `createNetwork(matrix, ssid, password)` - 创建网络
- `getNetwork(matrix/node/ssid)` - 查找网络
- `getNodeConnection(node/user)` - 查找节点连接
- `rangeSearch(x, y, z, range, max)` - 范围搜索网络
- `tick()` - 更新所有网络和连接
  - 验证并清理失效的网络/连接
  - 调用所有网络和连接的 tick()

**持久化**：
- 保存所有网络和连接到 NBT
- 从 NBT 加载并重建查找表

#### VBlocks - 虚拟方块引用
位置-based TileEntity 引用系统（支持 NBT 序列化）：

**基类 VBlock<T>**：
- `x, y, z` - 方块坐标
- `ignoreChunk` - 是否忽略区块加载检查
- `isLoaded(World)` - 检查区块是否加载
- `get(World)` - 获取 TileEntity（类型安全）
- `distSq(VBlock)` - 计算距离平方
- `toNBT()` / 构造器(NBT) - 序列化支持

**子类**：
- `VWMatrix` - IWirelessMatrix 引用（ignoreChunk=true）
- `VWNode` - IWirelessNode 引用（ignoreChunk=false）
- `VNNode` - IWirelessNode 引用（用于 NodeConn，ignoreChunk=true）
- `VNGenerator` - IWirelessGenerator 引用
- `VNReceiver` - IWirelessReceiver 引用

**设计目的**：
- 避免直接持有 TileEntity 引用（不可序列化）
- 延迟加载（只在需要时获取 TileEntity）
- 区块卸载安全

#### WirelessSystem - 事件处理器
监听无线系统事件并协调操作：

**监听的事件**：
- `ServerTickEvent` - 每 tick 调用 WiWorldData.tick()
- `CreateNetworkEvent` - 创建网络
- `DestroyNetworkEvent` - 销毁网络
- `ChangePassEvent` - 修改密码
- `LinkNodeEvent` - 连接节点到网络
- `UnlinkNodeEvent` - 断开节点
- `LinkUserEvent` - 连接生成器/接收器到节点
- `UnlinkUserEvent` - 断开生成器/接收器

**职责**：
- 验证操作合法性
- 调用 WiWorldData 执行操作
- 取消非法操作（setCanceled）

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

1. **能量系统集成**：IFItemManager 实现，支持带宽限制的双向充放电
2. **无线网络系统**：WirelessNet 实现网络内节点的能量平衡算法
3. **节点连接系统**：NodeConn 管理节点与生成器/接收器的能量传输
4. **世界数据管理**：WiWorldData 维护全局查找表和持久化
5. **虚拟方块引用**：VBlocks 系统实现延迟加载和 NBT 序列化
6. **网络同步**：客户端/服务器状态同步
7. **动态方块状态**：根据 TileEntity 数据更新方块显示
8. **物品栏管理**：2槽位的充放电逻辑
9. **事件驱动架构**：WirelessSystem 通过事件协调所有操作

## 系统架构总结

### 层次结构
```
WiWorldData (世界级数据容器)
  ├── WirelessNet (SSID 网络)
  │     ├── Matrix (中心节点)
  │     └── Nodes (普通节点列表)
  │           └── 能量平衡算法
  └── NodeConn (节点连接)
        ├── Node (中心节点)
        ├── Generators (生成器列表)
        └── Receivers (接收器列表)
              └── 能量传输算法
```

### 能量流动路径
1. **生成器 → 节点**：Generator.getEnergy() → Node.charge()
2. **节点 → 接收器**：Node.pull() → Receiver.injectEnergy()
3. **节点 → 网络平衡**：Node ↔ WirelessNet.buffer ↔ 其他 Nodes
4. **物品 → 节点**：IFItemManager.pull(item) → Node.charge()
5. **节点 → 物品**：Node.pull() → IFItemManager.charge(item)

### 查找表设计
**netLookup**（网络查找）：
- `Matrix → WirelessNet` - 通过矩阵找网络
- `Node → WirelessNet` - 通过节点找网络
- `SSID → WirelessNet` - 通过名称找网络

**nodeLookup**（连接查找）：
- `Node → NodeConn` - 通过节点找连接
- `Generator → NodeConn` - 通过生成器找连接
- `Receiver → NodeConn` - 通过接收器找连接

### 更新周期
- **WirelessNet.tick()** - 每 40 tick（2秒）能量平衡
- **NodeConn.tick()** - 每 tick 能量传输
- **TileNode.update()** - 每 tick 物品充放电，每 10 tick 同步状态

## Clojure 实现策略

### 核心命名空间结构
```
wireless/
  ├── network.clj       - WirelessNet 实现
  ├── node_connection.clj - NodeConn 实现
  ├── world_data.clj    - WiWorldData 实现
  ├── virtual_blocks.clj - VBlocks 系统
  ├── helper.clj        - WirelessHelper 工具函数
  └── events.clj        - 事件处理系统
```

### 数据结构设计

#### WirelessNet
```clojure
(defrecord WirelessNet
  [world-data        ; WiWorldData 引用
   matrix            ; VWMatrix
   ssid              ; String
   password          ; String
   nodes             ; atom<vector<VWNode>>
   buffer            ; atom<double>
   disposed          ; atom<boolean>
   update-counter])  ; atom<int>
```

#### NodeConn
```clojure
(defrecord NodeConn
  [world-data       ; WiWorldData 引用
   node             ; VNNode
   receivers        ; atom<vector<VNReceiver>>
   generators       ; atom<vector<VNGenerator>>
   disposed])       ; atom<boolean>
```

#### WiWorldData
```clojure
(defrecord WiWorldData
  [world            ; World
   net-lookup       ; atom<map> - 网络查找表
   node-lookup      ; atom<map> - 连接查找表
   networks         ; atom<vector<WirelessNet>>
   connections])    ; atom<vector<NodeConn>>
```

#### VBlock
```clojure
(defrecord VBlock
  [x y z            ; int - 坐标
   ignore-chunk     ; boolean
   type])           ; keyword - :matrix/:node/:generator/:receiver
```

### 能量系统实现

#### IFItemManager（替换 stub）
```clojure
(defn is-energy-item? [item-stack]
  "检查物品是否为 ImagEnergyItem")

(defn get-item-energy [item-stack]
  "从 NBT 读取能量")

(defn set-item-energy! [item-stack amount]
  "设置能量并更新耐久度显示")

(defn charge-item [item-stack amount ignore-bandwidth?]
  "充能到物品，返回剩余能量")

(defn pull-from-item [item-stack amount ignore-bandwidth?]
  "从物品拉取能量，返回实际拉取量")
```

### 网络系统实现

#### WirelessHelper
```clojure
(defn get-wireless-net [matrix-or-node]
  "获取网络")

(defn is-node-linked? [node]
  "检查节点是否连接")

(defn get-nodes-in-range [world pos]
  "获取范围内的节点")
```

#### 能量平衡算法
```clojure
(defn balance-energy! [wireless-net]
  "网络内节点能量平衡
  1. 计算总能量和平均百分比
  2. 高于平均：拉取到缓冲区
  3. 低于平均：从缓冲区推送
  4. 遵守带宽限制")
```

#### 能量传输算法
```clojure
(defn transfer-from-generators! [node-conn]
  "从生成器收集能量到节点")

(defn transfer-to-receivers! [node-conn]
  "从节点分发能量到接收器")
```

### VBlocks 系统
```clojure
(defn create-vblock [tile-entity type]
  "创建虚拟方块引用")

(defn vblock-get [vblock world]
  "获取 TileEntity（延迟加载）")

(defn vblock-to-nbt [vblock]
  "序列化到 NBT")

(defn vblock-from-nbt [nbt]
  "从 NBT 反序列化")
```

### TileEntity 更新整合
```clojure
(defn update-node-tile! [tile]
  "更新节点 TileEntity
  1. 每 10 tick 检查网络连接
  2. 每 tick 处理物品充放电
  3. 同步状态到客户端")
```

### 事件系统（可选）
如果需要完整的事件驱动：
```clojure
(defmulti handle-wireless-event :event-type)

(defmethod handle-wireless-event :create-network [event]
  "创建网络")

(defmethod handle-wireless-event :link-node [event]
  "连接节点")
```

### 持久化策略
使用 Minecraft 的 WorldSavedData 或自定义文件：
```clojure
(defn save-wireless-data [world-data]
  "保存到 NBT")

(defn load-wireless-data [world nbt]
  "从 NBT 加载")
```

### 实现优先级
1. **VBlocks 系统** - 基础引用机制
2. **WiWorldData** - 数据容器和查找表
3. **WirelessNet** - 网络和能量平衡
4. **NodeConn** - 节点连接和传输
5. **WirelessHelper** - 工具函数
6. **能量管理器** - 替换 stub
7. **事件系统** - 协调操作
8. **TileEntity 整合** - 更新现有实现
