# 构建与运行速查

在包含根目录 `settings.gradle` 的 **`minecraftmod`** 目录下执行命令。当前默认子工程：`api`、`mcmod`、`ac`、`forge-1.20.1`。

## 环境

- **Java 17**（与 Forge 1.20.1 工具链一致）。
- 使用仓库自带的 **Gradle Wrapper**（`gradlew.bat` / `gradlew`）。

## 常用 Gradle 任务

| 目的 | 命令（Windows） |
|------|-----------------|
| 运行 Forge 客户端 | `.\gradlew.bat :forge-1.20.1:runClient` |
| 运行 Forge 服务端（本地测） | `.\gradlew.bat :forge-1.20.1:runServer` |
| 数据生成 | `.\gradlew.bat :forge-1.20.1:runData` |
| 快速编译 Clojure（`ac`） | `.\gradlew.bat :ac:compileClojure` |
| 快速编译 Clojure（`mcmod`） | `.\gradlew.bat :mcmod:compileClojure` |
| Java 变更后刷新类路径 / LSP | `.\gradlew.bat :forge-1.20.1:classes`（或其它子工程 `:classes`） |
| 全量构建 | `.\gradlew.bat build` |

## 产物位置

- 各子模块输出的 JAR 一般在 **`forge-1.20.1/build/libs/`** 等目录下，文件名随 `gradle.properties` 中的 `mod_id`、`mod_version` 以及 Shadow / remap 任务配置变化。
- DataGenerator 输出目录以 **`forge-1.20.1/build.gradle`** 中 `runs.data` 的 `--output` 为准（通常为 `forge-1.20.1/src/generated/resources/`，并会合并进资源集）。

## Fabric（可选）

若已在 [`settings.gradle`](../../settings.gradle) 中取消注释 **`include 'fabric-1.20.1'`**，再使用 `:fabric-1.20.1:runClient` 等任务，并自行验证依赖与映射。

## 延伸阅读

- 架构与模块边界：[Runtime_And_DSL_CN.md](../02-architecture/Runtime_And_DSL_CN.md)
- 项目总览：[Project_Summary_CN.md](Project_Summary_CN.md)
- 数据生成细节：[../04-datagen/DataGenerator.md](../04-datagen/DataGenerator.md)
