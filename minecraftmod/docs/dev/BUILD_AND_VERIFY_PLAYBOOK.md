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

## 范围说明

- 本手册以默认根工程（未 include Fabric）为准。
- 启用 Fabric 后需建立独立验证基线，不与 Forge 基线混用。
