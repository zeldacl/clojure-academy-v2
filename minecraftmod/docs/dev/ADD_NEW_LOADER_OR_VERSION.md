# Add New Loader Or Version

将新平台或新 Minecraft 版本接入本仓库时，按本文档执行，避免遗漏平台契约、构建步骤和验证动作。

## 适用范围

- 新增 `NeoForge` 平台
- 恢复/规范化 `Fabric` 平台
- 为现有 Loader 增加新 Minecraft 版本模块

## 步骤总览

1. 新建 Gradle 子模块目录。
2. 复制最近的同类平台模块骨架。
3. 修改模块名、平台 ID、包名、命名空间。
4. 接入 Java 入口与资源描述文件。
5. 接入平台 bootstrap、registry、network、GUI、config、datagen。
6. 接入构建任务与最小验证任务。
7. 更新文档与验证矩阵。

## Step 1. 新建模块

推荐命名：

- `neoforge-1.20.1`
- `fabric-1.21.x`
- `forge-1.21.x`

同时更新：

- 根 `settings.gradle`
- 版本相关 `gradle.properties`（若适用）
- 根构建或 CI 中的任务定义（若需要）

## Step 2. 复制平台骨架

### 新增 NeoForge

优先复制：

- `forge-1.20.1/`

保留结构，替换：

- Loader 依赖
- 事件总线 / 网络 API
- 资源描述文件格式
- 平台包名与 namespace

### 规范化 Fabric

优先参考：

- `fabric-1.20.1/`
- 但需要统一命名，不再保留 `com.example.*` 作为正式模板。

## 分平台执行清单

### NeoForge 接入清单

- [ ] 新建模块目录：`neoforge-<mc-version>`。
- [ ] 在根 `settings.gradle` 中增加 `include 'neoforge-<mc-version>'`。
- [ ] 复制 `forge-1.20.1/build.gradle` 作为初始模板，并替换为 NeoForge 依赖与运行配置。
- [ ] 创建 Java 入口：`src/main/java/cn/li/neoforge<version>/MyModNeoForge.java`。
- [ ] 创建 SPI 实现：`platform/spi/NeoForge<version>PlatformBootstrap.java`。
- [ ] 创建资源文件：`META-INF/neoforge.mods.toml`。
- [ ] 创建 ServiceLoader 文件：`META-INF/services/cn.li.mcmod.platform.spi.PlatformBootstrap`。
- [ ] 创建 Clojure 主入口：`cn.li.neoforge<version>.mod`。
- [ ] 创建平台门面：`cn.li.neoforge<version>.platform.bootstrap-entry`。
- [ ] 创建真实平台安装层：`cn.li.neoforge<version>.platform.spi-bootstrap`。
- [ ] 实现 `registry.clj`、`events.clj`、`gui/init.clj`、`gui/network.clj`、`client/init.clj`、`config/bridge.clj`、`datagen/setup.clj`。
- [ ] 接入 `runClient` / `runServer` / `runData`。
- [ ] 至少通过 compile + smoke + datagen 验证。

### Fabric 规范化清单

- [ ] 保留模块目录：`fabric-<mc-version>`。
- [ ] 决定是否纳入根 `settings.gradle` 默认 include；若不纳入，文档中标注 `experimental`。
- [ ] 将 Java 包统一到 `cn.li.fabric<version>`，移除 `com.example.*` 作为正式入口。
- [ ] 将 Clojure namespace 统一到 `cn.li.fabric<version>.*`，逐步淘汰历史 `my_mod.fabric*` 入口。
- [ ] 校正 `fabric.mod.json` 中的 `main` / `client` / `fabric-datagen` entrypoints。
- [ ] 确认 `mod.clj` 中初始化顺序为：`platform-bootstrap/init-platform!` → 共享初始化 → registry / GUI / events / config。
- [ ] 确认 `ClientModInitializer` 只负责 client-only init。
- [ ] 确认 datagen Java 入口只负责桥接到 `datagen/setup.clj`。
- [ ] 至少通过 compile + datagen；若标记为正式支持，再补 smoke 验证。

### 新 Minecraft 版本升级清单

- [ ] 复制最近的同 Loader 模块，例如 `forge-1.20.1` → `forge-1.21.x`。
- [ ] 更新版本号、mappings、Loader 依赖。
- [ ] 校正平台 ID、Java 包、Clojure namespace。
- [ ] 优先修复 registry / network / GUI / datagen 的 API 漂移。
- [ ] 运行 compile、smoke、datagen 验证。
- [ ] 若旧版本仍保留，确保新旧版本文档与验证矩阵均明确列出状态。

## Step 3. 统一命名

必须统一的命名元素：

- 模块目录名
- `platformId`
- Java 包名
- Clojure namespace
- 资源入口声明（如 `fabric.mod.json` / `mods.toml` / `neoforge.mods.toml`）

示例：

- 模块：`neoforge-1.20.1`
- `platformId`：`neoforge-1.20.1`
- Java 包：`cn.li.neoforge1201`
- Clojure namespace：`cn.li.neoforge1201.*`

## Step 4. 补齐最小文件集合

### 所有平台至少需要

- `build.gradle`
- Loader 入口 Java 类
- 平台 bootstrap SPI 实现（若沿用 ServiceLoader）
- `mod.clj`
- `platform/bootstrap_entry.clj`
- `platform/spi_bootstrap.clj`
- `registry.clj`
- `events.clj`
- `gui/init.clj`
- `client/init.clj`
- `config/bridge.clj`
- `datagen/setup.clj`

### Forge / NeoForge 额外关注

- `META-INF/mods.toml` / `neoforge.mods.toml`
- 运行任务：`runClient` / `runServer` / `runData`
- GameTest 挂点（若计划纳入正式验证）

### Fabric 额外关注

- `fabric.mod.json`
- 三入口：`main` / `client` / `fabric-datagen`

## Step 5. 初始化顺序

主初始化链建议固定为：

1. Java 入口加载主 Clojure namespace。
2. 显式命名的 `start-<loader>-mod!` 首先执行 `platform-bootstrap/init-platform!`。
3. 安装平台对象协议、工厂函数与共享运行时桥接。
4. 再执行共享层初始化：`init-from-java` / `core/init`。
5. 最后完成 registry、config、events、GUI common/server init。

### 建议按文件落地

1. 先写 Java 入口，只保留 `require` + 调用主函数。
2. 再写 `platform/bootstrap_entry.clj`，先确保 `platformId` 可以触发平台 bootstrap。
3. 再写 `platform/spi_bootstrap.clj`，先安装最基础的 NBT / position / item / world 桥接。
4. 然后写 `mod.clj`，把初始化顺序串起来。
5. 再补 `registry.clj`、`events.clj`、`gui/*`、`client/init.clj`、`datagen/setup.clj`。
6. 最后才接入配置、集成、优化与额外平台特性。

## Step 6. 最小验证

每个新增平台模块至少应通过：

- compile 级：`compileJava`、`compileClojure`
- 启动级：`runClient`、`runServer`
- datagen 级：`runData` 或等价 datagen 入口
- 边界级：共享层静态引用扫描、client/server 边界检查

Forge / NeoForge 推荐额外纳入：

- GameTest 或等价集成验证

### 可直接执行的验证顺序

1. 先跑 `:module:compileJava`。
2. 再跑 `:module:compileClojure`。
3. 再跑 `:module:runData` 或等价 datagen 任务。
4. 再跑 `:module:runServer`，确认 dedicated server 不因 client 类泄漏而失败。
5. 最后跑 `:module:runClient`，确认入口和 client init 能正常装载。

若第 2 步失败，优先检查：

- Java 包与 Clojure namespace 是否一致；
- `platformId` 是否与 SPI / platform dispatch 对齐；
- sourceSets / AOT 输出是否已纳入运行 classpath。

## Step 7. 文档同步

新增平台或版本后，必须同步更新：

- `docs/README.md`
- `docs/02-architecture/platform-expansion/*`
- `docs/testing/MULTI_LOADER_VERIFICATION.md`
- 本文档

## 完成标准

只有同时满足以下条件，平台才可被视为“已接入”：

1. 模块可编译。
2. 入口链路可执行。
3. datagen 可运行。
4. 文档明确其支持状态。
5. 验证矩阵中有对应条目。

## 交付前最终检查

- [ ] 模块目录、平台 ID、Java 包、Clojure namespace 全部一致。
- [ ] Loader 描述文件中的入口类可被实际找到。
- [ ] `platform-bootstrap/init-platform!` 在主初始化链中位于共享初始化之前。
- [ ] network / GUI / datagen 至少都有占位实现，而不是只留 TODO。
- [ ] `docs/README.md`、验证矩阵、平台扩展文档已同步。
