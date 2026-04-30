# Clojure Minecraft Mod（Gradle 多模块）

以 Clojure 为主、Forge **1.20.1** 为当前默认交付目标的 Minecraft Mod。平台无关逻辑在 **`mcmod`** 与 **`ac`** 中；**`forge-1.20.1`** 为 Forge 适配层。`fabric-1.20.1` 目录可保留适配代码，但根目录 [`settings.gradle`](settings.gradle) 中 **`include 'fabric-1.20.1'` 默认注释**，不参与根工程构建。

## 文档

**完整说明、架构与 DSL 指南见 [docs/README.md](docs/README.md)。**

## 常用命令（在 `minecraftmod` 目录执行）

```powershell
# 运行 Forge 开发客户端
.\gradlew.bat :forge-1.20.1:runClient

# 仅编译 Clojure（快速迭代）
.\gradlew.bat :ac:compileClojure
.\gradlew.bat :mcmod:compileClojure

# 数据生成（输出见 forge 子工程配置）
.\gradlew.bat :forge-1.20.1:runData

# 完整构建
.\gradlew.bat build
```

需要 **Java 17**。构建产物位于各子模块的 `build/libs/`（具体 JAR 名随 Gradle `archivesName` / 版本配置而定）。
