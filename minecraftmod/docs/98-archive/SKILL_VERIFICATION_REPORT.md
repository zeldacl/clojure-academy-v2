# 技能系统验证报告

## Arc Gen 技能差异分析

### ✅ 已修正

| 参数 | 原版 | 修正后实现 | 状态 |
|------|------|---------|------|
| CP消耗模式 | 即时消耗 | 即时消耗 | ✅ **已修正** |
| CP消耗值 | 30-70 | 30-70 | ✅ **已修正** |
| 过载值 | 18-11 | 18-11 | ✅ **已修正** |
| 冷却时间 | 15-5 ticks | 15-5 ticks | ✅ **已修正** |
| 伤害值 | 5-9 | 5-9 | ✅ **已实现** |
| 射程 | 6-15方块 | 6-15方块 | ✅ **已实现** |
| 射线追踪 | 完整实现 | 完整实现 | ✅ **已实现** |
| 方块交互 | 点燃/钓鱼 | 点燃/钓鱼 | ✅ **已实现** |
| 实体伤害 | 完整实现 | 完整实现 | ✅ **已实现** |
| 眩晕效果 | exp>=1.0 | exp>=1.0 (占位) | ⚠️ **待完善** |

### 原版实现细节

**文件**: `/AcademyCraft/src/main/java/cn/academy/ability/vanilla/electromaster/skill/ArcGen.java`

**核心机制**:
1. **即时瞬发技能** - 按键触发，一次性消耗资源
2. **射线追踪** - 从玩家眼睛位置发射射线
3. **实体伤害** - 命中实体造成伤害
4. **方块交互**:
   - 点燃概率: 0-60%（基于经验值）
   - 钓鱼概率: 10%（exp>0.5时）
5. **眩晕效果** - exp>=1.0时对敌人造成眩晕
6. **经验值获取**:
   - 命中实体: 0.0048-0.0072
   - 命中方块: 0.0018-0.0027

**参数公式**:
```java
// CP消耗: 30 + 40 * exp (30-70)
consumption = ctx.lerp(30, 70);

// 过载: 18 - 7 * exp (18-11)
overload = ctx.lerpf(18, 11);

// 冷却: 15 - 10 * exp (15-5 ticks)
cooldown = ctx.lerp(15, 5);

// 伤害: 5 + 4 * exp (5-9)
damage = ctx.lerp(5, 9);

// 射程: 6 + 9 * exp (6-15 blocks)
range = ctx.lerp(6, 15);

// 点燃概率: 0 + 60 * exp (0-60%)
igniteProb = ctx.lerp(0, 60);

// 钓鱼概率: exp > 0.5 ? 10% : 0%
fishProb = exp > 0.5 ? 0.1 : 0;

// 眩晕: exp >= 1.0
canStun = exp >= 1.0;
```

### 当前实现（已修正）

**文件**: `/ac/src/main/clojure/cn/li/ac/content/ability/electromaster/arc_gen.clj`

**修正内容**:
1. ✅ 改为`:pattern :instant`（即时瞬发）
2. ✅ CP/过载消耗改为即时消耗（30-70 CP, 18-11过载）
3. ✅ 冷却时间改为动态值（15-5 ticks）
4. ✅ 实现了射线追踪逻辑
5. ✅ 实现了实体伤害系统（5-9伤害）
6. ✅ 实现了方块交互（点燃概率0-60%，钓鱼概率10%）
7. ⚠️ 眩晕效果（占位实现，待药水效果系统完善）
8. ✅ 经验值获取机制正确

**核心实现**:
```clojure
(defskill! arc-gen
  :id             :arc-gen
  :category-id    :electromaster
  :pattern        :instant  ; 修正：即时瞬发
  :cost           {:down {:cp       (fn [{:keys [player-id]}]
                                      (bal/lerp 30.0 70.0 (skill-exp player-id)))
                          :overload (fn [{:keys [player-id]}]
                                      (bal/lerp 18.0 11.0 (skill-exp player-id)))}}
  :cooldown-ticks (fn [{:keys [player-id]}]
                    (int (bal/lerp 15.0 5.0 (skill-exp player-id))))
  :actions        {:perform! arc-gen-perform!})
```

**功能实现**:
- ✅ 射线追踪：使用`raycast/raycast-combined`
- ✅ 实体伤害：使用`entity-damage/apply-direct-damage!`
- ✅ 方块点燃：使用`block-manip/set-block!`设置火焰
- ✅ 钓鱼检测：使用`block-manip/liquid-block?`检测水方块
- ✅ 经验值：命中实体0.0048+0.0024*exp，命中方块0.0018+0.0009*exp

### 修正方案

#### 方案1: 完整修正（推荐）

```clojure
(defskill! arc-gen
  :id :arc-gen
  :category-id :electromaster
  :name-key "ability.skill.electromaster.arc_gen"
  :description-key "ability.skill.electromaster.arc_gen.desc"
  :icon "textures/abilities/electromaster/skills/arc_gen.png"
  :ui-position [24 46]
  :level 1
  :controllable? true
  :ctrl-id :arc-gen
  
  ;; 修正：即时瞬发模式
  :pattern :instant
  
  ;; 修正：即时消耗
  :cost {:instant {:cp (fn [exp] (+ 30 (* 40 exp)))
                   :overload (fn [exp] (- 18 (* 7 exp)))}}
  
  ;; 修正：动态冷却
  :cooldown-ticks (fn [exp] (int (- 15 (* 10 exp))))
  
  ;; 技能参数
  :params {:damage (fn [exp] (+ 5 (* 4 exp)))
           :range (fn [exp] (+ 6 (* 9 exp)))
           :ignite-prob (fn [exp] (* 0.6 exp))
           :fish-prob (fn [exp] (if (> exp 0.5) 0.1 0))
           :can-stun? (fn [exp] (>= exp 1.0))}
  
  :actions {:perform! arc-gen-perform!})

(defn arc-gen-perform!
  [{:keys [player-id exp world] :as ctx}]
  (let [player (get-player world player-id)
        damage (+ 5 (* 4 exp))
        range (+ 6 (* 9 exp))
        ignite-prob (* 0.6 exp)
        fish-prob (if (> exp 0.5) 0.1 0)
        can-stun? (>= exp 1.0)
        
        ;; 射线追踪
        ray-result (raytrace player range)
        hit-entity? (entity-hit? ray-result)
        hit-block? (block-hit? ray-result)]
    
    (cond
      ;; 命中实体
      hit-entity?
      (let [entity (get-hit-entity ray-result)]
        (damage-entity! entity damage player)
        (when can-stun? (apply-stun! entity))
        (add-exp! ctx (+ 0.0048 (* 0.0024 exp))))
      
      ;; 命中方块
      hit-block?
      (let [block-pos (get-hit-block-pos ray-result)]
        ;; 点燃方块
        (when (< (rand) ignite-prob)
          (ignite-block! world block-pos))
        ;; 钓鱼
        (when (and (< (rand) fish-prob) (is-water? world block-pos))
          (spawn-fish-hook! world block-pos player))
        (add-exp! ctx (+ 0.0018 (* 0.0009 exp))))
      
      ;; 未命中
      :else
      (add-exp! ctx 0.001))
    
    ;; 生成视觉效果
    (spawn-arc-effect! player ray-result)))
```

#### 方案2: 保持当前实现（不推荐）

如果要保持持续引导模式，需要：
1. 更新技能描述说明这是持续技能
2. 调整CP/过载消耗率以匹配总消耗量
3. 添加射线追踪和伤害逻辑

### 其他技能验证状态

| 技能 | 验证状态 | 问题 |
|------|---------|------|
| Arc Gen | ✅ 已修正 | 完成 |
| Current Charging | ✅ 被动技能 | 无 |
| Body Intensify | ✅ 被动技能 | 无 |
| Mine Detect | ✅ 被动技能 | 无 |
| Mag Movement | ⚠️ 待验证 | 需要检查参数 |
| Thunder Bolt | ⚠️ 待验证 | 需要检查参数 |
| Railgun | ⚠️ 待验证 | 需要EntityCoinThrowing |
| Thunder Clap | ⚠️ 待验证 | 需要检查参数 |
| Mag Manip | ❌ 缺少实体 | 需要EntityBlock |

### 建议的验证顺序

1. ✅ **高优先级** - 修正Arc Gen（已完成）
2. **中优先级** - 验证其他主动技能参数
3. **低优先级** - 验证被动技能效果

### 工作量估算

- ✅ Arc Gen完整修正: 已完成
- 其他技能参数验证: 2-3天
- 技能效果测试: 1-2天
- **剩余**: 3-5天

### 技术债务

1. ✅ **射线追踪系统** - 已实现（使用raycast协议）
2. ✅ **实体伤害系统** - 已实现（使用entity-damage协议）
3. ✅ **方块交互系统** - 已实现（使用block-manipulation协议）
4. ⏳ **状态效果系统** - 需要实现眩晕等状态效果（药水效果）
5. ✅ **经验值系统** - 已实现（skill-effects/add-skill-exp!）

Arc Gen修正已完成，基础设施已就绪，其他技能可以复用这些系统。
