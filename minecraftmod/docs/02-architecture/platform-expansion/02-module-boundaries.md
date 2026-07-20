# 模块边界

## Purpose

把当前仓库在 `api`、`mcmod`、`ac`、平台模块之间已经存在的边界约束提升为正式架构规则，供后续扩平台、升级版本和代码评审统一使用。

## 依赖方向

允许的主依赖链：

- `api` → 无上游业务依赖
- `mcmod` → `api`
- `ac` → `api`、`mcmod`
- 平台模块 → `api`、`mcmod`、`ac`、Loader/Minecraft API

禁止的反向依赖：

- `ac` → 任意 `forge*` / `fabric*` / `future-loader*`
- `mcmod` → 任意 Loader / Minecraft API
- `api` → `mcmod` / `ac` / 平台模块

## 各模块红线

### `api`

- 只保留稳定接口、SPI、必要注解。
- 不引入 Clojure 运行时依赖到发布面。
- 不感知具体平台实现细节。

### `mcmod`

- 不引入 `net.minecraft.*`、`net.minecraftforge.*`、`net.fabricmc.*`、`net.future-loaderd.*`。
- 不静态依赖 `cn.li.forge1201.*`、`cn.li.fabric1201.*`、`cn.li.future-loader*.*`。
- 允许持有平台抽象入口、协议、multimethod 分发点和共享生命周期。

### `ac`

- 不引入任何 Loader / Minecraft 原生 API。
- 不依赖任何具体平台命名空间。
- 所有平台差异通过 `mcmod` 提供的共享协议或生命周期来吸收。

### 平台模块

- 可以使用 Loader / Minecraft API。
- 不得把平台实现细节扩散为共享 API。
- 应尽量通过桥接函数、平台初始化和最小 Java 入口把耦合控制在模块内部。

## 跨层调用规则

### 允许

- 平台模块通过受控入口调用 `ac` / `mcmod` 的初始化函数。
- 平台模块通过 ServiceLoader 或桥接类触发 `platform target bootstrap` / `ContentInitBootstrap`。
- `mcmod` 通过动态分发或平台注册点调用平台实现。

### 不允许

- `ac` 直接 `require` 平台 namespace。
- `mcmod` 直接 `extend-type` 到 Minecraft 原生类。
- 在共享层把版本判断散落到业务逻辑中。

## 代码审查检查项

1. 新增共享代码时，确认未引入 Loader / Minecraft API。
2. 新增平台功能时，确认没有通过共享层偷渡平台依赖。
3. 新增版本模块时，确认其差异主要位于平台目录与构建配置，而非 `ac` / `mcmod`。
4. 若需要新增跨层入口，必须同步更新平台接入契约文档。
