# Loader 生命周期映射

## Purpose

把不同 Loader 下的主初始化、客户端初始化与 datagen 初始化流程映射到统一抽象上，便于新增平台或升级版本时复用思路而不是重新推导生命周期。

## 统一抽象

每个平台都应覆盖三条链：

1. **main/common**：共享内容初始化、平台对象桥接、registry、事件、配置。
2. **client**：screen 注册、renderer 注册、client packet、client-only FX。
3. **datagen**：DataProvider 注册、生成资源入口。

## Forge 1.20.1（当前主线）

### Main/Common

1. Forge 发现 `MyMod1201`。
2. Java 入口 `require` `cn.li.forge1201.mod`。
3. 调用 `mod-init`。
4. `mod-init` 内先完成平台初始化与共享生命周期装配。
5. 完成 registry、事件、配置、内容注册。

#### 对应文件顺序

1. `forge-1.20.1/src/main/java/cn/li/forge1201/MyMod1201.java`
2. `forge-1.20.1/src/main/clojure/cn/li/forge1201/mod.clj`
3. `forge-1.20.1/src/main/clojure/cn/li/forge1201/platform/bootstrap_entry.clj`
4. `forge-1.20.1/src/main/java/cn/li/forge1201/platform/spi/Forge1201PlatformBootstrap.java`
5. `forge-1.20.1/src/main/clojure/cn/li/forge1201/platform/spi_bootstrap.clj`
6. `forge-1.20.1/src/main/clojure/cn/li/forge1201/registry.clj` / `events.clj` / `gui/*` / `config/*`

### Client

- 由 Forge client lifecycle 事件驱动。
- 应通过 side-checked 路径调用 client-only namespace。

#### 执行要点

- client setup 中只解析 `client/*` 或经 `side` 包装过的入口。
- 不要在 `mod.clj` namespace load 阶段触发 client 类加载。

### Datagen

- 由 `runData` 路径驱动。
- 需保证 datagen 下也能完成必要的平台注册状态初始化。

#### 执行要点

- datagen 运行前，确保平台 bridge 与共享 metadata 已可用。
- datagen 不应依赖 client-only 初始化副作用。

## NeoForge（目标模板）

### Main/Common

建议与 Forge 对称：

1. NeoForge 发现 `MyModNeoForge`。
2. Java 入口 `require` `cn.li.neoforge1201.mod`。
3. `mod-init` 首先调用 `platform-bootstrap/init-platform!`。
4. 通过 SPI 安装平台桥接实现。
5. 再启动共享内容初始化、registry、config、events。

#### 推荐文件落点

1. `src/main/java/cn/li/neoforge1201/MyModNeoForge.java`
2. `src/main/clojure/cn/li/neoforge1201/mod.clj`
3. `src/main/clojure/cn/li/neoforge1201/platform/bootstrap_entry.clj`
4. `src/main/java/cn/li/neoforge1201/platform/spi/NeoForge1201PlatformBootstrap.java`
5. `src/main/clojure/cn/li/neoforge1201/platform/spi_bootstrap.clj`
6. `src/main/clojure/cn/li/neoforge1201/registry.clj`
7. `src/main/clojure/cn/li/neoforge1201/events.clj`
8. `src/main/clojure/cn/li/neoforge1201/gui/init.clj`
9. `src/main/clojure/cn/li/neoforge1201/client/init.clj`
10. `src/main/clojure/cn/li/neoforge1201/datagen/setup.clj`

### Client

- 独立 client init。
- 所有 client-only 逻辑集中在 `client/*` 和 GUI client 初始化中。

#### 执行要点

- 沿用 Forge 的 side-check 思路。
- 优先保证 renderer / screen / client packet 的注册入口集中。

### Datagen

- 独立 datagen 入口。
- 尽量与 Forge 风格保持对称，降低迁移难度。

#### 建议顺序

1. 先打通 compile。
2. 再让 datagen 入口可运行。
3. 再补 client 与 server smoke。

## Fabric（当前可选实现 + 规范化目标）

### Main/Common

1. `fabric.mod.json` `entrypoints.main` 指向 Java `ModInitializer`。
2. Java 入口 `require` `cn.li.fabric1201.mod`。
3. `mod-init` 先执行 `platform-bootstrap/init-platform!`。
4. 再执行共享初始化、registry、events、GUI common/server init、config load。

#### 对应文件顺序

1. `fabric.mod.json`
2. `src/main/java/cn/li/fabric1201/MyModFabric.java`（目标规范）
3. `src/main/clojure/cn/li/fabric1201/mod.clj`
4. `src/main/clojure/cn/li/fabric1201/platform/bootstrap_entry.clj`
5. `src/main/clojure/cn/li/fabric1201/registry.clj` / `events.clj` / `gui/init.clj`

### Client

1. `fabric.mod.json` `entrypoints.client` 指向 `ClientModInitializer`。
2. Java 入口调用 GUI client init 与 client-only init。
3. 完成 screen、renderer、client packets、客户端特效注册。

#### 执行要点

- `ClientModInitializer` 只桥接到 client-only Clojure 入口。
- main 入口禁止顺手装载 client namespace。

### Datagen

1. `fabric.mod.json` `entrypoints.fabric-datagen` 指向 datagen 入口。
2. Java datagen 入口把控制权交给 `cn.li.fabric1201.datagen.setup/register-data-generators!`。

#### 执行要点

- datagen Java 入口保持极薄。
- datagen Clojure 入口只负责 provider 注册，不复制业务规则。

## 可直接执行的实施顺序

1. 先确定模块目录名、平台 ID、Java 包、Clojure namespace。
2. 再写主入口链（Java main → `mod.clj` → `platform-bootstrap/init-platform!`）。
3. 再写 client 入口链。
4. 再写 datagen 入口链。
5. 最后把 registry、events、GUI、config、network 的具体桥接填满。

## 生命周期完成定义

一个平台模块只有在以下三条链都被实际跑通后，生命周期文档才能视为完成：

- main/common 能启动并完成共享内容装配；
- client 能注册 screen / renderer / client packet；
- datagen 能完成 provider 注册并运行。

## 设计要求

- 三条链必须文档化，不能只有 main 入口。
- 不同 Loader 的差异应体现在入口与桥接层，不能扩散到共享业务层。
- 新平台进入仓库前，必须画清楚这三条链各自对应的文件与职责。
