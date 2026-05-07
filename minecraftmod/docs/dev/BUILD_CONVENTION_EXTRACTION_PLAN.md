# Build Convention Extraction Plan

## Purpose

规划如何把当前散落在平台模块（尤其 `forge-1.20.1`）中的构建魔法提取为统一 convention，从而降低新增 Loader / 新版本模块的复制成本。

## 当前痛点

- `forge-1.20.1/build.gradle` 承担了大量 AOT、remap、sourceSets 注入、字节码修正、运行任务前置依赖逻辑。
- `fabric-1.20.1/build.gradle` 也存在与其相似的共享源码注入模式。
- 新增平台或版本时，容易复制 build 文件并手工修改，导致逻辑漂移。

## 当前仓库重复项映射（Forge/Fabric）

| 重复主题 | Forge (`forge-1.20.1/build.gradle`) | Fabric (`fabric-1.20.1/build.gradle`) | 处理决策 |
|---|---|---|---|
| 注入共享源码 sourceSets | `sourceSets.main.{clojure,java,resources}` 注入 `ac/mcmod/api` | 同结构注入 `ac/mcmod/api` | **已抽取**到 `gradle/platform_build_helpers.gradle` 的 `configureInjectedPlatformSources` |
| Clojure 核心依赖（implementation） | `org.clojure:*` 三件套 | `org.clojure:*` 三件套 | **已抽取**到 `addSharedClojureRuntimeDeps` |
| Shadow 打包规则 | `shadowJar` 包含 Clojure 运行时 | 相同规则 | **已抽取**到 `configureSharedClojureShadowJar` |
| remap 输入策略 | `remapJar.inputFile = jar.archiveFile` | `remapJar.inputFile = shadowJar.archiveFile` | **保留差异**（loader 行为不同） |
| 运行时额外库配置 | `forgeRuntimeLibrary` 需显式声明 | Fabric 无该配置 | **保留差异**（仅 Forge） |
| AOT 输出合并/LVT 清理 | `stripClojureLVT` + `copyClojureClassesToJavaOutput` + run/check 依赖 | 当前未采用同套链路 | **暂不抽取**（先验证 Fabric 是否需要） |

> 注：本轮只做“低风险共性提取”，不改变 loader-specific 行为。

## 建议分阶段实施

### Phase 1 — 文档化现有规则

先确认并记录以下规则：

- 哪些 sourceSets 注入是必需的。
- AOT 输出目录与 Loom / Loader 运行时的关系。
- `stripClojureLVT` 的触发条件与必要性。
- `copyClojureClassesToJavaOutput` 的目的。
- 运行任务为何依赖这些前置任务。

### Phase 2 — 抽取 shared Gradle helpers

优先抽取：

- 共享 sourceSets 注入配置
- 共享 Clojure AOT 配置
- 共享 class copy / bytecode strip 任务
- 共享 compile/check 诊断任务注册函数

### Phase 3 — 提炼 platform convention plugin

目标：

- 通过 `buildSrc` 或本地 convention plugin 为 `forge-*` / `neoforge-*` / `fabric-*` 提供公共构建骨架。
- 平台模块只声明差异项：依赖、入口、映射、Loader API 与少量任务差异。

## 预期收益

- 新增平台模块更接近“填模板”而不是“复制几百行 Gradle 并祈祷”。
- 更容易统一修复 Clojure AOT / remap 问题。
- 减少多平台 build 脚本的行为漂移。

## 风险控制

- 不建议一开始就彻底改造为全新构建体系。
- 先让现有主线 Forge 保持可运行，再逐步提取共性。
- 每提取一步，都需要保留 compile/run 级回归验证。

## 完成标准

若后续满足以下条件，可视为 convention 抽取基本成功：

1. 新建一个 `loader-version` 模块时，不再需要复制大段现有 build 脚本。
2. AOT / remap / class copy 的关键逻辑只维护一份主实现。
3. `Forge`、`NeoForge`、`Fabric` 平台模块的差异大部分收敛为依赖与入口差异。
