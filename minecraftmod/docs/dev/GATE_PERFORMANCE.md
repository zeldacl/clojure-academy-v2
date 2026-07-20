# Gate Performance

本页记录构建、门禁与回归提速的可执行度量流程。目标不是“少测装作变快”，而是让每一层验证有稳定入口、可复现实测数据和明确 cache 边界。

## 采集入口

Windows 本地优先使用：

```powershell
.\scripts\perf\record_gate_performance.ps1 -WarmDaemon -Iterations 3
```

常用子集：

```powershell
.\scripts\perf\record_gate_performance.ps1 -TaskSpecs verifyArchitectureBoundaries,quickUnitTests -WarmDaemon -Iterations 3
```

配置缓存实验不要全局打开，使用额外参数单独采样：

```powershell
.\scripts\perf\record_gate_performance.ps1 -TaskSpecs verifyArchitectureBoundaries,quickUnitTests,":platform:compileClojure" -ExtraGradleArgs --configuration-cache -Iterations 2
```

2026-05-19 实测：`verifyArchitectureBoundaries --configuration-cache` 首次运行通过，第二次显示 `Reusing configuration cache` 且任务 `UP-TO-DATE`。其它任务仍需按本页脚本逐项实验，不默认全局开启。

脚本会写入：

- `build/reports/gate-performance/<timestamp>/metadata.json`：OS、Java、`GRADLE_OPTS`、JDK、任务列表等环境信息。
- `build/reports/gate-performance/<timestamp>/summary.csv`：任务、轮次、耗时、exit code、task outcome 摘要。
- `build/reports/gate-performance/<timestamp>/*.log`：每个任务的完整 Gradle 输出。

> 若日志出现 `single-use Daemon`，先检查用户环境变量 `GRADLE_OPTS` 是否带 `-Dorg.gradle.daemon=false`。该环境变量优先级高于仓库 `gradle.properties`。

## 推荐矩阵

| 层级 | 本地/CI | 入口 | 包含内容 | 何时运行 |
|---|---|---|---|---|
| Local quick | 本地 | 目标 `compileClojure` + `verifyArchitectureBoundaries` | 当前改动模块编译、跨层边界 | 内循环，每次小改后 |
| Local PR | 本地 | `verifyLocalPrGate` | `verifyCurrentPlatforms` + `quickUnitTests` | 提交/开 PR 前 |
| CI PR | CI | 单次 Gradle invocation 跑 `verifyLocalPrGate`，按需追加平台 compile | 可并行、可 cache 的 PR 门禁 | 每个 PR |
| Nightly full | CI | `verifyForgeTesting` + coverage + datagen parity + strict reflection experiments | GameTest、coverage、生成资源一致性、实验门禁 | 夜间/合并前完整回归 |

## Cache 白名单与边界

可优先缓存：

- Gradle user home（wrapper、modules、transforms）。
- Loom/Minecraft 下载与 remap cache。
- `verifyArchitectureBoundaries` 与 cleanup residue guard 的通过 marker。
- Clojure/Java 编译输出，前提是任务输入没有跨运行时污染。

谨慎缓存或先不缓存：

- Forge AOT bootstrap 相关目录：`platform-src/loader/forge/build/clojure/main` 与镜像到 `classes/java/main` 的 Clojure class。当前已通过 isolated stripped output 与编译前镜像清理降低污染风险，但 CI cache 仍建议先白名单再扩大。
- GameTest run 目录、`run/`、`run-data/`、`run-gametest/`。
- coverage HTML 全量报告；coverage 属于 nightly/显式门禁。

## 已落地的单任务提速点

- `verifyArchitectureBoundaries`：候选文件单次读取、规则内存匹配，并声明输入/通过 marker；热运行源文件未变时应 `UP-TO-DATE`。
- `verifyCleanupResidueGuards` 子任务：统一声明源输入与通过 marker，避免热运行时 25+ 个纯扫描 guard 反复执行。
- `stripClojureLVT`：Forge Clojure AOT 后处理输出到 `build/clojure/main-stripped`，避免原地改写 `compileClojure` 输出。
- `copyAcJavaClassesToPlatformOutput`：显式声明 AC Java class 输入，并使用 Copy 的增量/up-to-date 能力镜像到平台输出；由于目标是 Loom/dev launcher 需要的共享 `classes/java/main`，该任务不声明 build-cache 复用。
- ac/mcmod 测试：输出 require 总耗时、每 namespace 执行耗时与慢 namespace Top 10。
- `validateFabricSmokeLog` / `validateForgeGameTestLog`：先流式扫描 fatal pattern，遇到致命模式可提前失败。
- Forge bisect：默认保留 clean-safe；可用 `-PbisectMode=fast` 先热运行定位候选，再自动 clean-safe 确认单 namespace。

## 判定标准

- 每次优化至少保留一组 before/after 采样，建议热运行重复 3 次取中位数。
- 结果必须包含 task outcome 摘要，避免把“没有执行验证”误判为提速。
- 不以牺牲边界门禁、AOT bootstrap、GameTest/coverage 语义为代价换取耗时下降。