# 实体系统实现状态

## 当前实现（3个实体）

### ✅ 已实现
1. **entity_mag_hook** - 磁钩投射物
   - 类型: scripted-projectile
   - 状态: 完整实现
   
2. **intensify_effect** - 强化效果
   - 类型: scripted-effect
   - 状态: 完整实现

3. **entity_coin_throwing** - 投掷硬币（新增）
   - 类型: scripted-projectile
   - 状态: 简化实现
   - 注意: 原版有特殊行为（跟随玩家XZ、自动返回背包），当前实现为基础投射物

## 需要自定义Java实现的实体（23个）

### 🔴 高优先级（影响核心技能）

#### 方块操控实体（2个）
- **EntityBlock** - 被操控的方块
  - 需求: Rigidbody物理、TileEntity同步、方块状态渲染
  - 影响技能: Mag Manip
  - 复杂度: 高
  
- **MagManipEntityBlock** - 磁力操控方块
  - 需求: 继承EntityBlock + 移动控制 + 伤害 + 电弧效果
  - 影响技能: Mag Manip
  - 复杂度: 高

#### 熔毁者投射物（3个）
- **EntityMdBall** - 熔毁球
  - 需求: 加速运动、双层渲染、Alpha淡入淡出
  - 影响技能: Electron Bomb
  - 复杂度: 中
  - 可能方案: 扩展scripted-projectile + 自定义渲染器

- **EntityMDRay** - 大型熔毁射线
  - 需求: 静态射线、三层圆柱渲染、粒子效果
  - 影响技能: Meltdowner
  - 复杂度: 高
  - 需要: 自定义射线实体类 + 专用渲染器

- **EntityMdRaySmall** - 小型熔毁射线
  - 需求: 静态射线、三层圆柱渲染、音效
  - 影响技能: Meltdowner, Scatter Bomb
  - 复杂度: 高
  - 需要: 继承EntityRayBase

### 🟡 中优先级（视觉效果）

#### 电击效果实体（3个）
- **EntityArc** - 电弧效果
  - 需求: 两点间电弧渲染、动画
  - 影响技能: Arc Gen, Thunder Bolt
  - 复杂度: 中

- **EntitySurroundArc** - 环绕电弧
  - 需求: 环绕实体的电弧效果
  - 影响技能: Body Intensify, Mag Manip
  - 复杂度: 中

- **EntityRailgunFX** - 电磁炮视觉效果
  - 需求: 轨迹渲染、粒子效果
  - 影响技能: Railgun
  - 复杂度: 中

#### 护盾实体（2个）
- **EntityDiamondShield** - 钻石护盾
  - 需求: 跟随玩家、护盾渲染、伤害吸收
  - 复杂度: 中

- **EntityMdShield** - 熔毁护盾
  - 需求: 跟随玩家、护盾渲染、伤害吸收
  - 影响技能: Light Shield
  - 复杂度: 中

#### 标记实体（3个）
- **EntityMarker** - 通用标记
  - 需求: 位置标记、渲染
  - 复杂度: 低

- **EntityTPMarking** - 传送标记
  - 需求: 位置标记、传送目标
  - 影响技能: Mark Teleport
  - 复杂度: 低

- **EntityRippleMark** - 涟漪标记
  - 需求: 涟漪动画渲染
  - 影响技能: 传送技能
  - 复杂度: 中

### 🟢 低优先级（辅助功能）

#### 其他投射物（5个）
- **EntityMdRayBarrage** - 射线弹幕
- **EntityBarrageRayPre** - 弹幕预备射线
- **EntitySilbarn** - Silbarn投射物
- **EntityBloodSplash** - 血液飞溅效果
- **LocalEntity** - 客户端本地实体基类

#### 采矿射线（3个）
- **EntityMineRayBasic** - 基础采矿射线
- **EntityMineRayExpert** - 专家采矿射线
- **EntityMineRayLuck** - 幸运采矿射线
  - 影响技能: Mine Ray系列
  - 复杂度: 中
  - 需要: 继承EntityRayBase + 方块破坏逻辑

#### 射线基类（1个）
- **EntityRayBase** - 射线实体基类
  - 需求: 射线物理、淡入淡出、宽度抖动
  - 复杂度: 高
  - 注意: 其他射线实体的基础

## 实现策略

### 阶段1: 基础投射物（当前）
- ✅ 使用scripted-projectile实现简单投射物
- ✅ EntityCoinThrowing（简化版）

### 阶段2: 自定义实体基础设施
1. 创建EntityRayBase Java类
   - 射线物理和渲染基础
   - 淡入淡出动画系统
   - 宽度抖动系统

2. 创建EntityBlock Java类
   - Rigidbody物理集成
   - 方块状态和TileEntity同步
   - 碰撞处理

### 阶段3: 核心实体实现
1. **MagManipEntityBlock** - 继承EntityBlock
2. **EntityMDRay** - 继承EntityRayBase
3. **EntityMdRaySmall** - 继承EntityRayBase
4. **EntityMdBall** - 自定义投射物

### 阶段4: 视觉效果实体
1. 电弧效果（EntityArc, EntitySurroundArc）
2. 护盾实体
3. 标记实体

### 阶段5: 辅助实体
1. 采矿射线
2. 其他投射物

## 技术债务

### EntityCoinThrowing 完整实现需求
原版特殊行为：
1. X/Z坐标锁定到玩家位置（水平跟随）
2. Y坐标独立抛物线运动
3. 完成后自动返回玩家背包
4. 自定义旋转动画渲染
5. 正面/反面消息显示

当前简化实现：
- 使用标准projectile物理
- 无背包返回逻辑
- 无自定义渲染

建议：
- 短期: 在Railgun技能中实现背包返回逻辑
- 长期: 创建自定义CoinThrowingEntity Java类

## 估算工作量

| 阶段 | 实体数 | 工作量 | 状态 |
|------|--------|--------|------|
| 阶段1 | 3个 | 1天 | ✅ 完成 |
| 阶段2 | 2个基类 | 3-4天 | ⏳ 待开始 |
| 阶段3 | 4个 | 4-5天 | ⏳ 待开始 |
| 阶段4 | 8个 | 5-7天 | ⏳ 待开始 |
| 阶段5 | 8个 | 3-5天 | ⏳ 待开始 |
| **总计** | **25个** | **16-22天** | **12%完成** |

## 下一步行动

1. ✅ 完成世界生成系统
2. ✅ 添加EntityCoinThrowing简化版
3. ⏳ 创建EntityRayBase Java类
4. ⏳ 创建EntityBlock Java类
5. ⏳ 实现MagManipEntityBlock
6. ⏳ 实现熔毁射线实体
