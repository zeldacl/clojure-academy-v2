# 构建、验证与排障速查

在包含根目录 `settings.gradle` 的 `minecraftmod` 目录执行命令。默认子工程为 `api`、`mcmod`、`ac`、`forge-1.20.1`（Fabric 默认未启用）。

## 环境

- Java 17
- Gradle Wrapper（`gradlew.bat` / `gradlew`）

## 常用任务（Windows）

| 目标 | 命令 |
|------|------|
| 运行 Forge 客户端 | `.\gradlew.bat :forge-1.20.1:runClient` |
| 运行 Forge 服务端 | `.\gradlew.bat :forge-1.20.1:runServer` |
| 运行 DataGen | `.\gradlew.bat :forge-1.20.1:runData` |
| 快速编译（ac） | `.\gradlew.bat :ac:compileClojure` |
| 快速编译（mcmod） | `.\gradlew.bat :mcmod:compileClojure` |
| 快速编译（forge） | `.\gradlew.bat :forge-1.20.1:compileClojure` |
| 刷新 Java/LSP | `.\gradlew.bat :forge-1.20.1:classes` |
| 全量构建 | `.\gradlew.bat build` |

## 推荐验证流程

1. 改动后先做快速编译：`.\gradlew.bat :forge-1.20.1:compileClojure`
2. 跑基线验证：`.\gradlew.bat verifyForgeBaseline`
3. 跑测试管线：`.\gradlew.bat verifyForgeTesting`
4. GameTest 需要单独观察时：`.\gradlew.bat runForgeGameTests` + `.\gradlew.bat validateForgeGameTestLog`

## 故障定位入口

- 编译问题二分定位：`.\gradlew.bat :forge-1.20.1:bisectCompileClojure`
- check 问题二分定位：`.\gradlew.bat :forge-1.20.1:bisectCheckClojure`
- 指定命名空间最小复现：
  - `-PcompileNsOnly=ns.a,ns.b`
  - `-PcheckNsOnly=ns.a,ns.b`
  - `-PcheckNsFile=build/bisect-check-subset.txt`

## 产物与输出

- 打包产物通常位于各子工程 `build/libs/`。
- DataGen 输出目录由 `forge-1.20.1/build.gradle` 的 data run 配置决定，默认 `forge-1.20.1/src/generated/resources/`。

## Fabric（可选）

若在 [`settings.gradle`](../../settings.gradle) 取消注释 `include 'fabric-1.20.1'`，再执行 Fabric 对应任务。Fabric 不在默认构建与验证基线中。

## 延伸阅读

- 项目布局：[PROJECT_LAYOUT.md](PROJECT_LAYOUT.md)
- 架构总览：[Runtime_And_DSL_CN.md](../02-architecture/Runtime_And_DSL_CN.md)
- 验证范围：[../testing/IMPLEMENTATION_SCOPE.md](../testing/IMPLEMENTATION_SCOPE.md)
