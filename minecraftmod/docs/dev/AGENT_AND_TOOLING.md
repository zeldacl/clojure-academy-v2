# Agent 与工具链约定

本文是仓库内 Agent/开发辅助工具的当前规则。所有平台构建都通过单一 `:platform` 工程完成，loader/version 只由 `platform-targets.json` 与 `-PplatformTarget=<target-id>` 决定。

## 当前工程结构

- `api`：Java API 与边界契约。
- `mcmod`：平台无关运行框架。
- `ac`：内容层。
- `platform-src/common`：平台 target 共享启动与 glue。
- `platform-src/minecraft/version/mc-1201`：Minecraft 1.20.1 API 适配。
- `platform-src/loader/forge`：Forge loader 入口、metadata、事件绑定。
- `platform-src/loader/fabric`：Fabric loader 入口、metadata、事件绑定。
- `platform-target`：唯一 Gradle 平台工程。

禁止恢复旧根目录、旧 SPI、旧任务 alias、旧 namespace forwarding 或双轨实现。

## 常用命令

- 架构门禁：`cmd /c .\gradlew.bat verifyCurrentPlatforms --stacktrace`
- Forge 编译：`cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1" --stacktrace`
- Fabric 编译：`cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1" --stacktrace`
- Forge 客户端：`cmd /c .\gradlew.bat :platform:runClient "-PplatformTarget=forge-1.20.1"`
- Fabric 客户端：`cmd /c .\gradlew.bat :platform:runClient "-PplatformTarget=fabric-1.20.1"`

Windows 下 `-PplatformTarget=...` 建议整体加引号，避免 `cmd`/PowerShell 组合把带点号的值截断。

## 架构规则

- 不从 target id 字符串解析 loader/version；只读 catalog model。
- Minecraft 层不得枚举 Forge/Fabric；Loader 生命周期逻辑只属于 loader component。
- `ac` 与 `mcmod` 不依赖 Minecraft/Forge/Fabric API。
- Loader Java entrypoint、client/datagen entrypoint、metadata 是允许存在的外部框架薄入口；内部薄封装、forwarding namespace 和兼容 wrapper 不保留。
- DataGen 输出写入 `platform-target/build/generated/datagen/<target-id>/`，不写回源码目录。
- 不创建真实 future loader target、依赖、源码或发布产物；需要验证扩展性时使用 synthetic fixture 或 catalog/sourceSet/capability 测试。

## 必跑门禁

`verifyCurrentPlatforms` 聚合以下检查：

- `verifyNoLegacyArchitecture`
- `verifyNoThinForwarders`
- `verifyNoDuplicateCapabilities`
- `verifyNoUnusedNamespaces`
- `verifyNoTargetHardcoding`
- `verifyRepositoryHygiene`
