# 能力系统维护手册

## 系统职责

能力系统负责“能力定义、触发、冷却/状态管理、平台事件接入”的统一运行框架，业务能力位于 `ac`，抽象与契约位于 `mcmod`，平台绑定位于 `forge-1.20.1`。

## 模块边界

- `mcmod/ability`：能力抽象与通用协议。
- `ac/ability`：能力业务实现与能力装配。
- `forge1201/ability`：Forge 事件/生命周期适配。
- 禁止将平台 API 侵入 `ac` 与 `mcmod`。

## 运行时流程

1. 平台层调用 `cn.li.ac.core.init/init`，创建并注入 Ability RuntimeContainer。
2. runtime bridge 安装运行时组件（category/skill/event/lifecycle/context dispatcher）。
3. 能力内容初始化走 discovery（scanner + descriptor）加载技能命名空间。
4. 命令执行统一走 `command -> reducer -> effects`，再由 interpreter 处理副作用。
5. 写回状态并进行客户端同步（如需要）。

## 当前架构基线（2026-05）

- 状态变更主路径：`cn.li.ac.ability.service.command-runtime` + `cn.li.ac.ability.service.reducer`。
- `state_actions` 仅保留命令 facade，不再承担直接 store/update 旁路写。
- runtime 安装禁用 `alter-var-root` 回归路径（关键能力运行时命名空间已改为 atom 持有 + 显式 install）。
- context 子系统已开始拆分：
	- domain：`cn.li.ac.ability.service.context-domain`
	- repository：`cn.li.ac.ability.service.context-repository`
	- dispatcher：`cn.li.ac.ability.service.context-dispatcher`（兼容薄层）
- discovery 来源：
	- classpath scanner：`cn.li.ac.discovery.scanner`
	- descriptor：`ac/src/main/resources/ac/ability/providers.edn`
	- 初始化后执行 provider registry freeze。

## 关键守卫与回归

- 架构守卫测试：`cn.li.ac.ability.architecture-guard-test`
	- 防止关键运行时安装命名空间回归到 `alter-var-root`。
- discovery/能力初始化回归：
	- `cn.li.ac.ability.discovery-test`
	- `cn.li.ac.content.ability-test`
- 编译门禁：建议每次重构后运行 `unitTestCompile`。

## 扩展点

- 新增能力类型时优先扩展 `mcmod` 契约，再在 `ac` 实现。
- 需要平台事件时只在 `forge1201/ability` 新增桥接。
- 扩展保持“声明式能力定义 + 独立执行函数”。

## 排障手册

- 能力未触发：检查平台事件绑定与能力注册链路。
- 能力触发无效果：检查业务执行函数与状态写回逻辑。
- 客户端显示不同步：检查网络同步消息与客户端渲染入口。

## 变更风险

- 生命周期顺序变更可能导致能力注册时机失效。
- 能力状态数据结构变更可能造成存档兼容风险。

## 兼容性约束

- 当前重构阶段允许破坏旧内部 API，但能力标识建议保持稳定，避免历史数据与配置引用失效。
- 对外 SPI 变更应同步更新维护文档与测试，确保 downstream 模块可诊断。
