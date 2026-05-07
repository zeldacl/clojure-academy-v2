# 目标架构

## Purpose

定义本仓库面向多 Loader / 多 Minecraft 版本的目标架构，作为后续新增 `NeoForge`、恢复 `Fabric`、或升级至新 Minecraft 版本时的统一设计基线。

## 目标分层

```text
api
  ↓
mcmod
  ↓
ac
  ↓
loader-version module (forge-1.20.1 / fabric-1.20.1 / neoforge-1.20.1 / ...)
```

### `api`

职责：

- 对外暴露稳定 Java 接口与 SPI。
- 供第三方 mod 或平台桥接使用。
- 不承载任何具体 Loader / Minecraft 运行时逻辑。

### `mcmod`

职责：

- 平台无关协议、DSL、元数据、注册表、共享运行时。
- 提供 `PlatformBootstrap` / `ContentInitBootstrap` 所依赖的共享机制。
- 承载跨平台共享的网络消息目录、生命周期、抽象数据模型。

### `ac`

职责：

- 业务内容层。
- 能力系统、无线系统、GUI 业务、内容 DSL 使用方。
- 只能依赖 `api` / `mcmod`，不能直接依赖任何 Loader 或 Minecraft API。

### 平台模块

职责：

- 入口类、资源描述文件、Loader 事件接线。
- 对 Minecraft 原生对象做协议扩展或动态桥接安装。
- 实现 registry / network / GUI / datagen / config / client init 的平台适配。
- 将 `mcmod` / `ac` 的共享内容装入特定 Loader 生命周期中。

## 复用原则

### 应复用的部分

- `api` 中的公开接口。
- `mcmod` 中的协议、DSL、元数据、共享运行时。
- `ac` 中的业务逻辑与内容定义。

### 应按平台或版本分化的部分

- Loader 入口类。
- 平台对象协议安装（NBT、Position、ItemStack、World、PoseStack 等）。
- registry / network / GUI / datagen / config 桥接。
- 运行任务与构建参数。

## 设计原则

1. **业务不回流**：平台模块不得把具体 Loader 逻辑回流到 `ac` / `mcmod`。
2. **版本差异局部化**：与 Minecraft 版本变化相关的适配逻辑集中在对应的平台模块中。
3. **入口最小化**：Java 入口类只负责把控制权交给 Clojure 命名空间。
4. **平台契约显式化**：新增平台必须有完整的文件骨架、生命周期说明和验证清单。
5. **验证先于宣称支持**：文档声称支持的平台，必须纳入相应的编译或运行验证链。

## 当前到目标状态的差距

- `forge-1.20.1` 已形成主线实现。
- `fabric-1.20.1` 存在代码，但默认不参与根构建，存在漂移风险。
- `neoforge-*` 尚无正式模块模板。
- 构建层的 AOT/remap/sourceSets 注入逻辑仍集中在 `forge-1.20.1/build.gradle`，尚未抽象为可复用 convention。
