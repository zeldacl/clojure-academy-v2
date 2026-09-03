# 系统维护文档索引

本文档用于维护视角的系统分层与优先级导航，仅覆盖系统架构、边界、流程与排障，不记录具体技能、具体 UI 页面、具体无线节点配置。
## 架构文档优先级与防回退规则

能力迁移的当前规范只认 `ABILITY_MIGRATION_MATRIX_CN.md`、`COMBAT_CORE.md`、
`VFX_CORE.md`、`NODE_LANGUAGE.md` 及代码中的 final catalog/vocabulary。带有“audit”、
“gaps”或“plan”标题的文档是历史证据，除非明确标注为当前规范，否则不得作为实现
指令或 TODO。任何修复都必须保持单一 final 路径，不得恢复旧 VM/recipe、旧节点字段、
大 primitive、singleton/channel 旁路或兼容 facade；实机验证缺口另开任务处理。

## 一级核心系统（优先维护）

| 系统 | 主文档 | 主要代码区域 |
|------|--------|--------------|
| 能力系统（Ability） | [ABILITY_SYSTEM_MAINTENANCE.md](ABILITY_SYSTEM_MAINTENANCE.md)（**reducer-only** 唯一写路径） | `ac/ability`、`ac/content/ability`、`mcmod/ability`、`forge1201/ability` |
| 战斗核心（Combat Core） | [COMBAT_CORE.md](COMBAT_CORE.md) | `:combat-core`、`ac/ability/service/combat_content.clj`、`ac/ability/service/combat_runtime.clj` |
| 特效核心（VFX Core） | [VFX_CORE.md](VFX_CORE.md) | `:vfx-core`、`ac/client/effect_controller.clj`、`ac/ability/client/fx_spec.clj` |
| Combat/VFX 平台缺口工单 | [COMBAT_VFX_PLATFORM_GAPS.md](COMBAT_VFX_PLATFORM_GAPS.md)（历史审计档案，非当前工单/架构文档） | 同上两行 |
| UI 系统（GUI/CGUI，legacy） | [UI_SYSTEM_MAINTENANCE.md](UI_SYSTEM_MAINTENANCE.md) | `ac/gui`、`mcmod/gui`、`forge1201/gui` |
| Presentation Runtime | [PRESENTATION_V3.md](../06-gui/PRESENTATION_V3.md) | `:presentation-core`、`:presentation-compiler`、`ac/gui/reactive/register.clj` |
| 无线系统（Wireless） | [WIRELESS_SYSTEM_MAINTENANCE.md](WIRELESS_SYSTEM_MAINTENANCE.md) | `ac/wireless` |
| 数据终端（Terminal） | [TERMINAL_SYSTEM_MAINTENANCE.md](TERMINAL_SYSTEM_MAINTENANCE.md) | `ac/terminal`、`ac/terminal/client` |
| 能量系统（Energy） | [ENERGY_SYSTEM_MAINTENANCE.md](ENERGY_SYSTEM_MAINTENANCE.md) | `ac/energy` |
| 网络系统（Network） | [NETWORK_SYSTEM_MAINTENANCE.md](NETWORK_SYSTEM_MAINTENANCE.md) | `mcmod/network`、`forge1201/events` |
| 注册系统（Registry） | [REGISTRY_SYSTEM_MAINTENANCE.md](REGISTRY_SYSTEM_MAINTENANCE.md) | `mcmod/registry`、`ac/registry`、`forge1201/registry.clj` |
| DSL 基础设施（Block/Item/NBT/Tile） | [DSL_SYSTEM_MAINTENANCE.md](DSL_SYSTEM_MAINTENANCE.md) | `mcmod/block`、`mcmod/item`、`mcmod/nbt`、`ac/block`、`ac/item` |
| Block 机器统一模式 | [AC_MODULE_LAYERING.md](../02-architecture/AC_MODULE_LAYERING.md#block-machines-shared) | `ac/block/machine/*`、`ac/block/gui/sync` |

## 二级支撑系统（持续补齐）

- 配置系统：`ac/config`、`mcmod/config`、`forge1201/config`
- 事件系统：`mcmod/events`、`forge1201/events.clj`
- 平台抽象系统：`mcmod/platform`、`forge1201/platform_*`
- 运行时与生命周期：`mcmod/runtime`、`mcmod/lifecycle.clj`、`forge1201/mod.clj`
- DataGen：`mcmod/datagen`、`forge1201/datagen`

## 统一维护模板

每个系统文档按同一结构组织：

1. 系统职责
2. 模块边界
3. 运行时流程
4. 扩展点
5. 排障手册
6. 变更风险
7. 兼容性约束

## 关联文档

- 构建与验证：[../dev/BUILD_AND_VERIFY_PLAYBOOK.md](../dev/BUILD_AND_VERIFY_PLAYBOOK.md)
- 工程布局：[../01-overview/PROJECT_LAYOUT.md](../01-overview/PROJECT_LAYOUT.md)
- 架构总览：[../02-architecture/Runtime_And_DSL_CN.md](../02-architecture/Runtime_And_DSL_CN.md)
