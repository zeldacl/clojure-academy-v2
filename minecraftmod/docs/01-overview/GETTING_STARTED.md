# 构建、验证与排障速查

在包含根目录 `settings.gradle` 的 `minecraftmod` 目录执行命令。当前根工程为 `api`、`mcmod`、`ac`、`:platform`；平台目标由 `-PplatformTarget=<target-id>` 选择，默认目标来自 `platform-targets.json`。

## 环境

- Java 17
- Gradle Wrapper：`gradlew.bat` / `gradlew`

## 常用任务（Windows）

| 目标 | 命令 |
|------|------|
| 运行默认 Forge 客户端 | `.\gradlew.bat :platform:runClient` |
| 运行默认 Forge 服务端 | `.\gradlew.bat :platform:runServer` |
| 运行默认 Forge DataGen | `.\gradlew.bat :platform:runData` |
| 运行 Fabric 客户端 | `.\gradlew.bat :platform:runClient "-PplatformTarget=fabric-1.20.1"` |
| 快速编译 core | `.\gradlew.bat :ac:compileClojure :mcmod:compileClojure` |
| 快速编译 Forge target | `.\gradlew.bat :platform:compileClojure "-PplatformTarget=forge-1.20.1"` |
| 快速编译 Fabric target | `.\gradlew.bat :platform:compileClojure "-PplatformTarget=fabric-1.20.1"` |
| 生成 Forge 发布 jar | `.\gradlew.bat :platform:remapJar "-PplatformTarget=forge-1.20.1" -PreleaseAot` |
| 生成 Fabric 发布 jar | `.\gradlew.bat :platform:remapJar "-PplatformTarget=fabric-1.20.1" -PreleaseAot` |
| 架构门禁 | `.\gradlew.bat verifyCurrentPlatforms` |

## 推荐验证流程

1. 先跑 `verifyCurrentPlatforms`，确认架构门禁、manifest drift、target 硬编码和生成残留没有回归。
2. 对当前修改涉及的 loader 跑 `:platform:compileJava` / `:platform:compileClojure`，并显式传入对应 `-PplatformTarget=...`。
3. 需要跨 loader 对照时，用两次独立 Gradle invocation 或 CI matrix 分别跑 Forge/Fabric target。

## 输出

- 平台产物位于 `platform-target/build/libs/`。
- 发布 jar 由 `:platform:remapJar` 生成；每次只构建一个 target，必须显式传入对应的 `-PplatformTarget=<target-id>`。发布构建使用 `-PreleaseAot`。
- target metadata 生成到 `platform-target/build/generated/target-metadata/META-INF/academy-target.edn`。
- DataGen 输出位于 `platform-target/build/generated/datagen/<target-id>/`，不写回源码目录。
