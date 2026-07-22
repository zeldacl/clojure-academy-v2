# 项目布局

当前项目使用核心工程 + 目标化平台工程的布局�?
```text
api/
mcmod/
ac/

platform-src/
  common/
  minecraft/
    base/
    version/mc-1201/
  loader/
    forge/
    fabric/
  test-support/

platform-target/
platform-catalog.json
build-logic/
```

## Gradle 工程

| 工程 | 职责 |
|------|------|
| `:api` | 对外 Java API 与互操作接口�?|
| `:mcmod` | DSL、协议、生命周期、平台抽象和不依�?Minecraft 类的运行契约�?|
| `:ac` | AcademyCraft 内容与领域逻辑�?|
| `:platform` | 唯一平台工程；通过 `-PplatformTarget=<target-id>` 选择具体目标�?|

## 平台源码组件

| 目录 | 职责 |
|------|------|
| `platform-src/common/` | �?Loader 无关的平台通用代码�?|
| `platform-src/minecraft/mc-1.20.1/` | Minecraft API 通用适配�?|
| `platform-src/minecraft/mc-1.20.1/` | Minecraft 1.20.1 专属差异�?|
| `platform-src/loader/forge/` | Forge lifecycle、entrypoint、metadata、注册、client/datagen glue�?|
| `platform-src/loader/fabric/` | Fabric lifecycle、entrypoint、metadata、client/datagen glue�?|
| `platform-src/test-support/` | 平台目标测试辅助代码�?|

## 目标声明

`platform-catalog.json` 是唯一目标目录。每�?target 显式声明 loader、Minecraft version、Java version、source components、test components、capabilities、dependencies、artifact 信息�?datagen parity group�?
构建逻辑不得�?target id 字符串推导行为，也不得自动生�?Loader × Minecraft 版本的笛卡尔组合�?
## 依赖边界

- `mcmod` �?`ac` 不引�?`net.minecraft.*`、Forge、Fabric �?NeoForge API�?- `platform-src/minecraft/*` 可以引用 Minecraft API，但不能枚举 Loader�?- `platform-src/loader/*` 只承载对�?Loader 的生命周期、注册和入口 glue�?- Loader Java entrypoint、client/datagen entrypoint、metadata 是外部框架要求，允许保留；内部转�?namespace、单调用封装和双轨实现不保留�?
## 常用命令

```powershell
.\gradlew.bat verifyCurrentPlatforms
.\gradlew.bat :platform:compileClojure "-PplatformTarget=forge-1.20.1"
.\gradlew.bat :platform:compileClojure "-PplatformTarget=fabric-1.20.1"
```
