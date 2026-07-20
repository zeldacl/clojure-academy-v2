# 平台扩展设计索引

本目录用于承接 **多 Loader / 多 Minecraft 版本扩展** 的正式设计文档，目标是把当前仓库中已有的架构经验沉淀为可重复执行的规则、模板与验证方案。

## 目标

- 保留现有四层结构：`api` → `mcmod` → `ac` → 平台模块。
- 把 `Forge 1.20.1` 的现有成功经验整理成可以复制到 `future loader` / `Fabric` / 新版本的设计模板。
- 降低“新增平台或版本时重新考古、重新发明流程”的成本。

## 阅读顺序

1. [01-target-architecture.md](01-target-architecture.md) — 目标架构全景图。
2. [02-module-boundaries.md](02-module-boundaries.md) — 各模块职责与红线。
3. [03-platform-bootstrap-contract.md](03-platform-bootstrap-contract.md) — 平台接入契约。
4. [04-loader-lifecycle-map.md](04-loader-lifecycle-map.md) — Forge / future loader / Fabric 启动链。
5. [05-build-and-aot-strategy.md](05-build-and-aot-strategy.md) — 构建、AOT、remap 策略。
6. [06-versioning-strategy.md](06-versioning-strategy.md) — 目录命名与版本演进规则。
7. [07-network-gui-datagen-bridges.md](07-network-gui-datagen-bridges.md) — 平台桥接专题。
8. [08-client-server-boundary-rules.md](08-client-server-boundary-rules.md) — 客户端/服务端边界规则。

## 与现有文档的关系

- [`../Platform_And_Fabric.md`](../Platform_And_Fabric.md) 继续保留为现状说明。
- 本目录中的文档则偏向 **目标状态、扩展策略、模板规则与执行依据**。
- 实施手册与验证矩阵放在 `docs/dev/` 和 `docs/testing/` 中维护。

## 当前结论摘要

- 当前四层架构方向正确，应继续坚持。
- 当前默认交付线是 `forge target`；`fabric target` 已纳入根构建并按 minimal maintenance（compile 基线）维护。
- 未来扩展的主要瓶颈不在业务层，而在平台契约显式化、构建模板化和验证矩阵制度化。
