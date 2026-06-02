# Ability Core Spec V2

> 状态标签：**当前规范**。本文冻结 ability 核心重构后的服务端权威语义，用于实现、测试与回归验收。

**实现落点（2026-06）**：**Reducer-only** — 所有权威状态变更经 `command-runtime` → `reducer` → `runtime-store`；Context 协议由 `context-dispatcher` 承载 lifecycle 与合并读；context 业务字段在 `[:context-registry]`。已删除 `context-registry` 门面与 `:sync-*-data` reducer 命令。详见 [ABILITY_SYSTEM_MAINTENANCE.md](../04-systems/ABILITY_SYSTEM_MAINTENANCE.md)。

## Reducer-only 状态变更

与协议矩阵正交的实现约束：

| 域 | 变更方式 | 说明 |
|----|----------|------|
| ability / resource / cooldown / preset / develop / terminal | reducer 命令（如 `:consume-cp`、`:set-cooldown`） | 由 `state-tick`、技能 pattern、事件订阅组命令批次 |
| `[:context-registry]` 内字段 | reducer context 命令（如 `:context-assoc-skill-state`、`:update-context-status`） | 技能经 `context-skill-state`；lifecycle 经 `context-manager` + command |
| 持久化 / 客户端 sync 快照 | `:hydrate-player-state` | 仅 adapters；按 key 局部 `assoc`，非业务通用 API |
| 服务端 tick develop | `:server-tick` | 禁止 `:apply-server-tick-postprocess` 或 tick 后 ad-hoc store 写 |

资源结算、冷却、pattern 副作用的**语义**仍按下文「资源、冷却与 Pattern」；**实现**必须落到 reducer 命令，不得在 handler 内直接改 map。

## 行为基线

旧版 `Skill`/`ContextManager` 仅作为行为来源，不复制旧结构。本轮保留的语义是：

- `Skill`：注册后拥有稳定 full id/icon；`enabled && controllable` 才进入可控链路；单键生命周期严格分离 `on-key-down`、`on-key-tick`、`on-key-up`、`on-key-abort`。
- `ContextManager`：context 只允许 `constructed -> alive -> terminated` 单向流转；begin/establish/keepalive/terminate/channel/key 消息分层；建链前可缓冲，建立后按顺序 flush，终止后丢弃。
- keepalive：默认 1500ms 服务端超时，测试可通过 `ac.ctx.keepalive-timeout-ms` 覆盖；terminated context 默认保留 1000ms 后 purge。
- 近邻广播：`to-except-local` 只发送给源玩家之外的近邻玩家。
- 生命周期终止源：本地主动终止、服务端拒绝、keepalive 超时、死亡/登出/切换等统一进入 terminated。

下线项：旧短 token wire 兼容、旧 DataPart 隐式行为、输入包隐式建链。

## Context 协议

| 类别 | 消息 | 方向 | 语义 |
| ---- | ---- | ---- | ---- |
| 请求 | `ability:ctx/begin-link` | client -> server | 客户端请求服务端建立 skill context |
| 请求 | `ability:ctx/keepalive` | client -> server | 仅 ALIVE 且 owner 匹配时刷新 keepalive |
| 请求 | `ability:ctx/channel` | both | context 内 channel payload，必须有合法 keyword channel |
| 请求 | `ability:slot/key-*` | client -> server | 输入生命周期消息，必须 owner + ALIVE |
| 确认 | `ability:ctx/establish` | server -> client | 服务端接受 begin-link 并分配 server-id |
| 事件 | `ability:ctx/terminate` / `ability:ctx/terminated` | server -> client / local | 终止通知，本地清理 slot/context 表现 |

## 状态矩阵

| 消息 | missing ctx | constructed | alive owner | alive foreign | terminated |
| ---- | ----------- | ----------- | ----------- | ------------- | ---------- |
| begin-link | validate skill 后尝试 establish | 仅同 owner 可 establish | 仅同 owner 可 establish | `:ctx-not-owner` | 同 owner 可重新走服务端 gate，foreign 拒绝 |
| keepalive | `:ctx-not-found` | `:ctx-not-alive` | refresh | `:ctx-not-owner` | `:ctx-not-alive` |
| channel | `:ctx-not-found` | `:ctx-not-alive` | local dispatch + refresh | `:ctx-not-owner` | `:ctx-not-alive` |
| terminate | `:ctx-not-found` | terminate | terminate | `:ctx-not-owner` | idempotent |
| key-down | `:ctx-not-found` | `:ctx-not-alive` | start input or cooldown reject | `:ctx-not-owner` | `:ctx-not-alive` |
| key-tick/key-up | `:ctx-not-found` | `:ctx-not-alive` | only while input active | `:ctx-not-owner` | `:ctx-not-alive` |
| malformed payload | `:payload-invalid` | `:payload-invalid` | `:payload-invalid` | `:payload-invalid` | `:payload-invalid` |

乱序输入（例如 key-tick/key-up 早于 key-down）在 handler 边界记录 `:message-out-of-order`，不改变 context 状态。

## Skill 与 Preset Gate

服务端 establish 是权威入口。skill 必须同时满足：已注册、enabled、controllable、玩家已学习、玩家资源状态可用。preset 服务端写入只接受 learned + controllable slot；`nil/nil` 清空 slot。

客户端 UI 可以预检和展示，但不得作为最终授权来源。

## 资源、冷却与 Pattern

资源结算顺序固定为：calc -> consume -> overload -> growth -> recalc -> events。失败路径不得产生资源变更。

冷却键为 `[ctrl-id sub-id]`，主冷却使用 `:main`。设置冷却必须取 `max(existing, ticks)`；默认 cooldown 模式在 key-up 后应用主冷却，`:manual` 模式由技能自行设置。

pattern 副作用保持声明顺序：cost -> action/stage -> fx -> exp -> cooldown。cost fail、abort、terminate、cooldown reject 都必须可观测。

## 客户端表现

HUD slot 的 visual state 只由未 terminated 的 active context 驱动；cooldown 显示只读 authoritative cooldown data。

terminated event 必须清理 slot-context 映射，并让 flashing movement、charge coin、body-intensify charge、vecmanip crosshair/wave 收敛为 inactive。

## 观测与测试

统一拒绝原因：`:ctx-not-found`、`:player-uuid-missing`、`:ctx-not-owner`、`:ctx-not-alive`、`:payload-invalid`、`:message-out-of-order`。

核心自动化覆盖：

- `context_protocol_v2_test`：constructed buffer、establish flush、terminated route discard。
- `context_message_order_test`：乱序输入、重复 terminate、late keepalive。
- `context_ownership_test`：跨玩家伪造 ctx-id。
- `context_keepalive_timing_test`：keepalive timeout 与 `:timeout-terminated` 计数。
- `skill_control_gate_test`、`preset_handler_test`：服务端 skill/preset gate。
- `resource_settlement_test`、`cooldown_policy_test`、`patterns_test`：资源、冷却、pattern 顺序与失败路径。
- `hud_contract_test`、`client_overlay_lifecycle_test`：HUD/overlay 生命周期收敛。
