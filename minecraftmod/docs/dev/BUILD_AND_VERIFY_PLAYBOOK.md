# Build And Verify Playbook

统一的构建、验证与排障入口（默认 Forge 1.20.1 交付线）。

## 前置条件

- 在仓库 `minecraftmod` 根目录执行命令。
- Java 17。
- 使用 Gradle Wrapper：Windows `.\gradlew.bat`，Linux/macOS `./gradlew`。

## 快速日常流程

1. 快速编译：
   - `.\gradlew.bat :forge-1.20.1:compileClojure`
2. 基线验证：
   - `.\gradlew.bat verifyForgeBaseline`
3. 全量测试验证：
   - `.\gradlew.bat verifyForgeTesting`

## 运行与数据生成

- `.\gradlew.bat :forge-1.20.1:runClient`
- `.\gradlew.bat :forge-1.20.1:runServer`
- `.\gradlew.bat :forge-1.20.1:runData`

## 验证任务说明

- `unitTestCompile`：单元测试相关编译健康检查。
- `runAcUnitTests`：执行 `ac` 的 `clojure.test`（自动发现 `*_test.clj`）。
- `runMcmodUnitTests`：执行 `mcmod` 的 `clojure.test`（自动发现 `*_test.clj`）。
- `coverageAcClojureTests`：输出 `ac` 的 cloverage 覆盖率报告到 `ac/build/reports/coverage/`。
- `coverageMcmodClojureTests`：输出 `mcmod` 的 cloverage 覆盖率报告到 `mcmod/build/reports/coverage/`。
  - **Cloverage 排除命名空间**（见 `mcmod/build.gradle` 中 `--ns-exclude-regex`）：`cn.li.mcmod.client.obj`（体量极大的客户端胶水，单测 ROI 低）、`cn.li.mcmod.platform.position`（与 Cloverage 插桩后的 `IBlockPos` 协议身份冲突，测试里 `defrecord` 桩位需依赖未插桩协议）。总行覆盖率 ratchet 针对的是**已插桩**代码；排除上述命名空间后，百分比更接近「可纯 JVM 回归的核心逻辑」，不等于放弃客户端或坐标抽象的质量——后者由 Forge 侧与集成测试兜底。
- `verifyForgeBaseline`：Forge 侧基线检查。
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
- 生成覆盖率：
  - `.\gradlew.bat :ac:coverageAcClojureTests`
- 本地校验覆盖率不低于基线（与 CI 一致，Linux/macOS）：
  - `bash scripts/ac_coverage_ratchet.sh ac/coverage-baseline.txt ac/build/reports/coverage/index.html`
  - `bash scripts/mcmod_coverage_ratchet.sh mcmod/coverage-baseline.txt mcmod/build/reports/coverage/index.html`

## 范围说明

- 本手册以默认根工程（未 include Fabric）为准。
- 启用 Fabric 后需建立独立验证基线，不与 Forge 基线混用。
