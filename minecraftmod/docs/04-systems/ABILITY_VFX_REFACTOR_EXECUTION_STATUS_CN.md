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

## 当前清单

### 已补图（14/16 个历史迁移残留）

`arc-channel-session`、`arc-strike-transient`、`billboard-session`、
`block-progress-session`、`block-scan-transient`、`blood-retrograde-impact`、
`directed-blastwave-charge`、`directed-blastwave-wave`、
`particle-burst-trail-transient`、`target-box-session`、`target-mark-session`、
`ray-fan-transient`、`trajectory-ribbon-session`、`vortex-column-session`。

其中前 11 项已提交在 `ea5556f84`，后三项在 `e2815ad27`；均通过空渲染图专项审计后提交。

### 仍需 ABI 设计（2 个）

| VFX | 缺失语义 | 可执行下一步 |
|---|---|---|
| `blood-retrograde-charge` | payload 只有 `speed`，没有手部/世界锚点 | 从技能图补 `center`/`owner-anchor`，再接当前 `arc`/`beam` primitive |
| `first-person-motion-session` | `curves` 是相机/手部变换曲线，现有 scene 词汇无变换 primitive | 增加受控 `camera-motion` side-channel ABI，并由客户端消费；不得伪装成几何 |

## 验证命令

- `:ac:checkClojure`
- `:vfx-core:runVfxClojureTests`
- `:ac:runAcClojureTestsFast -Dac.test.only=cn.li.ac.vfx.empty-render-graph-audit-test`
- `:ac:runAcClojureTests`
- `verifyCurrentPlatforms`

本轮已通过：AC 全量 `647 tests / 6698 assertions`（持久化提交前后各一次），VFX Core `49 / 148`，以及 VFX 空图专项 `3 / 5`（14 个迁移残留已补图，2 个 ABI gap + 1 个合法 side-channel 仍为空）。最后三项图提交后若机器内存允许，必须重新执行上述全量门禁；在此之前不得把 50/36 标为“完全等价”。
