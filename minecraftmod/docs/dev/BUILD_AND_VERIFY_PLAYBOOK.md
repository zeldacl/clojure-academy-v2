# Build And Verify Playbook

统一的构建、验证与排障入口（默认 Forge 1.20.1 交付线）。

## 前置条件

- 在仓库 `minecraftmod` 根目录执行命令。
- Java 17。
- 使用 Gradle Wrapper：Windows `.\gradlew.bat`，Linux/macOS `./gradlew`。
- 若日志仍出现 `single-use Daemon`，检查本机环境变量 `GRADLE_OPTS` 是否包含 `-Dorg.gradle.daemon=false`；该环境变量会覆盖仓库 `gradle.properties`。
- 门禁耗时采样见 [GATE_PERFORMANCE.md](GATE_PERFORMANCE.md)，Windows 可运行 `.\scripts\perf\record_gate_performance.ps1 -WarmDaemon -Iterations 3`。

## 快速日常流程

优先按“改动范围 → 最小足够验证”选择单次 Gradle invocation，避免把相同依赖链拆成多次启动。

1. 快速编译：`.\gradlew.bat :forge-1.20.1:compileClojure`
1. 架构边界检查：`.\gradlew.bat verifyArchitectureBoundaries`
1. 平台无关单测：`.\gradlew.bat quickUnitTests`
1. 本地 PR 门禁：`.\gradlew.bat verifyLocalPrGate`
1. Forge 主线完整测试验证：`.\gradlew.bat verifyForgeTesting`

## 当前平台验证基线（推荐回归顺序）

推荐按层级选择，而不是每次都跑完整链路：

1. **Local quick**：目标模块 `compileClojure` + `verifyArchitectureBoundaries`。
2. **Local PR**：`verifyLocalPrGate`（等价于 `verifyCurrentPlatforms` + `quickUnitTests`，单次 Gradle invocation）。
3. **Full regression / nightly**：`verifyForgeTesting`，再按需追加 coverage、datagen parity 与严格反射实验。

若只改动单个测试覆盖的纯逻辑，优先使用 `-Dac.test.only=...` / `-Dmcmod.test.only=...` 跑目标测试；合并前再跑 `verifyLocalPrGate`。

## 运行与数据生成

- `.\gradlew.bat :forge-1.20.1:runClient`
- `.\gradlew.bat :forge-1.20.1:runServer`
- `.\gradlew.bat :forge-1.20.1:runData`

## 验证任务说明

- `unitTestCompile`：单元测试相关编译健康检查。
- `runAcUnitTests`：执行 `ac` 的 `clojure.test`（自动发现 `*_test.clj`），输出 namespace require 耗时与慢测试 namespace Top 10。
- `runMcmodUnitTests`：执行 `mcmod` 的 `clojure.test`（自动发现 `*_test.clj`），输出 namespace require 耗时与慢测试 namespace Top 10。
- `quickUnitTests`：单次 Gradle invocation 聚合执行 `runAcUnitTests` 与 `runMcmodUnitTests`。
- `verifyLocalPrGate`：本地 PR 推荐门禁，聚合 `verifyCurrentPlatforms` 与 `quickUnitTests`。
- `coverageAcClojureTests`：输出 `ac` 的 cloverage 覆盖率报告到 `ac/build/reports/coverage/`。
- `coverageMcmodClojureTests`：输出 `mcmod` 的 cloverage 覆盖率报告到 `mcmod/build/reports/coverage/`。
  - **Cloverage 排除命名空间**（见 `mcmod/build.gradle` 中 `--ns-exclude-regex`）：`cn.li.mcmod.client.obj`（体量极大的客户端胶水，单测 ROI 低）、`cn.li.mcmod.platform.position`（与 Cloverage 插桩后的 `IBlockPos` 协议身份冲突，测试里 `defrecord` 桩位需依赖未插桩协议）。总行覆盖率 ratchet 针对的是**已插桩**代码；排除上述命名空间后，百分比更接近「可纯 JVM 回归的核心逻辑」，不等于放弃客户端或坐标抽象的质量——后者由 Forge 侧与集成测试兜底。
- `verifyForgeBaseline`：Forge 侧基线检查。
- `verifyFabricBaseline`：Fabric compile 基线检查（minimal maintenance）。
- `verifyFabricStrictReflection`：显式执行 Fabric 严格反射 gate；当前不纳入 `verifyCurrentPlatforms`，需手动以 `-PfabricStrictReflection=true` 运行 `:fabric-1.20.1:checkClojure` 收敛历史告警。
- `runFabricSmoke`：执行 Fabric datagen 作为最小烟雾运行。
- `validateFabricSmokeLog`：校验 Fabric datagen 日志中的致命错误模式。
- `verifyFabricSmoke`：Fabric 最小烟雾验证入口（运行 + 日志校验）。
- `verifyForgeHookCoverage`：校验共享 Minecraft 实现键覆盖 AC hook catalog。
- `verifyFabricHookManifest`：校验 Fabric 支持/缺口清单是否覆盖 AC hook catalog。
- `verifyPlatformHookCoverage`：跨平台 hook 契约覆盖校验聚合入口。
- `verifyPlatformNoBusinessHookIds`：校验 `mcmod`/`mc1201`/平台 hook 层未硬编码业务 hook-id 字面量。
- `verifyPlatformNoAbilityMessageIds`：校验 `mcmod`/`mc1201`/平台主源码未硬编码 AC ability runtime message-id 字面量；message catalog 必须留在 `ac`。
- `verifyCurrentPlatforms`：当前纳入平台（Forge+Fabric）矩阵基线（compile + hook coverage + cleanup 守卫；**不含** smoke）。
- `verifyCurrentPlatformsWithSmoke`：在 `verifyCurrentPlatforms` 基础上追加 `verifyPlatformSmoke`（Fabric datagen 烟雾）。
- `verifyForgeClojureUnitTests`：手动运行 Forge/shared Clojure 单测（`:forge-1.20.1:runForgeClojureTests`）；**未**纳入 `verifyLocalPrGate` 或 `verifyForgeTesting`。
- `verifyArchitectureBoundaries`：跨层依赖边界扫描（阻止核心层误依赖平台/Minecraft 类）；任务按文件单次扫描并声明输入/通过标记输出，源文件未变时热运行可 UP-TO-DATE。
- `verifyCleanupResidueGuards`：聚合 cleanup/SSoT 回归守卫；子任务声明源输入与通过标记输出，源文件未变时热运行可跳过重复扫描。
- `verifyAbilityArchitectureStrict`：能力系统 **reducer-only** 门禁（禁止旁路写 store、旧 context mutation API、`:sync-*-data` 等）；见 [ABILITY_SYSTEM_MAINTENANCE.md](../04-systems/ABILITY_SYSTEM_MAINTENANCE.md)。
- `verifyAbilityNoDispatcherBusinessApiUsage`：禁止已删除的 `context-registry` 门面与 `ctx-reg/` 引用。
- `runForgeGameTests`：启动 GameTest 运行。
- `validateForgeGameTestLog`：校验 GameTest 日志中的失败/致命信息。
- `verifyForgeTesting`：聚合测试验证入口。

## 故障定位流程

### 1) `compileClojure` 或 `checkClojure` 失败

- 先缩小范围：
  - `-PcompileNsOnly=ns.a,ns.b`
  - `-PcheckNsOnly=ns.a,ns.b`
  - `-PcheckNsFile=build/bisect-check-subset.txt`
- 再自动定位首个失败命名空间：
  - `.\gradlew.bat :forge-1.20.1:bisectCompileClojure`
  - `.\gradlew.bat :forge-1.20.1:bisectCheckClojure`
- 默认 bisect 是 clean-safe；如需快速定位候选，可先加 `-PbisectMode=fast`，任务会在找到候选后自动用 clean-safe 单 namespace 确认。

### 2) GameTest 启动失败（测试未执行）

- 优先判定为运行时数据/注册引导问题。
- 重点检查 DataPack、registry、configured feature 引用一致性。

### 3) GameTest 执行后失败

- 先看 `validateForgeGameTestLog` 输出，区分：
  - 启动级 fatal 错误
  - 测试断言失败
- 先修启动级错误，再修用例级失败。

## 常见辅助命令

- 刷新 Java/LSP：`.\gradlew.bat :forge-1.20.1:classes`
- 快速编译子模块：
  - `.\gradlew.bat :ac:compileClojure`
  - `.\gradlew.bat :mcmod:compileClojure`
- 跑单/部分 ac 测试：
  - `.\gradlew.bat :ac:runAcClojureTests -Dac.test.only=cn.li.ac.foo-test`
- 跑单/部分 mcmod 测试：
  - `.\gradlew.bat :mcmod:runMcmodClojureTests -Dmcmod.test.only=cn.li.mcmod.foo-test`
- Forge GameTest 如需兼容旧环境并清理 25565 监听者，可显式增加：`-PforgeGameTestCleanPort25565=true`
- 生成覆盖率：
  - `.\gradlew.bat :ac:coverageAcClojureTests`
- 覆盖率 ratchet：当前仓库**未**提供 `scripts/ac_coverage_ratchet.sh` / `scripts/mcmod_coverage_ratchet.sh`；请手动对照 `ac/coverage-baseline.txt` 与 `mcmod/coverage-baseline.txt` 中的基线，或在本机自行实现同等校验后再纳入 CI。

## 范围说明

- 本手册以当前根工程（已 include Fabric）为准。
- 当前验证建议：`verifyForgeBaseline`（主线）+ `verifyFabricBaseline`（minimal maintenance）+ `verifyFabricSmoke` + `verifyPlatformHookCoverage` + `verifyCurrentPlatforms`（矩阵入口）。
- 新平台/版本接入时，请配合执行 [PLATFORM_ONBOARDING_CHECKLIST.md](PLATFORM_ONBOARDING_CHECKLIST.md)。

## Fabric 反射策略（分阶段）

- 默认：`checkClojure` 使用 `reflection='warn'`，保证基线可执行。
- 严格模式：增加参数 `-PfabricStrictReflection=true` 后，`checkClojure` 切换到 `reflection='fail'`。
- 建议策略：先收敛告警总量，再把 CI/主线切换到严格模式。
