# 平台接入契约

## Purpose

定义“一个新平台模块要被仓库接受，最少必须实现什么”。本契约覆盖平台启动、共享内容装配、关键分发点和最小文件集合。

## 核心契约对象

### `PlatformBootstrap`

职责：

- 提供稳定 `platformId()`。
- 在 `initialize()` 中安装该平台所需的一次性桥接逻辑。
- 由 `PlatformBootstraps.initialize(platformId)` 通过 Java `ServiceLoader` 发现并调用。

当前现状：

- `api` 和 `mcmod` 下各有一份 `PlatformBootstrap` / `PlatformBootstraps` 定义。
- 后续如要扩字段，必须同步维护两处，避免契约漂移。

### `ContentInitBootstrap`

职责：

- 由内容模块提供内容注册钩子。
- 把 `ac` 等内容模块接入共享生命周期。
- 平台模块不直接硬编码每个业务细节，而是驱动共享内容初始化流程。

### `*platform-version*`

职责：

- 作为 `mcmod` 中关键平台分发点的动态上下文。
- 用于 registry、network 等 `defmulti` 的平台区分。

### 关键平台分发点

至少应梳理并检查以下类型的分发点：

- 网络发送 / 接收
- registry 注册
- GUI 打开 / handler 注册
- 平台对象协议桥接
- datagen 注册

## 新平台最小实现清单

一个新平台模块至少应具备：

1. 一个 Loader 识别的 Java 入口。
2. 一个能被共享层识别的 `platformId`。
3. 一个平台初始化门面（通常为 `platform/bootstrap-entry:init-platform!`）。
4. 一个真正安装平台桥接的实现（通常为 `platform/spi-bootstrap` 或等价 namespace）。
5. registry 平台实现。
6. network 平台实现。
7. GUI / menu / screen 平台桥接。
8. datagen 入口与注册逻辑。
9. client-only 初始化入口。
10. 至少 compile 级验证任务。

## 推荐启动顺序

1. Loader 调用 Java 入口。
2. Java 入口 `require` 主 Clojure mod namespace。
3. 主 mod namespace 首先执行平台初始化。
4. 平台初始化安装平台协议、工厂函数和运行时桥接。
5. 然后再执行共享层初始化：`init-from-java` / `core/init` / 内容注册。
6. 最后接 Loader 侧 registry、event、GUI、config、client init、datagen 等流程。

## 命名规则

- 平台 ID：`forge-1.20.1`、`fabric-1.20.1`、`neoforge-1.20.1`
- Java 包：`cn.li.forge1201`、`cn.li.fabric1201`、`cn.li.neoforge1201`
- Clojure namespace：与 Java 包保持一一映射风格

禁止：

- Java 包、Clojure namespace、平台 ID 三套命名各玩各的。
- 混用 `com.example.*` / `my_mod.*` / `cn.li.*` 作为正式长期命名。
