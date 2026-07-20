# Build and Verify Playbook

当前平台构建只有一个 Gradle 工程：`:platform`。具体目标由 `platform-targets.json` 声明，并通过 `-PplatformTarget=<target-id>` 选择；默认目标为 `forge target`。

## 快速入口

- 架构门禁：`.\gradlew.bat verifyCurrentPlatforms`
- Forge 编译：`.\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge target"`
- Fabric 编译：`.\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric target"`
- Forge 客户端：`.\gradlew.bat :platform:runClient "-PplatformTarget=forge target"`
- Fabric 客户端：`.\gradlew.bat :platform:runClient "-PplatformTarget=fabric target"`

## 验证顺序

1. `verifyCurrentPlatforms`：确认旧目录、旧 SPI、重复 capability owner、AOT manifest drift、旧 target 硬编码和 platform-src 生成残留没有回归。
2. 按修改范围运行单 target 编译；不要在 Gradle 子工程名里表达 loader/version。
3. 跨 loader 对照由 CI matrix 或外部脚本分别调用 `:platform` 完成。

## 产物与生成文件

- 平台产物：`platform-target/build/libs/`
- target metadata：`platform-target/build/generated/target-metadata/META-INF/academy-target.edn`
- DataGen：`platform-target/build/generated/datagen/<target-id>/`

DataGen 不写入源码目录；需要 parity 时比较生成目录或 manifest，而不是提交生成残留。