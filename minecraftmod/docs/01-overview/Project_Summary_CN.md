# 项目概览

当前架构以 core + target catalog 为中心：

- `api`：Java API/边界契约。
- `mcmod`：平台无关运行框架与 Clojure 逻辑。
- `ac`：内容层。
- `platform-src/common`：平台 target 共享 glue。
- `platform-src/minecraft/version/mc-1201`：Minecraft 1.20.1 API 适配。
- `platform-src/loader/forge`：Forge loader 入口与绑定。
- `platform-src/loader/fabric`：Fabric loader 入口与绑定。
- `platform-target`：唯一 Gradle 平台工程，按 `-PplatformTarget=<target-id>` 选择目标。

`platform-targets.json` 是唯一目标目录；不要通过目录名、任务名或字符串解析推导 target 行为。

## 常用命令

- 默认 Forge 编译：`.\gradlew.bat :platform:compileClojure`
- 指定 Forge：`.\gradlew.bat :platform:compileClojure "-PplatformTarget=forge target"`
- 指定 Fabric：`.\gradlew.bat :platform:compileClojure "-PplatformTarget=fabric target"`
- 架构验证：`.\gradlew.bat verifyCurrentPlatforms`

本仓库不包含真实 future loader 26.1 target；新增 target 必须先扩展 catalog 与 source component，而不是复制旧平台工程。