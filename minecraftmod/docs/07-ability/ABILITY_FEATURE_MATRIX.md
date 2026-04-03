# Ability系统功能矩阵（迁移追踪表）

> 本文档是旧版1.12 ability系统的完整行为规格，作为新实现的验收基线。
> 每行包含：旧行为 | 目标行为 | 目标模块 | 测试点

---

## 约束基线（不可违反）

| 约束 | 说明 |
|------|------|
| 依赖方向 | `platform → mcmod → ac`，单向，禁止反向引用 |
| 客户端隔离 | `net.minecraft.client.*` 仅允许在 forge client 子层，通过 `side/resolve-client-fn` 进入 |
| mc import 禁区 | `ac/` 和 `mcmod/` 均不得引入任何 `net.minecraft.*` 类 |
| 事件归属 | Forge 事件在 forge 层捕获 → 转发到 mcmod 分发入口 → ac 注册处理器 |
| 服务端权威 | 所有状态写入在服务端执行；客户端展示/预测，服务端回包确认 |
| 无旧API兼容层 | 不保留旧接口；行为兼容，代码结构可重构 |

---

## 子系统1：分类与技能注册

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 1.1 | `CategoryManager.register(category)` 分配 catID，维护全局注册表 | `register-category!` 将 category spec 写入 `category-registry`，分配自增 ID | `ac/ability/category.clj` | 注册后 `get-all-categories` 含该分类 |
| 1.2 | `Category.addSkill(skill)` → `skill.addedSkill(cat, id)` 回调，技能不可再修改 | `register-skill!` 将 skill spec 写入 `skill-registry`，关联 category-id | `ac/ability/skill.clj` | 注册后 `get-skills-for-category` 含该技能 |
| 1.3 | `Skill.initFullName()` = "categoryName.skillName" | `get-skill-full-id` 返回 `"cat-id/skill-id"` 格式字符串 | `ac/ability/skill.clj` | `(get-skill-full-id :esper :psychokinesis)` = `"esper/psychokinesis"` |
| 1.4 | `Skill.initIcon()` 加载纹理路径 | `get-skill-icon-path` 返回资源路径字符串 | `ac/ability/skill.clj` | 路径格式正确 |
| 1.5 | `isEnabled()` 读取配置 `ac.ability.category.$name.skills.$skill.enabled` | 读取 config 树对应 enabled 字段 | `ac/ability/config.clj` | 禁用后 `can-control?` 返回 false |
| 1.6 | `canControl()` = enabled && canControl flag | `can-control?` 检查 enabled + controllable flag | `ac/ability/skill.clj` | 双条件满足才可控 |
| 1.7 | `Controllable` 接口：(catID, controlID) 序列化键对 | `controllable-key` 返回 `[cat-id ctrl-id]` 键对 | `ac/ability/controllable.clj` | 序列化/反序列化往返一致 |

---

## 子系统2：学习条件

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 2.1 | `DevConditionLevel`：玩家等级 ≥ minLevel | `check-level-condition` 检查传入等级 | `ac/ability/service/learning.clj` | 等级5满足minLevel=3的条件 |
| 2.2 | `DevConditionDep`：父技能经验 ≥ requiredExp (0-1) | `check-dep-condition` 检查技能经验 | `ac/ability/service/learning.clj` | 父技能经验0.8满足0.5要求 |
| 2.3 | `DevConditionDeveloperType`：开发器类型 ≥ 技能最低要求 | `check-developer-type-condition` 检查开发器类型 | `ac/ability/service/learning.clj` | NORMAL开发器可解锁level3技能 |
| 2.4 | `DeveloperType` enum：PORTABLE(1-2级), NORMAL(3级), ADVANCED(4-5级) | `:portable :normal :advanced` 关键字，按等级门槛映射 | `ac/ability/skill.clj` | 各等级对应正确类型 |
| 2.5 | 所有条件全通过才能学习 | `can-learn-skill?` 对条件列表做逻辑与 | `ac/ability/service/learning.clj` | 任一条件不满足则返回false |
| 2.6 | 学习成功：设 BitSet，触发 `SkillLearnEvent` | `learn-skill!` 更新状态图，触发学习事件 | `ac/ability/service/learning.clj` | 学习后 `is-learned?` = true，事件被触发 |
| 2.7 | `getLearningStims()` ≈ 3 + level² × 0.5 | `skill-learning-cost` 使用同一公式 | `ac/ability/skill.clj` | level=3时开销为7.5 |

---

## 子系统3：经验与升级

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 3.1 | `addSkillExp(amount)` 按 skill_scale × global_rate 累积，单技能上限1.0 | `add-skill-exp` 同样的缩放与上限 | `ac/ability/service/learning.clj` | 超过1.0的部分被丢弃 |
| 3.2 | `addLevelProgress(amount)` 同步增加等级进度 | level-progress 随经验累积增加 | `ac/ability/service/learning.clj` | 积累足量后 `can-level-up?` = true |
| 3.3 | 升级阈值 = 当前等级可控技能数 × category.progIncrRate × global_rate | `level-up-threshold` 使用同一公式 | `ac/ability/service/learning.clj` | 3个可控技能×1.0×1.0 = 阈值3.0 |
| 3.4 | `canLevelUp()` = level<5 && progress ≥ threshold | `can-level-up?` 同条件 | `ac/ability/service/learning.clj` | level5不可再升 |
| 3.5 | `setLevel(n)` 重置 expAddedThisLevel，重算CP/过载上限，触发 LevelChangeEvent | `level-up!` 更新等级，重置进度，触发事件 | `ac/ability/service/learning.clj` | 升级后CP上限正确更新，事件触发 |
| 3.6 | 切换分类时重置所有技能经验为0 | `clear-skill-exps` 清空经验map | `ac/ability/service/learning.clj` | 切换后所有技能经验=0 |

---

## 子系统4：CP/Overload资源

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 4.1 | `perform(overload, cp)` 校验并消耗，创意模式跳过CP检查 | `perform-resource!` 同一校验，返回bool | `ac/ability/service/resource.clj` | CP不足返回false，创意模式始终true |
| 4.2 | `consumeCP(amt)` 扣减curCP，设置untilRecover冷却 | `consume-cp` 函数体现同逻辑 | `ac/ability/service/resource.clj` | 消耗后untilRecover被设置 |
| 4.3 | `addOverload(amt)` 叠加过载，到达上限触发 OverloadEvent | `add-overload` 叠加并触发事件 | `ac/ability/service/resource.clj` | 达到maxOverload时触发过载事件 |
| 4.4 | CP恢复：untilRecover==0时每tick恢复 0.0003×maxCP×lerp | `tick-cp-recovery` 同一公式 | `ac/ability/service/resource.clj` | 模拟若干tick后curCP见涨 |
| 4.5 | Overload恢复：独立计数器，recovery_speed配置 | `tick-overload-recovery` 独立恢复逻辑 | `ac/ability/service/resource.clj` | 过载完全回复后 overload-fine?=true |
| 4.6 | `setActivateState(bool)` 触发 AbilityActivateEvent/DeactivateEvent | `set-activated!` 并触发事件 | `ac/ability/service/resource.clj` | 激活态变化时事件被触发 |
| 4.7 | `canUseAbility()` = activated && overloadFine && !interfering | `can-use-ability?` 三条件与 | `ac/ability/service/resource.clj` | 任一条件false则全false |
| 4.8 | 干扰源API：addInterf/removeInterf/hasInterf | `add-interference! remove-interference! interfering?` | `ac/ability/service/resource.clj` | 有干扰源时can-use-ability?=false |
| 4.9 | `MaxCP / MaxOverload CalcEvent` 允许外部修改上限 | 触发 `:calc/max-cp` 和 `:calc/max-overload` 事件 | `ac/ability/event.clj` | 事件订阅者可调整上限值 |
| 4.10 | `init_cp[5], init_overload[5]` 按level配置初始值 | 同名配置数组 | `ac/ability/config.clj` | level=3时上限=配置中index[2]值 |
| 4.11 | 服务端1/3/10 tick间隔同步CP数据到客户端 | 脏标记+周期flush同步 | `forge/ability/sync.clj` | 客户端收到更新后HUD数值改变 |

---

## 子系统5：冷却

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 5.1 | 冷却键 = `(controlID << 2 \| subID)`，主冷却 subID=0 | `cooldown-key [ctrl-id sub-id]` | `ac/ability/model/cooldown_data.clj` | 同ctrl不同sub互不影响 |
| 5.2 | `doSet(ctrl, id, cd)` 取 max(prev, new)，永不缩短 | `set-cooldown` 取最大值 | `ac/ability/service/cooldown.clj` | 已有100tick冷却，设50时保持100 |
| 5.3 | 每tick递减，到0时从map移除 | `tick-cooldowns` 递减并清理 | `ac/ability/service/cooldown.clj` | tick后冷却值正确减少 |
| 5.4 | `isInCooldown(ctrl, id)` 查询是否在冷却 | `in-cooldown?` 检查map是否含该键 | `ac/ability/service/cooldown.clj` | 冷却中返回true，到期后false |

---

## 子系统6：Context生命周期

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 6.1 | Context状态机：CONSTRUCTED → ALIVE → TERMINATED（单向） | `context-status` 枚举同三状态，只可前向转移 | `ac/ability/context.clj` | 不可从ALIVE回到CONSTRUCTED |
| 6.2 | LocalManager客户端：分配clientID，追踪本地上下文 | 客户端context注册表，clientID自增 | `forge/client/key_runtime.clj` | 每次激活clientID递增 |
| 6.3 | ServerManager：收到M_BEGIN_LINK创建远端context，分配serverID | 服务端收到begin-link消息创建serverCtx | `ac/ability/service/context_mgr.clj` | 服务端ctx有有效serverID |
| 6.4 | keepalive：0.5s间隔，1.5s超时则终止 | 同间隔与超时阈值 | `ac/ability/service/context_mgr.clj` | 模拟2秒无keepalive→contextTERMINATED |
| 6.5 | 握手前消息缓冲，建立后原子刷出 | `context-buffer-message!`，alive后`flush-buffered!` | `ac/ability/context.clj` | CONSTRUCTED状态发送的消息在ALIVE后被正确路由 |
| 6.6 | 近邻广播范围25m，排除自身 | `find-nearby-players` 25m范围查询 | `forge/ability/network.clj` | 范围内玩家收到广播 |
| 6.7 | 死亡/分类切换时强制abort所有上下文 | `abort-all-contexts!` | `ac/ability/service/context_mgr.clj` | 死亡后所有ctx=TERMINATED |
| 6.8 | `MSG_TERMINATED` 在所有终止路径发送 | `send-terminated-msg!` 在所有terminate路径调用 | `ac/ability/context.clj` | 终止事件被监听到 |

---

## 子系统7：消息路由

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 7.1 | `sendToServer(ch, args)` 从客户端路由到服务端ctx | `ctx-send-to-server!` | `ac/ability/context.clj` | 服务端handler被调用 |
| 7.2 | `sendToClient(ch, args)` 从服务端路由到源玩家客户端ctx | `ctx-send-to-client!` | `ac/ability/context.clj` | 源玩家客户端收到消息 |
| 7.3 | `sendToLocal(ch, args)` 仅本地队列，无网络跳转 | `ctx-send-to-local!` | `ac/ability/context.clj` | 消息不经过网络 |
| 7.4 | `sendToExceptLocal(ch, args)` 广播到非源玩家 | `ctx-send-to-except-local!` | `ac/ability/context.clj` | 源玩家不收到，其他near players收到 |
| 7.5 | `sendToSelf(ch, args)` 本地投递，两端均可调用 | `ctx-send-to-self!` | `ac/ability/context.clj` | 本地回环处理 |
| 7.6 | @Listener(channel=..., side=...) 注解绑定 | `ctx-on!` 注册监听器 map | `ac/ability/context.clj` | 注册后对应channel可收到消息 |

---

## 子系统8：Preset与按键运行时

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 8.1 | PresetData：4预设×4键槽，稀疏编码 | 同结构 map `{preset-idx {key-idx controllable}}` | `ac/ability/model/preset_data.clj` | 设值后读值一致 |
| 8.2 | 客户端 keydown → 创建Context，发起M_BEGIN_LINK | `on-key-down!` 创建ctx，发送begin-link | `forge/client/key_runtime.clj` | 按键后网络消息被发送 |
| 8.3 | keytick → 发送MSG_KEYTICK | `on-key-tick!` 发送tick消息 | `forge/client/key_runtime.clj` | 每帧调用时发送tick |
| 8.4 | keyup → abort ctx，发送MSG_KEYUP | `on-key-up!` 终止ctx | `forge/client/key_runtime.clj` | 释放键后ctx=TERMINATED |
| 8.5 | abort（死亡等）→ 强制终止，发送MSG_KEYABORT | `on-key-abort!` 强制终止 | `forge/client/key_runtime.clj` | abort后ctx清空 |
| 8.6 | Preset切换：卸载旧委托，加载新委托，触发PresetSwitchEvent | `switch-preset!` 更新活跃preset，触发事件 | `forge/client/key_runtime.clj` | 切换后键位绑定变化 |
| 8.7 | 客户端请求同步Preset到服务端 | 发送preset-update消息，服务端校验存储 | `ac/ability/network.clj` + `forge` | 重登录后Preset保持 |

---

## 子系统9：HUD

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 9.1 | CP条（蓝色，cur/max） | HUD渲染CP进度条 | `forge/client/ability_hud.clj` | 客户端可见蓝色条 |
| 9.2 | Overload条（橙色，cur/max，过载恢复特殊显示） | HUD渲染Overload条，过载中显示恢复动画 | `forge/client/ability_hud.clj` | 过载时有视觉区分 |
| 9.3 | 激活态指示器（activated=true时高亮） | HUD激活状态可视 | `forge/client/ability_hud.clj` | 激活时高亮 |
| 9.4 | 4键位提示：图标+文字 | 4个按键提示槽位 | `forge/client/ability_hud.clj` | 对应技能图标和名称显示 |
| 9.5 | 按键提示 DelegateState：IDLE/ACTIVE/SUPPRESSED | 状态对应的视觉反馈 | `forge/client/ability_hud.clj` | ACTIVE时有激活样式 |

---

## 子系统10：技能树与开发器GUI

| # | 旧行为 | 目标行为 | 目标模块 | 测试点 |
|---|--------|----------|----------|--------|
| 10.1 | 技能节点可学条件可视化（绿/红指示） | `skill-tree-screen` 显示每个条件通过/未通过 | `forge/client/ability_gui.clj` | 条件变化时UI变化 |
| 10.2 | 技能经验进度条（0-1.0 per skill） | 进度条渲染 | `forge/client/ability_gui.clj` | 进度条与servicer端数据同步 |
| 10.3 | 升级按钮（canLevelUp时可点击） | 发送level-up请求，服务端校验执行 | `forge/client/ability_gui.clj` + network | 按钮点击→升级成功→CP上限变化 |
| 10.4 | 预设编辑：4×4网格，分配技能到键位 | `preset-editor` GUI |`forge/client/ability_gui.clj` | 分配后键位绑定正确 |
| 10.5 | 切换激活预设 | preset选择按钮，发送preset-switch请求 | network | 切换后HUD键位变化 |

---

## 子系统11：事件族

| # | 事件 | 触发时机 | 端 | 目标文件 |
|---|------|----------|----|----------|
| 11.1 | `AbilityActivateEvent` | `setActivateState(true)` | Server | `ac/ability/event.clj` |
| 11.2 | `AbilityDeactivateEvent` | `setActivateState(false)` | Server | `ac/ability/event.clj` |
| 11.3 | `SkillLearnEvent` | 技能学习成功 | Both | `ac/ability/event.clj` |
| 11.4 | `SkillExpAddedEvent` | 技能经验增加 | Server | `ac/ability/event.clj` |
| 11.5 | `SkillExpChangedEvent` | 技能经验值发生变化 | Server | `ac/ability/event.clj` |
| 11.6 | `LevelChangeEvent` | 等级变化 | Server | `ac/ability/event.clj` |
| 11.7 | `CategoryChangeEvent` | 分类被设置/清除 | Both | `ac/ability/event.clj` |
| 11.8 | `OverloadEvent` | curOverload 达到 maxOverload | Server | `ac/ability/event.clj` |
| 11.9 | `PresetUpdateEvent` | Preset同步后 | Both | `ac/ability/event.clj` |
| 11.10 | `PresetSwitchEvent` | 切换激活Preset | Both | `ac/ability/event.clj` |
| 11.11 | `CalcEvent.SkillAttack` | attack()前 | Server | `ac/ability/event.clj` |
| 11.12 | `CalcEvent.MaxCP` | 计算最大CP时 | Server | `ac/ability/event.clj` |
| 11.13 | `CalcEvent.MaxOverload` | 计算最大Overload时 | Server | `ac/ability/event.clj` |
| 11.14 | `CalcEvent.CPRecoverSpeed` | 计算CP回速时 | Server | `ac/ability/event.clj` |
| 11.15 | `CalcEvent.OverloadRecoverSpeed` | 计算过载回速时 | Server | `ac/ability/event.clj` |

---

## 子系统12：配置项

| # | 配置路径 | 类型 | 默认值/说明 | 目标文件 |
|---|----------|------|-------------|----------|
| 12.1 | `ability.global.init_cp` | float[5] | 各等级初始最大CP | `ac/ability/config.clj` |
| 12.2 | `ability.global.init_overload` | float[5] | 各等级初始Overload上限 | `ac/ability/config.clj` |
| 12.3 | `ability.global.add_cp` | float[5] | 各等级附加CP | `ac/ability/config.clj` |
| 12.4 | `ability.global.add_overload` | float[5] | 各等级附加Overload | `ac/ability/config.clj` |
| 12.5 | `ability.global.cp_recover_speed` | float | CP恢复速率基数 | `ac/ability/config.clj` |
| 12.6 | `ability.global.overload_recover_speed` | float | 过载恢复速率基数 | `ac/ability/config.clj` |
| 12.7 | `ability.global.cp_recover_cooldown` | int | CP恢复等待tick数 | `ac/ability/config.clj` |
| 12.8 | `ability.global.overload_recover_cooldown` | int | 过载恢复等待tick数 | `ac/ability/config.clj` |
| 12.9 | `ability.global.prog_incr_rate` | float | 全局经验增速倍率 | `ac/ability/config.clj` |
| 12.10 | `ability.calc.attackPlayer` | bool | 是否允许技能攻击玩家 | `ac/ability/config.clj` |
| 12.11 | `ability.calc.destroyBlocks` | bool | 是否允许技能破坏方块 | `ac/ability/config.clj` |
| 12.12 | `ability.category.$cat.enabled` | bool | 分类启用开关 | 分类DSL |
| 12.13 | `ability.skill.$cat.$skill.damage_scale` | float | 技能伤害倍率 | 技能DSL |
| 12.14 | `ability.skill.$cat.$skill.cp_consume_speed` | float | 技能CP消耗倍率 | 技能DSL |
| 12.15 | `ability.skill.$cat.$skill.exp_incr_speed` | float | 技能经验获取倍率 | 技能DSL |
| 12.16 | `ability.skill.$cat.$skill.enabled` | bool | 技能启用开关 | 技能DSL |

---

## 验收进度追踪

- [x] Phase 2: mcmod协议层 → 已落地核心消息目录与上下文消息扩展（含 ctx/channel）
- [x] Phase 3: ac领域层 → 已落地学习/资源/冷却/context主链路，剩余局部行为细节继续补齐
- [x] Phase 4: forge适配层 → 已落地生命周期钩子、网络桥接、同步推送主链路
- [x] Phase 5: DSL+技能内容 → 已完成首批分类与技能DSL，ArcGen/Railgun进入行为模板阶段
- [~] Phase 6: 客户端运行时+GUI → 已完成输入运行时与同步缓存，GUI完整等价仍在推进
- [x] Phase 7: 事件+配置 → 主要事件族与配置项已接入，后续按技能细化参数
- [ ] Phase 8: 逐行打勾验收（进行中）
