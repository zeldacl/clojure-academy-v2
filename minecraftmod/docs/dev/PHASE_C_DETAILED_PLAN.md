# Phase C: 能量与能力系统统一重构 - 详细计划

## Phase C 目标

将能量系统和能力系统从混乱的单体架构统一为：
- 独立的domain/service/api层 (与Phase B相同模式)
- 协议驱动的设计
- 完整的可测试性
- 动态发现替代硬编码依赖

---

## C1: 能量系统统一接口 (Energy Subsystem)

### 现状
- `energy/operations.clj` - 混合的操作函数
- `energy/imag-energy-item.clj` - 虚数能量物品
- 分散的能量逻辑在blocks/wireless中
- 无统一的能量管理方式

### 目标架构

```
energy/domain/
  energy_container.clj     - Pure energy record (已在wireless/domain/energy.clj)
  item_energy.clj          - Item能量模型
  node_energy.clj          - 节点能量模型
  
energy/service/
  item_manager.clj         - 物品能量管理
  node_manager.clj         - 节点能量管理
  transfer_service.clj     - 能量转移逻辑
  
energy/api/
  protocol.clj             - IEnergyManager, IEnergyItem, IEnergyNode
  impl.clj                 - 实现
  adapter.clj              - 向后兼容
```

### 关键协议

```clojure
(defprotocol IEnergyManager
  (get-energy [this id])
  (set-energy [this id amount])
  (transfer-energy [this from to amount])
  (subscribe-to-changes [this id callback]))

(defprotocol IEnergyItem
  (get-item-energy [this stack])
  (set-item-energy [this stack amount])
  (get-item-capacity [this stack])
  (charge-item [this stack amount]))

(defprotocol IEnergyNode
  (get-node-energy [this vblock])
  (set-node-energy [this vblock amount])
  (get-node-capacity [this vblock]))
```

### 迁移路径
1. 创建 energy/domain 纯数据模型
2. 创建 energy/service 业务逻辑
3. 创建 energy/api 协议和实现
4. 创建 energy/api/adapter 连接旧API
5. 更新blocks使用新API（通过adapter）
6. 删除旧的energy/operations.clj

---

## C2: 能力系统重构 (Ability Subsystem)

### 现状
- `ability/state/` - 能力状态管理
- `ability/server/` - 服务器端能力逻辑
- `ability/client/` - 客户端端渲染
- `ability/registry/` - 硬编码的能力注册
- `ability/model/` - 数据模型（零散）

**主要问题**:
- 状态机实现3个不同版本
- 硬编码命名空间引用
- 循环依赖 (ui ↔ state)
- 无统一的能力API

### 目标架构

```
ability/domain/
  ability.clj              - 能力纯数据record
  state_machine.clj        - 状态机模型
  activation.clj           - 激活事件model
  
ability/service/
  lifecycle.clj            - 能力生命周期
  state_manager.clj        - 状态转换管理
  activation_handler.clj   - 激活处理
  
ability/api/
  protocol.clj             - IAbility, IAbilityRegistry, IAbilityState
  impl.clj                 - 实现
  discovery.clj            - 动态发现
  
ability/registry/
  builtin/                 - 内置能力（血液逆流等）
```

### 关键协议

```clojure
(defprotocol IAbility
  (get-ability-id [this])
  (get-ability-name [this])
  (can-activate [this player context])
  (on-activate [this player context])
  (on-tick [this player context])
  (on-deactivate [this player context]))

(defprotocol IAbilityRegistry
  (register-ability [this ability])
  (get-ability [this id])
  (list-abilities [this])
  (discover-abilities [this]))  ; Dynamic discovery!

(defprotocol IAbilityState
  (get-state [this ability-id])
  (set-state [this ability-id state])
  (valid? [this ability-id]))
```

### 迁移路径
1. 创建 ability/domain 纯数据模型
2. 创建 ability/service 业务逻辑（使用Phase A的stateful!)
3. 创建 ability/api 协议和实现
4. 实现动态发现替代硬编码注册
5. 创建 ability/api/adapter 兼容旧系统
6. 逐个更新能力实现
7. 删除旧的 ability/registry、ability/state

---

## C3: 动态发现系统 (Discovery Layer)

### 现状
- 硬编码命名空间引用: `(require [ability.x] [ability.y]...)`
- 模块通过正则表达式搜索发现
- 无通用接口

### 新系统

```clojure
discovery/
  core.clj                 - 发现引擎
  scanner.clj              - 文件系统扫描
  loader.clj               - 动态加载
  registry.clj             - 发现缓存
```

### 关键功能

```clojure
(defn discover-implementations
  "动态发现所有协议实现"
  [protocol-name namespace-pattern])
  
(defn load-module
  "动态加载模块"
  [namespace-string])
  
(defn register-provider
  "注册动态提供者"
  [service-name namespace-string])
```

### 使用示例

```clojure
;; 而不是:
(require [ability.blood-retrograde] 
         [ability.body-intensify]
         ...)

;; 现在:
(discover/discover-abilities "cn.li.ac.ability.*")
(discover/register-provider :energy-manager "cn.li.ac.energy.api.impl")
```

---

## C4: 清理与迁移 (Cleanup Phase)

### 删除的文件
- `wireless/core/` - 旧的纯数据结构 (VBlock, vblock.clj)
- `wireless/data/` - 旧的原子/可变状态
- `energy/operations.clj` - 旧的混合操作
- `ability/registry/` - 旧的硬编码注册
- `ability/state/` - 旧的状态管理
- 所有旧的混合模块

### 保留/迁移的文件
- Phase A 基础层 → 继续使用
- Phase B 新API层 → 完全替代旧系统
- Phase C 新API层 → 在Phase B基础上构建

### 验证清单

```markdown
- [ ] 所有无线操作使用新API
- [ ] 所有能量操作使用新API
- [ ] 所有能力操作使用新API
- [ ] 删除所有硬编码namespace导入
- [ ] 实现完整的动态发现
- [ ] 所有测试通过
- [ ] 性能基准（确保优化）
- [ ] 生成迁移指南
```

---

## 预期改进

### 代码质量
- 从 6.1/10 → 8.5/10
- 模块化: 8/10 → 9.5/10
- 可重用性: 6/10 → 9/10
- 可测试性: 6/10 → 9.5/10
- 可扩展性: 4/10 → 9/10 (动态发现!)

### 代码量
- 重复代码: -60% (统一)
- 测试覆盖: +200% (独立层可测)
- 文档: +150% (协议定义清晰)

---

## 时间估计

| 任务 | 工作量 | 复杂度 |
|------|--------|--------|
| C1: Energy | 8-10小时 | 中 |
| C2: Ability | 12-15小时 | 高 |
| C3: Discovery | 6-8小时 | 中 |
| C4: Cleanup | 4-6小时 | 低 |
| **总计** | **30-40小时** | **高** |

---

## 执行策略

### 并行可能的任务
- C1和C2可部分并行 (独立系统)
- C3应该跨越C1-C2
- C4是最后一步

### 渐进式迁移
- 使用适配层避免一次性重写
- 通过feature flags逐步启用新系统
- 每个能力独立迁移

### 风险管理
- 保留旧代码直到所有新API验证完整
- 自动化所有测试（关键!)
- 详细的变更日志

---

## 后续阶段

### Phase D: 性能优化
- 空间索引优化
- 缓存策略
- 批量操作优化

### Phase E: 第三方集成
- 公共API发布
- 扩展框架
- 插件系统

### Phase F: 文档与社区
- API文档生成
- 最佳实践指南
- 贡献者指南
