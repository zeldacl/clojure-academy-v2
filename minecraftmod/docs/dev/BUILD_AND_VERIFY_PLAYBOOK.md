# Build and Verify Playbook

当前平台构建只有一个 Gradle 工程：`:platform`。具体目标由 `platform-targets.json` 声明，并通过 `-PplatformTarget=<target-id>` 选择；默认目标为 catalog 中声明的 `forge-1.20.1`。

## 快速入口

- 架构门禁：`.\gradlew.bat verifyCurrentPlatforms`
- Forge 编译：`.\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1"`
- Fabric 编译：`.\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1"`
- Forge 客户端：`.\gradlew.bat :platform:runClient "-PplatformTarget=forge-1.20.1"`
- Fabric 客户端：`.\gradlew.bat :platform:runClient "-PplatformTarget=fabric-1.20.1"`
- Forge 发布 jar：`.\gradlew.bat :platform:remapJar "-PplatformTarget=forge-1.20.1" -PreleaseAot`
- Fabric 发布 jar：`.\gradlew.bat :platform:remapJar "-PplatformTarget=fabric-1.20.1" -PreleaseAot`
- DataGen parity：`.\gradlew.bat compareDatagenParityManifests`

## 发布 jar

最终发布 jar 使用 Loom 的 `:platform:remapJar` 生成。这个任务会把开发命名空间下的编译输出 remap/reobfuscate 成 Loader 可运行的发布 jar。每次只构建一个 target，目标由 `-PplatformTarget=<target-id>` 显式选择；发布构建必须带 `-PreleaseAot`，以使用发布 AOT 输出并剥离本地变量表。

```powershell
.\gradlew.bat :platform:remapJar "-PplatformTarget=forge-1.20.1" -PreleaseAot
.\gradlew.bat :platform:remapJar "-PplatformTarget=fabric-1.20.1" -PreleaseAot
```

发布件只取 `platform-target/build/libs/` 中由 `remapJar` 生成的非 `shadow` jar，文件名由 `platform-targets.json` 中当前 target 的 `artifact.baseName`、项目版本和 `artifact.classifier` 决定。

不要发布这些中间产物：

- `platform-target/build/devlibs/*.jar`：开发命名 jar，未作为最终 remap 输出。
- `platform-target/build/libs/*-shadow.jar` / `*-shadow-stripped.jar`：Fabric remap 输入或中间产物，不是最终发布 jar。
- `:platform:jar` 或 `:platform:shadowJar` 的直接输出。

## 验证顺序

1. `verifyCurrentPlatforms`：确认架构门禁、重复 capability owner、AOT manifest drift、target 硬编码和 platform-src 生成残留没有回归。
2. 按修改范围运行单 target 编译；不要在 Gradle 子工程名里表达 loader/version。
3. 跨 loader 对照由 CI matrix 分别调用 `:platform` 完成；DataGen 对照使用各 target 生成的 hash manifest。

## 产物与生成文件

- 平台产物：`platform-target/build/libs/`
- target metadata：`platform-target/build/generated/target-metadata/META-INF/academy-target.edn`
- DataGen：`platform-target/build/generated/datagen/<target-id>/`
- DataGen hash manifest：`platform-target/build/generated/datagen/<target-id>/META-INF/academy-datagen-hashes.json`

DataGen 不写入源码目录；需要 parity 时运行 `compareDatagenParityManifests` 比较 manifest，而不是提交生成残留。
