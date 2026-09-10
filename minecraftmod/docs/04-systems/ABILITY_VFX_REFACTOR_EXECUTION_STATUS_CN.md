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

已通过：AC 全量 `647 tests / 6698 assertions`（持久化改动阶段），VFX Core `49 / 148`，以及 VFX 空图专项此前的 `3 / 5`。`e3e68a185` 之后新增了 first-person 帧测试、charge 侧通道测试和三套平台接线；由于 Windows 当前无法为 Gradle JVM 提交约 512 MiB 虚拟内存（DOS errno 1455），必须在资源恢复后重新执行上述全量门禁，特别是 `:vfx-core:runVfxClojureTests`、`:ac:runAcClojureTestsFast -Dac.test.only=cn.li.ac.vfx.empty-render-graph-audit-test` 与 `verifyCurrentPlatforms`，才能把 50/36 标为“门禁全绿”。
