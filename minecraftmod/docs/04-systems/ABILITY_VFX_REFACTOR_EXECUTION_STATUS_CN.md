# 技能 / VFX 迁移执行状态

本文件记录基于 `main` 对照后的实际执行结果；不恢复旧 VM、旧 callback 或第二条执行路径。

## 已完成并已提交

- `2827091b2`：按 `content-id + owner + ability-id` 隔离并发技能会话。
- `4915174ba`：修复战斗与 VFX 生命周期边界。
- `9b727597c`：增加并发技能会话回归测试。
- `677aefad6`、`7a9e9fdaa`：恢复 radiation mark 的时长、攻击者继承和替换语义。
- `51960b1d1`、`8a0c65d12`：按 world/owner/effect 隔离 VFX 实例，修复 emitter anchor 与 one-shot 音效重复。
- `352efe13d`：将 radiation mark 写入 `combat-data`，接入玩家 NBT hydrate/persist，过期/清理同步持久状态。
- `ea5556f84`、`e2815ad27`：补回 11 个 V4 空渲染图的声明式 scene graph。
- `4e1671cca`：审计门禁收敛到当前仍需 ABI 设计的 5 个 VFX。
- `e3e68a185`：补回 `first-person-motion-session` 的手部变换 ABI，并恢复 `blood-retrograde-charge` 的 owner-local 移动速度侧通道。
- `f0e011001`：将 `first-person` primitive 纳入 6 个 loader 的接受集合，避免平台提交层静默丢弃。

## 当前清单

### 已补语义（16/16 个历史迁移残留）

`arc-channel-session`、`arc-strike-transient`、`billboard-session`、
`block-progress-session`、`block-scan-transient`、`blood-retrograde-impact`、
`directed-blastwave-charge`、`directed-blastwave-wave`、
`particle-burst-trail-transient`、`target-box-session`、`target-mark-session`、
`ray-fan-transient`、`trajectory-ribbon-session`、`vortex-column-session`。

其中前 11 项已提交在 `ea5556f84`，后三项在 `e2815ad27`；均通过空渲染图专项审计后提交。

`first-person-motion-session` 使用新的 `:first-person-motion` 声明式节点，帧层对曲线做确定性插值，复用现有平台 hand renderer；`blood-retrograde-charge` 保持空 render graph，因为它的真实语义是 owner-local walk-speed side channel，而不是可见几何。

### 当前不再有未分类缺口

空图审计现在只保留两个有明确消费者的 side-channel：`screen-flash-session` 与 `blood-retrograde-charge`；`first-person-motion-session` 已经是非空声明式图。

## 验证命令

- `:ac:checkClojure`
- `:vfx-core:runVfxClojureTests`
- `:ac:runAcClojureTestsFast -Dac.test.only=cn.li.ac.vfx.empty-render-graph-audit-test`
- `:ac:runAcClojureTests`
- `verifyCurrentPlatforms`

最终验证已通过：VFX Core `50 tests / 153 assertions`，ability-runtime `86 / 200`，AC 全量 `647 / 6698`，空图审计 `3 / 5`（无失败、无未分类图），以及 `verifyCurrentPlatforms` 全部通过。50 个技能和 36 个 VFX 当前均已纳入门禁覆盖；其中两个合法空图明确走 side-channel，其余历史迁移缺口已补齐。
