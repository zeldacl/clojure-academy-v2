# Platform Module Template

本文件给出平台模块的标准骨架，供 `Forge` / `NeoForge` / `Fabric` 新模块复用。

## 模板目标

- 统一平台模块目录和命名风格。
- 统一主初始化、client init、datagen init 的落点。
- 统一平台桥接的最小文件集合。

## 目录模板

```text
<loader>-<mc-version>/
├── build.gradle
├── src/main/java/cn/li/<loaderVersion>/
│   ├── <MainEntry>.java
│   ├── <ClientEntry>.java           # 若该 Loader 需要单独 client Java 入口
│   ├── datagen/
│   │   └── DataGeneratorSetup.java
│   └── platform/spi/
│       └── <PlatformBootstrapImpl>.java
├── src/main/clojure/cn/li/<loaderVersion>/
│   ├── mod.clj
│   ├── init.clj
│   ├── platform/
│   │   ├── bootstrap_entry.clj
│   │   └── spi_bootstrap.clj       # 若采用门面 + 真实安装分层
│   ├── registry.clj
│   ├── events.clj
│   ├── side.clj                     # Forge / NeoForge 推荐
│   ├── config/bridge.clj
│   ├── gui/
│   │   ├── init.clj
│   │   ├── network.clj
│   │   └── registry_impl.clj
│   ├── client/
│   │   └── init.clj
│   └── datagen/
│       └── setup.clj
└── src/main/resources/
    └── Loader-specific descriptor files
```

## 逐文件职责表

| 文件 | 必须程度 | 主要职责 | 允许依赖 | 禁止事项 |
|------|----------|----------|----------|----------|
| `build.gradle` | 必须 | 平台依赖、run 任务、AOT/remap 规则 | Gradle、Loader plugin | 把业务逻辑写入构建脚本 |
| `<MainEntry>.java` | 必须 | Loader 主入口，桥接到 Clojure | Loader API、Clojure runtime | 承载复杂业务逻辑 |
| `<ClientEntry>.java` | 视 Loader 而定 | client-only Java 入口 | Loader client API、Clojure runtime | 在 common 入口中替代它 |
| `DataGeneratorSetup.java` | 推荐/必须 | datagen Java 桥 | Loader datagen API | 直接重写共享生成逻辑 |
| `platform/spi/<PlatformBootstrapImpl>.java` | 若沿用 SPI 则必须 | ServiceLoader 发现与平台安装入口 | Java SPI、Clojure runtime | 直接承载平台业务初始化 |
| `mod.clj` | 必须 | 平台主初始化排序与总装配 | 平台桥接、共享层入口 | 在 namespace load 时做过重副作用 |
| `init.clj` | 推荐 | Java/Clojure 之间的轻量桥接 | 共享初始化函数 | 与 `mod.clj` 职责混杂 |
| `platform/bootstrap_entry.clj` | 必须 | 平台初始化门面 | SPI、日志 | 直接写所有平台对象扩展 |
| `platform/spi_bootstrap.clj` | 推荐/常用 | 安装平台对象协议与工厂函数 | Minecraft/Loader API | 暴露为共享层 API |
| `registry.clj` | 必须 | metadata → 平台 registry API | 平台注册 API、共享元数据 | 写业务定义 |
| `events.clj` | 必须 | 平台事件接线 | Loader 事件 API | 在共享层复制事件分发 |
| `side.clj` | Forge/NeoForge 推荐 | 物理侧识别与 client-only 解析 | Loader side API | 承载具体渲染业务 |
| `config/bridge.clj` | 推荐 | 平台配置系统 ↔ 共享配置 | Loader config API | 在共享层嵌入平台配置细节 |
| `gui/init.clj` | 必须 | GUI common/server/client 注册入口 | 平台 GUI API、共享 GUI 元数据 | 把所有 GUI 代码塞进一个函数 |
| `gui/network.clj` | 推荐 | GUI 相关网络桥接 | 平台网络 API | 重写共享消息语义 |
| `gui/registry_impl.clj` | 推荐 | menu/screen handler 平台实现 | 平台 GUI registry API | 写 client-only 渲染逻辑 |
| `client/init.clj` | 必须（有客户端功能时） | renderer、screen、client packet、FX 注册 | client-only API | 被 dedicated server 路径加载 |
| `datagen/setup.clj` | 必须（支持 datagen 时） | DataProvider 注册与桥接 | 平台 datagen API、共享 provider | 依赖 client-only 类 |

### Java 入口

职责：

- 被 Loader 发现。
- `require` 主 Clojure namespace。
- 调用最小入口函数。

约束：

- 不承载复杂业务逻辑。
- 不在 Java 入口中复制共享层逻辑。

### `mod.clj`

职责：

- 作为平台主初始化入口。
- 排序并触发平台初始化、共享内容初始化、registry、config、events、GUI common/server init。

### `platform/bootstrap_entry.clj`

职责：

- 作为平台初始化门面。
- 负责按 `platformId` 触发平台 bootstrap。

### `platform/spi_bootstrap.clj`

职责：

- 安装平台对象协议、工厂函数、运行时桥接。
- 尽量避免在 namespace load 时产生过重副作用。

### `registry.clj`

职责：

- 把共享层元数据与 Loader 平台注册 API 对接。

### `gui/*`

职责：

- common/server/client 不同阶段的 GUI 绑定。
- menu/screen 注册、打开与网络桥接。

### `client/init.clj`

职责：

- client-only 渲染、screen、renderer、client packets、特效注册。

### `datagen/setup.clj`

职责：

- 将共享层 provider 或生成逻辑挂到 Loader 的 datagen 生命周期上。

## 建议的最小落地顺序

1. `build.gradle`
2. `<MainEntry>.java`
3. `platform/spi/<PlatformBootstrapImpl>.java`
4. `platform/bootstrap_entry.clj`
5. `platform/spi_bootstrap.clj`
6. `mod.clj`
7. `registry.clj`
8. `events.clj`
9. `gui/*`
10. `client/init.clj`
11. `datagen/setup.clj`

这样安排的原因是：先保证平台能被发现，再保证共享层能启动，再补平台功能分支，最后补 client 与 datagen。

## NeoForge 建议实例

### 推荐文件名

- `src/main/java/cn/li/neoforge1201/MyModNeoForge.java`
- `src/main/java/cn/li/neoforge1201/platform/spi/NeoForge1201PlatformBootstrap.java`
- `src/main/clojure/cn/li/neoforge1201/mod.clj`
- `src/main/clojure/cn/li/neoforge1201/platform/bootstrap_entry.clj`
- `src/main/clojure/cn/li/neoforge1201/platform/spi_bootstrap.clj`

### 推荐首批可运行目标

- Java 入口能加载 `mod.clj`
- `mod.clj` 能调用 `platform-bootstrap/init-platform!`
- `platform-bootstrap/init-platform!` 能通过 SPI 找到 `NeoForge1201PlatformBootstrap`
- `compileJava`、`compileClojure`、`runData` 可以先打通

## Fabric 规范化建议实例

### 首批需要统一的点

- Java 包名统一为 `cn.li.fabric1201`
- `fabric.mod.json` entrypoints 与 Java 包同步
- Clojure 主入口统一到 `cn.li.fabric1201.mod`
- client 与 datagen 入口都指向 `cn.li.fabric1201.*`

### 首批可运行目标

- `fabric.mod.json` 三入口可以找到对应 Java 类
- Java 类可以桥接到统一命名空间的 Clojure 入口
- `compileJava`、`compileClojure`、`runData` 先打通

## 命名规则

- Java 包与 Clojure namespace 一致。
- `platformId` 与模块目录一致。
- Loader 描述文件里的入口类必须与实际 Java 包同步。

## 模板使用建议

- 新增 `NeoForge` 时，以当前 `forge-1.20.1` 为一号模板。
- 整理 `Fabric` 时，以现有 `fabric-1.20.1` 为素材，但统一到本模板命名规范。
- 任何偏离模板的结构都应有文档解释原因。
